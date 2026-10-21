package com.rea.academy.domain.learning;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 시험·성적 엔티티.
 * - 시험명·과목·점수·만점 입력. 백분율(rate)은 파생값으로 계산 (저장하지 않음).
 * - 비투 공개 동작: 스크립트(정형 양식)로 성적 발송 문구 생성 → 아이소식 1:1 전달.
 *   REA에서는 등록 시 학부모에게 GRADE 알림 발송 (NotificationService 재사용).
 * - 내신Wiki `student_scores`와는 별도 테이블. 향후 연동 시 examId 매핑 확장.
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_grades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 학생 ID (rea_students.id) */
    @Column(nullable = false)
    private Long studentId;

    /** 학생명 스냅샷 */
    @Column(length = 50)
    private String studentName;

    /** 시험명 (예: 10월 모의고사) */
    @Column(nullable = false, length = 100)
    private String examName;

    /** 과목 (예: 수학) */
    @Column(nullable = false, length = 50)
    private String subject;

    /** 점수 (0 이상, 만점 이하) */
    @Column(nullable = false)
    private Integer score;

    /** 만점 (1 이상) */
    @Column(nullable = false)
    private Integer fullScore;

    /** 시험 일자 (기본값=오늘) */
    @Column(nullable = false)
    private LocalDate examDate;

    /** 비고 (선택) */
    @Column(length = 500)
    private String note;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = ReaTime.now();
        if (this.examDate == null) {
            this.examDate = ReaTime.today();
        }
    }

    /** 백분율 (파생값, 소수점 1자리 반올림) */
    public double rate() {
        if (fullScore == null || fullScore == 0) return 0.0;
        return Math.round(score * 1000.0 / fullScore) / 10.0;
    }
}
