package com.rea.academy.security;

import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaStudent;
import com.rea.academy.domain.member.ReaStudentRepository;
import com.rea.academy.domain.member.ReaRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 인증키 기반 인증 필터.
 *
 * - HTTP 헤더 {@code X-Auth-Key: {authKey}} 로 인증키 전달
 * - {@code /api/rea/**} 전체에 적용 (단, {@code /api/rea/auth/**} — 인증키 로그인 — 제외)
 * - 인증키 누락·무효 → 401 JSON 응답 후 체인 중단
 * - 인증 성공 → {@link AuthContextHolder} + Spring SecurityContext에 인증 정보 저장
 *
 * 주의: 서블릿 컨테이너 자동 등록을 막기 위해 FilterRegistrationBean(enabled=false)으로
 * 등록하고, Spring Security 필터 체인에만 편입한다 (SecurityConfig 참조).
 */
public class AuthKeyFilter extends OncePerRequestFilter {

    public static final String AUTH_KEY_HEADER = "X-Auth-Key";

    private static final String API_PREFIX = "/api/rea/";
    private static final String AUTH_PUBLIC_PREFIX = "/api/rea/auth/";

    private final ReaMemberRepository memberRepository;
    private final ReaStudentRepository studentRepository;

    public AuthKeyFilter(ReaMemberRepository memberRepository,
                         ReaStudentRepository studentRepository) {
        this.memberRepository = memberRepository;
        this.studentRepository = studentRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(API_PREFIX) || uri.startsWith(AUTH_PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        String authKey = request.getHeader(AUTH_KEY_HEADER);
        if (authKey == null || authKey.isBlank()) {
            reject(response, "인증키가 필요합니다. (X-Auth-Key 헤더)");
            return;
        }

        AuthContext context = resolve(authKey.trim());
        if (context == null) {
            reject(response, "유효하지 않은 인증키입니다.");
            return;
        }

        AuthContextHolder.set(context);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        context,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + context.role().name()))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 인증키 → 인증 컨텍스트 해석.
     * 1) 원장·강사 키 (ReaMember) 2) 학생 키 → 학부모(PARENT) (ReaStudent) 순서로 조회.
     * 비활성(active=false) 키는 인증 실패로 처리.
     */
    private AuthContext resolve(String authKey) {
        var memberOpt = memberRepository.findByAuthKeyAndActiveTrue(authKey);
        if (memberOpt.isPresent()) {
            ReaMember m = memberOpt.get();
            return new AuthContext(
                    AuthContext.PrincipalType.MEMBER,
                    m.getId(), m.getAcademyId(), m.getRole(), m.getName());
        }

        var studentOpt = studentRepository.findByAuthKeyAndActiveTrue(authKey);
        if (studentOpt.isPresent()) {
            ReaStudent s = studentOpt.get();
            return new AuthContext(
                    AuthContext.PrincipalType.STUDENT,
                    s.getId(), s.getAcademyId(), ReaRole.PARENT,
                    s.getParentName() != null ? s.getParentName() : s.getName() + " 학부모");
        }

        return null;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + escape(message) + "\"}");
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
