# REA 학원관리 1단계 — 반 및 시간표 관리 구현 기록

> 작성일: 2026-10-13
> 범위: 반 CRUD·학생 배정/제외/이동·반별 주간 시간표 (수납·결제 제외)
> 기준: `docs/functional-spec-v1.md` §2, 비투 공개 동작(반 구분·반별 소속)만 참고하여 독립 코드 작성

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 반 생성·수정·종료 | 이름(필수)·설명·담당강사(필수)·요일·시작/종료시각·강의실·정원(선택). 종료는 비활성화 + 소속 학생 배정 해제(이력 보존) |
| 학생 배정 | `studentIds[]` 일괄 배정. 중복 배정은 무시(idempotent), 정원 초과 시 409 |
| 학생 제외 | 배정 비활성화(soft delete). 재등록 시 신규 이력 행 생성 |
| 반 이동 | 기존 반에서 제외 → 대상 반에 배정 (원자적). 대상 반 정원 검증 |
| 반 상세 | 담당 강사명·시간표·정원·소속 학생 목록 |
| 주간 시간표 | 활성 반의 요일·시간을 일자별로 펼침. 월→일, 시작시각 순 정렬. 시간표 미지정·종료 반 제외 |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 타 학원 데이터 접근 시 400 |

### 검증 규칙
- 담당강사: 같은 학원의 활성 TEACHER 또는 ACADEMY_OWNER만 허용
- 요일: `MON`~`SUN` 3자리 코드 (잘못된 값 400). `java.time.DayOfWeek` 상수는 MONDAY~SUNDAY이므로 별도 매핑 사용
- 시각: `HH:mm` 형식, 시작 < 종료 강제
- 정원: 1 이상 (null = 무제한)
- 배정·제외·이동은 활성 반에만 허용

### 인증 관련 메모
- 1차 설계대로 인증 미구현 → `SecurityConfig`에서 `/api/rea/**` 전체 허용 (CSRF 비활성화). 격리는 Service 레벨 academyId 스코핑으로 수행. 인증키 인증 도입 시 교체 필요.
- 기존 동작 변경 없이 예외만 매핑하는 `ApiExceptionHandler` 추가: `IllegalArgumentException` → 400, `IllegalStateException` → 409.

## 2. 파일 목록

### Backend (신규)
| 파일 | 역할 |
|---|---|
| `domain/clazz/ReaClass.java` | 반 엔티티 (`rea_classes`) |
| `domain/clazz/ClassEnrollment.java` | 반-학생 매핑 엔티티 (`rea_class_enrollments`, soft delete) |
| `domain/clazz/ReaClassRepository.java` | 반 Repository |
| `domain/clazz/ClassEnrollmentRepository.java` | 매핑 Repository |
| `dto/ClassDto.java` | Create/Update/Response/Detail/Assign/Move/TimetableEntry DTO |
| `service/ClassService.java` | 반 관리 업무 로직 (DTO 입출력, UI 의존 없음) |
| `controller/ClassController.java` | REST API (HTTP 입출력만) |
| `controller/ApiExceptionHandler.java` | 400/409 예외 매핑 (신규, 기존 코드 미수정) |
| `config/SecurityConfig.java` | `/api/rea/**` 허용 (신규, 인증 미구현 단계) |
| `src/test/.../service/ClassServiceTest.java` | H2 인메모리 서비스 테스트 15건 |

### 기존 코드 변경
- 없음 (1차 Member/Auth 코드 미수정 — 회귀로만 검증)

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `CLS-01-class-list.html` | 반 목록 (반 카드: 이름·학생수·담당강사·시간표 요약, 종료반 포함 토글) |
| `CLS-02-class-form.html` | 반 생성·수정 (강사 선택, 요일 체크박스, 시간·강의실·정원) |
| `CLS-03-class-detail.html` | 반 상세 (학생 목록, 배정 UI, 제외, 반 이동, 반 종료) |
| `timetable-weekly.html` | 주간 시간표 (월~일 7열 그리드, 모바일 1열) |

- 데스크톱·모바일 반응형 (`@media` 분기). MBR-01과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/{academyId}/classes` | 반 생성 |
| GET | `/api/rea/{academyId}/classes` | 반 목록 (`?includeInactive=true`로 종료반 포함, 학생수·강사명 포함) |
| GET | `/api/rea/{academyId}/classes/{id}` | 반 상세 (소속 학생 목록 포함) |
| PUT | `/api/rea/{academyId}/classes/{id}` | 반 수정 |
| POST | `/api/rea/{academyId}/classes/{id}/close` | 반 종료 (204) |
| POST | `/api/rea/{academyId}/classes/{id}/students` | 학생 배정 (`{studentIds:[]}`) |
| DELETE | `/api/rea/{academyId}/classes/{id}/students/{studentId}` | 학생 제외 (204) |
| POST | `/api/rea/{academyId}/classes/{id}/students/{studentId}/move` | 반 이동 (`{toClassId}`) |
| GET | `/api/rea/{academyId}/timetable/weekly` | 주간 시간표 |

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, `@SpringBootTest` + `@Transactional`)
`ClassServiceTest` — **15건 전
...[truncated 3056 chars]