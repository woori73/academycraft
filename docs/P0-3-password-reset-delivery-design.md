# P0-3: 비밀번호 재설정 발송 연동 설계

작성일: 2026-10-16
상태: 설계 (구현, 계약, 실제 발송은 별도 승인 후)
관련: docs/FOLLOWUP-password-reset-delivery.md, AuthService.requestPasswordReset()

## 1. 발송 서비스 선정

### SMS (1순위)

| 서비스 | 단가 (참고) | 비고 |
|---|---|---|
| CoolSMS (SOLAPI) | SMS 약 20원/건, LMS 50원/건 | REST API, 건당 과금, 소규모 적합 |
| 네이버 클라우드 SENS | 별도 요금제 | 대량, 기업용. NCP 계정 필요 |

권장: CoolSMS (SOLAPI). 월정액 없이 건당 과금.

### 이메일 (2순위, 보조 채널)

- 기존 Zoho 운영 중 (admin@feel2real.co.kr, DKIM, SPF, DMARC 설정 완료)
- SMTP 발송으로 추가 비용 없음
- SMS 수신 불가 회원용 폴백

### 예상 비용

- 비밀번호 재설정은 저빈도 (월 수십 건 수준 예상)
- SMS 월 100건 기준 약 2,000원/월
- 이메일 추가 비용 0원

## 2. 보안 설계

### 토큰 생명주기

- 토큰 생성: SecureRandom 256비트, DB에 SHA-256 해시 저장 (원문 미저장)
- 만료: 30분 (rea.security.password-reset-minutes=30)
- 재사용 방지: 사용 즉시 무효화 (1회용)
- 요청 제한: 동일 아이디 시간당 5회 (무차별 대입 방지)
- 응답: 아이디 존재 여부와 무관하게 동일 메시지 (계정 열거 방지)

### 발송 시 보안 규칙

1. 토큰을 로그, 응답에 포함하지 않음 (운영: expose-reset-token=false)
2. 재설정 링크: https://{운영도메인}/screens/AUTH-02-password-reset.html?token={token}
   - HTTPS 필수
   - 토큰은 URL 파라미터 1회 전달 후 메모리에서만 처리
3. 발신번호 사전 등록 (전기통신사업법)
4. 발송 이력 감사 로그 (수신자 마스킹)

## 3. 구현 계획 (승인 후)

1. NotificationSender 인터페이스에 SMS, 이메일 구현체 추가
   - 현재 MockNotificationSender에서 SmsNotificationSender, EmailNotificationSender로
2. AuthService.requestPasswordReset()에 발송 연동
   - 발송 실패 시 3회 재시도 (지수 백오프)
   - 실패해도 토큰은 유효하게 유지 (재요청 가능)
3. AUTH-02 페이지: URL 파라미터 토큰 방식으로 변경
4. 모의 테스트: Mock 발송기로 전체 흐름 검증 후 실제 연동

## 4. 선행 조건 (승인 필요)

- CoolSMS 계약 및 발신번호 등록
- 운영 도메인 확정
- 실제 발송 승인
