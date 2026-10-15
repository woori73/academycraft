package com.rea.academy.domain.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 알림 설정 엔티티 (학부모=학생별 × 알림 종류별 1행).
 * 미설정 종류는 기본 ON으로 간주한다.
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_notification_settings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"academyId", "studentId", "type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaNotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 설정 대상 학생 ID (학부모 = 학생 인증키 로그인 사용자) */
    @Column(nullable = false)
    private Long studentId;

    /** 알림 종류 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    /** 수신 여부 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updatedAt = LocalDateTime.now();
    }

    public void updateEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
