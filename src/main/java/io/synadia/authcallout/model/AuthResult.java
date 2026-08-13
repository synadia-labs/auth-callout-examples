package io.synadia.authcallout.model;

import io.nats.jwt.UserClaim;

public class AuthResult {

    private final boolean success;
    private final String account;
    private final UserClaim userClaim;
    private final String error;

    private AuthResult(boolean success, String account, UserClaim userClaim, String error) {
        this.success = success;
        this.account = account;
        this.userClaim = userClaim;
        this.error = error;
    }

    public static AuthResult success(String account, UserClaim userClaim) {
        return new AuthResult(true, account, userClaim, null);
    }

    public static AuthResult failure(String error) {
        return new AuthResult(false, null, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getAccount() {
        return account;
    }

    public UserClaim getUserClaim() {
        return userClaim;
    }

    public String getError() {
        return error;
    }

    @Override
    public String toString() {
        return success
                ? "AuthResult{success, account='" + account + "'}"
                : "AuthResult{failure, error='" + error + "'}";
    }
}
