package com.rea.academy.domain.consultation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 상담 스레드 종류 (functional-spec-v1.md §6).
 */
@Getter
@RequiredArgsConstructor
public enum ConsultationType {
    INQUIRY("문의"),    // 학부모 → 학원 1:1 문의
    NEWS("아이소식");  // 학원 → 학부모 1:1 소식 (민감정보 개별 전달)

    private final String label;

    public static ConsultationType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("상담 종류는 필수입니다.");
        }
        try {
            return ConsultationType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 상담 종류입니다: " + value);
        }
    }
}
