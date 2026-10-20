package com.rea.academy.agent.plan;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.agent.AgentTool;
import com.rea.academy.domain.admin.AuditActionType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 실행 실패·재시도 금지·미리보기 미구현 도구(fail-closed). 테스트 전용 도구는 이 클래스의 컨텍스트에만 등록된다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AgentApprovalFailureTest.TestTools.class)
class AgentApprovalFailureTest extends AgentApprovalTestBase {

    static final AtomicInteger FAILING_EXECUTIONS = new AtomicInteger();

    @BeforeEach
    void resetCounter() {
        FAILING_EXECUTIONS.set(0);
    }

    @Test
    @DisplayName("실행 실패: 계획 단계에서는 도구가 실행되지 않고, 승인 후 실패하면 FAILED 로 남으며 자동 재시도·재승인이 없다")
    void givenFailingTool_whenApprove_thenFailedOnce_andNoRetry() throws Exception {
        MvcResult created = execute(ownerKey, academyId, "{\"tool\":\"failingTestTool\",\"params\":{\"note\":\"x\"}}");
        JsonNode plan = body(created).get("plan");
        assertThat(FAILING_EXECUTIONS.get()).isZero();

        MvcResult first = approve(ownerKey, academyId, plan.get("planId").asText(), plan.get("planHash").asText());
        MvcResult again = approve(ownerKey, academyId, plan.get("planId").asText(), plan.get("planHash").asText());

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(first).get("status").asText()).isEqualTo("FAILED");
        assertThat(body(first).get("failureCode").asText()).isEqualTo("TOOL_ERROR");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code").asText()).isEqualTo("PLAN_FAILED");
        assertThat(FAILING_EXECUTIONS.get()).isEqualTo(1);
        assertThat(auditCount(AuditActionType.AGENT_PLAN_FAILED)).isEqualTo(1);
        assertThat(auditCount(AuditActionType.AGENT_PLAN_EXECUTED)).isZero();
    }

    @Test
    @DisplayName("preview 를 구현하지 않은 쓰기 도구: 501 로 거부되고 계획이 만들어지지 않는다 (fail-closed)")
    void givenWriteToolWithoutPreview_whenRequest_then501_andNoPlan() throws Exception {
        MvcResult r = execute(ownerKey, academyId, "{\"tool\":\"noPreviewTestTool\",\"params\":{}}");

        assertThat(r.getResponse().getStatus()).isEqualTo(501);
        assertThat(body(r).get("errorCode").asText()).isEqualTo("PREVIEW_NOT_IMPLEMENTED");
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
    }

    @TestConfiguration
    static class TestTools {
        @Bean
        AgentTool failingTestTool() {
            return new AgentTool() {
                @Override public String getName() { return "failingTestTool"; }
                @Override public String getDescription() { return "테스트 전용: 항상 실패하는 쓰기 도구"; }
                @Override public Map<String, String> getInputSchema() { return Map.of("note*", "메모"); }
                @Override public boolean isWrite() { return true; }
                @Override public Map<String, Object> preview(Map<String, Object> input, AgentContext ctx) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("summary", "테스트 실패 도구");
                    p.put("changes", Map.of("메모", String.valueOf(input.get("note"))));
                    return p;
                }
                @Override public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
                    FAILING_EXECUTIONS.incrementAndGet();
                    throw new IllegalStateException("의도된 실패");
                }
            };
        }

        @Bean
        AgentTool noPreviewTestTool() {
            return new AgentTool() {
                @Override public String getName() { return "noPreviewTestTool"; }
                @Override public String getDescription() { return "테스트 전용: preview 미구현 쓰기 도구"; }
                @Override public Map<String, String> getInputSchema() { return Map.of(); }
                @Override public boolean isWrite() { return true; }
                @Override public Map<String, Object> execute(Map<String, Object> input, AgentContext ctx) {
                    throw new AssertionError("preview 가 없는 도구는 절대 실행되면 안 된다");
                }
            };
        }
    }
}
