package io.synadia.authcallout;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.synadia.authcallout.client.NatsDecentralizedNKeyClient;
import io.synadia.authcallout.client.NatsDecentralizedPasswordClient;
import io.synadia.authcallout.client.NatsJwtTokenClient;
import io.synadia.authcallout.client.NatsUserPasswordClient;
import io.synadia.authcallout.config.AppConfig;
import io.synadia.authcallout.server.AuthCalloutService;
import io.synadia.authcallout.server.DecentralizedAuthCalloutService;
import io.synadia.authcallout.server.LeafSentinelAuthCalloutService;
import io.synadia.authcallout.server.handler.DecentralizedNKeyKvAuthHandler;
import io.synadia.authcallout.server.handler.KvStoreAuthHandler;
import io.synadia.authcallout.server.handler.MySqlAuthHandler;
import io.synadia.authcallout.server.handler.NKeyAuthHandler;
import io.synadia.authcallout.server.handler.OktaAuthHandler;
import io.synadia.authcallout.server.handler.PasswordAuthHandler;
import io.synadia.authcallout.server.handler.TokenAuthHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point.
 *
 * Usage:
 *   java -jar authcallout-service.jar [mode] [config-file]
 *
 * Modes:
 *   service                      (default) Start the centralized AuthCallout service
 *   client-password              Demo: connect with username/password (centralized)
 *   client-token                 Demo: connect with an auth token (centralized)
 *   decentralized-service        Start the decentralized AuthCallout service
 *   client-decentralized-pass    Demo: connect with sentinel .creds + user/pass (Scenario 1)
 *                                      or just user/pass with default_sentinel (Scenario 2)
 *   client-decentralized-nkey    Demo: connect with NKey challenge-response + default_sentinel (Scenario 3)
 *   leaf-sentinel-service        Start the leaf-node + default_sentinel AuthCallout service (Scenario 4)
 *                                Verifies Ed25519 nonce signatures — sole key-possession enforcer
 *                                when bearer-token sentinel is active
