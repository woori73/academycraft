# REA 학원관리 보안 설계 (1단계 — 인증키 기반 인증 완성)

> 작성일: 2026-10-13
> 범위: 인증키 인증 필터, 학원별 테넌트 격리, 역할별 접근 권한, 보안 테스트
> 관련 코드: `backend/src/main/java/com/rea/academy/security/` 패키지

## 1. 인증 흐름

```
클라이언트 ── X-Auth-Key: {authKey} ──▶ AuthKeyFilter (Spring Security 체인)
                                              │
                        ┌─────────────────────┴─────────────────────┐
                        │ /api/rea/auth/** (key-login) → 통과 (공개) │
                        └─────────────────────┬─────────────────────┘
                                              ▼
                        ① ReaMember.findByAuthKeyAndActiveTrue → 원장/강사
                        ② ReaStudent.findByAuthKeyAndActiveTrue → 학부모(PARENT)
                                              │
                        ┌──────────┴──────────┐
                     찾을 수 없음           찾음
                        ▼                     ▼
                   401 JSON 반환     AuthContext 저장 (ThreadLocal + SecurityContext)
                                              │
                                              ▼
                                   ReaSecurityInterceptor
                                   ① 테넌트 격리 검사 → ② 역할 권한 검사 (ReaAccessRules)
                                              │
                                              ▼
                                          Controller
```

### 인증 컨텍스트 (AuthContext)

인증 성공 시 요청 스레드에 저장되는 정보:

| 필드 | 원장/강사 키 | 학생 키 |
|---|---|---|
| principalType | MEMBER | STUDENT |
| principalId | 회원 ID | 학생 ID |
| academyId | 소속 학원 ID | 소속 학원 ID |
| role | ACADEMY_OWNER / TEACHER | PARENT |
| name | 회원 이름 | 학부모 이름 (없으면 "학생이름 학부모") |

- 보관: `AuthContextHolder` (ThreadLocal) — 요청 종료 시 반드시 제거
- 비활성(`active=false`) 키는 인증 실패로 처리
- 세션 무상태(STATELESS): 인증 정보는 요청별로만 유지, 서버 세션 없음

### 인증키 발급·로그인

- `POST /api/rea/auth/key-login` (헤더 불필요, 공개): body `{authKey}` → `{memberId?, studentId?, academyId, role, name}`
- 원장/강사 인증키는 회원 상세 조회·재발급 API로 확인 (목록 조회에서는 제외)
- 학생 인증키는 학생 등록 응답에 포함 (학부모에게 전달용)

## 2. 401 / 403 조건

### 401 Unauthorized (ReaAuthenticationException)

| 조건 | 예 |
|---|---|
| `X-Auth-Key` 헤더 누락 | 헤더 없이 `/api/rea/**` 호출 |
| 빈 인증키 | `X-Auth-Key: ` (공백) |
| 유효하지 않은 인증키 | 위조·오타 키 |
| 비활성 키 | 탈퇴·삭제된 회원/학생의 키 |

응답: `{"message": "..."}` (필터에서 직접 JSON 반환 — DispatcherServlet 이전 단계)

### 403 Forbidden (ReaForbiddenException)

| 조건 | 예 |
|---|---|
| URL의 `{academyId}` ≠ 인증 사용자의 academyId | A학원 키로 `/api/rea/2/...` 호출 |
| 역할에 허용되지 않은 API | 아래 권한표 참조 |
| 학부모가 본인 자녀가 아닌 학생 데이터 조회 | 학부모A 키로 학생B 조회 |

## 3. 역할 × 기능 권한표

범례: ✅ 허용 / ❌ 403 차단. 모든 API는 추가로 테넌트 격리(URL academyId 일치)가 적용됨.

### 3.1 원장 (ACADEMY_OWNER)

| 기능 | 읽기 | 쓰기 |
|---|---|---|
| 전체 (회원·학생·반·출결·알림·공지·상담·학습·운영·관리) | ✅ | ✅ |

### 3.2 강사 (TEACHER) — 읽기 전체 + 쓰기는 담당 반 관련만

| 기능 | 읽기 | 쓰기 |
|---|---|---|
| 회원 목록·상세 조회 | ✅ | — |
| 강사 등록·삭제·인증키 재발급 (원장 전용) | ✅ | ❌ |
| 학생 등록 | ✅ | ✅ |
| 학생 삭제·인증키 재발급 (원장 전용) | ✅ | ❌ |
| 반 생성·수정·종료·학생 배정·이동 (원장 전용) | ✅ | ❌ |
| 출결 기록·키패드·정정 | ✅ | ✅ |
| 출결 설정 변경 (원장 전용) | ✅ | ❌ |
| 공지 작성·수정·삭제·읽음 처리 | ✅ | ✅ |
| 상담 개설·메시지·종료·읽음 처리 | ✅ | ✅ |
| 숙제·숙제 검사·진도·성적 | ✅ | ✅ |
| 알림 발송·재시도·설정 변경 (원장 전용) | ✅ | ❌ |
| 알림 읽음 처리 (본인 수신 확인) | ✅ | ✅ |
| 일정(시간표) 등록·수정·삭제 (원장 전용) | ✅ | ❌ |
| CSV 내보내기 | ✅ | — |
| CSV 학생 일괄 등록 (원장 전용) | — | ❌ |
| 학원 정보 조회 | ✅ | — |
| 학원 정보 변경·지점·권한 변경·감사 로그 (원장 전용) | ✅ (조회는 허용) | ❌ |

