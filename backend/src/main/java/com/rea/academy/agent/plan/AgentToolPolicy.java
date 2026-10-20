package com.rea.academy.agent.plan;

import com.rea.academy.agent.AgentContext;
import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.security.AuthContext;

/**
 * AI 쓰기 권한 정책 (확정: 초기에는 ACADEMY_OWNER 만).
 * 강사(TEACHER)는 조회와 초안 확인만 가능하고 AI 쓰기는 차단한다.
 * 기존 수동 업무 권한({@code ReaAccessRules})은 이 정책과 무관하며 변경하지 않는다.
 */
public final class AgentToolPolicy {

    private AgentToolPolicy() {
    }

    /** AI 쓰기 계획을 만들고 승인할 수 있는가 */
    public static boolean mayUseAiWrite(AgentContext ctx) {
        return ctx.principalType() == AuthContext.PrincipalType.MEMBER && ctx.role() == ReaRole.ACADEMY_OWNER;
    }

    /** 쓰기를 실행하지는 못하지만 서버 미리보기(초안)는 볼 수 있는가 */
    public static boolean mayViewDraft(AgentContext ctx) {
        return ctx.principalType() == AuthContext.PrincipalType.MEMBER && ctx.role() == ReaRole.TEACHER;
    }
}
