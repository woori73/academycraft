package com.rea.academy.config;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.security.AuthKeyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 설정 (1단계 — 인증키 기반 인증 완성).
 *
 * - {@code X-Auth-Key} 헤더의 인증키로 모든 /api/rea/** 요청 인증 (key-login 제외)
 * - 인증키 누락·무효 → 401, 테넌트·역할 위반 → 403 (인터셉터 + 예외 핸들러)
 * - 무상태(STATELESS) 세션: 인증 정보는 요청별 ThreadLocal 컨텍스트로만 유지
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public AuthKeyFilter authKeyFilter(ReaMemberRepository memberRepository,
                                       ReaStudentRepository studentRepository) {
        return new AuthKeyFilter(memberRepository, studentRepository);
    }

    /**
     * AuthKeyFilter는 Spring Security 체인에서만 동작하도록 서블릿 컨테이너
     * 자동 등록을 비활성화한다 (이중 실행 방지).
     */
    @Bean
    public FilterRegistrationBean<AuthKeyFilter> authKeyFilterRegistration(AuthKeyFilter filter) {
        FilterRegistrationBean<AuthKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthKeyFilter authKeyFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/rea/auth/**").permitAll()
                        .requestMatchers("/api/rea/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(authKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
