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

    @Test
    @DisplayName("키 상한(5,000) 도달 시 신규 키 거부 — 유효 키는 보호됨")
    void capReached_newKeyRejected_validKeysProtected() {
        // 5,000개 키 채우기
        for (int i = 0; i < 5_000; i++) {
            assertThat(limiter.tryAcquire("bulk" + i)).as("키 %d", i).isTrue();
        }
        assertThat(limiter.keyCount()).isEqualTo(5_000);

        // 신규 키 거부
        assertThat(limiter.tryAcquire("newAttacker")).isFalse();
        assertThat(limiter.keyCount()).isEqualTo(5_000);

        // 기존 유효 키의 제한은 그대로 유지 (제거되지 않음)
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire("bulk0")).isTrue();
        }
        assertThat(limiter.tryAcquire("bulk0")).isFalse(); // 5회 제한 유지
    }

    @Test
    @DisplayName("서로 다른 ID로 우회 시도해도 상한 도달 후에는 모두 거부")
    void differentIds_bypassBlocked() {
        for (int i = 0; i < 5_000; i++) {
            limiter.tryAcquire("victim" + i);
        }
        // 공격자가 ID를 바꿔가며 요청해도 모두 거부
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("attacker" + i))
                    .as("우회 시도 %d", i).isFalse();
        }
        // 기존 키는 여전히 정상 동작
        assertThat(limiter.tryAcquire("victim0")).isTrue();
    }
}
