# REA 학원관리 5단계 — 상담 관리 구현 기록

> 작성일: 2026-10-13
> 범위: 상담 스레드 생성(INQUIRY 문의·NEWS 아이소식)·스레드 내 메시지 작성(텍스트·첨부 메타데이터)·문의 모음(미답변/답변완료 필터·답변)·아이소식 1:1 전달·학원 답변 시 CONSULT_REPLY 알림 발송·메시지 읽음 처리·스레드 상태 전이(OPEN/ANSWERED/CLOSED)·종료 (수납·결제 제외)
> 기준: `docs/functional-spec-v1.md` §6, 비투 공개 동작(문의·답변·문의 모음·아이소식 1:1·부모도 글쓰기 가능)만 참고하여 독립 코드 작성
> 경계: **실제 외부 메시지 발송 금지 — NotificationService의 Mock 사용**

## 1. 구현 기능

| 기능 | 내용 |
|---|---|
| 스레드 생성 | INQUIRY(학부모→학원 1:1 문의) / NEWS(학원→학부모 1:1 아이소식). 제목 필수. INQUIRY는 content가 있으면 첫 문의 메시지로 저장, NEWS는 작성자(원장·강사) + 내용 필수 → 첫 메시지로 저장 |
| 메시지 작성 | 스레드 내 메시지 (텍스트, 첨부파일명·키 메타데이터). 학부모(studentId/authKey, 본인 스레드만) 또는 원장·강사(memberId). 종료된 스레드는 409 |
| 문의 모음 | 관리자용 INQUIRY 목록: 미답변(OPEN)/답변완료(ANSWERED)/종료(CLOSED) 필터 + 답변 작성 |
| 아이소식 | 강사가 특정 학생 학부모에게 1:1 소식 전달 (민감정보 개별 전달). 부모도 답글 가능 (양방향) |
| 답변 알림 | 학원(원장·강사) 발신 메시지마다 해당 학부모에게 CONSULT_REPLY 알림 발송 (3단계 NotificationService `sendToStudent` 재사용, 실패 격리: 알림 실패해도 메시지 저장 유지). 학부모 알림 OFF면 skipped |
| 읽음 처리 | 상대방 메시지만 읽음 처리 가능 (본인 메시지 400). 멱등 (중복 호출 시 alreadyRead=true). 학부모=학원 메시지, 관리자=학부모 메시지 기준 미읽음 건수 |
| 상태 전이 | INQUIRY: 생성 OPEN → 학원 답변 시 ANSWERED → 학부모 추가 문의 시 OPEN → 종료 시 CLOSED. NEWS: 종료 전까지 OPEN 유지 |
| 스레드 종료 | 원장·강사 또는 해당 학부모가 종료. 중복 종료 409 |
| 학원 격리 | 모든 조회·수정을 academyId로 스코핑. 타 학원 스레드·학생·강사 접근 시 400 |

### 검증 규칙
- type: `INQUIRY/NEWS` (대소문자 무시), status: `OPEN/ANSWERED/CLOSED` (대소문자 무시)
- 스레드 생성: studentId 또는 authKey 필수, title 필수. NEWS는 authorMemberId(같은 학원 활성 원장·강사)·content 필수
- 메시지: content 필수. 학부모는 해당 스레드의 학생(학부모)만, 학원은 같은 학원 활성 원장·강사만
- 읽음 처리: 발신자 본인은 읽음 처리 불가 (400)
- 학부모 목록·상세는 본인(자녀) 스레드만

## 2. 파일 목록

