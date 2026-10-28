package io.example.authcallout;

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
 * Authenticates clients against a MySQL table using SHA-256 hashed passwords.
 *
 * Expected table:
 *   CREATE TABLE users (
 *       username      VARCHAR(255) PRIMARY KEY,
 *       password_hash VARCHAR(64)  NOT NULL   -- SHA-256 hex
 *   );
 *
 * Opens a new JDBC connection per authentication request.
 * For high-throughput use, replace with a connection pool (e.g. HikariCP).
 */
public class MySqlAuthHandler {

    private static final Logger log = LoggerFactory.getLogger(MySqlAuthHandler.class);

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String query;
    private final String defaultAccount;

    public MySqlAuthHandler(String jdbcUrl, String dbUser, String dbPassword,
                            String query, String defaultAccount) {
        this.jdbcUrl         = jdbcUrl;
        this.dbUser          = dbUser;
        this.dbPassword      = dbPassword;
        this.query           = query;
        this.defaultAccount  = defaultAccount;
        log.info("MySqlAuthHandler ready — url: {}", jdbcUrl);
    }

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
                String stored = rs.getString(1);
                if (stored.equalsIgnoreCase(sha256Hex(password))) {
                    log.debug("MySQL auth: user '{}' authenticated", username);
                    return AuthResult.success(defaultAccount);
                }
                log.debug("MySQL auth: wrong password for user '{}'", username);
                return AuthResult.failure("Invalid password");
            }
        } catch (SQLException e) {
            log.error("MySQL auth DB error for user '{}'", username, e);
            return AuthResult.failure("Database error");
        } catch (NoSuchAlgorithmException e) {
            return AuthResult.failure("SHA-256 not available");
        }
    }

    static String sha256Hex(String input) throws NoSuchAlgorithmException {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] h = d.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
