package io.example.authcallout;

import io.nats.jwt.UserClaim;

public class AuthResult {
    private final boolean success; private final String account, error; private final UserClaim userClaim;
    private AuthResult(boolean s, String a, String e, UserClaim u) { success=s; account=a; error=e; userClaim=u; }
    public static AuthResult success(String account) { return new AuthResult(true,account,null,new UserClaim()); }
    public static AuthResult failure(String error)   { return new AuthResult(false,null,error,null); }
    public boolean isSuccess()    { return success; }
    public String  getAccount()   { return account; }
    public String  getError()     { return error; }
    public UserClaim getUserClaim() { return userClaim; }
    @Override public String toString() { return success ? "SUCCESS("+account+")" : "FAILURE("+error+")"; }
}
