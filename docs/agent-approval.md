# AI 쓰기 승인 — 서버 검증형 2단계 승인 (P1-a)

> 상태: 구현·로컬 검증 완료(Draft PR). **병합·운영 배포·운영 DB 변경은 별도 승인 후** 진행한다.
> 설계 배경: BuildIt `docs/academycraft/` (P1 설계, PR #826).

## 1. 확정 정책
| 항목 | 정책 |
|------|------|
| AI 쓰기 권한 | **ACADEMY_OWNER 만.** TEACHER 는 조회와 초안(서버 미리보기) 확인만, AI 쓰기는 차단. PARENT 는 거부 |
| 수동 업무 권한 | 변경 없음 (`ReaAccessRules` 의 기존 강사 쓰기 화이트리스트 그대로) |
| 계획 유효기간 | **10분** (`rea.agent.approval.ttl-minutes`) |
| 승인 근거 | 서버 보관 실행 계획 ID + 사용자가 본 계획 해시. **`writeApproved` 불리언은 승인 근거가 될 수 없다** |

## 2. 흐름과 API (모두 `/api/rea/{academyId}/agent/…`, 기존 인증·테넌트 격리 적용)
| 단계 | 호출 | 동작 |
|------|------|------|
| 요청 | `POST /execute` `{command}` 또는 `{tool, params, idempotencyKey?}` | 읽기 도구: 즉시 실행. **쓰기 도구: 실행하지 않고** 서버가 미리보기를 만든 계획(`plan`)을 반환. `writeApproved:true` 는 **400** |
| 조회 | `GET /plans/{planId}` | 계획 상태·미리보기·(비밀값 제거된) 결과. 만든 본인만 |
| 승인 | `POST /plans/{planId}/approve` `{planHash}` | 입력은 받지 않는다. 서버 재검증 후 **1회** 실행. 강사는 403 |
| 취소 | `POST /plans/{planId}/cancel` | PENDING 만. 강사는 403 |

`idempotencyKey` 는 헤더가 아니라 **본문 필드**다(BuildIt 게이트웨이는 `Content-Type` 외 헤더를 전달하지 않고, 본문은 서명 대상이다).

### 상태
`PENDING → EXECUTING → EXECUTED | FAILED`, 그리고 `CANCELLED / EXPIRED / INVALIDATED`. 한 번 벗어난 상태로 돌아가지 않는다. 쓰기 실패는 **자동 재시도하지 않는다**(다시 하려면 새 계획 + 새 승인).

### 승인 시 서버 재검증 순서 (`AgentPlanService.gate`)
1. 계획 행 잠금(`FOR UPDATE`) → 없으면 404
2. 다른 학원 계획 → 404(존재 숨김) + 거부 감사
3. 만든 본인이 아니면 403
4. 상태: EXECUTED 는 **재실행 없이 저장된 결과 반환**, 실행 중 409, 만료 410(상태 EXPIRED), 그 외 종결 상태 409
5. 실제 연결 회원 재조회: 활성·원장 역할·같은 학원·학원 활성, 계획 시점과 같은 인증 경로·연결 지문 → 아니면 `INVALIDATED`
6. 저장 행 변조 탐지: 저장된 입력·미리보기 해시와 계획 해시를 다시 계산 → 불일치 `INVALIDATED(PLAN_TAMPERED)`
7. 요청 해시 비교 → 틀리면 403 `HASH_MISMATCH` (계획은 유지)
8. 도구 재조회(등록된 쓰기 도구인지), 저장 입력 재정규화, **미리보기 재생성 후 저장본과 비교** → 대상이 바뀌었으면 `INVALIDATED(TARGET_CHANGED)`
9. `UPDATE … WHERE status='PENDING' AND expires_at > now` **조건부 갱신(CAS)** 이 1행일 때만 `EXECUTING` 으로 선점 → 아니면 409
10. 도구 실행(저장된 입력만 사용, 쓰기 승인은 서버가 내부에서만 부여) → `EXECUTED`/`FAILED` + 감사

### 계획 생성 시
- 입력 정규화: 스키마에 있는 키만, 스칼라 값만, 길이 제한. 모델이 만든 파라미터를 그대로 믿지 않는다.
- 도구 `preview()` 가 참조 대상(학생·반)이 **같은 학원 소속·활성**인지 서버가 직접 조회해 확인하고 변경 미리보기를 만든다. `preview` 를 구현하지 않은 쓰기 도구는 계획을 만들 수 없다(fail-closed).
- 한도: 원장당 승인 대기 20개, 분당 생성 10개. 같은 원장의 생성 요청은 회원 행 잠금으로 직렬화한다(별도 트랜잭션을 쓰지 않아 연결 풀 고갈 위험이 없다).

## 3. 위협별 방어와 검증 테스트
| 위협 | 방어 | 테스트 |
|------|------|--------|
| `writeApproved:true` 로 직접 쓰기 | 컨트롤러 400 거부 + `AgentExecutor` 가 값을 무시(쓰기 도구를 직접 실행하는 경로 자체가 없음) | `givenWriteApprovedTrue_…`, `AgentExecutorTest`, `AgentControllerSecurityTest` |
| 승인처럼 보이는 필드(`approved`, `planId` …) | 무시되고 계획만 생성 | `givenFakeApprovalFields_…` |
| 모델 제안 파라미터 변조·미리보기 위조 | 정규화·소속 검증·서버 미리보기. 요청의 `preview`/`explanation` 무시 | `givenModelSuppliedPreviewFields_…`, `givenSmuggledParams_…` |
| 승인 후 입력 바꿔치기 | 승인 본문은 입력 미수신, 실행 입력은 저장값 | `givenApproveBodyWithExtraInput_…` |
| 저장 행 변조 | 입력·미리보기·계획 해시 재계산 | `givenTamperedStoredInput_…` |
| 틀린 해시 | 403, 계획 유지 | `givenWrongHash_…` |
| 동시 승인 / 더블 클릭 | 행 잠금 + CAS 1회 실행 | `givenPlan_whenConcurrentApprovals_…`(8개 요청 → 학생 1명), `givenDoubleClick_…` |
| 만료 | 10분, 만료 시 410 | `givenExpiredPlan_…` |
| 권한 변경·회원 비활성·학원 비활성·연결 변경 | 승인 시 실제 회원 재조회·지문 비교 | `givenRoleChangedToTeacher_…`, `givenMemberDeactivated_…`, `givenAcademyDeactivated_…`, `givenAuthSourceChanged_…` |
| 다른 학원 / 다른 원장 | 인터셉터 403, 계획 404, 본인만 승인 | `givenOtherAcademyOwner_…`, `givenAnotherOwnerInSameAcademy_…` |
| 강사·학부모 | 강사: 초안만·승인/취소 403·조회 404, 학부모 403 | `givenTeacher_…`, `givenParent_…` |
| 대상 변경(승인 전 반 종료 등) | 미리보기 재생성 비교 → 무효 | `givenTargetChangedAfterPlan_…` |
| 중복 요청 | `idempotencyKey` 동일 계획 반환, 다른 요청 409, 동시 생성 1개 | `givenIdempotencyKey_…`, `givenSameKeyConcurrently_…` |
| 실행 실패·재시도 | FAILED 고정, 재승인 409, 도구 1회만 호출 | `AgentApprovalFailureTest` |
| 실행 중 중단 | 오래된 EXECUTING 은 결과 불명 실패로 표시, 재실행 없음 | `givenStaleExecuting_…` |
| 비밀값·개인정보 | 인증키는 승인 직후 응답에만, 저장·감사에 없음. 감사에는 입력 해시만 | `givenRegisterResult_…`, `givenFullFlow_…` |
| 호출 폭주 | 대기·분당 상한(429) | `AgentApprovalLimitsTest` |
| 긴급 차단 | `rea.agent.approval.enabled=false` → 쓰기 503, 읽기·수동 기능 정상 | `AgentApprovalDisabledTest` |
| BuildIt 게이트웨이 노출 | P1-a 에서는 `/agent` 를 열지 않음(P1-b 별도 승인) | `BuildItGatewayAgentPathsTest` |

방어를 일부러 제거하는 변이 확인: 해시 검사 제거 → `givenWrongHash` 실패, 실시간 권한·연결 재검증 제거 → 4개 테스트 실패, CAS·행 잠금 제거 → 동시 승인 테스트 실패(테스트가 실제 방어를 검증함).

## 4. 기존 경로 우회 차단과 안전한 전환
### 문제
기존에는 `POST /agent/execute` 가 요청 본문의 `writeApproved:true` 만으로 쓰기 도구를 실행했다. 새 승인 API 를 추가하기만 하면 이 경로가 남아 우회가 된다.

### 조치
1. **컨트롤러**: `writeApproved:true` → 400 `WRITE_APPROVED_NOT_ACCEPTED` + 거부 감사. 컨텍스트의 승인 값은 항상 `false`.
2. **`AgentExecutor`**: `AgentContext.writeApproved` 를 **읽지 않는다.** 쓰기 도구는 어떤 호출자·컨텍스트로도 이 엔진에서 실행되지 않고 계획 생성만 한다. 쓰기 도구 실행은 승인된 계획의 `AgentPlanService.approve` 에서만 일어난다.
3. **`CHAT-01-ai-chat.html`**: "쓰기 승인" 체크박스 제거. 쓰기 응답의 서버 미리보기를 보여 주고 [승인하고 실행]/[취소]로 새 API 를 호출(요청에는 계획 해시만). 미리보기 값은 이스케이프한다.
4. 구 테스트가 "불리언으로 쓰기가 실행됨"을 정상으로 단언하던 부분은 새 계약(우회 차단·계획 승인)으로 바꿨다.

### 전환 계획 (호환 기간 없음 — 보안상 불리언 승인을 유지할 수 없다)
| 단계 | 내용 |
|------|------|
| 1 | 서버와 `CHAT-01` 화면은 **같은 배포 단위**(정적 자원 포함)로 나간다. 구 화면을 열어 둔 사용자가 `writeApproved:true` 를 보내면 400 과 안내 메시지가 나오며 새로고침하면 새 흐름이 된다 |
| 2 | BuildIt 게이트웨이는 `/agent` 를 아직 열지 않았으므로 BuildIt 경유 호출자는 없다. 직접 호출자는 AcademyCraft 자체 로그인 사용자의 `CHAT-01` 뿐이다 |
| 3 | 배포 후 감사 로그에서 `AGENT_PLAN_REJECTED` 중 `WRITE_APPROVED_NOT_ACCEPTED` 건수를 확인해 구 클라이언트·우회 시도를 파악한다 |
| 4 | 문제 시 `rea.agent.approval.enabled=false` 로 쓰기만 즉시 중지(읽기·수동 기능 영향 없음) |
| 5 | 외부 스크립트가 있다면 `{command}` → 응답의 `plan.planId/planHash` → `…/approve` 의 2단계 호출로 바꿔야 한다 |

## 5. 데이터 모델·마이그레이션 (V6)
- `backend/src/main/resources/db/migration/V6__agent_plans.sql`: `rea_agent_plans` 추가(**추가 전용**, 기존 테이블 무수정). 유니크 `(academy_id, idempotency_key)`, 인덱스 2개.
- 엔티티 `ReaAgentPlan` 과 동일 정의. `status` 는 `@JdbcTypeCode(VARCHAR)` 로 MySQL ENUM 이 아닌 VARCHAR 로 매핑한다(기존 감사 로그 엔티티와 동일 방식).
- 감사 행위 종류 `AGENT_PLAN_*` 8종 추가(`rea_audit_logs.action_type` 은 VARCHAR(40) 이라 DB 변경 없음).

### 로컬 MySQL 리허설 결과 (MySQL 8.0.46, 이 환경의 격리 인스턴스 — **운영 DB 아님**)
| 리허설 | 결과 |
|--------|------|
| V1~V6 적용 + Hibernate `ddl-auto=validate` | 통과. 리허설 중 `CHAR`↔`VARCHAR`, `ENUM`↔`VARCHAR` 불일치를 발견해 V6 와 엔티티를 수정함(H2 테스트로는 잡히지 않음) |
| 승인·보안·기존 에이전트 회귀 130건을 MySQL 에서 실행(연결 풀 5) | 전부 통과 (동시 승인 1회 실행, 동시 생성 1개). 리허설 중 별도 트랜잭션 방식의 **연결 풀 고갈**을 발견해 행 잠금 직렬화로 교체함 |
| **앱만 이전 버전으로 롤백**(V6 파일 없는 코드 + V6 적용 DB) | 기동·인증 흐름 통과. Flyway 기본값(`ignoreMigrationPatterns=*:future`)이 "적용됐지만 코드에 없는" V6 를 무시한다. 운영 설정(`application-prod.properties`)에 이를 바꾸는 항목은 없음 |
| **최후 수단**: `DROP TABLE rea_agent_plans` + `flyway_schema_history` 의 V6 행 삭제 후 새 앱 기동 | V6 가 다시 정상 적용됨(재적용 가능) |
| **백업 복원**: `mysqldump` → 새 DB → 새 앱 | 계획 38행·버전 이력 1~6 보존, 검증 통과 |

### 운영 적용 절차 (별도 승인 후)
1. 대상 DB 백업(`mysqldump --single-transaction`)과 복원 확인.
2. 이 PR 병합 → 배포(기동 시 Flyway 가 V6 적용).
3. 기능 확인: 읽기 도구, 수동 업무 화면, 쓰기 계획 생성·승인(테스트 학원/계정).
4. 문제 시 순서: ① `rea.agent.approval.enabled=false` ② 앱 이전 버전 롤백(테이블은 무시됨, 위 리허설 확인) ③ 최후 수단으로만 테이블 제거(사전 백업 필수, 자동화 금지) ④ 복원은 백업에서.
- 롤백은 감사 로그 열거형 값(`AGENT_PLAN_*`)을 지우지 않는다(기존 행 보존).

## 6. 설정
| 키 | 기본값 | 설명 |
|----|--------|------|
| `rea.agent.approval.enabled` | `true` | `false` 면 쓰기 도구는 503 안내(긴급 차단) |
| `rea.agent.approval.ttl-minutes` | `10` | 계획 유효기간 |
| `rea.agent.approval.max-pending-per-principal` | `20` | 원장당 승인 대기 상한 |
| `rea.agent.approval.max-creates-per-minute` | `10` | 원장당 분당 생성 상한 |
| `rea.agent.approval.executing-timeout-minutes` | `5` | 이 시간 넘게 EXECUTING 이면 결과 불명 실패로 표시 |

## 7. 한계와 후속 (이 PR 범위 밖)
- **P1-b**: BuildIt `AcademyCraftGateway` 와 AcademyCraft `BuildItGatewayFilter` 의 허용 목록에 `/agent` 정확 경로 5개를 양쪽 동시에 추가(별도 승인).
- 연결 지문은 현재 (주체 종류·주체 ID·학원·역할·인증 경로·BuildIt 역할)로 만든다. 게이트웨이 필터가 BuildIt 사용자 ID 를 요청 속성으로 노출하지 않아 포함하지 못했다 → P1-b 에서 속성을 추가하면 지문에 넣는다.
- 게이트웨이 nonce 는 프로세스 메모리다. 승인 프로토콜은 이에 의존하지 않는다(만료·CAS·해시).
- `registerStudent` 결과의 학생 인증키는 승인 직후 응답에만 표시된다. 응답을 놓치면 학생 상세 화면에서 확인해야 한다.
- 강사 AI 쓰기 개방은 도구별 정책 결정 후(현재 전면 차단). 반 생성·종료·출결·상담 쓰기 도구는 이 승인 구조 위에서 후속(P3)으로 추가한다.
- 과거 검증 기록 `phase5-task3-agent.md` 는 `writeApproved` 시절의 동작을 기록한 역사 문서다.
