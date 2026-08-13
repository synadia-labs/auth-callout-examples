package io.synadia.authcallout.server;

import io.nats.client.Connection;
import io.nats.jwt.AuthorizationRequest;
import io.nats.jwt.AuthorizationResponse;
import io.nats.jwt.Claim;
import io.nats.jwt.ClaimIssuer;
import io.nats.jwt.Permission;
import io.nats.jwt.UserClaim;
import io.nats.nkey.NKey;
import io.nats.service.Endpoint;
import io.nats.service.Service;
import io.nats.service.ServiceBuilder;
import io.nats.service.ServiceEndpoint;
import io.nats.service.ServiceMessage;
import io.nats.service.ServiceMessageHandler;
import io.synadia.authcallout.model.AuthResult;
import io.synadia.authcallout.server.handler.OktaAuthHandler;
import io.synadia.authcallout.server.handler.PasswordAuthHandler;
import io.synadia.authcallout.server.handler.TokenAuthHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.nats.jwt.JwtUtils.getClaimBody;

/**
 * AuthCallout responder service.
 *
 * Listens on {@code $SYS.REQ.USER.AUTH} via the NATS Micro API.
 * Delegates credential validation to the configured handlers:
 *
 *   username/password → {@link PasswordAuthHandler} (KV Store, MySQL, or Okta ROPC)
 *   auth token        → {@link TokenAuthHandler}    (Okta introspection)
 *
 * Responds with a signed {@code AuthorizationResponse} JWT using the
 * account NKey seed configured in {@code auth.signing.key.seed}.
 */
