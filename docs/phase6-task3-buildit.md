# REA 6단계 작업 3 — BuildIt 홈페이지 연동 준비 상세 검증 기록

> 작성일: 2026-10-13
> 범위: 읽기 전용 조사 + 설계 보완. `~/workspace/wemade-reorg/`는 **읽기 전용 참조(수정 없음)**,
> EASY 코드 미접촉, 운영 DB 변경 없음, 배포 없음, 외부 발송 없음, 유료 API/LLM 없음
> 산출물: 본 문서 + `docs/buildit-integration.md` 보완(§9)
> 테스트: 신규 테스트 없음 (코드 변경 없음). 누적 315/315 유지

---

## 0. 핵심 질문에 대한 답 (요약)

> **"기존 홈페이지 제작 기능(편집기)을 변경하지 않고, 홈페이지 상담 신청 → REA 상담관리 연결이 가능한가?"**

**답: 조건부 가능 — 홈페이지 편집기 코드 변경 없이 연결 가능하나, "홈페이지 상담 신청 폼"이라는 전제 자체가 현재 코드에 존재하지 않는다.**

| 구분 | 판정 | 근거 |
|---|---|---|
| 홈페이지 → REA 학원관리 이동 링크 | **가능** (편집기 코드 변경 불필요) | 홈페이지 편집기는 콘텐츠(버튼/링크) 편집 기능이므로, 학원 운영자가 REA URL을 링크로 다는 것은 콘텐츠 편집이지 코드 변경이 아님. 학원 식별은 서브도메인 handle → 서버사이드 academy_id resolve로 확정 |
| 홈페이지 "상담 신청" → REA 상담관리 데이터 연결 | **조건부 가능** (REA 측 소규모 추가 필요) | 아래 §1 실측: 홈페이지에 **상담 신청 폼이 존재하지 않음**(연락처 채널 모달만). 연결하려면 REA 측에 "입학상담 신청(프로스펙트)" 접수 API + 공개 폼 페이지가 새로 필요. 홈페이지 측은 그 URL로의 링크만 추가하면 됨 |
| 익명 방문자가 REA 상담 API 직접 호출 | **불가능** | `POST /api/rea/{academyId}/consultations`는 X-Auth-Key 필수(익명→401) + 등록 학생(studentId/authKey) 필수. 방문자(프로스펙트) 개념이 REA에 없음 |
| 편집기 코드 수정 없이 홈페이지 서버가 폼 데이터를 REA로 자동 전송 | **불가능** | 홈페이지 서버/편집기 코드 변경이 필요한 영역. 본 작업 범위 밖 (사용자 승인 없는 편집기 변경 금지) |

---

## 1. BuildIt 홈페이지 "상담 신청" 실측 (wemade-reorg 읽기 전용 조사)

### 1.1 상담 신청 UI의 실제 정체

| 파일 | 내용 |
|---|---|
| `src/main/resources/templates/public/academy_public.html` L41–51 | "상담 신청하기" 모달. 주석: *"상담 신청 — 학원이 입력한 전화/이메일로 연결. 옵션은 academy_public.js가 채운다."* |
| `src/main/resources/static/js/academy_public.js` L42, L108, L1168–1192 | `consultChannels = BUSINESS_INFO.contactChannels(...)` → `<a href="tel:…/mailto:…/카카오…">` 링크 목록 렌더링. **폼 아님, 입력 필드 없음, 서버 전송 없음** |
| `src/main/resources/templates/public/academy_layout_preview.html` L65–70 | `consultationCta` 레이아웃 블록: `state=PREPARING`이면 "상담 신청은 준비 중이에요." 플레이스홀더, 아니면 **클릭 액션 없는 `<button>상담 신청</button>`** |
| `src/main/java/.../academy/layout/AcademyDefaultLayoutFactory.java` L74–79 | `consultationCtaConfig()`: `state=PREPARING`, `enabled=false`가 기본값 |

**결론: "상담 신청 데이터가 어디에 저장되는가"에 대한 답은 "저장되지 않는다"이다.**
홈페이지의 상담 신청은 이름·연락처·상담 내용을 입력받는 폼이 아니라,
학원이 등록한 연락처 채널(tel/mailto/카카오톡 등)로 연결해주는 **연락처 모달**이다.
따라서 "홈페이지 상담 신청 폼 → REA 상담관리"의 "폼 데이터 매핑"은 현재 코드 기준으로 매핑 대상 자체가 없다.

