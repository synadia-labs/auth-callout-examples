package io.example.authcallout;

import io.nats.client.Connection;
import io.nats.jwt.*;
import io.nats.nkey.NKey;
import io.nats.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import static io.nats.jwt.JwtUtils.getClaimBody;

/**
 * AuthCallout for the Leaf Node + default_sentinel scenario.
 *
 * Critical difference from DecentralizedAuthCalloutService:
 * This service performs Ed25519 nonce signature verification before the KV lookup.
 * When default_sentinel is active the NATS server skips its native nonce-sig check,
 * making this callout the SOLE enforcer of key possession for both clients AND leaf nodes.
 *
 * Auth flow for NKey connections:
 *   1. Server sends nonce to connecting party.
 *   2. Party signs nonce with its NKey seed and sends {nkey, sig} in CONNECT.
 *   3. Server applies default_sentinel and forwards AuthorizationRequest here.
 *   4. This service verifies Ed25519(nonce, sig, pubKey) — sole key-possession check.
 *   5. Looks up pubKey in auth-nkeys KV whitelist.
 *   6. Mints user JWT bound to the target account.
 *
 * ar.clientInfo.kind distinguishes leaf nodes ("Leafnode") from regular clients ("Client").
 */
public class LeafSentinelAuthCalloutService implements ServiceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LeafSentinelAuthCalloutService.class);
    private static final String SUBJECT = "$SYS.REQ.USER.AUTH";

    private final Connection         nc;
    private final KvStoreAuthHandler passwordHandler;
    private final NKeyKvAuthHandler  nkeyHandler;
    private final NKey               signingKey;
    private final String             signingKeyPublic;
    private final String             defaultAccount;
    private final String             issuerAccount;

    private Service                      service;
    private CompletableFuture<Boolean>   doneFuture;

    public LeafSentinelAuthCalloutService(Connection nc,
                                          KvStoreAuthHandler passwordHandler,
                                          NKeyKvAuthHandler  nkeyHandler,
                                          String signingKeySeed,
                                          String defaultAccount,
                                          String issuerAccount) throws Exception {
        this.nc               = nc;
        this.passwordHandler  = passwordHandler;
        this.nkeyHandler      = nkeyHandler;
        this.defaultAccount   = defaultAccount;
        this.issuerAccount    = issuerAccount;
        this.signingKey       = NKey.fromSeed(signingKeySeed.toCharArray());
        this.signingKeyPublic = new String(signingKey.getPublicKey());
        log.info("LeafSentinelAuthCalloutService initialised — issuerAccount: {}, signingKey: {}",
                issuerAccount, signingKeyPublic);
    }

    public void start() throws Exception {
        Endpoint ep = Endpoint.builder().name("LeafSentinelAuthCalloutEndpoint").subject(SUBJECT).build();
        ServiceEndpoint sep = ServiceEndpoint.builder().endpoint(ep).handler(this).build();
        service = new ServiceBuilder().connection(nc)
                .name("LeafSentinelAuthCalloutService").version("1.0.0")
                .description("Leaf Sentinel AuthCallout — Ed25519 nonce verification + NKey KV whitelist")
                .addServiceEndpoint(sep).build();
        doneFuture = service.startService();
        log.info("LeafSentinelAuthCalloutService started on {}", SUBJECT);
    }

    public void stop() { if (service != null) service.stop(); }
    public CompletableFuture<Boolean> doneFuture() { return doneFuture; }

    @Override
    public void onMessage(ServiceMessage smsg) {
        try {
            Claim claim = new Claim(getClaimBody(smsg.getData()));
            AuthorizationRequest ar = claim.authorizationRequest;
            if (ar == null || ar.connectOpts == null) return;

            String kind     = resolveKind(ar);
            String identity = resolveIdentity(ar);
            AuthResult result = resolveAuth(ar, kind);

            if (result.isSuccess()) {
                log.info("Auth SUCCESS — kind: {}, identity: '{}', account: '{}'",
                        kind, identity, result.getAccount());
            } else {
                log.warn("Auth FAILED  — kind: {}, identity: '{}', reason: '{}'",
                        kind, identity, result.getError());
            }
            respond(smsg, ar, result, identity);

        } catch (Exception e) {
            log.error("Error processing leaf sentinel auth callout", e);
        }
    }

    private AuthResult resolveAuth(AuthorizationRequest ar, String kind) {
        // Password path — no nonce verification needed for password auth
        if (ar.connectOpts.user != null && !ar.connectOpts.user.isBlank()) {
            if (passwordHandler == null) return AuthResult.failure("Password auth not configured");
            return passwordHandler.authenticate(ar.connectOpts.user, ar.connectOpts.pass);
        }

        // NKey path — MUST verify nonce signature before KV lookup.
        // The bearer sentinel causes the server to skip its native nonce-sig check.
        // This callout is the sole enforcer of key possession.
        String clientNKey = ar.connectOpts.nkey;
        if (clientNKey == null || clientNKey.isBlank()) return AuthResult.failure("No credentials provided");

        AuthResult nonceCheck = verifyNonce(ar, clientNKey, kind);
        if (!nonceCheck.isSuccess()) return nonceCheck;

        if (nkeyHandler == null) return AuthResult.failure("NKey auth not configured");
        return nkeyHandler.authenticate(clientNKey);
    }

    /**
     * Verifies the Ed25519 signature of the server nonce.
     *   nonce  = ar.clientInfo.nonce
     *   sig    = ar.connectOpts.sig  (base64url, no padding)
     *   pubKey = ar.connectOpts.nkey
     */
    private AuthResult verifyNonce(AuthorizationRequest ar, String clientNKey, String kind) {
        if (ar.clientInfo == null || ar.clientInfo.nonce == null || ar.clientInfo.nonce.isBlank()) {
            log.warn("Nonce missing — kind: {}, nkey: {}", kind, clientNKey);
            return AuthResult.failure("Nonce missing in auth request");
        }
        if (ar.connectOpts.sig == null || ar.connectOpts.sig.isBlank()) {
            log.warn("Signature missing — kind: {}, nkey: {}", kind, clientNKey);
            return AuthResult.failure("Nonce signature missing");
        }
        try {
            NKey pubKey = NKey.fromPublicKey(clientNKey.toCharArray());
            byte[] sig  = Base64.getUrlDecoder().decode(ar.connectOpts.sig);
            boolean ok  = pubKey.verify(ar.clientInfo.nonce.getBytes(), sig);
            if (!ok) {
                log.warn("Nonce signature INVALID — kind: {}, nkey: {}", kind, clientNKey);
                return AuthResult.failure("Nonce signature verification failed");
            }
            log.debug("Nonce signature verified — kind: {}, nkey: {}", kind, clientNKey);
            return AuthResult.success(defaultAccount);
        } catch (Exception e) {
            log.warn("Nonce verification error — kind: {}, nkey: {}: {}", kind, clientNKey, e.getMessage());
            return AuthResult.failure("Nonce signature verification error");
        }
    }

    private String resolveKind(AuthorizationRequest ar) {
        return (ar.clientInfo != null && ar.clientInfo.kind != null) ? ar.clientInfo.kind : "Client";
    }

    private String resolveIdentity(AuthorizationRequest ar) {
        if (ar.connectOpts.user != null && !ar.connectOpts.user.isBlank()) return ar.connectOpts.user;
        return ar.connectOpts.nkey != null ? ar.connectOpts.nkey : ar.userNkey;
    }

    private void respond(ServiceMessage smsg, AuthorizationRequest ar,
                         AuthResult result, String identity) {
        try {
            String responseJwt;
            if (result.isSuccess()) {
                UserClaim uc = result.getUserClaim();
                uc.issuerAccount(issuerAccount);

                String userJwt = new ClaimIssuer()
                        .aud(result.getAccount()).name(identity)
                        .iss(signingKeyPublic).sub(ar.userNkey)
                        .nats(uc).issueJwt(signingKey);

                AuthorizationResponse authResp = new AuthorizationResponse()
                        .jwt(userJwt).issuerAccount(issuerAccount);

                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id).iss(signingKeyPublic).sub(ar.userNkey)
                        .nats(authResp).issueJwt(signingKey);
            } else {
                AuthorizationResponse authResp = new AuthorizationResponse()
                        .error(result.getError()).issuerAccount(issuerAccount);
                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id).iss(signingKeyPublic).sub(ar.userNkey)
                        .nats(authResp).issueJwt(signingKey);
            }
            smsg.respond(nc, responseJwt);
        } catch (Exception e) {
            log.error("Failed to send leaf sentinel auth response", e);
        }
    }
}
