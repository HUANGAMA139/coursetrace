package com.coursetrace.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@Profile("local")
public class LocalDemoAccounts implements ApplicationRunner {
    private final AppUserMapper users;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public LocalDemoAccounts(AppUserMapper users, PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("student", "学习者");
        seed("student2", "另一位同学");
    }

    private void seed(String username, String displayName) {
        if (users.selectCount(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username)) > 0) return;
        var user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwords.encode("CourseTrace@123"));
        user.setCreatedAt(LocalDateTime.now(clock));
        users.insert(user);
    }
}
