# REA 6단계 작업 4 — 운영 환경 준비

- 작성일: 2026-10-13
- 상태: 초안/점검 문서. **운영 DB 적용·운영 배포는 본 작업 범위 밖 (모두 금지)**
- 누적 테스트: 315/315 PASS (H2 인메모리) → 본 작업 신규 PerfReferenceTest 3건 추가
- 전체 스위트 단일 JVM 실행 시: 318 중 304 PASS + 14 FAIL — **14건은 기존 테스트 격리 이슈로 인한 것으로 확인** (아래 4-5 참고, 본 작업 변경과 무관). 클래스별 단독 실행 시 전원 PASS

---

## 4-1. DB 마이그레이션 절차 검증

### 4-1.1 DDL 초안 개요

- 전체 SQL 초안: `db/migration/V1__rea_initial.sql` (**초안 DRAFT — 운영 적용 금지**)
- JPA 엔티티(`com.rea.academy.domain.*`)로부터 수동 추출. 대상: MySQL 8.x, `utf8mb4` / `utf8mb4_unicode_ci`

| # | 테이블 | 행 단위 | 주요 키·제약 |
|---|--------|---------|--------------|
| 1 | `rea_academies` | 학원 1건 | PK(id, 자동생성 없음 — 명시 발급). idx_parent |
| 2 | `rea_audit_logs` | 변경 1건 (append-only) | PK(id). idx_academy, idx_action(academy, action_type, id) |
| 3 | `rea_members` | 회원 1명 | PK(id). **UK(auth_key)**. idx_academy, idx_academy_role |
| 4 | `rea_students` | 학생 1명 | PK(id). **UK(auth_key)**. idx_academy, idx_academy_name |
| 5 | `rea_classes` | 반 1개 | PK(id). idx_academy, idx_academy_active |
| 6 | `rea_class_enrollments` | 반-학생 매핑 1건 (soft delete) | PK(id). idx_enroll_class, idx_enroll_student |
| 7 | `rea_attendance_events` | 출결 이벤트 1건 | PK(id). idx_student(academy, student, occurred_at), idx_range(academy, occurred_at) |
| 8 | `rea_attendance_corrections` | 정정 이력 1건 | PK(id). idx_event(academy, event_id, corrected_at) |
| 9 | `rea_attendance_settings` | 학원당 1행 | PK(id). **UK(academy_id)** |
| 10 | `rea_notifications` | 발송 이력 1건 | PK(id). idx_student, idx_unread(academy, student, read_at) |
| 11 | `rea_notification_settings` | 학생×유형 설정 | PK(id). **UK(academy, student, type)** |
| 12 | `rea_notices` | 공지 1건 (content TEXT) | PK(id). idx_academy, idx_class, FULLTEXT(title) 선택 |
| 13 | `rea_notice_reads` | 읽음 확인 1건 | PK(id). **UK(uk_notice_read: academy, notice, student)** |
| 14 | `rea_consultations` | 상담 스레드 1건 | PK(id). idx_student, idx_status |
| 15 | `rea_consultation_messages` | 메시지 1건 | PK(id). idx_thread, idx_unread |
| 16 | `rea_grades` | 성적 1건 | PK(id). idx_student(academy, student, exam_date, id) |
| 17 | `rea_homework` | 숙제 1건 | PK(id). idx_class, idx_student, idx_academy |
| 18 | `rea_homework_question_refs` | @ElementCollection (내신Wiki 문항 참조 스텁) | idx_homework |
| 19 | `rea_homework_checks` | 숙제 검사 1건 | PK(id). **UK(uk_homework_check: homework_id, student_id)** |
| 20 | `rea_progress` | 진도 기록 1건 | PK(id). idx_student |
| 21 | `rea_schedules` | 일정 1건 | PK(id). idx_date, idx_consultation, idx_title |
| 22 | `rea_teacher_assignments` | 강사 담당 1건 | PK(id). **UK(uq_rea_teacher_assignment: academy, teacher)** |

