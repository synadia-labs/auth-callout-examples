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

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.nats.jwt.JwtUtils.getClaimBody;

/**
 * AuthCallout service for the Leaf Node + default_sentinel scenario.
 *
 * Handles authentication for both NATS clients and leaf nodes through the
 * same {@code $SYS.REQ.USER.AUTH} subject — the server routes both kinds
 * identically when {@code default_sentinel} is active.
 *
 * Key difference from {@link DecentralizedAuthCalloutService}:
 * this service performs Ed25519 nonce signature verification on NKey
 * connections. This is critical because the bearer-token sentinel causes
 * the NATS server to skip its own native nonce-sig check — making the
 * callout the sole enforcer of key possession.
 *
 * Auth flow for NKey connections (client or leaf node):
 *  1. Server sends nonce to connecting party.
 *  2. Party signs nonce with its NKey seed and sends {nkey, sig} in CONNECT.
 *  3. Server applies default_sentinel (bearer), skips native sig check.
 *  4. Server dispatches AuthorizationRequest to this service.
 *  5. This service verifies the Ed25519 sig of clientInfo.nonce
 *     against connectOpts.nkey — sole key-possession check.
 *  6. Looks up connectOpts.nkey in the auth-nkeys KV whitelist.
 *  7. Mints user JWT with sub=userNkey (server-generated placeholder,
 *     required for replay protection), bound to the target account.
 *
 * Auth flow for user/pass connections:
 *  Delegates directly to the {@link PasswordAuthHandler} — no nonce check.
 *
 * Logging includes {@code clientInfo.kind} so it is obvious in the logs
 * whether a leaf node or a regular client was authenticated.
 */
