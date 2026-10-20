package com.rea.academy.agent.plan;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.security.AuthContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 정규화 JSON·계획 해시·연결 지문 단위 테스트 (스프링 컨텍스트 없음) */
class AgentPlanHasherTest {

    private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 10, 20, 12, 0, 0);

    private static AgentContext ctx(ReaRole role, long principal) {
        return new AgentContext(AuthContext.PrincipalType.MEMBER, 1L, role, principal, "n", false);
    }

    @Test
    @DisplayName("given 키 순서만 다른 두 맵 / when 정규화 JSON 을 만들면 / then 문자열과 해시가 같다")
    void givenDifferentKeyOrder_whenCanonical_thenSameJsonAndHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", "x");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", "x");
        b.put("b", 1);

        assertThat(AgentPlanHasher.canonicalJson(a)).isEqualTo(AgentPlanHasher.canonicalJson(b));
        assertThat(AgentPlanHasher.sha256Hex(AgentPlanHasher.canonicalJson(a)))
                .isEqualTo(AgentPlanHasher.sha256Hex(AgentPlanHasher.canonicalJson(b)));
    }

    @Test
    @DisplayName("given 계획 해시의 각 구성요소 / when 하나만 바꾸면 / then 해시가 달라진다 (학원·사용자·역할·연결·도구·입력·미리보기·만료)")
    void givenEachComponent_whenChanged_thenHashChanges() {
        String base = AgentPlanHasher.planHash(1L, 2L, "ACADEMY_OWNER", "fp", "registerStudent", "in", "pv", EXPIRES);

        assertThat(AgentPlanHasher.planHash(9L, 2L, "ACADEMY_OWNER", "fp", "registerStudent", "in", "pv", EXPIRES)).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 9L, "ACADEMY_OWNER", "fp", "registerStudent", "in", "pv", EXPIRES)).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 2L, "TEACHER", "fp", "registerStudent", "in", "pv", EXPIRES)).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 2L, "ACADEMY_OWNER", "fp2", "registerStudent", "in", "pv", EXPIRES)).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 2L, "ACADEMY_OWNER", "fp", "createNoticeDraft", "in", "pv", EXPIRES)).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 2L, "ACADEMY_OWNER", "fp", "registerStudent", "in2", "pv", EXPIRES)).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 2L, "ACADEMY_OWNER", "fp", "registerStudent", "in", "pv2", EXPIRES)).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 2L, "ACADEMY_OWNER", "fp", "registerStudent", "in", "pv", EXPIRES.plusSeconds(1))).isNotEqualTo(base);
        assertThat(AgentPlanHasher.planHash(1L, 2L, "ACADEMY_OWNER", "fp", "registerStudent", "in", "pv", EXPIRES)).isEqualTo(base);
    }

    @Test
    @DisplayName("given 같은 주체 / when 역할·인증 경로·주체가 다르면 / then 연결 지문이 다르다")
    void givenDifferentRoleOrSource_whenFingerprint_thenDiffers() {
        String base = AgentPlanHasher.fingerprint(ctx(ReaRole.ACADEMY_OWNER, 5L), "DIRECT");

        assertThat(AgentPlanHasher.fingerprint(ctx(ReaRole.TEACHER, 5L), "DIRECT")).isNotEqualTo(base);
        assertThat(AgentPlanHasher.fingerprint(ctx(ReaRole.ACADEMY_OWNER, 6L), "DIRECT")).isNotEqualTo(base);
        assertThat(AgentPlanHasher.fingerprint(ctx(ReaRole.ACADEMY_OWNER, 5L), "BUILDIT:ADMIN")).isNotEqualTo(base);
        assertThat(AgentPlanHasher.fingerprint(ctx(ReaRole.ACADEMY_OWNER, 5L), "BUILDIT:ADMIN"))
                .isNotEqualTo(AgentPlanHasher.fingerprint(ctx(ReaRole.ACADEMY_OWNER, 5L), "BUILDIT:TUTOR"));
        assertThat(AgentPlanHasher.fingerprint(ctx(ReaRole.ACADEMY_OWNER, 5L), null)).isEqualTo(base);
    }

    @Test
    @DisplayName("given null 이나 다른 값 / when 상수 시간 비교하면 / then 같을 때만 true")
    void givenValues_whenConstantTimeEquals_thenOnlyEqualIsTrue() {
        assertThat(AgentPlanHasher.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(AgentPlanHasher.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(AgentPlanHasher.constantTimeEquals("abc", null)).isFalse();
        assertThat(AgentPlanHasher.constantTimeEquals(null, null)).isFalse();
    }
}
