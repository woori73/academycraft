package com.rea.academy.domain.notification.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 발송 관련 설정.
 *
 * <p>모든 민감값(API 키·SMTP 비밀번호)은 환경변수로 주입한다.
 * 운영 기본값은 전부 비활성화(실제 발송 OFF)이다.</p>
 *
 * <pre>
 * rea.notification.sms.enabled=false
 * rea.notification.sms.solapi-api-key=${SOLAPI_API_KEY:}
 * rea.notification.sms.solapi-api-secret=${SOLAPI_API_SECRET:}
 * rea.notification.sms.sender=${SOLAPI_SENDER:}
 * rea.notification.email.enabled=false
 * rea.notification.email.smtp-username=${ZOHO_SMTP_USER:}
 * rea.notification.email.smtp-password=${ZOHO_SMTP_PASSWORD:}
 * rea.notification.email.from=${ZOHO_SMTP_FROM:}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "rea.notification")
public class NotificationProperties {

    private final Sms sms = new Sms();
    private final Email email = new Email();
    private final Retry retry = new Retry();
    private final RateLimit rateLimit = new RateLimit();
    /** 재설정 링크 기본 URL (예: https://academycraft.example.com) */
    private String resetBaseUrl = "";

    public Sms getSms() { return sms; }
    public Email getEmail() { return email; }
    public Retry getRetry() { return retry; }
    public RateLimit getRateLimit() { return rateLimit; }
    public String getResetBaseUrl() { return resetBaseUrl; }
    public void setResetBaseUrl(String resetBaseUrl) { this.resetBaseUrl = resetBaseUrl; }

    public static class Sms {
        /** 실제 SMS 발송 활성화 (기본 false) */
        private boolean enabled = false;
        private String solapiApiKey = "";
        private String solapiApiSecret = "";
        /** 발신번호 (사전 등록 필수) */
        private String sender = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSolapiApiKey() { return solapiApiKey; }
        public void setSolapiApiKey(String solapiApiKey) { this.solapiApiKey = solapiApiKey; }
        public String getSolapiApiSecret() { return solapiApiSecret; }
        public void setSolapiApiSecret(String solapiApiSecret) { this.solapiApiSecret = solapiApiSecret; }
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }

        /** 발송에 필요한 설정이 모두 갖춰졌는지 */
        public boolean isConfigured() {
            return !solapiApiKey.isBlank() && !solapiApiSecret.isBlank() && !sender.isBlank();
        }
    }

    public static class Email {
        /** 실제 이메일 발송 활성화 (기본 false) */
        private boolean enabled = false;
        private String smtpHost = "smtp.zoho.com";
        private int smtpPort = 587;
        private String smtpUsername = "";
        private String smtpPassword = "";
        private String from = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSmtpHost() { return smtpHost; }
        public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }
        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }
        public String getSmtpUsername() { return smtpUsername; }
        public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }
        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public boolean isConfigured() {
            return !smtpUsername.isBlank() && !smtpPassword.isBlank() && !from.isBlank();
        }
    }

    public static class Retry {
        /** 최대 시도 횟수 (초기 1회 + 재시도) */
        private int maxAttempts = 3;
        /** 지수 백오프 기본 대기 (ms) */
        private long backoffMs = 1000;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getBackoffMs() { return backoffMs; }
        public void setBackoffMs(long backoffMs) { this.backoffMs = backoffMs; }
    }

    public static class RateLimit {
        /** 시간당 최대 재설정 요청 횟수 (loginId 기준) */
        private int maxPerHour = 5;

        public int getMaxPerHour() { return maxPerHour; }
        public void setMaxPerHour(int maxPerHour) { this.maxPerHour = maxPerHour; }
    }
}
