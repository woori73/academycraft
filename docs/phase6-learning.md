# REA 학원관리 6단계 — 학습 관리 구현 기록

> 작성일: 2026-10-13
> 범위: 숙제 등록·검사·안내, 학습 진도 기록·조회, 시험·성적 등록·조회, 학습 리포트 데이터 API, 내신Wiki 연동 구조(스텁) (수납·결제 제외)
> 기준: `docs/functional-spec-v1.md` §7, 비투 공개 동작(스크립트 성적 발송·진도·숙제 검사 등록·아이소식 1:1 전달)만 참고하여 독립 코드 작성

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 숙제 등록 | 강사가 반별(classId)·학생별(studentId) 숙제 등록. 제목·내용·마감일(yyyy-MM-dd) 필수. 내신Wiki 문항 참조(questionRefs) 선택. 등록 시 대상 학부모에게 HOMEWORK 알림 발송 (실패 격리) |
| 숙제 검사 | 강사가 (숙제, 학생)별 검사 결과 입력 — 완료/미완료 + 선택 점수·코멘트. 재검사 시 upsert 갱신 |
| 숙제 안내 | 학부모가 자녀(학생ID·인증키)의 숙제 목록 + 검사 상태(완료/미완료/미검사·점수·코멘트) 조회 |
| 진도 기록 | 강사가 학생별 단원·진도율(0~100)·메모·기록일자 기록 |
| 진도 조회 | 학생별 진도 기록일자 최신순 조회 |
| 성적 등록 | 강사가 시험명·과목·점수·만점·시험일자 입력. 검증: 0 ≤ 점수 ≤ 만점, 만점 ≥ 1. 등록 시 학부모에게 GRADE 알림 발송 (실패 격리) |
| 성적 조회 | 학생별 성적 이력 (시험일자 최신순, 백분율 포함) |
| 학습 리포트 | 학생별 종합 데이터 API — 성적(건수·전체/과목별 평균·최근 시험명)·숙제(배정·검사·완료·미완료·미검사)·진도(건수·최근 5건)·출결(최근 30일 등원/결석/지각/조퇴 일수) |
| 내신Wiki 연동 구조 | `WikiQuestionRef` 값 객체(문항ID·출처) + `WikiIntegrationPort` 인터페이스 + `StubWikiIntegrationPort` 스텁. 실제 연동은 향후. 내신Wiki 코드 수정 없음 |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 타 학원 학생·반·숙제 접근 시 400 |

### 알림 연동
- 숙제 등록 → `NotificationService.sendToStudent`(HOMEWORK) — 반별이면 반 소속 활성 학생 전원에게 개별 발송 (수신 거부 OFF 학부모는 생략)
- 성적 등록 → `NotificationService.sendToStudent`(GRADE) — 해당 학생 학부모 1명
- 발송 실패는 격리: 숙제·성적 등록 자체는 유지되고 응답에 `notificationError`로 전달
- 실제 외부 발송 없음 (`MockNotificationSender`)

## 2. 파일 목록

### Backend (신규 — 기존 1차·1~5단계 코드 수정 없음)
| 파일 | 역할 |
|---|---|
| `domain/learning/HomeworkCheckStatus.java` | 검사 결과 enum (COMPLETED/INCOMPLETE) + parse |
| `domain/learning/WikiQuestionRef.java` | 내신Wiki 문항 참조 값 객체 (@Embeddable: questionId, source) |
| `domain/learning/WikiIntegrationPort.java` | 내신Wiki 연동 포트 인터페이스 (향후 구현용) |
| `domain/learning/StubWikiIntegrationPort.java` | 연동 포트 스텁 (미연동: 빈 값 반환, Bean 미등록) |
| `domain/learning/ReaHomework.java` | 숙제 엔티티 (`rea_homework` + `rea_homework_question_refs`) |
| `domain/learning/ReaHomeworkRepository.java` | 숙제 Repository |
| `domain/learning/ReaHomeworkCheck.java` | 검사 결과 엔티티 (`rea_homework_checks`, (homework_id, student_id) unique) |
| `domain/learning/ReaHomeworkCheckRepository.java` | 검사 결과 Repository |
| `domain/learning/ReaProgress.java` | 진도 엔티티 (`rea_progress`) |
| `domain/learning/ReaProgressRepository.java` | 진도 Repository |
| `domain/learning/ReaGrade.java` | 성적 엔티티 (`rea_grades`, 백분율 `rate()` 파생 계산) |
| `domain/learning/ReaGradeRepository.java` | 성적 Repository |
| `dto/LearningDto.java` | Homework/Progress/Grade/Report DTO |
| `service/LearningService.java` | 학습 관리 업무 로직 (DTO 입출력, UI 의존 없음) |
| `controller/LearningController.java` | REST API (HTTP 입출력만) |
| `src/test/.../service/LearningServiceTest.java` | H2 인메모리 서비스 테스트 18건 |

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `GRD-01-grade-form.html` | 성적 등록: 학생·시험명·과목·점수·만점·시험일자 입력 |
| `GRD-02-grade-list.html` | 성적 조회: 학생별 성적 이력 + 건수·평균 요약 |
| `HW-01-homework-form.html` | 숙제 등록: 반/학생 선택·제목·내용·마감일·문항 참조 |
| `HW-02-homework-check.html` | 숙제 검사: 숙제 선택 → 검사 결과 목록 + 학생별 검사 입력 |
| `HW-03-homework-list.html` | 숙제 안내: 자녀 숙제 목록·검사 상태·선생님 코멘트 |
| `RPT-01-learning-report.html` | 학습 리포트: 성적·숙제·진도·출결 종합 + 프린트 |

