package com.rea.academy.domain.notification.delivery;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비밀번호 재설정 요청 제한 (loginId 기준, 시간당 N회).
 *
 * <p>인메모리 구현 (단일 인스턴스 기준).
 * 제한 초과 시에도 호출자에게는 계정 존재 여부와 무관한 동일 응답을 반환해야 하므로,
 * 이 클래스는 단순히 허용 여부만 반환한다.</p>
 *
 * <p>메모리 보호 (fail-closed):
 * <ul>
 *   <li>키 최대 5,000개 — 상한 도달 시 만료 기록만 정리</li>
 *   <li>만료 정리 후에도 공간이 없으면 <strong>신규 키 요청을 거부</strong>
 *       (유효한 기존 키를 절대 제거하지 않음 — 제한 우회 방지)</li>
 *   <li>매 요청마다 해당 키의 만료 타임스탬프 정리</li>
 *   <li>1,000회 요청마다 전체 만료 항목 스윕</li>
 * </ul></p>
 */
@Component
public class PasswordResetRateLimiter {

    private static final long WINDOW_MS = 3_600_000L; // 1시간
    /** 최대 키 수 (메모리 상한) */
    private static final int MAX_KEYS = 5_000;
    /** 전체 스윕 주기 (요청 횟수) */
    private static final int SWEEP_INTERVAL = 1_000;

    private final NotificationProperties props;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private long requestCount = 0;

    public PasswordResetRateLimiter(NotificationProperties props) {
        this.props = props;
    }

    /**
     * 요청을 허용하면 true (카운트 증가), 제한 초과면 false.
     *
     * <p>키 상한 도달 시: 만료 기록 정리 후에도 공간이 없으면
     * 신규 키는 거부한다. 기존 유효 키는 절대 제거하지 않는다.</p>
     */
    public synchronized boolean tryAcquire(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return false;
        }
        String key = loginId.trim().toLowerCase();
        long now = Instant.now().toEpochMilli();

        // 주기적 전체 스윕 (만료 항목 제거)
        if (++requestCount % SWEEP_INTERVAL == 0) {
            sweepExpired(now);
        }

        Deque<Long> q = hits.get(key);
        if (q == null) {
            // 신규 키: 상한 도달 시 만료 정리 후에도 공간이 없으면 거부
            if (hits.size() >= MAX_KEYS) {
                sweepExpired(now);
                if (hits.size() >= MAX_KEYS) {
                    return false;
                }
            }
            q = new ArrayDeque<>();
            hits.put(key, q);
        }
        evictExpiredTimestamps(q, now);
        if (q.size() >= props.getRateLimit().getMaxPerHour()) {
            return false;
        }
        q.addLast(now);
        return true;
    }

    /** 해당 키의 만료 타임스탬프 제거 */
    private void evictExpiredTimestamps(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.pollFirst();
        }
    }

    /** 전체 맵에서 만료된 항목 제거 */
    private void sweepExpired(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Deque<Long>> e = it.next();
            evictExpiredTimestamps(e.getValue(), now);
            if (e.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    /** 테스트용 초기화 */
    public synchronized void reset() {
        hits.clear();
        requestCount = 0;
    }

    /** 테스트용: 현재 키 수 */
    public synchronized int keyCount() {
        return hits.size();
    }
}
