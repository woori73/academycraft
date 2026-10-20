package com.rea.academy.mysqlval;

import com.rea.academy.domain.member.ReaMemberRepository;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.dto.MemberDto;
import com.rea.academy.service.AuthService;
import com.rea.academy.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 격리 MySQL 8.x 스키마 검증 — P0-4 운영 배포 전 필수 관문.
 *
 * <p>실행 조건: 시스템 프로퍼티 {@code rea.mysql.validation=true} (기본 CI에서는 스킵).
 * 워크플로우: {@code mysql-schema-validation.yml} (임시, 검증 후 삭제).
 *
 * <p>검증:
 * 1. 컨텍스트 기동 시 Flyway V1→V2→V3 적용 (실패하면 기동 실패)
 * 2. {@code ddl-auto=validate} 로 엔티티-스키마 정합성 확인 (불일치하면 기동 실패)
 * 3. {@code flyway_schema_history} 에 V1·V2·V3 SUCCESS 기록 확인
 * 4. 인증 API 통합 테스트 (회원 생성→자격 설정→로그인→대시보드 API→로그아웃)
 */
@EnabledIfSystemProperty(named = "rea.mysql.validation", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysqlval")
@Transactional
class MySqlSchemaValidationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemberService memberService;
    @Autowired
    private AuthService authService;
    @Autowired
    private ReaMemberRepository memberRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long ownerId;

    @BeforeEach
    void setUp() {
        ownerId = memberService.createMember(MemberDto.CreateRequest.builder()
                .academyId(1L).role(ReaRole.ACADEMY_OWNER)
                .name("검증원장").phone("010-9999-0001").build()).getId();
        String authKey = memberRepository.findById(ownerId).orElseThrow().getAuthKey();
        authService.setupCredentials(authKey, "mysqlval_owner", "Mysqlval1234");
    }

    @Test
    @DisplayName("Flyway V1~V6 이 SUCCESS 로 적용됨")
    void flywayMigrationsApplied() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
    }

    // given V6 적용 후 MySQL / when rea_agent_plans 구조를 조회하면 / then 중복 방지 유니크 키와 조회 인덱스가 있고 기존 감사 테이블은 그대로다
    @Test
    @DisplayName("V6: rea_agent_plans 테이블·유니크 키(idempotency)·인덱스가 존재한다")
    void agentPlansTableCreatedByV6() {
        List<String> uniqueCols = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'rea_agent_plans' AND index_name = 'uq_agent_plan_idem' AND non_unique = 0 "
                        + "ORDER BY seq_in_index", String.class);
        assertThat(uniqueCols).containsExactly("academy_id", "idempotency_key");
        Integer indexes = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'rea_agent_plans' AND index_name IN "
                        + "('idx_agent_plan_academy_status','idx_agent_plan_principal')", Integer.class);
        assertThat(indexes).isEqualTo(2);
        Integer auditColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'rea_audit_logs'", Integer.class);
        assertThat(auditColumns).isEqualTo(10);
    }

    @Test
    @DisplayName("MySQL에서 로그인→대시보드 API→로그아웃 전체 흐름")
    void authFlowOnMysql() throws Exception {
        // 로그인
        MvcResult loginResult = mockMvc.perform(post("/api/rea/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"mysqlval_owner\",\"password\":\"Mysqlval1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();
        String body = loginResult.getResponse().getContentAsString();
        String token = body.replaceAll(".*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertThat(token).isNotBlank().doesNotContain("accessToken");

        // 대시보드 API (인증)
        mockMvc.perform(get("/api/rea/1/dashboard/stats")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 무인증 접근 차단
        mockMvc.perform(get("/api/rea/1/dashboard/stats"))
                .andExpect(status().isUnauthorized());

        // 로그아웃
        mockMvc.perform(post("/api/rea/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }
}