- 테이블 22개 (컬렉션 테이블 1개 포함). 엔티티에 `@ManyToOne` 등 JPA FK가 **없으므로(전부 논리 Long 컬럼)** 물리 FK는 생성하지 않음. 논리 FK 22개는 SQL 파일 하단 `[논리 FK 목록]`에 정리, 고아 레코드 주기 점검 필요
- 타입 매핑: `Long→BIGINT`, `String(n)→VARCHAR(n)`, TEXT→TEXT, `Boolean→TINYINT(1)`, `LocalDateTime→DATETIME(6)`, `LocalDate→DATE`, `LocalTime→TIME`, `Integer→INT`, ENUM(STRING)→VARCHAR
- 인덱스는 기존 코드에 `@Index` 선언이 없으므로 Repository 쿼리 메서드 패턴에서 도출한 권장치 (파일 하단 `[인덱스 정리]` 참고)

### 4-1.2 버전 관리 전략

1. `V1__rea_initial.sql` = 전체 초기 스키마 (본 초안). 운영 최초 적용 시 1회만 실행
2. `V2` 이후는 **ALTER TABLE 1개 변경 단위 = 1개 파일** (예: `V2__add_notice_pin.sql`)
3. 되돌릴 수 없는 변경(`DROP COLUMN`, `DROP TABLE`)은 V파일에 포함하지 않음 — 별도 승인 후에만 작성
4. 컬럼 추가는 `NULL` 허용 또는 `DEFAULT` 포함 (기존 행 깨짐 방지)
5. `flyway_schema_history`로 적용 이력 추적. 운영 적용 전 전체 백업 필수
6. JPA `ddl-auto` 운영값은 `validate` (스키마 자동 변경 금지). 초안 적용 후 `validate`로 정합성 확인 절차를 거칠 것

### 4-1.3 롤백 절차·원칙

- Flyway는 기본적으로 DDL 롤백을 지원하지 않으므로 **마이그레이션별 롤백 스크립트 작성 원칙**을 문서로 고정:
  1. **원칙 1 — 앞으로만 가는 마이그레이션**: V(N) 실패 시 V(N-1)로 "돌아가는" 것이 아니라, 문제를 고친 **V(N+1) 정정 마이그레이션**을 작성한다. (Flyway 권장 방식)
  2. **원칙 2 — 되돌릴 수 있는 변경만 V파일에**: `ADD COLUMN`, `CREATE INDEX`, `ALTER … DEFAULT` 등 역연산이 명확한 변경만 허용
  3. **원칙 3 — 파괴적 변경은 2단계로**: ① V(N): 신규 컬럼 추가 + 애플리케이션 이중 기록 → ② 데이터 검증 후 별도 승인된 V(M): 구 컬럼 제거. 두 단계 사이 최소 1개 릴리스 간격
  4. **원칙 4 — 롤백 스크립트는 마이그레이션과 쌍으로 보관**: `db/migration/rollback/U2__remove_notice_pin.sql` 형식으로 초안 보관 (실제 적용 금지). Undo는 Flyway Teams 유료 기능이므로 사용하지 않음
  5. **원칙 5 — 실패한 마이그레이션은 repair 후 재시도 금지**: `flyway repair`로 실패 기록 제거 → 원인 수정 → 재배포의 순서. 동일 버전 파일을 수정해서 재적용하지 않음 (체크섬 불일치)

- **롤백 시 데이터 보존 주의사항**
  - 어떤 롤백도 `DELETE`/`TRUNCATE`를 포함하지 않는다. 행 삭제가 필요하면 `active=false` soft delete로 대체
  - `rea_audit_logs`는 append-only: 롤백 스크립트에서 이 테이블의 행을 수정·삭제하지 않는다 (감사 추적 무결성)
  - 인증키(`auth_key`) 컬럼 변경 시 기존 키 무효화 금지 — 키 재발급은 애플리케이션 API로만 수행
  - 롤백 전 `mysqldump` 전체 백업 + `flyway_schema_history` 스냅샷을 남기고, 복구 후 행 수(`COUNT(*)`) 대조로 검증

---

## 4-2. 운영 설정·비밀키 관리 점검

### 4-2.1 application.properties 분리 방안

