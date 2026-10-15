# REA 5단계 작업 3: AI 학원 비서 검증

- 일자: 2026-10-13
- 방식: `javac -encoding UTF-8 -parameters` 직접 컴파일 + 커스텀 JUnit 러너(TestRunner), H2 인메모리
- 테스트 코드: `backend/src/test/java/com/rea/academy/agent/AgentTask3VerificationTest.java` (27개)
- 결과: **신규 27/27 PASS, 전체 스위트 284/284 PASS (회귀 없음)**

## 검증용 시딩 데이터 (학원 ID=1, 원장 컨텍스트)

| 구분 | 내용 |
|---|---|
| 반 | 영어반, 수학반 (담당: 원장) |
| 학생 | 김철수(고2)·이영희(고1) → 영어반 / 박민수(중3) → 수학반 / 최지훈(고2) → 미배정 |
| 출결 | 김철수 오늘 ABSENT (감기) |
| 숙제 | 영어반 "영어 단어 암기" — 이영희만 COMPLETED, 김철수 미제출 |
| 성적 | 김철수: 영어 70%(30일 전)→85%(오늘, 상승), 수학 80% 단일(유지). 이영희: 성적 없음 |

## 케이스별 결과

### 케이스 1. "영어반 학생 목록 보여줘" — PASS

- 입력: `영어반 학생 목록 보여줘`
- 파싱: `getStudents`, params `{className=영어반}`
- 실행 도구: `getStudents` (읽기, 승인 불필요 → 즉시 실행)
- DB 상태: 변경 없음 (읽기). 결과 `count=2`, 김철수·이영희 (이름·학년만 반환, 학부모 연락처·인증키 미포함)
- 감사 로그: `AGENT_TOOL_EXECUTED` / target `getStudents` / "성공"
- 추가 확인: 반 미지정 시 학원 전체 4명 반환. 존재하지 않는 반("과학반") → `실행 실패: 반을 찾을 수 없습니다` 안내
- 화면: `CHAT-01-ai-chat.html`의 `renderResult`에 `getStudents` 케이스 추가 — 이름·학년 표(`<table class="data">`) 렌더링, TOOLS 패널에 예시 명령 2종 등록 (정적 코드 검증)

### 케이스 2. "오늘 결석한 학생 누구야?" — PASS

- 입력: `오늘 결석한 학생 누구야?`
- 파싱: `getAbsentStudents`, params `{date=오늘}`
- 실행 도구: `getAbsentStudents` (읽기, 즉시 실행)
- DB 상태: 변경 없음. 결과 `date=2026-10-13`, `count=1`, 김철수(영어반)
- 감사 로그: `AGENT_TOOL_EXECUTED` / target `getAbsentStudents` / "성공"
- 추가 확인: `어제 결석한 학생 누구야?` → `count=0` 정상 반환 (0명 표시도 정상 케이스)

### 케이스 3. "숙제 안 낸 학생 찾아줘" — PASS (파서 수정 후)

- 입력: `영어반 숙제 안 낸 학생 찾아줘`
- 파싱: `getHomeworkIncomplete`, params `{className=영어반}` ("안 낸" 변형 인식)
- 실행 도구: `getHomeworkIncomplete` (읽기, 즉시 실행)
- DB 상태: 변경 없음. 결과 `incompleteCount=1`, 김철수 / 숙제 "영어 단어 암기" (이영희는 COMPLETED라 제외)
- 감사 로그: `AGENT_TOOL_EXECUTED` / target `getHomeworkIncomplete` / "성공"
- 참고: 반을 특정할 수 없는 `숙제 안 낸 학생 찾아줘`는 파싱은 성공하나 도구 실행 단계에서 `반 ID 또는 반 이름은 필수입니다` 안내 후 실패 (실행 안 됨)

### 케이스 4. "학부모 공지문 작성해줘: 내일 휴원" — PASS