- 데스크톱·모바일 반응형 (`@media` 분기). 기존 화면과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/{academyId}/homework` | 숙제 등록 `{classId?, studentId?, title, content, dueDate, questionRefs?}` → HOMEWORK 알림 |
| GET | `/api/rea/{academyId}/homework?classId=&studentId=&authKey=` | 숙제 목록 (학생 조회 시 검사 상태 포함) |
| POST | `/api/rea/{academyId}/homework/{id}/check` | 숙제 검사 `{studentId, status, score?, comment?}` |
| GET | `/api/rea/{academyId}/homework/{id}/checks` | 숙제별 검사 결과 목록 |
| POST | `/api/rea/{academyId}/progress` | 진도 기록 `{studentId, unit, progressRate, memo?, recordedDate?}` |
| GET | `/api/rea/{academyId}/progress/student/{studentId}?authKey=` | 학생별 진도 조회 |
| POST | `/api/rea/{academyId}/grades` | 성적 등록 `{studentId, examName, subject, score, fullScore, examDate?, note?}` → GRADE 알림 |
| GET | `/api/rea/{academyId}/grades/student/{studentId}?authKey=` | 학생별 성적 이력 |
| GET | `/api/rea/{academyId}/reports/student/{studentId}?authKey=` | 학습 리포트 데이터 |

예외 매핑은 기존 `ApiExceptionHandler` 재사용: `IllegalArgumentException` → 400.

### 검증 규칙
- 숙제: 반·학생 중 최소 하나 필수, 제목·내용·마감일(yyyy-MM-dd) 필수
- 검사: `COMPLETED/INCOMPLETE` (대소문자 무시), 점수 ≥ 0
- 진도: 단원 필수, 진도율 0~100
- 성적: 시험명·과목 필수, 0 ≤ 점수 ≤ 만점, 만점 ≥ 1, 시험일자 yyyy-MM-dd (미입력 시 오늘)

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, 커스텀 JUnit 엔진 러너)
`LearningServiceTest` — **18건 전부 PASS**
- 숙제: 반별 등록→소속 학생 2명에게 HOMEWORK 알림 / 개별 등록→1명에게만 / 반·학생 미지정 400 / 문항 참조 2건 저장
- 검사: 완료+점수 저장 → 학생 목록에 상태 반영 / 재검사 upsert (1행 유지) / 잘못된 상태값·음수 점수 400 / 인증키로 자녀 숙제 조회
- 진도: 기록→최신순 조회 / 진도율 101·-1 → 400
- 성적: 등록→GRADE 알림 1건·미읽음 1건 / 점수>만점·만점<1·음수 → 400 / 시험일자 최신순 이력 / 인증키로 자녀 성적 조회
- 리포트: 성적 3건(평균 80.0·수학 75.0)·숙제(배정2·완료1·미검사1)·진도1건·출결(등원1일) 집계 / 빈 학생은 빈 요약
- 학원 격리: 타 학원 학생·반으로 숙제/성적/진도/리포트 접근 시 400, 타 학원 목록 비어 있음
- 회귀: 1차 학생 등록·1단계 반 배정·2단계 출결 기록·6단계 연동

### 4.2 전체 회귀
6개 테스트 클래스 **100건 전부 PASS** (Learning 18 + Attendance 16 + Class 15 + Consultation + Notice + Notification)

### 4.3 빌드 환경 메모
- 기존 방식 그대로: `javac -encoding UTF-8` 직접 컴파일 + `/tmp/rea-build/TestRunner.java` 구동. 추가 의존성 없음.

### 기존 코드 변경
- 없음 (1차 Member·1~5단계 코드 미수정 — 회귀 테스트로만 검증)

## 5. 남은 사항 (향후 단계)
- 내신Wiki 실제 연동: `WikiIntegrationPort` 구현체 작성 (문항 조회·오답노트·시험범위 데이터). 내신Wiki 도메인 코드는 수정하지 않고 참조만.
- Report v2.8 HTML UI 고도화: 6단계는 데이터 API까지만 구현. v2.8을 `GET /reports/student/{id}` 응답 바인딩으로 교체하는 작업은 별도.
- 숙제·성적 알림 문구 스크립트(정형 양식) 고도화 (비투 스크립트 방식 참고).
