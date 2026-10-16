package com.rea.academy.domain.notification.delivery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Zoho SMTP 발송기 설정.
 *
 * <p>SMTP 계정·비밀번호는 환경변수로만 주입한다
 * ({@code ZOHO_SMTP_USER}, {@code ZOHO_SMTP_PASSWORD}).
 * 발송 자체는 {@code rea.notification.email.enabled=true}일 때만 수행된다.</p>
 */
@Configuration
public class MailConfig {

    private final NotificationProperties props;

    public MailConfig(NotificationProperties props) {
        this.props = props;
    }

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(props.getEmail().getSmtpHost());
        sender.setPort(props.getEmail().getSmtpPort());
        sender.setUsername(props.getEmail().getSmtpUsername());
        sender.setPassword(props.getEmail().getSmtpPassword());
        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        return sender;
    }
}
