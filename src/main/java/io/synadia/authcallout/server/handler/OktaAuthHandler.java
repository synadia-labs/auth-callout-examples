package io.synadia.authcallout.server.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.jwt.UserClaim;
import io.synadia.authcallout.model.AuthResult;
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
 * Scenario 3: client username/password → Okta (ROPC / Resource Owner Password Credentials).
 * Scenario 4: client JWT/access token  → Okta (token introspection).
 *
 * Configuration keys (in config.properties):
 *   okta.domain        e.g. https://your-org.okta.com
 *   okta.client.id
 *   okta.client.secret
 *   okta.scopes        space-separated; default "openid profile email"
 *
 * NOTE: Okta deprecated ROPC in newer Identity Engine orgs.
 * If ROPC is disabled, use the Okta Authentication API (/api/v1/authn) instead.
 * The introspection endpoint (/oauth2/v1/introspect) works for both opaque
 * access tokens and OIDC ID tokens.
 */
public class OktaAuthHandler implements PasswordAuthHandler, TokenAuthHandler {

    @Override
    public void close() {
        // HttpClient manages its own lifecycle; nothing to release here
    }

    private static final Logger log = LoggerFactory.getLogger(OktaAuthHandler.class);

    private final String oktaDomain;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final String defaultAccount;
    private final String tokenPath;
    private final String introspectPath;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OktaAuthHandler(String oktaDomain, String clientId, String clientSecret,
                            String scopes, String defaultAccount,
                            String tokenPath, String introspectPath) {
        String domain = oktaDomain.strip();
        if (domain.startsWith("http://")) {
            log.warn("okta.domain uses plain http — upgrading to https");
            domain = "https://" + domain.substring(7);
        } else if (!domain.startsWith("https://")) {
            domain = "https://" + domain;
        }
        this.oktaDomain = domain.stripTrailing();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes;
        this.defaultAccount = defaultAccount;
        this.tokenPath = tokenPath;
        this.introspectPath = introspectPath;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        log.info("OktaAuthHandler ready — domain: {}, tokenPath: {}, introspectPath: {}",
                this.oktaDomain, tokenPath, introspectPath);
    }

    // ── Scenario 3: username/password via ROPC ────────────────────────────────

    @Override
    public AuthResult authenticate(String username, String password) {
        if (username == null || password == null) {
            return AuthResult.failure("Username and password are required");
        }
        try {
            String body = "grant_type=password"
                    + "&username=" + urlEncode(username)
                    + "&password=" + urlEncode(password)
                    + "&scope=" + urlEncode(scopes)
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oktaDomain + tokenPath))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                if (json.has("access_token")) {
                    log.debug("Okta ROPC auth: user '{}' authenticated", username);
                    return AuthResult.success(defaultAccount, new UserClaim());
                }
            }

            log.debug("Okta ROPC non-200 response (status {}) for user '{}': {}",
                    response.statusCode(), username, response.body());
            String errorDesc = extractErrorDescription(response.body());
            log.debug("Okta ROPC auth failed for user '{}': {}", username, errorDesc);
            return AuthResult.failure(errorDesc);

        } catch (Exception e) {
            log.error("Okta ROPC error for user '{}'", username, e);
            return AuthResult.failure("Okta authentication error");
        }
    }

    // ── Scenario 4: token via introspection ───────────────────────────────────

    @Override
    public AuthResult authenticateToken(String token) {
        if (token == null || token.isBlank()) {
            return AuthResult.failure("Token is required");
        }
        try {
            String credentials = clientId + ":" + clientSecret;
            String basicAuth = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            String body = "token=" + urlEncode(token)
                    + "&token_type_hint=access_token";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(oktaDomain + introspectPath))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("Authorization", "Basic " + basicAuth)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.debug("Okta introspection response (status {}): {}",
                    response.statusCode(), response.body());
            JsonNode json = objectMapper.readTree(response.body());
            boolean active = json.path("active").asBoolean(false);

            if (active) {
                String subject = json.path("sub").asText("unknown");
                log.debug("Okta token introspection: token active for sub='{}'", subject);
                return AuthResult.success(defaultAccount, new UserClaim());
            }

            log.debug("Okta token introspection: token is inactive or invalid");
            return AuthResult.failure("Token is inactive or invalid");

        } catch (Exception e) {
            log.error("Okta introspection error", e);
            return AuthResult.failure("Okta token validation error");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Extracts an error description from a response body.
     * Tries JSON first ({@code error_description} field); falls back to the raw
     * body (truncated) if the response is not valid JSON (e.g. an HTML error page).
     */
    private String extractErrorDescription(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            return json.path("error_description").asText("Authentication failed");
        } catch (Exception ignored) {
            String raw = body != null ? body.strip() : "";
            return raw.length() > 200 ? raw.substring(0, 200) + "…" : raw;
        }
    }
}
