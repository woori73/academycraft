package com.rea.academy.domain.attendance;

/**
 * 출결 이벤트 구분.
 * - CHECK_IN: 등원
 * - CHECK_OUT: 하원
 * - ABSENT: 결석
 * - LATE: 지각
 * - EARLY_LEAVE: 조퇴
 *
 * 키패드 자동 판별은 CHECK_IN/CHECK_OUT만 사용한다.
 * (당일 첫 기록=등원, 이후=하원. 결석·지각·조퇴는 강사가 직접 기록)
 */
public enum AttendanceEventType {
    CHECK_IN,
    CHECK_OUT,
    ABSENT,
    LATE,
    EARLY_LEAVE;

    /** 요청 문자열 → enum. 대소문자 무시. */
    public static AttendanceEventType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("출결 구분(eventType)은 필수입니다.");
        }
        try {
            return AttendanceEventType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "잘못된 출결 구분입니다: " + value + " (CHECK_IN/CHECK_OUT/ABSENT/LATE/EARLY_LEAVE)");
        }
    }
}
