package com.rea.academy.agent.plan;

import com.rea.academy.domain.admin.AuditActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/** 긴급 차단 스위치(rea.agent.approval.enabled=false): 쓰기만 막고 읽기·수동 기능은 그대로 */
@SpringBootTest(properties = "rea.agent.approval.enabled=false")
@AutoConfigureMockMvc
class AgentApprovalDisabledTest extends AgentApprovalTestBase {

    @Test
    @DisplayName("스위치 OFF: 쓰기 요청은 503 + 계획 없음 + 거부 감사, 읽기 도구는 정상")
    void givenSwitchOff_whenWrite_then503_andReadStillWorks() throws Exception {
        MvcResult write = execute(ownerKey, academyId, "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"스위치학생\"}}");
        MvcResult read = execute(ownerKey, academyId, "{\"command\":\"오늘 결석 학생\"}");

        assertThat(write.getResponse().getStatus()).isEqualTo(503);
        assertThat(body(write).get("errorCode").asText()).isEqualTo("APPROVAL_DISABLED");
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
        assertThat(studentCount("스위치학생")).isZero();
        assertThat(auditCount(AuditActionType.AGENT_PLAN_REJECTED)).isEqualTo(1);
        assertThat(body(read).get("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("스위치 OFF: 수동 학생 등록 API(기존 업무 기능)는 영향이 없다")
    void givenSwitchOff_whenManualRegistration_thenUnaffected() throws Exception {
        MvcResult manual = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/rea/" + academyId + "/students")
                .header("X-Auth-Key", ownerKey).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"수동학생\",\"grade\":\"중1\"}")).andReturn();

        assertThat(manual.getResponse().getStatus()).isIn(200, 201);
        assertThat(studentCount("수동학생")).isEqualTo(1);
    }
}
