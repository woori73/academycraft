package com.rea.academy.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rea.academy.integration.BuildItGatewayFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P1-b: BuildIt 게이트웨이 경유 시 연결 지문(BuildIt 역할·사용자 ID·학원 ID) 강화와 버전 불일치 안전 실패.
 * 게이트웨이 필터가 서명 검증 후 설정하는 요청 속성을 MockMvc requestAttr 로 재현한다(클라이언트 헤더가 아님).
 */
class AgentGatewayContextTest extends AgentApprovalTestBase {

    @Autowired
    private ObjectMapper springMapper;

    private MockHttpServletRequestBuilder withGateway(MockHttpServletRequestBuilder b, String role, Object userId, Object academyId) {
        if (role != null) {
            b.requestAttr("buildit.role", role);
        }
        if (userId != null) {
            b.requestAttr("buildit.userId", userId);
        }
        if (academyId != null) {
            b.requestAttr("buildit.academyId", academyId);
        }
        return b;
    }

    private MvcResult execute(String body, String role, Object userId, Object academyId) throws Exception {
        return mvc.perform(withGateway(post("/api/rea/" + academyId0() + "/agent/execute").header("X-Auth-Key", ownerKey)
                .contentType(MediaType.APPLICATION_JSON).content(body), role, userId, academyId)).andReturn();
    }

    private MvcResult approve(String planId, String hash, String role, Object userId, Object academyId) throws Exception {
        return mvc.perform(withGateway(post("/api/rea/" + academyId0() + "/agent/plans/" + planId + "/approve").header("X-Auth-Key", ownerKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"planHash\":\"" + hash + "\"}"), role, userId, academyId)).andReturn();
    }

    private long academyId0() {
        return academyId;
    }

    private static final String REQ = "{\"tool\":\"registerStudent\",\"params\":{\"name\":\"게이트웨이학생\"}}";

    @Test
    @DisplayName("게이트웨이 역할만 있고 사용자·학원 속성이 없으면(필터·컨트롤러 버전 불일치) 403 으로 안전하게 실패하고 계획이 없다")
    void givenRoleWithoutUserAttributes_whenWrite_thenFailsSafely() throws Exception {
        MvcResult r = execute(REQ, "TUTOR", null, null);

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).get("code").asText()).isEqualTo("GATEWAY_CONTEXT_INCOMPLETE");
        assertThat(planRepository.findByAcademyIdOrderByCreatedAtAsc(academyId)).isEmpty();
    }

    @Test
    @DisplayName("같은 BuildIt 사용자·학원·역할로 계획 생성과 승인: 정상 실행된다")
    void givenSameGatewayIdentity_whenPlanAndApprove_thenExecuted() throws Exception {
        JsonNode plan = body(execute(REQ, "ADMIN", 4L, 12L)).get("plan");

        MvcResult done = approve(plan.get("planId").asText(), plan.get("planHash").asText(), "ADMIN", 4L, 12L);

        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(done).get("status").asText()).isEqualTo("EXECUTED");
        assertThat(studentCount("게이트웨이학생")).isEqualTo(1);
    }

    @Test
    @DisplayName("승인 시 BuildIt 사용자 ID가 달라지면 연결 변경으로 무효 처리되고 실행되지 않는다")
    void givenDifferentBuildItUser_whenApprove_thenInvalidated() throws Exception {
        JsonNode plan = body(execute(REQ, "ADMIN", 4L, 12L)).get("plan");

        MvcResult r = approve(plan.get("planId").asText(), plan.get("planHash").asText(), "ADMIN", 5L, 12L);

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(r).get("code").asText()).isEqualTo("CONNECTION_CHANGED");
        assertThat(statusOf(plan.get("planId").asText())).isEqualTo("INVALIDATED");
        assertThat(studentCount("게이트웨이학생")).isZero();
    }

    @Test
    @DisplayName("승인 시 BuildIt 학원 ID·역할(ADMIN→TUTOR)·인증 경로(게이트웨이→직접)가 달라져도 무효 처리된다")
    void givenDifferentBuildItAcademyRoleOrSource_whenApprove_thenInvalidated() throws Exception {
        String[] names = {"학원다름", "역할다름", "경로다름"};
        Object[][] changes = {{"ADMIN", 4L, 13L}, {"TUTOR", 4L, 12L}, {null, null, null}};
        for (int i = 0; i < names.length; i++) {
            JsonNode plan = body(execute("{\"tool\":\"registerStudent\",\"params\":{\"name\":\"" + names[i] + "\"}}", "ADMIN", 4L, 12L)).get("plan");
            MvcResult r = approve(plan.get("planId").asText(), plan.get("planHash").asText(),
                    (String) changes[i][0], changes[i][1], changes[i][2]);
            assertThat(r.getResponse().getStatus()).as(names[i]).isEqualTo(403);
            assertThat(body(r).get("code").asText()).as(names[i]).isEqualTo("CONNECTION_CHANGED");
            assertThat(studentCount(names[i])).as(names[i]).isZero();
        }
    }

    @Test
    @DisplayName("직접 로그인으로 만든 계획을 게이트웨이 경유로 승인할 수 없다 (반대 방향도 동일)")
    void givenPlanFromDirectLogin_whenApproveViaGateway_thenInvalidated() throws Exception {
        JsonNode plan = body(execute(REQ, null, null, null)).get("plan");

        MvcResult r = approve(plan.get("planId").asText(), plan.get("planHash").asText(), "ADMIN", 4L, 12L);

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(studentCount("게이트웨이학생")).isZero();
    }

    @Test
    @DisplayName("버전 호환: 운영과 같은 Spring JSON 매퍼는 모르는 필드를 무시한다 (새 BuildIt → 이전 AcademyCraft 신원 파싱이 깨지지 않음)")
    void givenSpringMapper_whenUnknownIdentityField_thenIgnored() throws Exception {
        record OldIdentity(Long userId, String role, Long ownerAcademyId, long issuedAt, String nonce) { }
        String withNewField = "{\"userId\":54,\"role\":\"TUTOR\",\"ownerAcademyId\":12,\"issuedAt\":1,\"nonce\":\"n\",\"agentProtocol\":1}";

        OldIdentity parsed = springMapper.readValue(withNewField, OldIdentity.class);

        assertThat(parsed.userId()).isEqualTo(54L);
        assertThat(springMapper.readValue("{\"userId\":1,\"role\":\"ADMIN\",\"issuedAt\":1,\"nonce\":\"n\"}",
                BuildItGatewayFilter.Identity.class).agentProtocol()).isNull();   // 이전 BuildIt → 프로토콜 없음
    }

    @Test
    @DisplayName("서명 호환(기준값): BuildIt 과 같은 입력은 항상 같은 HMAC 이 나와야 한다 — 양쪽 저장소가 같은 값을 고정한다")
    void givenGoldenVector_whenSign_thenMatchesContract() throws Exception {
        byte[] body = ("{\"command\":\"오늘 결석 학생\",\"idempotencyKey\":\"0b7e7f0e-2f4a-4a4e-9a52-3f2c1d6e9a11\"}")
                .getBytes(StandardCharsets.UTF_8);

        String signature = BuildItGatewayFilter.sign("test-only-shared-secret-with-at-least-32-bytes", "POST",
                "/api/rea/7/agent/execute", "eyJ1c2VySWQiOjU0fQ", body);

        assertThat(signature).isEqualTo("fbf7683638ad79107448a2a18a940a9576024672c497db7c93e1164a8e3680b1");
    }
}
