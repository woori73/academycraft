# REA 학원관리 2단계 — AI Agent 실행 기반

> 위치: `backend/src/main/java/com/rea/academy/agent/`
> 원칙: 유료 API·LLM 연동 금지 (키워드/패턴 기반 파서만), 실제 외부 메시지 발송 금지 (Mock 유지),
> H2 인메모리만 사용, 운영 DB·배포 금지.

## 1. 아키텍처

```
HTTP 요청 (X-Auth-Key)
  → AuthKeyFilter (1단계 인증)
  → ReaSecurityInterceptor (테넌트 격리 + 역할 권한)
  → AgentController (POST /api/rea/{academyId}/agent/execute)
  → AgentExecutor: 파싱 → 정책 검사 → 도구 실행 → 감사 로그 기록
  → CommandParser (자연어 → {toolName, params})
  → AgentDispatcher (도구 이름 → 인스턴스 매핑)
  → AgentTool 7종 (기존 Service 재사용)
```

### 클래스 목록

| 클래스 | 역할 |
|---|---|
| `AgentTool` | 도구 인터페이스: `getName()`, `getDescription()`, `getInputSchema()`, `isWrite()`, `execute(input, ctx)` |
| `AgentContext` | 실행 컨텍스트 (레코드): `principalType`, `academyId`, `role`, `principalId`, `principalName`, `writeApproved`(P1-a 이후 **쓰기 승인 근거로 쓰지 않음**, 하위 호환 자리). 1단계 `AuthContext`에서 학원·역할·주체를 가져온다 |
| `AgentUtils` | 입력 검증·날짜 파싱("오늘"/"어제"/"내일"/"yyyy-MM-dd") 공통 유틸 |
| `AgentDispatcher` | Spring에 등록된 `AgentTool` 빈을 이름으로 조회·실행 |
| `CommandParser` | 자연어 → 도구 호출 변환 (키워드/패턴 기반). 실패 시 후보 도구 제안 |
| `AgentExecutor` | 단일 파이프라인: 파싱 → 정책 검사 → 도구 실행 → 감사 로그(`AuditRecorder`, `AGENT_TOOL_EXECUTED`) |
| `AgentController` | `POST /api/rea/{academyId}/agent/execute`, `GET /api/rea/{academyId}/agent/tools` |

## 2. 도구 목록 (8종)

스키마 표기: `*` 접미 = 선택값, 없으면 필수.

### 2.1 `registerStudent` (쓰기)

학생 등록 + 인증키 발급. `classId`가 있으면 등록 직후 반에 배정. `MemberService.createStudent` + `ClassService.assignStudents` 재사용.

- 입력: `name` (학생 이름), `classId*` (반 ID), `parentName*`, `parentPhone*`, `grade*`, `schoolName*`
- 출력: `studentId`, `name`, `authKey`, `classId`, `className`

### 2.2 `assignStudentToClass` (쓰기)

학생을 지정된 반에 배정. `ClassService.assignStudents` 재사용.

- 입력: `studentId`, `classId`
- 출력: `studentId`, `classId`, `className`

### 2.3 `getAbsentStudents` (읽기)

지정 날짜의 결석 학생 목록. 모든 활성 반의 명부를 확인해 상태 `ABSENT`인 학생을 수집.

- 입력: `date*` ("오늘"/"어제"/"yyyy-MM-dd", 기본 오늘)
- 출력: `date`, `count`, `absentees[]` (`studentId`, `studentName`, `classId`, `className`)

### 2.4 `getHomeworkIncomplete` (읽기)

반의 숙제 미제출 학생 목록. `homeworkId`가 없으면 해당 반 전체 숙제 대상.
검사 기록이 `COMPLETED`가 아닌 학생(미검사 포함)을 미제출로 본다.

- 입력: `classId`, `homeworkId*`
- 출력: `classId`, `className`, `homeworkId`, `incompleteCount`, `students[]` (`studentId`, `studentName`, `homeworkId`, `homeworkTitle`)

### 2.5 `createNoticeDraft` (쓰기)

공지 초안 작성. **학부모 알림을 발송하지 않는다** (발송은 별도 단계).
일반 공지 작성(`NoticeService.create`)을 거치지 않고 `ReaNoticeRepository`에 직접 저장하며,
제목에 `[초안]` 접두어를 붙인다.

