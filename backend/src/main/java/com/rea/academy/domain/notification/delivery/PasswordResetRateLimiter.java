package com.rea.academy.domain.notification.delivery;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비밀번호 재설정 요청 제한 (loginId 기준, 시간당 N회).
 *
 * <p>인메모리 구현 (단일 인스턴스 기준).
 * 제한 초과 시에도 호출자에게는 계정 존재 여부와 무관한 동일 응답을 반환해야 하므로,
 * 이 클래스는 단순히 허용 여부만 반환한다.</p>
 */
@Component
public class PasswordResetRateLimiter {

    private static final long WINDOW_MS = 3_600_000L; // 1시간

    private final NotificationProperties props;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public PasswordResetRateLimiter(NotificationProperties props) {
        this.props = props;
    }

    /**
     * 요청을 허용하면 true (카운트 증가), 제한 초과면 false.
     */
    public synchronized boolean tryAcquire(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            return false;
        }
        String key = loginId.trim().toLowerCase();
        long now = Instant.now().toEpochMilli();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.pollFirst();
        }
        if (q.size() >= props.getRateLimit().getMaxPerHour()) {
            return false;
        }
        q.addLast(now);
        // 메모리 누수 방지: 키 정리
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return true;
    }

    /** 테스트용 초기화 */
    public synchronized void reset() {
        hits.clear();
    }
}
