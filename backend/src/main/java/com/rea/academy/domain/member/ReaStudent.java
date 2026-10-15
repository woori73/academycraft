package com.rea.academy.domain.member;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * REA 학생 엔티티.
 * 비투 방식: 원장이 학생을 등록하면 학생 인증키가 발급되고,
 * 학부모는 그 인증키로 로그인 (학부모 별도 회원가입 없음).
 */
@Entity
@Table(name = "rea_students")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 학생 이름 */
    @Column(nullable = false, length = 50)
    private String name;

    /** 학년 (선택) */
    @Column(length = 20)
    private String grade;

    /** 학교명 (선택) */
    @Column(length = 100)
    private String schoolName;

    /**
     * 학생 인증키.
     * 학부모가 이 키로 로그인한다.
     */
    @Column(nullable = false, unique = true, length = 32)
    private String authKey;

    /** 학부모 이름 (선택) */
    @Column(length = 50)
    private String parentName;

    /** 학부모 연락처 (알림 수신용, 선택) */
    @Column(length = 20)
    private String parentPhone;

    /** 활성 여부 */
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

    public void deactivate() {
        this.active = false;
    }

    public void renewAuthKey(String newAuthKey) {
        this.authKey = newAuthKey;
    }

    public void updateParentInfo(String parentName, String parentPhone) {
        this.parentName = parentName;
        this.parentPhone = parentPhone;
    }

    public void updateInfo(String name, String grade, String schoolName) {
        if (name != null && !name.isBlank()) this.name = name;
        if (grade != null) this.grade = grade;
        if (schoolName != null) this.schoolName = schoolName;
    }
}
