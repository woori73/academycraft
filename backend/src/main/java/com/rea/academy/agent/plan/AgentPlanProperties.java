package com.rea.academy.agent.plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** AI 쓰기 승인 설정. 기본값은 운영 정책(원장 전용·10분 만료)이다. */
@Component
public class AgentPlanProperties {

    /** 끄면 쓰기 도구는 일시 중지 안내만 반환한다(긴급 차단 스위치). 읽기 도구는 영향 없음. */
    private final boolean enabled;
    private final Duration ttl;
    private final int maxPendingPerPrincipal;
    private final int maxCreatesPerMinute;
    private final Duration executingTimeout;

    public AgentPlanProperties(
            @Value("${rea.agent.approval.enabled:true}") boolean enabled,
            @Value("${rea.agent.approval.ttl-minutes:10}") long ttlMinutes,
            @Value("${rea.agent.approval.max-pending-per-principal:20}") int maxPending,
            @Value("${rea.agent.approval.max-creates-per-minute:10}") int maxPerMinute,
            @Value("${rea.agent.approval.executing-timeout-minutes:5}") long executingTimeoutMinutes) {
        this.enabled = enabled;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxPendingPerPrincipal = maxPending;
        this.maxCreatesPerMinute = maxPerMinute;
        this.executingTimeout = Duration.ofMinutes(executingTimeoutMinutes);
    }

    public boolean enabled() { return enabled; }
    public Duration ttl() { return ttl; }
    public int maxPendingPerPrincipal() { return maxPendingPerPrincipal; }
    public int maxCreatesPerMinute() { return maxCreatesPerMinute; }
    public Duration executingTimeout() { return executingTimeout; }
}
