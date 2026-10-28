package io.example.authcallout;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

import static io.nats.jwt.JwtUtils.getClaimBody;

/**
 * AuthCallout responder for the MySQL scenario.
 * Delegates credential validation to {@link MySqlAuthHandler}.
 */
public class AuthCalloutService implements ServiceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthCalloutService.class);
    private static final String SUBJECT = "$SYS.REQ.USER.AUTH";

    private final Connection       nc;
    private final MySqlAuthHandler handler;
    private final NKey             signingKey;
    private final String           signingKeyPublic;

    private Service                    service;
    private CompletableFuture<Boolean> doneFuture;

    public AuthCalloutService(Connection nc, MySqlAuthHandler handler,
                              String signingKeySeed) throws Exception {
        this.nc               = nc;
        this.handler          = handler;
        this.signingKey       = NKey.fromSeed(signingKeySeed.toCharArray());
        this.signingKeyPublic = new String(signingKey.getPublicKey());
        log.info("AuthCalloutService initialised — signingKey: {}", signingKeyPublic);
    }

    public void start() throws Exception {
        Endpoint ep = Endpoint.builder().name("AuthCalloutEndpoint").subject(SUBJECT).build();
        ServiceEndpoint sep = ServiceEndpoint.builder().endpoint(ep).handler(this).build();
        service = new ServiceBuilder()
                .connection(nc).name("AuthCalloutService").version("1.0.0")
                .description("MySQL AuthCallout").addServiceEndpoint(sep).build();
        doneFuture = service.startService();
        log.info("AuthCalloutService started on {}", SUBJECT);
    }

    public void stop() { if (service != null) service.stop(); }
    public CompletableFuture<Boolean> doneFuture() { return doneFuture; }

    @Override
    public void onMessage(ServiceMessage smsg) {
        try {
            Claim claim = new Claim(getClaimBody(smsg.getData()));
            AuthorizationRequest ar = claim.authorizationRequest;
            if (ar == null || ar.connectOpts == null) return;

            AuthResult result = handler.authenticate(ar.connectOpts.user, ar.connectOpts.pass);
            log.info("Auth result for user='{}': {}", ar.connectOpts.user, result);
            respond(smsg, ar, result);
        } catch (Exception e) {
            log.error("Error processing auth callout request", e);
        }
    }

    private void respond(ServiceMessage smsg, AuthorizationRequest ar, AuthResult result) {
        try {
            String responseJwt;
            if (result.isSuccess()) {
                UserClaim uc = result.getUserClaim();
                String userJwt = new ClaimIssuer()
                        .aud(result.getAccount()).name(ar.connectOpts.user)
                        .iss(signingKeyPublic).sub(ar.userNkey)
                        .nats(uc).issueJwt(signingKey);
                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id).iss(signingKeyPublic)
                        .sub(ar.userNkey).nats(new AuthorizationResponse().jwt(userJwt))
                        .issueJwt(signingKey);
            } else {
                responseJwt = new ClaimIssuer()
                        .aud(ar.serverId.id).iss(signingKeyPublic)
                        .sub(ar.userNkey).nats(new AuthorizationResponse().error(result.getError()))
                        .issueJwt(signingKey);
            }
            smsg.respond(nc, responseJwt);
        } catch (Exception e) {
            log.error("Failed to send auth callout response", e);
        }
    }
}
