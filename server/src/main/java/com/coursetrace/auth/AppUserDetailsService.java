package com.coursetrace.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AppUserDetailsService implements UserDetailsService {
    private final AppUserMapper users;

    public AppUserDetailsService(AppUserMapper users) { this.users = users; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        if (username == null || username.length() > 64) throw new UsernameNotFoundException("User not found");
        var user = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, username.strip().toLowerCase(Locale.ROOT)));
        if (user == null) throw new UsernameNotFoundException("User not found");
        return new AuthenticatedUser(user);
    }
}