public class AuthCalloutService implements ServiceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthCalloutService.class);

    static final String AUTH_CALLOUT_SUBJECT = "$SYS.REQ.USER.AUTH";
    static final String SERVICE_NAME = "AuthCalloutService";
    static final String SERVICE_VERSION = "1.0.0";
    static final String SERVICE_DESCRIPTION =
            "NATS AuthCallout responder — validates client credentials and issues signed JWTs";

    private final Connection nc;
    private final PasswordAuthHandler passwordHandler;
    private final TokenAuthHandler tokenHandler;
    private final NKey signingKey;
    private final String signingKeyPublic;
    private final String defaultAccount;
    private final String passwordBackend;
    private final boolean tokenAuthEnabled;
    private final CurveKeyHelper xkey;

    private Service service;
    private CompletableFuture<Boolean> serviceStoppedFuture;

    public AuthCalloutService(Connection nc,
                               PasswordAuthHandler passwordHandler,
                               TokenAuthHandler tokenHandler,
                               String signingKeySeed,
                               String defaultAccount,
                               String passwordBackend,
                               String xkeySeed) throws Exception {
        this.nc = nc;
        this.passwordHandler = passwordHandler;
        this.tokenHandler = tokenHandler;
        this.defaultAccount = defaultAccount;
        this.passwordBackend = passwordBackend;
        this.tokenAuthEnabled = tokenHandler != null;
        this.signingKey = NKey.fromSeed(signingKeySeed.toCharArray());
        this.signingKeyPublic = new String(signingKey.getPublicKey());
        this.xkey = xkeySeed != null ? new CurveKeyHelper(xkeySeed) : null;
        String xkeyPublic = this.xkey != null ? this.xkey.getPublicKeyNKey() : "disabled";
        log.info("AuthCalloutService initialised — account: {}, backend: {}, tokenAuth: {}, xkey: {}, signingKey: {}",
                defaultAccount, passwordBackend, tokenAuthEnabled, xkeyPublic, signingKeyPublic);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws Exception {
        Endpoint endpoint = Endpoint.builder()
                .name("AuthCalloutEndpoint")
                .subject(AUTH_CALLOUT_SUBJECT)
                .metadata(Map.of(
                        "description", "Processes NATS AuthorizationRequest and returns a signed AuthorizationResponse JWT",
                        "subject",     AUTH_CALLOUT_SUBJECT
                ))
                .build();

        ServiceEndpoint serviceEndpoint = ServiceEndpoint.builder()
                .endpoint(endpoint)
                .handler(this)
                .build();

        service = new ServiceBuilder()
                .connection(nc)
                .name(SERVICE_NAME)
                .version(SERVICE_VERSION)
                .description(SERVICE_DESCRIPTION)
                .metadata(Map.of(
                        "password.backend",  passwordBackend,
                        "token.auth.enabled", String.valueOf(tokenAuthEnabled),
                        "default.account",   defaultAccount
                ))
                .addServiceEndpoint(serviceEndpoint)
                .build();

        serviceStoppedFuture = service.startService();
        log.info("AuthCalloutService started — listening on {}", AUTH_CALLOUT_SUBJECT);
    }

    public void stop() {
        if (service != null) {
            service.stop();
            log.info("AuthCalloutService stopped");
        }
    }

    public CompletableFuture<Boolean> getServiceStoppedFuture() {
        return serviceStoppedFuture;
    }

    // ── ServiceMessageHandler ─────────────────────────────────────────────────

    @Override
    public void onMessage(ServiceMessage smsg) {
        log.debug("Received auth callout request on subject: {}", smsg.getSubject());
        try {
            byte[] payload = smsg.getData();
            if (xkey != null) {
                String serverXkeyHeader = smsg.getHeaders() != null
                        ? smsg.getHeaders().getFirst("Nats-Server-Xkey")
                        : null;
                if (serverXkeyHeader == null || serverXkeyHeader.isBlank()) {
                    log.error("xkey decryption configured but Nats-Server-Xkey header is missing");
                    return;
                }
                payload = xkey.open(payload, serverXkeyHeader);
                if (payload == null) {
                    log.error("xkey decryption failed — key mismatch or corrupted payload");
                    return;
                }
                log.debug("Decrypted auth callout payload using xkey");
            }
            Claim claim = new Claim(getClaimBody(payload));
            AuthorizationRequest ar = claim.authorizationRequest;

            if (ar == null) {
                log.warn("Received message with no AuthorizationRequest claim — ignoring");
                return;
            }

            log.debug("Auth request — serverId: {}, userNkey: {}",
                    ar.serverId != null ? ar.serverId.id : "?", ar.userNkey);

            AuthResult result = resolveAuth(ar);
            log.info("Auth result for user='{}' token='{}': {}",
                    ar.connectOpts != null ? ar.connectOpts.user : null,
                    ar.connectOpts != null ? (ar.connectOpts.authToken != null ? "[token]" : null) : null,
                    result);

            respond(smsg, ar, result);

        } catch (Exception e) {
            log.error("Unexpected error processing auth callout request", e);
        }
    }

    // ── Auth dispatch ─────────────────────────────────────────────────────────

    private AuthResult resolveAuth(AuthorizationRequest ar) {
        if (ar.connectOpts == null) {
            return AuthResult.failure("No connect options in authorization request");
        }

        if (ar.connectOpts.authToken != null && !ar.connectOpts.authToken.isBlank()) {
            // Token path → always Okta introspection
            if (tokenHandler == null) {
                return AuthResult.failure("Token authentication is not configured");
            }
            return tokenHandler.authenticateToken(ar.connectOpts.authToken);
        }

        // Username/password path → configured backend
        if (ar.connectOpts.user == null || ar.connectOpts.user.isBlank()) {
            return AuthResult.failure("No credentials provided");
        }
        if (passwordHandler == null) {
            return AuthResult.failure("Password authentication is not configured");
        }
        return passwordHandler.authenticate(ar.connectOpts.user, ar.connectOpts.pass);
    }

    // ── JWT response ──────────────────────────────────────────────────────────

    private void respond(ServiceMessage smsg, AuthorizationRequest ar, AuthResult result) {
        try {
            String responseJwt;

            if (result.isSuccess()) {
                UserClaim userClaim = result.getUserClaim() != null
                        ? result.getUserClaim()
                        : new UserClaim();

                userClaim.pub(new Permission().deny("auth.pub.test"));

                String userJwt = new ClaimIssuer()
                        .aud(result.getAccount())
                        .name(ar.connectOpts.user)
                        .iss(signingKeyPublic)
                        .sub(ar.userNkey)
                        .nats(userClaim)
                        .issueJwt(signingKey);

                log.debug("Sentinel token (userJwt): {}", userJwt);

                AuthorizationResponse authResponse = new AuthorizationResponse().jwt(userJwt);

                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id)
                        .iss(signingKeyPublic)
                        .sub(ar.userNkey)
                        .nats(authResponse)
                        .issueJwt(signingKey);

            } else {
                AuthorizationResponse authResponse = new AuthorizationResponse()
                        .error(result.getError());

                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id)
                        .iss(signingKeyPublic)
                        .sub(ar.userNkey)
                        .nats(authResponse)
                        .issueJwt(signingKey);
            }

            smsg.respond(nc, responseJwt);
            log.debug("Auth response sent — success: {}", result.isSuccess());

        } catch (Exception e) {
            log.error("Failed to send auth callout response", e);
        }
    }
}
