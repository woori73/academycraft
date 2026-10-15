package com.rea.academy.domain.learning;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 숙제 검사 결과 엔티티.
 * - (homeworkId, studentId)당 1행 (upsert). 강사가 재검사 시 갱신.
 * - 검사 결과: 완료(COMPLETED) / 미완료(INCOMPLETE) + 선택 점수·코멘트
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_homework_checks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_homework_check", columnNames = {"homework_id", "student_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaHomeworkCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 숙제 ID (rea_homework.id) */
    @Column(name = "homework_id", nullable = false)
    private Long homeworkId;

    /** 검사 대상 학생 ID (rea_students.id) */
    @Column(name = "student_id", nullable = false)
    private Long studentId;

    /** 검사 대상 학생명 스냅샷 */
    @Column(length = 50)
    private String studentName;

    /** 검사 결과: 완료 / 미완료 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HomeworkCheckStatus status;

    /** 검사 점수 (선택, 0 이상) */
    private Integer score;

    /** 검사 코멘트 (선택) */
    @Column(length = 500)
    private String comment;

    /** 검사 일시 */
    @Column(nullable = false)
    private LocalDateTime checkedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.checkedAt == null) {
            this.checkedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 재검사: 결과·점수·코멘트 갱신 */
    public void recheck(HomeworkCheckStatus status, Integer score, String comment) {
        this.status = status;
        this.score = score;
        this.comment = comment;
        this.checkedAt = LocalDateTime.now();
    }
}
