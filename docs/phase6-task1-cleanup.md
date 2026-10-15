# REA 6단계 작업 1 — 남은 문제 정리 리포트

> 작성일: 2026-10-13
> 범위: 5단계 종료 시점의 남은 문제 3건(CHAT-01 키 불일치, 학부모 답글 403, 대량 데이터·모바일 재검증) + 검증 중 추가 발견 1건
> 경계: H2 인메모리만 사용, 운영 DB·배포·main 병합 없음, 실제 개인정보 없음(가명 더미), 외부 발송 없음(Mock 유지), 유료 API/LLM 없음

## 0. 요약

| 항목 | 결과 |
|---|---|
| 1-1 CHAT-01 localStorage 키 통일 | 수정 완료 (`rea-chat-auth-key` → `rea_auth_key`), 회귀 PASS |
| 1-2 학부모 상담 답글·읽음 처리 403 | 코드 변경 없음. 테스트 3건 추가로 **의도적 정책** 확인 (403 정상 반환) |
| 1-3 대량 데이터 검증 (500명/10,000건/1,500건) | 백엔드 집계 정확성 PASS. **신규 발견 2건**: ① 31개 화면 API 경로 중복 접두사(`/api/rea/1/1/...` → 404) — 수정 완료 ② 월별 출결 N+1 (51초) — 백엔드 최적화 검토 대상으로 보고 |
| 1-4 모바일 8건 재검증 | 25개 화면 × 3개 뷰포트 = 75회 렌더링 **전체 PASS**, 8건 수정 모두 유지 |
| 전체 테스트 | **292/292 PASS** (기존 284 + 신규 8: SecurityTest 3, BulkDataVerificationTest 5) |

## 1. 1-1 — CHAT-01 localStorage 키 불일치 수정

- 파일: `frontend/screens/CHAT-01-ai-chat.html` 171행
- 변경: `const LS_KEY = 'rea-chat-auth-key';` → `const LS_KEY = 'rea_auth_key';`
- 전수 grep 결과: 해당 파일 내 `rea-chat-auth-key` 참조는 171행 1곳뿐(저장 599행·로드 608행은 `LS_KEY` 변수 경유). `frontend/` 코드 내 잔여 참조 0건 (docs 내 역사적 언급만 남음).
- MBR-07-login.html · `components/api-client.js`는 이미 `rea_auth_key` 사용 — 수정 불필요.

### 회귀 테스트 (Playwright headless Chromium 1243, 스텁 API 서버)
1. 인증키 입력 → 저장 버튼 클릭 → `localStorage['rea_auth_key'] === 'TEST-KEY-123'` ✅
2. 구 키 `rea-chat-auth-key` 미생성 ✅
3. 페이지 리로드 후 입력칸에 인증키 자동 복원 ✅
4. 명령 전송 시 `POST /api/rea/1/agent/execute` 요청에 `X-Auth-Key: TEST-KEY-123` 헤더 실전송 확인 ✅ (서버 수신 로그 대조)
5. 인라인 JS `node --check` 통과 ✅
- **결과: PASS** — 이제 MBR-07 로그인에서 저장한 키를 CHAT-01이 그대로 읽는다.

## 2. 1-2 — 학부모 상담 답글·알림 읽음 처리 403 정책 확인 (코드 변경 없음)

`security/ReaAccessRules.java` 학부모 쓰기 화이트리스트(`PARENT_WRITES`)는
`POST /consultations`(문의 등록), `POST /notices/*/read`(공지 읽음 확인),
`POST /attendance/keypad`(키오스크 출결) 3종만 허용. 따라서 아래는 **의도적으로 403**:

| 엔드포인트 | 학부모 호출 시 |
|---|---|
| `POST /api/rea/{a}/consultations/{id}/messages` (상담 답글) | 403 |
| `POST /api/rea/{a}/consultations/{id}/messages/{messageId}/read` (상담 메시지 읽음 처리) | 403 |
| `POST /api/rea/{a}/notifications/{id}/read` (알림 읽음 처리) | 403 |

### 추가 테스트 (`SecurityTest`에 3건 추가)
- `parentConsultationReply_returns403`
- `parentConsultationMessageRead_returns403`
- `parentNotificationMarkRead_returns403`
- **결과: 3건 모두 403 확인 PASS** (`SecurityTest` 전체 38/38 PASS)
- 정책 근거: 학부모는 문의 등록(INQUIRY)까지만 허용하고, 학원↔학부모 대화 스레드의 답글·읽음 처리는 원장·강사 전용으로 유지 (비투 운영 흐름상 학원이 응대 주체).

