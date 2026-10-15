package com.rea.academy.security;

/**
 * 인가 실패 → HTTP 403.
 * - URL의 {academyId}와 인증된 사용자의 academyId 불일치 (테넌트 격리)
 * - 역할(Role)에 허용되지 않은 API 호출
 * - 학부모가 본인 자녀가 아닌 학생 데이터 조회
 */
public class ReaForbiddenException extends RuntimeException {

    public ReaForbiddenException(String message) {
        super(message);
    }
}
