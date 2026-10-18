package com.rea.academy.domain.ops;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 학원 일정 엔티티 (7단계 운영 관리).
 * - 학원 전체 일정 또는 반별 일정 등록 (제목·일자·시간·대상·메모)
 * - 상담 일정: 5단계 상담 스레드와 선택적 연결 (consultationId)
 *   예) 상담 스레드에서 약속한 대면 상담 일시를 일정으로 등록
 *
 * 모든 데이터에 academyId를 포함 (학원별 격리).
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 일정 제목 (필수) */
    @Column(nullable = false, length = 200)
    private String title;

    /** 일정 일자 (필수) */
    @Column(nullable = false)
    private LocalDate date;

    /** 시작 시각 (선택, HH:mm) */
    private LocalTime startTime;

    /** 종료 시각 (선택, HH:mm) */
    private LocalTime endTime;

    /** 대상 구분: ALL(학원 전체) / CLASS(반별) */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScheduleTargetType targetType = ScheduleTargetType.ALL;

    /** 대상 반 ID (CLASS인 경우, rea_classes.id) */
    private Long classId;

    /** 대상 반명 스냅샷 (등록 당시) */
    @Column(length = 100)
    private String className;

    /**
     * 연결된 상담 스레드 ID (선택, rea_consultations.id).
     * 상담 일정으로 등록하는 경우에만 사용한다.
     */
    private Long consultationId;

    /** 연결된 상담 스레드 제목 스냅샷 (선택) */
    @Column(length = 200)
    private String consultationTitle;

    /** 메모 (선택) */
    @Column(length = 2000)
    private String memo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 일정 수정. null인 항목은 변경하지 않는다. */
    public void update(String title, LocalDate date, LocalTime startTime, boolean startTimeSet,
                       LocalTime endTime, boolean endTimeSet,
                       ScheduleTargetType targetType, Long classId, String className,
                       Long consultationId, boolean consultationIdSet, String consultationTitle,
                       String memo, boolean memoSet) {
        if (title != null) this.title = title;
        if (date != null) this.date = date;
        if (startTimeSet) this.startTime = startTime;
        if (endTimeSet) this.endTime = endTime;
        if (targetType != null) this.targetType = targetType;
        if (classId != null || targetType == ScheduleTargetType.ALL) {
            this.classId = classId;
            this.className = className;
        }
        if (consultationIdSet) {
            this.consultationId = consultationId;
            this.consultationTitle = consultationTitle;
        }
        if (memoSet) this.memo = memo;
    }
}
