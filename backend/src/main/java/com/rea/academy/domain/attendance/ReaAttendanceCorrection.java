package com.rea.academy.domain.attendance;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 출결 정정 이력 엔티티.
 * 잘못된 출결 기록을 수정할 때마다 정정 전·후 값을 남긴다.
 * 원본 이벤트 행은 in-place로 수정되고, 변경 내역은 이 테이블에 보존된다.
 *
 * 화면-기능 분리: 이 엔티티는 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_attendance_corrections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaAttendanceCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 정정 대상 출결 이벤트 ID (rea_attendance_events.id) */
    @Column(nullable = false)
    private Long eventId;

    /** 정정 전 구분 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AttendanceEventType beforeType;

    /** 정정 후 구분 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AttendanceEventType afterType;

    /** 정정 전 발생 일시 */
    @Column(nullable = false)
    private LocalDateTime beforeOccurredAt;

    /** 정정 후 발생 일시 */
    @Column(nullable = false)
    private LocalDateTime afterOccurredAt;

    /** 정정 전 메모 */
    @Column(length = 500)
    private String beforeNote;

    /** 정정 후 메모 */
    @Column(length = 500)
    private String afterNote;

    /** 정정 사유 (필수) */
    @Column(nullable = false, length = 500)
    private String reason;

    /** 정정 일시 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime correctedAt;

    @PrePersist
    protected void onCreate() {
        if (this.correctedAt == null) {
            this.correctedAt = LocalDateTime.now();
        }
    }
}
