package com.rea.academy.domain.notification;

/** 메시지 발송 대상 지정 방식. */
public enum NotificationTargetType {
    /** 개별 학부모 (targetIds = 학생 ID 목록) */
    INDIVIDUAL,
    /** 반 전체 (targetIds = 반 ID 목록) */
    CLASS,
    /** 학원 전체 (targetIds 무시) */
    ALL;

    public static NotificationTargetType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("발송 대상 종류는 필수입니다 (INDIVIDUAL/CLASS/ALL).");
        }
        try {
            return NotificationTargetType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 발송 대상 종류입니다: " + value);
        }
    }
}
