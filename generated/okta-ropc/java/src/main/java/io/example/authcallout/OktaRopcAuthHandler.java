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

/**
 * Scenario 3: Authenticates clients via Okta Resource Owner Password Credentials (ROPC) flow.
 *
 * Sends the username/password to Okta's token endpoint. A successful response
 * containing an access_token means authentication passes.
 *
 * NOTE: Okta deprecated ROPC in Identity Engine orgs. Use a legacy Okta org,
 * or Auth0 (set token.path=/oauth/token in config.properties).
 */
public class OktaRopcAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(OktaRopcAuthHandler.class);

    private final String     domain;
    private final String     clientId;
    private final String     clientSecret;
    private final String     scopes;
    private final String     tokenPath;
    private final String     defaultAccount;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OktaRopcAuthHandler(String domain, String clientId, String clientSecret,
                                String scopes, String tokenPath, String defaultAccount) {
        this.domain         = normalise(domain);
        this.clientId       = clientId;
        this.clientSecret   = clientSecret;
        this.scopes         = scopes;
        this.tokenPath      = tokenPath;
        this.defaultAccount = defaultAccount;
        this.http           = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        log.info("OktaRopcAuthHandler ready — domain: {}{}", this.domain, tokenPath);
    }

    public AuthResult authenticate(String username, String password) {
        if (username == null || password == null)
            return AuthResult.failure("Username and password are required");
        try {
            String body = "grant_type=password"
                    + "&username="      + enc(username)
                    + "&password="      + enc(password)
                    + "&scope="         + enc(scopes)
                    + "&client_id="     + enc(clientId)
                    + "&client_secret=" + enc(clientSecret);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(domain + tokenPath))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("Okta ROPC response status {} for user '{}'", resp.statusCode(), username);

            if (resp.statusCode() == 200) {
                JsonNode json = mapper.readTree(resp.body());
                if (json.has("access_token")) {
                    log.debug("Okta ROPC: user '{}' authenticated", username);
                    return AuthResult.success(defaultAccount);
                }
            }
            String errorDesc = extractError(resp.body());
            log.debug("Okta ROPC failed for user '{}': {}", username, errorDesc);
            return AuthResult.failure(errorDesc);

        } catch (Exception e) {
            log.error("Okta ROPC error for user '{}'", username, e);
            return AuthResult.failure("Okta authentication error");
        }
    }

    private String extractError(String body) {
        try {
            JsonNode j = mapper.readTree(body);
            return j.path("error_description").asText("Authentication failed");
        } catch (Exception ignored) {
            return body != null && body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    private static String enc(String v)  { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
    private static String normalise(String d) {
        d = d.strip();
        if (!d.startsWith("https://") && !d.startsWith("http://")) d = "https://" + d;
        return d.stripTrailing();
    }
}
