package io.example.authcallout;

/**
 * Entry point for the AuthCallout KV Store example.
 *
 * Usage:
 *   java -jar authcallout-kv-store-1.0.0.jar service [config.properties]
 *   java -jar authcallout-kv-store-1.0.0.jar client  [natsUrl] [username] [password] [subject] [message]
 *
 * If no mode is given, defaults to 'service' with config.properties on the classpath.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "service";

        switch (mode) {
            case "service" -> ServiceRunner.run(args.length > 1 ? args[1] : null);
            case "client"  -> ClientRunner.run(args);
            default -> {
                System.err.println("Unknown mode: " + mode);
                System.err.println("Usage: java -jar authcallout-kv-store-1.0.0.jar service|client [options]");
                System.exit(1);
            }
        }
    }
}
