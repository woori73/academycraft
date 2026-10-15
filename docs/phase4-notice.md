# REA 학원관리 4단계 — 공지 관리 구현 기록

> 작성일: 2026-10-13
> 범위: 공지 CRUD(전체공지·반소식)·통합 목록·상세(조회수 관리자만)·읽음 확인·확인자 목록·공지 등록 시 NOTICE 알림 발송·첨부파일 메타데이터 (수납·결제 제외)
> 기준: `docs/functional-spec-v1.md` §5, 비투 공개 동작(조회수 관리자만 표시·반소식·글쓰기·파일 첨부)만 참고하여 독립 코드 작성
> 경계: **실제 외부 메시지 발송 금지 — NotificationService의 Mock 사용**

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 공지 작성 | 대상 선택 (전체공지=classId null / 반소식=classId 지정), 제목·내용, 작성자(원장·강사, 선택), 첨부파일명·키(메타데이터만). 저장 후 대상 학부모에게 NOTICE 알림 자동 발송 (실패 격리: 발송 실패해도 공지 등록 유지) |
| 공지 목록 | 관리자: 학원 전체 공지 + scope(ALL/CLASS)·classId 필터. 학부모(studentId/authKey): 전체공지 + 자녀 소속 반 공지 통합 조회 |
| 공지 상세 | 조회할 때마다 조회수 +1. 조회수는 관리자 조회에서만 응답에 포함 (학부모는 null). 학부모는 전체공지·소속 반 공지만 조회 가능 |
| 수정·삭제 | 수정: 제목·내용·대상·첨부 선택 변경 (changeToAll=true면 전체공지로 전환). 삭제: 비활성 (목록·상세 제외) |
| 읽음 확인 | 학생(학부모)별 1행 기록, 멱등 (중복 호출 시 기존 기록). 전체공지·소속 반 공지만 가능 |
| 확인자 목록 | 관리자용: 읽은 학생 목록 (이름·읽음 시각순) |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 타 학원 공지·반·학생 접근 시 400 |

### 검증 규칙
- title/content 필수 (수정 시 제공된 값만, 빈 문자열 거부)
- 대상 반: 같은 학원의 **활성** 반만 (종료된 반 지정 시 400)
- 작성자: 같은 학원의 원장·강사 회원만 (선택)
- scope: `ALL`/`CLASS` (대소문자 무시), 그 외 400
- 학부모 목록·상세·읽음 확인은 전체공지 + 자녀 소속 반 범위로 제한
- 읽음 확인은 studentId 또는 authKey 필수

## 2. 파일 목록

### Backend (신규 — 1차·1~3단계 코드 수정 없음)
| 파일 | 역할 |
|---|---|
| `domain/notice/ReaNotice.java` | 공지 엔티티 (`rea_notices`, classId null=전체공지) |
| `domain/notice/ReaNoticeRead.java` | 읽음 확인 엔티티 (`rea_notice_reads`, 학원+공지+학생 유일) |
| `domain/notice/ReaNoticeRepository.java` | 공지 Repository (학부모 범위 조회 JPQL 포함) |
| `domain/notice/ReaNoticeReadRepository.java` | 읽음 확인 Repository |
| `dto/NoticeDto.java` | 작성/수정/목록/상세/읽음확인/확인자 DTO |
| `service/NoticeService.java` | 공지 업무 로직 (NotificationService 주입·발송) |
| `controller/NoticeController.java` | REST API (HTTP 입출력만) |
| `src/test/.../service/NoticeServiceTest.java` | H2 인메모리 서비스 테스트 14건 |

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `NTC-01-notice-list.html` | 전체공지 목록: 전체/전체공지/반소식 탭, 학부모(authKey) 조회, 조회수(관리자만) 표시 |
| `NTC-02-class-news.html` | 반소식 목록: 반 선택 → 반별 공지 |
| `NTC-03-notice-form.html` | 공지 작성: 대상 선택(전체/반), 제목·내용, 작성자ID·첨부 메타데이터, 등록 시 알림 발송 결과 표시 |
| `NTC-04-notice-detail.html` | 공지 상세: 내용·첨부, 읽음 확인(authKey), 확인자 목록(관리자), 삭제(관리자) |

