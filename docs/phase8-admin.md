# REA 학원관리 8단계 — 학원 관리·관리자 기능 구현 기록 (최종)

> 작성일: 2026-10-13
> 범위: 학원 기본정보 관리·지점 관리·사용자/권한 관리·변경 이력(감사 로그)·전 도메인 데이터 격리 검증·모바일 점검 문서화·최종 통합 테스트
> 기준: `docs/functional-spec-v1.md` (8단계), 비투 공개 동작만 참고하여 독립 코드 작성
> 경계: **비투 비공개 소스·디자인 자산 복제 금지. EASY 장부 코드 금지. 운영 DB 금지 (H2만). 실제 외부 메시지 발송 금지. 수납·결제 기능 개발 금지.**

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 학원 기본정보 | 조회·수정 (이름·전화·주소·로고 키·메모). PUT upsert — 미등록이면 신규 등록("학원 등록", name 필수), 등록済이면 제공 항목만 수정 |
| 지점 관리 | 본원·지점 관계 (`parentAcademyId`). 본원은 PUT /info로 먼저 등록되어야 함. 지점의 새 academyId는 서버 자동 발급 (등록된 학원 중 최대값 + 1) |
| 사용자 목록 | `?role=` — OWNER(원장) / TEACHER(강사) / PARENT(학부모 연결) / 미지정 시 원장·강사 전체 |
| 학부모 연결 관리 | 학생별 학부모 성명·연락처·연결 여부 (`connected` = 학부모 성명 등록 여부). 학부모는 학생 인증키로 로그인하므로 별도 회원 없음 |
| 권한 변경 (원장만) | `actorMemberId`가 같은 학원의 활성 원장이어야 함. 강사→원장 승격 허용, 마지막 원장 강등 방지(409). 원장 교체는 "후임 승격 → 전임 강등" 2단계 |
| 변경 이력 (감사 로그) | append-only 기록. 조회 API (관리자, 최신순, `?actionType=` 필터) |
| 감사 대상 | 학생 등록·삭제 / 반 생성·종료 / 출결 정정 / 공지 작성·삭제 / 권한 변경 / 학원 정보 수정 / 지점 등록 |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 전 도메인(회원·반·출결·알림·공지·상담·학습·운영·관리) 격리 테스트 |
| 모바일 점검 | 33개 화면 viewport·반응형 점검 문서화 (`docs/mobile-check.md`, 코드 수정 없음) |

### 감사 로그 기록 방식 (기존 코드 수정 없음)

기존 서비스(MemberService·ClassService·AttendanceService·NoticeService)를 수정하지 않기 위해
**AOP**(`AuditLogAspect`)로 메서드 성공 시점에 이력을 기록한다:

| 대상 메서드 | 기록 종류 | 방식 |
|---|---|---|
| `MemberService.createStudent` | STUDENT_CREATED | `@AfterReturning` (학원 ID는 요청 DTO에서 추출) |
| `MemberService.deactivateStudent` | STUDENT_DELETED | `@Around` (실행 전 학생명 조회) |
| `ClassService.createClass` | CLASS_CREATED | `@AfterReturning` |
| `ClassService.closeClass` | CLASS_CLOSED | `@Around` (실행 전 반 이름 조회) |
| `AttendanceService.correctEvent` | ATTENDANCE_CORRECTED | `@AfterReturning` |
| `NoticeService.create` | NOTICE_CREATED | `@AfterReturning` |
| `NoticeService.delete` | NOTICE_DELETED | `@Around` (실행 전 공지 제목 조회) |
| `AdminService.saveAcademyInfo` | ACADEMY_INFO_UPDATED | 직접 기록 |
| `AdminService.createBranch` | BRANCH_CREATED | 직접 기록 (본원·지점 양쪽) |
| `AdminService.changeMemberRole` | MEMBER_ROLE_CHANGED | 직접 기록 |

- 감사 기록 실패는 업무 로직에 영향을 주지 않는다 (실패 격리: warn 로그만).
- 기존 서비스에는 행위자(actor) 개념이 없어 해당 이력의 행위자는 null(시스템)로 기록된다.
- 권한 변경은 기존 `ReaMember` 코드를 수정하지 않기 위해 역할이 바뀐 복사본을 `save()` (merge)로 저장한다.

### 검증 규칙
- 학원 등록: name 필수. 수정 시 빈 이름 400
- 지점 등록: 본원 미등록 시 400, 지점 이름 필수
- 사용자 목록: 알 수 없는 role 400
- 권한 변경: 요청자 누락·비원장·타 학원 요청자 400, 마지막 원장 강등 409, 같은 역할·학부모 역할 지정 400
- 이력 조회: 알 수 없는 actionType 400

