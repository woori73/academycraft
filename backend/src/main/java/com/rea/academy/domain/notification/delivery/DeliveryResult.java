package com.rea.academy.domain.notification.delivery;

/**
 * 발송 결과.
 * 개인정보·토큰을 포함하지 않는다.
 */
public record DeliveryResult(boolean success, String channel, String messageId, String error) {

    public static DeliveryResult success(String channel, String messageId) {
        return new DeliveryResult(true, channel, messageId, null);
    }

    public static DeliveryResult failure(String channel, String error) {
        return new DeliveryResult(false, channel, null, error);
    }

    public static DeliveryResult skipped(String reason) {
        return new DeliveryResult(false, "NONE", null, reason);
    }
}
