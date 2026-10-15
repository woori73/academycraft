package com.rea.academy.domain.notification;

/**
 * 알림 채널 추상화.
 * 실제 외부 발송(SMS/푸시/알림톡)은 3단계 범위 밖이므로,
 * 현재 구현체는 {@link MockNotificationSender} 하나뿐이다.
 * 향후 실제 채널 연동 시 이 인터페이스의 구현체를 추가하면 된다.
 */
public interface NotificationSender {

    /**
     * 알림 1건을 발송한다 (Mock은 실제 발송 없이 로그만 기록).
     *
     * @param channel        발송 채널
     * @param recipientName  수신자 표시명 (학부모명 또는 학생명)
     * @param recipientPhone 수신자 연락처 (없을 수 있음)
     * @param title          제목
     * @param content        내용
     * @return 발송 결과
     */
    NotificationSendResult send(NotificationChannel channel,
                                String recipientName,
                                String recipientPhone,
                                String title,
                                String content);
}
