package com.rea.academy.agent;

import com.rea.academy.domain.member.ReaRole;
import com.rea.academy.security.AuthContext;

/**
 * Agent 도구 실행 컨텍스트 (2단계).
 * 1단계 {@link AuthContext}에서 학원 ID·역할·주체 정보를 가져오고,
 * Agent API 요청의 writeApproved를 더한다.
 *
 * 불변 값 객체 (Java Record).
 */
public record AgentContext(
        AuthContext.PrincipalType principalType,
        Long academyId,
        ReaRole role,
        Long principalId,
        String principalName,
        // 쓰기 승인 근거로 사용하지 않는다 (P1-a). AgentExecutor 는 이 값을 무시하고,
        // 쓰기 도구는 서버 보관 계획 승인(AgentPlanService)으로만 실행한다. 하위 호환용 자리만 남겨 둔다.
        boolean writeApproved) {

    public AgentContext {
        if (academyId == null) {
            throw new IllegalArgumentException("academyId는 필수입니다.");
        }
        if (role == null) {
            throw new IllegalArgumentException("role은 필수입니다.");
        }
    }

    /** 학부모(학생 인증키 로그인) 여부 */
    public boolean isParent() {
        return role == ReaRole.PARENT;
    }

    /** 원장 여부 */
    public boolean isOwner() {
        return role == ReaRole.ACADEMY_OWNER;
    }

    /** 강사 여부 */
    public boolean isTeacher() {
        return role == ReaRole.TEACHER;
    }
}
