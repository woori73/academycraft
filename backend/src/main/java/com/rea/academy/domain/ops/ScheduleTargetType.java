package com.rea.academy.domain.ops;

/**
 * 학원 일정 대상 구분.
 * - ALL: 학원 전체 일정 (예: 휴원일, 전체 행사)
 * - CLASS: 특정 반 대상 일정 (예: 반별 특강, 반별 학부모 상담의 날)
 */
public enum ScheduleTargetType {
    ALL,
    CLASS;

    /** 화면 표시용 라벨 */
    public String label() {
        return this == ALL ? "전체" : "반별";
    }

    /** 요청 문자열 → enum. 빈 값이면 ALL(기본값). 대소문자 무시. */
    public static ScheduleTargetType parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return ScheduleTargetType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "잘못된 일정 대상입니다: " + value + " (ALL/CLASS)");
        }
    }
}
