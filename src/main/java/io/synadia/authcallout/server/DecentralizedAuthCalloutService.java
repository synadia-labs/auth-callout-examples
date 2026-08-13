package io.synadia.authcallout.server;

import io.nats.client.Connection;
import io.nats.jwt.AuthorizationRequest;
import io.nats.jwt.AuthorizationResponse;
import io.nats.jwt.Claim;
import io.nats.jwt.ClaimIssuer;
import io.nats.jwt.UserClaim;
import io.nats.nkey.NKey;
import io.nats.service.Endpoint;
import io.nats.service.Service;
import io.nats.service.ServiceBuilder;
import io.nats.service.ServiceEndpoint;
import io.nats.service.ServiceMessage;
import io.nats.service.ServiceMessageHandler;
import io.synadia.authcallout.model.AuthResult;
import io.synadia.authcallout.server.handler.NKeyAuthHandler;
import io.synadia.authcallout.server.handler.PasswordAuthHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.nats.jwt.JwtUtils.getClaimBody;

/**
 * Decentralized AuthCallout responder service.
 *
 * Listens on {@code $SYS.REQ.USER.AUTH} via the NATS Micro API.
 * Supports two credential types depending on what the client presents:
 *
 *   username/password → {@link PasswordAuthHandler} (KV store lookup)
 *                       Used in Scenarios 1 (explicit sentinel) and 2 (default_sentinel)
 *
 *   NKey only         → {@link NKeyAuthHandler} (KV store NKey whitelist lookup)
 *                       Used in Scenario 3 (default_sentinel + NKey challenge-response)
 *
 * Routing is determined by whether {@code connectOpts.user} is present:
 *   - user present → user/pass path
 *   - user absent  → NKey path (ar.userNkey is the public key to validate)
 *
 * The signing key must be the AUTH account's signing key, configured via
 * {@code auth.decentralized.signing.key.seed}.
 */
