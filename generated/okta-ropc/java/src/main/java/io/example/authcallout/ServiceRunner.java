package io.example.authcallout;

import io.nats.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;

public class ServiceRunner {
    private static final Logger log = LoggerFactory.getLogger(ServiceRunner.class);

    public static void run(String configPath) throws Exception {
        Properties p = load(configPath);
        String natsUrl       = p.getProperty("nats.url",              "nats://localhost:4222");
        String serviceUser   = p.getProperty("nats.service.user",     "auth");
        String servicePass   = p.getProperty("nats.service.password", "auth");
        String signingKey    = require(p, "auth.signing.key.seed");
        String defaultAcct   = p.getProperty("auth.default.account",  "APP");
        String domain        = require(p, "okta.domain");
        String clientId      = require(p, "okta.client.id");
        String clientSecret  = require(p, "okta.client.secret");
        String scopes        = p.getProperty("okta.scopes",           "openid profile email");
        String tokenPath     = p.getProperty("okta.token.path",       "/oauth2/v1/token");

        Connection nc = Nats.connect(new Options.Builder()
                .server(natsUrl).userInfo(serviceUser, servicePass)
                .errorListener(new ErrorListener() {}).build());

        OktaRopcAuthHandler handler = new OktaRopcAuthHandler(
                domain, clientId, clientSecret, scopes, tokenPath, defaultAcct);
        AuthCalloutService service = new AuthCalloutService(nc, handler, signingKey);
        service.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.stop();
            try { nc.close(); } catch (Exception ignored) {}
        }));

        log.info("AuthCallout service running (Okta ROPC). Press Ctrl+C to stop.");
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