public class LeafSentinelAuthCalloutService implements ServiceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LeafSentinelAuthCalloutService.class);

    static final String AUTH_CALLOUT_SUBJECT = "$SYS.REQ.USER.AUTH";
    static final String SERVICE_NAME         = "LeafSentinelAuthCalloutService";
    static final String SERVICE_VERSION      = "1.0.0";
    static final String SERVICE_DESCRIPTION  =
            "AuthCallout for leaf-node + default_sentinel — verifies Ed25519 nonce signatures " +
            "and validates NKeys against a KV whitelist";

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

    public LeafSentinelAuthCalloutService(Connection nc,
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
        log.info("LeafSentinelAuthCalloutService initialised — account: {}, issuerAccount: {}, xkey: {}, signingKey: {}",
                defaultAccount, issuerAccount, xkeyPublic, signingKeyPublic);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws Exception {
        Endpoint endpoint = Endpoint.builder()
                .name("LeafSentinelAuthCalloutEndpoint")
                .subject(AUTH_CALLOUT_SUBJECT)
                .metadata(Map.of(
                        "description", "Processes AuthorizationRequest for leaf nodes and clients via default_sentinel",
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
                        "model",           "leaf-sentinel",
                        "default.account", defaultAccount
                ))
                .addServiceEndpoint(serviceEndpoint)
                .build();

        serviceStoppedFuture = service.startService();
        log.info("LeafSentinelAuthCalloutService started — listening on {}", AUTH_CALLOUT_SUBJECT);
    }

    public void stop() {
        if (service != null) {
            service.stop();
            log.info("LeafSentinelAuthCalloutService stopped");
        }
    }

    public CompletableFuture<Boolean> getServiceStoppedFuture() {
        return serviceStoppedFuture;
    }

    // ── ServiceMessageHandler ─────────────────────────────────────────────────

    @Override
    public void onMessage(ServiceMessage smsg) {
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
            }

            Claim claim = new Claim(getClaimBody(payload));
            AuthorizationRequest ar = claim.authorizationRequest;

            if (ar == null) {
                log.warn("Received message with no AuthorizationRequest claim — ignoring");
                return;
            }

            String kind = resolveKind(ar);
            log.debug("Auth callout request — kind: {}, serverId: {}, userNkey: {}",
                    kind, ar.serverId != null ? ar.serverId.id : "?", ar.userNkey);

            AuthResult result = resolveAuth(ar, kind);
            String identity = resolveIdentity(ar);

            if (result.isSuccess()) {
                log.info("Auth SUCCESS — kind: {}, identity: '{}', account: '{}'",
                        kind, identity, result.getAccount());
            } else {
                log.warn("Auth FAILED — kind: {}, identity: '{}', reason: '{}'",
                        kind, identity, result.getError());
            }

            respond(smsg, ar, result, identity);

        } catch (Exception e) {
            log.error("Unexpected error processing auth callout request", e);
        }
    }

    // ── Auth dispatch ─────────────────────────────────────────────────────────

    private AuthResult resolveAuth(AuthorizationRequest ar, String kind) {
        if (ar.connectOpts == null) {
            return AuthResult.failure("No connect options in authorization request");
        }

        // User/pass path — no nonce verification needed for password auth
        if (ar.connectOpts.user != null && !ar.connectOpts.user.isBlank()) {
            if (passwordHandler == null) {
                return AuthResult.failure("Password authentication is not configured");
            }
            return passwordHandler.authenticate(ar.connectOpts.user, ar.connectOpts.pass);
        }

        // NKey path — verify the Ed25519 nonce signature before KV lookup.
        // IMPORTANT: the bearer-token sentinel causes the NATS server to skip
        // its native nonce-sig check. This callout is the sole enforcer of
        // key possession for both clients and leaf nodes.
        String clientNKey = ar.connectOpts.nkey;
        if (clientNKey == null || clientNKey.isBlank()) {
            return AuthResult.failure("No credentials provided");
        }

        AuthResult nonceCheck = verifyNonce(ar, clientNKey, kind);
        if (!nonceCheck.isSuccess()) {
            return nonceCheck;
        }

        if (nkeyHandler == null) {
            return AuthResult.failure("NKey authentication is not configured");
        }
        return nkeyHandler.authenticate(clientNKey);
    }

    /**
     * Verifies the Ed25519 signature of the server nonce against the client's NKey.
     * nonce  = ar.clientInfo.nonce
     * sig    = ar.connectOpts.sig  (base64url-encoded, no padding)
     * pubkey = ar.connectOpts.nkey
     */
    private AuthResult verifyNonce(AuthorizationRequest ar, String clientNKey, String kind) {
        if (ar.clientInfo == null || ar.clientInfo.nonce == null || ar.clientInfo.nonce.isBlank()) {
            log.warn("Nonce missing in auth request — kind: {}, nkey: {}", kind, clientNKey);
            return AuthResult.failure("Nonce missing in auth request");
        }
        if (ar.connectOpts.sig == null || ar.connectOpts.sig.isBlank()) {
            log.warn("Signature missing in connect opts — kind: {}, nkey: {}", kind, clientNKey);
            return AuthResult.failure("Nonce signature missing");
        }

        try {
            NKey pubKey = NKey.fromPublicKey(clientNKey.toCharArray());
            byte[] sig = Base64.getUrlDecoder().decode(ar.connectOpts.sig);
            boolean valid = pubKey.verify(ar.clientInfo.nonce.getBytes(), sig);
            if (!valid) {
                log.warn("Nonce signature verification FAILED — kind: {}, nkey: {}", kind, clientNKey);
                return AuthResult.failure("Nonce signature verification failed");
            }
            log.debug("Nonce signature verified — kind: {}, nkey: {}", kind, clientNKey);
            return AuthResult.success(defaultAccount, null);
        } catch (Exception e) {
            log.warn("Nonce signature verification error — kind: {}, nkey: {}: {}", kind, clientNKey, e.getMessage());
            return AuthResult.failure("Nonce signature verification error");
        }
    }

    private String resolveKind(AuthorizationRequest ar) {
        if (ar.clientInfo != null && ar.clientInfo.kind != null && !ar.clientInfo.kind.isBlank()) {
            return ar.clientInfo.kind;
        }
        return "Client";
    }

    private String resolveIdentity(AuthorizationRequest ar) {
        if (ar.connectOpts != null
                && ar.connectOpts.user != null
                && !ar.connectOpts.user.isBlank()) {
            return ar.connectOpts.user;
        }
        return ar.connectOpts != null && ar.connectOpts.nkey != null
                ? ar.connectOpts.nkey
                : ar.userNkey;
    }

    // ── JWT response ──────────────────────────────────────────────────────────

    private void respond(ServiceMessage smsg, AuthorizationRequest ar,
                         AuthResult result, String identity) {
        try {
            String responseJwt;

            if (result.isSuccess()) {
                UserClaim userClaim = result.getUserClaim() != null
                        ? result.getUserClaim()
                        : new UserClaim();
                userClaim.issuerAccount(issuerAccount);

                String userJwt = new ClaimIssuer()
                        .aud(result.getAccount())
                        .name(identity)
                        .iss(signingKeyPublic)
                        .sub(ar.userNkey)       // must be server-generated placeholder
                        .nats(userClaim)
                        .issueJwt(signingKey);

                AuthorizationResponse authResponse = new AuthorizationResponse()
                        .jwt(userJwt)
                        .issuerAccount(issuerAccount);

                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id)
                        .iss(signingKeyPublic)
                        .sub(ar.userNkey)
                        .nats(authResponse)
                        .issueJwt(signingKey);

            } else {
                AuthorizationResponse authResponse = new AuthorizationResponse()
                        .error(result.getError())
                        .issuerAccount(issuerAccount);

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