## 2. 파일 목록

### Backend (신규 — 기존 1차·1~7단계 코드 수정 없음)
| 파일 | 역할 |
|---|---|
| `domain/admin/AuditActionType.java` | 감사 행위 종류 enum 10종 (한글 label + parse) |
| `domain/admin/ReaAcademy.java` | 학원 기본정보 엔티티 (`rea_academies`, id = academyId 직접 지정, `parentAcademyId`) |
| `domain/admin/ReaAcademyRepository.java` | 학원 Repository (지점 목록·max ID 조회) |
| `domain/admin/ReaAuditLog.java` | 감사 로그 엔티티 (`rea_audit_logs`, append-only) |
| `domain/admin/ReaAuditLogRepository.java` | 감사 로그 Repository (최신순·종류별) |
| `dto/AdminDto.java` | AcademyInfo/Branch/User/RoleChange/AuditLog DTO |
| `service/AuditRecorder.java` | 감사 로그 기록기 (@Transactional) |
| `service/AuditLogAspect.java` | 기존 서비스 변경 이력 AOP 기록 (@Aspect, 실패 격리) |
| `service/AdminService.java` | 학원 관리 업무 로직 (DTO 입출력, UI 의존 없음) |
| `controller/AdminController.java` | REST API (HTTP 입출력만) |
| `src/test/.../service/AdminServiceTest.java` | H2 인메모리 서비스 테스트 36건 |
| `src/test/.../service/FullScenarioTest.java` | 최종 통합 시나리오 테스트 2건 |

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `ADM-01-academy-info.html` | 학원 정보: 기본정보 조회·저장(upsert), 지점 등록·목록 |
| `ADM-02-user-roles.html` | 사용자·권한: 역할 탭(전체/원장/강사/학부모 연결), 권한 변경 폼 |
| `ADM-03-audit-log.html` | 변경 이력: 종류 필터·최신순 목록 |

- 데스크톱·모바일 반응형 (`@media` 분기). 기존 화면과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

### Docs
| 파일 | 내용 |
|---|---|
| `docs/phase8-admin.md` | 본 문서 (8단계 구현 기록) |
| `docs/mobile-check.md` | 33개 화면 모바일 대응 점검 리포트 |
| `docs/FINAL-REPORT.md` | 1~8단계 전체 최종 보고서 |

