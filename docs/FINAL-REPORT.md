# REA 학원관리 시스템 — 최종 보고서 (1~8단계 + 6단계까지 완료)

> 작성일: 2026-10-13 (6단계 작업 5에서 갱신)
> 프로젝트: `~/workspace/rea-academy/` (backend: Spring Boot 3.2 / Java 17, frontend: 정적 HTML 35개)
> 기준: `docs/functional-spec-v1.md`, 비투 공개 동작만 참고하여 독립 코드 작성
> 경계: 비투 비공개 소스·디자인 자산 복제 금지 / EASY 장부 코드 금지 / 운영 DB 금지 (H2만) / 실제 외부 메시지 발송 금지 / 수납·결제 기능 개발 금지
> 테스트: **318/318 PASS** (개별 실행). 단일 JVM 일괄 실행은 304/318 — 14건은 테스트 격리 이슈 (코드 버그 아님, `docs/phase6-final.md` §1.2).
> 시범 운영 판정: **조건부 가능** — 필수 해결 항목: ① 학생 수정 API 구현 ② PII 로그 마스킹 ③ 비활성 학생 이력 조회 정책 결정 (상세: `docs/phase6-final.md` §6).
>
> **정정 (5단계 작업 5)**: 본 보고서 초판에 "인증키 인증(Security) 미구현"으로 기재된 것은 오래된 기술이다.
> 실제로는 1단계에서 X-Auth-Key 인증·학원별 격리(403)·역할별 권한이 구현 완료되었으며(테스트 182/182 PASS),
> 5단계 작업 1에서 화면 인증 연결까지 완료했다. 아래 §2.2·§5·§6에 정정 내용을 반영했다.

---

## 1. 구현 완료된 기능 목록 (8단계 전체)

### 1차 — 회원·학생·인증키 (기반)
- 원장·강사·학부모 3역할 회원 등록, 학생 등록 시 인증키 자동 발급 (영문+숫자 10자리)
- 인증키 재발급, 회원·학생 비활성화(삭제), 인증키 로그인 (학부모는 학생 인증키로 로그인)
- 학원당 원장 1명 제한

### 1단계 — 반 관리
- 반 생성·수정·종료 (이름·설명·담당강사·요일·시간·강의실·정원), 종료 시 소속 학생 배정 해제
- 학생 일괄 배정 (중복 무시·정원 초과 409), 제외 (soft delete), 반 이동 (원자적)
- 반 상세 (담당 강사명·시간표·소속 학생), 반별 주간 시간표

### 2단계 — 출결
- 출결 기록 (직접 입력), 키패드 출결 (등원/하원 자동 판별), 반별 출결부, 학생별 이력, 월별 현황
- 출결 정정 (사유 필수, 정정 이력 보존), 출결 설정 (하원 가능 시간·사진 전송·알림 문구)

### 3단계 — 알림·메시지
- 채널 추상화 (`NotificationSender` + `MockNotificationSender`, 실제 발송 없음)
- 6종 알림 (등원·하원·공지·상담답변·성적·숙제), 출결 연동 자동 발송 (문구 `{name}` 치환)
- 개별/반별/전체 메시지 발송, 발송 이력·재시도, 읽음 처리·미읽음 건수, 종류별 ON/OFF 설정
- 실패 격리: 알림 실패가 출결·공지·상담 등록을 막지 않음

### 4단계 — 공지
- 전체공지·반소식 작성, 통합 목록 (관리자/학부모 범위 분리), 상세 (조회수 관리자만)
- 수정·삭제 (비활성), 읽음 확인 (멱등), 확인자 목록, 공지 등록 시 NOTICE 알림 자동 발송

### 5단계 — 상담
- 1:1 문의 (INQUIRY)·아이소식 (NEWS) 스레드, 메시지 작성 (텍스트·첨부 메타데이터)
- 문의 모음 (미답변/답변완료/종료 필터), 상태 전이 (OPEN→ANSWERED→OPEN→CLOSED)
- 학원 답변 시 CONSULT_REPLY 알림, 메시지 읽음 처리 (상대방만·멱등)

### 6단계 — 학습 관리
- 숙제 등록 (반별·학생별·내신Wiki 문항 참조) ·검사 (완료/미완료·점수·코멘트·upsert) ·안내
- 학습 진도 기록·조회, 성적 등록 (0≤점수≤만점 검증) ·조회
- 학습 리포트 데이터 API (성적·숙제·진도·출결 종합), 내신Wiki 연동 구조 (포트+스텁)

