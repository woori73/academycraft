package com.rea.academy.notification;

import com.rea.academy.domain.notification.delivery.DeliveryResult;
import com.rea.academy.domain.notification.delivery.NotificationProperties;
import com.rea.academy.domain.notification.delivery.PasswordResetDelivery;
import com.rea.academy.domain.notification.delivery.PasswordResetDeliveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 오케스트레이터 테스트: 재시도·채널 폴백·예외 없음 보장.
 */
class PasswordResetDeliveryServiceTest {

    private static PasswordResetDelivery stub(boolean enabled, int failTimes,
                                              AtomicInteger calls, String channel) {
        return new PasswordResetDelivery() {
            @Override
            public boolean isEnabled() { return enabled; }

            @Override
            public DeliveryResult deliver(String phone, String email, String resetUrl) {
                calls.incrementAndGet();
                if (calls.get() <= failTimes) {
                    return DeliveryResult.failure(channel, "일시 오류");
                }
                return DeliveryResult.success(channel, "msg-1");
            }
        };
    }

    private NotificationProperties props(int maxAttempts, long backoffMs) {
        NotificationProperties p = new NotificationProperties();
        p.getRetry().setMaxAttempts(maxAttempts);
        p.getRetry().setBackoffMs(backoffMs);
        return p;
    }

    @Test
    @DisplayName("첫 시도 성공 시 재시도 없음")
    void successFirstTry_noRetry() {
        AtomicInteger calls = new AtomicInteger();
        PasswordResetDeliveryService svc = new PasswordResetDeliveryService(
                List.of(stub(true, 0, calls, "SMS")), props(3, 1));
        DeliveryResult r = svc.deliver("01012345678", null, "https://x/y");
        assertThat(r.success()).isTrue();
        assertThat(r.channel()).isEqualTo("SMS");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("2회 실패 후 3회째 성공 (지수 백오프 재시도)")
    void retryThenSuccess() {
        AtomicInteger calls = new AtomicInteger();
        PasswordResetDeliveryService svc = new PasswordResetDeliveryService(
                List.of(stub(true, 2, calls, "SMS")), props(3, 1));
        DeliveryResult r = svc.deliver("01012345678", null, "https://x/y");
        assertThat(r.success()).isTrue();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("최대 시도 초과 시 실패 반환 (예외 없음)")
    void maxAttemptsExceeded_failure() {
        AtomicInteger calls = new AtomicInteger();
        PasswordResetDeliveryService svc = new PasswordResetDeliveryService(
                List.of(stub(true, 99, calls, "SMS")), props(3, 1));
        DeliveryResult r = svc.deliver("01012345678", null, "https://x/y");
        assertThat(r.success()).isFalse();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("SMS 실패 시 이메일 채널로 폴백")
    void fallbackToEmail() {
        AtomicInteger smsCalls = new AtomicInteger();
        AtomicInteger mailCalls = new AtomicInteger();
        PasswordResetDeliveryService svc = new PasswordResetDeliveryService(
                List.of(stub(true, 99, smsCalls, "SMS"),
                        stub(true, 0, mailCalls, "EMAIL")),
                props(2, 1));
        DeliveryResult r = svc.deliver("01012345678", "a@b.c", "https://x/y");
        assertThat(r.success()).isTrue();
        assertThat(r.channel()).isEqualTo("EMAIL");
        assertThat(smsCalls.get()).isEqualTo(2);
        assertThat(mailCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("비활성화 채널은 건너뜀")
    void disabledSkipped() {
        AtomicInteger calls = new AtomicInteger();
        PasswordResetDeliveryService svc = new PasswordResetDeliveryService(
                List.of(stub(false, 0, calls, "SMS")), props(3, 1));
        DeliveryResult r = svc.deliver("01012345678", null, "https://x/y");
        assertThat(r.success()).isFalse();
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("어댑터가 예외를 던져도 서비스는 예외를 던지지 않음")
    void adapterThrows_noException() {
        PasswordResetDelivery exploding = new PasswordResetDelivery() {
            @Override
            public boolean isEnabled() { return true; }

            @Override
            public DeliveryResult deliver(String phone, String email, String resetUrl) {
                throw new RuntimeException("boom");
            }
        };
        PasswordResetDeliveryService svc = new PasswordResetDeliveryService(
                List.of(exploding), props(2, 1));
        DeliveryResult r = svc.deliver("01012345678", null, "https://x/y");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("어댑터 오류");
    }
}
