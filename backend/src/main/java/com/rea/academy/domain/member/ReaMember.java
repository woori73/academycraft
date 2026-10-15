package com.rea.academy.domain.member;

import jakarta.persistence.*;
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

    /** 활성 여부 (삭제 시 비활성, 복구 불가 안내) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 비활성화 (삭제) */
    public void deactivate() {
        this.active = false;
    }

    /** 인증키 재발급 */
    public void renewAuthKey(String newAuthKey) {
        this.authKey = newAuthKey;
    }
}
