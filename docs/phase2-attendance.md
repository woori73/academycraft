# REA 학원관리 2단계 — 출결 관리 구현 기록

> 작성일: 2026-10-13
> 범위: 출결 이벤트 기록(키패드 자동 판별·강사 직접 기록)·반별 출결부·학생별 이력·월별 현황·출결 정정·출결 설정 (수납·결제·알림 발송 제외)
> 기준: `docs/functional-spec-v1.md` §3, 비투 공개 동작(키패드 출결·등하원 알림 설정·하원 가능 시간·사진 전송 옵션)만 참고하여 독립 코드 작성

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 출결 기록 | 등원(CHECK_IN)·하원(CHECK_OUT)·결석(ABSENT)·지각(LATE)·조퇴(EARLY_LEAVE) 5종. 강사가 studentId 또는 authKey로 직접 기록 (발생 일시 지정 가능, 메모 가능) |
| 키패드 자동 판별 | 학생이 출석키(= 학생 인증키) 입력 → 당일 첫 기록=등원, 이후=하원. 당일 등·하원 완료 후 입력 시 409 |
| 하원 가능 시간 | 설정의 시작 시각 이전에는 CHECK_OUT/EARLY_LEAVE 기록 거부 (409) |
| 반별 출결부 | 반·일자별 전체 학생 상태 목록 (등원/하원완료/결석/지각/조퇴/미기록) + 등·하원 시각 |
| 학생별 이력 | 기간별 출결 기록 (기본 최근 30일), 정정 이력 포함 조회 가능 |
| 월별 현황 | 반별 일자별 상태 코드 + 출석·결석·지각·조퇴일수 집계 |
| 출결 정정 | 기록 수정 + 정정 전·후 값을 정정 이력 테이블에 보존 (사유 필수) |
| 출결 설정 | 하원 가능 시간대·출결 사진 전송 여부·등/하원 알림 문구 ({name} 자리표시자). 2단계는 설정값 저장까지 |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 타 학원 학생·반·설정 접근 시 400 |
| 알림 훅 포인트 | 출결 기록·키패드·정정 지점에 `[알림 연동 포인트 - 3단계]` 주석으로 표시. 실제 발송 금지 (2단계) |

### 검증 규칙
- eventType: `CHECK_IN/CHECK_OUT/ABSENT/LATE/EARLY_LEAVE` (대소문자 무시)
- 정정 사유 필수. 정정 시 eventType·occurredAt·note 선택 변경
- 하원 가능 시작 시각 < 종료 시각 강제 (둘 다 설정 시)
- 시각 형식 `HH:mm`, 일자 `yyyy-MM-dd`, 일시 `yyyy-MM-dd'T'HH:mm`, 월 `yyyy-MM`
- 기록 시점 소속 반 ID를 이벤트에 스냅샷으로 저장 (classId, nullable)

## 2. 파일 목록

### Backend (신규 — 기존 1차·1단계 코드 수정 없음)
| 파일 | 역할 |
|---|---|
| `domain/attendance/AttendanceEventType.java` | 출결 구분 enum + parse |
| `domain/attendance/AttendanceSource.java` | 기록 경로 enum (KEYPAD/MANUAL) |
| `domain/attendance/ReaAttendanceEvent.java` | 출결 이벤트 엔티티 (`rea_attendance_events`) |
| `domain/attendance/ReaAttendanceCorrection.java` | 정정 이력 엔티티 (`rea_attendance_corrections`) |
| `domain/attendance/ReaAttendanceSetting.java` | 출결 설정 엔티티 (`rea_attendance_settings`, 학원별 1행) |
| `domain/attendance/ReaAttendanceEventRepository.java` | 이벤트 Repository |
| `domain/attendance/ReaAttendanceCorrectionRepository.java` | 정정 이력 Repository |
| `domain/attendance/ReaAttendanceSettingRepository.java` | 설정 Repository |
| `dto/AttendanceDto.java` | Check/Keypad/Roster/Monthly/Settings/Correct DTO |
| `service/AttendanceService.java` | 출결 업무 로직 (DTO 입출력, UI 의존 없음) |
| `controller/AttendanceController.java` | REST API (HTTP 입출력만) |
| `src/test/.../service/AttendanceServiceTest.java` | H2 인메모리 서비스 테스트 16건 |

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `ATT-01-attendance-keypad.html` | 출결 입력 (키패드): 숫자 키패드 + 직접 타이핑, 등원/하원 자동 판별 결과 표시 |
| `ATT-02-attendance-monthly.html` | 월별 출결 현황: 달력 형태 집계표 + 출석/결석/지각/조퇴일수, 프린트 버튼 |
| `ATT-03-attendance-roster.html` | 반별 출결부: 일자별 상태·등하원 시각, 결석/지각/등원 빠른 기록, 정정 |
| `ATT-04-attendance-settings.html` | 출결 설정: 하원 가능 시간대·사진 전송 ON/OFF·알림 문구 |

