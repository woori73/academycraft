package com.rea.academy.domain.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 비밀번호 재설정 링크 발송 오케스트레이터.
 *
 * <p>활성화된 채널(SMS → 이메일 순)으로 발송을 시도한다.
 * 각 채널은 최대 N회 재시도(지수 백오프)하며,
 * 모든 채널이 실패해도 예외를 던지지 않는다 (토큰은 유효하게 유지되어 재요청 가능).
 * 발송 성공 여부와 관계없이 호출자는 항상 동일한 응답을 반환해야 한다.</p>
 */
@Service
public class PasswordResetDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetDeliveryService.class);

    private final List<PasswordResetDelivery> deliveries;
    private final NotificationProperties props;

    public PasswordResetDeliveryService(List<PasswordResetDelivery> deliveries,
                                        NotificationProperties props) {
        this.deliveries = deliveries;
        this.props = props;
    }

    /**
     * 재설정 링크 발송 시도. 예외를 던지지 않는다.
     *
     * @return 하나라도 성공하면 success, 전부 실패/비활성화면 마지막 실패 결과
     */
    public DeliveryResult deliver(String phone, String email, String resetUrl) {
        DeliveryResult last = DeliveryResult.skipped("활성화된 발송 채널이 없습니다.");
        for (PasswordResetDelivery d : deliveries) {
            if (!d.isEnabled()) {
                continue;
            }
            last = deliverWithRetry(d, phone, email, resetUrl);
            if (last.success()) {
                return last;
            }
        }
        if (!last.success()) {
            log.warn("[발송] 모든 채널 발송 실패: channel={} error={}", last.channel(), last.error());
        }
        return last;
    }

    private DeliveryResult deliverWithRetry(PasswordResetDelivery d, String phone,
                                            String email, String resetUrl) {
        int maxAttempts = props.getRetry().getMaxAttempts();
        DeliveryResult last = DeliveryResult.failure("UNKNOWN", "미실행");
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                last = d.deliver(phone, email, resetUrl);
            } catch (Exception e) {
                // 어댑터 버그가 전체 흐름을 깨지 않도록 방어
                log.warn("[발송] 어댑터 예외: {}", e.getClass().getSimpleName());
                last = DeliveryResult.failure("UNKNOWN", "어댑터 오류: " + e.getClass().getSimpleName());
            }
            if (last.success()) {
                return last;
            }
            if (attempt < maxAttempts) {
                sleepQuietly(props.getRetry().getBackoffMs() * (1L << (attempt - 1)));
            }
        }
        return last;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