public class DecentralizedAuthCalloutService implements ServiceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DecentralizedAuthCalloutService.class);

    static final String AUTH_CALLOUT_SUBJECT  = "$SYS.REQ.USER.AUTH";
    static final String SERVICE_NAME          = "DecentralizedAuthCalloutService";
    static final String SERVICE_VERSION       = "1.0.0";
    static final String SERVICE_DESCRIPTION   =
            "Decentralized NATS AuthCallout — validates user/pass or NKey credentials via KV store";

    private final Connection nc;
    private final PasswordAuthHandler passwordHandler;
    private final NKeyAuthHandler nkeyHandler;
    private final NKey signingKey;
    private final String signingKeyPublic;
    private final String defaultAccount;
    private final String issuerAccount;
    private final CurveKeyHelper xkey;

    private Service service;
    private CompletableFuture<Boolean> serviceStoppedFuture;

    public DecentralizedAuthCalloutService(Connection nc,
                                           PasswordAuthHandler passwordHandler,
                                           NKeyAuthHandler nkeyHandler,
                                           String signingKeySeed,
                                           String defaultAccount,
                                           String issuerAccount,
                                           String xkeySeed) throws Exception {
        this.nc = nc;
        this.passwordHandler = passwordHandler;
        this.nkeyHandler = nkeyHandler;
        this.defaultAccount = defaultAccount;
        this.issuerAccount = issuerAccount;
        this.signingKey = NKey.fromSeed(signingKeySeed.toCharArray());
        this.signingKeyPublic = new String(signingKey.getPublicKey());
        this.xkey = xkeySeed != null ? new CurveKeyHelper(xkeySeed) : null;
        String xkeyPublic = this.xkey != null ? this.xkey.getPublicKeyNKey() : "disabled";
        log.info("DecentralizedAuthCalloutService initialised — account: {}, issuerAccount: {}, xkey: {}, signingKey: {}",
                defaultAccount, issuerAccount, xkeyPublic, signingKeyPublic);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws Exception {
        Endpoint endpoint = Endpoint.builder()
                .name("DecentralizedAuthCalloutEndpoint")
                .subject(AUTH_CALLOUT_SUBJECT)
                .metadata(Map.of(
                        "description", "Processes decentralized NATS AuthorizationRequest and returns a signed AuthorizationResponse JWT",
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
                        "model",           "decentralized",
                        "default.account", defaultAccount
                ))
                .addServiceEndpoint(serviceEndpoint)
                .build();

        serviceStoppedFuture = service.startService();
        log.info("DecentralizedAuthCalloutService started — listening on {}", AUTH_CALLOUT_SUBJECT);
    }

    public void stop() {
        if (service != null) {
            service.stop();
            log.info("DecentralizedAuthCalloutService stopped");
        }
    }

    public CompletableFuture<Boolean> getServiceStoppedFuture() {
        return serviceStoppedFuture;
    }

    // ── ServiceMessageHandler ─────────────────────────────────────────────────

    @Override
    public void onMessage(ServiceMessage smsg) {
        log.debug("Received decentralized auth callout request on subject: {}", smsg.getSubject());
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

            log.debug("Decentralized auth request — serverId: {}, userNkey: {}",
                    ar.serverId != null ? ar.serverId.id : "?", ar.userNkey);

            AuthResult result = resolveAuth(ar);
            String identity = resolveIdentity(ar);
            if (result.isSuccess()) {
                log.info("Decentralized auth SUCCESS — identity='{}', account='{}'", identity, result.getAccount());
            } else {
                log.warn("Decentralized auth FAILED — identity='{}', reason='{}'", identity, result.getError());
            }

            respond(smsg, ar, result, identity);

        } catch (Exception e) {
            log.error("Unexpected error processing decentralized auth callout request", e);
        }
    }

    // ── Auth dispatch ─────────────────────────────────────────────────────────

    private AuthResult resolveAuth(AuthorizationRequest ar) {
        if (ar.connectOpts == null) {
            return AuthResult.failure("No connect options in authorization request");
        }

        // User/pass path: present in Scenarios 1 (explicit sentinel) and 2 (default_sentinel)
        if (ar.connectOpts.user != null && !ar.connectOpts.user.isBlank()) {
            if (passwordHandler == null) {
                return AuthResult.failure("Password authentication is not configured");
            }
            return passwordHandler.authenticate(ar.connectOpts.user, ar.connectOpts.pass);
        }

        // NKey-only path: present in Scenario 3 (default_sentinel + NKey challenge-response).
        // When default_sentinel is active, ar.userNkey is the sentinel bearer's public key.
        // The actual connecting client's NKey is in ar.connectOpts.nkey.
        if (nkeyHandler == null) {
            return AuthResult.failure("NKey authentication is not configured");
        }
        String clientNKey = ar.connectOpts.nkey;
        if (clientNKey == null || clientNKey.isBlank()) {
            return AuthResult.failure("No credentials provided");
        }
        return nkeyHandler.authenticate(clientNKey);
    }

    /** Returns the identity string to use as the name in the issued user JWT. */
    private String resolveIdentity(AuthorizationRequest ar) {
        if (ar.connectOpts != null
                && ar.connectOpts.user != null
                && !ar.connectOpts.user.isBlank()) {
            return ar.connectOpts.user;
        }
        // NKey path: use the client's actual NKey (connectOpts.nkey), not the sentinel's userNkey
        return ar.connectOpts != null && ar.connectOpts.nkey != null
                ? ar.connectOpts.nkey
                : ar.userNkey;
    }

    /**
     * Returns the subject (public key) to use in user and response JWTs.
     * Must always be ar.userNkey — the session identity established by the server
     * (sentinel bearer user NKey for default_sentinel, or the client's NKey in
     * non-sentinel scenarios). The server validates that the user JWT sub matches this.
     *
     * For KV whitelist lookup in NKey auth, use ar.connectOpts.nkey instead
     * (see resolveAuth), which holds the client's actual challenge-response NKey.
     */
    private String resolveSubject(AuthorizationRequest ar) {
        return ar.userNkey;
    }

    // ── JWT response ──────────────────────────────────────────────────────────

    private void respond(ServiceMessage smsg, AuthorizationRequest ar,
                         AuthResult result, String identity) {
        try {
            String responseJwt;
            // userJwtSub = the connecting client's actual public key used as sub in the inner user JWT.
            // For NKey auth with default_sentinel: connectOpts.nkey (client's NKey, not the sentinel bearer's)
            // For password auth: ar.userNkey (the sentinel bearer's NKey, since no client NKey exists)
            String userJwtSub = resolveSubject(ar);

            if (result.isSuccess()) {
                UserClaim userClaim = result.getUserClaim() != null
                        ? result.getUserClaim()
                        : new UserClaim();
                userClaim.issuerAccount(issuerAccount);

                String userJwt = new ClaimIssuer()
                        .aud(result.getAccount())
                        .name(identity)
                        .iss(signingKeyPublic)
                        .sub(userJwtSub)
                        .nats(userClaim)
                        .issueJwt(signingKey);

                AuthorizationResponse authResponse = new AuthorizationResponse()
                        .jwt(userJwt)
                        .issuerAccount(issuerAccount);

                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id)
                        .iss(signingKeyPublic)
                        .sub(ar.userNkey)   // response JWT sub = the sentinel bearer's NKey (requestor)
                        .nats(authResponse)
                        .issueJwt(signingKey);

            } else {
                AuthorizationResponse authResponse = new AuthorizationResponse()
                        .error(result.getError())
                        .issuerAccount(issuerAccount);

                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id)
                        .iss(signingKeyPublic)
                        .sub(ar.userNkey)   // response JWT sub = the sentinel bearer's NKey (requestor)
                        .nats(authResponse)
                        .issueJwt(signingKey);
            }

            smsg.respond(nc, responseJwt);
            log.debug("Decentralized auth response sent — success: {}", result.isSuccess());

        } catch (Exception e) {
            log.error("Failed to send decentralized auth callout response", e);
        }
    }
}
