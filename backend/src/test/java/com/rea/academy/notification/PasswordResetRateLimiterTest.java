package com.rea.academy.notification;

import com.rea.academy.domain.notification.delivery.NotificationProperties;
import com.rea.academy.domain.notification.delivery.PasswordResetRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재설정 요청 제한 테스트 (시간당 5회).
 */
class PasswordResetRateLimiterTest {

    private PasswordResetRateLimiter limiter;

    @BeforeEach
    void setUp() {
        NotificationProperties props = new NotificationProperties();
        props.getRateLimit().setMaxPerHour(5);
        limiter = new PasswordResetRateLimiter(props);
    }

    @Test
    @DisplayName("5회까지 허용, 6회째 차단")
    void allowFive_thenBlock() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("user1")).as("시도 %d", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire("user1")).isFalse();
        assertThat(limiter.tryAcquire("user1")).isFalse();
    }

    @Test
    @DisplayName("다른 아이디는 별도 카운트")
    void separateIds() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("userA");
        }
        assertThat(limiter.tryAcquire("userA")).isFalse();
        assertThat(limiter.tryAcquire("userB")).isTrue();
    }

    @Test
    @DisplayName("아이디 대소문자 구분 없이 동일 카운트")
    void caseInsensitive() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("User1");
        }
        assertThat(limiter.tryAcquire("USER1")).isFalse();
    }

    @Test
    @DisplayName("빈 아이디는 거부")
    void blankId_rejected() {
        assertThat(limiter.tryAcquire(null)).isFalse();
        assertThat(limiter.tryAcquire("  ")).isFalse();
    }

    @Test
    @DisplayName("reset 후 다시 허용")
    void reset_allowsAgain() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("user1");
        }
        assertThat(limiter.tryAcquire("user1")).isFalse();
        limiter.reset();
        assertThat(limiter.tryAcquire("user1")).isTrue();
    }
}
