# AcademyCraft Spring Security 전환 계획

> 작성일: 2026-10-16
> 상태: **구현 완료** (2026-10-16, 브랜치 `feat/login-security`, PR 제출, main 병합 대기)
> 테스트: 338/338 PASS (기존 318 + 신규 보안 20)

## 구현 요약

| 항목 | 상태 |
|---|---|
| 아이디·비밀번호 로그인 | ✅ `POST /api/rea/auth/login` |
| BCrypt 해싱 | ✅ strength 12 (운영), 4 (개발·테스트) |
| JWT (Access 15분 + Refresh 7일) | ✅ jjwt 0.12.6 |
| Refresh 로테이션 | ✅ DB 해시 대조, jti 고유값 |
| 로그인 실패 잠금 | ✅ 5회 실패 시 15분 잠금 |
| 계정 비활성화 | ✅ 로그인·토큰 모두 차단 |
| 비밀번호 재설정 | ✅ 토큰 방식 (30분 유효) |
| 원장의 회원 자격 설정 | ✅ `PUT /members/{id}/credentials` |
| X-Auth-Key 제한 | ✅ `!prod` 프로파일에서만 동작 |
| 학원별 격리 | ✅ 기존 인터셉터 유지 (JWT 컨텍스트 연동) |
| 역할별 권한 | ✅ 기존 ReaAccessRules 유지 |
| 학부모 자녀 연결 | ✅ `linkedStudentId` |

---

## 1. 현재 상태

- 인증 방식: X-Auth-Key (커스텀 헤더 기반)
- 문제점:
  - Spring Security 필터 체인 미적용
  - 세션·토큰 관리 부재
  - CSRF·세션 고정 등 표준 보호 부재
  - 역할별 인가가 컨트롤러 레벨에서 수동 처리

---

## 2. 목표 아키텍처

### 인증 방식
- **JWT 기반 stateless 인증** (Spring Security + jjwt)
- 로그인: `POST /api/auth/login` → Access Token (15분) + Refresh Token (7일)
- 인증키(X-Auth-Key)는 전환 기간 동안 하위 호환 유지 후 폐기

### 역할 분리

| 역할 | 권한 |
|---|---|
| 원장 (DIRECTOR) | 학원 전체 관리, 비활성 학생 조회, 강사 관리, 통계 |
| 강사 (TEACHER) | 담당 반 출결·숙제·성적, 공지 작성 |
| 학부모 (PARENT) | 자녀 정보 조회, 공지 조회, 1:1 상담 |

### 학원별 데이터 격리
- 모든 API에 `academyId` 스코프 강제
- JWT 클레임에 `academyId` 포함
- 서비스 레이어에서 `SecurityContext`의 academyId와 요청 academyId 대조
- 불일치 시 403 반환

### 개인정보 접근 통제
- 학부모는 본인 자녀 데이터만 조회
- 강사는 담당 반 학생만 조회
- 원장은 학원 전체 조회 (감사 로그 기록)
- PII 필드(이름·전화번호)는 역할별 마스킹 규칙 적용

---

## 3. 전환 단계

### 1단계: 기반 구축
- Spring Security 의존성 추가
- `SecurityConfig` 작성 (필터 체인, CORS, CSRF)
- JWT 유틸리티 (`JwtTokenProvider`)
- `UserDetailsService` 구현

### 2단계: 인증 API
- `POST /api/auth/login` (아이디·비밀번호)
- `POST /api/auth/refresh` (토큰 갱신)
- `POST /api/auth/logout` (토큰 무효화)

### 3단계: 인가 적용
- `@PreAuthorize` 어노테이션으로 역할별 접근 제어
- 기존 X-Auth-Key 필터와 병행 운영 (하위 호환)

### 4단계: 데이터 격리 강화
- 모든 쿼리에 academyId 스코프 검증
- 감사 로그에 접근 주체 기록

### 5단계: X-Auth-Key 폐기
- 하위 호환 기간 종료 후 제거
- 마이그레이션 가이드 제공

---

## 4. 보안 요구사항

- [ ] 비밀번호 BCrypt 해싱 (strength 12)
- [ ] JWT 시크릿 256비트 이상 (환경변수)
- [ ] Refresh Token rotation
- [ ] 로그인 시도 제한 (5회 실패 시 15분 잠금)
- [ ] 감사 로그 (인증·인가 실패, PII 접근)
- [ ] HTTPS 강제 (운영)

---

## 5. 테스트 계획

- [ ] 역할별 접근 테스트 (원장·강사·학부모)
- [ ] 타 학원 접근 차단 테스트
- [ ] 토큰 만료·갱신 테스트
- [ ] 기존 318개 테스트 회귀

---

## 6. 주의사항

- 운영 DB 변경 없음 (전환은 코드 레벨)
- 기존 X-Auth-Key 사용자는 점진적 마이그레이션
- 수납·결제 모듈은 전환 범위 제외
