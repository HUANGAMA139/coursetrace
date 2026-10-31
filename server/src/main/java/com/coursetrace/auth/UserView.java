package com.coursetrace.auth;

public record UserView(long id, String username, String displayName) {
    public static UserView from(AuthenticatedUser user) {
        return new UserView(user.id(), user.getUsername(), user.displayName());
    }
}
