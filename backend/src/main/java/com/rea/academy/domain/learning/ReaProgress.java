package com.rea.academy.domain.learning;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 학생별 학습 진도 기록 엔티티.
 * - 단원(unit)·진도율(progressRate, 0~100)·메모(memo)를 일자별로 기록
 * - 비투 공개 동작: 원생 개별 학습 진도 등록
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaProgress {

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

    /** 단원 (예: 수학Ⅱ 3단원 미분) */
    @Column(nullable = false, length = 200)
    private String unit;

    /** 진도율 (0~100) */
    @Column(nullable = false)
    private Integer progressRate;

    /** 메모 (선택) */
    @Column(length = 1000)
    private String memo;

    /** 기록 일자 (기본값=오늘) */
    @Column(nullable = false)
    private LocalDate recordedDate;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = ReaTime.now();
        if (this.recordedDate == null) {
            this.recordedDate = ReaTime.today();
        }
    }
}
