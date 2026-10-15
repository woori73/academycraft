package com.rea.academy.agent;

import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.dto.AgentDto;
import com.rea.academy.service.AuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 실행 엔진 (2단계).
 * 파싱 → 정책 검사 → 도구 실행 → 감사 로그 기록의 단일 파이프라인을 담당한다.
 *
 * 실행 정책:
 * - 읽기(Read) 도구: 즉시 실행
 * - 쓰기(Write) 도구: writeApproved=true AND 학부모 아님 → 실행.
 *   승인 없으면 "승인 필요" 응답 (도구 미실행). 학부모의 쓰기 도구 호출은 거부.
 * - 외부 메시지 발송: 호출 금지 (모든 도구는 Mock/발송 없음 경로만 사용)
 * - 모든 실행(성공·거부·실패)은 감사 로그에 기록
 */
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final CommandParser commandParser;
    private final AgentDispatcher dispatcher;
    private final AuditRecorder auditRecorder;

    /**
     * 자연어 명령 실행.
     *
     * @param ctx     실행 컨텍스트 (학원·역할·승인 여부)
     * @param command 자연어 명령
     * @return 실행 응답 (항상 success 플래그 포함, HTTP 200)
     */
    @Transactional
    public AgentDto.ExecuteResponse execute(AgentContext ctx, String command) {
        // 1. 파싱
        CommandParser.ParseResult parsed = commandParser.parse(command);
        if (!parsed.ok()) {
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .message(parsed.message())
                    .suggestions(parsed.suggestions())
                    .build();
        }

        // 2. 도구 조회
        AgentTool tool;
        try {
            tool = dispatcher.resolve(parsed.toolName());
        } catch (IllegalArgumentException e) {
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }

        Map<String, Object> params = new LinkedHashMap<>(parsed.params());

        // 3. 정책 검사 (쓰기 도구)
        if (tool.isWrite()) {
            if (ctx.isParent()) {
                recordAudit(ctx, tool, command, "거부: 학부모는 쓰기 도구를 사용할 수 없습니다.");
                return AgentDto.ExecuteResponse.builder()
                        .success(false)
                        .toolUsed(tool.getName())
                        .requiresApproval(false)
                        .message("학부모는 쓰기 도구를 사용할 수 없습니다. (원장·강사 전용)")
                        .build();
            }
            if (!ctx.writeApproved()) {
                recordAudit(ctx, tool, command, "거부: 쓰기 승인 없음");
                return AgentDto.ExecuteResponse.builder()
                        .success(false)
                        .toolUsed(tool.getName())
                        .requiresApproval(true)
                        .message("쓰기 작업 승인이 필요합니다. writeApproved=true로 다시 요청하세요.")
                        .build();
            }
        }

        // 4. 도구 실행
        try {
            Map<String, Object> result = tool.execute(params, ctx);
            recordAudit(ctx, tool, command, "성공");
            return AgentDto.ExecuteResponse.builder()
                    .success(true)
                    .toolUsed(tool.getName())
                    .result(result)
                    .message("실행 완료: " + tool.getName())
                    .build();
        } catch (RuntimeException e) {
            recordAudit(ctx, tool, command, "실패: " + e.getMessage());
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .toolUsed(tool.getName())
                    .message("실행 실패: " + e.getMessage())
                    .build();
        }
    }

    /** 모든 실행(성공·거부·실패)을 감사 로그에 append-only 기록 */
    private void recordAudit(AgentContext ctx, AgentTool tool, String command, String outcome) {
        auditRecorder.record(
                ctx.academyId(),
                AuditActionType.AGENT_TOOL_EXECUTED,
                "에이전트",
                null,
                tool.getName(),
                ctx.principalId(),
                ctx.principalName(),
                outcome + " | 명령: " + truncate(command, 300));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
