# REA 학원관리 7단계 — 운영 관리 구현 기록

> 작성일: 2026-10-13
> 범위: 일정·캘린더, 강사 담당 업무, 통합 검색, 통계 대시보드, CSV(엑셀 호환) 가져오기·내보내기 (수납·결제 제외)
> 기준: `docs/functional-spec-v1.md` (운영 관리 7단계), 비투 공개 동작만 참고하여 독립 코드 작성

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 일정 등록 | 제목·일자(필수), 시작/종료 시각(선택, HH:mm), 대상(전체/반별), 메모. 반별이면 반 지정 필수 |
| 상담 일정 | 5단계 상담 스레드와 선택적 연결 (`consultationId`) — 상담 약속 일정 등록 |
| 월별 캘린더 조회 | `?yearMonth=yyyy-MM` — 일자·시각 순 정렬 |
| 일정 수정·삭제 | PUT(지정 항목만 변경, CLASS→ALL 전환 시 반 연결 초기화), DELETE |
| 강사 담당 현황 | 기존 반(rea_classes)의 teacherId 기준 담당 반 + 반별 학생 수 + 담당 업무 메모 |
| 담당 업무 메모 | 강사별 1행 upsert (`rea_teacher_assignments`) |
| 통합 검색 | 학생 이름으로 학생·출결·성적·숙제·상담 횡단 검색 + 상담/공지 제목 일치 포함 (섹션별 최대 20건) |
| 통계 대시보드 | 활성 학생 수·활성 반 수·오늘 출결 현황(등원/하원/결석/지각/조퇴/기록 학생 수)·미답변(OPEN) 상담 수·이번 달 공지 수 |
| 학생 CSV 내보내기 | 이름,학년,학교,인증키,학부모명,학부모연락처,반,등록일 (UTF-8 BOM + CRLF, 엑셀 호환) |
| 학생 CSV 가져오기 | 열 순서: 이름,학년,반,학부모명,연락처. 헤더 행 선택, 인증키 자동 발급, 반 이름 일치 시 배정. 이름 빈 행 실패, 없는 반은 경고(등록은 유지) |
| 출결 CSV 내보내기 | 반별(classId 선택)·월별(yearMonth 필수). 열: 일자,이름,반,구분,발생시각,메모 |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 타 학원 일정·강사·검색·통계 접근 불가 |

## 2. 파일 목록

### Backend (신규 — 기존 1~6단계 코드 수정 없음)
| 파일 | 역할 |
|---|---|
| `domain/ops/ScheduleTargetType.java` | 일정 대상 enum (ALL/CLASS + parse + 라벨) |
| `domain/ops/ReaSchedule.java` | 일정 엔티티 (`rea_schedules`, 상담 연결 consultationId) |
| `domain/ops/ReaScheduleRepository.java` | 월별 조회·상담 연결 조회·제목 검색 |
| `domain/ops/ReaTeacherAssignment.java` | 강사 담당 업무 메모 엔티티 (`rea_teacher_assignments`, (academyId, teacherId) unique) |
| `domain/ops/ReaTeacherAssignmentRepository.java` | 강사별 메모 1건 조회 |
| `domain/ops/OpsAttendanceEventRepository.java` | 7단계용 출결 조회 (독립 인터페이스 — 아래 §4.1 참조) |
| `dto/OpsDto.java` | Schedule/Dashboard/Search/Import DTO |
| `service/OpsService.java` | 운영 관리 업무 로직 (DTO 입출력, UI 의존 없음) |
| `controller/OpsController.java` | REST API + CSV 다운로드 헤더 (HTTP 입출력만) |
| `src/test/.../service/OpsServiceTest.java` | H2 인메모리 서비스 테스트 26건 |

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `OPS-01-schedule-calendar.html` | 일정·캘린더: 월별 달력·일정 목록·등록/수정/삭제·상담 연결 선택 |
| `OPS-02-dashboard.html` | 통계 대시보드: 학생/반/미답변 문의/월 공지 카드 + 오늘 출결 현황 |
| `OPS-03-excel-io.html` | 엑셀 가져오기·내보내기: 학생/출결 CSV 다운로드, 학생 CSV 일괄 등록 |

