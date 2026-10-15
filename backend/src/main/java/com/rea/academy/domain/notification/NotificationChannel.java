package com.rea.academy.domain.notification;

/**
 * 알림 발송 채널.
 * 1차는 앱 푸시(PUSH) — 비투처럼 문자 비용 0원.
 * SMS/알림톡은 외부 연동이 필요하므로 3단계에서는 Mock으로만 기록하고 실제 발송하지 않는다.
 */
public enum NotificationChannel {
    PUSH,
    SMS,
    ALIMTALK;

    public static NotificationChannel parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("알림 채널은 필수입니다 (PUSH/SMS/ALIMTALK).");
        }
        try {
            return NotificationChannel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 알림 채널입니다: " + value);
        }
    }
}
