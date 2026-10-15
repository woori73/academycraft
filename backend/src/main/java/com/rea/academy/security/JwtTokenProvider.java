package com.rea.academy.security;

import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 토큰 발급·검증.
 *
 * - Access Token: 15분, {@code Authorization: Bearer} 헤더로 전달
 * - Refresh Token: 7일, HttpOnly 쿠키로 전달 (XSS로 탈취 불가)
 * - 클레임: memberId(sub), academyId, role, type(access|refresh)
 * - 시크릿: {@code rea.jwt.secret} / 환경변수 {@code REA_JWT_SECRET}.
 *   미설정 시起動마다 랜덤 생성 (개발 편의, 운영에서는 반드시 설정 — 토큰 무효화됨).
 */
@Slf4j
@Component
public class JwtTokenProvider {

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    public static final long ACCESS_TOKEN_VALIDITY_MS = 15 * 60 * 1000L;
    public static final long REFRESH_TOKEN_VALIDITY_MS = 7 * 24 * 60 * 60 * 1000L;

    @Value("${rea.jwt.secret:}")
    private String secretProperty;

    @Value("${rea.jwt.issuer:academycraft}")
    private String issuer;

    private SecretKey key;

    @PostConstruct
    void init() {
        String secret = secretProperty;
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("[JWT] rea.jwt.secret 미설정 — 랜덤 시크릿 사용 (재시작 시 토큰 무효화). "
                    + "운영에서는 REA_JWT_SECRET 환경변수를 반드시 설정하세요.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("rea.jwt.secret은 256비트(32바이트) 이상이어야 합니다.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 테스트용: 고정 시크릿으로 초기화 */
    void initForTest(String secret) {
        this.secretProperty = secret;
        this.issuer = "academycraft-test";
        init();
    }

    public String createAccessToken(ReaMember member) {
        return createToken(member, TOKEN_TYPE_ACCESS, ACCESS_TOKEN_VALIDITY_MS);
    }

    public String createRefreshToken(ReaMember member) {
        return createToken(member, TOKEN_TYPE_REFRESH, REFRESH_TOKEN_VALIDITY_MS);
    }

    private String createToken(ReaMember member, String type, long validityMs) {
        Date now = new Date();
        var builder = Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(member.getId()))
                .claim("academyId", member.getAcademyId())
                .claim("role", member.getRole().name())
                .claim("type", type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMs));
        if (TOKEN_TYPE_REFRESH.equals(type)) {
            // 동일 초에 연속 발급해도 고유하도록 (로테이션 검증 정확도)
            builder.id(java.util.UUID.randomUUID().toString());
        }
        return builder.signWith(key).compact();
    }

    /** 토큰 검증 후 클레임 반환. 위조·만료·타입 불일치 시 JwtException. */
    public Claims parseAndValidate(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String type = claims.get("type", String.class);
        if (!expectedType.equals(type)) {
            throw new JwtException("토큰 타입 불일치: " + type);
        }
        return claims;
    }

    public Long memberIdOf(Claims claims) {
        return Long.parseLong(claims.getSubject());
    }

    public Long academyIdOf(Claims claims) {
        return claims.get("academyId", Long.class);
    }

    public ReaRole roleOf(Claims claims) {
        return ReaRole.valueOf(claims.get("role", String.class));
    }
}
