# 후속 작업: 운영용 비밀번호 재설정 SMS·이메일 연동

> 상태: 미착수 (별도 승인 후 진행)
> 관련: `AuthService.requestPasswordReset()` — 현재 개발 환경에서만 토큰을 응답에 포함

## 현재 상태

- `POST /api/rea/auth/password-reset/request`가 재설정 토큰을 DB에 저장
- 개발: `rea.security.expose-reset-token=true` → 응답에 토큰 포함
- 운영: `expose-reset-token=false` → 토큰 미포함, **발송 수단 없음 (구현 필요)**

## 필요한 작업

1. **발송 채널 결정**: SMS (예: 네이버 클라우드, 쿨SMS) 또는 이메일 (Zoho 운영 중: `admin@feel2real.co.kr`)
   - 회원의 연락처(`phone`)가 있으므로 SMS가 1순위
2. **`AuthService.requestPasswordReset()`에 발송 연동**
   - 토큰을 로그·응답에 절대 포함하지 않음
   - 발송 실패 시 재시도 정책 (3회, 지수 백오프)
3. **재설정 링크 형식**: `https://{도메인}/screens/AUTH-02-password-reset.html?token={token}`
   - 현재 AUTH-02 페이지는 개발용으로 토큰을 JS 변수로 받음 → URL 파라미터 방식으로 변경 필요
4. **보안 요구사항**
   - 토큰 30분 만료 (기존 유지)
   - 시간당 요청 횟수 제한 (무차별 대입 방지)
   - 발송 이력 감사 로그

## 선행 조건

- 운영 도메인 확정
- SMS 발신번호 사전 등록 (전기통신사업법)
- 발송 서비스 계약·비용 승인

## 범위 외

- 운영 배포, 운영 DB 마이그레이션, AWS 변경 (별도 승인 필요)
