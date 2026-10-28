package io.example.authcallout;

import io.nats.client.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Demo: connects with username/password validated via Okta ROPC. */
public class ClientRunner {
    public static void run(String[] args) throws Exception {
        String url      = at(args,1,"nats://localhost:4222");
        String username = at(args,2,"alice@example.com");
        String password = at(args,3,"REPLACE_WITH_OKTA_PASSWORD");
        String subject  = at(args,4,"test");
        String message  = at(args,5,"hello from "+at(args,2,"alice"));

        try (Connection nc = Nats.connect(new Options.Builder()
                .server(url).userInfo(username,password).maxReconnects(0)
                .reconnectWait(Duration.ofSeconds(1)).errorListener(new ErrorListener(){}).build())) {
            System.out.printf("[CLIENT] Connected as '%s'%n",username);
            nc.createDispatcher(msg->System.out.printf("[CLIENT] Received: %s%n",
                    new String(msg.getData(),StandardCharsets.UTF_8))).subscribe(subject);
            nc.publish(subject,message.getBytes(StandardCharsets.UTF_8));
            System.out.printf("[CLIENT] Published '%s' to '%s'%n",message,subject);
            Thread.sleep(500);
        } catch (AuthenticationException e) {
            System.err.printf("[CLIENT] Auth failed for '%s' — check Okta credentials%n",username);
            System.exit(1);
        }
    }
    private static String at(String[] a,int i,String d) { return a!=null&&a.length>i?a[i]:d; }
}