현재: `backend/src/main/resources/application.properties` 1개 (H2 인메모리, `ddl-auto=create-drop`) — 개발 전용.

분리 방안 (프로파일 방식, Spring Boot 표준):

| 파일 | 용도 | 활성 조건 |
|------|------|-----------|
| `application.properties` | 공통 (앱명, 포트, Jackson 등 비밀·환경 무관 항목만) | 항상 |
| `application-dev.properties` | 개발: H2 인메모리, `ddl-auto=create-drop`, H2 콘솔 ON | `spring.profiles.active=dev` (기본값) |
| `application-prod.properties` | 운영: MySQL, `ddl-auto=validate`, Flyway ON | `spring.profiles.active=prod` (배포 시 명시) |

초안 파일 작성됨 (실제 값 없음, 전부 `${…}` 플레이스홀더):
- `backend/src/main/resources/application-dev.properties` — 현재 dev 설정을 그대로 이전
- `backend/src/main/resources/application-prod.properties` — 아래 골격

```properties
spring.profiles.active=prod
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.datasource.hikari.maximum-pool-size=${DB_POOL_SIZE:20}
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.h2.console.enabled=false
```

원칙:
- prod 파일에 실제 값(호스트·계정·비밀번호)을 **절대 커밋하지 않음**. 전부 환경변수 플레이스홀더
- `ddl-auto=validate` 고정 (운영에서 스키마 자동 변경 금지). 스키마 변경은 Flyway V파일로만
- H2 콘솔·`show-sql`은 prod에서 OFF
- `spring.profiles.active` 기본값은 `dev` 유지 — prod 전환은 배포 파이프라인에서만 명시

### 4-2.2 비밀키 관리 방안 (실제 값 다루지 않음 — 방안만)

관리 대상 비밀: ① DB 접속 정보(URL/계정/비밀번호) ② 인증키 생성 시크릿(인증키 발급 시 서명·솔트 용도) ③ 외부 API 키(향후 실제 알림 발송 연동 시: SMS/알림톡)

| 방식 | 장점 | 단점 | 권장 |
|------|------|------|------|
| **환경변수** (OS/컨테이너) | 구현 단순, 12-factor 표준, 코드 변경 없음 | 프로세스 목록·덤프에 노출 가능, 회전(로테이션) 시 재배포 필요 | **1순위 (현재 규모)** |
| **시크릿 매니저** (AWS Secrets Manager 등) | 자동 회전, 접근 감사, 버전 관리 | 유료·운영 복잡도 증가, SDK 연동 필요 | 학원 수·트래픽 확대 후 검토 |
| 설정 파일 암호화 (Jasypt 등) | 파일로 버전 관리 가능 | 마스터키를 또 보관해야 함(닭-달걀), 키 유출 시 전수 교체 | 비권장 |

