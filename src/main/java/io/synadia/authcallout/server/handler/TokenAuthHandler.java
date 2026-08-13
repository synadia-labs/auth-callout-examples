package io.synadia.authcallout.server.handler;

import io.synadia.authcallout.model.AuthResult;

public interface TokenAuthHandler extends AutoCloseable {

    AuthResult authenticateToken(String token);

    @Override
    default void close() throws Exception {
        // no-op by default
    }
}
