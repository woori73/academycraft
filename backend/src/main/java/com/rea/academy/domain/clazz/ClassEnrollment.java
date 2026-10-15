package com.rea.academy.domain.clazz;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 반-학생 매핑 엔티티.
 * (student, academy, class) 3자 연결. 한 학생은 여러 반에 속할 수 있다.
 * 이력은 soft delete(is_active=false)로 남기고 재등록 시 신규 행 생성.
 *
 * 화면-기능 분리: 이 엔티티는 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_class_enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ClassEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 반 ID (rea_classes.id) */
    @Column(nullable = false)
    private Long classId;

    /** 학생 ID (rea_students.id) */
    @Column(nullable = false)
    private Long studentId;

    /** 등록 일시 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime enrolledAt;

    /** 활성 여부 (제외·이동 시 false) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.enrolledAt == null) this.enrolledAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 반에서 제외 (비활성) */
    public void deactivate() {
        this.active = false;
    }
}