### Backend (신규 — 1차·1~4단계 코드 수정 없음)
| 파일 | 역할 |
|---|---|
| `domain/consultation/ConsultationType.java` | 상담 종류 enum (INQUIRY/NEWS, 한글 label) + parse |
| `domain/consultation/ConsultationStatus.java` | 상담 상태 enum (OPEN/ANSWERED/CLOSED, 한글 label) + parse |
| `domain/consultation/ReaConsultation.java` | 상담 스레드 엔티티 (`rea_consultations`, 학생명 스냅샷, 상태 전이 메서드) |
| `domain/consultation/ReaConsultationMessage.java` | 상담 메시지 엔티티 (`rea_consultation_messages`, 학부모/학원 발신자 ID + 발신자명 스냅샷, 읽음 시각) |
| `domain/consultation/ReaConsultationRepository.java` | 스레드 Repository (학원별·학생별 최신순) |
| `domain/consultation/ReaConsultationMessageRepository.java` | 메시지 Repository (스레드별 작성순·건수·마지막 메시지) |
| `dto/ConsultationDto.java` | 생성/목록/상세/메시지/종료/읽음 DTO |
| `service/ConsultationService.java` | 상담 업무 로직 (NotificationService 주입·CONSULT_REPLY 발송) |
| `controller/ConsultationController.java` | REST API (HTTP 입출력만) |
| `src/test/.../service/ConsultationServiceTest.java` | H2 인메모리 서비스 테스트 20건 |

### Backend (기존 코드 확장 — 수정 없음, 메서드 추가만)
| 파일 | 변경 |
|---|---|
| `service/NotificationService.java` | `sendToStudent(academyId, studentId, type, title, content)` 신규 public 메서드 추가. 단일 수신자·단일 종류 발송 (CONSULT_REPLY용). 수신 OFF면 skipped. 기존 메서드는 일절 변경 없음 |

### Frontend (신규, `frontend/screens/`)
| 파일 | 화면 |
|---|---|
| `CNS-01-consultation-list.html` | 문의 목록 (학부모): 인증키 로그인 → 본인 스레드 목록, 문의/아이소식 탭, 상태 뱃지 → CNS-05로 이동 |
| `CNS-02-consultation-form.html` | 문의 작성 (학부모): 인증키·제목·내용 → 1:1 문의 전송 |
| `CNS-03-inquiry-inbox.html` | 문의 모음 (관리자): 미답변/답변완료/종료 필터, 스레드 선택 → 대화 + 답변 전송 (학부모 알림 발송 결과 표시) |
| `CNS-04-news-send.html` | 아이소식 작성 (강사): 학생 선택 → 제목·내용 → 특정 학부모에게 1:1 전송 |
| `CNS-05-consultation-detail.html` | 대화 상세: 스레드 ID + 인증키/회원ID → 말풍선 대화, 메시지 전송, 상대방 메시지 읽음 처리, 상담 종료 |

- 데스크톱·모바일 반응형 (`@media` 분기). 기존 화면과 동일한 최소 기능 UI 스타일. 모든 화면 node `--check` 통과.

