package com.rea.academy.domain.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock 알림 발송기.
 * <strong>실제 외부 발송을 절대 하지 않는다.</strong> 발송 요청을 로그로만 기록하고
 * 성공(또는 테스트용 강제 실패) 결과를 반환한다.
 * 발송 이력·재시도·설계 검증용으로만 사용한다.
 */
@Component
public class MockNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    /**
     * 테스트용 강제 실패 스위치. true이면 모든 발송을 FAILED로 처리한다.
     * 운영 흐름에서는 항상 false.
     */
    private volatile boolean forceFail = false;

    public void setForceFail(boolean forceFail) {
        this.forceFail = forceFail;
    }

    public boolean isForceFail() {
        return forceFail;
    }

    /**
     * PII 마스킹: 이름은 첫 글자만, 전화번호는 뒤 4자리만 표시.
     */
    static String maskName(String name) {
        if (name == null || name.isEmpty()) return "-";
        return name.charAt(0) + "*".repeat(Math.max(0, name.length() - 1));
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "***-****-" + phone.substring(phone.length() - 4);
    }

    @Override
    public NotificationSendResult send(NotificationChannel channel,
                                       String recipientName,
                                       String recipientPhone,
                                       String title,
                                       String content) {
        // 실제 발송 금지: 외부 API 호출 없음. 로그 기록만 수행.
        // PII 보호: 이름·전화번호는 마스킹, 제목·본문은 길이만 기록.
        String maskedName = maskName(recipientName);
        String maskedPhone = maskPhone(recipientPhone);
        int titleLen = title == null ? 0 : title.length();
        int contentLen = content == null ? 0 : content.length();
        if (forceFail) {
            log.warn("[MOCK-알림] 강제 실패 모드: channel={} to={}", channel, maskedName);
            return NotificationSendResult.failure("Mock 강제 실패 (테스트 모드)");
        }
        if ((channel == NotificationChannel.SMS || channel == NotificationChannel.ALIMTALK)
                && (recipientPhone == null || recipientPhone.isBlank())) {
            log.warn("[MOCK-알림] 발송 실패(연락처 없음): channel={} to={}", channel, maskedName);
            return NotificationSendResult.failure("수신자 연락처가 없습니다.");
        }
        log.info("[MOCK-알림] channel={} to={}({}) titleLen={} contentLen={}",
                channel, maskedName, maskedPhone, titleLen, contentLen);
        return NotificationSendResult.success("MOCK-" + System.currentTimeMillis());
    }
}