- 데스크톱·모바일 반응형 (`@media` 분기). 기존 화면과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/{academyId}/notices` | 공지 작성 `{classId(선택), title, content, authorId(선택), attachmentFileName/attachmentFileKey(선택)}` → 공지 + 알림 발송 결과 |
| GET | `/api/rea/{academyId}/notices?scope=ALL\|CLASS&classId=` | 목록. `studentId=` 또는 `authKey=`이면 학부모 통합 조회 |
| GET | `/api/rea/{academyId}/notices/{id}` | 상세 (조회수 +1). 학부모 조회 시 viewCount=null, myRead 포함 |
| PUT | `/api/rea/{academyId}/notices/{id}` | 수정 `{title?, content?, classId?, changeToAll?, attachmentFileName?, attachmentFileKey?}` |
| DELETE | `/api/rea/{academyId}/notices/{id}` | 삭제 (비활성) → 204 |
| POST | `/api/rea/{academyId}/notices/{id}/read` | 읽음 확인 `{studentId 또는 authKey}` (멱등) |
| GET | `/api/rea/{academyId}/notices/{id}/readers` | 확인자 목록 (관리자) |

예외 매핑은 기존 `ApiExceptionHandler` 재사용: `IllegalArgumentException` → 400, `IllegalStateException` → 409.

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, 커스텀 JUnit 엔진 러너)
`NoticeServiceTest` — **14건 전부 PASS**
- 작성: 전체공지 → 3명 전원에 NOTICE 알림 / 반소식 → 해당 반 2명에게만 / 제목·내용 필수 / 타 학원 반·작성자 400 / 첨부 메타데이터 저장
- 목록: 관리자 전체+scope/classId 필터 / 학부모 통합 조회(전체+소속 반)·authKey 동일 / 타 반 classId 400 / 타 학원 학생 400 / 학부모 목록 viewCount null
- 상세: 조회수 +1 누적 / 관리자만 viewCount 표시 / 학부모 타 반 공지 400 / 타 학원 공지 400
- 수정·삭제: 제목·대상 변경 / 반소식↔전체공지 전환 / 삭제 후 목록·상세 제외
- 읽음 확인: 기록 저장·멱등 / authKey 가능 / 확인자 목록 / 타 반 공지·타 학원 학생 400
- 학원 격리: 타 학원 목록 빈값, 상세·수정·삭제·확인자 400
- 회귀: 알림은 `NotificationType.NOTICE`, 제목 `[공지] ` 접두사

회귀 — **총 62건 전부 PASS** (ClassServiceTest 15 · AttendanceServiceTest 16 · NotificationServiceTest 17 · NoticeServiceTest 14)

### 4.2 빌드 환경 메모
- 2·3단계와 동일: `javac -encoding UTF-8` 직접 컴파일 (의존성 jar, `/tmp/rea-build/cp.txt`) + 커스텀 TestRunner 구동. `@SpringBootTest` + H2 정상 기동.
- 컴파일 이슈: `@Query` 이름 파라미터 — javac에 `-parameters` 미적용 시 Spring Data가 파라미터명을 찾지 못함. `ReaNoticeRepository.findVisibleNotices`에 `@Param` 명시로 해결.
- 기존 코드 변경: **없음** (확장만).

## 5. 설계 결정 기록
- 작성자 식별: body의 authorId(선택) — 같은 학원의 원장·강사 회원만 허용, 이름은 스냅샷. 미지정 시 authorName null.
- 조회수 표시: 뷰어 판별을 studentId/authKey 유무로 수행 (Notification 패턴과 동일). 학부모 응답의 viewCount는 null.
- 읽음 확인 대상: 학생 ID (학부모=학생 인증키 로그인 사용자). noticeId+studentId 유일 제약으로 중복 방지, 멱등 반환.
- 알림 발송: `NotificationService.sendMessage` 재사용 (targetType ALL/CLASS, 종류 NOTICE, 기본 채널 PUSH). 알림 제목 `[공지] + 공지제목`, 내용은 900자 절단. 발송 예외는 공지 등록을 막지 않고 `notificationError`로 반환.
- 첨부파일: 4단계에서는 파일명·키 메타데이터만 저장. 실제 업로드는 FileStorage 연동 시 확장 (5단계 이후).