### 1.2 학원 식별 체계 (홈페이지 측)

| 항목 | 실측 |
|---|---|
| 서브도메인 필터 | `config/SubdomainAcademyFilter.java` — 서브도메인에서 학원 slug 추출 (`SLUG_ATTRIBUTE = "subdomainAcademySlug"`), 렌더 경로 `/subdomain-home` |
| 식별자 | `Academy.handle`(서브도메인) ↔ `academy_id`(숫자). `academies` 테이블이 원천 |
| 공개 페이지 | `{handle}.feel2real.co.kr` → 필터에서 slug resolve → 학원별 홈페이지 렌더링 |
| REA 측 식별자 | URL 경로 `/api/rea/{academyId}` (숫자). BuildIt의 `academy_id`와 **동일값 사용**이 전제 (기존 설계 §1.2와 동일) |

### 1.3 홈페이지 편집기 위치

- 학원 홈페이지 편집(빌더) UI는 `AcademyBuilderPageController` + `AcademyLayoutApiController`/`AcademyLayoutPageController` (GrapesJS 계열, 프론트 구현은 클로드 담당 영역 — 본 작업에서 미접촉).
- 레이아웃 블록 타입: `AcademyLayoutBlockType.CONSULTATION_CTA("consultationCta")`.
- 편집기는 블록의 **설정값(config)** 을 편집하는 도구이므로, 상담 신청 버튼에 REA URL 링크를 거는 것은
  **콘텐츠 편집(링크 설정) 수준**이며 편집기 코드 변경이 아니다.

---

## 2. REA 상담 API 실측

### 2.1 엔드포인트 (`ConsultationController`)

| Method | Path | 인증 | 비고 |
|---|---|---|---|
| POST | `/api/rea/{academyId}/consultations` | X-Auth-Key 필수 | 스레드 생성 |
| GET | `/api/rea/{academyId}/consultations` | X-Auth-Key 필수 | 목록 (?status/?type/?studentId/?authKey/?targetStudentId) |
| GET | `/api/rea/{academyId}/consultations/{id}` | X-Auth-Key 필수 | 상세+메시지 |
| POST | `/api/rea/{academyId}/consultations/{id}/messages` | X-Auth-Key 필수 | 메시지 작성 (학원 발신 시 학부모 알림) |
| POST | `/api/rea/{academyId}/consultations/{id}/close` | X-Auth-Key 필수 | 스레드 종료 |
| POST | `/api/rea/{academyId}/consultations/{id}/messages/{messageId}/read` | X-Auth-Key 필수 | 읽음 처리 |

### 2.2 스레드 생성 요청 스펙 (`ConsultationDto.CreateRequest`)

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| studentId | Long | ○ (authKey와 택1) | **등록된 ReaStudent의 ID**. `ParentScope.forceOwnStudent()`로 검증 — 미등록자는 스레드 생성 불가 |
| authKey | String | ○ (studentId와 택1) | 등록 학생의 인증키 (=학부모 로그인 키) |
| type | String | ○ | `INQUIRY`(학부모→학원 문의) / `NEWS`(학원→학부모 아이소식). 그 외 값 불가 |
| title | String | ○ | 빈 값 불가 |
| content | String | △ | INQUIRY 첫 문의/NEWS는 필수, 그 외 선택 |
| authorMemberId | Long | △ | NEWS 작성자(원장·강사) 필수 |
| attachmentFileName / attachmentFileKey | String | × | 선택 |

응답: `CreateResponse { consultation(DetailResponse), firstMessageId, notificationSentCount, notificationFailedCount, notificationSkippedCount, notificationError }`

### 2.3 인증 체계 (`SecurityConfig`, `AuthKeyFilter`)

- `POST /api/rea/auth/key-login`이 **유일한 공개 API** (`/api/rea/auth/**` permitAll)
- 그 외 `/api/rea/**`는 전부 인증 필요. 헤더 `X-Auth-Key` 누락·위조·비활성 → **401**
- URL `{academyId}` vs 인증키의 academyId 불일치 → **403** (`ReaSecurityInterceptor`)
- 학부모(PARENT): 본인 자녀 스레드만 생성·조회, NEWS 생성 시 403 (`ConsultationService.create` L68–74)

### 2.4 방문자(프로스펙트) 개념의 부재 — 연결의 핵심 장애물

- REA의 상담은 **등록 학생·학부모 간 1:1 소통** 전용이다. `ConsultationType`은 `INQUIRY`/`NEWS` 2종뿐이며,
  "입학상담 신청"(예비 학부모·방문자의 문의) 타입이 없다.
