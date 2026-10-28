package io.example.authcallout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Scenario 4: Validates a JWT access token via Okta token introspection.
 *
 * The client obtains an Okta access token externally (e.g. via browser OAuth2 flow
 * or machine-to-machine client credentials) and passes it as the NATS auth_token.
 * This service calls Okta's introspect endpoint to verify the token is active.
 */
public class OktaIntrospectHandler {

    private static final Logger log = LoggerFactory.getLogger(OktaIntrospectHandler.class);

    private final String     domain;
    private final String     clientId;
    private final String     clientSecret;
    private final String     introspectPath;
    private final String     defaultAccount;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OktaIntrospectHandler(String domain, String clientId, String clientSecret,
                                  String introspectPath, String defaultAccount) {
        this.domain         = normalise(domain);
        this.clientId       = clientId;
        this.clientSecret   = clientSecret;
        this.introspectPath = introspectPath;
        this.defaultAccount = defaultAccount;
        this.http           = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        log.info("OktaIntrospectHandler ready — domain: {}{}", this.domain, introspectPath);
    }

    public AuthResult authenticateToken(String token) {
        if (token == null || token.isBlank())
            return AuthResult.failure("Token is required");
        try {
            String credentials = clientId + ":" + clientSecret;
            String basicAuth   = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            String body = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + "&token_type_hint=access_token";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(domain + introspectPath))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("Authorization", "Basic " + basicAuth)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("Okta introspect response (status {}): {}", resp.statusCode(), resp.body());

            JsonNode json = mapper.readTree(resp.body());
            boolean active = json.path("active").asBoolean(false);
            if (active) {
                String sub = json.path("sub").asText("unknown");
                log.debug("Token active for sub='{}'", sub);
                return AuthResult.success(defaultAccount);
            }
            log.debug("Token is inactive or invalid");
            return AuthResult.failure("Token is inactive or invalid");

        } catch (Exception e) {
            log.error("Okta introspect error", e);
            return AuthResult.failure("Okta token validation error");
        }
    }

    private static String normalise(String d) {
        d = d.strip();
        if (!d.startsWith("https://") && !d.startsWith("http://")) d = "https://" + d;
        return d.stripTrailing();
    }
}
