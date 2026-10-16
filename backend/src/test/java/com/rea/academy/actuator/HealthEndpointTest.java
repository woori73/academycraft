package com.rea.academy.actuator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Health-check 엔드포인트 테스트 — 운영 배포 검증·ALB 타겟 헬스체크용.
 *
 * 검증:
 * - GET /actuator/health → 200 + {"status":"UP"} (인증 불필요)
 * - 상세 정보 미노출 (show-details=never)
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("GET /actuator/health → 200 + status UP (인증 불필요)")
    void healthReturnsUpWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("/actuator/health 외 엔드포인트는 노출되지 않음")
    void onlyHealthExposed() throws Exception {
        // info·metrics 등은 exposure.include=health 에 포함되지 않음
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isNotFound());
    }
}
