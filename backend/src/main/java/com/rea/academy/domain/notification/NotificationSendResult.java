package com.rea.academy.domain.notification;

/**
 * 알림 발송 결과.
 * Mock 발송을 포함한 모든 채널이 이 형태로 결과를 반환한다.
 */
public class NotificationSendResult {

    private final boolean ok;
    private final String messageId;
    private final String failReason;

    private NotificationSendResult(boolean ok, String messageId, String failReason) {
        this.ok = ok;
        this.messageId = messageId;
        this.failReason = failReason;
    }

    public static NotificationSendResult success(String messageId) {
        return new NotificationSendResult(true, messageId, null);
    }

    public static NotificationSendResult failure(String failReason) {
        return new NotificationSendResult(false, null, failReason);
    }

    public boolean isOk() {
        return ok;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getFailReason() {
        return failReason;
    }
}
