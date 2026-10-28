package io.example.authcallout;

import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ServiceRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceRunner.class);

    public static void run(String configPath) throws Exception {
        Properties p = load(configPath);

        String natsUrl        = p.getProperty("nats.url",              "nats://localhost:4222");
        String serviceUser    = p.getProperty("nats.service.user",     "auth");
        String servicePass    = p.getProperty("nats.service.password", "auth");
        String signingKeySeed = require(p, "auth.signing.key.seed");
        String defaultAccount = p.getProperty("auth.default.account",  "APP");
        String jdbcUrl        = require(p, "mysql.url");
        String dbUser         = require(p, "mysql.user");
        String dbPassword     = require(p, "mysql.password");
        String query          = p.getProperty("mysql.query",
                "SELECT password_hash FROM users WHERE username = ?");

        Connection nc = Nats.connect(new Options.Builder()
                .server(natsUrl).userInfo(serviceUser, servicePass)
                .errorListener(new ErrorListener() {}).build());

        MySqlAuthHandler handler = new MySqlAuthHandler(jdbcUrl, dbUser, dbPassword, query, defaultAccount);
        AuthCalloutService service = new AuthCalloutService(nc, handler, signingKeySeed);
        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.stop();
            try { nc.close(); } catch (Exception ignored) {}
        }));

        log.info("AuthCallout service running (MySQL). Press Ctrl+C to stop.");
        service.doneFuture().get();
    }

    private static Properties load(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = path != null ? new FileInputStream(path)
                : ServiceRunner.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (in == null) throw new IOException("config.properties not found");
            p.load(in);
        }
        return p;
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing config: " + key);
        return v;
    }
}
