package com.rea.academy.notification;

import com.rea.academy.domain.notification.delivery.DeliveryResult;
import com.rea.academy.domain.notification.delivery.NotificationProperties;
import com.rea.academy.domain.notification.delivery.SolapiSmsDelivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOLAPI SMS 어댑터 단위 테스트 (외부 호출 없음).
 */
class SolapiSmsDeliveryTest {

    private NotificationProperties props(boolean enabled) {
        NotificationProperties p = new NotificationProperties();
        p.getSms().setEnabled(enabled);
        p.getSms().setSolapiApiKey("test-key");
        p.getSms().setSolapiApiSecret("test-secret");
        p.getSms().setSender("01012345678");
        return p;
    }

    @Test
    @DisplayName("비활성화 상태에서는 발송하지 않고 skipped 반환")
    void disabled_skipped() {
        SolapiSmsDelivery d = new SolapiSmsDelivery(props(false), HttpClient.newHttpClient());
        assertThat(d.isEnabled()).isFalse();
        DeliveryResult r = d.deliver("01012345678", null, "https://x/y?token=t");
        assertThat(r.success()).isFalse();
        assertThat(r.channel()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("API 키 없으면 비활성화로 간주")
    void notConfigured_disabled() {
        NotificationProperties p = new NotificationProperties();
        p.getSms().setEnabled(true); // 키 없음
        SolapiSmsDelivery d = new SolapiSmsDelivery(p, HttpClient.newHttpClient());
        assertThat(d.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("전화번호 없으면 실패 (외부 호출 없음)")
    void noPhone_failure() {
        SolapiSmsDelivery d = new SolapiSmsDelivery(props(true), HttpClient.newHttpClient());
        DeliveryResult r = d.deliver(null, null, "https://x/y?token=t");
        assertThat(r.success()).isFalse();
        assertThat(r.channel()).isEqualTo("SMS");
    }

    @Test
    @DisplayName("잘못된 전화번호 형식이면 실패 (외부 호출 없음)")
    void invalidPhone_failure() {
        SolapiSmsDelivery d = new SolapiSmsDelivery(props(true), HttpClient.newHttpClient());
        DeliveryResult r = d.deliver("02-1234-5678", null, "https://x/y?token=t");
        assertThat(r.success()).isFalse();
    }

    @Test
    @DisplayName("HMAC-SHA256 서명이 결정적으로 생성됨")
    void hmac_deterministic() throws Exception {
        String s1 = SolapiSmsDelivery.hmacSha256Hex("secret", "date123salt456");
        String s2 = SolapiSmsDelivery.hmacSha256Hex("secret", "date123salt456");
        assertThat(s1).isEqualTo(s2);
        assertThat(s1).hasSize(64);
        assertThat(s1).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("HMAC-SHA256 서명이 키·데이터에 따라 달라짐")
    void hmac_varies() throws Exception {
        String s1 = SolapiSmsDelivery.hmacSha256Hex("secret1", "data");
        String s2 = SolapiSmsDelivery.hmacSha256Hex("secret2", "data");
        String s3 = SolapiSmsDelivery.hmacSha256Hex("secret1", "data2");
        assertThat(s1).isNotEqualTo(s2);
        assertThat(s1).isNotEqualTo(s3);
    }

    @Test
    @DisplayName("salt는 32자리 hex이며 매번 다름")
    void salt_random() {
        String a = SolapiSmsDelivery.randomSalt();
        String b = SolapiSmsDelivery.randomSalt();
        assertThat(a).hasSize(32);
        assertThat(a).matches("[0-9a-f]+");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("JSON 이스케이프 처리")
    void escapeJson() {
        String escaped = SolapiSmsDelivery.escapeJson("a\"b\\c\nd");
        assertThat(escaped).isEqualTo("a\\\"b\\\\c\\nd");
    }
}
