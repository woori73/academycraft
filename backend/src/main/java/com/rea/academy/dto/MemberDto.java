package com.rea.academy.dto;

import com.rea.academy.domain.member.ReaRole;
import lombok.*;

/**
 * 회원 DTO.
 * 화면-기능 분리: Service Layer는 DTO로 입출력. UI는 DTO를 받아 렌더링.
 */
public class MemberDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateRequest {
        private Long academyId;
        private ReaRole role;
        private String name;
        private String phone;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private Long id;
        private Long academyId;
        private ReaRole role;
        private String name;
        private String phone;
        /** 인증키는 목록에서 제외, 상세에서만 반환 (보안) */
        private String authKey;
        private Boolean active;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KeyLoginRequest {
        private String authKey;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class KeyLoginResponse {
        private Long memberId;
        private Long academyId;
        private ReaRole role;
        private String name;
        /** 학생 인증키로 로그인한 학부모인 경우 학생 ID */
        private Long studentId;
    }
}
