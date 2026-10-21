package com.rea.academy.domain.member;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;
import java.time.LocalDateTime;

/**
 * REA 회원 엔티티.
 * 비투의 인증키 방식을 적용: 회원가입 없이 인증키로 로그인.
 * - 원장/강사는 직접 인증키 발급
 * - 학부모는 학생 등록 시 발급되는 학생 인증키로 로그인 (별도 회원가입 없음)
 *
 * 화면-기능 분리: 이 엔티티는 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 역할: 원장/강사/학부모 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReaRole role;

    /** 이름 */
    @Column(nullable = false, length = 50)
    private String name;

    /**
     * 인증키 (비투 방식).
     * - 원장/강사: 직접 발급
     * - 학부모: 학생 등록 시 발급되는 키를 공유받아 사용
     */
    @Column(nullable = false, unique = true, length = 32)
    private String authKey;

    /** 연락처 (선택) */
    @Column(length = 20)
    private String phone;

    /** 이메일 (선택, 비밀번호 재설정 링크 발송용) */
    @Column(length = 100)
    private String email;

    /**
     * 로그인 아이디 (정식 로그인용, 전역 유니크).
     * 기존 인증키 회원에게는 null일 수 있음 — 최초 로그인 설정 시 발급.
     */
    @Column(unique = true, length = 50)
    private String loginId;

    /** BCrypt 해시 비밀번호 (평문 절대 저장 금지) */
    @Column(length = 100)
    private String passwordHash;

    /** 연속 로그인 실패 횟수 */
    @Column(nullable = false)
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    /** 계정 잠금 해제 시각 (null이면 잠금 없음) */
    private LocalDateTime lockedUntil;

    /** 비밀번호 재설정 토큰 (평문이 아닌 랜덤 토큰) */
    @Column(length = 64)
    private String passwordResetToken;

    /** 재설정 토큰 만료 시각 */
    private LocalDateTime passwordResetExpiresAt;

    /** 현재 유효한 리프레시 토큰의 SHA-256 해시 (로테이션 검증용) */
    @Column(length = 64)
    private String refreshTokenHash;

    /**
     * 학부모(PARENT) 계정에 연결된 자녀 학생 ID (rea_students.id, 논리 FK).
     * 원장·강사는 null.
     */
    private Long linkedStudentId;

    /** 활성 여부 (삭제 시 비활성, 복구 불가 안내) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = ReaTime.now();
        this.updatedAt = ReaTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = ReaTime.now();
    }

    /** 비활성화 (삭제) */
    public void deactivate() {
        this.active = false;
    }

    /** 인증키 재발급 */
    public void renewAuthKey(String newAuthKey) {
        this.authKey = newAuthKey;
    }

    // ================= 정식 로그인 (Spring Security 전환) =================

    /** 로그인 자격 설정 (아이디 + BCrypt 해시) */
    public void setCredentials(String loginId, String passwordHash) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
    }

    /** 로그인 자격 보유 여부 */
    public boolean hasCredentials() {
        return loginId != null && passwordHash != null;
    }

    /** 계정 잠금 여부 */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(ReaTime.now());
    }

    /** 로그인 실패 기록 (5회 연속 시 15분 잠금 — 호출자가 임계값 관리) */
    public void recordLoginFailure(int maxAttempts, int lockMinutes) {
        this.failedLoginAttempts = (this.failedLoginAttempts == null ? 0 : this.failedLoginAttempts) + 1;
        if (this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = ReaTime.now().plusMinutes(lockMinutes);
        }
    }

    /** 로그인 성공 시 실패 카운터 초기화 */
    public void resetLoginFailures() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /** 비밀번호 재설정 토큰 발급 */
    public void issuePasswordResetToken(String token, int expiryMinutes) {
        this.passwordResetToken = token;
        this.passwordResetExpiresAt = ReaTime.now().plusMinutes(expiryMinutes);
    }

    /** 재설정 토큰 유효성 */
    public boolean isPasswordResetTokenValid(String token) {
        return token != null
                && token.equals(this.passwordResetToken)
                && this.passwordResetExpiresAt != null
                && this.passwordResetExpiresAt.isAfter(ReaTime.now());
    }

    /** 비밀번호 변경 (재설정 토큰·실패 카운터·리프레시 토큰 초기화) */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.passwordResetToken = null;
        this.passwordResetExpiresAt = null;
        this.refreshTokenHash = null;
        resetLoginFailures();
    }

    /** 리프레시 토큰 해시 저장 (로테이션) */
    public void setRefreshTokenHash(String hash) {
        this.refreshTokenHash = hash;
    }

    /** 리프레시 토큰 무효화 (로그아웃) */
    public void clearRefreshToken() {
        this.refreshTokenHash = null;
    }

    /** 저장된 리프레시 토큰과 대조 */
    public boolean matchesRefreshTokenHash(String hash) {
        return hash != null && hash.equals(this.refreshTokenHash);
    }

    /** 학부모 계정의 자녀 학생 연결 */
    public void linkStudent(Long studentId) {
        this.linkedStudentId = studentId;
    }

    /** 이메일 변경 (비밀번호 재설정 링크 발송용) */
    public void changeEmail(String email) {
        this.email = (email == null || email.isBlank()) ? null : email.trim();
    }
}
