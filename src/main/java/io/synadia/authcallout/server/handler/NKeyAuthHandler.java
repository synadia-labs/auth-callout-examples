package io.synadia.authcallout.server.handler;

import io.synadia.authcallout.model.AuthResult;

/**
 * Decentralized authentication handler that validates a client by its NKey public key.
 *
 * Used in Scenario 3 (decentralized NKey + default_sentinel) where the client
 * performs NKey challenge-response. The AuthCallout service receives the client's
 * public NKey and verifies it against a trusted store.
 */
public interface NKeyAuthHandler extends AutoCloseable {

    /**
     * Authenticate a client by its NKey public key.
     *
     * @param nkeyPublic the client's public NKey (from AuthorizationRequest.userNkey)
     * @return AuthResult indicating success or failure
     */
    AuthResult authenticate(String nkeyPublic);
}
