package com.rea.academy.domain.notification;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 알림 종류 (functional-spec-v1.md §4.2).
 */
@Getter
@RequiredArgsConstructor
public enum NotificationType {
    CHECK_IN("등원 알림"),
    CHECK_OUT("하원 알림"),
    NOTICE("공지 알림"),
    CONSULT_REPLY("상담 답변 알림"),
    GRADE("성적 알림"),
    HOMEWORK("숙제 알림");

    private final String label;

    public static NotificationType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("알림 종류는 필수입니다.");
        }
        try {
            return NotificationType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 알림 종류입니다: " + value);
        }
    }
}