- 데스크톱·모바일 반응형 (`@media` 분기). MBR-01·CLS-01과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/{academyId}/attendance/check` | 출결 기록 `{studentId 또는 authKey, eventType, occurredAt?, note?}` |
| POST | `/api/rea/{academyId}/attendance/keypad` | 키패드 입력 `{authKey}` → 등원/하원 자동 판별 |
| GET | `/api/rea/{academyId}/attendance/roster?classId=&date=` | 반별 출결부 |
| GET | `/api/rea/{academyId}/attendance/student/{studentId}?from=&to=` | 학생별 이력 |
| GET | `/api/rea/{academyId}/attendance/monthly?classId=&yearMonth=` | 월별 현황 |
| PUT | `/api/rea/{academyId}/attendance/{eventId}` | 출결 정정 `{eventType?, occurredAt?, note?, reason(필수)}` |
| GET | `/api/rea/{academyId}/attendance/settings` | 출결 설정 조회 (미설정 시 기본값) |
| PUT | `/api/rea/{academyId}/attendance/settings` | 출결 설정 변경 (upsert) |

예외 매핑은 기존 `ApiExceptionHandler` 재사용: `IllegalArgumentException` → 400, `IllegalStateException` → 409.

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, 커스텀 JUnit 엔진 러너)
`AttendanceServiceTest` — **16건 전부 PASS**
- 키패드: 첫 입력=등원 / 두 번째=하원 / 세 번째=409 / 무효 키=400
- 직접 기록: 결석(사유) / 인증키로 기록 / 하원 가능 시간 전 하원=409
- 반별 출결부: 2명 상태 판정 (PRESENT/ABSENT), 이름순 정렬
- 학생별 이력: 기간 필터 + 발생 일시순
- 월별 현황: 일자별 상태 코드 + 출석4/결석1/지각1/조퇴1 집계
- 정정: 기록 수정 + 정정 이력 1건 보존 / 사유 없으면 400
- 설정: 기본값 조회 + 변경 저장
- 학원 격리: 타 학원 학생 기록 400, 타 학원 키패드 400, 타 학원 반 출결부 400
- 회귀: 1차 학생 등록·1단계 반 생성·배정·출결 연동

`ClassServiceTest` (1단계, 기존) — **15건 전부 PASS** (회귀 확인)

### 4.2 빌드 환경 메모
- `mvn` 온라인 빌드 불가 (egress 프록시). 오프라인 `mvn -o`도 실패: (1) Maven이 root로 실행되어 기본 로컬 저장소가 `/root/.m2`가 됨 → `-Dmaven.repo.local=/home/hatch/.m2/repository` 필요, (2) 캐시에 빌드 플러그인(maven-compiler/surefire/spring-boot-maven-plugin) jar 자체가 없음 → Maven 빌드 불가.
- 대체: `javac` 직접 컴파일 (의존성 98개 jar, 중복 버전은 최신만 사용) + `junit-platform-launcher` 미캐시 → JUnit Jupiter 엔진을 직접 구동하는 최소 러너(`/tmp/rea-build/TestRunner.java`) 작성. `@SpringBootTest` + H2 컨텍스트 정상 기동 확인.
- 인코딩: `javac -encoding UTF-8` 필수 (한글 주석).

### 기존 코드 변경
- 없음 (1차 Member·1단계 Class 코드 미수정 — 회귀 테스트로만 검증)
