package com.rea.academy.agent.plan;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AI 쓰기 실행 계획 (서버 보관). 승인 대상과 실행 대상을 같은 행으로 고정한다.
 *
 * - 실행 입력은 항상 이 행의 {@code inputJson} 이며, 승인 요청에서 입력을 다시 받지 않는다.
 * - planHash 는 사용자·학원·역할·연결·도구·입력·만료를 묶어 변조를 탐지한다.
 * - 상태 전이는 {@link ReaAgentPlanRepository} 의 조건부 갱신(CAS)으로만 한다.
 */
@Entity
@Table(name = "rea_agent_plans",
        uniqueConstraints = @UniqueConstraint(name = "uq_agent_plan_idem", columnNames = {"academy_id", "idempotency_key"}),
        indexes = {
                @Index(name = "idx_agent_plan_academy_status", columnList = "academy_id,status,expires_at"),
                @Index(name = "idx_agent_plan_principal", columnList = "academy_id,principal_id,created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReaAgentPlan {

    @Id
    @Column(name = "plan_id", length = 36, nullable = false, updatable = false)
    private String planId;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private Long academyId;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private Long principalId;

    /** 계획 생성 시점의 내부 역할 (ACADEMY_OWNER) */
    @Column(name = "role_at_plan", length = 30, nullable = false, updatable = false)
    private String roleAtPlan;

    /** 계획 시점 인증 경로·연결 지문 (SHA-256 hex) */
    @Column(name = "binding_fingerprint", length = 64, nullable = false, updatable = false)
    private String bindingFingerprint;

    @Column(name = "tool_name", length = 80, nullable = false, updatable = false)
    private String toolName;

    /** 정규화된 도구 입력(JSON). 승인 후 실행에 쓰이는 유일한 입력. */
    @Column(name = "input_json", length = 4000, nullable = false)
    private String inputJson;

    @Column(name = "input_sha256", length = 64, nullable = false, updatable = false)
    private String inputSha256;

    /** 서버가 도구 입력으로부터 생성한 변경 미리보기(JSON). 모델 문장이 아니다. */
    @Column(name = "preview_json", length = 4000, nullable = false, updatable = false)
    private String previewJson;

    @Column(name = "plan_hash", length = 64, nullable = false, updatable = false)
    private String planHash;

    /** RULE(자연어 규칙 파서) / STRUCTURED(도구·파라미터 직접 지정) — 권한 근거가 아니라 기록용 */
    @Column(name = "source", length = 20, nullable = false, updatable = false)
    private String source;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)   // MySQL ENUM 이 아닌 VARCHAR 로 매핑 (V6 스키마와 일치, 기존 감사 로그 엔티티와 동일 방식)
    @Column(name = "status", length = 20, nullable = false)
    private AgentPlanStatus status;

    @Column(name = "idempotency_key", length = 36, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 실행 결과(JSON, 인증키 등 비밀값 제거본) */
    @Column(name = "result_json", length = 2000)
    private String resultJson;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    public void markTerminal(AgentPlanStatus next, String failure) {
        this.status = next;
        this.failureCode = failure;
    }

    public void markExecuted(String redactedResultJson, LocalDateTime at) {
        this.status = AgentPlanStatus.EXECUTED;
        this.resultJson = redactedResultJson;
        this.executedAt = at;
    }

    public void markFailed(String code, LocalDateTime at) {
        this.status = AgentPlanStatus.FAILED;
        this.failureCode = code;
        this.executedAt = at;
    }

    public void markCancelled() {
        this.status = AgentPlanStatus.CANCELLED;
    }
}
