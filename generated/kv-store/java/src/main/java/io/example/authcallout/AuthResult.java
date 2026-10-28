package io.example.authcallout;

import io.nats.jwt.UserClaim;

/**
 * Outcome of an authentication attempt.
 */
public class AuthResult {

    private final boolean success;
    private final String  account;
    private final String  error;
    private final UserClaim userClaim;

    private AuthResult(boolean success, String account, String error, UserClaim userClaim) {
        this.success   = success;
        this.account   = account;
        this.error     = error;
        this.userClaim = userClaim;
    }

    public static AuthResult success(String account) {
        return new AuthResult(true, account, null, new UserClaim());
    }

    public static AuthResult failure(String error) {
        return new AuthResult(false, null, error, null);
    }

    public boolean   isSuccess()  { return success; }
    public String    getAccount() { return account; }
    public String    getError()   { return error; }
    public UserClaim getUserClaim() { return userClaim; }

    @Override
    public String toString() {
        return success ? "SUCCESS(account=" + account + ")" : "FAILURE(" + error + ")";
    }
}
