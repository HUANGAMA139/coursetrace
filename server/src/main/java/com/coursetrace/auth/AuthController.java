package com.coursetrace.auth;

import com.coursetrace.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @GetMapping("/me")
    ApiResponse<UserView> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(UserView.from(user));
    }

    // Reading the deferred token also creates the anonymous session used for login CSRF protection.
    @GetMapping("/csrf")
    ApiResponse<CsrfView> csrf(CsrfToken token) {
        return ApiResponse.ok(new CsrfView(token.getHeaderName(), token.getToken()));
    }

    public record CsrfView(String headerName, String token) {}
}
