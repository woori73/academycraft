# REA 학원관리 3단계 — 알림·메시지 구현 기록

> 작성일: 2026-10-13
> 범위: 알림 채널 추상화(Mock)·6종 알림 종류·출결 연동 자동 발송·개별/반별/전체 메시지 발송·발송 이력·재시도·수신자 알림 목록·읽음 처리·종류별 ON/OFF 설정 (수납·결제 제외)
> 기준: `docs/functional-spec-v1.md` §4, 비투 공개 동작(앱 푸시=문자 비용 0원)만 참고하여 독립 코드 작성
> 경계: **실제 SMS·푸시·알림톡 발송 절대 금지 — Mock 구현체만 사용**

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 채널 추상화 | `NotificationSender` 인터페이스 + `MockNotificationSender` 구현체 (실제 발송 없이 로그만 기록). 향후 실제 채널 연동 시 구현체 추가 |
| 알림 종류 | CHECK_IN(등원)·CHECK_OUT(하원)·NOTICE(공지)·CONSULT_REPLY(상담답변)·GRADE(성적)·HOMEWORK(숙제) 6종 |
| 출결 연동 | 출결 기록·키패드에서 등원/하원 시 학부모에게 자동 알림. 문구는 출결 설정의 checkInMessage/checkOutMessage (`{name}` 치환). 정정으로 등·하원 구분이 바뀐 경우에만 재알림 (중복 방지) |
| 메시지 발송 | 원장·강사가 대상 지정 발송: INDIVIDUAL(학생 ID 목록)·CLASS(반 ID 목록)→소속 학생·ALL(학원 전체). 기본 채널 PUSH (비용 0원) |
| 발송 이력 | 수신자·채널·제목·내용·상태(SENT/FAILED)·실패 사유·재시도 횟수·읽음 여부 기록. 실패 건 재시도 API (FAILED만, 재시도횟수 +1) |
| 알림 목록 | 수신자(학부모=학생 인증키 로그인)별 최신순 목록, 읽음/안읽음, 미읽음 건수, 읽음 처리 (본인 알림만) |
| 알림 설정 | 학부모(학생)별 × 종류별 ON/OFF. 미설정 종류는 기본 ON. OFF면 발송 생략(skipped) |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 타 학원 알림·학생·반 접근 시 400 |
| 실패 격리 | 알림 발송 실패가 출결 기록을 막지 않음 (AttendanceService에서 예외 격리 + NotificationService 내부도 FAILED로 기록) |

### 검증 규칙
- targetType: `INDIVIDUAL/CLASS/ALL` (대소문자 무시). INDIVIDUAL/CLASS는 targetIds 필수
- title/content 필수
- 재시도는 FAILED 상태에서만 (성공 건 재시도 시 409)
- 읽음 처리는 본인(수신 학생) 알림만
- 설정 변경: type 필수, enabled 필수

## 2. 파일 목록

### Backend (신규 — 기존 1차·1단계·2단계 코드 수정 없음, AttendanceService 연동 포인트 교체 제외)
| 파일 | 역할 |
|---|---|
| `domain/notification/NotificationChannel.java` | 채널 enum (PUSH/SMS/ALIMTALK) + parse |
| `domain/notification/NotificationType.java` | 알림 종류 enum 6종 (한글 label 포함) + parse |
| `domain/notification/NotificationStatus.java` | 발송 상태 enum (SENT/FAILED) |
| `domain/notification/NotificationTargetType.java` | 발송 대상 enum (INDIVIDUAL/CLASS/ALL) + parse |
| `domain/notification/NotificationSender.java` | 채널 추상화 인터페이스 |
| `domain/notification/NotificationSendResult.java` | 발송 결과 (성공/실패+사유) |
| `domain/notification/MockNotificationSender.java` | Mock 발송기 (@Component). 실제 발송 없음, 로그만 기록. 테스트용 강제 실패 스위치 포함 |
| `domain/notification/ReaNotification.java` | 알림 엔티티 (`rea_notifications`, 수신자별 1행, 수신자명·연락처 스냅샷) |
| `domain/notification/ReaNotificationRepository.java` | 수신자별 목록·학원 이력·미읽음 건수 |
| `domain/notification/ReaNotificationSetting.java` | 알림 설정 엔티티 (`rea_notification_settings`, 학생×종류별 1행) |
| `domain/notification/ReaNotificationSettingRepository.java` | 설정 Repository |
| `dto/NotificationDto.java` | Send/발송결과/목록/이력/읽음/설정 DTO |
| `service/NotificationService.java` | 알림 업무 로직 (DTO 입출력, UI 의존 없음) |
| `controller/NotificationController.java` | REST API (HTTP 입출력만) |
| `src/test/.../service/NotificationServiceTest.java` | H2 인메모리 서비스 테스트 17건 |

