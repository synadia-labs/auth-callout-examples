package io.synadia.authcallout.server.handler;

import io.nats.jwt.UserClaim;
import io.synadia.authcallout.model.AuthResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Scenario 2: client username/password → MySQL.
 *
 * Expected table (adapt as needed):
 *
 *   CREATE TABLE users (
 *       username      VARCHAR(255) PRIMARY KEY,
 *       password_hash VARCHAR(64)  NOT NULL   -- SHA-256 hex
 *   );
 *
 * The configured query must accept exactly one parameter (the username) and
 * return one column (the stored password hash).
 *
 * Default query: SELECT password_hash FROM users WHERE username = ?
 *
 * Note: Opens a new JDBC connection per authentication request.
 * For high-throughput scenarios, replace with a connection pool (e.g. HikariCP).
 */
public class MySqlAuthHandler implements PasswordAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(MySqlAuthHandler.class);

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String query;
    private final String defaultAccount;

    public MySqlAuthHandler(String jdbcUrl, String dbUser, String dbPassword,
                             String query, String defaultAccount) {
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.query = query;
        this.defaultAccount = defaultAccount;
        log.info("MySqlAuthHandler ready — url: {}", jdbcUrl);
    }

    @Override
    public AuthResult authenticate(String username, String password) {
        if (username == null || password == null) {
            return AuthResult.failure("Username and password are required");
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    log.debug("MySQL auth: user '{}' not found", username);
                    return AuthResult.failure("User not found: " + username);
                }

                String storedHash = rs.getString(1);
                String inputHash = sha256Hex(password);

                if (storedHash.equalsIgnoreCase(inputHash)) {
                    log.debug("MySQL auth: user '{}' authenticated", username);
                    return AuthResult.success(defaultAccount, new UserClaim());
                }

                log.debug("MySQL auth: invalid password for user '{}'", username);
                return AuthResult.failure("Invalid password");
            }

        } catch (SQLException e) {
            log.error("MySQL auth DB error for user '{}'", username, e);
            return AuthResult.failure("Database error during authentication");
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available", e);
            return AuthResult.failure("Authentication service error");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String sha256Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