### 7단계 — 운영 관리
- 일정 등록·월별 조회·수정·삭제, 상담 스레드 연결 (상담 일정)
- 강사 담당 현황 (담당 반+학생 수) ·담당 업무 메모 upsert
- 통합 검색 (학생 이름으로 학생·출결·성적·숙제·상담·공지 횡단)
- 통계 대시보드 (학생/반 수·오늘 출결·미답변 문의·이번 달 공지)
- CSV 가져오기·내보내기 (엑셀 호환: UTF-8 BOM + CRLF)

### 8단계 — 학원 관리·관리자 기능 (최종)
- 학원 기본정보 조회·수정 (PUT upsert: 미등록이면 "학원 등록")
- 지점 관리 (본원·지점 관계, 새 academyId 자동 발급)
- 역할별 사용자 목록 (원장·강사·학부모 연결), 권한 변경 (원장만, 원장 교체 2단계 지원)
- 변경 이력 (감사 로그): 학생 등록·삭제, 반 생성·종료, 출결 정정, 공지 작성·삭제,
  권한 변경, 학원 정보 수정, 지점 등록 — AOP로 기존 코드 수정 없이 기록
- 전 도메인 학원별 데이터 격리 검증, 35개 화면 모바일 점검 문서화
- 최종 통합 시나리오 테스트 (학원 등록→…→통계→감사 로그)

### 1단계 (보안) — X-Auth-Key 인증 (구현 완료, 테스트 182/182 PASS)

- `AuthKeyFilter` (401: 키 누락·위조·비활성) → `ReaSecurityInterceptor` (403: 타 학원 URL·역할 외 API·학부모 타 자녀 조회) → Service 스코핑의 **이중 방어**
- 역할별 권한 매트릭스 (`docs/security.md` §3): 원장 전체 / 강사 읽기+담당 반 쓰기 / 학부모 본인 자녀 읽기+최소 쓰기 화이트리스트
- 인증키 로그인 `POST /api/rea/auth/key-login` (유일한 공개 API)

### 2단계 (AI Agent) — 도구 7종 + 승인 플로우 (구현 완료, 테스트 236/236 PASS)

- 도구 7종: 학생등록·반배정·결석조회·숙제미제출·공지초안·성적분석·운영보고서
- 자연어 파싱, 쓰기 작업 승인 플로우 (승인 전 DB 반영 없음·거부 시 변경 없음)
- `POST /api/rea/{academyId}/agent/execute`, `GET /api/rea/{academyId}/agent/tools`

### 3단계 (채팅 UI) — "AI 학원 비서" (구현 완료)

- `CHAT-01-ai-chat.html`: 인증키 입력·대화 컨텍스트·도구 결과 렌더링
- 5단계 작업 3에서 6개 시나리오 케이스 검증 PASS (승인→DB 반영·감사 로그, 거부 시 변경 없음 포함)

### 5단계 후속 작업 (2026-10-13)

- **작업 1 (화면 인증 연결)**: 34개 화면 + `api-client.js`에 X-Auth-Key 헤더 적용, 로그인 화면(`MBR-07-login.html`) 추가. 테스트 253/253 PASS (신규 17)
- **작업 2 (E2E)**: HTTP 실측 시나리오 A~D (운영 전체 흐름·권한·격리·매트릭스 회귀). 테스트 257/257 PASS (신규 4)
- **작업 3 (AI 비서 검증)**: 2단계 Agent 27개 신규 테스트 + 6개 시나리오 케이스. 전체 284/284 PASS
- **작업 4 (모바일 검증)**: 25개 화면 정적+빈 상태 기준 점검 (가로 스크롤·고정 요소 가림·텍스트 잘림 0건). 실데이터 육안 재확인 권장 (남은 문제 참조)
- **작업 5 (연동·문서 정리)**: 본 보고서 정정, `docs/buildit-integration.md` API 계약 보완, `docs/wiki-integration.md`·`docs/payment-policy.md` 신규

### 6단계 (2026-10-13) — 통합·운영 검증 + 최종 보고