### 3.3 학부모 (PARENT) — 본인(자녀) 범위 읽기 + 최소 쓰기 화이트리스트

| 기능 | 결과 |
|---|---|
| 본인 자녀 정보 조회 (`GET /students/{자녀ID}`) | ✅ |
| 본인 자녀 출결 이력 (`GET /attendance/student/{자녀ID}`) | ✅ |
| 본인 자녀 진도·성적·리포트 (`GET /progress|grades|reports/student/{자녀ID}`) | ✅ |
| 학원 공지 목록·상세 조회 (`GET /notices`, `/notices/{id}`) | ✅ (X-Auth-Key가 학생 키이면 쿼리 파라미터 없이 자동 학부모 뷰 — NTC-04) |
| 본인 알림 조회 (`GET /notifications`, `/notifications/unread-count`, `/notifications/settings`) | ✅ (본인 범위로 강제) |
| 본인 상담 스레드 조회 (`GET /consultations`, `/consultations/{id}`) | ✅ (본인 범위로 강제) |
| 학부모 문의 등록 (`POST /consultations`, INQUIRY만) | ✅ (본인 명의로 강제, NEWS 위조 차단) |
| 공지 읽음 확인 (`POST /notices/{id}/read`) | ✅ (본인에 대해서만) |
| 키패드 출결 (`POST /attendance/keypad`) | ✅ (본인 출석키로만 기록) |
| 다른 학생 데이터 조회 | ❌ 403 |
| 자녀 외 모든 조회 (회원 목록, 반 목록, 전체 출결부, 발송 이력 등) | ❌ 403 |
| 그 외 모든 쓰기 (공지 작성·수정·삭제, 상담 메시지 작성·종료, 출결 직접 기록 등) | ❌ 403 |

### 3.4 공개 (인증 불필요)

| API | 설명 |
|---|---|
| `POST /api/rea/auth/key-login` | 인증키 로그인 (유일한 공개 API) |

## 4. 이중 방어 구조

1. **HTTP 계층** (신규, 1단계)
   - `AuthKeyFilter`: 인증키 검증 → 401
   - `ReaSecurityInterceptor`: URL `{academyId}` vs 인증 academyId 비교 → 403, 역할 매트릭스 적용 → 403
2. **Service 계층** (기존 유지)
   - 모든 Service 메서드가 `academyId` 파라미터로 스코핑, 타 학원 데이터 접근 시 400
   - HTTP를 우회하는 내부 호출에도 테넌트 격리가 유지됨

## 5. 운영 참고사항

- **키패드 출결** (보안 검토, 2026-10-13 — ATT-01 키오스크):
  `POST /attendance/keypad`는 body의 출석키(= 학생 인증키)로 등·하원을 판별한다.
  인증 없이 공개하는 방안도 검토했으나, 출석키가 10자리 영숫자(~52비트)라 공개 시
  키 추측으로 타 학생 출결을 위조 기록할 수 있어 **공개하지 않기로 결정**했다.
  대신 `X-Auth-Key` 헤더 인증을 유지하고(프론트 ATT-01은 학생 출석키를 헤더·본문에
  함께 전송), PARENT 역할에 본인 출석키로만 기록 가능한 쓰기 예외를 부여했다.
  `AttendanceService.keypadCheck`가 본문의 출석키와 헤더 인증 학생이 다르면 403으로 차단한다.
  키오스크 단말에 별도 강사 키를 보관할 필요가 없으며, academyId 스코프는 기존대로 유지된다.
- **인증키 재발급**: `POST /members/{id}/renew-key`, `POST /students/{id}/renew-key` (원장 전용).
  키 유출이 의심되면 즉시 재발급한다.
- **학부모 식별**: 학생 인증키 = 학부모 로그인 키. 1학생 1학부모 키 구조이며,
  학부모는 본인 자녀의 `{studentId}`가 포함된 API만 호출할 수 있다.

## 6. 테스트

- `backend/src/test/java/com/rea/academy/security/SecurityTest.java` — 35건
  - 401: 키 누락 / 위조 키 / 빈 헤더
  - 403: 타 학원 URL 접근·쓰기(학부모 포함, 테넌트 격리) / 학부모 타 학생 조회·미허용 쓰기(공지 수정·삭제, 알림 발송·이력, 상담 메시지·종료, 출결 직접 기록, NEWS 위조, 타 학생 출석키 키패드)·회원 목록 / 강사 원장 전용 API(강사 등록·학원 설정·학생 삭제)
  - 200: 원장 전체 / 강사 읽기+학생 등록 / 학부모 본인 자녀·공지·알림·상담 / 학부모 허용 쓰기(문의 등록·공지 읽음 확인·키패드 본인 출결) / key-login 공개·PARENT 역할
- 회귀: 기존 9개 테스트 클래스 164건 전부 통과 (서비스 레벨 테스트는 HTTP 계층과 무관하므로 수정 없음)
