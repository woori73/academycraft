package com.rea.academy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Agent API 입출력 DTO (2단계).
 */
public final class AgentDto {

    private AgentDto() {
    }

    /** Agent 실행 요청 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ExecuteRequest {
        /** 자연어 명령 (예: "학생 등록 김철수", "오늘 결석 학생") */
        private String command;
        /**
         * @deprecated 쓰기 승인 근거가 아니다. true 로 보내면 400 으로 거부한다 (P1-a).
         *             쓰기는 계획 생성 후 {@code POST .../agent/plans/{planId}/approve} 로만 실행된다.
         */
        @Deprecated
        private Boolean writeApproved;
        /** 구조화 호출: 도구 이름 (command 와 함께 보낼 수 없다) */
        private String tool;
        /** 구조화 호출: 도구 파라미터 (스키마에 있는 키만 허용) */
        private Map<String, Object> params;
        /** 계획 생성 중복 방지 키 (UUID, 본문 필드 — 서명 대상에 포함된다) */
        private String idempotencyKey;
    }

    /** 계획 승인 요청: 입력은 받지 않고 사용자가 본 계획의 해시만 받는다 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ApproveRequest {
        private String planHash;
    }

    /** 실행 계획 조회·응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PlanView {
        private String planId;
        private String status;
        private String toolName;
        /** 서버가 도구 입력으로 생성한 변경 미리보기 */
        private Map<String, Object> preview;
        private String planHash;
        private java.time.LocalDateTime expiresAt;
        /** 실행 결과 (승인 직후 응답에는 전체, 이후 조회에는 비밀값 제거본) */
        private Map<String, Object> result;
        private String failureCode;
        private String message;
        /** 같은 요청의 재전송 응답이면 true */
        private boolean replayed;
    }

    /** Agent 실행 응답 */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ExecuteResponse {
        private boolean success;
        /** 실행된 도구 이름 (파싱 실패 시 null) */
        private String toolUsed;
        /** 도구 실행 결과 */
        private Map<String, Object> result;
        /** 사람 읽기용 메시지 */
        private String message;
        /** 쓰기 승인이 필요해 거부된 경우 true */
        private Boolean requiresApproval;
        /** 파싱 실패 시 후보 도구 제안 */
        private List<String> suggestions;
        /** 쓰기 도구 요청 시 생성된 실행 계획 (승인 전 미리보기 포함) */
        private PlanView plan;
        /** 실행하지 않고 서버 미리보기만 제공하는 경우(강사) 표시용 */
        private Map<String, Object> draftPreview;
        /** 차단·거부 사유 코드 (예: OWNER_ONLY, APPROVAL_DISABLED) */
        private String errorCode;
        /** 컨트롤러가 사용할 HTTP 상태 (직렬화하지 않음) */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private Integer httpStatus;
    }

    /** 도구 메타데이터 (목록 조회용) */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ToolInfo {
        private String name;
        private String description;
        private Map<String, String> inputSchema;
        private boolean write;
    }
}