- `create()`는 `requireStudent(academyId, …)`를 강제하므로, REA에 등록되지 않은 방문자는
  기존 API로 상담 스레드를 만들 방법이 **원천적으로 없다**.
- 따라서 홈페이지(방문자 대상) 상담 신청을 REA 상담관리에 연결하려면
  **REA 측에 프로스펙트 접수 수단(공개 API + 공개 폼 페이지)이 신규로 필요**하다.
  이는 "대규모 기능 추가"가 아닌 소규모 확장(신규 엔드포인트 1~2개 + 공개 화면 1개)이다.

---

## 3. 필드 매핑표

### 3.1 현재 홈페이지 "상담 신청" (연락처 모달) → REA

| 홈페이지 측 (현재) | REA 상담 API 필드 | 매핑 가능 여부 |
|---|---|---|
| (없음 — 폼이 존재하지 않음) | studentId / authKey | **불가** — 방문자는 등록 학생이 아님 |
| (없음) | type | **불가** — INQUIRY/NEWS 모두 등록자 전제 |
| (없음) | title / content | 매핑 대상 데이터 자체가 없음 |

### 3.2 REA에 "입학상담 신청" 공개 접수 추가 시 (제안) — 홈페이지 폼 신설 가정

홈페이지 측에 실제 상담 신청 폼(이름·연락처·상담 내용)을 신설하고(편집기 콘텐츠 수준 또는 REA 공개 페이지),
REA에 프로스펙트 접수 API를 추가한다는 전제 하의 매핑:

| 홈페이지 폼 필드 (신설 가정) | REA 프로스펙트 접수 API (신규 제안) | 비고 |
|---|---|---|
| 이름 | `applicantName` | 필수 |
| 연락처(휴대전화) | `applicantPhone` | 필수. 개인정보 → REA 관리자만 조회 (학부모 개인정보 노출 규칙과 동일) |
| 상담 희망 내용 | `content` | 필수 |
| 관심 반/과정 (선택) | `interestedClass` | 선택. REA 반 목록과 매칭은 관리자가 수동 |
| 희망 상담 방식 (선택) | `preferredChannel` | 전화/방문 등. 선택 |
| academyId | URL 경로 `{academyId}` | 홈페이지 서버사이드 handle→academy_id resolve로 주입. 방문자가 직접 입력하지 않음 |

**인증**: 프로스펙트 접수는 공개 API여야 하므로(방문자는 인증키 없음),
기존 X-Auth-Key 체인과 **별도 경로**로 설계한다 (예: `POST /api/rea/{academyId}/inquiries/public`).
남용 방지: rate limit + 학원별 일일 접수 상한 + 관리자 승인 후 정식 스레드 전환.
개인정보(연락처)는 원장·담당 강사만 조회 가능하도록 역할 제한.

---

## 4. 연동 시나리오 (홈페이지 편집기 코드 변경 없음 전제)

### 시나리오 1 — 링크 방식 (REA 코드 변경 없음, 즉시 가능)

```
[홈페이지 방문자]                    [REA]
"학원관리" 메뉴/버튼 클릭
  └─ href="https://rea.{도메인}/a/{academyId}/..." (편집기에서 링크 콘텐츠로 설정)
       └─ REA 로그인 화면 → 인증키 입력 → 자기 학원 관리 화면
```

- 작업: 홈페이지 편집기에서 메뉴/버튼에 REA 딥링크 추가 (콘텐츠 편집). `{academyId}`는 홈페이지 서버사이드에서 주입.
- REA 코드 변경: 없음. 전제조건: REA 배포 URL 확정, `academy_id` = REA `academyId` 일치.
- 한계: 상담 "신청"이 아닌 관리 화면 이동. 방문자 상담 접수는 해결되지 않음.

### 시나리오 2 — REA 공개 "입학상담 신청" 페이지 + 홈페이지 링크 (REA 소규모 추가)

```
[홈페이지 방문자]                                    [REA]
"상담 신청" 버튼 클릭
  └─ href="https://rea.{도메인}/a/{academyId}/inquiry" (공개 페이지, 인증 불필요)
       └─ 이름·연락처·상담 내용 입력 → POST /api/rea/{academyId}/inquiries/public
            └─ REA에 "입학상담" 접수 건 생성 → 원장/강사에게 알림 → CNS 화면에서 확인·답변
```