### 기존 코드 변경
- 없음 (1차·1~7단계 코드 미수정 — 회귀 테스트 126건으로 검증)

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/rea/{academyId}/info` | 학원 정보 조회 (미등록 시 400) |
| PUT | `/api/rea/{academyId}/info` | 학원 정보 등록·수정 (upsert) `{name?, phone?, address?, logoKey?, memo?, actorMemberId?}` |
| GET | `/api/rea/{academyId}/branches` | 지점 목록 |
| POST | `/api/rea/{academyId}/branches` | 지점 등록 `{name(필수), phone?, address?, logoKey?, memo?, actorMemberId?}` → 새 academyId 자동 발급 |
| GET | `/api/rea/{academyId}/users?role=` | 역할별 사용자 목록 (`OWNER`/`TEACHER`/`PARENT`, 미지정 시 원장·강사 전체) |
| PUT | `/api/rea/{academyId}/users/{memberId}/role` | 권한 변경 (원장만) `{role(필수), actorMemberId(필수)}` |
| GET | `/api/rea/{academyId}/audit-logs?actionType=` | 변경 이력 조회 (최신순, 종류 필터 선택) |

예외 매핑은 기존 `ApiExceptionHandler` 재사용: `IllegalArgumentException` → 400, `IllegalStateException` → 409.

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, 커스텀 JUnit 엔진 러너)
`AdminServiceTest` — **36건 전부 PASS**
- 학원 정보: 등록(upsert)→조회 / 미등록 조회 400 / 이름 없이 등록 400 / 부분 수정(null 유지) / 빈 이름 수정 400 / 등록·수정 감사 로그 2건
- 지점: 등록(본원 관계·새 ID 발급)→목록 / 본원 미등록 400 / 이름 없음 400 / 본원·지점 양쪽 감사 로그
- 사용자·권한: 전체/원장/강사/학부모연결 목록 / 연결 여부 판정 / 잘못된 role 400 / 비원장 요청 400 / 요청자 누락 400 / 타 학원 요청자 400 / 마지막 원장 강등 409 / 승격 성공+감사 로그 / 원장 교체 2단계(후임 승격→전임 강등) / 같은 역할 400 / 학부모 역할 지정 400 / 원장 없는 학원 승격 불가
- 감사 로그(AOP): 학생 등록·삭제 / 반 생성·종료 / 출결 정정 / 공지 작성·삭제 기록 / 종류 필터 대소문자 무시 / 최신순 정렬
- 격리: 감사 로그·학원 정보·지점·회원/학생·반/출결·공지/상담·학습/알림/일정 — 타 학원 데이터 접근 차단 (전 도메인)

`FullScenarioTest` — **2건 전부 PASS**
- 전체 시나리오: 학원 등록 → 원장 → 강사 → 학생 → 반 생성·배정 → 출결(등원 알림) → 알림 발송 → 공지(알림) → 상담 문의·답변(상태 전이·알림) → 숙제 등록·검사(알림) → 성적 등록(알림) → 진도·학습 리포트 → 일정 → 통계 대시보드 → 감사 로그 확인 → 통합 검색
- 지점 시나리오: 지점 등록 → 지점 학원 독립 운영(원장·학생) → 본원 격리 확인

### 4.2 전체 회귀
9개 테스트 클래스 **164건 전부 PASS**
(Admin 36 + FullScenario 2 + 기존 126: Ops 26 · Learning 18 · Consultation 20 · Notice 14 · Notification 17 · Attendance 16 · Class 15)

### 4.3 빌드 환경 메모
- 기존 방식 그대로: `javac -encoding UTF-8` 직접 컴파일 (`/tmp/rea-build/cp.txt`, 의존성 jar) + 커스텀 TestRunner 구동.
- AOP: `spring-boot-starter-aop` + `aspectjweaver`가 캐시에 있어 추가 의존성 없이 `@Aspect` 동작 확인.
- 디버깅 기록: `MemberService.createStudent`는 단일 DTO 인자라 `jp.getArgs()[0]`이 academyId가 아님 → 요청 DTO에서 추출하도록 수정. 감사 기록 실패는 warn 격리로 테스트에서 즉시 발견 가능.

### 설계 결정 기록
- `ReaAcademy.id`에 `@GeneratedValue` 미사용: id = academyId (멀티테넌트 키 그 자체). PUT /info upsert가 경로 academyId로 직접 행을 만든다.
- 지점 academyId 자동 발급: 등록된 학원 중 최대값 + 1 (프로토타입 단순 할당).
- 권한 변경을 복사+merge로 구현한 이유: 기존 `ReaMember`에 role setter가 없고 "기존 코드 수정 금지" 원칙을 지키기 위함.
- 원장 교체 2단계(후임 승격 → 전임 강등): 1학원 1원장 등록 규칙(`MemberService.createMember`)은 유지하면서, 원장 권한으로 후임을 승격할 수 있게 하여 인수인계를 가능하게 함. 마지막 1명의 원장 강등은 항상 차단.
- 감사 로그 행위자: AdminService 경유 변경은 `actorMemberId`(선택)로 기록, 기존 서비스 경유(AOP)는 행위자 미상으로 null 기록.

## 5. 남은 사항 (향후 과제 — FINAL-REPORT에도 수록)
- 인증키 인증 도입: 현재 `/api/rea/**` 전체 허용. 인증키 기반 인증·원장/강사 권한 체크를 SecurityConfig에 연결 필요.
- 실제 알림 발송 연동: 현재 `MockNotificationSender`만. FCM 푸시·알림톡/SMS 실제 연동 시 `NotificationSender` 구현체 교체.
- 내신Wiki 실제 연동: `WikiIntegrationPort` 구현체 작성 (현재 스텁).
- Report v2.8 고도화: 현재 데이터 API까지만 구현. v2.8 HTML 바인딩은 별도 작업.
- xlsx 네이티브: 현재 CSV(엑셀 호환)로 충족. Apache POI 확보 시 확장.
- 반복 일정: 현재 단건 일정만 (매주/매월 반복 규칙 미구현).
- 감사 로그 행위자: 기존 서비스(AOP 경유) 변경 시 행위자를 알 수 없음 — 인증 도입 후 SecurityContext에서 행위자 주입으로 개선 가능.
- 지점 academyId 발급: 현재 max+1 단순 할당. 동시 등록 경합·미등록 학원 ID 충돌 가능성 — 운영 단계에서 시퀀스 테이블 등 확정 필요.
