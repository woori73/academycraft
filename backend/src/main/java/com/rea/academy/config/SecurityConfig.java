package com.rea.academy.config;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.security.AuthKeyFilter;
import com.rea.academy.security.JwtAuthenticationFilter;
import com.rea.academy.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * 보안 설정 (2단계 — Spring Security 기반 정식 로그인).
 *
 * 인증 흐름:
 * 1. {@code POST /api/rea/auth/login} → Access Token(Bearer) + Refresh Token(HttpOnly 쿠키)
 * 2. {@link JwtAuthenticationFilter}: {@code Authorization: Bearer} 검증 → 인증 컨텍스트 구성
 * 3. {@link AuthKeyFilter}: 개발·테스트 환경(!prod)에서만 X-Auth-Key 폴백 허용.
 *    운영(prod)에서는 등록되지 않아 정식 로그인이 유일한 인증 수단이다.
 * 4. {@code ReaSecurityInterceptor}: 테넌트 격리 + 역할 권한 검사 (기존 그대로)
 *
 * - 무상태(STATELESS) 세션
 * - 미인증 요청 → 401 JSON (기본 403 대신)
 * - CSRF: JWT를 헤더로 전달하므로 비활성화. Refresh 쿠키는 SameSite=Strict.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            ReaMemberRepository memberRepository) {
        return new JwtAuthenticationFilter(tokenProvider, memberRepository);
    }

    /**
     * X-Auth-Key 폴백은 개발·테스트 환경(!prod)에만 허용.
     * 운영에서는 정식 로그인이 유일한 인증 수단이다.
     */
    @Bean
    @Profile("!prod")
    public AuthKeyFilter authKeyFilter(ReaMemberRepository memberRepository,
                                       ReaStudentRepository studentRepository) {
        return new AuthKeyFilter(memberRepository, studentRepository);
    }

    @Bean
    @Profile("!prod")
    public FilterRegistrationBean<AuthKeyFilter> authKeyFilterRegistration(AuthKeyFilter filter) {
        FilterRegistrationBean<AuthKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticationEntryPoint reaAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"인증이 필요합니다.\"}");
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           ObjectProvider<AuthKeyFilter> authKeyFilterProvider,
                                           AuthenticationEntryPoint entryPoint)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/rea/auth/**").permitAll()
                        .requestMatchers("/api/rea/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);
        // 개발 환경에서만 X-Auth-Key 폴백 필터 추가 (JWT 필터 뒤)
        authKeyFilterProvider.ifAvailable(authKeyFilter ->
                http.addFilterAfter(authKeyFilter, JwtAuthenticationFilter.class));
        return http.build();
    }
}