- 입력: `target` (`"ALL"`=전체 또는 반 ID), `title`, `content`
- 출력: `noticeId`, `title`, `target`, `draft: true`

### 2.6 `analyzeGradeChanges` (읽기)

학생 성적 변화 분석. `LearningService.getGrades` 재사용.
과목별로 평균 백분율·첫 성적·최근 성적·추이(상승/하락/유지, ±5%p 임계)를 계산하고 요약 텍스트 생성.

- 입력: `studentId`, `startDate*` (기본 90일 전), `endDate*` (기본 오늘)
- 출력: `studentId`, `studentName`, `startDate`, `endDate`, `gradeCount`, `overallAvgRate`,
  `subjects[]` (`subject`, `count`, `avgRate`, `firstRate`, `lastRate`, `trend`), `summary`

### 2.7 `generateOperationReport` (읽기)

학원 운영 현황 보고서. 읽기 전용이므로 외부 메시지 발송 없음.
- 출결률: 활성 반 × 기간 일자별 명부 집계 (등원=등원/하원완료/지각/조퇴)
- 공지 수, 상담 수(진행중/답변완료/종료)

- 입력: `startDate*` (기본 30일 전), `endDate*` (기본 오늘)
- 출력: `classCount`, `attendanceRate`, `attendedCount`, `rosterCount`, `absentCount`, `lateCount`,
  `earlyLeaveCount`, `noticeCount`, `consultationCount`, `open/answered/closedConsultationCount`, `report` (한글 텍스트)

### 2.8 `getStudents` (읽기)

학원 또는 반의 학생 목록(이름·학년). `classId`·`className`이 모두 없으면 학원 전체 활성 학생.
학부모 연락처 등 민감 정보는 반환하지 않는다. `ClassService`·`MemberService` 재사용.

- 입력: `classId*` (반 ID), `className*` (반 이름, 예: "영어반"), `nameKeyword*` (학생 이름 검색어)
- 출력: `classId`, `className`, `count`, `students[]` (`studentId`, `studentName`, `grade`)

## 3. 실행 정책

| 구분 | 정책 |
|---|---|
| 읽기(Read) 도구 | 즉시 실행 |
| 쓰기(Write) 도구 | **`writeApproved` 불리언으로는 실행되지 않는다(P1-a).** 원장은 서버 보관 실행 계획(미리보기 포함)을 받고 `POST …/agent/plans/{planId}/approve` 로만 실행된다. 강사는 서버 초안만 확인, 학부모는 거부. → [agent-approval.md](agent-approval.md) |
| 학부모 | 쓰기 도구 호출 거부 (승인 여부 무관). HTTP 레벨에서도 `POST`는 쓰기이므로 `/agent/execute` 접근 시 403 |
| 외부 메시지 발송 | 금지. 모든 도구는 Mock/발송 없음 경로만 사용 |
| 감사 로그 | 읽기 실행·학부모 거부는 `AGENT_TOOL_EXECUTED`, AI 쓰기 계획의 생성·승인·실행·실패·거부·취소·만료·무효화는 `AGENT_PLAN_*` 로 `rea_audit_logs`에 append-only 기록 (대상 종류="에이전트") |

### 역할 × Agent API 접근 (1단계 보안 유지)

- 원장: 전체 허용
- 강사: 읽기 도구 허용. AI 쓰기는 차단(서버 초안만 확인) — 승인·취소 API 는 `ReaAccessRules` 에 의해 403. 수동 업무 권한은 변경 없음
- 학부모: 403 (읽기 명령도 Agent API 경유 불가)
- 테넌트 격리: URL `{academyId}` ≠ 인증 사용자 학원 → 403

## 4. CommandParser 패턴

유료 API·LLM 없이 규칙 기반. `CommandParser.ParseResult {ok, toolName, params, suggestions, message}`.

