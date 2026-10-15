package com.rea.academy.domain.consultation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 상담 스레드 상태 (functional-spec-v1.md §6.3).
 */
@Getter
@RequiredArgsConstructor
public enum ConsultationStatus {
    OPEN("진행중"),      // 문의 접수·대화 진행 중
    ANSWERED("답변완료"), // 학원(원장·강사)이 답변한 상태
    CLOSED("종료");       // 상담 종료

    private final String label;

    public static ConsultationStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("상담 상태는 필수입니다.");
        }
        try {
            return ConsultationStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 상담 상태입니다: " + value);
        }
    }
}
