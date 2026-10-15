package com.rea.academy.dto;

import lombok.*;

/**
 * 학생 DTO.
 */
public class StudentDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateRequest {
        private Long academyId;
        private String name;
        private String grade;
        private String schoolName;
        private String parentName;
        private String parentPhone;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateRequest {
        private String name;
        private String grade;
        private String schoolName;
        private String parentName;
        private String parentPhone;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private Long id;
        private Long academyId;
        private String name;
        private String grade;
        private String schoolName;
        private String parentName;
        private String parentPhone;
        private Boolean active;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DetailResponse {
        private Long id;
        private Long academyId;
        private String name;
        private String grade;
        private String schoolName;
        private String parentName;
        private String parentPhone;
        /** 인증키는 상세 화면에서만 반환 (학부모 공유용) */
        private String authKey;
        private Boolean active;
    }
}
