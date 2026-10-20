package com.rea.academy.agent.plan;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReaAgentPlanRepository extends JpaRepository<ReaAgentPlan, String> {

    /** 승인·취소·상태 전이 직렬화를 위한 행 잠금 조회 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ReaAgentPlan p where p.planId = :planId")
    Optional<ReaAgentPlan> findForUpdate(@Param("planId") String planId);

    /**
     * 계획 생성 직렬화: 같은 원장의 생성 요청을 회원 행 잠금으로 순서대로 처리한다.
     * (별도 트랜잭션 없이 연결 1개로 중복 키 검사·상한 검사 경합을 없앤다. 잠금은 호출 트랜잭션 종료까지 유지)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from com.rea.academy.domain.member.ReaMember m where m.id = :memberId")
    Optional<com.rea.academy.domain.member.ReaMember> lockMemberForPlanCreation(@Param("memberId") Long memberId);

    Optional<ReaAgentPlan> findByAcademyIdAndIdempotencyKey(Long academyId, String idempotencyKey);

    long countByAcademyIdAndPrincipalIdAndStatusAndExpiresAtAfter(
            Long academyId, Long principalId, AgentPlanStatus status, LocalDateTime now);

    long countByAcademyIdAndPrincipalIdAndCreatedAtAfter(
            Long academyId, Long principalId, LocalDateTime since);

    /**
     * 1회 실행 보증(CAS): PENDING 이고 만료 전인 경우에만 EXECUTING 으로 바꾼다.
     * 영향 행 수가 1이 아니면 다른 요청이 먼저 처리한 것이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ReaAgentPlan p set p.status = com.rea.academy.agent.plan.AgentPlanStatus.EXECUTING, "
            + "p.approvedAt = :now, p.approvedBy = :by "
            + "where p.planId = :planId and p.status = com.rea.academy.agent.plan.AgentPlanStatus.PENDING "
            + "and p.expiresAt > :now")
    int claimForExecution(@Param("planId") String planId, @Param("by") Long by,
                          @Param("now") LocalDateTime now);

    List<ReaAgentPlan> findByAcademyIdOrderByCreatedAtAsc(Long academyId);
}
