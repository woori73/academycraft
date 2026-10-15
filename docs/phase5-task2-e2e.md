# REA 5단계 작업 2 — E2E 종단 검증 결과

- 일시: 2026-10-13
- 방식: `javac -encoding UTF-8 -parameters` 직접 컴파일 + 커스텀 JUnit 러너(TestRunner), H2 인메모리
- 테스트 코드: `backend/src/test/java/com/rea/academy/e2e/AcademyE2eTest.java` (신규)
- 전체 테스트: **257/257 PASS** (기존 253 + 신규 4)

## 테스트 설계

MockMvc(HTTP 레벨, Spring Security 필터 체인 포함). 각 단계마다 실제 HTTP 상태코드와
응답 본문 핵심을 assert.

부트스트랩 설계: 공개 회원가입 API가 없으므로 **첫 원장은 서비스 시딩**으로 생성
(운영에서는 플랫폼 관리자/배포 시딩 경로). 인증키 발급 직후의 모든 흐름은 HTTP로 검증.
X-Auth-Key는 발급 직후 레포지토리에서 읽어 발급 여부를 확인하고, 공개 `key-login` API로
로그인한다.

## 시나리오별 결과

### 시나리오 A: 학원 운영 전체 흐름 — ✅ PASS (12단계 전 단계 200)

| 단계 | 요청 | 응답 |
|---|---|---|
| A1 학원 생성 | `PUT /api/rea/1/info` (원장 키) `{"name":"한빛학원",...}` | 200, `"name":"한빛학원"` (신규 row 생성 — saveAcademyInfo upsert) |
| A2 원장 로그인 | `POST /api/rea/auth/key-login` (헤더 없음) `{"authKey":"<원장키>"}` | 200, `"role":"ACADEMY_OWNER"`, `"memberId":<id>` |
| A3 학생 3명 등록 | `POST /api/rea/1/students` ×3 (원장 키) | 200 ×3, 각 응답 `"authKey"` non-blank 발급 확인 |
| A4 강사 등록 | `POST /api/rea/1/members` `{"role":"TEACHER",...}` (원장 키) | 200, `"role":"TEACHER"` |
| A5 반 생성·배정 | `POST /api/rea/1/classes` → `POST /api/rea/1/classes/{id}/students` `{"studentIds":[..]}` (원장 키) | 200, `"studentCount":3` |
| A6 출결 기록 | `POST /api/rea/1/attendance/check` `CHECK_IN`×2, `ABSENT`×1 (강사 키) | 200 ×3; `GET /notifications/history`에 `"type":"CHECK_IN"` 기록 확인 (Mock 발송 — 외부 발송 없음, 로그 `[MOCK-알림]` 출력) |
| A7 공지 작성 | `POST /api/rea/1/notices` 전체공지 1건 + `{"classId":..}` 반공지 1건 (원장 키) | 200 ×2 |
| A8 학부모 공지 확인 | `key-login`(학생 키) → 200 `"role":"PARENT"`; `GET /notices` → 200, 전체+반공지 2건 모두 노출; `POST /notices/{id}/read` → 200; `GET /notices/{id}` → `"myRead":true` | 전 단계 200 |
| A9 상담 | 학부모 `POST /consultations` `INQUIRY` → 200 `"status":"OPEN"`; 원장 `POST /consultations/{id}/messages` → 200; 학부모 `GET /consultations/{id}` → 200, 답변 본문 포함 + `"status":"ANSWERED"` | 전 단계 200 |
| A10 숙제 | 강사 `POST /homework` → 200; `GET /homework/{id}/checks` → 200 `"[]"` (전원 미제출) | 200 |
| A11 성적·리포트 | 강사 `POST /grades` `{"score":85,...}` → 200; 학부모 `GET /reports/student/{id}` → 200 `"gradeSummary"`, `"gradeCount":1`, 평균 85 | 200 |
| A12 감사 로그 | `GET /api/rea/1/audit-logs` (원장 키) → 200 | `ACADEMY_INFO_UPDATED`, `STUDENT_CREATED`, `CLASS_CREATED`, `NOTICE_CREATED` 전부 기록됨 |

### 시나리오 B: 권한 검증 — ✅ PASS (4건)

| # | 요청 | 기대 | 실제 |
|---|---|---|---|
| B1 | 학부모가 다른 학생 조회 `GET /students/{타학생ID}` | 403 | 403 ✅ |
| B2 | 강사가 학원 설정 변경 `PUT /info` | 403 | 403 ✅ |
| B3 | 인증키 없이 `GET /students` | 401 | 401 ✅ |
| B4 | 학부모가 공지 작성 `POST /notices` | 403 | 403 ✅ |

### 시나리오 C: 학원 격리 검증 — ✅ PASS (6건)

학원A(원장A·학생A), 학원B(원장B·학생B) 구성 후 교차 접근:

| # | 요청 | 결과 |
|---|---|---|
| C2 | A키로 `GET /api/rea/2/students` / `GET /api/rea/2/students/{학생B}` / `POST /api/rea/2/notices` | 전부 403 ✅ |
| C3 | B키로 `GET /api/rea/1/students` / `GET /api/rea/1/students/{학생A}` | 전부 403 ✅ |
| 대조 | 본인 학원 `GET /students` | A·B 모두 200 ✅ |

### 시나리오 D: 권한 매트릭스 회귀 — ✅ PASS (9건)

