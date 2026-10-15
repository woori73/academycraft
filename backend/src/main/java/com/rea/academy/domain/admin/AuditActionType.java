package com.rea.academy.domain.admin;

/**
 * 변경 이력(감사 로그) 행위 종류.
 * - STUDENT_*: 1차 MemberService (학생 등록·삭제)
 * - CLASS_*: 1단계 ClassService (반 생성·종료)
 * - ATTENDANCE_CORRECTED: 2단계 AttendanceService (출결 정정)
 * - NOTICE_*: 4단계 NoticeService (공지 작성·삭제)
 * - MEMBER_ROLE_CHANGED: 8단계 AdminService (권한 변경)
 * - ACADEMY_INFO_UPDATED: 8단계 AdminService (학원 정보 수정)
 * - BRANCH_CREATED: 8단계 AdminService (지점 등록)
 *
 * 기존 서비스의 변경은 AOP(AuditLogAspect)로 기록하므로 기존 코드를 수정하지 않는다.
 */
public enum AuditActionType {
    STUDENT_CREATED("학생 등록"),
    STUDENT_DELETED("학생 삭제"),
    CLASS_CREATED("반 생성"),
    CLASS_CLOSED("반 종료"),
    ATTENDANCE_CORRECTED("출결 정정"),
    NOTICE_CREATED("공지 작성"),
    NOTICE_DELETED("공지 삭제"),
    MEMBER_ROLE_CHANGED("권한 변경"),
    ACADEMY_INFO_UPDATED("학원 정보 수정"),
    BRANCH_CREATED("지점 등록"),
    AGENT_TOOL_EXECUTED("에이전트 도구 실행");

    private final String label;

    AuditActionType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 대소문자 무시 파싱 (audit-logs 조회 필터용) */
    public static AuditActionType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("이력 종류가 비어 있습니다.");
        }
        String normalized = value.trim().toUpperCase();
        for (AuditActionType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("알 수 없는 이력 종류입니다: " + value);
    }
}
