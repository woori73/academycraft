package com.rea.academy.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/** 승인 대기 상한·분당 생성 상한 (설정값을 낮춰 검증) */
@SpringBootTest(properties = {
        "rea.agent.approval.max-pending-per-principal=3",
        "rea.agent.approval.max-creates-per-minute=5"})
@AutoConfigureMockMvc
class AgentApprovalLimitsTest extends AgentApprovalTestBase {

    @Test
    @DisplayName("승인 대기 상한: 대기 중 계획이 상한에 닿으면 429, 하나를 취소하면 다시 만들 수 있다")
    void givenPendingCap_whenExceeded_then429_andRecoversAfterCancel() throws Exception {
        JsonNode first = registerPlan("대기1");
        registerPlan("대기2");
        registerPlan("대기3");

        MvcResult over = execute(ownerKey, academyId, "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"초과\"}}");
        cancel(ownerKey, academyId, first.get("planId").asText());
        MvcResult after = execute(ownerKey, academyId, "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"복구\"}}");

        assertThat(over.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(over).get("errorCode").asText()).isEqualTo("TOO_MANY_PENDING");
        assertThat(body(after).get("plan").get("status").asText()).isEqualTo("PENDING");
        assertThat(studentCount("초과")).isZero();
    }

    @Test
    @DisplayName("분당 생성 상한: 대기 계획이 적어도 1분에 너무 많이 만들면 429")
    void givenPerMinuteCap_whenExceeded_then429() throws Exception {
        for (int i = 0; i < 5; i++) {
            JsonNode p = registerPlan("분당" + i);
            cancel(ownerKey, academyId, p.get("planId").asText());
        }

        MvcResult over = execute(ownerKey, academyId, "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"분당초과\"}}");

        assertThat(over.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(over).get("errorCode").asText()).isEqualTo("RATE_LIMITED");
    }
}