*
 * Config file defaults to config.properties on the classpath.
 *
 * Examples:
 *   java -jar authcallout-service.jar service /etc/authcallout/config.properties
 *   java -jar authcallout-service.jar client-password
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "service";
        String configPath = args.length > 1 ? args[1] : null;

        AppConfig config = configPath != null
                ? AppConfig.loadFromFile(configPath)
                : AppConfig.loadFromClasspath();

        switch (mode) {
            case "service"                   -> runService(config);
            case "client-password"           -> runUserPasswordClient(config, args);
            case "client-token"              -> runTokenClient(config, args);
            case "decentralized-service"     -> runDecentralizedService(config);
            case "client-decentralized-pass" -> runDecentralizedPasswordClient(config, args);
            case "client-decentralized-nkey" -> runDecentralizedNKeyClient(config, args);
            case "leaf-sentinel-service"     -> runLeafSentinelService(config);
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.err.println("Valid modes: service | client-password | client-token"
                        + " | decentralized-service | client-decentralized-pass | client-decentralized-nkey");
                System.exit(1);
            }
        }
    }

    // ── Service mode ──────────────────────────────────────────────────────────

    private static void runService(AppConfig config) throws Exception {
        log.info("Starting AuthCallout service — backend: {}", config.getPasswordBackend());

        Connection nc = buildServiceConnection(config);

        PasswordAuthHandler passwordHandler = buildPasswordHandler(config, nc);
        TokenAuthHandler tokenHandler = buildTokenHandler(config);

        AuthCalloutService service = new AuthCalloutService(
                nc,
                passwordHandler,
                tokenHandler,
                config.getSigningKeySeed(),
                config.getDefaultAccount(),
                config.getPasswordBackend(),
                config.getXkeySeed());

        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            service.stop();
            try { nc.close(); } catch (Exception ignored) {}
            try { passwordHandler.close(); } catch (Exception ignored) {}
            if (tokenHandler != null) {
                try { tokenHandler.close(); } catch (Exception ignored) {}
            }
        }));

        log.info("AuthCallout service running. Press Ctrl+C to stop.");
        service.getServiceStoppedFuture().get();
    }

    // ── Client-password mode ──────────────────────────────────────────────────

    private static void runUserPasswordClient(AppConfig config, String[] args) throws Exception {
        // args: client-password [configFile] <username> <password> <subject> [message]
        int offset = args.length > 1 && !args[1].contains("=") ? 1 : 0;
        String username = args.length > 1 + offset ? args[1 + offset] : "alice";
        String password = args.length > 2 + offset ? args[2 + offset] : "alice";
        String subject = args.length > 3 + offset ? args[3 + offset] : "test";
        String message = args.length > 4 + offset ? args[4 + offset] : "hello from user-password client";

        log.info("Connecting as user='{}' to {}", username, config.getNatsUrl());

        try (NatsUserPasswordClient client = new NatsUserPasswordClient(
                config.getNatsUrl(), username, password)) {

            client.connect();
            System.out.printf("[CLIENT] Connected as '%s'%n", username);

            client.createDispatcher(subject, msg -> {
                String body = new String(msg.getData());
                System.out.printf("[CLIENT] Received on '%s': %s%n", msg.getSubject(), body);
            });

            client.publish(subject, message);
            System.out.printf("[CLIENT] Published to '%s': %s%n", subject, message);

            Thread.sleep(1000);
        } catch (Exception e) {
            System.err.printf("[CLIENT] Failed: %s%n", e.getMessage());
            log.error("Client-password error", e);
        }
    }

    // ── Client-token mode ─────────────────────────────────────────────────────

    private static void runTokenClient(AppConfig config, String[] args) throws Exception {
        // args: client-token [configFile] <token> <subject> [message]
        int offset = args.length > 1 && !args[1].contains("=") ? 1 : 0;
        String token = args.length > 1 + offset ? args[1 + offset] : "my-okta-access-token";
        String subject = args.length > 2 + offset ? args[2 + offset] : "test";
        String message = args.length > 3 + offset ? args[3 + offset] : "hello from token client";

        log.info("Connecting with token to {}", config.getNatsUrl());

        try (NatsJwtTokenClient client = new NatsJwtTokenClient(
                config.getNatsUrl(), token)) {

            client.connect();
            System.out.println("[CLIENT] Connected with token");

            client.createDispatcher(subject, msg -> {
                String body = new String(msg.getData());
                System.out.printf("[CLIENT] Received on '%s': %s%n", msg.getSubject(), body);
            });

            client.publish(subject, message);
            System.out.printf("[CLIENT] Published to '%s': %s%n", subject, message);

            Thread.sleep(1000);
        } catch (Exception e) {
            System.err.printf("[CLIENT] Failed: %s%n", e.getMessage());
            log.error("Client-token error", e);
        }
    }

    // ── Decentralized service mode ────────────────────────────────────────────

    private static void runDecentralizedService(AppConfig config) throws Exception {
        log.info("Starting decentralized AuthCallout service");

        Connection nc = buildDecentralizedServiceConnection(config);

        PasswordAuthHandler passwordHandler = buildPasswordHandler(config, nc);

        NKeyAuthHandler nkeyHandler = buildNKeyHandler(config, nc);

        DecentralizedAuthCalloutService service = new DecentralizedAuthCalloutService(
                nc,
                passwordHandler,
                nkeyHandler,
                config.getDecentralizedSigningKeySeed(),
                config.getDecentralizedDefaultAccount(),
                config.getDecentralizedIssuerAccount(),
                config.getXkeySeed());

        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            service.stop();
            try { nc.close(); } catch (Exception ignored) {}
            try { passwordHandler.close(); } catch (Exception ignored) {}
            try { nkeyHandler.close(); } catch (Exception ignored) {}
        }));

        log.info("Decentralized AuthCallout service running. Press Ctrl+C to stop.");
        service.getServiceStoppedFuture().get();
    }

    // ── Leaf sentinel service mode ────────────────────────────────────────────

    private static void runLeafSentinelService(AppConfig config) throws Exception {
        log.info("Starting leaf-sentinel AuthCallout service");

        Connection nc = buildDecentralizedServiceConnection(config);

        PasswordAuthHandler passwordHandler = buildPasswordHandler(config, nc);
        NKeyAuthHandler nkeyHandler = buildNKeyHandler(config, nc);

        LeafSentinelAuthCalloutService service = new LeafSentinelAuthCalloutService(
                nc,
                passwordHandler,
                nkeyHandler,
                config.getDecentralizedSigningKeySeed(),
                config.getDecentralizedDefaultAccount(),
                config.getDecentralizedIssuerAccount(),
                config.getXkeySeed());

        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            service.stop();
            try { nc.close(); } catch (Exception ignored) {}
            if (passwordHandler != null) {
                try { passwordHandler.close(); } catch (Exception ignored) {}
            }
            try { nkeyHandler.close(); } catch (Exception ignored) {}
        }));

        log.info("Leaf-sentinel AuthCallout service running. Press Ctrl+C to stop.");
        service.getServiceStoppedFuture().get();
    }

    // ── Decentralized client-password mode ────────────────────────────────────

    private static void runDecentralizedPasswordClient(AppConfig config, String[] args)
            throws Exception {
        // args: client-decentralized-pass [configFile] <username> <password> <subject> [message]
        int offset = args.length > 1 && !args[1].contains("=") ? 1 : 0;
        String username = args.length > 1 + offset ? args[1 + offset] : "alice";
        String password = args.length > 2 + offset ? args[2 + offset] : "alice";
        String subject  = args.length > 3 + offset ? args[3 + offset] : "test";
        String message  = args.length > 4 + offset ? args[4 + offset] : "hello from decentralized user/pass client";

        // sentinelCredsPath is null when using default_sentinel (Scenario 2)
        String sentinelCreds = config.getDecentralizedSentinelCreds();

        log.info("Connecting as decentralized user='{}' sentinel={} to {}",
                username, sentinelCreds != null ? sentinelCreds : "default_sentinel", config.getNatsUrl());

        try (NatsDecentralizedPasswordClient client = new NatsDecentralizedPasswordClient(
                config.getNatsUrl(), username, password, sentinelCreds)) {

            client.connect();
            System.out.printf("[CLIENT] Connected as decentralized user '%s'%n", username);

            client.createDispatcher(subject, msg -> {
                String body = new String(msg.getData());
                System.out.printf("[CLIENT] Received on '%s': %s%n", msg.getSubject(), body);
            });

            client.publish(subject, message);
            System.out.printf("[CLIENT] Published to '%s': %s%n", subject, message);

            Thread.sleep(1000);
        } catch (io.nats.client.AuthenticationException e) {
            System.err.printf("[CLIENT] Authentication failed for user '%s' — check service logs for details%n", username);
            log.error("Client-decentralized-pass: authentication failed for user '{}'", username);
        } catch (Exception e) {
            System.err.printf("[CLIENT] Failed: %s%n", e.getMessage());
            log.error("Client-decentralized-pass error", e);
        }
    }

    // ── Decentralized client-nkey mode ────────────────────────────────────────

    private static void runDecentralizedNKeyClient(AppConfig config, String[] args)
            throws Exception {
        // args: client-decentralized-nkey [configFile] <subject> [message]
        int offset = args.length > 1 && !args[1].contains("=") ? 1 : 0;
        String subject = args.length > 1 + offset ? args[1 + offset] : "test";
        String message = args.length > 2 + offset ? args[2 + offset] : "hello from decentralized NKey client";

        log.info("Connecting via NKey challenge-response to {}", config.getNatsUrl());

        try (NatsDecentralizedNKeyClient client = new NatsDecentralizedNKeyClient(
                config.getNatsUrl(), config.getDecentralizedNKeySeed())) {

            client.connect();
            System.out.printf("[CLIENT] Connected via NKey (public: %s)%n", client.getPublicKey());

            client.createDispatcher(subject, msg -> {
                String body = new String(msg.getData());
                System.out.printf("[CLIENT] Received on '%s': %s%n", msg.getSubject(), body);
            });

            client.publish(subject, message);
            System.out.printf("[CLIENT] Published to '%s': %s%n", subject, message);

            Thread.sleep(1000);
        } catch (Exception e) {
            System.err.printf("[CLIENT] Failed: %s%n", e.getMessage());
            log.error("Client-decentralized-nkey error", e);
        }
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    private static Connection buildDecentralizedServiceConnection(AppConfig config) throws Exception {
        return Nats.connect(new Options.Builder()
                .server(config.getNatsUrl())
                .authHandler(Nats.credentials(config.getDecentralizedServiceCreds()))
                .errorListener(new ErrorListener() {})
                .build());
    }

    private static Connection buildServiceConnection(AppConfig config) throws Exception {
        Options.Builder builder = new Options.Builder()
                .server(config.getNatsUrl())
                .errorListener(new ErrorListener() {});

        String user = config.getServiceUser();
        String pass = config.getServicePassword();
        if (user != null && !user.isBlank()) {
            builder.userInfo(user, pass);
        }

        return Nats.connect(builder.build());
    }

    private static PasswordAuthHandler buildPasswordHandler(AppConfig config, Connection nc)
            throws Exception {
        return switch (config.getPasswordBackend()) {
            case "kv" -> {
                try {
                    yield new KvStoreAuthHandler(
                            nc,
                            config.getKvBucketName(),
                            config.getDefaultAccount());
                } catch (Exception e) {
                    log.warn("KV bucket '{}' not found — password (kv) authentication disabled: {}",
                            config.getKvBucketName(), e.getMessage());
                    yield null;
                }
            }
            case "mysql" -> new MySqlAuthHandler(
                    config.getMysqlUrl(),
                    config.getMysqlUser(),
                    config.getMysqlPassword(),
                    config.getMysqlQuery(),
                    config.getDefaultAccount());
            case "okta" -> new OktaAuthHandler(
                    config.getOktaDomain(),
                    config.getOktaClientId(),
                    config.getOktaClientSecret(),
                    config.getOktaScopes(),
                    config.getDefaultAccount(),
                    config.getOktaTokenPath(),
                    config.getOktaIntrospectPath());
            default -> throw new IllegalArgumentException(
                    "Unknown auth.password.backend: " + config.getPasswordBackend()
                            + ". Valid values: kv | mysql | okta");
        };
    }

    private static NKeyAuthHandler buildNKeyHandler(AppConfig config, Connection nc) {
        if (!config.isDecentralizedNKeyEnabled()) {
            log.info("NKey authentication disabled (auth.decentralized.nkey.enabled=false)");
            return null;
        }
        try {
            return new DecentralizedNKeyKvAuthHandler(
                    nc, config.getDecentralizedNKeyBucket(), config.getDecentralizedDefaultAccount());
        } catch (Exception e) {
            log.warn("NKey KV bucket '{}' not found — NKey authentication disabled: {}",
                    config.getDecentralizedNKeyBucket(), e.getMessage());
            return null;
        }
    }

    private static TokenAuthHandler buildTokenHandler(AppConfig config) {
        // Token validation is always Okta; return null if Okta is not configured.
        try {
            return new OktaAuthHandler(
                    config.getOktaDomain(),
                    config.getOktaClientId(),
                    config.getOktaClientSecret(),
                    config.getOktaScopes(),
                    config.getDefaultAccount(),
                    config.getOktaTokenPath(),
                    config.getOktaIntrospectPath());
        } catch (IllegalStateException e) {
            log.warn("Okta not configured — token authentication (Scenario 4) disabled: {}",
                    e.getMessage());
            return null;
        }
    }
}