task2 과정에서 발견한 버그(I1~I4)의 회귀 방지:

| # | 요청 | 기대 | 실제 |
|---|---|---|---|
| D1 | 강사 `POST /homework` | 200 | 200 ✅ |
| D2 | 강사 `POST /grades` | 200 | 200 ✅ |
| D3 | 강사 `POST /progress` | 200 | 200 ✅ |
| D4 | 강사 `POST /consultations/{id}/messages` | 200 | 200 ✅ |
| D5 | 강사 `PUT /attendance/settings` (원장 전용) | 403 | 403 ✅ |
| D6~8 | 학부모 `GET /progress|grades|reports/student/{자녀ID}` | 200 | 200 ✅ |

## 발견된 이슈와 수정 내용

### I1. 강사 학습 쓰기 전부 403 — ReaAccessRules 경로 패턴 오기재 (수정 완료)

- 원인: `TEACHER_WRITES` 화이트리스트가 `/api/rea/*/learning/homework`,
  `/learning/homework/*/check`, `/learning/progress`, `/learning/grades` 로 되어 있었으나,
  실제 컨트롤러 경로(LearningController)는 `/api/rea/{academyId}/homework` 등
  `/learning` 세그먼트가 없음. 프론트엔드(HW-01/HW-02/RPT-01)도 세그먼트 없는 경로 사용.
- 결과: 강사의 숙제 등록·검사·진도·성적 쓰기가 모두 403 (docs/security.md 3.2 권한표와 상충).
- 수정: `ReaAccessRules.java` — 4개 패턴에서 `/learning` 세그먼트 제거.
- 검증: 시나리오 A10/A11(강사 숙제·성적 200), D1~D3.

### I2. 학부모 본인 자녀 진도·성적·리포트 조회 전부 403 — 동일 원인 (수정 완료)

- 원인: `PARENT_OWN_STUDENT_READS`가 `/api/rea/*/learning/{progress,grades,reports}/student/{id}`
  로 되어 있었으나 실제 경로는 `/api/rea/{id}/{progress,grades,reports}/student/{id}`.
- 결과: 학부모가 본인 자녀 리포트 조회 시 403 (docs/security.md 3.3 권한표 "✅"와 상충).
- 수정: `ReaAccessRules.java` — 3개 패턴에서 `/learning` 세그먼트 제거.
  `docs/security.md`의 권한표 경로 표기도 실제 경로로 수정.
- 검증: 시나리오 A11(학부모 리포트 200), D6~D8.

### I3. 강사 상담 메시지·종료·읽음처리 전부 403 — 단일 `*` 패턴 한계 (수정 완료)

- 원인: 화이트리스트 `POST /api/rea/*/consultations/*` — AntPathMatcher의 `*`는
  단일 세그먼트만 매칭하므로 `/consultations/{id}/messages`,
  `/{id}/close`, `/{id}/messages/{messageId}/read` (다중 세그먼트)를 매칭하지 못함.
- 결과: 강사의 상담 답변·종료·읽음처리가 403 (docs/security.md 3.2 "상담 개설·메시지·종료·읽음 처리 ✅"와 상충).
- 수정: 패턴을 `POST /api/rea/*/consultations/**` 로 변경.
- 검증: 시나리오 D4. (학부모의 상담 메시지 작성 차단은 그대로 유지 — SecurityTest 통과)

### I4. 강사가 출결 설정 변경 가능 — 화이트리스트 과다 매칭 (수정 완료)

- 원인: `PUT /api/rea/*/attendance/*` 가 출결 정정(`/attendance/{eventId}`)뿐 아니라
  출결 설정 변경(`/attendance/settings`)까지 매칭.
- 결과: 강사가 원장 전용인 출결 설정 변경 가능 (docs/security.md 3.2 "출결 설정 변경 (원장 전용) ❌"와 상충).
- 수정: `checkTeacher()`에서 `PUT /api/rea/*/attendance/settings`를 명시적으로 먼저 차단.
- 검증: 시나리오 D5 (403).

### I5. 첫 원장 생성 경로 부재 — 설계 메모 (수정 없음)

- 공개 회원가입 API가 없어 HTTP만으로는 첫 원장·학원 부트스트랩이 불가.
  E2E에서는 서비스 시딩으로 첫 원장을 생성하고(운영에서는 플랫폼 관리자/배포 시딩 경로),
  학원 생성(PUT /info upsert)부터는 HTTP로 검증. 코드 변경 없음.

## 빌드/테스트 이력

1. E2E 테스트 초안 작성 → 실행: 시나리오 A·D에서 403 2건 발생.
   원인 분석 중 "ReaAccessRules 수정 후 main 클래스 미재컴파일"을 발견 —
   `javac`로 main 전체 재컴파일 후 재실행.
2. 재실행 결과 4/4 PASS → 위 I1~I4 수정 반영 후 최종.
3. 전체 스위트: **257/257 PASS** (기존 253 + 신규 4). 회귀 없음.

## 경계 준수

- wemade-reorg 미수정, EASY 미접촉, 운영 DB 미사용(H2 인메모리), 실제 외부 발송 없음(MockNotificationSender),
  프론트엔드 미수정.
- 코드 수정 범위: `ReaAccessRules.java`(권한 패턴 3곳), `docs/security.md`(경로 표기 1행),
  신규 테스트 1파일 + `.testbuild/test-sources.txt` 1행.
