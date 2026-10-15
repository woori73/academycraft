package com.rea.academy.controller;

import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.dto.AuthDto;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.service.AuthService;
import com.rea.academy.service.MemberService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 인증 API.
 *
 * - {@code POST /key-login}: 기존 인증키 로그인 (개발 환경 호환용)
 * - {@code POST /login}: 정식 아이디·비밀번호 로그인 → Access Token(본문) + Refresh Token(HttpOnly 쿠키)
 * - {@code POST /refresh}: Refresh Token(쿠키)으로 Access Token 재발급 (로테이션)
 * - {@code POST /logout}: Refresh Token 무효화 + 쿠키 삭제
 * - {@code POST /password-reset/request|confirm}: 비밀번호 재설정
 * - {@code POST /setup-credentials}: 개발 환경 전용 최초 자격 설정
 *
 * 토큰 저장 방식:
 * - Access Token: 메모리에만 보관 (XSS 시 localStorage 탈취 방지 — SPA는 메모리 변수 권장)
 * - Refresh Token: HttpOnly + SameSite=Strict 쿠키 (JS 접근 불가)
 */
@RestController
@RequestMapping("/api/rea/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "ACRT";

    private final MemberService memberService;
    private final AuthService authService;

    @Value("${rea.security.cookie-secure:false}")
    private boolean cookieSecure;

    // ================= 기존 인증키 로그인 (개발 호환) =================

    @PostMapping("/key-login")
    public ResponseEntity<MemberDto.KeyLoginResponse> loginByKey(
            @RequestBody MemberDto.KeyLoginRequest req) {
        return ResponseEntity.ok(memberService.loginByKey(req.getAuthKey()));
    }

    // ================= 정식 로그인 =================

    @PostMapping("/login")
    public ResponseEntity<AuthDto.LoginResponse> login(
            @Valid @RequestBody AuthDto.LoginRequest req,
            HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(req.getLoginId(), req.getPassword());
        setRefreshCookie(response, result.refreshToken());
        ReaMember m = result.member();
        return ResponseEntity.ok(AuthDto.LoginResponse.builder()
                .tokenType("Bearer")
                .accessToken(result.accessToken())
                .expiresIn(15 * 60)
                .memberId(m.getId())
                .academyId(m.getAcademyId())
                .role(m.getRole().name())
                .name(m.getName())
                .build());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDto.RefreshResponse> refresh(
            HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractRefreshCookie(request);
        AuthService.RefreshResult result = authService.refresh(refreshToken);
        setRefreshCookie(response, result.refreshToken());
        return ResponseEntity.ok(AuthDto.RefreshResponse.builder()
                .tokenType("Bearer")
                .accessToken(result.accessToken())
                .expiresIn(15 * 60)
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request, HttpServletResponse response) {
        authService.logout(extractRefreshCookieOrNull(request));
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    // ================= 비밀번호 재설정 =================

    @PostMapping("/password-reset/request")
    public ResponseEntity<AuthDto.PasswordResetRequestResponse> requestPasswordReset(
            @Valid @RequestBody AuthDto.PasswordResetRequest req) {
        return ResponseEntity.ok(authService.requestPasswordReset(req.getLoginId()));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody AuthDto.PasswordResetConfirm req) {
        authService.confirmPasswordReset(req.getToken(), req.getNewPassword());
        return ResponseEntity.noContent().build();
    }

    // ================= 쿠키 =================

    private void setRefreshCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/rea/auth")
                .maxAge(Duration.ofDays(7))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/rea/auth")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        String token = extractRefreshCookieOrNull(request);
        if (token == null) {
            throw new com.rea.academy.security.ReaAuthenticationException(
                    "리프레시 토큰이 없습니다.");
        }
        return token;
    }

    private String extractRefreshCookieOrNull(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (REFRESH_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    // ================= 개발 환경 전용 =================

    /**
     * 개발 환경 전용: 기존 인증키 회원의 최초 로그인 자격 설정.
     * 운영(prod) 프로파일에서는 빈이 등록되지 않는다.
     */
    @RestController
    @RequestMapping("/api/rea/auth")
    @Profile("!prod")
    @RequiredArgsConstructor
    public static class DevSetupController {

        private final AuthService authService;

        @PostMapping("/setup-credentials")
        public ResponseEntity<Void> setupCredentials(
                @Valid @RequestBody AuthDto.SetupCredentialsRequest req) {
            authService.setupCredentials(
                    req.getAuthKey(), req.getLoginId(), req.getNewPassword());
            return ResponseEntity.noContent().build();
        }
    }
}
