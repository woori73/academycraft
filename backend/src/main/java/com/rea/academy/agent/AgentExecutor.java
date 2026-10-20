package com.rea.academy.agent;

import com.rea.academy.agent.plan.AgentPlanException;
import com.rea.academy.agent.plan.AgentPlanService;
import com.rea.academy.agent.plan.AgentToolPolicy;
import com.rea.academy.domain.admin.AuditActionType;
import com.rea.academy.dto.AgentDto;
import com.rea.academy.service.AuditRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 실행 엔진.
 * 파싱 → 정책 검사 → 도구 실행(읽기) 또는 계획 생성(쓰기) → 감사 로그 기록의 단일 파이프라인을 담당한다.
 *
 * 실행 정책 (P1-a: 서버 검증형 승인):
 * - 읽기(Read) 도구: 즉시 실행
 * - 쓰기(Write) 도구: <b>이 엔진은 절대 실행하지 않는다.</b> {@link AgentContext#writeApproved()} 값은 무시한다.
 *   원장(ACADEMY_OWNER)은 서버 보관 실행 계획(미리보기 포함)을 받고, 계획 승인 API 로만 실행된다.
 *   강사(TEACHER)는 서버 미리보기(초안)만 볼 수 있고 계획은 만들어지지 않는다. 학부모는 거부된다.
 * - 외부 메시지 발송: 호출 금지 (모든 도구는 Mock/발송 없음 경로만 사용)
 * - 쓰기 요청의 모든 단계는 감사 로그에 기록
 */
@Service
@RequiredArgsConstructor
public class AgentExecutor {

    private final CommandParser commandParser;
    private final AgentDispatcher dispatcher;
    private final AuditRecorder auditRecorder;
    private final AgentPlanService planService;

    /** 자연어 명령 실행 (인증 경로 미지정: 직접 로그인으로 간주) */
    @Transactional
    public AgentDto.ExecuteResponse execute(AgentContext ctx, String command) {
        return run(ctx, null, null, command, null, "RULE", "DIRECT");
    }

    /** HTTP 요청 실행: 자연어 명령 또는 구조화 호출(tool + params) 중 하나만 허용 */
    @Transactional
    public AgentDto.ExecuteResponse execute(AgentContext ctx, AgentDto.ExecuteRequest req, String authSource) {
        boolean structured = req.getTool() != null && !req.getTool().isBlank();
        if (structured && req.getCommand() != null && !req.getCommand().isBlank()) {
            throw new IllegalArgumentException("command 와 tool 은 함께 보낼 수 없습니다.");
        }
        return structured
                ? run(ctx, req.getTool().trim(), req.getParams(), null, req.getIdempotencyKey(), "STRUCTURED", authSource)
                : run(ctx, null, null, req.getCommand(), req.getIdempotencyKey(), "RULE", authSource);
    }

    private AgentDto.ExecuteResponse run(AgentContext ctx, String toolName, Map<String, Object> structuredParams,
                                         String command, String idempotencyKey, String source, String authSource) {
        String label;
        String resolvedTool;
        Map<String, Object> params;
        if (toolName != null) {
            label = "tool:" + toolName;
            resolvedTool = toolName;
            params = new LinkedHashMap<>(structuredParams == null ? Map.of() : structuredParams);
        } else {
            // 1. 파싱
            CommandParser.ParseResult parsed = commandParser.parse(command);
            if (!parsed.ok()) {
                return AgentDto.ExecuteResponse.builder()
                        .success(false)
                        .message(parsed.message())
                        .suggestions(parsed.suggestions())
                        .build();
            }
            label = command;
            resolvedTool = parsed.toolName();
            params = new LinkedHashMap<>(parsed.params());
        }

        // 2. 도구 조회 (등록된 도구만 = 화이트리스트)
        AgentTool tool;
        try {
            tool = dispatcher.resolve(resolvedTool);
        } catch (IllegalArgumentException e) {
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }

        // 3. 쓰기 도구: 실행하지 않고 계획/초안/거부 처리
        if (tool.isWrite()) {
            return handleWrite(ctx, tool, params, label, idempotencyKey, source, authSource);
        }

        // 4. 읽기 도구 실행
        try {
            Map<String, Object> result = tool.execute(params, ctx);
            recordAudit(ctx, tool, label, "성공");
            return AgentDto.ExecuteResponse.builder()
                    .success(true)
                    .toolUsed(tool.getName())
                    .result(result)
                    .message("실행 완료: " + tool.getName())
                    .build();
        } catch (RuntimeException e) {
            recordAudit(ctx, tool, label, "실패: " + e.getMessage());
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .toolUsed(tool.getName())
                    .message("실행 실패: " + e.getMessage())
                    .build();
        }
    }

    private AgentDto.ExecuteResponse handleWrite(AgentContext ctx, AgentTool tool, Map<String, Object> params,
                                                 String label, String idempotencyKey, String source,
                                                 String authSource) {
        // 학부모 등 AI 쓰기·초안 대상이 아닌 주체
        if (!AgentToolPolicy.mayUseAiWrite(ctx) && !AgentToolPolicy.mayViewDraft(ctx)) {
            recordAudit(ctx, tool, label, "거부: 학부모는 쓰기 도구를 사용할 수 없습니다.");
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .toolUsed(tool.getName())
                    .requiresApproval(false)
                    .message("학부모는 쓰기 도구를 사용할 수 없습니다. (원장 전용)")
                    .build();
        }

        // 강사: 변경 내용 초안(서버 미리보기)만 확인할 수 있다. 계획·실행은 없다.
        if (AgentToolPolicy.mayViewDraft(ctx)) {
            try {
                Map<String, Object> input = planService.normalizeInput(tool, params);
                Map<String, Object> draft = tool.preview(input, ctx);
                planService.recordRejected(ctx, "tool=" + tool.getName() + " reason=OWNER_ONLY(TEACHER) draft_only");
                return AgentDto.ExecuteResponse.builder()
                        .success(false)
                        .toolUsed(tool.getName())
                        .requiresApproval(false)
                        .errorCode("OWNER_ONLY")
                        .draftPreview(draft)
                        .message("AI 쓰기는 원장만 사용할 수 있습니다. 변경 내용 초안만 확인할 수 있고 저장되지 않았습니다.")
                        .build();
            } catch (RuntimeException e) {
                planService.recordRejected(ctx, "tool=" + tool.getName() + " reason=OWNER_ONLY(TEACHER) invalid_input");
                return AgentDto.ExecuteResponse.builder()
                        .success(false)
                        .toolUsed(tool.getName())
                        .requiresApproval(false)
                        .errorCode("OWNER_ONLY")
                        .message("AI 쓰기는 원장만 사용할 수 있습니다. (" + e.getMessage() + ")")
                        .build();
            }
        }

        // 원장: 서버 보관 계획 생성 (데이터 무변경)
        try {
            AgentDto.PlanView plan = planService.createPlan(ctx, tool, params, idempotencyKey, source, authSource);
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .toolUsed(tool.getName())
                    .requiresApproval(true)
                    .plan(plan)
                    .message("승인이 필요합니다. 아래 변경 내용을 확인하고 승인해 주세요. 승인 전에는 아무것도 저장되지 않습니다.")
                    .build();
        } catch (AgentPlanException e) {
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .toolUsed(tool.getName())
                    .requiresApproval(false)
                    .errorCode(e.code())
                    .httpStatus(e.status().value())
                    .message(e.getMessage())
                    .build();
        } catch (UnsupportedOperationException e) {
            // 미리보기를 구현하지 않은 쓰기 도구는 계획을 만들 수 없다 (fail-closed)
            planService.recordRejected(ctx, "tool=" + tool.getName() + " reason=PREVIEW_NOT_IMPLEMENTED");
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .toolUsed(tool.getName())
                    .requiresApproval(false)
                    .errorCode("PREVIEW_NOT_IMPLEMENTED")
                    .httpStatus(501)
                    .message("이 도구는 아직 AI 쓰기를 지원하지 않습니다.")
                    .build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            planService.recordRejected(ctx, "tool=" + tool.getName() + " reason=INVALID_INPUT");
            return AgentDto.ExecuteResponse.builder()
                    .success(false)
                    .toolUsed(tool.getName())
                    .requiresApproval(false)
                    .errorCode("INVALID_INPUT")
                    .message("계획을 만들 수 없습니다: " + e.getMessage())
                    .build();
        }
    }

    /** 읽기 도구 실행·거부를 감사 로그에 append-only 기록 */
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
