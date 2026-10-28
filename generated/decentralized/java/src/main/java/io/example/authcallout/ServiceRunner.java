package io.example.authcallout;

import io.nats.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ServiceRunner {
    private static final Logger log = LoggerFactory.getLogger(ServiceRunner.class);

    public static void run(String configPath) throws Exception {
        Properties p = load(configPath);

        String natsUrl       = p.getProperty("nats.url",             "nats://localhost:4222");
        String serviceCreds  = require(p, "auth.decentralized.service.creds");
        String signingKeyPath= require(p, "auth.decentralized.signing.key.path");
        String signingKeySeed= Files.readString(Path.of(signingKeyPath)).strip();
        String issuerAccount = require(p, "auth.decentralized.issuer.account");
        String defaultAccount= require(p, "auth.decentralized.default.account");
        String kvBucket      = p.getProperty("kv.bucket.name", "auth-users");
        String nkeyBucket    = p.getProperty("auth.decentralized.nkey.bucket", "auth-nkeys");
        boolean nkeyEnabled  = Boolean.parseBoolean(
                p.getProperty("auth.decentralized.nkey.enabled", "true"));

        // Service connects with AUTH account .creds — bypasses auth callout
        Connection nc = Nats.connect(new Options.Builder()
                .server(natsUrl)
                .authHandler(Nats.credentials(serviceCreds))
                .errorListener(new ErrorListener() {}).build());

        KvStoreAuthHandler passwordHandler = null;
        try {
            passwordHandler = new KvStoreAuthHandler(nc, kvBucket, defaultAccount);
        } catch (Exception e) {
            log.warn("KV bucket '{}' not found — password auth disabled: {}", kvBucket, e.getMessage());
        }

        NKeyKvAuthHandler nkeyHandler = null;
        if (nkeyEnabled) {
            try {
                nkeyHandler = new NKeyKvAuthHandler(nc, nkeyBucket, defaultAccount);
            } catch (Exception e) {
                log.warn("NKey KV bucket '{}' not found — NKey auth disabled: {}", nkeyBucket, e.getMessage());
            }
        }

        DecentralizedAuthCalloutService service = new DecentralizedAuthCalloutService(
                nc, passwordHandler, nkeyHandler, signingKeySeed, defaultAccount, issuerAccount);
        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.stop();
            try { nc.close(); } catch (Exception ignored) {}
        }));

        log.info("Decentralized AuthCallout service running. Press Ctrl+C to stop.");
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
