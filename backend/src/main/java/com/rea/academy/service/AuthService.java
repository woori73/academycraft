package com.rea.academy.service;

import com.rea.academy.domain.member.ReaMember;
import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.domain.notification.delivery.NotificationProperties;
import com.rea.academy.domain.notification.delivery.PasswordResetDeliveryService;
import com.rea.academy.domain.notification.delivery.PasswordResetRateLimiter;
import com.rea.academy.dto.AuthDto;
import com.rea.academy.security.AuthContext;
import com.rea.academy.security.AuthContextHolder;
import com.rea.academy.security.JwtTokenProvider;
import com.rea.academy.security.ReaAuthenticationException;
import com.rea.academy.security.ReaForbiddenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 정식 로그인(JWT) 인증 서비스.
 *
 * - 아이디·비밀번호 로그인, BCrypt 해싱 (strength는 설정값)
 * - 연속 5회 실패 시 15분 계정 잠금
 * - Access(15분) + Refresh(7일) 토큰, Refresh 로테이션 (DB 해시 대조)
 * - 비밀번호 재설정 토큰 (30분 유효)
 * - 로그에 비밀번호·토큰 원문을 절대 기록하지 않는다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final ReaMemberRepository memberRepository;
    private final JwtTokenProvider tokenProvider;
    private final PasswordResetDeliveryService deliveryService;
    private final PasswordResetRateLimiter rateLimiter;
    private final NotificationProperties notificationProps;

    @Value("${rea.security.bcrypt-strength:12}")
    private int bcryptStrength;

    @Value("${rea.security.max-login-attempts:5}")
    private int maxLoginAttempts;

    @Value("${rea.security.lock-minutes:15}")
    private int lockMinutes;

    @Value("${rea.security.password-reset-minutes:30}")
    private int passwordResetMinutes;

    /** 개발 환경에서만 재설정 토큰을 응답에 포함 (운영은 SMS·이메일 발송) */
    @Value("${rea.security.expose-reset-token:false}")
    private boolean exposeResetToken;

    private BCryptPasswordEncoder encoder() {
        return new BCryptPasswordEncoder(bcryptStrength);
    }

    // ================= 로그인 =================

    @Transactional
    public LoginResult login(String loginId, String password) {
        Optional<ReaMember> opt = memberRepository.findByLoginIdAndActiveTrue(loginId);
        if (opt.isEmpty() || !opt.get().hasCredentials()) {
            // 존재 여부 누설 방지: 동일 메시지
            throw new ReaAuthenticationException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        ReaMember member = opt.get();
        if (member.isLocked()) {
            log.warn("[AUTH] 잠긴 계정 로그인 시도: loginId={}", maskLoginId(loginId));
            throw new ReaAuthenticationException("계정이 잠겼습니다. 15분 후 다시 시도하세요.");
        }
        if (!encoder().matches(password, member.getPasswordHash())) {
            member.recordLoginFailure(maxLoginAttempts, lockMinutes);
            memberRepository.save(member);
            log.warn("[AUTH] 로그인 실패 ({}회): loginId={}",
                    member.getFailedLoginAttempts(), maskLoginId(loginId));
            throw new ReaAuthenticationException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        member.resetLoginFailures();
        String accessToken = tokenProvider.createAccessToken(member);
        String refreshToken = tokenProvider.createRefreshToken(member);
        member.setRefreshTokenHash(sha256(refreshToken));
        memberRepository.save(member);
        log.info("[AUTH] 로그인 성공: memberId={}, role={}", member.getId(), member.getRole());
        return new LoginResult(member, accessToken, refreshToken);
    }

    // ================= 토큰 갱신 (로테이션) =================

    @Transactional
    public RefreshResult refresh(String refreshToken) {
        Claims claims;
        try {
            claims = tokenProvider.parseAndValidate(
                    refreshToken, JwtTokenProvider.TOKEN_TYPE_REFRESH);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ReaAuthenticationException("리프레시 토큰이 유효하지 않거나 만료되었습니다.");
        }
        Long memberId = tokenProvider.memberIdOf(claims);
        ReaMember member = memberRepository.findById(memberId)
                .filter(m -> Boolean.TRUE.equals(m.getActive()))
                .orElseThrow(() -> new ReaAuthenticationException(
                        "리프레시 토큰이 유효하지 않거나 만료되었습니다."));
        // 로테이션 검증: DB 해시와 대조 (탈취된 구 토큰 재사용 차단)
        if (!member.matchesRefreshTokenHash(sha256(refreshToken))) {
            log.warn("[AUTH] 리프레시 토큰 재사용 의심 차단: memberId={}", memberId);
            member.clearRefreshToken();
            memberRepository.save(member);
            throw new ReaAuthenticationException("리프레시 토큰이 유효하지 않거나 만료되었습니다.");
        }
        String newAccess = tokenProvider.createAccessToken(member);
        String newRefresh = tokenProvider.createRefreshToken(member);
        member.setRefreshTokenHash(sha256(newRefresh));
        memberRepository.save(member);
        return new RefreshResult(newAccess, newRefresh);
    }

    // ================= 로그아웃 =================

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            Claims claims = tokenProvider.parseAndValidate(
                    refreshToken, JwtTokenProvider.TOKEN_TYPE_REFRESH);
            Long memberId = tokenProvider.memberIdOf(claims);
            memberRepository.findById(memberId).ifPresent(m -> {
                m.clearRefreshToken();
                memberRepository.save(m);
            });
            log.info("[AUTH] 로그아웃: memberId={}", memberId);
        } catch (JwtException | IllegalArgumentException e) {
            // 이미 무효한 토큰이면 로그아웃은 성공으로 처리
            log.debug("[AUTH] 로그아웃 시 무효 토큰 무시");
        }
    }

    // ================= 비밀번호 재설정 =================

    @Transactional
    public AuthDto.PasswordResetRequestResponse requestPasswordReset(String loginId) {
        // 요청 제한: 초과해도 계정 존재 여부를 누설하지 않도록 동일 응답 반환
        boolean allowed = rateLimiter.tryAcquire(loginId);
        Optional<ReaMember> opt = memberRepository.findByLoginIdAndActiveTrue(loginId);
        String token = null;
        if (allowed && opt.isPresent() && opt.get().hasCredentials()) {
            ReaMember member = opt.get();
            token = randomToken();
            member.issuePasswordResetToken(token, passwordResetMinutes);
            memberRepository.save(member);
            // 토큰 로그 기록 금지. 발송 실패해도 토큰은 유효하게 유지 (재요청 가능).
            // 발송은 트랜잭션 커밋 후 비동기로 처리:
            //  - 커밋 전에는 발송하지 않음 (롤백 시 미발송 보장)
            //  - 비동기이므로 재시도·타임아웃이 HTTP 응답을 지연시키지 않음
            scheduleDeliveryAfterCommit(member, token);
            log.info("[AUTH] 비밀번호 재설정 토큰 발급: memberId={}", member.getId());
        }
        // 존재 여부 누설 방지: 항상 동일 응답
        return AuthDto.PasswordResetRequestResponse.builder()
                .resetToken(exposeResetToken ? token : null)
                .message("입력한 아이디가 등록되어 있으면 재설정 안내를 발송했습니다.")
                .build();
    }

    private String buildResetUrl(String token) {
        String base = notificationProps.getResetBaseUrl();
        if (base == null || base.isBlank()) {
            base = "";
        } else if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/screens/AUTH-02-password-reset.html?token=" + token;
    }

    /**
     * 트랜잭션 커밋 후 발송 예약.
     * 커밋 전에 발송하면 롤백 시 DB에 없는 토큰이 발송되는 문제가 생긴다.
     * 엔티티가 커밋 후 detach될 수 있으므로 필요한 값은 미리 복사한다.
     */
    private void scheduleDeliveryAfterCommit(ReaMember member, String token) {
        String phone = member.getPhone();
        String email = member.getEmail();
        Long memberId = member.getId();
        String resetUrl = buildResetUrl(token);
        Runnable send = () -> deliveryService.deliverAsync(phone, email, resetUrl, memberId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            // 트랜잭션 없이 호출된 경우 (방어): 즉시 발송
            send.run();
        }
    }

    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        validatePasswordPolicy(newPassword);
        ReaMember member = memberRepository.findByPasswordResetToken(token)
                .filter(m -> m.isPasswordResetTokenValid(token))
                .orElseThrow(() -> new ReaAuthenticationException(
                        "재설정 토큰이 유효하지 않거나 만료되었습니다."));
        member.changePassword(encoder().encode(newPassword));
        memberRepository.save(member);
        log.info("[AUTH] 비밀번호 재설정 완료: memberId={}", member.getId());
    }

    // ================= 자격 설정 =================

    /**
     * 개발 환경 전용: 기존 인증키 회원의 최초 로그인 자격 설정.
     * 운영에서는 원장 전용 API(setMemberCredentials)를 사용.
     */
    @Transactional
    public ReaMember setupCredentials(String authKey, String loginId, String newPassword) {
        validatePasswordPolicy(newPassword);
        ReaMember member = memberRepository.findByAuthKeyAndActiveTrue(authKey)
                .orElseThrow(() -> new ReaAuthenticationException("유효하지 않은 인증키입니다."));
        if (member.hasCredentials()) {
            throw new ReaAuthenticationException("이미 로그인 자격이 설정된 계정입니다.");
        }
        ensureLoginIdAvailable(loginId, null);
        member.setCredentials(loginId, encoder().encode(newPassword));
        memberRepository.save(member);
        log.info("[AUTH] 로그인 자격 최초 설정: memberId={}", member.getId());
        return member;
    }

    /**
     * 원장 전용: 회원의 로그인 자격 설정·초기화 (임시 비밀번호 발급).
     * 대상 회원은 같은 학원이어야 한다.
     */
    @Transactional
    public ReaMember setMemberCredentials(Long academyId, Long memberId,
                                          String loginId, String tempPassword,
                                          Long linkedStudentId) {
        AuthContext auth = AuthContextHolder.getOrNull();
        if (auth == null || !auth.isOwner() || !auth.academyId().equals(academyId)) {
            throw new ReaForbiddenException("원장만 회원 로그인 자격을 설정할 수 있습니다.");
        }
        validatePasswordPolicy(tempPassword);
        ReaMember member = memberRepository.findById(memberId)
                .filter(m -> m.getAcademyId().equals(academyId))
                .orElseThrow(() -> new ReaAuthenticationException("회원을 찾을 수 없습니다."));
        ensureLoginIdAvailable(loginId, memberId);
        member.setCredentials(loginId, encoder().encode(tempPassword));
        if (member.getRole() == ReaRole.PARENT && linkedStudentId != null) {
            member.linkStudent(linkedStudentId);
        }
        member.resetLoginFailures();
        memberRepository.save(member);
        log.info("[AUTH] 원장이 회원 자격 설정: memberId={}, by={}", memberId, auth.principalId());
        return member;
    }

    // ================= 내부 =================

    private void ensureLoginIdAvailable(String loginId, Long excludeMemberId) {
        Optional<ReaMember> existing = memberRepository.findByLoginId(loginId);
        if (existing.isPresent() && !existing.get().getId().equals(excludeMemberId)) {
            throw new ReaAuthenticationException("이미 사용 중인 아이디입니다.");
        }
    }

    /** 비밀번호 정책: 8자 이상, 영문+숫자 포함 */
    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8
                || !password.matches(".*[A-Za-z].*")
                || !password.matches(".*[0-9].*")) {
            throw new ReaAuthenticationException("비밀번호는 8자 이상, 영문과 숫자를 포함해야 합니다.");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 계산 실패", e);
        }
    }

    /** 로그용 아이디 마스킹 (앞 2자만) */
    private String maskLoginId(String loginId) {
        if (loginId == null || loginId.length() <= 2) {
            return "**";
        }
        return loginId.substring(0, 2) + "***";
    }

    // ================= 결과 DTO =================

    public record LoginResult(ReaMember member, String accessToken, String refreshToken) {
    }

    public record RefreshResult(String accessToken, String refreshToken) {
    }
}