## 3. 1-3 — 대량 데이터 화면 검증 (더미 데이터만)

### 3.1 데이터 규모 (H2 인메모리, 전부 가명)
- 학생 500명: `학생001`~`학생500` (실명·연락처 없음)
- 출결 10,000건: 500명 × 2026-10-01~20, 등원(CHECK_IN)
- 성적 1,500건: 300명 × 5과목(국어/영어/수학/과학/사회), `2026-10 중간고사`
- 테스트: `backend/src/test/java/com/rea/academy/perf/BulkDataVerificationTest.java` (5건, `.testbuild/test-sources.txt` 등록)

### 3.2 백엔드 API 측정 (MockMvc, 목표 3초 이내)

| API | 결과 |
|---|---|
| `GET /students` (500건) | **43ms** PASS — 500건 전부 반환, 첫/끝 이름 정합 |
| `GET /students?name=학생1` | **100건** PASS — `Containing` 검색으로 학생100~199 매칭 (0패딩 이름 기준) |
| `GET /attendance/monthly?classId=&yearMonth=2026-10` | 집계 **정확성 PASS** (500명 전원 presentDays=20, absentDays=0, 일별 상태 1~20일 IN·21~31일 NONE, 총합 10,000) / 응답 시간 **51,289ms — 3초 목표 미달** |
| `GET /grades/student/{id}` (5건) | **383ms** PASS |

### 3.3 프론트엔드 렌더링 측정 (Playwright + 스텁 API, 390px)
| 화면 | 측정 | 결과 |
|---|---|---|
| MBR-01 학생 500명 목록 | 카드 500개 렌더 완료까지 | **217ms** PASS |
| MBR-01 "학생1" 검색 | 카드 100개 렌더 완료까지 | **140ms** PASS |
| ATT-02 월별 (500명×31일 표, 18,000셀) | 테이블 501행 렌더 완료까지 | **117ms** PASS |
| GRD-02 성적 5건 | 행 렌더 완료까지 | **46ms** PASS |
- 렌더링 깨짐 없음 (가로 스크롤·잘림·겹침 0건).

### 3.4 발견 이슈 ① — 31개 화면 API 경로 중복 접두사 (수정 완료, 중요)
- 현상: `var REA_API_BASE = '/api/rea/1'` + `apiGet('/1/students')` → 최종 URL `/api/rea/1/1/students` → **실 백엔드에서 404**.
- 범위: `REA_API_BASE`를 설정한 31개 화면 전부 (ATT×4, CLS×3, CNS×5, GRD×2, HW×3, MBR-01, NTC×4, NTF×3, OPS×3, RPT-01, timetable-weekly). ADM-01/02/03(미설정, `/${id}/` 템플릿)·CHAT-01(자체 `API_BASE='/api/rea'`)·MBR-07(로그인 전용)은 정상.
- 원인: 5단계 작업 1에서 `const API='/api/rea/1'` → `var REA_API_BASE='/api/rea/1'`로 교체하면서, 호출부 경로의 `/1/` 접두사와 중복. 화면 인증 검증이 코드 수준에 그쳐 실 HTTP 검증을 놓침.
- 수정: 31개 파일의 `var REA_API_BASE = '/api/rea/1';` → `var REA_API_BASE = '/api/rea';` (academyId=1은 각 호출 경로의 `/1/`이 담당). 변수 경로(`url`/`path`) 사용 화면 6곳도 전부 `/1/` 시작 확인.
- 검증: 스텁 서버 수신 로그에서 `/api/rea/1/students` 정상 호출 확인, 4개 화면 렌더링 PASS(§3.3).
- 참고: api-client.js의 `apiBase() + path` 단순 결합 구조상, 향후 학원ID 가변화가 필요하면 호출부 경로를 `/${academyId}/...` 템플릿으로 일원화할 것.

### 3.5 발견 이슈 ② — 월별 출결 N+1 (백엔드, 수정 보류 → 검토 후 보고)
- 현상: `AttendanceService.getMonthly` → `toMonthlyStudent`가 학생 1명당 31일 각각 `findByAcademyIdAndStudentIdAndOccurredAtBetween` 호출 = **500명 × 31일 = 15,500 쿼리**, 51초.
- 집계 정확성에는 문제없음(§3.2 PASS). 프론트 렌더링도 117ms로 정상 — 병목은 순수 백엔드 쿼리 수.
- 조치: 6-1 지시("백엔드 변경은 추가 검토 후 보고")에 따라 코드 수정 없이 `monthly_responseTimeMeasured` 테스트는 측정·기록용으로 유지. 권장 최적화: 월 범위 1회 조회 후 메모리 그룹핑.
- 참고: 실제 반 규모(30~100명)에서도 100명 기준 약 10초로 3초 목표 미달.

