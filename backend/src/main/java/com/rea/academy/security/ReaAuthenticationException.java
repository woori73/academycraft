package com.rea.academy.security;

/**
 * 인증 실패 → HTTP 401.
 * - X-Auth-Key 헤더 누락
 * - 유효하지 않은·비활성 인증키
 */
public class ReaAuthenticationException extends RuntimeException {

    public ReaAuthenticationException(String message) {
        super(message);
    }
}
