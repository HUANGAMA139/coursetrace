package com.coursetrace.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

public final class AuthenticatedUser implements UserDetails, CredentialsContainer {
    @Serial
    private static final long serialVersionUID = 1L;
    private final long id;
    private final String username;
    private final String displayName;
    private String passwordHash;

    public AuthenticatedUser(AppUser user) {
        id = user.getId();
        username = user.getUsername();
        displayName = user.getDisplayName();
        passwordHash = user.getPasswordHash();
    }

    public long id() { return id; }
    public String displayName() { return displayName; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    @Override public void eraseCredentials() { passwordHash = null; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_STUDENT"));
    }
}