- 입력: `학부모 공지문 작성해줘: 제목: 내일 휴원 안내 내용: 내일은 하루 휴원합니다.`
- 파싱: `createNoticeDraft`, params `{target=ALL, title=내일 휴원 안내, content=내일은 하루 휴원합니다.}`
- 1차 요청(`writeApproved=false`): `success=false`, `requiresApproval=true`, `toolUsed=createNoticeDraft` — 도구 미실행
- DB 상태: 공지 수 변경 없음, 알림(Notification) 수 변경 없음 — **즉시 발송 없음 확인**
- 감사 로그: `AGENT_TOOL_EXECUTED` / target `createNoticeDraft` / "거부: 쓰기 승인 없음"
- 화면: `requiresApproval=true` 응답 시 채팅 UI가 `pendingCmd` 저장 + "⚠️ 승인이 필요합니다" +
  "승인하고 실행"/"취소" 버튼 표시 (정적 코드 검증, `renderAgentResponse`의 `requiresApproval` 분기)

### 케이스 5. 승인 → DB 반영 + 감사 로그 — PASS

- 승인 전 스냅샷: 공지 0건, 알림 N건, 감사로그 M건 기록
- 승인 요청(`writeApproved=true`) 실행: `success=true`, `toolUsed=createNoticeDraft`,
  result `{noticeId, title=[초안] 내일 휴원 안내, target=ALL, draft=true}`
- DB 상태: 공지 1건 증가. 저장 내용 — 제목 `[초안] 내일 휴원 안내`, 내용 `내일은 하루 휴원합니다.`,
  `classId=null`(전체 대상). 알림 수는 증가 없음 (**DB 반영까지만, 발송 없음**)
- 감사 로그: M+1건. 최신 로그 target `createNoticeDraft`, detail "성공" 포함

### 케이스 6. 승인 거부 시 DB 변경 없음 — PASS

- 입력: 케이스 4와 동일 형식, `writeApproved=false` 유지 (사용자가 "취소"를 누른 것과 동일)
- 결과: `success=false`, `requiresApproval=true`
- DB 상태: 공지 수·학생 수 모두 변경 없음
- 감사 로그: "거부: 쓰기 승인 없음" 1건 추가 (취소된 명령의 흔적은 감사 로그에만 남음)

### 케이스 7. "이번 달 성적 변화 분석해줘" — PASS

- 입력: `학생 {김철수ID} 이번 달 성적 변화 분석해줘`
- 파싱: `analyzeGradeChanges`, params `{studentId}`
- 실행 도구: `analyzeGradeChanges` (읽기, 즉시 실행)
- DB 상태: 변경 없음. 결과 `gradeCount=3`, 영어 `70.0%→85.0% (상승)`, 수학 `(유지)`,
  summary에 "총 3건, 전체 평균 …" + 추이 라인 포함
- 감사 로그: `AGENT_TOOL_EXECUTED` / target `analyzeGradeChanges` / "성공"
- 추가 확인: 성적이 없는 이영희 → `gradeCount=0`, `summary="기간 내 성적 기록이 없습니다."` (빈 결과 안내 정상)
- 참고: 학생을 특정하지 않은 `이번 달 성적 변화 분석해줘`는 파싱 성공 후 도구 실행 단계에서
  `학생 ID(은)는 필수입니다` 안내 후 실패 — 어느 학생인지 물을 수 없어 실행하지 않음 (관찰 사항, 수정 없음)

### 케이스 8. "학생 삭제해줘" — PASS (파서 수정 후)

- 입력: `학생 삭제해줘` (승인 플래그 `true`여도 동일)
- 파싱: **실패** — `DESTRUCTIVE_KW(삭제|지우|제거|탈퇴|파기)` 가드에서 명시적 거부.
  메시지: `파괴적 작업(삭제·제거 등)은 에이전트로 실행할 수 없습니다. 관리 화면에서 직접 처리하세요.`
- 실행된 도구: 없음 (`toolUsed=null`)
- DB 상태: 학생 수 변경 없음 (삭제 미실행)
- 감사 로그: 파싱 실패는 도구 미실행이므로 미기록 (기존 정책 유지 — 파서 실패는 감사 대상 아님)
- 추가 확인: `학생 5를 2반에서 제거해줘` 같은 변형도 동일하게 거부

## 발견·수정한 이슈 (4건)

