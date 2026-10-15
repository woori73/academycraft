package com.rea.academy.domain.notification;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 알림 엔티티 (수신자별 1행).
 * 학부모는 학생 인증키로 로그인하므로 수신자는 학생 ID로 식별한다.
 * 발송 당시의 수신자명·연락처는 스냅샷으로 보존한다.
 *
 * 화면-기능 분리: 순수 도메인. UI 의존성 없음.
 */
@Entity
@Table(name = "rea_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소속 학원 ID (멀티테넌트 키) */
    @Column(nullable = false)
    private Long academyId;

    /** 수신 학생 ID (학부모 = 학생 인증키 로그인 사용자) */
    @Column(nullable = false)
    private Long recipientStudentId;

    /** 발송 당시 수신자명 스냅샷 */
    @Column(length = 50)
    private String recipientName;

    /** 발송 당시 수신자 연락처 스냅샷 (없을 수 있음) */
    @Column(length = 20)
    private String recipientPhone;

    /** 발송 채널 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    /** 알림 종류 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    /** 제목 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 내용 */
    @Column(nullable = false, length = 1000)
    private String content;

    /** 발송 상태 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    /** 실패 사유 (FAILED인 경우) */
    @Column(length = 500)
    private String failReason;

    /** 재시도 횟수 */
    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /** 읽음 시각 (수신자 읽음 처리 시) */
    private LocalDateTime readAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /** 읽음 처리 */
    public void markRead() {
        this.readAt = LocalDateTime.now();
    }

    public boolean isRead() {
        return this.readAt != null;
    }

    /** 발송 성공 반영 */
    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.failReason = null;
    }

    /** 발송 실패 반영 */
    public void markFailed(String reason) {
        this.status = NotificationStatus.FAILED;
        this.failReason = reason;
    }

    /** 재시도 횟수 증가 */
    public void increaseRetryCount() {
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
    }
}
