package com.rea.academy.controller;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentDispatcher;
import com.rea.academy.agent.AgentExecutor;
import com.rea.academy.agent.plan.AgentPlanException;
import com.rea.academy.agent.plan.AgentPlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import com.rea.academy.dto.AgentDto;
import com.rea.academy.security.AuthContext;
import com.rea.academy.security.AuthContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Agent 실행 API (2단계).
 * 인증 필수 (1단계 X-Auth-Key 보안 적용).
 * 테넌트 격리·역할 권한은 ReaSecurityInterceptor가 강제한다.
 * 화면-기능 분리: Controller는 HTTP 입출력만 담당. 파싱·정책·실행은 AgentExecutor에 위임.
 */
@RestController
@RequestMapping("/api/rea/{academyId}/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentExecutor agentExecutor;
    private final AgentDispatcher agentDispatcher;
    private final AgentPlanService planService;

    /**
     * 자연어 명령 또는 구조화 호출(tool + params) 실행.
     * body: {command | tool+params, idempotencyKey?}
     *
     * 읽기 도구는 즉시 실행한다. 쓰기 도구는 <b>실행하지 않고</b> 서버가 미리보기를 만든 실행 계획을 돌려준다.
     * 실행은 {@code POST .../plans/{planId}/approve} 로만 가능하다.
     * {@code writeApproved:true} 는 승인 근거가 아니므로 400 으로 거부한다 (기존 클라이언트 우회 차단).
     */
    @PostMapping("/execute")
    public ResponseEntity<AgentDto.ExecuteResponse> execute(
            @PathVariable Long academyId,
            @RequestBody AgentDto.ExecuteRequest req,
            HttpServletRequest http) {
        AgentContext ctx = contextFor(academyId);
        if (Boolean.TRUE.equals(req.getWriteApproved())) {
            planService.recordRejected(ctx, "reason=WRITE_APPROVED_NOT_ACCEPTED (boolean 승인 우회 시도)");
            throw new AgentPlanException(HttpStatus.BAD_REQUEST, "WRITE_APPROVED_NOT_ACCEPTED",
                    "writeApproved 는 더 이상 승인 근거로 사용할 수 없습니다. "
                            + "쓰기는 계획을 만든 뒤 승인 API(/agent/plans/{planId}/approve)로만 실행됩니다.");
        }
        AgentDto.ExecuteResponse response = agentExecutor.execute(ctx, req, authSource(http));
        int status = response.getHttpStatus() == null ? 200 : response.getHttpStatus();
        return ResponseEntity.status(status).body(response);
    }

    /** 실행 계획 조회 (계획을 만든 본인만) */
    @GetMapping("/plans/{planId}")
    public ResponseEntity<AgentDto.PlanView> getPlan(@PathVariable Long academyId, @PathVariable String planId) {
        return ResponseEntity.ok(planService.get(contextFor(academyId), planId));
    }

    /** 계획 승인 후 1회 실행. 입력은 받지 않고 사용자가 본 계획 해시만 받는다. */
    @PostMapping("/plans/{planId}/approve")
    public ResponseEntity<AgentDto.PlanView> approvePlan(
            @PathVariable Long academyId, @PathVariable String planId,
            @RequestBody AgentDto.ApproveRequest req, HttpServletRequest http) {
        if (req == null || req.getPlanHash() == null || req.getPlanHash().isBlank()) {
            throw new IllegalArgumentException("planHash 는 필수입니다.");
        }
        return ResponseEntity.ok(planService.approve(contextFor(academyId), planId, req.getPlanHash(), authSource(http)));
    }

    /** 계획 취소 (승인 전에만) */
    @PostMapping("/plans/{planId}/cancel")
    public ResponseEntity<AgentDto.PlanView> cancelPlan(@PathVariable Long academyId, @PathVariable String planId) {
        return ResponseEntity.ok(planService.cancel(contextFor(academyId), planId));
    }

    /** 인증 컨텍스트에서 실행 컨텍스트를 만든다. 쓰기 승인 불리언은 항상 false (클라이언트 값은 쓰지 않는다). */
    @SuppressWarnings("deprecation")
    private AgentContext contextFor(Long academyId) {
        AuthContext auth = AuthContextHolder.get();
        return new AgentContext(auth.principalType(), academyId, auth.role(), auth.principalId(), auth.name(), false);
    }

    /**
     * 인증 경로 문자열: 직접 로그인은 "DIRECT", BuildIt 게이트웨이 경유는 BuildIt 역할·사용자 ID·학원 ID 를 포함한다.
     * 값은 서명 검증을 통과한 게이트웨이 필터가 설정한 요청 속성이며 클라이언트 입력이 아니다.
     * 게이트웨이 경유인데 속성이 빠져 있으면(필터·컨트롤러 버전 불일치 등) 안전하게 실패한다.
     */
    private static String authSource(HttpServletRequest http) {
        Object role = http.getAttribute("buildit.role");
        if (role == null) {
            return "DIRECT";
        }
        Object userId = http.getAttribute("buildit.userId");
        Object academyId = http.getAttribute("buildit.academyId");
        if (userId == null || academyId == null) {
            throw new AgentPlanException(HttpStatus.FORBIDDEN, "GATEWAY_CONTEXT_INCOMPLETE",
                    "게이트웨이 연결 정보가 불완전해 요청을 처리하지 않았습니다.");
        }
        return "BUILDIT:" + role + ":u" + userId + ":a" + academyId;
    }

    /**
     * 사용 가능한 도구 목록 (설명·입출력 스키마).
     */
    @GetMapping("/tools")
    public ResponseEntity<List<AgentDto.ToolInfo>> listTools(
            @PathVariable Long academyId) {
        List<AgentDto.ToolInfo> tools = agentDispatcher.describeTools().stream()
                .map(m -> AgentDto.ToolInfo.builder()
                        .name((String) m.get("name"))
                        .description((String) m.get("description"))
                        .inputSchema(castSchema(m.get("inputSchema")))
                        .write((Boolean) m.get("isWrite"))
                        .build())
                .toList();
        return ResponseEntity.ok(tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> castSchema(Object value) {
        return (Map<String, String>) value;
    }
}