- **작업 1 (남은 문제 정리)**: CHAT-01 localStorage 키 통일 (`rea_auth_key`) · 학부모 답글 403 의도적 정책 확인 (테스트 3건) · 대량 데이터 검증 (500명/10,000건/1,500건) · 발견 2건: ① 31개 화면 API 경로 중복 접두사(404) **수정 완료** ② 월별 출결 N+1 (15,500 쿼리·51초) 백엔드 최적화 승인 대기 · 모바일 75회 실측 재검증 (이슈 0건). 테스트 292/292 PASS (신규 8)
- **작업 2 (운영 수준 통합 검증)**: 격리 매트릭스 11건 (역할×리소스 전부 403) · 정합성 4건 (학생 비활성 시 기록 보존·조회 차단 — 정책 결정 필요) · 동시 수정 4건 (낙관적 락 권장 정책 결정, 현재 마지막 쓰기 승리) · 장애 복구 4건 (롤백 PASS, 발견 2건: fetch 타임아웃 미설정·네트워크 오류 미표시). 수정 1건: 반 부분 업데이트 capacity null 초기화 버그. 발견: `MemberService.updateStudent()` no-op + PUT 미등록 (학생 수정 불가). AI 비서 재검증 62/62 PASS. 테스트 315/315 PASS (신규 23)
- **작업 3 (BuildIt 홈페이지 연동)**: 핵심 발견 — 홈페이지에 실제 상담 신청 폼이 없음 (연락처 채널 모달만). 홈페이지→REA 이동 링크 가능 (편집기 코드 변경 불필요), 상담 신청 연결은 조건부 가능 (REA 공개 접수 API 신설 또는 홈페이지 폼 신설 중 택1, 승인 필요), 익명 방문자의 REA 상담 API 직접 호출 불가. REA 독립 배포 가능 (wemade-reorg 의존 0건). 코드 변경 없음
- **작업 4 (운영 환경 준비)**: Flyway V1 DDL 초안 (22개 테이블, `db/migration/V1__rea_initial.sql`) — **운영 적용 금지**. dev/prod 프로파일 초안 (prod는 플레이스홀더만). 응답시간 실측: 학생목록 p95 25ms · 성적 p95 4.6ms · 리포트 p95 27ms — PASS. 발견: MockNotificationSender가 PII를 로그에 기록 (수정 권장). GitHub Actions 없음 (향후 CI 권장사항 문서화). 단일 JVM 일괄 실행 시 14건 실패 = 테스트 격리 이슈 (코드 버그 아님). 테스트 318/318 PASS (신규 3)
- **작업 5 (최종 보고)**: `docs/phase6-final.md` 작성 — 테스트 총괄·브라우저 검증·BuildIt 연동 범위·운영 DB 판단·남은 문제 10건 표·**시범 운영 판정: 조건부 가능** (필수 해결 3건: 학생 수정 API 구현 · PII 로그 마스킹 · 비활성 학생 이력 정책 결정)

---

## 2. 실제 동작 확인된 기능 vs 미구현 기능

### 2.1 실제 동작 확인 (H2 인메모리 테스트 **318건 PASS** + 화면 node --check 35건)

| 영역 | 동작 확인된 기능 |
|---|---|
| 회원·인증 | 회원/학생 등록·조회·비활성화·인증키 재발급·인증키 로그인·원장 1명 제한 |
| 인증·인가 (Security) | X-Auth-Key 헤더 인증 (401) · URL academyId 일치 검증·역할 매트릭스 (403) · 학원별 격리 |
| 반 | 생성·수정·종료·배정·제외·이동·주간 시간표 |
| 출결 | 직접 기록·키패드 자동 판별·출결부·월별·정정(이력 보존)·설정 |
| 알림 | Mock 발송·6종·출결/공지/상담/숙제/성적 연동 발송·이력·재시도·읽음·ON/OFF |
| 공지 | 작성·목록·상세(조회수)·수정·삭제·읽음 확인·확인자 목록 |
| 상담 | 스레드 생성·메시지·상태 전이·종료·읽음·답변 알림 |
| 학습 | 숙제 등록·검사·진도·성적·리포트 데이터 API |
| AI Agent | 도구 7종·자연어 파싱·쓰기 승인 플로우·채팅 UI ("AI 학원 비서") |
| 운영 | 일정 CRUD·상담 연결·강사 담당·통합 검색·대시보드·CSV IO |
| 관리 | 학원 정보 upsert·지점 등록·사용자 목록·권한 변경·감사 로그 기록/조회 |
| 격리 | 전 도메인 타 학원 접근 차단 (400 또는 빈 결과) |
| 화면 | 35개 화면 인라인 JS 문법 검사 통과, viewport·반응형 점검 완료 |

### 2.2 미구현 / 스텁 상태 (설계·인터페이스만 존재)

