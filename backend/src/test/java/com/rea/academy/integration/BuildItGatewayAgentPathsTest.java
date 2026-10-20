package com.rea.academy.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-a 범위 확인: BuildIt 게이트웨이 경유 요청에는 아직 /agent 경로를 열지 않는다.
 * (정확 경로 허용은 P1-b 에서 BuildIt·AcademyCraft 양쪽에 함께, 별도 승인 후 진행한다.)
 */
class BuildItGatewayAgentPathsTest {

    private static final String PLAN = "11111111-1111-1111-1111-111111111111";

    private static BuildItGatewayFilter.Binding binding(String role) {
        return new BuildItGatewayFilter.Binding(1L, role, 10L, 7L, "STUDENT".equals(role) ? null : 3L,
                "STUDENT".equals(role) ? 9L : null);
    }

    @Test
    @DisplayName("given BuildIt ADMIN·TUTOR·STUDENT 연결 / when 에이전트 경로를 확인하면 / then 모두 허용되지 않는다")
    void givenAnyBuildItRole_whenAgentPaths_thenNotAllowedYet() {
        String base = "/api/rea/7/agent";
        String[][] cases = {
                {"GET", base + "/tools"}, {"POST", base + "/execute"}, {"GET", base + "/plans/" + PLAN},
                {"POST", base + "/plans/" + PLAN + "/approve"}, {"POST", base + "/plans/" + PLAN + "/cancel"}};
        for (String role : new String[] {"ADMIN", "TUTOR", "STUDENT"}) {
            for (String[] c : cases) {
                assertThat(BuildItGatewayFilter.allowed(c[1], c[0], binding(role))).as(role + " " + c[0] + " " + c[1]).isFalse();
            }
        }
    }

    @Test
    @DisplayName("given 같은 연결 / when 기존 업무 경로를 확인하면 / then 기존대로 허용된다 (수동 기능 보존)")
    void givenBinding_whenExistingBusinessPaths_thenStillAllowed() {
        assertThat(BuildItGatewayFilter.allowed("/api/rea/7/students", "GET", binding("ADMIN"))).isTrue();
        assertThat(BuildItGatewayFilter.allowed("/api/rea/7/classes", "POST", binding("TUTOR"))).isTrue();
        assertThat(BuildItGatewayFilter.allowed("/api/rea/8/students", "GET", binding("ADMIN"))).isFalse();
    }
}
