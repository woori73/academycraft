package com.rea.academy.domain.notification.delivery;

/**
 * 비밀번호 재설정 링크 발송 채널 추상화.
 *
 * <p>구현체는 외부 발송(SOLAPI SMS, Zoho SMTP 등)을 담당한다.
 * 모든 구현체는 설정으로 비활성화할 수 있어야 하며,
 * 비활성화 상태에서는 실제 외부 호출을 절대 하지 않는다.</p>
 */
public interface PasswordResetDelivery {

    /**
     * 이 채널이 설정상 활성화되어 있는지.
     * 비활성화면 {@link #deliver}를 호출하지 않는다.
     */
    boolean isEnabled();

    /**
     * 재설정 링크를 발송한다.
     *
     * @param phone    수신자 전화번호 (없을 수 있음, SMS용)
     * @param email    수신자 이메일 (없을 수 있음, 이메일용)
     * @param resetUrl 재설정 URL (토큰 포함)
     * @return 발송 결과 (토큰·개인정보 미포함)
     */
    DeliveryResult deliver(String phone, String email, String resetUrl);
}