| 기능 | 상태 | 비고 |
|---|---|---|
| 인증키 인증 (Security) | **구현 완료** | 초판의 "미구현" 기재는 오래된 기술. 1단계에서 구현 (182/182 PASS), 5단계 작업 1에서 화면 인증 연결 완료 (253/253 PASS) |
| 학생 정보 수정 | **미구현** | `MemberService.updateStudent()`는 no-op (엔티티에 반영 안 함) + `PUT /students/{studentId}` 엔드포인트 미등록 → **학생 정보 수정 불가**. 시범 운영 전 구현 필수 (`docs/phase6-final.md` §5 #2) |
| 동시 수정 낙관적 락 | **미구현** | `@Version` 없음 → 마지막 쓰기 승리. 정책 결정(낙관적 락 권장)은 완료, 구현은 후속 승인 필요 |
| 프론트 fetch 타임아웃·전역 오류 핸들러 | **미구현** | 타임아웃 미설정, MBR-01·ATT-02 등 일부 화면 네트워크 오류 미표시. 안정화 범위 후속 작업 |
| 실제 알림 발송 | 스텁 (유지) | `MockNotificationSender`만. 실제 발송은 의도적으로 미연동 (방침: §6 #2 체크리스트 참조). **주의**: Mock이 INFO 로그에 수신자 이름·전화번호·제목·본문을 기록 → 시범 운영 전 마스킹 필수 (`docs/phase6-final.md` §5 #4) |
| 내신Wiki 실제 연동 | 스텁 | `WikiIntegrationPort` 인터페이스 + 스텁 구현체. 설계 문서: `docs/wiki-integration.md` |
| Report v2.8 고도화 | 부분 | 데이터 API까지만. v2.8 HTML 바인딩 별도 작업 |
| xlsx 네이티브 | 미구현 | CSV(엑셀 호환)로 충족. POI 확보 시 확장 |
| 반복 일정 | 미구현 | 단건 일정만 |
| 감사 로그 행위자 (AOP 경유) | 제한 (유지) | AOP 경유 시 행위자 null(시스템). 서비스 시그니처에 행위자가 없어 인증 도입 후에도 동일 — 알려진 한계 |
| 수납·결제 | 의도적 제외 | 결제선생 외부 연결 방침. 개발 금지 준수. 상세: `docs/payment-policy.md` |

---

## 3. API 전체 목록 (78개)

기준 경로: `/api/rea/{academyId}` (인증 제외). 예외: `IllegalArgumentException` → 400, `IllegalStateException` → 409.

### 인증 (1)
| Method | Path | 설명 |
|---|---|---|
| POST | `/api/rea/auth/key-login` | 인증키 로그인 `{authKey}` → 역할 판별 |

### 회원·학생 (10)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/members` | 원장·강사 등록 `{role, name, phone?}` |
| GET | `/{academyId}/members?role=` | 회원 목록 |
| GET | `/{academyId}/members/{memberId}` | 회원 상세 (인증키 포함) |
| DELETE | `/{academyId}/members/{memberId}` | 회원 비활성화 |
| POST | `/{academyId}/members/{memberId}/renew-key` | 회원 인증키 재발급 |
| POST | `/{academyId}/students` | 학생 등록 `{name, grade?, parentName?, parentPhone?}` → 인증키 발급 |
| GET | `/{academyId}/students?name=` | 학생 목록 |
| GET | `/{academyId}/students/{studentId}` | 학생 상세 |
| DELETE | `/{academyId}/students/{studentId}` | 학생 비활성화 |
| POST | `/{academyId}/students/{studentId}/renew-key` | 학생 인증키 재발급 |

### 반 (9)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/classes` | 반 생성 |
| GET | `/{academyId}/classes?includeInactive=` | 반 목록 |
| GET | `/{academyId}/classes/{classId}` | 반 상세 |
| PUT | `/{academyId}/classes/{classId}` | 반 수정 |
| POST | `/{academyId}/classes/{classId}/close` | 반 종료 |
| POST | `/{academyId}/classes/{classId}/students` | 학생 배정 `{studentIds[]}` |
| DELETE | `/{academyId}/classes/{classId}/students/{studentId}` | 학생 제외 |
| POST | `/{academyId}/classes/{classId}/students/{studentId}/move` | 반 이동 `{toClassId}` |
| GET | `/{academyId}/timetable/weekly` | 주간 시간표 |

### 출결 (8)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/attendance/check` | 출결 기록 `{studentId\|authKey, eventType, occurredAt?, note?}` |
| POST | `/{academyId}/attendance/keypad` | 키패드 입력 `{authKey}` → 등/하원 자동 판별 |
| GET | `/{academyId}/attendance/roster?classId=&date=` | 반별 출결부 |
| GET | `/{academyId}/attendance/student/{studentId}?from=&to=` | 학생별 이력 |
| GET | `/{academyId}/attendance/monthly?classId=&yearMonth=` | 월별 현황 |
| PUT | `/{academyId}/attendance/{eventId}` | 출결 정정 `{eventType?, occurredAt?, note?, reason(필수)}` |
| GET | `/{academyId}/attendance/settings` | 출결 설정 조회 |
| PUT | `/{academyId}/attendance/settings` | 출결 설정 변경 |

### 알림 (8)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/notifications/send` | 메시지 발송 `{targetType, targetIds[], title, content}` |
| GET | `/{academyId}/notifications?studentId=&authKey=` | 수신자별 알림 목록 |
| GET | `/{academyId}/notifications/unread-count?studentId=&authKey=` | 미읽음 건수 |
| GET | `/{academyId}/notifications/history` | 발송 이력 (관리자) |
| POST | `/{academyId}/notifications/{id}/retry` | 실패 건 재시도 |
| POST | `/{academyId}/notifications/{id}/read` | 읽음 처리 |
| GET | `/{academyId}/notifications/settings?studentId=&authKey=` | 알림 설정 조회 |
| PUT | `/{academyId}/notifications/settings` | 알림 설정 변경 |

### 공지 (7)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/notices` | 공지 작성 |
| GET | `/{academyId}/notices?scope=&classId=&studentId=&authKey=` | 공지 목록 |
| GET | `/{academyId}/notices/{id}?studentId=&authKey=` | 공지 상세 |
| PUT | `/{academyId}/notices/{id}` | 공지 수정 |
| DELETE | `/{academyId}/notices/{id}` | 공지 삭제 |
| POST | `/{academyId}/notices/{id}/read` | 읽음 확인 |
| GET | `/{academyId}/notices/{id}/readers` | 확인자 목록 (관리자) |

### 상담 (6)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/consultations` | 스레드 생성 `{studentId\|authKey, type, title, content?, authorMemberId?}` |
| GET | `/{academyId}/consultations?status=&type=&studentId=&authKey=` | 스레드 목록 |
| GET | `/{academyId}/consultations/{id}?studentId=&authKey=` | 스레드 상세 |
| POST | `/{academyId}/consultations/{id}/messages` | 메시지 작성 |
| POST | `/{academyId}/consultations/{id}/close` | 스레드 종료 |
| POST | `/{academyId}/consultations/{id}/messages/{messageId}/read` | 읽음 처리 |

### 학습 (9)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/homework` | 숙제 등록 |
| GET | `/{academyId}/homework?classId=&studentId=&authKey=` | 숙제 목록 |
| POST | `/{academyId}/homework/{id}/check` | 숙제 검사 |
| GET | `/{academyId}/homework/{id}/checks` | 숙제별 검사 결과 |
| POST | `/{academyId}/progress` | 진도 기록 |
| GET | `/{academyId}/progress/student/{studentId}?authKey=` | 진도 조회 |
| POST | `/{academyId}/grades` | 성적 등록 |
| GET | `/{academyId}/grades/student/{studentId}?authKey=` | 성적 조회 |
| GET | `/{academyId}/reports/student/{studentId}?authKey=` | 학습 리포트 데이터 |

### 운영 (11)
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/schedules` | 일정 등록 |
| GET | `/{academyId}/schedules?yearMonth=` | 월별 일정 |
| PUT | `/{academyId}/schedules/{id}` | 일정 수정 |
| DELETE | `/{academyId}/schedules/{id}` | 일정 삭제 |
| GET | `/{academyId}/teachers/{teacherId}/assignments` | 강사 담당 현황 |
| PUT | `/{academyId}/teachers/{teacherId}/assignments/memo` | 담당 업무 메모 수정 |
| GET | `/{academyId}/search?query=` | 통합 검색 |
| GET | `/{academyId}/dashboard/stats` | 통계 대시보드 |
| GET | `/{academyId}/export/students.csv` | 학생 CSV 내보내기 |
| POST | `/{academyId}/import/students.csv` | 학생 CSV 가져오기 |
| GET | `/{academyId}/export/attendance.csv?classId=&yearMonth=` | 출결 CSV 내보내기 |

### 관리 (7) — 8단계 신규
| Method | Path | 설명 |
|---|---|---|
| GET | `/{academyId}/info` | 학원 정보 조회 |
| PUT | `/{academyId}/info` | 학원 정보 등록·수정 (upsert) |
| GET | `/{academyId}/branches` | 지점 목록 |
| POST | `/{academyId}/branches` | 지점 등록 |
| GET | `/{academyId}/users?role=` | 역할별 사용자 목록 |
| PUT | `/{academyId}/users/{memberId}/role` | 권한 변경 (원장만) |
| GET | `/{academyId}/audit-logs?actionType=` | 변경 이력 조회 |

### AI Agent (2) — 2단계 신규
| Method | Path | 설명 |
|---|---|---|
| POST | `/{academyId}/agent/execute` | 자연어 명령 실행 (쓰기 도구는 승인 플로우) |
| GET | `/{academyId}/agent/tools` | 사용 가능 도구 목록 |

---

## 4. 화면 전체 목록 (35개)

| ID | 화면명 | 단계 |
|---|---|---|
| MBR-01-student-list.html | 학생 목록 | 1차 |
| CLS-01-class-list.html | 반 목록 | 1단계 |
| CLS-02-class-form.html | 반 생성·수정 | 1단계 |
| CLS-03-class-detail.html | 반 상세 | 1단계 |
| timetable-weekly.html | 주간 시간표 | 1단계 |
| ATT-01-attendance-keypad.html | 출결 입력 (키패드) | 2단계 |
| ATT-02-attendance-monthly.html | 월별 출결 현황 | 2단계 |
| ATT-03-attendance-roster.html | 반별 출결부 | 2단계 |
| ATT-04-attendance-settings.html | 출결 설정 | 2단계 |
| NTF-01-notification-list.html | 알림 목록 (학부모) | 3단계 |
| NTF-02-message-send.html | 메시지 발송 (관리자) | 3단계 |
| NTF-03-send-history.html | 발송 이력 (관리자) | 3단계 |
| NTC-01-notice-list.html | 전체공지 목록 | 4단계 |
| NTC-02-class-news.html | 반소식 목록 | 4단계 |
| NTC-03-notice-form.html | 공지 작성 | 4단계 |
| NTC-04-notice-detail.html | 공지 상세 | 4단계 |
| CNS-01-consultation-list.html | 문의 목록 (학부모) | 5단계 |
| CNS-02-consultation-form.html | 문의 작성 (학부모) | 5단계 |
| CNS-03-inquiry-inbox.html | 문의 모음 (관리자) | 5단계 |
| CNS-04-news-send.html | 아이소식 작성 (강사) | 5단계 |
| CNS-05-consultation-detail.html | 대화 상세 | 5단계 |
| GRD-01-grade-form.html | 성적 등록 | 6단계 |
| GRD-02-grade-list.html | 성적 조회 | 6단계 |
| HW-01-homework-form.html | 숙제 등록 | 6단계 |
| HW-02-homework-check.html | 숙제 검사 | 6단계 |
| HW-03-homework-list.html | 숙제 안내 (학부모) | 6단계 |
| RPT-01-learning-report.html | 학습 리포트 | 6단계 |
| OPS-01-schedule-calendar.html | 일정·캘린더 | 7단계 |
| OPS-02-dashboard.html | 통계 대시보드 | 7단계 |
| OPS-03-excel-io.html | 엑셀 가져오기·내보내기 | 7단계 |
| ADM-01-academy-info.html | 학원 정보 | 8단계 |
| ADM-02-user-roles.html | 사용자·권한 관리 | 8단계 |
| ADM-03-audit-log.html | 변경 이력 | 8단계 |
| MBR-07-login.html | 인증키 로그인 | 5단계 작업 1 |
| CHAT-01-ai-chat.html | AI 학원 비서 (채팅 UI) | 2단계·5단계 작업 3 |

전 화면 `viewport` 메타 보유, 반응형 (`@media`/유동 레이아웃) — 상세: `docs/mobile-check.md`.
인라인 JS `node --check` 35/35 통과.

---

## 5. 테스트 결과 요약

- **전체: 누적 318건 전부 PASS** (H2 인메모리, `@SpringBootTest` + 커스텀 JUnit 엔진 러너, 클래스별 단독 실행 기준)
- 단일 JVM 일괄 실행: **304/318** — 14건 실패는 테스트 격리 이슈로 인한 것 (코드 버그 아님). 상세: `docs/phase6-final.md` §1.2
- 단계별 내역:

| 구간 | 테스트 수 | 결과 |
|---|---|---|
| 1차~8단계 (FINAL-REPORT 초판: Class 15 · Attendance 16 · Notification 17 · Notice 14 · Consultation 20 · Learning 18 · Ops 26 · Admin 36 · FullScenario 2) | 164 | PASS |
| 1단계 보안 (X-Auth-Key·격리·역할 권한, 신규 18) | 누적 182 | PASS |
| 2단계 AI Agent (도구 7종·파싱·승인 플로우, 신규 54) | 누적 236 | PASS |
| 5단계 작업 1 (화면 인증 연결, 신규 17 — SecurityTest 35/35) | 누적 253 | PASS |
| 5단계 작업 2 (E2E 시나리오 A~D, 신규 4) | 누적 257 | PASS |
| 5단계 작업 3 (AI 비서 검증, 신규 27) | 누적 284 | PASS |
| 6단계 작업 1 (403 정책 확인 3 · BulkData 5) | 누적 292 | PASS |
| 6단계 작업 2 (격리 매트릭스 11 · 정합성 4 · 동시 수정 4 · 복구 4) | 누적 315 | PASS |
| 6단계 작업 4 (PerfReferenceTest 응답시간 측정 3) | 누적 **318** | PASS |
- 기존 코드 변경: 1차·1~8단계 기존 코드는 미수정 (318건 회귀로 검증). 6단계에서 코드 수정 2건만: ① 31개 화면 `REA_API_BASE` 중복 접두사 수정 ② 반 부분 업데이트 시 capacity null 초기화 버그 수정 (ClassService + ReaClass, 각 1~2행)
- 빌드: `mvn` 온라인/오프라인 불가 → `javac -encoding UTF-8` 직접 컴파일 + `/tmp/rea-build/TestRunner`
- 화면: `node --check` 35/35 통과

### 최종 통합 시나리오 (FullScenarioTest, 2건 PASS)
1. 학원 등록 → 원장 → 강사 → 학생 → 반 생성·배정 → 출결(등원 알림) → 알림 발송 →
   공지(알림) → 상담 문의·답변(상태 전이·알림) → 숙제 등록·검사(알림) → 성적 등록(알림) →
   진도·학습 리포트 → 일정 → 통계 대시보드 → 감사 로그 확인 → 통합 검색
2. 지점 등록 → 지점 학원 독립 운영 → 본원 격리 확인

---

## 6. 미구현/향후 과제

| # | 과제 | 현 상태 | 방향 |
|---|---|---|---|
| 1 | **인증키 인증 도입** | **완료** (초판의 "미구현"은 오래된 기술) | 1단계에서 X-Auth-Key 인증·학원별 격리(403)·역할별 권한 구현 (182/182 PASS). 5단계 작업 1에서 화면 인증 연결 완료 (253/253 PASS) |
| 2 | **실제 알림 발송 연동** | Mock 유지 (의도적) | `NotificationSender` 인터페이스 + `MockNotificationSender` 유지 방침. 실제 연동 시 체크리스트: ① FCM(푸시)/알림톡/SMS 발신 계약 체결 ② 발신번호·카카오 채널·템플릿 승인 ③ `NotificationSender` 실제 구현체 추가 (인터페이스 교체, Mock 제거 아님) ④ 채널별 비용·재시도 정책 확정 (실패 격리 원칙 유지 — 발송 실패가 등록 업무를 막지 않음) ⑤ 발송 이력 테이블(`ReaNotification`) 스키마 확장 검토 (채널별 메시지 ID·상태 코드) ⑥ 수신자 연락처 수집·보관 동의 절차 ⑦ 실제 발송은 사용자 별도 승인 필요 |
| 3 | **내신Wiki 실제 연동** | 스텁 | `WikiIntegrationPort` 구현체 (문항 조회·오답노트·시험범위). 내신Wiki 코드 수정 없이 참조만. 설계: `docs/wiki-integration.md` |
| 4 | **Report v2.8 고도화** | 데이터 API만 | v2.8 HTML에 `GET /reports/student/{id}` 바인딩 |
| 5 | **xlsx 네이티브** | CSV로 엑셀 호환 충족 | Apache POI 확보 시 xlsx 내보내기 확장 |
| 6 | **반복 일정** | 단건만 | 매주/매월 반복 규칙 + 일정 알림 발송 |
| 7 | 일정 알림 | 미구현 | 일정 등록 시 대상 학부모 알림 (NotificationService 연동) |
| 8 | 대시보드 확장 | 기본 지표만 | 기간별 출결률·반별 통계 |
| 9 | 감사 로그 행위자 보완 | AOP 경유 시 미상 (유지) | 인증 도입 후에도 AOP 경유 변경은 행위자 null(시스템). 서비스 시그니처에 행위자가 없어 구조적 한계 — 알려진 한계로 유지 |
| 10 | 지점 ID 발급 고도화 | max+1 단순 할당 | 운영 단계 시퀀스 테이블 등 확정 |
| 11 | 숙제·성적 알림 문구 스크립트 | 기본 문구 | 비투 스크립트 방식 정형 양식 고도화 |
| 12 | 첨부파일 실제 업로드 | 메타데이터만 | FileStorage 연동 |

## 6-1. 남은 문제 (5단계 작업 5 정리 → 6단계 작업 5에서 갱신)

| # | 문제 | 상태 | 비고 |
|---|---|---|---|
| R1 | CHAT-01 localStorage 키 이름 불일치 | **수정 완료 (6단계 작업 1)** | `rea-chat-auth-key` → `rea_auth_key` 통일, Playwright 회귀 5건 PASS. docs 내 역사적 언급은 변경 이력으로 유지 |
| R2 | 학부모 상담 답글·알림 읽음 403 | **의도적 유지 (별도 승인 필요 시 변경)** | `docs/security.md` §3.3 권한표: 학부모 허용 쓰기는 "문의 등록·공지 읽음 확인·키패드 출결"만. 상담 메시지 작성·종료, 알림 읽음 처리는 403 유지 (`ReaAccessRules.PARENT_WRITES`). 변경 시 권한표·E2E 매트릭스(D 시나리오) 동시 수정 필요 |
| R3 | 프론트 모바일 검증 | **빈 상태 75회 실측 완료, 실데이터 육안 재확인 권장** | 6단계 작업 1에서 25개 화면 × 3개 뷰포트 실측 (가로 스크롤·고정 요소 가림·텍스트 잘림 0건). 31개 화면 URL 수정 후 첫 정상 연동이므로 개발/스테이징 백엔드 대상 육안 확인 권장 |
| R4 | 월별 출결 N+1 (15,500 쿼리·51초) | **백엔드 최적화 승인 대기** | 집계 정확성 PASS, 병목은 순수 백엔드 쿼리 수. 월 범위 일괄 조회 후 메모리 그룹핑 권장. 시범 규모(30~100명)에서는 우회 가능 |
| R5 | `MemberService.updateStudent()` no-op + PUT 미등록 (학생 수정 불가) | **시범 운영 전 구현 필수** | 학생 정보 수정이 현재 불가능. 신규 API 구현 시 낙관적 락과 함께 적용 권장 |
| R6 | 테스트 격리 이슈 (단일 JVM 일괄 실행 14건 실패) | **CI 설계 시 해결 필요** | `BulkDataVerificationTest`의 `@BeforeAll` 시드 커밋이 공유 H2의 원장 시드와 충돌. 코드 버그 아님, 클래스별 단독 실행 시 전원 PASS |
| R7 | MockNotificationSender PII 로그 | **시범 운영 전 마스킹 필수** | INFO 로그에 수신자 이름·전화번호·제목·본문 기록. 이름 일부·전화번호 마스킹, 본문은 길이만 기록으로 변경 |
| R8 | fetch 타임아웃 미설정·일부 화면 네트워크 오류 미표시 | **안정화 후속 작업 권장** | api-client.js에 타임아웃(예: 15초) + 공통 오류 표시. 31개 화면 범위이므로 별도 작업으로 분리 |
| R9 | 학생 비활성 후 이력 조회 정책 미결정 | **시범 운영 전 정책 결정 필요** | DB는 보존, 서비스 조회는 차단 중. 퇴원생 과거 출결·성적 조회 운영 흐름 확정 필요 |
| R10 | 동시 수정 낙관적 락 미구현 (마지막 쓰기 승리) | **정책 결정 완료, 구현은 후속 승인 필요** | 5개 엔티티에 `@Version` 도입 시 스키마 변경 수반. 시범 규모에서는 수용 가능 |

- 전체 남은 문제 10건과 시범 운영 판정: `docs/phase6-final.md` §5·§6.

---

## 7. 아키텍처 요약

```
frontend/screens/*.html (35) ── UI Layer (교체 가능, 최소 기능)
        │  REST (/api/rea/{academyId}/**)
controller/* ── HTTP 입출력만
service/* ── 업무 로직 (DTO 입출력, UI 의존 없음)
domain/* ── 순수 엔티티 (rea_ prefix, academyId 멀티테넌트 키)
```

- **계층 분리**: Domain/Service/Controller 분리, DTO 입출력 준수 (전 단계)
- **멀티테넌트 격리**: 모든 엔티티에 `academyId`, Service에서 스코핑 (전 도메인 테스트 검증)
- **확장 원칙**: 8단계 감사 로그는 AOP로 기존 코드 무수정 확장. Repository는 독립 인터페이스 (빈 충돌 회피)
- **문서**: `docs/functional-spec-v1.md`, `docs/phase1~8-*.md`, `docs/mobile-check.md`, 본 보고서

## 8. 산출물 위치

- Backend: `~/workspace/rea-academy/backend/src/main/java/com/rea/academy/`
- Frontend: `~/workspace/rea-academy/frontend/screens/` (35개)
- DB: `~/workspace/rea-academy/db/migration/V1__rea_initial.sql` (Flyway 초안, 22개 테이블 — 운영 적용 금지)
- Config: `~/workspace/rea-academy/backend/src/main/resources/application-{dev,prod}.properties` (초안, prod는 플레이스홀더만)
- Docs: `~/workspace/rea-academy/docs/` (functional-spec-v1, phase1~8, mobile-check, FINAL-REPORT,
  security, agent, chat-ui, buildit-integration, wiki-integration, payment-policy, phase5-task1~5, phase6-task1~4, phase6-final)
- Tests: `~/workspace/rea-academy/backend/src/test/java/com/rea/academy/service/` (누적 318건)
