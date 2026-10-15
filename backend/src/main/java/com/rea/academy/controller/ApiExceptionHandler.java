package com.rea.academy.controller;

import com.rea.academy.security.ReaAuthenticationException;
import com.rea.academy.security.ReaForbiddenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * API 예외 변환.
 * - ReaAuthenticationException → 401 (인증키 누락·무효)
 * - ReaForbiddenException → 403 (테넌트 격리 위반·역할 권한 위반)
 * - IllegalArgumentException → 400 (잘못된 요청: 필수값 누락, 타 학원 데이터 접근 등)
 * - IllegalStateException → 409 (상태 충돌: 정원 초과, 이미 종료된 반 등)
 *
 * 화면-기능 분리: HTTP 상태코드 매핑만 담당. 업무 로직은 Service에 유지.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ReaAuthenticationException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(ReaAuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", messageOf(e)));
    }

    @ExceptionHandler(ReaForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ReaForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", messageOf(e)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", messageOf(e)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", messageOf(e)));
    }

    private String messageOf(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : "요청을 처리할 수 없습니다.";
    }
}
