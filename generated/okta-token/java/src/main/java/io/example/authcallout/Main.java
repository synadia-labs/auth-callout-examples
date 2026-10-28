package io.example.authcallout;

public class Main {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "service";
        switch (mode) {
            case "service" -> ServiceRunner.run(args.length > 1 ? args[1] : null);
            case "client"  -> TokenClientRunner.run(args);
            default -> { System.err.println("Usage: java -jar authcallout-okta-token-1.0.0.jar service|client"); System.exit(1); }
        }
    }
}
