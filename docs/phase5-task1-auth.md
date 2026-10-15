# REA 5단계 작업 1 — 33개 화면 인증 연결 (X-Auth-Key)

> 작업일: 2026-10-13
> 범위: 프론트엔드 인증 배선만. 백엔드·CHAT-01·wemade-reorg·EASY·운영DB 미접촉.

## 1. 산출물

| 파일 | 구분 | 설명 |
|---|---|---|
| `frontend/components/api-client.js` | 신규 | 공통 API 클라이언트. `apiGet/apiPost/apiPut/apiDelete` (+`apiPostText` CSV용) 제공 |
| `frontend/screens/MBR-07-login.html` | 신규 | 인증키 로그인 화면 (401 리다이렉트 대상. 기존에 존재하지 않았음) |
| `frontend/screens/` 33개 html | 수정 | CHAT-01-ai-chat.html 제외 전 파일. 직접 `fetch(` → api-client 경유로 교체 |

※ 과제 지시문에는 "32개 화면"으로 기재되어 있으나, 실제 `screens/` 디렉토리에는
CHAT-01을 제외하고 33개 html이 존재하므로 33개 전부를 처리함.

## 2. api-client.js 설계

- `localStorage 'rea_auth_key'`에서 인증키를 읽어 모든 요청에 `X-Auth-Key` 헤더 자동 추가
- `window.REA_API_BASE` (기본값 `'/api/rea'`, 요청 시점 평가): 화면에서
  `var REA_API_BASE = '/api/rea/1';` 형태로 주입. 기존 `const API = '/api/rea/1'` 대체
- `window.REA_LOGIN_PAGE` (기본값 `'MBR-07-login.html'`): 401 시 이동 대상
- 401 → 저장 키 삭제(호출자가 직접 전달한 키는 제외) + 로그인 화면 리다이렉트(루프 방지)
- 403 → `alert('권한이 없습니다')` 후 예외 throw
- 학부모 화면용: 입력받은 학생 인증키를 마지막 인자 `keyOverride`로 전달하면
  localStorage에 저장하지 않고 해당 요청의 헤더로만 사용. 예) `apiGet('/1/notifications', key)`
- 인증키는 URL 파라미터에 절대 포함하지 않음. `console.log`에 키 출력 없음.
  XSS 주의 주석 포함 (localStorage 평문 저장, innerHTML 삽입 시 escapeHtml 필수)
- `POST /api/rea/auth/key-login`은 공개 API이므로 api-client 경유 금지
  (빈 X-Auth-Key 헤더를 보내면 key-login 자체가 401로 거부됨) → 로그인 화면에서 순수 fetch 사용

## 3. 화면별 적용 내역 (33개)

공통: `<script src="../components/api-client.js"></script>` 로드 태그 추가,
`const API = '/api/rea/1'` → `var REA_API_BASE = '/api/rea/1'` 교체.

| 화면 | fetch 수 | 특이사항 |
|---|---|---|
| ADM-01-academy-info.html | 4 | `/${id}/...` 템플릿 유지 (REA_API_BASE 기본값 `/api/rea` 사용) |
| ADM-02-user-roles.html | 2 | 동상 |
| ADM-03-audit-log.html | 1 | 동상 |
| ATT-01-attendance-keypad.html | 1 | 학생 출석키 → 헤더로 전송. body `{authKey}`는 백엔드 식별용 유지 |
| ATT-02-attendance-monthly.html | 2 | 표준 변환 |
| ATT-03-attendance-roster.html | 4 | 표준 변환 (GET×2, POST, PUT) |
| ATT-04-attendance-settings.html | 2 | 표준 변환 |
| CLS-01-class-list.html | 1 | 표준 변환 |
| CLS-02-class-form.html | 3 | 저장: `method` 변수 분기 → `apiPut/apiPost` 조건 분기로 교체 |
| CLS-03-class-detail.html | 7 | 표준 변환 (body 없는 POST `/close` 포함) |
| CNS-01-consultation-list.html | 1 | `?authKey=` 제거 → 헤더 전송. 상세 링크의 `&authKey=` 제거 |
| CNS-02-consultation-form.html | 1 | 학생키 → 헤더. body `{authKey,…}` 유지 |
| CNS-03-inquiry-inbox.html | 3 | 표준 변환 |
| CNS-04-news-send.html | 2 | 표준 변환 (멀티라인 body 처리) |
| CNS-05-consultation-detail.html | 4 | 학부모: 학생키 헤더 전송. 원장·강사: localStorage 키 사용. 구버전 `?authKey=` 링크 읽기만 유지(하위호환) |
| GRD-01-grade-form.html | 2 | 표준 변환 |
| GRD-02-grade-list.html | 2 | 표준 변환 |
| HW-01-homework-form.html | 2 | `Promise.all` 내 fetch → `apiGet` |
| HW-02-homework-check.html | 3 | 동상 |
| HW-03-homework-list.html | 2 | 표준 변환 |
| MBR-01-student-list.html | 3 | `url` 변수 경로-only 로 변경 후 `apiGet(url)` |
| NTC-01-notice-list.html | 1 | `?authKey=` 제거 → 헤더 전송. 상세 링크의 `&authKey=` 제거 |
| NTC-02-class-news.html | 2 | 동상 |
| NTC-03-notice-form.html | 2 | 표준 변환 |
| NTC-04-notice-detail.html | 4 | `?authKey=` 제거 → 헤더 전송. 읽음확인 body `{authKey}` 유지 |
| NTF-01-notification-list.html | 5 | `?authKey=` 3곳 제거 → 헤더 전송. body `{authKey}` 유지 |
| NTF-02-message-send.html | 3 | 표준 변환 |
| NTF-03-send-history.html | 2 | body 없는 POST `/retry` → `apiPost(path)` |
| OPS-01-schedule-calendar.html | 5 | 저장: `id ? PUT : POST` → 조건 분기. DELETE 표준 변환 |
| OPS-02-dashboard.html | 1 | 표준 변환 |
| OPS-03-excel-io.html | 2+2 | CSV import → `apiPostText`. **CSV 다운로드 2곳은 `<a href>` 직접 이동에서 `apiGet`+Blob 방식으로 변경** (헤더 인증 필요, 기존 코드는 `API` 상수 삭제로 ReferenceError 발생하던 것을 수정) |
| RPT-01-learning-report.html | 2 | 표준 변환 |
| timetable-weekly.html | 1 | 표준 변환 |
| MBR-07-login.html | 1(raw) | 신규. key-login만 순수 fetch (공개 API). 성공 시 `rea_auth_key` 저장 |

