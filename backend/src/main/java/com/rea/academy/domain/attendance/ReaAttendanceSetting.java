package com.rea.academy.domain.attendance;

import com.rea.academy.common.ReaTime;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 출결 설정 엔티티 (학원별 1행).
 * 비투 공개 설정 항목 참고: 하원 가능 시간, 출결 사진 전송, 알림 문구.
 * 2단계에서는 설정값 저장까지만. 실제 사진 업로드·알림 발송은 이후 단계.
 *
 * 화면-기능 분리: 이 엔티티는 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_attendance_settings",
        uniqueConstraints = @UniqueConstraint(columnNames = "academyId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaAttendanceSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키, 유일) */
    @Column(nullable = false, unique = true)
    private Long academyId;

    /**
     * 하원 가능 시작 시각 (선택).
     * 설정 시 이 시각 이전에는 CHECK_OUT/EARLY_LEAVE 기록을 거부한다.
     */
    private LocalTime checkOutEarliestTime;

    /** 하원 가능 종료 시각 (선택, 참고용) */
    private LocalTime checkOutLatestTime;

    /** 출결 사진 전송 여부 (2단계: 설정값만 저장. 실제 촬영·전송은 미구현) */
    @Column(nullable = false)
    @Builder.Default
    private Boolean photoEnabled = false;

    /** 등원 알림 문구 (3단계 발송 시 사용 예정. {name} = 학생 이름 자리표시자) */
    @Column(length = 200)
    @Builder.Default
    private String checkInMessage = "{name}님이 등원했습니다.";

    /** 하원 알림 문구 (3단계 발송 시 사용 예정. {name} = 학생 이름 자리표시자) */
    @Column(length = 200)
    @Builder.Default
    private String checkOutMessage = "{name}님이 하원했습니다.";

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

    /** 출결 설정 변경. null이면 기존값 유지 (photoEnabled는 null이면 유지). */
    public void update(LocalTime checkOutEarliestTime, LocalTime checkOutLatestTime,
                       Boolean photoEnabled, String checkInMessage, String checkOutMessage) {
        this.checkOutEarliestTime = checkOutEarliestTime;
        this.checkOutLatestTime = checkOutLatestTime;
        if (photoEnabled != null) this.photoEnabled = photoEnabled;
        if (checkInMessage != null) this.checkInMessage = checkInMessage;
        if (checkOutMessage != null) this.checkOutMessage = checkOutMessage;
    }

    /** 학원의 기본 설정 행 생성 */
    public static ReaAttendanceSetting defaults(Long academyId) {
        return ReaAttendanceSetting.builder()
                .academyId(academyId)
                .build();
    }
}
