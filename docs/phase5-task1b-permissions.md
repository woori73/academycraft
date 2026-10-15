# REA 5단계 작업 1b — PARENT 역할 권한 매트릭스 보완

> 작업일: 2026-10-13
> 범위: 백엔드 권한 매트릭스 + 본인-스코프 강제 + 보안 테스트. 프론트엔드·wemade-reorg·EASY·운영DB 미접촉.
> 배경: 프론트 인증 연결(phase5-task1-auth) 작업에서 발견 — PARENT 허용 목록 부족으로
> 정상 학부모 흐름(알림 조회·상담 목록·문의 등록·공지 읽음 확인·키패드 출결)이 403으로 막힘.

## 1. 산출물 (변경 파일)

| 파일 | 구분 | 설명 |
|---|---|---|
| `backend/.../security/ReaAccessRules.java` | 수정 | PARENT 읽기 목록 추가(알림·상담 계열) + PARENT 쓰기 화이트리스트 신설 |
| `backend/.../security/ParentScope.java` | 신규 | 학부모 본인-스코프 강제 헬퍼 (`forceOwnStudent` / `ownStudentIdOrNull`) |
| `backend/.../service/NoticeService.java` | 수정 | list·detail·markRead에 본인 강제 적용 (NTC-04 자동 학부모 뷰) |
| `backend/.../service/ConsultationService.java` | 수정 | create·list·detail에 본인 강제 적용 + 학부모 NEWS 위조 차단 |
| `backend/.../service/NotificationService.java` | 수정 | getNotifications·countUnread·getSettings에 본인 강제 적용 |
| `backend/.../service/AttendanceService.java` | 수정 | keypadCheck: 본문 출석키 ≠ 헤더 인증 학생이면 403 |
| `backend/.../security/SecurityTest.java` | 수정 | PARENT 테스트 17건 추가 (18→35건) |
| `docs/security.md` | 수정 | §3.3 학부모 권한표 갱신, §5 키패드 보안 검토 기록 |
| `.testbuild/main-sources.txt` | 수정 | ParentScope.java 컴파일 목록 추가 |

## 2. PARENT 허용 매트릭스 (최종)

**읽기** (본인/자녀 범위 한정):
- 기존: 본인 자녀 정보·출결·진도·성적·리포트, 학원 공지 목록·상세
- 추가: `GET /notifications`, `/notifications/unread-count`, `/notifications/settings`
  (발송·발송이력·재시도 제외), `GET /consultations`, `/consultations/{id}`

**쓰기** (화이트리스트만, 나머지 403 유지):
- `POST /consultations` — 학부모 문의 등록 (INQUIRY만, 본인 명의로 강제)
- `POST /notices/{id}/read` — 읽음 확인 (본인에 대해서만)
- `POST /attendance/keypad` — 키오스크 출결 (본인 출석키로만)

## 3. 본인-스코프 강제 (`ParentScope`)

- 학부모는 학생 인증키로 로그인하므로 백엔드가 `AuthContext`에서 본인(자녀) studentId를 역으로 안다.
- Service 진입 시 `ParentScope.forceOwnStudent(studentId, authKey)`로 쿼리/본문 입력을
  본인 것으로 치환 → 타 학생 ID·키 위조가 원천 차단된다.
- 원장·강사(MEMBER) 인증에는 아무 것도 하지 않음 (기존 파라미터 방식 하위 호환).
- 학부모가 `POST /consultations`에 `type=NEWS`를 담으면 403
  (학원 발신 아이소식의 작성자 위조 방지 — INQUIRY만 허용).

## 4. NTC-04 학부모 뷰 판정 개선

- 공지 상세(`GET /notices/{id}`)·목록: `X-Auth-Key`가 학생 키이면 쿼리 파라미터 없이
  자동으로 학부모 뷰로 동작 (조회수 미표시, `myRead` 포함 → 읽음확인 버튼 표시).
- 기존 `?studentId=` / `?authKey=` 쿼리 파라미터 방식은 원장·강사 미리보기용으로 하위 호환 유지.
- 부수 효과: 학부모가 파라미터 없이 `GET /notices`를 호출하면 기존에는
  관리자 뷰(전체 공지 + 조회수)가 반환됐으나, 이제는 전체공지 + 자녀 소속 반 공지만 반환된다.

## 5. 키패드(ATT-01) 보안 검토 — 공개하지 않기로 결정

- 검토안: 키오스크 모드이므로 `POST /attendance/keypad`를 인증 없이 공개하되 academyId 스코프 유지.
- 결정: **공개하지 않음.**
  - 이유 1: 출석키는 10자리 영숫자(~52비트). 공개 엔드포인트에서는 키 추측으로
    타 학생 출결을 위조 기록할 수 있고, 서버 측 rate limiting이 없다.
  - 이유 2: 프론트 ATT-01은 이미 학생 출석키를 `X-Auth-Key` 헤더 + 본문에 함께 전송한다
    (phase5-task1-auth). 헤더 인증을 유지해도 키오스크 UX에 지장이 없다.
  - 이유 3: 헤더 인증(본인) + 본문 출석키의 이중 바인딩이 공개 방식보다 엄격하다.
- 적용: PARENT 쓰기 화이트리스트에 `POST /attendance/keypad`를 추가하되,
  `AttendanceService.keypadCheck`에서 본문 출석키의 학생 ≠ 헤더 인증 학생이면 403.
  키오스크 단말에 별도 강사 키를 보관할 필요가 없어졌다 (docs/security.md §5 갱신).

## 6. 테스트 결과

- 방식: `javac` 직접 컴파일(`-encoding UTF-8 -parameters`) + 커스텀 JUnit 러너 (H2 인메모리)
- `SecurityTest`: 35/35 PASS (기존 18 + 신규 17)
  - 허용 200: 알림 목록·미읽음·설정 조회 / 상담 목록·상세·문의 등록 / 공지 상세 자동 학부모 뷰(myRead) /
    읽음 확인 / 읽음 확인 후 myRead=true / 키패드 본인 키 / 타 학생 ID 문의 등록 시 본인 강제
  - 차단 403: 공지 수정·삭제 / 알림 발송·발송이력 / 상담 메시지 작성·스레드 종료 /
    출결 직접 기록 / NEWS 위조 등록 / 타 학생 출석키 키패드 / 타 학원 URL 접근·쓰기(테넌트 격리 유지)
  - 기타: 타 학생 상담 스레드 상세 → 400 (조회 불가)
- 전체 회귀: **253/253 PASS** (기준선 236/236 + 신규 17, 실패 0)

## 7. 남은 고려사항 (후속 판단 필요)

- 학부모 상담 답글(`POST /consultations/{id}/messages`)은 현재 403 유지.
  비투 핵심 흐름상 학부모가 문의 스레드에 추가 답글을 달아야 한다면 별도 승인 후
  본인-스레프 범위로 허용 범위를 넓혀야 한다.
- 알림 읽음 처리(`POST /notifications/{id}/read`)도 학부모에게 403 유지 (읽기 전용 원칙).
  학부모 앱에서 알림 읽음 표시가 필요하면 같은 방식으로 본인 범위 허용을 검토할 것.