### Backend (기존 코드 최소 수정 — 2단계에서 예고된 연동 포인트)
| 파일 | 변경 |
|---|---|
| `service/AttendanceService.java` | 3곳의 `[알림 연동 포인트 - 3단계]` 주석을 실제 호출로 교체: `check()`·`keypadCheck()`에서 등·하원 알림 발송, `correctEvent()`에서 등·하원 구분 변경 시에만 재알림. `notifyAttendance()` 헬퍼로 예외 격리. 기존 로직·검증 규칙 변경 없음 |

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `NTF-01-notification-list.html` | 알림 목록 (학부모): 인증키 로그인 → 수신 알림 목록·읽음/안읽음·읽음 처리·미읽음 뱃지·종류별 ON/OFF 설정 |
| `NTF-02-message-send.html` | 메시지 발송 (관리자): 개별/반별/전체 대상 선택 → 학생·반 목록 → 제목·내용 발송 |
| `NTF-03-send-history.html` | 발송 이력 (관리자): 상태·종류 필터, 성공/실패 표시, 실패 사유, 재시도 버튼 |

- 데스크톱·모바일 반응형 (`@media` 분기). 기존 화면과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/{academyId}/notifications/send` | 메시지 발송 `{targetType, targetIds[], title, content}` |
| GET | `/api/rea/{academyId}/notifications?studentId=&authKey=` | 수신자별 알림 목록 (둘 중 하나 필수) |
| GET | `/api/rea/{academyId}/notifications/unread-count?studentId=&authKey=` | 미읽음 건수 |
| GET | `/api/rea/{academyId}/notifications/history` | 발송 이력 (관리자) |
| POST | `/api/rea/{academyId}/notifications/{id}/retry` | 실패 건 재시도 |
| POST | `/api/rea/{academyId}/notifications/{id}/read` | 읽음 처리 `{studentId 또는 authKey}` |
| GET | `/api/rea/{academyId}/notifications/settings?studentId=&authKey=` | 알림 설정 조회 |
| PUT | `/api/rea/{academyId}/notifications/settings` | 알림 설정 변경 `{studentId 또는 authKey, settings: [{type, enabled}]}` |

예외 매핑은 기존 `ApiExceptionHandler` 재사용: `IllegalArgumentException` → 400, `IllegalStateException` → 409.

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, 커스텀 JUnit 엔진 러너)
`NotificationServiceTest` — **17건 전부 PASS**
- 발송: 개별 1건 SENT·이력 기록 / 반별(소속만) / 전체 2건 / 타 학원 학생 400 / 타 학원 반 400
- Mock·재시도: 강제 실패 → FAILED+사유 → 재시도 → SENT·재시도횟수 1 / 성공 건 재시도 409 / 타 학원 재시도 400
- 설정: NOTICE OFF → skipped 1건 / 설정 조회·변경 (미설정 기본 ON, authKey 기준 변경)
- 목록·읽음: 목록 조회·읽음 처리·미읽음 0 / 타인 알림 읽음 400
- 출결 연동: 키패드 등원 → CHECK_IN 알림 (문구 `{name}` 치환 확인) / 하원 → CHECK_OUT 알림 / CHECK_IN OFF → 알림 없이 출결 기록만 저장 / 정정(ABSENT→CHECK_IN) → 재알림 1건
- 학원 격리: 이력 학원별 분리

`AttendanceServiceTest` (2단계, 기존) — **16건 전부 PASS** (회귀 확인, 출결 알림 연동 후에도 기존 동작 유지)

`ClassServiceTest` (1단계, 기존) — **15건 전부 PASS** (회귀 확인)

**총 48건 전부 PASS**

### 4.2 빌드 환경 메모
- 2단계와 동일: `javac -encoding UTF-8` 직접 컴파일 (의존성 98개 jar, `/tmp/rea-build/cp.txt`) + 커스텀 TestRunner 구동. `@SpringBootTest` + H2 정상 기동.
- 기존 코드 변경: `AttendanceService.java`만 최소 수정 (연동 포인트 3곳 + NotificationService 주입 + 예외 격리 헬퍼). 나머지 기존 파일 미수정.

## 5. 설계 결정 기록
- 수신자 식별: 학부모 별도 회원가입이 없으므로(학생 인증키 로그인) 수신자 = 학생 ID. 수신자명·연락처는 발송 시점 스냅샷 보존.
- 채널: 기본 PUSH (비투처럼 앱 푸시 = 문자 비용 0원). SMS/ALIMTALK는 Mock에서 연락처 없으면 FAILED 처리 (실제 발송 없음).
- 정정 알림: 구분 변경(예: 결석→등원) 시에만 재알림. 같은 구분 내 시각·메모 정정은 중복 알림 방지를 위해 발송하지 않음.
- CONSULT_REPLY·GRADE·HOMEWORK 종류는 enum·설정에 포함 (4·6·7단계 공지·상담·성적/숙제에서 발송 시 재사용 예정). 현재 발송 주체는 출결 연동(CHECK_IN/CHECK_OUT)과 메시지 발송(NOTICE).
