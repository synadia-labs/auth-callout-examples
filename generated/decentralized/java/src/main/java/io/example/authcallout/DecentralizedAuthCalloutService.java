package io.example.authcallout;

import io.nats.client.Connection;
import io.nats.jwt.*;
import io.nats.nkey.NKey;
import io.nats.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

import static io.nats.jwt.JwtUtils.getClaimBody;

/**
 * Decentralized AuthCallout responder.
 *
 * Supports three sub-scenarios:
 *   A) Explicit sentinel:   client loads sentinel.creds + sends username/password
 *   B) default_sentinel:    server auto-applies sentinel; client sends username/password only
 *   C) NKey challenge-resp: client holds NKey seed; server applies default_sentinel; callout
 *                           validates NKey against auth-nkeys KV whitelist
 *
 * Routing: ar.connectOpts.user != null  →  password path (A or B)
 *          ar.connectOpts.user == null  →  NKey path (C)   via ar.connectOpts.nkey
 *
 * JWTs are signed with the AUTH account signing key and include issuerAccount
 * so the NATS server can resolve the signing key back to the AUTH account.
 */
public class DecentralizedAuthCalloutService implements ServiceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DecentralizedAuthCalloutService.class);
    private static final String SUBJECT = "$SYS.REQ.USER.AUTH";

    private final Connection          nc;
    private final KvStoreAuthHandler  passwordHandler;
    private final NKeyKvAuthHandler   nkeyHandler;
    private final NKey                signingKey;
    private final String              signingKeyPublic;
    private final String              defaultAccount;
    private final String              issuerAccount;

    private Service                       service;
    private CompletableFuture<Boolean>    doneFuture;

    public DecentralizedAuthCalloutService(Connection nc,
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
        log.info("DecentralizedAuthCalloutService initialised — issuerAccount: {}, signingKey: {}",
                issuerAccount, signingKeyPublic);
    }

    public void start() throws Exception {
        Endpoint ep = Endpoint.builder().name("DecentralizedAuthCalloutEndpoint").subject(SUBJECT).build();
        ServiceEndpoint sep = ServiceEndpoint.builder().endpoint(ep).handler(this).build();
        service = new ServiceBuilder().connection(nc)
                .name("DecentralizedAuthCalloutService").version("1.0.0")
                .description("Decentralized NATS AuthCallout (user/pass + NKey)")
                .addServiceEndpoint(sep).build();
        doneFuture = service.startService();
        log.info("DecentralizedAuthCalloutService started on {}", SUBJECT);
    }

    public void stop() { if (service != null) service.stop(); }
    public CompletableFuture<Boolean> doneFuture() { return doneFuture; }

    @Override
    public void onMessage(ServiceMessage smsg) {
        try {
            Claim claim = new Claim(getClaimBody(smsg.getData()));
            AuthorizationRequest ar = claim.authorizationRequest;
            if (ar == null || ar.connectOpts == null) return;

            AuthResult result   = resolveAuth(ar);
            String     identity = resolveIdentity(ar);
            log.info("Decentralized auth: identity='{}' result={}", identity, result);
            respond(smsg, ar, result, identity);

        } catch (Exception e) {
            log.error("Error processing decentralized auth callout", e);
        }
    }

    private AuthResult resolveAuth(AuthorizationRequest ar) {
        // Password path (Scenario A / B)
        if (ar.connectOpts.user != null && !ar.connectOpts.user.isBlank()) {
            if (passwordHandler == null) return AuthResult.failure("Password auth not configured");
            return passwordHandler.authenticate(ar.connectOpts.user, ar.connectOpts.pass);
        }
        // NKey path (Scenario C)
        if (nkeyHandler == null) return AuthResult.failure("NKey auth not configured");
        String clientNKey = ar.connectOpts.nkey;
        if (clientNKey == null || clientNKey.isBlank()) return AuthResult.failure("No credentials provided");
        return nkeyHandler.authenticate(clientNKey);
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
            log.error("Failed to send decentralized auth response", e);
        }
    }
}
