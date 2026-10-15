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
        /** 쓰기 도구 실행 승인 여부 (기본 false) */
        private Boolean writeApproved;
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