| # | 이슈 | 원인 | 수정 | 검증 |
|---|---|---|---|---|
| I1 | "영어반 학생 목록 보여줘" 파싱 실패 (도구 없음) | 학생 목록 조회 도구가 7종에 없음 | 신규 읽기 도구 `GetStudentsTool` 추가 (`classId*`/`className*`/`nameKeyword*`, 민감정보 제외). 파서에 규칙 8 추가 ("학생"+목록/명단/리스트/조회/보여 → `getStudents`, 반 이름 추출) | 케이스 1 (3건) |
| I2 | "숙제 안 낸 학생 찾아줘" 파싱 실패 | 파서가 "미제출"/"미완료"만 인식 | "안 낸"/"못 낸" 변형 인식 추가 (`isUnsubmitted`, 공백 제거 후 검사 — "안내" 오탐 방지). `GetHomeworkIncompleteTool`에 `className*` 입력 추가 ("영어반 숙제 …" 지원) | 케이스 3 (4건) |
| I3 | "학생 삭제해줘"가 단순 파싱 실패로 끝남 | 파괴적 작업에 대한 명시적 가드 없음 (현재는 삭제 도구가 없어 실행은 안 되지만, 거절 사유가 불명확) | 파서 최우선 규칙으로 파괴적 키워드(삭제\|지우\|제거\|탈퇴\|파기) 명시적 거부 + 안내 메시지 | 케이스 8 (3건) |
| I4 | 신규 도구 클래스가 컴파일 산출물에 누락 | `.testbuild/main-sources.txt`에 `GetStudentsTool.java` 미등록 → `AgentDispatcher`가 `getStudents`를 찾지 못해 "알 수 없는 도구입니다" 실패 (케이스 1 실행 3건 실패) | `main-sources.txt`에 1행 추가 후 전체 재컴파일 | 전체 284/284 |

## 프론트엔드 변경 (CHAT-01-ai-chat.html)

- `TOOLS` 패널에 `getStudents` 항목 추가 (예시: `영어반 학생 목록 보여줘`, `학생 목록 보여줘`)
- `renderResult`에 `getStudents` 케이스 추가: 반 이름 + 학생 수 + 이름/학년 표 렌더링, 0명 시 안내 문구
- 기존 승인 UI(`requiresApproval` → "승인하고 실행"/"취소" 버튼, `approveAndRun()`)는 코드 정적 검증으로 확인 — 케이스 4·5·6의 백엔드 응답과 정합

## 빌드/테스트 이력

1. 기존 agent 테스트 4종 베이스라인: 54/54 PASS
2. 이슈 I1·I2·I3 코드 수정 + 검증 테스트 27개 작성 → 실행: 24/27 PASS, 케이스 1 실행 3건 실패
3. 원인 분석: I4 (소스 목록 누락) → 수정 후 전체 재컴파일 → 27/27 PASS
4. 전체 스위트 회귀: **284/284 PASS** (기존 257 + 신규 27)

## 경계 준수

- `~/workspace/wemade-reorg/` 미접촉, EASY 코드 미접촉
- 운영 DB 미사용 (H2 인메모리만)
- 실제 외부 메시지 발송 없음: 공지 초안은 `ReaNoticeRepository` 직접 저장 + `[초안]` 접두어, 알림 발송 경로 없음 (Mock 유지)
- 유료 API/LLM 미사용 (키워드/패턴 기반 파서 유지)

## 남은 관찰 사항 (수정 없음)

- O1: 학생 미지정 성적 분석(`이번 달 성적 변화 분석해줘`)은 도구 실행 단계에서 "학생 ID 필수" 안내 후 실패.
  채팅 UI의 대화 컨텍스트(`lastIds`)가 있으면 "그 학생 성적 분석"처럼 ID를 주입할 수 있어 실사용 영향은 제한적.
- O2: 반 미지정 숙제 조회(`숙제 안 낸 학생 찾아줘`)도 동일하게 "반 ID 또는 반 이름 필수" 안내 후 실패.
  후속 대화에서 "영어반"을 지정하면 정상 동작.
- O3: 파싱 실패(케이스 8 등)는 감사 로그에 기록되지 않음 — 기존 정책(도구 미실행은 감사 대상 아님) 유지.
