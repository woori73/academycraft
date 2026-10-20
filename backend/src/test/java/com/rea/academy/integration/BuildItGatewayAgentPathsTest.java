package com.rea.academy.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-b: BuildIt 게이트웨이 경유 요청의 /agent 정확 경로 5개만 허용한다.
 * 경로 허용은 서명·연결·역할·테넌트 검증을 대체하지 않는다(그 검증은 BuildItGatewayFilterTest 가 다룬다).
 */
class BuildItGatewayAgentPathsTest {

    private static final String PLAN = "11111111-1111-1111-1111-111111111111";
    private static final String BASE = "/api/rea/7/agent";

    private static BuildItGatewayFilter.Binding binding(String role) {
        return new BuildItGatewayFilter.Binding(1L, role, 10L, 7L, "STUDENT".equals(role) ? null : 3L,
                "STUDENT".equals(role) ? 9L : null);
    }

    private static final String[][] EXACT = {
            {"GET", BASE + "/tools"}, {"POST", BASE + "/execute"}, {"GET", BASE + "/plans/" + PLAN},
            {"POST", BASE + "/plans/" + PLAN + "/approve"}, {"POST", BASE + "/plans/" + PLAN + "/cancel"}};

    @Test
    @DisplayName("given ADMIN·TUTOR 연결과 프로토콜 1 / when 정확 경로 5개를 확인하면 / then 허용된다")
    void givenAdminOrTutorWithProtocol_whenExactPaths_thenAllowed() {
        for (String role : new String[] {"ADMIN", "TUTOR"}) {
            for (String[] c : EXACT) {
                assertThat(BuildItGatewayFilter.allowed(c[1], c[0], binding(role), 1)).as(role + " " + c[0] + " " + c[1]).isTrue();
            }
        }
    }

    @Test
    @DisplayName("given 프로토콜이 없거나 다른 BuildIt(버전 불일치) / when 에이전트 경로를 확인하면 / then 거부된다")
    void givenProtocolMissingOrDifferent_whenAgentPaths_thenDenied() {
        for (String[] c : EXACT) {
            assertThat(BuildItGatewayFilter.allowed(c[1], c[0], binding("ADMIN"), null)).as("null " + c[1]).isFalse();
            assertThat(BuildItGatewayFilter.allowed(c[1], c[0], binding("ADMIN"), 0)).as("0 " + c[1]).isFalse();
            assertThat(BuildItGatewayFilter.allowed(c[1], c[0], binding("ADMIN"), 2)).as("2 " + c[1]).isFalse();
            assertThat(BuildItGatewayFilter.allowed(c[1], c[0], binding("ADMIN"))).as("3-arg " + c[1]).isFalse();
        }
    }

    @Test
    @DisplayName("given STUDENT 연결 / when 에이전트 경로를 확인하면 / then 프로토콜이 맞아도 거부된다")
    void givenStudentBinding_whenAgentPaths_thenDenied() {
        for (String[] c : EXACT) {
            assertThat(BuildItGatewayFilter.allowed(c[1], c[0], binding("STUDENT"), 1)).as(c[1]).isFalse();
        }
    }

    @Test
    @DisplayName("given 정확 경로 / when 메서드가 다르면 / then 거부된다")
    void givenExactPaths_whenWrongMethod_thenDenied() {
        for (String[] c : EXACT) {
            for (String m : new String[] {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"}) {
                if (!m.equals(c[0])) {
                    assertThat(BuildItGatewayFilter.allowed(c[1], m, binding("ADMIN"), 1)).as(m + " " + c[1]).isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("given 허용 목록 밖의 변형 경로 / when 확인하면 / then 와일드카드처럼 열리지 않고 모두 거부된다")
    void givenPathVariants_whenChecked_thenAllDenied() {
        String[] denied = {
                BASE, BASE + "/", BASE + "/tools/", BASE + "/tools/x", BASE + "/execute/", BASE + "/plans", BASE + "/plans/",
                BASE + "/plans/" + PLAN + "/", BASE + "/plans/" + PLAN + "/approve/x", BASE + "/plans/" + PLAN + "/delete",
                BASE + "/plans/not-a-uuid", BASE + "/plans/not-a-uuid/approve", BASE + "/plans/" + PLAN.substring(1),
                BASE + "/plans/" + PLAN + "%2f..", BASE + "/plans/../tools", BASE + "/../students", BASE + "/Tools",
                BASE + "//tools", BASE + "/tools;x=1", "/api/rea/7/AGENT/tools", "/api/rea/07/agent/tools",
                "/api/rea/0/agent/tools", "/api/rea/7/agent-evil/tools", "/api/rea/7/agentx"};
        for (String p : denied) {
            for (String m : new String[] {"GET", "POST"}) {
                assertThat(BuildItGatewayFilter.allowed(p, m, binding("ADMIN"), 1)).as(m + " " + p).isFalse();
            }
        }
    }

    @Test
    @DisplayName("given 다른 학원 경로 / when 연결된 학원(7)이 아니면 / then 거부된다 (타 학원 접근 차단)")
    void givenOtherAcademyPath_thenDenied() {
        assertThat(BuildItGatewayFilter.allowed("/api/rea/8/agent/tools", "GET", binding("ADMIN"), 1)).isFalse();
        assertThat(BuildItGatewayFilter.allowed("/api/rea/8/agent/execute", "POST", binding("TUTOR"), 1)).isFalse();
        assertThat(BuildItGatewayFilter.allowed("/api/rea/8/agent/plans/" + PLAN + "/approve", "POST", binding("ADMIN"), 1)).isFalse();
    }

    @Test
    @DisplayName("given 같은 연결 / when 기존 업무 경로를 확인하면 / then 기존대로 허용된다 (수동 기능 보존)")
    void givenBinding_whenExistingBusinessPaths_thenStillAllowed() {
        assertThat(BuildItGatewayFilter.allowed("/api/rea/7/students", "GET", binding("ADMIN"), null)).isTrue();
        assertThat(BuildItGatewayFilter.allowed("/api/rea/7/students", "GET", binding("ADMIN"), 1)).isTrue();
        assertThat(BuildItGatewayFilter.allowed("/api/rea/7/classes", "POST", binding("TUTOR"))).isTrue();
        assertThat(BuildItGatewayFilter.allowed("/api/rea/8/students", "GET", binding("ADMIN"))).isFalse();
    }

    @Test
    @DisplayName("given 경로 문자열 / when 에이전트 경로 여부를 판정하면 / then agent 세그먼트만 해당한다")
    void givenPaths_whenIsAgentPath_thenOnlyAgentSegment() {
        assertThat(BuildItGatewayFilter.isAgentPath("/api/rea/7/agent")).isTrue();
        assertThat(BuildItGatewayFilter.isAgentPath("/api/rea/7/agent/tools")).isTrue();
        assertThat(BuildItGatewayFilter.isAgentPath("/api/rea/7/students")).isFalse();
        assertThat(BuildItGatewayFilter.isAgentPath("/api/rea/7/agentx")).isFalse();
    }
}
