package com.rea.academy.domain.ops;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 강사 담당 업무 메모 엔티티 (7단계 운영 관리).
 * - 담당 반은 기존 반(rea_classes)의 teacherId로 조회하므로 별도 저장하지 않는다.
 * - 이 엔티티는 "담당 업무"에 해당하는 자유 메모만 저장한다
 *   (예: "월수금 초등부 관리, 상담 요일: 화·목").
 * - (academyId, teacherId)당 1행. upsert 방식으로 관리한다.
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_teacher_assignments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rea_teacher_assignment", columnNames = {"academyId", "teacherId"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaTeacherAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 강사 회원 ID (rea_members.id) */
    @Column(nullable = false)
    private Long teacherId;

    /** 담당 업무 메모 (선택) */
    @Column(length = 2000)
    private String dutyMemo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = ReaTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = ReaTime.now();
    }

    /** 담당 업무 메모 변경 */
    public void updateDutyMemo(String dutyMemo) {
        this.dutyMemo = dutyMemo;
    }
}
