package com.rea.academy.domain.notification;

/** 발송 상태. */
public enum NotificationStatus {
    /** 발송 성공 (Mock 포함) */
    SENT,
    /** 발송 실패 — 재시도 대상 */
    FAILED
}