- 홈페이지 측 작업: 기존 연락처 모달에 "온라인 상담 신청" 링크 1개 추가 (콘텐츠 편집 수준).
  또는 `consultationCta` 블록의 버튼 href를 REA 공개 페이지로 설정.
- REA 측 작업 (신규, 소규모): ① 프로스펙트 접수 공개 API 1개 ② 공개 폼 페이지 1개(CNS 계열) ③ 관리자 접수 목록 조회.
  기존 인증 체계와 분리, rate limit·개인정보 역할 제한 포함.
- 편집기 코드 변경: 없음.

### 시나리오 3 — 홈페이지 폼 → REA API 직접 호출 (불가)

- 홈페이지에 실제 폼을 신설하고 그 데이터를 REA로 자동 전송하려면
  홈페이지 서버(또는 편집기)에 폼 액션/웹훅 설정 기능이 필요 → **편집기·서버 코드 변경 필수**.
  본 작업의 "편집기 변경 금지" 조건 하에서는 불가.
- 또한 브라우저에서 REA API를 직접 호출하면 인증키/서비스 키 노출 문제가 있어
  BFF 프록시가 필요 (§5.1-3) — 이 역시 홈페이지 서버 코드 변경이다.

---

## 5. 독립성 유지 확인 (3-3)

| 검사 항목 | 결과 |
|---|---|
| REA 백엔드가 wemade-reorg 코드를 import하는가 | **없음** — `grep "import f2r"` 전체 백엔드에서 0건. REA는 독립 Maven 프로젝트(`backend/pom.xml`), 패키지 `com.rea.academy` |
| REA 프론트가 홈페이지 JS/CSS에 의존하는가 | **없음** — `frontend/screens/*.html`은 독립 정적 페이지 (api-client.js 공통 클라이언트 사용) |
| 홈페이지 편집기가 REA 코드를 참조하는가 | **없음** — 편집기(GrapesJS 계열)는 wemade-reorg 내부 자산. REA는 URL 링크로만 연결 |
| REA 독립 배포 가능 여부 | **가능** — 별도 빌드·별도 DB(H2/운영 DB 분리 전제)·별도 인증 체계. `docs/security.md` 이중 방어 체인에 홈페이지 의존성 없음 |
| 공유되는 것 | **URL + 학원 식별자 값(`academy_id` = `academyId`)의 약속뿐**. 코드·DB·세션 공유 없음 |

---

## 6. 막힌 부분 / 추가 승인이 필요한 것

1. **홈페이지에 상담 신청 "폼"이 없음** (§1.1) — "폼 → REA" 연결 논의의 전제가 성립하지 않음.
   방문자 상담 접수를 원하면 ① REA 공개 접수 페이지 신설(시나리오 2) 또는
   ② 홈페이지 측 폼 신설(편집기/서버 코드 변경 필요 — 별도 승인 사항) 중 택1.
2. **REA에 프로스펙트(예비 학부모) 개념이 없음** (§2.4) — 시나리오 2의 REA 추가 개발에 대한 사용자 승인 필요.
   범위: 공개 접수 API 1개 + 공개 폼 1개 + 관리자 조회. "대규모 기능 추가"는 아님.
3. **REA 배포 URL·도메인 미확정** — 시나리오 1의 딥링크도 URL이 확정돼야 설정 가능.
4. **`academy_id` = REA `academyId` 일치 확인** — 프로비저닝 시 고정 필요 (기존 설계 §3.3).
5. **시나리오 3(폼 자동 전송)** — 편집기·서버 코드 변경이 필요하므로 본 작업 범위 밖. 필요 시 별도 승인.

---

## 7. 기존 설계 문서와의 정합성

- `docs/buildit-integration.md` §1(옵션 A 링크 방식 권장) — 본 검증에서 재확인. 시나리오 1이 §4.1 1단계와 일치.
- §4.3(3단계: 홈페이지에서 REA 데이터 쓰기 금지) — 시나리오 3이 이에 해당하므로 "하지 않는 것" 유지.
- §8 API 계약 — §2 실측으로 보완 (§9에 반영).
- 본 문서는 4단계 설계의 "홈페이지 상담 신청 폼이 있다"는 암묵적 전제를 **실측으로 정정**한다.

---

*읽기 전용 조사: wemade-reorg 파일 수정 없음. 본 문서의 모든 wemade-reorg 경로 인용은 조사 시점(2026-10-13) 기준이다.*
