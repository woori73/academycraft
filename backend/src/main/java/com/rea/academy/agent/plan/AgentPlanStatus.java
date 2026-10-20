package com.rea.academy.agent.plan;

/**
 * AI 쓰기 실행 계획 상태.
 * PENDING → EXECUTING → EXECUTED | FAILED 가 정상 흐름이며, 한 번 벗어난 상태로 되돌아가지 않는다.
 * CANCELLED / EXPIRED / INVALIDATED 도 종결 상태다 (재실행은 새 계획 + 새 승인).
 */
public enum AgentPlanStatus {
    PENDING,
    EXECUTING,
    EXECUTED,
    FAILED,
    CANCELLED,
    EXPIRED,
    INVALIDATED
}
