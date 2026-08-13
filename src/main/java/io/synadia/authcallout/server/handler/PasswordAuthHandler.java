package io.synadia.authcallout.server.handler;

import io.synadia.authcallout.model.AuthResult;

public interface PasswordAuthHandler extends AutoCloseable {

    AuthResult authenticate(String username, String password);

    @Override
    default void close() throws Exception {
        // no-op by default; override if resources need releasing
    }
}