총 직접 `fetch(` 호출 82곳 → 전부 api-client 경유 (key-login 1곳 제외).

## 4. 보안 점검 결과

| 점검 항목 | 결과 |
|---|---|
| URL에 `?authKey=` / `?key=` / `&authKey=` 파라미터 | **없음** (CNS-05 내 주석 1건만 언급, 코드 아님) |
| `console.log`에 인증키(authKey/auth_key/X-Auth-Key) 출력 | **없음** (0건) |
| localStorage 키 이름 일관성 | `rea_auth_key`로 통일. 단, CHAT-01은 기존 `rea-chat-auth-key` 사용 — 수정 금지 지시로 통합 불가 (알려진 불일치) |
| 인증키의 body 전송 | 학부모 식별용 6곳 유지 (NTF-01 읽음/설정, NTC-04 읽음확인, CNS-02 문의, ATT-01 키패드, CNS-05). 백엔드 DTO가 `authKey`를 요구하므로 URL이 아닌 body로만 전송 |
| 페이지 간 링크로 키 전달 | CNS-01→CNS-05, NTC-01/NTC-02→NTC-04 링크의 `&authKey=` 제거. 대상 화면에서 재입력 |

## 5. 회귀 테스트 결과

- `node --check`: 34개 화면 인라인 스크립트 + `api-client.js` = 36개 스크립트 **전부 PASS**
- 변환 검증: `grep`으로 `fetch(` 잔여 확인 — 의도된 2곳만 잔류
  (MBR-07-login의 key-login 공개 API 호출, api-client.js 내부 구현)
- 샘플 검증: 파일별 `apiGet/apiPost/apiPut/apiDelete` 호출 목록 전수 대조 —
  82곳 모두 경로(`/1/...` 또는 `/<id>/...`) + body 형태로 정상 변환됨
- 특수 케이스: CLS-02/OPS-01 조건부 PUT/POST, HW-01/HW-02 Promise.all,
  OPS-03 CSV import/export, ADM `${id}` 템플릿 — 개별 확인 완료

## 6. 알려진 제한사항 (후속 과제 후보 — 백엔드 범위)

1. **학부모 역할의 알림/상담/키패드 API 403**: 백엔드 `ReaAccessRules`에서 PARENT는
   `GET /notifications` 계열·`GET /consultations` 읽기가 허용 목록에 없고,
   모든 쓰기(읽음확인·문의등록·키패드)가 403이다. 프론트는 규격대로 헤더를 보내나
   백엔드 역할 매트릭스 미허용으로 `권한이 없습니다` alert가 뜬다.
   → 백엔드 1단계 설계 확인 후 역할 매트릭스 보완 필요 (프론트에서 해결 불가).
2. **NTC-04 공지 상세의 학부모 식별**: 백엔드 `NoticeService.detail`이
   `studentId/authKey` 파라미터로 학부모 뷰를 판정하므로, 헤더-only 전환 후에는
   별도 식별 파라미터 없이는 관리자 뷰(조회수 표시)로 동작할 수 있다.
3. **localStorage 키 이중화**: CHAT-01(`rea-chat-auth-key`) vs api-client(`rea_auth_key`).
   CHAT-01 수정 금지로 통합하지 못함. 로그인 화면에서 저장한 키는 CHAT-01에서 읽지 못함.

## 7. 준수 사항 확인

- `~/workspace/wemade-reorg/` 미접촉 (읽기도 하지 않음)
- EASY 코드 미접촉, 운영 DB 미사용
- CHAT-01-ai-chat.html 무수정 (확인: `git` 미사용 환경이나 파일 mtime·내용 대조로 미변경 확인)
- 백엔드 코드 무수정
