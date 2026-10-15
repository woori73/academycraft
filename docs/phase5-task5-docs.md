# 5단계 작업 5 — 연동 및 문서 정리 (작업 기록)

> 작성일: 2026-10-13
> 성격: **문서 작업만**. 코드 수정 없음. `~/workspace/wemade-reorg/` 읽기 전용 유지,
> EASY 장부 코드는 접촉하지 않음 (READ ONLY 방침 유지).

---

## 1. 수행 내역

### 1.1 `docs/buildit-integration.md` 보완 (§8 부록 신설)

BuildIt 홈페이지 ↔ REA API 계약을 부록으로 정리했다 (3단계 게이트 충족 전 개발 시작 금지 명시):

- **인증 방식**: `X-Auth-Key` 헤더 (유일). 인증키 발급 흐름 —
  원장·강사는 `POST /{academyId}/members` 등록 응답에 포함, 학부모는 학생 등록 시 학생 인증키 발급(=학부모 로그인 키),
  재발급은 원장 전용 `POST /{memberId|studentId}/renew-key`.
  로그인(역할 판별)은 `POST /api/rea/auth/key-login` (유일한 공개 API).
  URL 쿼리·딥링크에 인증키 포함 금지.
- **학원 식별자 전달**: URL 경로 `/api/rea/{academyId}/` + 헤더 인증키의 academyId 일치 검증
  (`ReaSecurityInterceptor` → 불일치 403, 키 누락·위조·비활성 401). BFF는 학원별 서비스 키 사용 (마스터 키 금지).
- **핵심 엔드포인트**: 78개 중 홈페이지 연동용 읽기 중심 목록 —
  `GET /{academyId}/info`, `GET /{academyId}/notices?scope=ACADEMY`, `GET /{academyId}/notices/{id}`,
  `GET /{academyId}/dashboard/stats`, `GET /{academyId}/students/{studentId}`(BFF만),
  `GET /{academyId}/consultations` 계열(BFF만), `GET /{academyId}/reports/student/{studentId}`(BFF만).
  홈페이지 위젯에서 REA 데이터 쓰기 금지 (쓰기는 REA 화면으로 이동).
- **CORS·세션 공유**: 1~3단계 모두 불필요 —
  브라우저가 REA API를 직접 호출하지 않고(BFF 서버 호출), 세션 공유 없이 서명 일회용 토큰 교환(2단계 SSO).
  향후 브라우저 직접 호출 필요 시에만 홈페이지 도메인 명시 allowlist 검토 (`*` 금지).

### 1.2 `docs/wiki-integration.md` 신규 작성

- 구현하지 않고 인터페이스 정의 + 데이터 흐름만 설계.
- 기존 스텁 위치·책임 문서화:
  `domain/learning/WikiIntegrationPort.java` (인터페이스, `resolve(WikiQuestionRef)`),
  `domain/learning/StubWikiIntegrationPort.java` (빈 값 반환),
  `domain/learning/WikiQuestionRef.java` (문항 ID 참조 값 객체 — 복사 금지 원칙),
  `ReaHomework`의 `wikiQuestionRef` 참조.
- 시퀀스 2종: ① REA 숙제 등록 → Wiki 문항 조회 (읽기 전용 pull, 참조 끊김 시 숙제 이력 유지),
  ② Wiki 오답(`wrong_note_entries`, conversation_id 기준) → REA 학습 리포트
  (`student_id ↔ conversation_id` 매핑 테이블은 3단계 범위).
- 제약: 내신Wiki 도메인 코드 수정 금지 (참조만), REA→Wiki 쓰기 금지, 중복 저장 금지.

### 1.3 `docs/payment-policy.md` 신규 작성 (1페이지)

- REA 수납·결제 미구현 유지 (의도적 설계 결정 — 책임 분리·결제선생 존재·회계 무결성).
- 결제선생 외부 연결 방침: 학원이 직접 계약, 플랫폼은 청구 링크만 제공,
  인증=API키+사업장코드+SHA-256, `POST /bill` 청구서 발송이 시작점 (건당 55원).
- REA(LMS)에 수납·회계 DB 없음 명시: 금액·수납 상태·청구서·정산 — 전부 저장 금지.
  결제 근거 필요 시 읽기 전용 조회만 허용.
