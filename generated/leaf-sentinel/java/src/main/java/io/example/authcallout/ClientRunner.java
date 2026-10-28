package io.example.authcallout;

/** Demo client for leaf-sentinel: NKey challenge-response against the hub. */
public class ClientRunner {
    public static void run(String[] args) throws Exception {
        String url     = at(args,1,"nats://localhost:4222");
        String seed    = at(args,2,"REPLACE_WITH_NKEY_SEED");
        String subject = at(args,3,"test");

        try (NatsNKeyClient client = new NatsNKeyClient(url, seed)) {
            client.connect();
            System.out.printf("[CLIENT] Connected via NKey (public: %s)%n", client.getPublicKey());
            client.subscribe(subject);
            client.publish(subject, "hello from NKey client");
            System.out.printf("[CLIENT] Published to '%s'%n", subject);
            Thread.sleep(500);
        } catch (io.nats.client.AuthenticationException e) {
            System.err.println("[CLIENT] Auth failed — verify NKey is in auth-nkeys and nonce sig is correct");
            System.exit(1);
        }
    }
    private static String at(String[] a,int i,String d){return a!=null&&a.length>i?a[i]:d;}
}
