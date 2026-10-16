package com.rea.academy.domain.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * SOLAPI v4 SMS 발송 어댑터.
 *
 * <p>API: {@code POST https://api.solapi.com/messages/v4/send}<br>
 * 인증: {@code Authorization: HMAC-SHA256 apiKey=..., date=..., salt=..., signature=...}<br>
 * signature = HMAC-SHA256(apiSecret, date + salt) hex</p>
 *
 * <p>설정({@code rea.notification.sms.enabled})이 false이거나
 * API 키·발신번호가 없으면 발송하지 않는다.
 * API 키·토큰·전화번호를 로그에 출력하지 않는다.</p>
 */
@Component
@Order(1) // SMS 우선
public class SolapiSmsDelivery implements PasswordResetDelivery {

    private static final Logger log = LoggerFactory.getLogger(SolapiSmsDelivery.class);
    private static final String API_URL = "https://api.solapi.com/messages/v4/send";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final NotificationProperties props;
    private final HttpClient httpClient;

    @Autowired
    public SolapiSmsDelivery(NotificationProperties props) {
        this(props, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    // 테스트·외부 주입용: HttpClient 교체 가능
    public SolapiSmsDelivery(NotificationProperties props, HttpClient httpClient) {
        this.props = props;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isEnabled() {
        return props.getSms().isEnabled() && props.getSms().isConfigured();
    }

    @Override
    public DeliveryResult deliver(String phone, String email, String resetUrl) {
        if (!isEnabled()) {
            return DeliveryResult.skipped("SMS 발송 비활성화");
        }
        if (phone == null || phone.isBlank()) {
            return DeliveryResult.failure("SMS", "수신자 전화번호가 없습니다.");
        }
        String normalized = phone.replaceAll("[^0-9]", "");
        if (!normalized.matches("^01[0-9]{8,9}$")) {
            return DeliveryResult.failure("SMS", "유효하지 않은 전화번호 형식입니다.");
        }
        String text = "[AcademyCraft] 비밀번호 재설정 링크입니다. 30분 이내에 접속하세요.\n" + resetUrl;
        // SMS 90바이트 초과 시 LMS로 전환 (SOLAPI 자동 처리이나 명시)
        try {
            String date = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String salt = randomSalt();
            String signature = hmacSha256Hex(props.getSms().getSolapiApiSecret(), date + salt);
            String auth = "HMAC-SHA256 apiKey=" + props.getSms().getSolapiApiKey()
                    + ", date=" + date + ", salt=" + salt + ", signature=" + signature;
            String body = "{\"message\":{\"to\":\"" + normalized
                    + "\",\"from\":\"" + props.getSms().getSender().replaceAll("[^0-9]", "")
                    + "\",\"text\":\"" + escapeJson(text) + "\"}}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                log.info("[발송] SMS 발송 요청 성공: status={}", res.statusCode());
                return DeliveryResult.success("SMS", "solapi-" + System.currentTimeMillis());
            }
            log.warn("[발송] SMS 발송 실패: status={}", res.statusCode());
            return DeliveryResult.failure("SMS", "SOLAPI 오류 status=" + res.statusCode());
        } catch (Exception e) {
            // 타임아웃·네트워크 오류: 전화번호·토큰 로그 금지
            log.warn("[발송] SMS 발송 예외: {}", e.getClass().getSimpleName());
            return DeliveryResult.failure("SMS", "발송 중 오류: " + e.getClass().getSimpleName());
        }
    }

    /** HMAC-SHA256 hex 서명 (테스트 가능하도록 package-private) */
    public static String hmacSha256Hex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    public static String randomSalt() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b).substring(0, 32);
    }

    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