| 명령 예시 | 도구 | 추출 파라미터 |
|---|---|---|
| `학생 등록 김철수`, `"이름: 김철수" 학생 등록`, `"김철수" 학생 등록` | `registerStudent` | `name` (따옴표 > "이름:" > "학생 등록 X" 순서) |
| `학생 3을 2반에 배정해줘` | `assignStudentToClass` | `studentId=3`, `classId=2` ("학생 N" / "N반" / 숫자 순서) |
| `오늘 결석 학생`, `어제 결석자 조회`, `2026-10-12 결석 학생` | `getAbsentStudents` | `date` ("오늘"/"어제"/"yyyy-MM-dd") |
| `숙제 미제출 학생 확인`, `3반 숙제 미완료자`, `숙제 5 미제출` | `getHomeworkIncomplete` | `classId`, `homeworkId*` |
| `공지 작성`, `전체 공지 초안 작성`, `공지 작성 "방학 안내" 내용: ...` | `createNoticeDraft` | `target` (전체→ALL / N반→ID), `title`, `content` |
| `성적 분석해줘`, `학생 성적 변화 추이`, `... 2026-01-01 ~ 2026-06-30` | `analyzeGradeChanges` | `studentId`, `startDate`, `endDate` |
| `운영 보고서 만들어줘`, `최근 7일 현황 보고서` | `generateOperationReport` | `startDate`, `endDate` ("최근 N일" 지원) |

파싱 실패 시: `success: false`, 이해 불가 메시지 + 명령에 포함된 키워드 기반 후보 도구 제안(`suggestions`).

## 5. API

### `POST /api/rea/{academyId}/agent/execute` (인증 필수)

요청:
```json
{ "command": "학생 등록 김철수" }
```
`writeApproved:true` 는 **400**(`WRITE_APPROVED_NOT_ACCEPTED`)으로 거부된다. 구조화 호출 `{ "tool": "...", "params": {...}, "idempotencyKey": "<uuid>" }` 도 지원한다. 쓰기 도구는 아래 "승인 필요" 응답(`plan`)을 돌려주며 데이터는 바뀌지 않는다. 승인·조회·취소 API 는 [agent-approval.md](agent-approval.md) 참조.

응답:
```json
{
  "success": true,
  "toolUsed": "registerStudent",
  "result": { "studentId": 12, "name": "김철수", "authKey": "...", "classId": null, "className": null },
  "message": "실행 완료: registerStudent",
  "requiresApproval": null,
  "suggestions": null
}
```

승인 필요 응답 예시 (쓰기 도구, 원장):
```json
{ "success": false, "toolUsed": "registerStudent", "requiresApproval": true,
  "plan": { "planId": "…", "status": "PENDING", "planHash": "…", "expiresAt": "…",
            "preview": { "summary": "새 학생을 등록합니다", "changes": { "학생 이름": "김철수" }, "notice": "…" } },
  "message": "승인이 필요합니다. 아래 변경 내용을 확인하고 승인해 주세요. 승인 전에는 아무것도 저장되지 않습니다." }
```

### `GET /api/rea/{academyId}/agent/tools` (인증 필수)

8개 도구의 이름·설명·입출력 스키마·쓰기 여부 목록.

## 6. 변경된 기존 파일

- `domain/admin/AuditActionType.java`: `AGENT_TOOL_EXECUTED("에이전트 도구 실행")` 추가
- `security/ReaAccessRules.java`: `TEACHER_WRITES`에 `POST /api/rea/*/agent/execute` 추가
  (강사가 Agent API를 쓸 수 있게 허용. 실제 쓰기 허용은 `AgentExecutor`의 승인 검사가 담당)

## 7. 테스트

| 테스트 클래스 | 내용 | 수 |
|---|---|---|
| `agent/CommandParserTest` | 명령 → 도구 매핑 14종, 파라미터 추출, 실패·제안 | 24 |
| `agent/AgentToolTest` | 도구 7종 단위 테스트 | 13 |
| `agent/AgentExecutorTest` | 읽기 즉시 실행 / 쓰기 승인 거부 / 학부모 거부 / 감사 로그 | 6 |
| `agent/AgentControllerSecurityTest` | 401(키 누락·무효) / 403(타 학원·학부모) / 역할별 실행 / 도구 목록(8종) | 11 |
| `agent/AgentTask3VerificationTest` | 5단계 작업 3: 8개 케이스 E2E 검증 (시딩 + 입력→파싱→실행→DB→감사로그) | 27 |
| 기존 10종 | 회귀 | 182 |
| **합계** | | **263 PASS** |
