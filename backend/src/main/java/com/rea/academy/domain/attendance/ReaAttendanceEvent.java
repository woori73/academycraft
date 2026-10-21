package com.rea.academy.domain.attendance;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 출결 이벤트 엔티티.
 * 비투 공개 동작 참고: 등원/하원 기록 + 결석·지각·조퇴 구분 + 강사 정정.
 * 모든 데이터에 academyId를 포함 (학원별 격리).
 *
 * 화면-기능 분리: 이 엔티티는 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_attendance_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaAttendanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 학생 ID (rea_students.id) */
    @Column(nullable = false)
    private Long studentId;

    /** 기록 시점 소속 반 ID (rea_classes.id, 선택). 기록 당시 기준 스냅샷. */
    private Long classId;

    /** 출결 구분 */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AttendanceEventType eventType;

    /** 발생 일시 (기록 대상 일시. 기본값=기록 시점) */
    @Column(nullable = false)
    private LocalDateTime occurredAt;

    /** 기록 경로 (KEYPAD / MANUAL) */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AttendanceSource source;

    /** 메모 (강사 기록 사유 등, 선택) */
    @Column(length = 500)
    private String note;

    /** 출결 사진 키 (FileStorage 연동용, 선택. 2단계에서는 설정값만 사용) */
    @Column(length = 255)
    private String photoKey;

    /** 정정 이력 존재 여부 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean corrected = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = ReaTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.occurredAt == null) {
            this.occurredAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = ReaTime.now();
    }

    /** 출결 정정 적용 (정정 이력 행은 별도 테이블에 기록) */
    public void correct(AttendanceEventType eventType, LocalDateTime occurredAt, String note) {
        if (eventType != null) this.eventType = eventType;
        if (occurredAt != null) this.occurredAt = occurredAt;
        this.note = note;
        this.corrected = true;
    }
}
