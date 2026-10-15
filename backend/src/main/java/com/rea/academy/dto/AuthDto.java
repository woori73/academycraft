package com.rea.academy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * 정식 로그인(JWT) 인증 DTO.
 */
public class AuthDto {

    @Getter
    @Setter
    @NoArgsConstructor
    public static class LoginRequest {
        @NotBlank
        private String loginId;
        @NotBlank
        private String password;
    }

    @Getter
    @Builder
    public static class LoginResponse {
        private final String tokenType; // "Bearer"
        private final String accessToken;
        private final long expiresIn; // 초
        private final Long memberId;
        private final Long academyId;
        private final String role;
        private final String name;
    }

    @Getter
    @Builder
    public static class RefreshResponse {
        private final String tokenType;
        private final String accessToken;
        private final long expiresIn;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class PasswordResetRequest {
        @NotBlank
        private String loginId;
    }

    @Getter
    @Builder
    public static class PasswordResetRequestResponse {
        /** 개발 환경에서만 토큰 반환 (운영은 SMS·이메일 발송). */
        private final String resetToken;
        private final String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    public static class PasswordResetConfirm {
        @NotBlank
        private String token;
        @NotBlank
        @Size(min = 8, max = 64)
        private String newPassword;
    }

    /** 개발 환경 전용: 기존 인증키 회원의 최초 로그인 자격 설정 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SetupCredentialsRequest {
        @NotBlank
        private String authKey;
        @NotBlank
        @Size(min = 4, max = 50)
        private String loginId;
        @NotBlank
        @Size(min = 8, max = 64)
        private String newPassword;
    }

    /** 원장 전용: 회원의 로그인 자격 설정·초기화 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class SetCredentialsRequest {
        @NotBlank
        @Size(min = 4, max = 50)
        private String loginId;
        @NotBlank
        @Size(min = 8, max = 64)
        private String tempPassword;
        /** 학부모 계정의 자녀 학생 연결 (PARENT만) */
        private Long linkedStudentId;
    }
}
