package com.rea.academy.security;

import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT Bearer 토큰 인증 필터.
 *
 * - {@code Authorization: Bearer {accessToken}} 헤더가 있을 때만 동작.
 *   헤더가 없으면 체인을 그대로 통과 (개발 환경의 X-Auth-Key 폴백 허용).
 * - 토큰 유효 → {@link AuthContextHolder} + SecurityContext에 인증 정보 저장.
 *   이후 {@link ReaSecurityInterceptor}의 테넌트 격리·역할 권한 검사가 그대로 적용된다.
 * - 토큰 누락이 아니라 무효·만료·위조면 401로 즉시 차단 (폴백 없음).
 * - 비활성 회원(active=false)의 토큰은 거부.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/rea/";
    private static final String AUTH_PUBLIC_PREFIX = "/api/rea/auth/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final ReaMemberRepository memberRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith(API_PREFIX) || uri.startsWith(AUTH_PUBLIC_PREFIX)) {
            return true;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header == null || !header.startsWith(BEARER_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(HttpHeaders.AUTHORIZATION)
                .substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = tokenProvider.parseAndValidate(
                    token, JwtTokenProvider.TOKEN_TYPE_ACCESS);
            Long memberId = tokenProvider.memberIdOf(claims);
            ReaMember member = memberRepository.findById(memberId).orElse(null);
            if (member == null || !Boolean.TRUE.equals(member.getActive())) {
                reject(response, "유효하지 않은 인증 토큰입니다. (회원 없음·비활성)");
                return;
            }
            // 토큰 클레임과 DB의 academyId·role이 다르면 위조 의심 → 거부
            Long claimAcademyId = tokenProvider.academyIdOf(claims);
            if (!claimAcademyId.equals(member.getAcademyId())
                    || tokenProvider.roleOf(claims) != member.getRole()) {
                log.warn("[JWT] 클레임 불일치 차단: memberId={}", memberId);
                reject(response, "유효하지 않은 인증 토큰입니다.");
                return;
            }

            AuthContext context = new AuthContext(
                    AuthContext.PrincipalType.MEMBER,
                    member.getId(),
                    member.getAcademyId(),
                    member.getRole(),
                    member.getName(),
                    member.getLinkedStudentId());
            AuthContextHolder.set(context);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            context, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + context.role().name()))));
            try {
                filterChain.doFilter(request, response);
            } finally {
                AuthContextHolder.clear();
                SecurityContextHolder.clearContext();
            }
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("[JWT] 토큰 검증 실패: {}", e.getMessage());
            reject(response, "인증 토큰이 유효하지 않거나 만료되었습니다.");
        }
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
