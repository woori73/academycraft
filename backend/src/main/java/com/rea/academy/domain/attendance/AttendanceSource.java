package com.rea.academy.domain.attendance;

/**
 * 출결 기록 경로.
 * - KEYPAD: 출결 키패드 단말에서 학생이 직접 입력
 * - MANUAL: 강사·원장이 직접 기록 (결석·지각·조퇴 포함)
 */
public enum AttendanceSource {
    KEYPAD,
    MANUAL
}