- 데스크톱·모바일 반응형 (`@media` 분기). 기존 화면과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/{academyId}/schedules` | 일정 등록 `{title, date(yyyy-MM-dd), startTime?, endTime?(HH:mm), targetType?(ALL\|CLASS), classId?(CLASS면 필수), consultationId?, memo?}` |
| GET | `/api/rea/{academyId}/schedules?yearMonth=` | 월별 일정 (yyyy-MM 필수) |
| PUT | `/api/rea/{academyId}/schedules/{id}` | 일정 수정 (null 항목 미변경) |
| DELETE | `/api/rea/{academyId}/schedules/{id}` | 일정 삭제 (204) |
| GET | `/api/rea/{academyId}/teachers/{teacherId}/assignments` | 강사 담당 현황 (담당 반 + 업무 메모) |
| PUT | `/api/rea/{academyId}/teachers/{teacherId}/assignments/memo` | 담당 업무 메모 upsert `{dutyMemo}` |
| GET | `/api/rea/{academyId}/search?query=` | 통합 검색 (학생·출결·성적·숙제·상담·공지) |
| GET | `/api/rea/{academyId}/dashboard/stats` | 통계 대시보드 |
| GET | `/api/rea/{academyId}/export/students.csv` | 학생 목록 CSV 다운로드 (UTF-8 BOM) |
| POST | `/api/rea/{academyId}/import/students.csv` | 학생 일괄 등록 (Content-Type: text/csv) |
| GET | `/api/rea/{academyId}/export/attendance.csv?classId=&yearMonth=` | 출결 기록 CSV 다운로드 (classId 선택, yearMonth 필수) |

예외 매핑은 기존 `ApiExceptionHandler` 재사용: `IllegalArgumentException` → 400.

### 검증 규칙
- 일정: 제목·일자(yyyy-MM-dd) 필수, 시각 HH:mm, 종료 > 시작, CLASS면 classId 필수(해당 학원 반), consultationId는 해당 학원 상담이어야 함
- yearMonth: yyyy-MM 필수
- 검색: query 필수 (빈 값 400)
- CSV: 비어 있으면 400

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, 커스텀 JUnit 엔진 러너)
`OpsServiceTest` — **26건 전부 PASS**
- 일정: 등록→월별 조회 / 반별 등록 / 제목·일자·시간범위·반미지정·잘못된 대상값 400 / 수정(지정 항목만) / CLASS→ALL 전환 시 반 초기화 / 삭제 + 타 학원 일정 접근 400 / 다른 달 제외
- 상담 일정: 상담 스레드 연결(제목 스냅샷) / 타 학원 상담 연결 400
- 강사 담당: 담당 반+학생 수+메모 null / 메모 upsert(등록→수정→삭제, 행 1개 유지) / 타 학원 강사 400
- 통합 검색: 학생 이름 횡단(출결·성적·숙제·상담) / 공지 제목 일치 / 빈 검색어 400 / 타 학원 제외
- 대시보드: 학생/반 수 / 오늘 출결(등원1·결석1·기록 학생 2) / 미답변 상담 1·이번 달 공지 1
- CSV: 학생 내보내기(BOM+헤더+행) / 가져오기(2행 등록+반 배정→반 학생 수 4) / 이름 빈 행 실패+없는 반 경고 / 헤더 없음+BOM 허용 / 출결 내보내기(반별·월별, 등원/지각) / 전체+잘못된 yearMonth 400
- 학원 격리: 빈 학원 대시보드 0·일정/검색 비어 있음·내보내기에 타 학원 학생 없음
- 회귀: 1단계 학생 등록·반 배정·2단계 출결·5단계 상담 → 7단계 검색에서 횡단 조회

#### 빌드 환경 특이사항
- Apache POI가 `~/.m2` 캐시에 없어 CSV(엑셀 호환: UTF-8 BOM + CRLF + RFC 4180 인용)로 구현.
- `OpsAttendanceEventRepository`를 기존 `ReaAttendanceEventRepository` 상속이 아닌 **독립 인터페이스**로 작성: 상속 시 같은 타입 빈 2개 생성 → 기존 `AttendanceService` 생성자 주입이 `NoUniqueBeanDefinitionException`으로 깨짐 (기존 코드 수정 금지 원칙상 독립 인터페이스가 정답).

### 4.2 전체 회귀
7개 테스트 클래스 **126건 전부 PASS** (Ops 26 + 기존 100: Attendance·Class·Consultation·Learning 18·Notice·Notification)

### 기존 코드 변경
- 없음 (1~6단계 코드 미수정 — 회귀 테스트로만 검증)

## 5. 남은 사항 (향후 단계)
- 엑셀(xlsx) 네이티브 지원: Apache POI 확보 시 CSV 외 xlsx 내보내기로 확장 가능 (현재 CSV로 엑셀 호환 충족).
- 일정 알림: 일정 등록 시 대상 학부모에게 알림 발송 (현재 미구현 — 필요 시 NotificationService 연동).
- 반복 일정: 매주/매월 반복 일정 규칙 (현재 단건 일정만).
- 대시보드 확장: 기간별 출결률·반별 통계 등.
