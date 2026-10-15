package com.rea.academy.domain.learning;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 숙제 검사 결과 구분 (functional-spec-v1.md §7.2).
 * - COMPLETED: 완료
 * - INCOMPLETE: 미완료
 * 점수는 별도 score 필드로 입력 (선택).
 */
@Getter
@RequiredArgsConstructor
public enum HomeworkCheckStatus {
    COMPLETED("완료"),
    INCOMPLETE("미완료");

    private final String label;

    public static HomeworkCheckStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("검사 결과(완료/미완료)는 필수입니다.");
        }
        try {
            return HomeworkCheckStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 검사 결과입니다: " + value);
        }
    }
}