### 3.6 페이징 관련
- MBR-01은 서버·클라이언트 페이징 없음(500건 일괄 렌더, 217ms로 문제없음). 수천 건 이상 규모가 되면 프론트 가상 스크롤 또는 백엔드 페이징 API 도입 검토.

## 4. 1-4 — 5단계 모바일 이슈 8건 재검증

- 방법: 5단계와 동일 (Playwright headless Chromium 1243, 360/390/430px, 25개 화면 × 3 = 75회 렌더링).
  항목: 가로 스크롤(`scrollWidth` vs 뷰포트), 터치 영역 44px(`button/.btn/input/select/textarea/a` 실측, 체크박스·라디오 박스 제외 — 의도된 20px 설계), 고정 요소 가림.
- **결과: 75회 전체 PASS, 이슈 0건.** 8건 수정이 모두 유지됨을 구조(grep) + 실측으로 재확인:

| # | 화면 | 수정 유지 확인 |
|---|---|---|
| 1 | 전체 25개 | `@media (max-width:480px)` 공통 패치(`button,.btn`/`input/select/textarea` 44px, `font-size:16px`, 체크박스 20px+`accent-color`) — 25/25 유지 |
| 2 | MBR-01 | `.search{flex-wrap:wrap}` 유지 |
| 3 | CLS-01 | `.nav a{min-height:44px; inline-flex}` 유지 |
| 4 | CLS-02 | `.days label` 칩 패딩 유지 |
| 5 | CNS-05 | `.role-pick label` 패딩 + `min-width:0; flex:none` 유지 |
| 6 | ATT-04 | `<label for="f-photo">` 연결 유지 |
| 7 | CHAT-01 | `table.data{display:block;overflow-x:auto}`, `#authKey{flex:1;min-width:0}`, `.approve` 패딩 유지 |
| 8 | 공통 | 체크박스·라디오 20px + 라벨 히트 영역 ≥44px 의도 설계 유지 |

- CHAT-01의 1-1 키 변경은 JS 1행 수정으로 CSS·레이아웃에 영향 없음 (모바일 재측정에서 CHAT-01 3개 뷰포트 모두 PASS).

## 5. 테스트 현황

- **292/292 PASS** (H2 인메모리, `javac` 직접 컴파일 + 커스텀 JUnit 러너)
  - 기존 284 → SecurityTest +3 (1-2 정책 확인), BulkDataVerificationTest +5 (1-3 대량 검증)
  - CHAT-01 회귀·모바일·렌더링은 Playwright 스크립트(`/tmp/rea6/`)로 검증 후 본 문서에 기록 (JUnit 스위트 제외)
- 컴파일: `javac -encoding UTF-8 -parameters` (기존 규칙 유지)

## 6. 수정 파일 목록

- `frontend/screens/CHAT-01-ai-chat.html` — LS_KEY 통일 (1행)
- `frontend/screens/` 31개 — `REA_API_BASE` 중복 접두사 수정 (각 1행)
  - ATT-01/02/03/04, CLS-01/02/03, CNS-01/02/03/04/05, GRD-01/02, HW-01/02/03, MBR-01, NTC-01/02/03/04, NTF-01/02/03, OPS-01/02/03, RPT-01, timetable-weekly
- `backend/src/test/java/com/rea/academy/security/SecurityTest.java` — 403 정책 확인 테스트 3건 추가
- `backend/src/test/java/com/rea/academy/perf/BulkDataVerificationTest.java` — 신규 (5건)
- `.testbuild/test-sources.txt` — 신규 테스트 등록
- `docs/phase6-task1-cleanup.md` — 본 문서 (신규)

## 7. 남은 항목 (후속 검토 필요)

1. **월별 출결 N+1 최적화** (§3.5) — 백엔드 변경이므로 별도 승인 후 진행. 승인 시 `getMonthly`를 월 범위 일괄 조회로 변경하고 본 문서의 측정 테스트를 3초 assertion으로 복원.
2. **실데이터 화면 육안 재확인** — 5단계 남은 문제 중 본 작업에서 다루지 않은 항목. REA_API_BASE 수정(§3.4)으로 31개 화면의 실제 API 연동이 처음 정상화되므로, 스테이징/개발 백엔드 대상 육안 확인 권장.
3. docs 내 `rea-chat-auth-key` 역사적 언급(FINAL-REPORT.md, chat-ui.md, phase5-task1-auth.md, phase5-task4-mobile.md, phase5-task5-docs.md)은 변경 이력으로 유지.