- EASY 장부는 기존 운영 흐름 유지, 실데이터 작업 시 **READ ONLY** (기존 데이터·회계 로직 변경 금지).

### 1.4 알림 Mock 상태 명시

- 방침: `MockNotificationSender` 유지 (실제 발송 미연동은 의도적).
  `NotificationSender` 인터페이스 + Mock 구현체 구조 그대로 둔다.
- Mock → 실제 연동 시 체크리스트를 `docs/FINAL-REPORT.md` §6 #2에 명시:
  ① FCM/알림톡/SMS 발신 계약 ② 발신번호·카카오 채널·템플릿 승인 ③ `NotificationSender` 실제 구현체 추가
  (인터페이스 교체, Mock 제거 아님) ④ 채널별 비용·재시도 정책 (실패 격리 원칙 유지)
  ⑤ 발송 이력 테이블(`ReaNotification` — 이미 존재) 스키마 확장 검토
  ⑥ 수신자 연락처 수집·보관 동의 절차 ⑦ 실제 발송은 사용자 별도 승인 필요.

### 1.5 `docs/FINAL-REPORT.md` 업데이트

- **정정**: "인증키 인증(Security) 미구현" 오래된 기술을 "구현 완료 (1단계, 182/182 PASS)"로 정정
  (보고서 상단 정정 박스 + §2.2 + §6 #1).
- **구현 완료 기능 목록**: 1단계 보안·2단계 AI Agent·3단계 채팅 UI·5단계 작업 1~4 섹션 추가 (§1).
- **테스트 수**: 164건 → **누적 284/284 PASS** 단계별 내역 (§5):
  164 (1차~8단계) + 18 (보안) + 54 (Agent) + 17 (작업1) + 4 (작업2 E2E) + 27 (작업3) = 284.
- **API/화면 수**: 76개 → 78개 (Agent 2종 추가), 33개 → 35개 화면 (`MBR-07-login`, `CHAT-01-ai-chat` 추가).
- **스텁/미구현 목록**: 알림 Mock 유지 방침·감사 로그 행위자 알려진 한계·수납결제 제외 유지 (§2.2).
- **남은 문제 (§6-1 신설)**:
  - R1: CHAT-01 localStorage 키 불일치 (`rea-chat-auth-key` vs `rea_auth_key`) — 알려진 이슈, 동작 정상, 통일 시 코드 수정 승인 필요
  - R2: 학부모 상담 답글·알림 읽음 403 유지 — 의도적 (`security.md` §3.3 권한표, `ReaAccessRules.PARENT_WRITES`), 변경 시 별도 승인
  - R3: 모바일 검증 — 빈 상태 기준 완료, 실데이터 육안 재확인 권장

---

## 2. 변경 파일 목록

| 파일 | 변경 |
|---|---|
| `docs/buildit-integration.md` | §8 부록 신설 (API 계약 요약: 인증·식별자·핵심 엔드포인트·CORS/세션) |
| `docs/wiki-integration.md` | 신규 (연동 설계: 포트 위치·책임·시퀀스 2종·구현 체크리스트) |
| `docs/payment-policy.md` | 신규 (수납·결제 1페이지 방침 요약) |
| `docs/FINAL-REPORT.md` | 정정 + 5단계 반영 + 남은 문제 §6-1 |
| `docs/phase5-task5-docs.md` | 본 파일 (작업 기록) |

코드 변경: **없음**. DB 변경: 없음. 배포: 없음.

---

## 3. 검증

- 문서 간 정합성: `security.md` §3.3 권한표 ↔ `ReaAccessRules.java` (PARENT_WRITES) ↔ FINAL-REPORT §6-1 R2 — 일치 확인
- 테스트 수 정합성: 164+18+54+17+4+27 = 284, 각 단계 문서(phase5-task1~3, agent.md, FINAL-REPORT 초판)와 대조 — 일치
- CHAT-01 키 이름: `frontend/screens/CHAT-01-ai-chat.html` 171행 `rea-chat-auth-key` vs
  `frontend/components/api-client.js` 42행·`MBR-07-login.html` 60행 `rea_auth_key` — 불일치 실측 확인
- 경계 준수: wemade-reorg 읽기 전용 (열람만, 수정 없음), EASY 코드 미접촉, 운영 DB 미사용
