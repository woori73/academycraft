package com.rea.academy.notification;

import com.rea.academy.domain.notification.delivery.DeliveryResult;
import com.rea.academy.domain.notification.delivery.NotificationProperties;
import com.rea.academy.domain.notification.delivery.SolapiSmsDelivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 오류 메시지·로그에 개인정보·인증정보가 포함되지 않는지 검증.
 */
class DeliveryLogSafetyTest {

    private static final String PHONE = "01098765432";
    private static final String EMAIL = "private@example.com";
    private static final String TOKEN_URL = "https://x/reset?token=secret-token-123";

    private NotificationProperties props() {
        NotificationProperties p = new NotificationProperties();
        p.getSms().setEnabled(true);
        p.getSms().setSolapiApiKey("test-key");
        p.getSms().setSolapiApiSecret("test-secret");
        p.getSms().setSender("01012345678");
        return p;
    }

    @Test
    @DisplayName("SMS 실패 결과에 전화번호·토큰·API키가 포함되지 않음")
    void smsFailure_noPii() {
        SolapiSmsDelivery d = new SolapiSmsDelivery(props(), HttpClient.newHttpClient());
        // 잘못된 번호 → 외부 호출 없이 실패
        DeliveryResult r = d.deliver("999", EMAIL, TOKEN_URL);
        assertThat(r.success()).isFalse();
        String error = r.error();
        assertThat(error).doesNotContain(PHONE);
        assertThat(error).doesNotContain("secret-token-123");
        assertThat(error).doesNotContain("test-key");
        assertThat(error).doesNotContain("test-secret");
    }

    @Test
    @DisplayName("전화번호 없음 실패 결과에 토큰이 포함되지 않음")
    void noPhone_noToken() {
        SolapiSmsDelivery d = new SolapiSmsDelivery(props(), HttpClient.newHttpClient());
        DeliveryResult r = d.deliver(null, EMAIL, TOKEN_URL);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).doesNotContain("secret-token-123");
    }

    @Test
    @DisplayName("DeliveryResult 자체는 토큰·개인정보를 담지 않음")
    void resultHoldsNoPii() {
        DeliveryResult r = DeliveryResult.failure("SMS", "발송 중 오류: SocketTimeoutException");
        assertThat(r.toString()).doesNotContain(PHONE);
        assertThat(r.toString()).doesNotContain(EMAIL);
        assertThat(r.toString()).doesNotContain("secret-token-123");
    }
}