운영 방안 (초안):
1. 비밀은 **환경변수로만 주입** (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REA_AUTHKEY_SECRET`, 향후 `SMS_API_KEY` 등). `application-prod.properties`는 `${…}` 참조만
2. 환경변수 설정 주체는 배포 담당자(사용자)가 서버/OS 레벨에서 직접 — 저장소에 기록 금지, 채팅·문서에 값 기재 금지
3. 인증키 생성 시크릿: 최소 32바이트 랜덤. 유출 시 기존 발급 키 전체 재발급 절차 필요 → 시크릿 회전 runbook을 장애 복구 문서와 함께 보관
4. DB 비밀번호: 90일 주기 교체 권장 (교체 시 무중단 반영을 위해 커넥션 풀 drain 절차 포함)
5. 접근 통제: 운영 서버 SSH·환경변수 조회 권한은 최소 인원으로 제한. 누가 언제 조회했는지 기록
6. 시크릿 매니저 도입은 **별도 승인 후**: 도입 시에도 환경변수 인터페이스는 유지하고 주입원만 교체 (코드 변경 최소화)

---

## 4-3. 장애 복구 절차

> 전제: 운영 배포 자체가 아직 승인되지 않았으므로 아래는 **사전 runbook 초안**. 실제 운영 개시 전 리허설 필요.

### 시나리오 A — DB 장애

**A-1. 연결 실패 (커넥션 풀 고갈·DB 프로세스 다운·네트워크 단절)**

- 탐지: API 전체 5xx 급증, HikariCP `Connection is not available` 로그, 헬스체크(`/actuator/health` 도입 시) DOWN. 알림 기준: 1분간 DB 에러율 > 1%
- 임시 조치:
  1. 트래픽이 있으면 LB에서 해당 인스턴스 제외 (단일 인스턴스면 점검 페이지 전환)
  2. 앱 재시작 금지 (재시작해도 DB가 죽어 있으면 동일). 먼저 DB 상태 확인
  3. 읽기 전용 저하 모드 전환 검토 (신규 쓰기 차단 안내 문구 노출)
- 복구 절차:
  1. DB 프로세스/인스턴스 상태 확인 → 재시작 또는 장애조치(failover)
  2. `SELECT 1` 수준의 연결 확인 후 앱의 Hikari 풀 상태 확인 (연결 복구되면 풀은 자동 회복)
  3. 장시간 복구 불가 시 최근 백업으로 신규 인스턴스 복원 → 앱의 `DB_URL`을 새 엔드포인트로 교체 (환경변수 변경 후 앱 재시작)
  4. `flyway_schema_history`의 `success` 플래그로 스키마 정합성 확인
- 사후 검증: 전체 API 스모크 (로그인→학생 목록→출결 기록), `COUNT(*)` 주요 테이블 대조, 감사 로그(`rea_audit_logs`) 연속성 확인

**A-2. 데이터 손상 (잘못된 마이그레이션·애플리케이션 버그로 오염)**

- 탐지: 정합성 점검 쿼리 (고아 레코드 수, `active` 비정상 분포, 음수 점수 등) 일일 배치 리포트로 감지
- 임시 조치: 오염 범위 확정 전까지 해당 기능 쓰기 중단 (예: 출결 정정 API 일시 차단)
- 복구 절차:
  1. 오염 범위·시점 특정 (감사 로그 `rea_audit_logs`의 `action_type`·`created_at`으로 추적)
  2. **데이터 복구는 DELETE가 아니라 정정 마이그레이션(V파일)으로만** — 삭제된 것처럼 보여야 하면 `active=false`
  3. 백업에서 특정 테이블만 복원해야 할 경우: 복원 → 오염 구간만 애플리케이션 레벨에서 재적용 (직접 UPDATE 금지, 서비스 API 경유)
  4. 감사 로그는 손대지 않음 (복구 작업 자체도 감사 로그에 기록됨)
- 사후 검증: 오염 전후 `COUNT(*)`·체크섬 대조, E2E 시나리오 A/B/C/D 재실행

### 시나리오 B — API 서버 장애

**B-1. 프로세스 다운 (크래시·배포 실패)**

- 탐지: 헬스체크 실패, 포트 미응답, 프로세스 부재. 알림: 30초 연속 실패 시
- 임시 조치: LB에서 제외 → 예비 인스턴스로 트래픽 전환 (단일 인스턴스면 점검 안내)
- 복구 절차:
  1. 마지막 로그 200줄 확인 — OOM인지, DB 단절인지, 배포 직후인지 구분
  2. systemd/컨테이너 자동 재시작 정책 확인 (Restart=always 권장)
  3. 배포 직후 장애면 **이전 버전으로 롤백** (JAR 교체 + 재시작, DB는 건드리지 않음 — DB 롤백과 앱 롤백은 분리)
  4. 재시작 후 `/students` 등 핵심 API 스모크
- 사후 검증: 에러율·응답시간 대시보드 정상화, 감사 로그 연속성

**B-2. OOM (메모리 부족)**

- 탐지: `java.lang.OutOfMemoryError` 로그, GC 시간 급증, 응답시간 p95 급등
- 임시 조치: 즉시 재시작 (힙 덤프 확보 후). `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/rea/` 권장
- 복구 절차:
  1. 힙 덤프 분석 (Eclipse MAT 등) — 누수 지점 특정. 월별 출결 N+1(6-1 발견, 15,500 쿼리)이 대량 조회 시 메모리 압박 요인이 될 수 있음 → 최적화 전까지 대량 조회는 페이지네이션 적용
  2. 힙 사이즈(`-Xmx`)는 서버 메모리의 50~70% 범위에서 조정, 컨테이너면 limit과 정합
  3. 동일 OOM 재발 시 원인 패치 전까지 야간 배치·대량 조회 시간대 분산
- 사후 검증: 24시간 GC 로그 모니터링, p95 응답시간 기준치 복귀

### 시나리오 C — 디스크 용량 부족

- 탐지: 디스크 사용률 알림 (80% 경고 / 90% 긴급), DB `Disk full` 에러, 로그 write 실패
- 임시 조치:
  1. 로그 로테이션 즉시 실행 (오래된 로그 압축·삭제 — 단, 장애 조사 중인 로그는 보존)
  2. DB 바이너리 로그·슬로우 쿼리 로그 정리 (운영 정책 범위 내)
  3. 95% 이상이면 쓰기 트래픽 일시 차단 (데이터 손실 방지)
- 복구 절차:
  1. 용량 점유 상위 디렉토리 특정 (`du -sh`) — 로그/백업/업로드(첨부파일) 중 범인 구분
  2. 로그 보관 정책 확정 (예: 30일, 압축 보관) 후 logrotate 적용
  3. 첨부파일(`attachment_file_key`, `photo_key`)은 파일 스토리지 분리 검토 (DB 서버 디스크와 분리)
  4. 근본 해결: 볼륨 확장 또는 보관 정책 변경
- 사후 검증: 사용률 70% 이하 복귀, 일일 사용률 추이 그래프 확인

### 공통 — 백업 정책 (초안)

| 대상 | 주기 | 보관 | 비고 |
|------|------|------|------|
| MySQL 전체 (`mysqldump` 또는 스냅샷) | 매일 새벽 | 7일 (일) + 월 1회 6개월 | 복원 리허설 분기 1회 |
| `flyway_schema_history` | 백업에 포함 | — | 스키마 버전 추적 |
| 애플리케이션 JAR·설정 | 배포 시점 | 최근 3개 버전 | 롤백용 |
| 환경변수·시크릿 목록 (값 제외) | 변경 시 | 버전 관리 | 값 자체는 백업 암호화 |

---

## 4-4. 로그·오류·응답시간

### 4-4.1 주요 API 응답시간 측정 (H2 기준 참고치)

- 측정 방식: MockMvc(HTTP 스택 포함) 100회 반복 → p50/p95 산출
- 측정 테스트: `backend/src/test/java/com/rea/academy/perf/PerfReferenceTest.java` (신규 3건)
- 데이터 규모: 학생 300명, 출결 4,500건(15일×300명), 성적 1,000건(200명×5과목), 반 숙제 5건+검사 5건+진도 10건
- 목표: 화면 응답 3,000ms 이내 (6-1 기준)

| API | 데이터 | p50 | p95 | 판정 |
|-----|--------|-----|-----|------|
| `GET /api/rea/{a}/students` (학생 목록) | 300건 | 4.7ms | 25.4ms | PASS |
| `GET /api/rea/{a}/grades/student/{s}` (성적 조회) | 5건 | 1.9ms | 4.6ms | PASS |
| `GET /api/rea/{a}/reports/student/{s}` (리포트 생성) | 성적+숙제+진도+출결 집계 | 5.2ms | 27.3ms | PASS |

- 위 수치는 전체 스위트 실행 내 측정값. 단독 실행 시에도 동일 규모 (p50 5~7ms, p95 30~36ms) — 실행 간 편차는 JVM 웜업·GC 영향

- 월별 출결(`GET /attendance/monthly`)은 6-1에서 N+1(500명×31일=15,500 쿼리, 약 51초)로 이미 문서화되어 **재측정 제외**. 백엔드 최적화(월 범위 일괄 조회)는 별도 승인 후 진행, 승인 시 본 측정에 3초 assertion으로 복원
- 참고: H2 인메모리 기준이므로 운영 MySQL·네트워크 환경에서는 별도 측정 필요. p95가 p50의 4~6배로 튀는 것은 JVM 웜업·GC 영향 — 운영에서는 워밍업 후 측정 권장

### 4-4.2 오류 로그 형식 점검

**현재 상태 (실측):**

1. `ApiExceptionHandler` — 401/403/400/409를 `{"message": "…"}` JSON으로 변환. **요청 ID·trace ID 없음, 스택트레이스 기록 없음**
2. `AuthKeyFilter.reject` — 401을 같은 `{"message"}` 형식으로 직접 기록. 필터 단계라 `@RestControllerAdvice`를 타지 않음
3. 매핑되지 않은 예외(5xx)는 Spring Boot 기본 처리 — 별도 로깅 없음
4. `logback-spring.xml` 없음 → Spring Boot 기본 콘솔 패턴 사용 (JSON 아님, MDC 없음)
5. 애플리케이션 로그는 총 4곳:
   - `AttendanceService`: `log.warn("출결 알림 발송 실패 … academyId={}, studentId={}, type={}")` — ID만, PII 없음 ✓
   - `AuditLogAspect`: `log.warn("감사 로그 기록 실패 … {}", e.getMessage())` — 메시지 일부 노출 가능하나 PII 아님 ✓
   - `MockNotificationSender`: 아래 발견 이슈 참고

**발견 이슈 1 (PII 로그 — 수정 권장, 본 작업에서 코드 미변경):**
- `MockNotificationSender.send()`가 `log.info("[MOCK-알림] channel={} to={}({}) title={} content={}", channel, recipientName, recipientPhone, title, content)` 로 **수신자 이름·전화번호·알림 제목·본문**을 INFO 레벨에 기록
- 현재 Mock(실제 발송 없음)이지만, 운영에서 Mock이 활성화되거나 로그가 외부로 수집되면 개인정보 유출. 권장: `recipientName` 마스킹(이름 일부), `recipientPhone` 마스킹(뒤 4자리 제외), `content`는 길이만 기록

**발견 이슈 2 (운영 관찰성 부족 — 개선 권장):**
- 5xx 발생 시 스택트레이스가 어디에도 기록되지 않아 장애 원인 추적 불가. 권장: `ApiExceptionHandler`에 5xx 핸들러 추가 + `log.error("…", e)` (스택트레이스 포함). 단, 응답 본문에는 스택트레이스를 포함하지 말 것
- 요청 ID 부재: MDC(`requestId`)를 필터에서 발급 → 로그 패턴에 포함 → 장애 시 요청 단위 추적. `application-prod.properties`에 권장 패턴 주석으로 추가함
- 운영 로그는 JSON 형식(`logstash-logback-encoder`) 도입 검토 — 로그 수집기 연동 시

**개인정보 로그 점검 결론:** 위 Mock 1건을 제외하고 서비스·감사·인증 경로에서 이름·전화번호·인증키가 로그에 남지 않음을 확인. 인증키(`authKey`)는 어떤 로그에도 기록되지 않음 ✓

---

## 4-5. GitHub Actions 점검

### 점검 결과: **워크플로우 없음**

- `~/workspace/rea-academy/`에 `.github/` 디렉토리 자체가 없음. 워크플로우 파일 0개
- 사용자 지시: "GitHub Actions는 필요한 CI만 실행하고 불필요한 반복 E2E는 금지"

### 발견 이슈 — 단일 JVM 전체 실행 시 14건 실패 (기존 테스트 격리 문제)

본 작업에서 전체 스위트를 단일 JVM으로 실행하자 318 중 14건이 실패하는 것을 발견. **본 작업 변경(신규 테스트·프로파일 초안·문서)과 무관**함을 확인 — 신규 테스트를 제외한 기존 21개 클래스만으로 실행해도 동일한 14건 실패 (301/315).

- 실패 내역: `IsolationMatrixTest` 11건 + `DataIntegrityTest` 3건, 전부 `setUp`/`seedOwner`에서 `IllegalStateException: 이미 원장이 등록되어 있습니다.` (`MemberService.createMember`, 학원당 원장 1명 제한)
- 원인: 모든 테스트 클래스가 동일한 Spring 컨텍스트 + 동일한 이름의 H2 인메모리 DB(`rea_academy`)를 공유. `BulkDataVerificationTest`(PER_CLASS)의 `@BeforeAll buildBulkData()`는 테스트 트랜잭션 범위 밖이라 원장(학원 1) 시드 데이터가 **커밋**되고, 이후 실행되는 클래스들의 원장 시드와 충돌. 클래스별 단독 실행 시에는 전원 PASS (SecurityTest 38/38, IsolationMatrixTest 11/11 단독 확인)
- CI 설계 시사점: 단일 JVM 일괄 실행을 CI 기본으로 삼으면 거짓 실패 발생. 권장 해결책 (별도 승인 후):
  1. `BulkDataVerificationTest`의 `@BeforeAll` 시드를 `@BeforeEach` + 롤백 방식으로 변경, 또는
  2. CI에서 클래스별 JVM 분리 실행 (`forkCount` 방식), 또는
  3. 시드용 학원 ID를 클래스마다 분리 (본 작업의 `PerfReferenceTest`는 학원 9를 사용해 이 충돌을 회피 — 22개 클래스 일괄 실행에서 3/3 PASS 확인)
- 본 작업에서는 기존 테스트를 수정하지 않고 신규 테스트만 충돌 회피 처리함 (범위 준수)

### 향후 CI 추가 시 권장사항

1. **필요한 CI만**: `build` (javac 컴파일) + `test` (JUnit 스위트). 현재 `.testbuild`의 javac 직접 컴파일 방식을 CI에서도 재사용 가능
2. **금지**: E2E·브라우저 테스트의 반복 실행 (Playwright 등). E2E는 수동 트리거(`workflow_dispatch`)로만, PR 자동 실행에 포함하지 않음
3. **트리거 범위 제한**: `paths` 필터로 `backend/**` 변경 시에만 실행. `docs/**`, `frontend/**` 단독 변경에는 CI 미실행 (불필요한 실행 방지)
4. **중복 실행 취소**: `concurrency: cancel-in-progress: true`로 같은 브랜치의 이전 실행 자동 취소
5. **운영 배포 워크플로우는 별도 승인 전까지 추가 금지**. 배포는 수동 승인 게이트 필수
6. 위 테스트 격리 이슈를 먼저 해결한 뒤 CI에 전체 스위트를 올릴 것 (미해결 시 클래스별 분리 실행으로 우회)
7. AGENTS.md의 "GitHub Actions 운영 원칙" (실행 최소화·임시 워크플로우 정리·비용 최적화 경계) 준수

---

## 산출물 목록

| # | 산출물 | 경로 |
|---|--------|------|
| 1 | 본 문서 | `docs/phase6-task4-ops.md` |
| 2 | Flyway V1 DDL 초안 (22개 테이블, 인덱스·논리FK 정리) | `db/migration/V1__rea_initial.sql` |
| 3 | 개발 프로파일 초안 (H2) | `backend/src/main/resources/application-dev.properties` |
| 4 | 운영 프로파일 초안 (MySQL, 플레이스홀더만) | `backend/src/main/resources/application-prod.properties` |
| 5 | 응답시간 측정 테스트 (100회×3종, p50/p95) | `backend/src/test/java/com/rea/academy/perf/PerfReferenceTest.java` (`.testbuild/test-sources.txt` 등록) |

## 준수 사항 확인

- 운영 DB 실제 적용 없음 (DDL·프로파일 전부 초안, 플레이스홀더만) ✓
- 실제 비밀값 다룸 없음 ✓
- 운영 배포 없음 ✓
- `~/workspace/wemade-reorg/` 미접촉 ✓
- EASY 코드 미접촉 ✓
- 외부 메시지 발송 없음 ✓
- 유료 API/LLM 미사용 ✓
- 비투 자산 복제 없음 ✓
- 대규모 기능 추가 없음 (측정용 테스트 1개·설정 초안 2개·문서만) ✓
