package com.rea.academy.controller;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentDispatcher;
import com.rea.academy.agent.AgentExecutor;
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

    /**
     * 자연어 명령 실행.
     * body: {command: "자연어 명령", writeApproved: boolean}
     * 응답: {success, toolUsed, result, message, requiresApproval, suggestions}
     */
    @PostMapping("/execute")
    public ResponseEntity<AgentDto.ExecuteResponse> execute(
            @PathVariable Long academyId,
            @RequestBody AgentDto.ExecuteRequest req) {
        AuthContext auth = AuthContextHolder.get();
        AgentContext ctx = new AgentContext(
                auth.principalType(),
                academyId,
                auth.role(),
                auth.principalId(),
                auth.name(),
                Boolean.TRUE.equals(req.getWriteApproved()));
        return ResponseEntity.ok(agentExecutor.execute(ctx, req.getCommand()));
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
