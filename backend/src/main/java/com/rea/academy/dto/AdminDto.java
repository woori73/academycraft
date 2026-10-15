package com.rea.academy.dto;

import com.rea.academy.domain.member.ReaRole;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 학원 관리·관리자 기능(학원 정보·지점·사용자/권한·감사 로그) DTO 모음 (8단계).
 * 화면-기능 분리: Service 입출력용. UI 프레임워크 의존 없음.
 */
public class AdminDto {

    // ===== 학원 기본정보 =====

    /**
     * 학원 정보 등록·수정 요청 (PUT, upsert).
     * - 미등록 학원이면 신규 등록: name 필수
     * - 등록済이면 제공된 항목만 수정 (null은 변경 없음)
     * - actorMemberId: 감사 로그 행위자 기록용 (선택)
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AcademyInfoUpdateRequest {
        private String name;
        private String phone;
        private String address;
        private String logoKey;
        private String memo;
        private Long actorMemberId;
    }

    /** 학원 정보 응답 (지점 등록 응답으로도 재사용) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AcademyInfoResponse {
        /** 학원 ID (= academyId) */
        private Long id;
        private String name;
        private String phone;
        private String address;
        private String logoKey;
        private String memo;
        /** 본원 학원 ID (null이면 본원/독립 학원) */
        private Long parentAcademyId;
        private boolean branch;
        private Boolean active;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /**
     * 지점 등록 요청.
     * 새 academyId는 서버가 자동 발급 (등록된 학원 중 최대값 + 1).
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class BranchCreateRequest {
        private String name;
        private String phone;
        private String address;
        private String logoKey;
        private String memo;
        private Long actorMemberId;
    }

    // ===== 사용자·권한 =====

    /** 원장·강사 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StaffResponse {
        private Long id;
        private ReaRole role;
        private String roleLabel;
        private String name;
        private String phone;
        private Boolean active;
        private LocalDateTime createdAt;
    }

    /**
     * 학부모 연결 응답.
     * 학부모는 별도 회원가입이 없고 학생 인증키로 로그인하므로,
     * "학부모 목록" = 학생별 학부모 연결 상태로 제공한다.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ParentLinkResponse {
        private Long studentId;
        private String studentName;
        private String grade;
        private String parentName;
        private String parentPhone;
        /** 학부모 정보(성명) 등록 여부 */
        private boolean connected;
    }

    /** 역할별 사용자 목록 응답 (?role=OWNER|TEACHER|PARENT, 미지정 시 전체 원장·강사) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UserListResponse {
        private List<StaffResponse> staff;
        private List<ParentLinkResponse> parents;
    }

    /**
     * 권한 변경 요청 (원장만 수행 가능).
     * - role: 변경할 역할 (OWNER|ACADEMY_OWNER|TEACHER, 대소문자 무시)
     * - actorMemberId: 요청자 회원 ID (원장 검증용, 필수)
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RoleChangeRequest {
        private String role;
        private Long actorMemberId;
    }

    // ===== 감사 로그 =====

    /** 변경 이력 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AuditLogResponse {
        private Long id;
        private String actionType;
        private String actionTypeLabel;
        /** 변경 대상 종류: 학생/반/출결/공지/회원/학원 */
        private String targetType;
        private Long targetId;
        private String targetName;
        private Long actorId;
        private String actorName;
        private String detail;
        private LocalDateTime createdAt;
    }

    /** 변경 이력 목록 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AuditLogListResponse {
        private List<AuditLogResponse> logs;
        private long totalCount;
    }
}
