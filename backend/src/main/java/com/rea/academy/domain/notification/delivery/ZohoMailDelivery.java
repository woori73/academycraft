package com.rea.academy.domain.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;

/**
 * Zoho SMTP 이메일 발송 어댑터.
 *
 * <p>JavaMailSender 빈은 {@link MailConfig}에서 생성한다.
 * 설정({@code rea.notification.email.enabled})이 false이거나
 * SMTP 계정이 없으면 발송하지 않는다.
 * 토큰·수신자 주소를 로그에 출력하지 않는다.</p>
 */
@Component
@Order(2) // SMS 실패 시 폴백
public class ZohoMailDelivery implements PasswordResetDelivery {

    private static final Logger log = LoggerFactory.getLogger(ZohoMailDelivery.class);

    private final NotificationProperties props;
    private final JavaMailSender mailSender;

    public ZohoMailDelivery(NotificationProperties props, JavaMailSender mailSender) {
        this.props = props;
        this.mailSender = mailSender;
    }

    @Override
    public boolean isEnabled() {
        return props.getEmail().isEnabled() && props.getEmail().isConfigured();
    }

    @Override
    public DeliveryResult deliver(String phone, String email, String resetUrl) {
        if (!isEnabled()) {
            return DeliveryResult.skipped("이메일 발송 비활성화");
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            return DeliveryResult.failure("EMAIL", "수신자 이메일이 없습니다.");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(props.getEmail().getFrom());
            helper.setTo(email.trim());
            helper.setSubject("[AcademyCraft] 비밀번호 재설정 안내");
            helper.setText("비밀번호 재설정을 요청하셨습니다.\n\n"
                    + "아래 링크에서 30분 이내에 새 비밀번호를 설정하세요.\n"
                    + resetUrl + "\n\n"
                    + "본인이 요청하지 않았다면 이 메일을 무시하세요.", false);
            mailSender.send(message);
            log.info("[발송] 이메일 발송 성공");
            return DeliveryResult.success("EMAIL", "zoho-" + System.currentTimeMillis());
        } catch (Exception e) {
            // 타임아웃·인증 오류: 주소·토큰 로그 금지
            log.warn("[발송] 이메일 발송 예외: {}", e.getClass().getSimpleName());
            return DeliveryResult.failure("EMAIL", "발송 중 오류: " + e.getClass().getSimpleName());
        }
    }
}