## 3. API 목록

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/{academyId}/consultations` | 스레드 생성 `{studentId 또는 authKey, type, title, content?, authorMemberId?(NEWS), attachmentFileName/Key?}` → 스레드 + 첫 메시지 + 알림 발송 결과 |
| GET | `/api/rea/{academyId}/consultations?status=&type=` | 스레드 목록. `studentId`/`authKey`(학부모: 본인만), `targetStudentId`(관리자: 특정 학생) |
| GET | `/api/rea/{academyId}/consultations/{id}` | 스레드 상세 + 메시지 목록. 학부모는 `studentId`/`authKey`로 본인 스레드만 |
| POST | `/api/rea/{academyId}/consultations/{id}/messages` | 메시지 작성 `{content, attachmentFileName/Key?, studentId|authKey(학부모) 또는 memberId(원장·강사)}` → 메시지 + CONSULT_REPLY 알림 결과 |
| POST | `/api/rea/{academyId}/consultations/{id}/close` | 스레드 종료 `{studentId|authKey(학부모) 또는 memberId(원장·강사)}` |
| POST | `/api/rea/{academyId}/consultations/{id}/messages/{messageId}/read` | 읽음 처리 `{studentId|authKey 또는 memberId}` (멱등) |

예외 매핑은 기존 `ApiExceptionHandler` 재사용: `IllegalArgumentException` → 400, `IllegalStateException` → 409.

## 4. 테스트 결과

### 4.1 단위 테스트 (H2 인메모리, 커스텀 JUnit 엔진 러너)
`ConsultationServiceTest` — **20건 전부 PASS**
- 생성: INQUIRY(내용 없이 → OPEN·메시지 0건 / 내용 포함 → 학부모 발신 첫 메시지) / NEWS(강사 발신 첫 메시지 + CONSULT_REPLY 알림 1건, 제목 `[상담 답변]` 접두사 확인) / NEWS 작성자·내용 없으면 400 / authKey 생성 / 제목 없음·타 학원 학생 400
- 메시지·상태: 학부모 추가 메시지 → OPEN 유지·알림 없음 / 강사 답변 → ANSWERED + 알림 발송 / ANSWERED 후 학부모 추가 문의 → OPEN 복귀 / 타 학원 강사·다른 학부모·발신자 없음 400 / CONSULT_REPLY OFF → skipped (상태 전이는 정상)
- 종료: CLOSED 전환 / 종료 후 메시지 409 / 중복 종료 409 / 학부모 본인 스레드 종료 / 타인 스레드 종료 400
- 읽음: 학부모가 강사 메시지 읽음 (readerName=학부모명) · 멱등 · 본인 메시지 400 · 다른 학부모 400 / 강사가 학부모 메시지 읽음
- 조회: 상태·타입 필터 / 학부모 본인 스레드만 (목록·상세) / 학부모 미읽음 건수 (학원 메시지 2건 → 1건 읽음 후 1건)
- 격리: 타 학원 스레드 상세·메시지·종료 400, 목록 학원별 분리
- enum 대소문자 무시 파싱

회귀 — **총 82건 전부 PASS**
(ClassServiceTest 15 · AttendanceServiceTest 16 · NotificationServiceTest 17 · NoticeServiceTest 14 · ConsultationServiceTest 20)

### 4.2 빌드 환경 메모
- 2·3·4단계와 동일: `javac -encoding UTF-8` 직접 컴파일 (`/tmp/rea-build/cp.txt`, 의존성 jar) + 커스텀 TestRunner 구동. `@SpringBootTest` + H2 정상 기동.
- 이번 단계는 `@Query` 미사용 (derived query만)이라 `@Param` 이슈 없음.
- 기존 코드 변경: **없음** (NotificationService에 public 메서드 1개 추가 = 확장만).

## 5. 설계 결정 기록
- 발신자 식별: 학부모는 studentId (학생 인증키 로그인 사용자), 학원은 memberId (원장·강사 회원). 메시지에 둘 중 하나만 설정, 발신자명은 스냅샷.
- 상태 전이 기준: 문의 모음의 '미답변/답변완료' 필터가 INQUIRY의 OPEN/ANSWERED에 정확히 대응하도록, 학원 답변 시 ANSWERED·학부모 추가 문의 시 OPEN 복귀. NEWS는 소식 전달이 목적이므로 OPEN 유지 (부모 답글 가능).
- 알림: 학원 발신 메시지마다 CONSULT_REPLY 1건 (제목 `[상담 답변] + 스레드 제목`). NEWS 생성 시 첫 메시지도 동일 규칙으로 알림 발송 (학부모에게 소식 도착 알림).
- 읽음 의미: '상대방이 읽었음'. 본인 메시지는 읽음 처리 불가. 미읽음 건수는 조회자 기준 반대편 미읽음 메시지 수.
- 첨부파일: 4단계 공지와 동일하게 파일명·키 메타데이터만 저장. 실제 업로드는 FileStorage 연동 시 확장.
- 상담 일정(7단계): 사용자 지시대로 5단계 범위 밖. 7단계 운영 관리의 일정·캘린더에서 다룬다.
