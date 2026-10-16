package com.rea.academy.notification;

import com.rea.academy.domain.notification.delivery.DeliveryResult;
import com.rea.academy.domain.notification.delivery.NotificationProperties;
import com.rea.academy.domain.notification.delivery.ZohoMailDelivery;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Zoho SMTP 어댑터 테스트 (Mock JavaMailSender — 실제 발송 없음).
 */
@ExtendWith(MockitoExtension.class)
class ZohoMailDeliveryTest {

    @Mock
    JavaMailSender mailSender;

    private NotificationProperties props(boolean enabled) {
        NotificationProperties p = new NotificationProperties();
        p.getEmail().setEnabled(enabled);
        p.getEmail().setSmtpUsername("admin@feel2real.co.kr");
        p.getEmail().setSmtpPassword("secret");
        p.getEmail().setFrom("admin@feel2real.co.kr");
        return p;
    }

    private MimeMessage mimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    @DisplayName("비활성화 상태에서는 발송하지 않고 skipped 반환")
    void disabled_skipped() {
        ZohoMailDelivery d = new ZohoMailDelivery(props(false), mailSender);
        assertThat(d.isEnabled()).isFalse();
        DeliveryResult r = d.deliver(null, "a@b.c", "https://x/y?token=t");
        assertThat(r.success()).isFalse();
        assertThat(r.channel()).isEqualTo("NONE");
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("SMTP 계정 없으면 비활성화로 간주")
    void notConfigured_disabled() {
        NotificationProperties p = new NotificationProperties();
        p.getEmail().setEnabled(true); // 계정 없음
        ZohoMailDelivery d = new ZohoMailDelivery(p, mailSender);
        assertThat(d.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("수신자 이메일 없으면 실패 (발송 시도 없음)")
    void noEmail_failure() {
        ZohoMailDelivery d = new ZohoMailDelivery(props(true), mailSender);
        DeliveryResult r = d.deliver("01012345678", null, "https://x/y?token=t");
        assertThat(r.success()).isFalse();
        assertThat(r.channel()).isEqualTo("EMAIL");
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("정상 발송 시 send 호출, 제목·수신자 확인")
    void success_sendsMessage() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage());
        ZohoMailDelivery d = new ZohoMailDelivery(props(true), mailSender);
        DeliveryResult r = d.deliver(null, "user@example.com", "https://x/reset?token=abc");
        assertThat(r.success()).isTrue();
        assertThat(r.channel()).isEqualTo("EMAIL");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getSubject()).contains("비밀번호 재설정");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("발송 중 예외 발생 시 실패 반환 (예외 전파 없음)")
    void exception_failure() {
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP 연결 실패"));
        ZohoMailDelivery d = new ZohoMailDelivery(props(true), mailSender);
        DeliveryResult r = d.deliver(null, "user@example.com", "https://x/y?token=t");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("RuntimeException");
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
