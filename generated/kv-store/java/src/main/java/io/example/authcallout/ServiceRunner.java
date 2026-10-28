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

/**
 * Loads configuration, connects to NATS as the auth service user,
 * and starts the AuthCallout service.
 */
public class ServiceRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceRunner.class);

    public static void run(String configPath) throws Exception {
        Properties props = loadProps(configPath);

        String natsUrl        = props.getProperty("nats.url",          "nats://localhost:4222");
        String serviceUser    = props.getProperty("nats.service.user",  "auth");
        String servicePass    = props.getProperty("nats.service.password", "auth");
        String signingKeySeed = require(props, "auth.signing.key.seed");
        String defaultAccount = props.getProperty("auth.default.account", "APP");
        String bucketName     = props.getProperty("kv.bucket.name",    "auth-users");

        log.info("Connecting to NATS at {} as '{}'", natsUrl, serviceUser);

        Connection nc = Nats.connect(new Options.Builder()
                .server(natsUrl)
                .userInfo(serviceUser, servicePass)
                .errorListener(new ErrorListener() {})
                .build());

        KvStoreAuthHandler handler = new KvStoreAuthHandler(nc, bucketName, defaultAccount);
        AuthCalloutService service = new AuthCalloutService(nc, handler, signingKeySeed);
        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            service.stop();
            try { nc.close(); } catch (Exception ignored) {}
        }));

        log.info("AuthCallout service running. Press Ctrl+C to stop.");
        service.doneFuture().get();
    }

    private static Properties loadProps(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = path != null
                ? new FileInputStream(path)
                : ServiceRunner.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (in == null) throw new IOException("config.properties not found");
            p.load(in);
        }
        return p;
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Required config key missing: " + key);
        return v;
    }
}
