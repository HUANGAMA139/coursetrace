package com.coursetrace.config;

import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.auth.UserView;
import com.coursetrace.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        return http
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/csrf", "/api/auth/login", "/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated())
                // Spring's filter handles session fixation protection and clears CSRF on authentication.
                .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> write(json, response, 200,
                                ApiResponse.ok(UserView.from((AuthenticatedUser) authentication.getPrincipal()))))
                        .failureHandler((request, response, exception) -> write(json, response, 401,
                                ApiResponse.error("BAD_CREDENTIALS", "账号或密码不正确"))))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .deleteCookies("COURSETRACE_SESSION")
                        .logoutSuccessHandler((request, response, authentication) -> write(json, response, 200,
                                ApiResponse.ok(null))))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> write(json, response, 401,
                                ApiResponse.error("AUTHENTICATION_REQUIRED", "请先登录")))
                        .accessDeniedHandler((request, response, exception) -> write(json, response, 403,
                                ApiResponse.error(exception instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED",
                                        exception instanceof CsrfException ? "页面已过期，请刷新后重试" : "无权执行此操作"))))
                .build();
    }

    private static void write(ObjectMapper json, HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(), body);
    }
}
