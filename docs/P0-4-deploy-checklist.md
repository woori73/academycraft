# P0-4: 운영 배포 점검표

작성일: 2026-10-16
상태: 점검표 (운영 배포 실행은 별도 승인 전까지 금지)

## 1. 환경변수

| 변수 | 용도 | 상태 |
|---|---|---|
| REA_JWT_SECRET | JWT 서명 (256비트 이상) | 미설정 - 운영 배포 전 필수 |
| (없음) | DB 접속 정보 | 운영 DB 연결 자체가 미승인 |

주의: REA_JWT_SECRET 미설정 시 랜덤 생성되어 재시작마다 토큰 무효화. 운영에서는 반드시 설정.

## 2. 보안 설정

- [ ] rea.security.cookie-secure=true (HTTPS 운영)
- [ ] rea.security.expose-reset-token=false (운영)
- [ ] rea.security.bcrypt-strength=12 (운영 기본값)
- [ ] X-Auth-Key 폴백 비활성화 확인 (prod 프로파일)
- [ ] CORS 설정 확인 (필요 시)

## 3. DB 마이그레이션

- [ ] Flyway V1, V2 스크립트 위치 확인 (backend/src/main/resources/db/migration/)
- [ ] 운영 DB 적용은 별도 승인 필요
- [ ] 적용 전 flyway_schema_history 백업
- [ ] V1: 초기 스키마, V2: 로그인, 보안 테이블

## 4. 배포 전 확인

- [ ] CI 통과 (main)
- [ ] 브라우저 QA 완료
- [ ] 비밀번호 재설정 발송 연동 완료
- [ ] HTTPS 인증서
- [ ] 운영 도메인 확정
- [ ] 롤백 계획 수립

## 5. 금지 사항

- 운영 배포 실행 금지 (별도 승인 전까지)
- 운영 DB 직접 변경 금지
- AWS 인프라 변경 금지
