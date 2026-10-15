# BuildIt 홈페이지 ↔ REA 학원관리 통합 설계 (4단계 — 설계만, 구현 없음)

> 작성일: 2026-10-13
> 단계 성격: **설계 전용**. 코드 수정 없음. `~/workspace/wemade-reorg/`는 읽기 전용 참조,
> EASY 장부 코드는 절대 접촉 금지, 운영 DB 관련 내용 없음.
>
> 참조: `docs/FINAL-REPORT.md` (REA 8단계 구현 현황), `docs/security.md` (X-Auth-Key 인증),
> `docs/agent.md` (AI Agent), `~/workspace/research_notes/buildit-reuse-inventory-20261013-1105/report.md` (BuildIt 자산 인벤토리),
> `~/workspace/buildit-phase2/final/integration-design.md` (Wiki→LMS→Report 통합 설계)

---

## 0. 전제 정리

| 항목 | BuildIt 측 (홈페이지 플랫폼) | REA 측 (학원관리) |
|---|---|---|
| 코드베이스 | `~/workspace/wemade-reorg/` (feel2real-buildit 백엔드, 읽기 전용) | `~/workspace/rea-academy/` (독립 구현) |
| 학원 식별자 | `Academy.handle` (서브도메인) + `academy_id` | URL 경로의 `{academyId}` (숫자) |
| 인증 방식 | JWT + Naver OAuth (`domain/user/`) | `X-Auth-Key` 헤더 인증키 (회원가입 없음, `docs/security.md`) |
| 테넌트 격리 | `SubdomainAcademyFilter` + `verifyOwnsAcademy` (fail-closed) | `AuthKeyFilter` + `ReaSecurityInterceptor` + Service 스코핑 (이중 방어) |
| 학부모 계정 | **없음** (`Student.parent_name/phone` 컬럼만 존재) | 학생 인증키 = 학부모 로그인 키 (PARENT 역할) |

핵심 사실: BuildIt과 REA는 **별도 코드베이스·별도 인증 체계**다. BuildIt은 홈페이지 제작·게시 플랫폼이고,
REA는 학원 운영 관리(출결·공지·상담·숙제·성적) 시스템이다. 통합은 "하나로 합치기"가 아니라
**"홈페이지에서 REA 기능으로 끊김 없이 이동하고, 필요한 데이터만 읽기 전용으로 공유"**하는 것이 목표다.

---

## 1. BuildIt 홈페이지 ↔ REA 연결 구조

### 1.1 옵션 비교

| 기준 | 옵션 A: 링크 방식 | 옵션 B: 임베드 방식 | 옵션 C: API 방식 |
|---|---|---|---|
| 방식 | 홈페이지 메뉴에서 REA 화면으로 이동 (새 탭/동일 탭) | 홈페이지 내 iframe/위젯으로 REA 화면 삽입 | 홈페이지가 REA REST API를 호출해 데이터를 직접 렌더링 |
| 구현 비용 | 최소 (메뉴 링크 1줄 + 학원 식별자 매핑) | 중 (iframe 리사이즈·쿠키 정책·X-Frame-Options 조정) | 중~대 (BFF 프록시·캐시·위젯 UI 개발) |
| 인증 처리 | REA 로그인 화면에서 인증키 입력 (별도 로그인) | SSO 없이는 iframe 안에서도 로그인 필요. SSO 있으면 원클릭 | 홈페이지 서버가 서비스 키로 호출 → 방문자에게는 로그인 불필요 (공개 데이터만) |
| 사용자 경험 | 화면 전환 발생 (홈페이지↔REA 왕복) | 홈페이지 안에서 완결 (경험 최상) | 홈페이지 디자인에 자연스럽게 녹음 |
| 보안 리스크 | 낮음 (각 시스템이 자기 인증·인가 유지) | 중간 (클릭재킹·서드파티 쿠키·세션 유출 면적) | 중간 (API 키 관리·과다 호출·데이터 노출 범위 설계 필요) |
| 유지보수 | REA 화면 변경이 홈페이지에 영향 없음 | REA 화면 변경 시 임베드 깨짐 가능 (높이·라우팅) | REA API 변경 시 위젯 수정 필요 |
| 적합 용도 | **관리 기능 전체** (출결·상담·성적 입력 등 관리자/학부모 업무 화면) | 학부모용 조회 화면 (출결 현황·공지·숙제 안내) | 공개·요약 정보 (공지 목록 미리보기·학원 소개용 통계) |

### 1.2 학원 식별자 전달 방식

BuildIt은 이미 서브도메인 `handle` → `academy_id` 매핑 인프라를 갖고 있다
(`SubdomainAcademyFilter`, `verifyOwnsAcademy`). REA는 URL 경로의 `{academyId}`를 테넌트 키로 쓴다.
두 식별자를 연결하는 규칙:

1. **매핑의 원천**: BuildIt `academies` 테이블 (`handle` ↔ `academy_id`)이 원천이다. REA의 `academyId`는
   BuildIt의 `academy_id`와 **동일한 값을 사용**한다 (REA 8단계 지점 관리도 숫자 ID 체계).
   즉, 홈페이지(서브도메인)에서 자기 학원의 `academy_id`를 아는 것은 서버사이드에서 자명하다.
2. **전달 방식**: 홈페이지 서버사이드에서 `handle` → `academy_id`를 resolve한 뒤,
   REA 딥링크를 생성한다. 예: `https://rea.{도메인}/a/{academyId}/notices`
   - 브라우저(클라이언트 JS)가 handle→academyId 매핑 테이블을 들고 있지 않는다.
   - 매핑은 서버 렌더링 시점에 주입하거나, 홈페이지 백엔드의 설정값으로 보관한다.
3. **프로비저닝 시 고정**: BuildIt에서 학원(홈페이지)이 생성될 때 REA 학원도 함께 생성되면
   (3.3절), `academy_id`가 양쪽에 동일하게 존재하므로 이후 매핑 조회가 불필요하다.

### 1.3 인증 연계

| 주체 | BuildIt 계정 | REA 인증 | 1단계 (링크) | 2단계 (SSO) |
|---|---|---|---|---|
| 원장 | TUTOR (원장) 로그인 | 원장 인증키 (ACADEMY_OWNER) | REA 로그인 화면에서 인증키 입력 (별도 로그인 허용) | BuildIt 로그인 세션 → 일회용 서명 토큰으로 REA 자동 로그인 |
| 강사 | TUTOR 로그인 | 강사 인증키 (TEACHER) | 동일 (별도 로그인) | 동일 (SSO) |
| 학부모 | **계정 없음** | 학생 인증키 (PARENT) | REA 로그인 화면에서 인증키 입력 (유일한 경로) | SSO 대상 아님 — 인증키 로그인 유지 |
| 홈페이지 방문자 | 없음 | 없음 | REA 접근 불가 | REA 접근 불가 (공개 위젯은 옵션 C의 읽기 API만) |

**SSO 필요 여부 판단**:
- **1단계에서는 SSO 불필요.** 별도 로그인을 허용한다. 이유: (1) 학부모는 BuildIt 계정이 없어 SSO 대상이 될 수 없고,
  어차피 인증키 로그인이 필요하다. (2) 원장·강사도 인증키 1개 입력으로 로그인되는 구조라 진입 장벽이 낮다.
  (3) SSO는 토큰 교환·세션 동기화 등 양쪽 코드 변경이 필요한데, 1단계 목표(최소 비용 즉시 연결)와 맞지 않는다.
- **2단계에서 SSO 도입.** 원장·강사에 한해 BuildIt 로그인 → REA 원클릭 이동을 제공한다.
  학부모는 인증키 로그인을 그대로 유지한다 (학부모용 BuildIt 계정 신설은 별도 정책 결정 사항이며 본 설계 범위 밖).

### 1.4 권장안

> **1단계: 옵션 A (링크 방식) + 서버사이드 handle→academyId 매핑. 별도 로그인 허용, SSO 없음.**
> **2단계: 옵션 B (임베드) — 학부모 조회 화면에 한해, SSO(원장·강사)와 함께 도입.**
> **옵션 C (API 방식)는 3단계에서 공개 요약 위젯(공지 미리보기 등)에만 제한적으로 사용.**

**이유**:
1. REA의 핵심 사용자는 학원 관리자(원장·강사)와 학부모로, 이들이 쓰는 화면(출결 입력·상담 답변·성적 등록)은
   REA 33개 화면에 이미 완성되어 있다. 이를 홈페이지에 다시 구현하거나(옵션 C 전면 적용) iframe으로 감싸는 것(옵션 B 전면 적용)보다
   **완성된 화면으로 바로 연결하는 것이 비용 대비 효과가 가장 크다**.
2. 인증 체계가 다르다(JWT vs 인증키). 링크 방식은 각 시스템이 자기 인증을 유지하므로
   **보안 경계가 명확**하고, 1단계 보안(`docs/security.md`의 이중 방어)과 정합성이 깨지지 않는다.
3. 학부모는 BuildIt 계정이 없어 SSO 혜택을 받을 수 없다. 임베드·SSO에 투자해도 학부모 경험은 개선되지 않으므로,
   2단계 임베드는 "학부모 조회 화면"처럼 로그인 후에도 인증키 세션으로 동작하는 범위로 한정한다.
4. 옵션 C는 홈페이지 방문자(비로그인)에게 보여줄 **공개 요약 정보**에만 쓴다. 관리·개인정보 영역을 API로 노출하면
   인가 설계가 복잡해지고, REA의 역할별 권한표(`docs/security.md` §3)를 홈페이지 측에서 중복 구현해야 한다.

---

## 2. 기능 경계 명확화 (중복 방지)

원칙: **같은 데이터를 두 시스템이 각자 쓰지 않는다.** 한쪽이 source of truth가 되고, 다른 쪽은 읽기 전용으로 참조한다.
신규 개발이 필요한 영역은 REA가 담당하고, BuildIt 기존 자산은 수정 없이 참조만 한다.

| 기능 영역 | LMS 기존 (BuildIt) | REA 신규 | 경계 규칙 |
|---|---|---|---|
| 학생 관리 | `Student` 엔티티 (User 1:1, JWT 로그인, `school_name`/`grade`/`parent_name`/`parent_phone`) | `ReaStudent` (academyId 격리, 인증키, 반 배정 단위) | **분리 유지.** LMS Student = 온라인 학습 플랫폼 계정(수강·콘텐츠 접근). REA ReaStudent = 학원 오프라인 관리 대장(출결·반·상담). 단기 통합 시 ID 매핑 테이블이 필요해 비용이 크므로, 필요 시 `lms ↔ rea` 학생 매핑 테이블로 **단방향 참조만** 허용. 어느 한쪽을 삭제해도 다른 쪽에 영향 없도록 FK를 걸지 않는다. |
| 성적 | `StudentScore` (엔티티만 존재, 입력·조회 API 없음. `exam_id`·`source_id`로 내신Wiki 연결) | 성적 등록·조회 API (`POST/GET /grades`), 0≤점수≤만점 검증, 성적 알림 | **쓰기는 REA, 시험 원천은 Wiki/LMS.** 학원 일상 성적(쪽지시험·수행평가 등)은 REA에 입력한다. 내신Wiki 시험 결과는 `StudentScore`가 원천이며, REA는 읽기 전용으로 참조한다 (3.2절). 같은 시험을 양쪽에 중복 입력하지 않는다. |
| 숙제/학습 | `LmsContent` (복습영상 등록→발행→학생 조회, `LmsLearnerAccessService` 수강권 게이트) | 숙제 등록(반별·학생별·Wiki 문항 참조)·검사(완료/미완료·점수·코멘트)·안내, 진도 기록 | **LMS = 콘텐츠 유통, REA = 숙제 부여·검사·학부모 안내.** 복습영상 자체는 LMS에 두고, REA 숙제는 "영상 시청" 같은 과제를 `LmsContent` 링크 참조로 걸 수 있다. 숙제 검사·미제출 추적은 REA 전담. |
| 공지/상담 | 수업 커뮤니티 (`lessonId` 기준, `GENERAL`/`QNA`/`NOTICE` 유형, 수업 상품 중심) | 전체공지·반소식 (학원/반 기준), 1:1 문의·아이소식 스레드 (학부모 소통 중심) | **상품 관련은 LMS, 학원 운영·학부모 소통은 REA.** 수업 자료·수업 Q&A는 수업 커뮤니티에, 학원 공지·출결 알림·학부모 1:1 상담은 REA에. `lessonId` 기준과 반(Class) 기준이 1:1로 매핑되지 않으므로 도메인을 합치지 않는다. |
| 수강권 | `LmsEnrollment` (유료/무료 강좌 수강권. `student_id`+`academy_id`+`lesson_id`(LessonProduct FK), 상태·부여경로) | 반 편성 (오프라인 반 운영 단위. 정원·시간표·담당강사, 학생 배정·이동·종료) | **개념이 다르다.** 수강권 = "온라인 강좌를 들을 권리"(상품 구매 단위). 반 편성 = "오프라인 수업을 듣는 집단"(운영 단위). 수강권을 반 소속으로 오용하지 않는다. 온라인 강좌 수강생에게 오프라인 반을 안내할 때는 매핑 테이블로 연결만 한다. |
| 수납·결제·회계 | 결제선생 외부 연결 방침 (학원이 직접 계약, 플랫폼은 청구 링크만 제공). EASY 장부 기존 운영 (READ ONLY) | **개발하지 않음 (재확인)** | REA에 결제·수납·회계 기능을 만들지 않는다. 홈페이지의 결제 버튼은 결제선생 청구 링크로만 연결하고, 장부 집계는 EASY 기존 흐름을 그대로 둔다. REA의 반·학생 데이터가 결제 근거로 필요하면 **읽기 전용 조회**까지만 허용하고, 금액·수납 상태는 절대 REA에 저장하지 않는다. |

### 경계 위반 방지 체크리스트 (신규 개발 시)

- [ ] 같은 의미를 가진 데이터를 두 DB에 중복 저장하지 않는가? (저장한다면 어느 쪽이 원천인지 명시)
- [ ] BuildIt 기존 테이블(`academies`, `users`, `students`, `lms_*`, `easy_*`)에 컬럼을 추가·변경하지 않는가?
   (필요하면 REA 측 별도 테이블 + `academy_id` 연결)
- [ ] 학부모 개인정보(`parent_phone` 등)가 홈페이지 공개 API로 노출되지 않는가?
- [ ] 결제·수납·회계 로직이 REA에 들어오지 않았는가?

---

## 3. 데이터 흐름 설계

### 3.1 BuildIt 홈페이지 → REA

```
[BuildIt 홈페이지]                                    [REA]
서브도메인 handle ──(서버사이드 resolve)──▶ academyId ──▶ 딥링크 생성
  예) "학원관리" 메뉴 → https://rea.{도메인}/a/{academyId}/dashboard

[홈페이지 → REA, 쓰는 방향] — 프로비저닝 (3.3절)
  학원 등록 이벤트 → REA 학원 생성 (PUT /api/rea/{academyId}/info, 서비스 토큰)
  원장 정보 → REA 원장 회원 생성 → 원장 인증키 발급 → 홈페이지 관리자 패널에 표시

[REA → 홈페이지, 읽는 방향] — 3단계 위젯용 (읽기 전용)
  홈페이지 서버(BFF) → REA 읽기 API (공지 목록·학원 소개용 통계)
  ※ 브라우저가 REA API를 직접 호출하지 않는다 (키 노출 방지, 5.1절)
```

데이터 흐름 요약:
- **쓰기 방향**: 홈페이지 → REA는 **학원 프로비저닝(생성 시 1회)** 뿐이다. 일상 운영 데이터는 REA에서 직접 입력한다.
- **읽기 방향**: REA → 홈페이지는 **3단계에서** 공개 요약 정보(공지 미리보기 등)만 제공한다.
- 홈페이지 방문자(비로그인)는 REA의 어떤 개인정보·관리 API에도 접근하지 않는다.

### 3.2 REA → LMS (성적·숙제 연동 방향)

`integration-design.md`의 Wiki→LMS→Report 흐름과 REA의 `WikiIntegrationPort`(스텁)를 연결한다.
방향은 **REA가 LMS/내신Wiki를 읽기 전용으로 참조(pull)** 하는 단방향이다. LMS가 REA를 쓰지 않는다.

```
[내신Wiki] ──(문항·정답·해설 원천)──▶ [REA 숙제 등록]
  REA `POST /homework` 시 `wikiQuestionRef`(문항 ID 목록) 저장
  → WikiIntegrationPort 구현체(신규)가 questions 테이블 조회 (읽기 전용, Wiki 코드 수정 없음)

[내신Wiki/LMS] ──(시험 결과 원천)──▶ [REA 성적 조회]
  StudentScore (exam_id·source_id로 Wiki 시험과 연결) → REA 성적 화면에서 "시험 성적 가져오기" 참조
  → REA grades 테이블에 복사하지 않고, 조회 시점에 참조 조인 (중복 저장 금지)

[REA] ──(학습 리포트 데이터 API)──▶ [Report v2.8]
  GET /reports/student/{studentId} (성적·숙제·진도·출결 종합, FINAL-REPORT §3)
  → v2.8 HTML의 buildExam()/buildWeekly() 바인딩 포인트에 주입 (integration-design.md §3-5)
```

규칙:
1. **쓰기 방향은 REA → REA 테이블만.** REA가 `questions`, `student_scores`, `lms_contents`에 쓰지 않는다.
2. **문항 참조는 ID 참조.** REA 숙제가 Wiki 문항을 "복사"하지 않고 `wikiQuestionRef`로 참조한다.
   Wiki 문항이 수정되면 REA 숙제에도 최신 문항이 반영되는 구조를 기본으로 한다.
   (스냅샷이 필요한 경우 — 시험 출제 후 문항 고정 — 는 별도 정책으로 스냅샷 테이블을 둔다.)
3. **오답노트 연동**: `wrong_note_entries`는 `conversation_id` 기준이라 REA와 직접 연결되지 않는다.
   연동 시 `student_id ↔ conversation_id` 매핑 테이블이 필요하며, 이는 3단계(데이터 동기화) 범위다.

### 3.3 인증키 발급 흐름 (홈페이지 학원 등록 → REA 학원 생성 연동)

**1단계 (수동 프로비저닝)**:
1. BuildIt에서 학원(홈페이지) 등록 → `academies`에 `academy_id`·`handle` 생성 (기존 흐름, 변경 없음)
2. 학원 관리자(또는 운영자)가 REA 관리자 화면에서 학원 정보 upsert
   (`PUT /api/rea/{academyId}/info`, REA 8단계 API) — 이때 `academyId`는 BuildIt의 `academy_id`와 동일값 사용
3. 원장 회원 등록 (`POST /{academyId}/members`, role=원장) → 원장 인증키 발급
4. 홈페이지 관리자 패널의 "학원관리" 메뉴에 `{academyId}` 기반 REA 딥링크 설정

**2단계 (자동 프로비저닝)**:
1. BuildIt 학원 등록 트랜잭션(또는 등록 후크)에서 REA 프로비저닝 API 호출
   - 호출 주체: BuildIt 서버 (서비스 토큰 인증, 사람 아님)
   - `PUT /api/rea/{academyId}/info` (학원 생성) → `POST /{academyId}/members` (원장 생성)
2. 발급된 원장 인증키는 **DB에만 저장**하고, 홈페이지 관리자 패널에는 "최초 1회 확인용"으로만 표시 후 마스킹
   (인증키는 비밀번호와 동급 취급, 5.3절)
3. 실패 시 재시도 큐: 학원 등록이 REA 프로비저닝 실패로 롤백되지 않도록 분리 (홈페이지 등록 우선, REA는 eventual consistency)

**하지 않는 것**: 홈페이지 회원가입 시 REA 회원을 자동 생성하지 않는다.
BuildIt의 `User`(JWT/Naver OAuth)와 REA의 `ReaMember`(인증키)는 별개 체계이며,
학부모는 BuildIt 계정 자체가 없다. 자동 동기화는 두 인증 체계의 충돌을 만들 뿐이다.

---

## 4. 단계별 도입 방안

### 4.1 1단계: 링크 연결 (최소 비용, 즉시 가능)

| 항목 | 내용 |
|---|---|
| 목표 | 홈페이지에서 REA로 이동할 수 있다 |
| 전제조건 | ① REA 배포 URL 확정 (HTTPS) ② `docs/security.md`의 X-Auth-Key 인증이 배포 환경에 적용됨 ③ BuildIt `academy_id` = REA `academyId` 일치 확인 |
| 작업 범위 | 홈페이지 메뉴(헤더/푸터 또는 관리자 패널)에 "학원관리" 링크 추가. 서버사이드에서 `handle` → `academy_id` resolve 후 `https://rea.{도메인}/a/{academyId}/...` 딥링크 생성. 역할별 진입점 2종: 관리자용(대시보드 `OPS-02`), 학부모용(공지·숙제 안내 `NTC-01`/`HW-03`) |
| 예상 규모 | 소 (홈페이지 템플릿 수정 + 매핑 설정. REA 코드 변경 없음) |
| 인증 | 별도 로그인 (REA 로그인 화면에서 인증키 입력) |
| 완료 기준 | 홈페이지 메뉴 클릭 → REA 로그인 화면 → 인증키 입력 → 자기 학원 데이터만 보임 |

### 4.2 2단계: SSO/임베드 (사용자 경험 개선)

| 항목 | 내용 |
|---|---|
| 목표 | 원장·강사는 BuildIt 로그인 상태에서 REA로 원클릭 이동. 학부모 조회 화면을 홈페이지 안에 임베드 |
| 전제조건 | ① 1단계 운영 안정화 ② 토큰 교환 API 명세 합의 (양쪽 코드 변경 필요) ③ 임베드 대상 화면 선정 (학부모 조회용: 출결 현황·공지·숙제 안내 — 쓰기 화면은 임베드하지 않음) |
| 작업 범위 | (a) SSO: BuildIt 로그인 세션 → 서명된 일회용 토큰 발급 → REA가 검증 후 세션 생성 (토큰은 URL이 아닌 POST 바인딩, 1회용·단기 만료). 원장·강사만 대상. (b) 임베드: 선정된 조회 화면을 iframe으로 삽입. `X-Frame-Options: SAMEORIGIN` + 홈페이지 도메인 allowlist, iframe 리사이즈 스크립트 |
| 예상 규모 | 중 (양쪽에 토큰 교환 엔드포인트 신규 개발, 임베드 화면별 검증) |
| 하지 않는 것 | 학부모 SSO (BuildIt 학부모 계정이 없으므로 대상 아님). 쓰기 화면(출결 입력·상담 답변·성적 등록)의 임베드 (보안 면적·오작동 리스크) |

### 4.3 3단계: 데이터 동기화 (필요시)

| 항목 | 내용 |
|---|---|
| 목표 | 홈페이지에 REA 데이터를 보여준다 (공지 미리보기·학원 소개용 통계 등) |
| 전제조건 | ① 읽기 전용 API 명세 확정 (노출 필드 화이트리스트) ② BFF 프록시 + 캐시 + rate limit 설계 ③ 개인정보 비노출 검증 |
| 작업 범위 | (a) REA 읽기 전용 요약 API (예: `GET /api/rea/{academyId}/public/notices` — 제목·일자만, 본문 일부) (b) 홈페이지 BFF가 서비스 키로 호출·캐시 (c) 홈페이지 위젯 UI. (d) 선택: `wrong_note_entries` ↔ REA 매핑 테이블, 성적 참조 조인 |
| 예상 규모 | 중~대 (API·BFF·위젯 신규 개발. 단, REA 코어 도메인 변경 없음) |
| 하지 않는 것 | 홈페이지에서 REA 데이터 쓰기 (공지 작성·출결 입력 등을 홈페이지 위젯에서 직접 처리하지 않음). 양방향 실시간 동기화 (필요성이 입증되기 전까지 폴링·캐시로 충분) |

### 단계 간 게이트

- 1단계 → 2단계: 1단계 완료 기준(메뉴→로그인→자기 학원 데이터 확인)이 충족되고,
  원장·강사의 "매번 인증키 입력이 불편하다"는 실제 요구가 확인될 때 진행.
- 2단계 → 3단계: 홈페이지에 REA 데이터를 노출해야 하는 구체적 화면(모집용 공지 미리보기 등)과
  노출해도 되는 필드 목록이 확정될 때 진행. "일단 다 보여주자"식 동기화는 하지 않는다.

---

## 5. 보안 고려사항

### 5.1 홈페이지 ↔ REA 간 통신 보안

1. **HTTPS 필수**: 양쪽 모두 HTTPS. 혼합 콘텐츠·HTTP 리다이렉트 금지.
2. **인증키는 헤더로만** (`X-Auth-Key`): URL 쿼리 파라미터·딥링크에 인증키를 절대 포함하지 않는다
   (브라우저 히스토리·서버 로그·Referer 유출 방지). 1단계 딥링크에는 `{academyId}`만 포함하고,
   로그인은 REA 로그인 화면에서 수행한다.
3. **브라우저 → REA API 직접 호출 금지** (옵션 C 전면 적용 시의 위험):
   홈페이지 위젯이 필요하면 홈페이지 **서버(BFF)가 서비스 키로 호출**하고 결과를 내려준다.
   서비스 키는 서버 환경변수에만 보관하고, 클라이언트 JS·HTML에 노출하지 않는다.
4. **SSO 토큰 (2단계)**: 서명된 일회용 토큰, 단기 만료(≤5분), POST 바인딩, 재사용 불가.
   토큰 탈취 시 피해를 줄이기 위해 권한은 REA 측에서 재확인한다 (토큰이 "원장이다"라고 주장해도
   REA의 역할 권한표를 다시 적용).

### 5.2 학원별 데이터 격리 유지 방안

- REA의 이중 방어는 그대로 유지한다: `AuthKeyFilter`(401) → `ReaSecurityInterceptor`(URL academyId 일치·역할 권한, 403)
  → Service 스코핑. 홈페이지 연동이 이 체인을 우회하는 경로를 만들지 않는다.
- 홈페이지 BFF가 REA를 호출할 때도 **학원별 키(또는 학원별 서비스 토큰)** 를 사용한다.
  하나의 마스터 키로 전 학원 API를 호출하는 구조를 만들지 않는다 — 키 유출 시 blast radius가 전체가 된다.
- BuildIt 측 `verifyOwnsAcademy`(TUTOR=원장 기준) 패턴과 REA 측 인터셉터를 **각자 유지**한다.
  한쪽의 소유권 확인을 다른 쪽이 대신하지 않는다 (경계 명확화).
- 임베드(2단계) 시 iframe 안의 REA 세션은 REA 도메인 기준으로 격리된다.
  홈페이지 도메인의 JS가 iframe 내부 DOM·세션에 접근할 수 없도록 `sandbox` 속성과
  `X-Frame-Options` allowlist를 병행한다.

### 5.3 1단계 X-Auth-Key 인증과의 정합성

`docs/security.md`에서 구현된 인증 체계와 통합 설계의 정합 포인트:

| security.md 내용 | 통합 설계에서의 준수 |
|---|---|
| `POST /api/rea/auth/key-login`이 유일한 공개 API | 홈페이지 딥링크는 로그인 화면으로만 연결. key-login을 홈페이지에서 프록시 호출하지 않는다 |
| 401: 키 누락·위조·비활성 / 403: 타 학원 URL·역할 외 API·학부모 타 자녀 조회 | BFF·SSO 토큰 교환 후에도 REA 측에서 401/403을 **재적용**. 홈페이지가 "인증됐다"고 전달해도 REA가 최종 판정 |
| 학부모(PARENT): 본인 자녀 읽기만, 모든 쓰기 403 | 임베드 대상에서 쓰기 화면 제외 (4.2). 홈페이지 위젯에 학부모 쓰기 기능 없음 |
| 강사(TEACHER): 읽기 전체 + 담당 반 관련 쓰기만 | SSO 토큰에 role을 담더라도 REA 권한표를 그대로 적용. 토큰 role 위조 시 REA 측 검증에서 차단 |
| 키패드 출결: HTTP 호출은 강사/원장 키 필요, body에 학생 키 | 키패드 단말용 키는 학원 현장에서 관리. 홈페이지·임베드에 키패드 화면을 노출하지 않음 |
| 인증키 재발급은 원장 전용 | 홈페이지 관리자 패널에서 인증키를 재발급·조회하는 기능을 만들지 않는다 (REA 화면으로 이동) |
| 감사 로그 행위자: 인증 도입 후 SecurityContext에서 주입 | SSO(2단계) 도입 시 토큰 교환으로 생성된 세션도 `AuthContext`에 동일하게 적재되어 감사 로그 행위자가 기록되도록 한다 |

### 5.4 추가로 금지하는 것

- 홈페이지 공개 페이지에 원장·강사·학부모 인증키를 표시·전달하는 기능
- REA 세션을 홈페이지와 공유하는 서드파티 쿠키 (SameSite=Lax 이상, 임베드 시에도 세션 공유가 아닌 토큰 교환 방식)
- 디버그·테스트용으로 `{academyId}` 열거 API를 공개하는 것 (학원 목록 조회는 인증 필요)

---

## 6. 미결정 사항 (향후 결정 필요 — 본 설계에서 단정하지 않음)

1. **REA 배포 도메인**: `rea.{도메인}` 서브도메인 vs 경로(`/rea/`) — 1단계 전제조건. 인프라 결정 사항.
2. **학부모용 BuildIt 계정 신설 여부**: 신설 시 SSO 대상 확대·회원 체계 통합 재설계가 필요하나,
   본 설계는 "학부모 계정 없음"을 전제로 한다.
3. **수강권(LmsEnrollment) ↔ 반 편성 매핑 테이블**: 온라인 강좌 수강생을 오프라인 반에 연결해야 하는
   실제 요구가 생기면 3단계에서 설계. 지금은 만들지 않는다.
4. **Wiki 문항 스냅샷 정책** (3.2절): 시험 출제 후 문항 고정이 필요한지는 학원 운영 정책에 따라 결정.
5. **알림 발송 채널** (FCM/알림톡/SMS): REA는 `MockNotificationSender` 상태.
   실제 발송 연동은 별도 계약·승인이 필요하며 본 통합 설계 범위 밖.

---

## 7. 산출물·다음 단계

- 본 문서: `docs/buildit-integration.md` (설계 완료)
- 다음 단계는 **사용자 승인 후** 진행:
  1. 1단계 전제조건 확정 (REA 배포 URL·도메인, academy_id 일치 확인)
  2. 홈페이지 메뉴 링크 작업 (BuildIt 측 — `wemade-reorg` 수정은 별도 승인 필요)
  3. 2·3단계는 게이트(4절) 충족 시 별도 설계·승인

## 8. 부록: REA API 계약 요약 (BuildIt BFF·위젯 연동용)

> 5단계 작업 5 보완 (2026-10-13). 계약 정리만이며, 3단계(데이터 동기화) 게이트 충족 전에는 BFF·위젯 개발을 시작하지 않는다.

### 8.1 인증 방식: X-Auth-Key 헤더

| 항목 | 내용 |
|---|---|
| 인증 수단 | HTTP 헤더 `X-Auth-Key: <인증키>` (유일한 방식) |
| 인증키 발급 | 원장·강사: `POST /{academyId}/members` 등록 응답에 포함. 학부모: 학생 등록 `POST /{academyId}/students` 시 학생 인증키 발급 = 학부모 로그인 키 (별도 학부모 계정 없음) |
| 인증키 재발급 | `POST /{memberId\|studentId}/renew-key` (원장 전용) |
| 로그인 (역할 판별) | `POST /api/rea/auth/key-login` `{authKey}` → `{role: ACADEMY_OWNER\|TEACHER\|PARENT}`. **유일한 공개(인증 불필요) API** |
| 이후 모든 요청 | 헤더에 `X-Auth-Key` 포함. **URL 쿼리 파라미터·딥링크에 인증키를 절대 포함하지 않는다** (히스토리·로그·Referer 유출 방지) |
| 실패 코드 | 키 누락·위조·비활성 → **401** (`AuthKeyFilter`). 타 학원 URL·역할 외 API·학부모 타 자녀 조회 → **403** (`ReaSecurityInterceptor`) |

### 8.2 학원 식별자 전달: URL 경로 + 헤더 일치 검증

- 기준 경로: `/api/rea/{academyId}/…`
- 전달 흐름: BuildIt 홈페이지 서버가 서브도메인 `handle` → `academy_id`를 resolve (서버사이드) →
  REA 호출 시 URL의 `{academyId}`에 주입.
- 검증: `ReaSecurityInterceptor`가 **URL의 `{academyId}`와 헤더 인증키의 academyId 일치 여부**를 검사한다.
  불일치 시 403 (테넌트 격리). 이중 방어: `AuthKeyFilter`(401) → 인터셉터(403) → Service 스코핑.
- BFF가 REA를 호출할 때는 **학원별 서비스 키**를 사용한다. 하나의 마스터 키로 전 학원 API를 호출하는 구조 금지.

### 8.3 홈페이지 연동 핵심 엔드포인트 (76개 중)

3단계 공개 위젯·BFF용으로 노출 가능한 **읽기 중심** 핵심 목록 (전체 목록: `docs/FINAL-REPORT.md` §3).
개인정보·관리 영역은 BFF 서버에서만 호출하고 브라우저에 직접 노출하지 않는다.

| 용도 | Method · Path | 비고 |
|---|---|---|
| 학원 소개용 정보 | `GET /{academyId}/info` | 학원 기본정보 (공개 범위 필드만 위젯에) |
| 공지 미리보기 (위젯) | `GET /{academyId}/notices?scope=ACADEMY` | 제목·일자 위주. 본문은 상세 화면으로 이동 |
| 공지 상세 | `GET /{academyId}/notices/{id}` | 위젯 클릭 시 REA 화면으로 이동 권장 |
| 학원 소개용 통계 | `GET /{academyId}/dashboard/stats` | 학생/반 수·공지 수 등 집계 (개인정보 없음) |
| 학생 조회 (BFF만) | `GET /{academyId}/students/{studentId}` | 인증 필수. 브라우저 직접 호출 금지 |
| 상담 스레드 (BFF만) | `GET /{academyId}/consultations`, `GET /{academyId}/consultations/{id}` | 인증 필수 |
| 학습 리포트 (BFF만) | `GET /{academyId}/reports/student/{studentId}` | 인증 필수 |

원칙: **홈페이지 위젯에서 REA 데이터 쓰기(공지 작성·출결 입력 등)는 하지 않는다.**
쓰기는 REA 화면(링크 방식)으로 이동해서 수행한다 (§4.3).

### 8.4 CORS·세션 공유 (향후 고려사항)

| 항목 | 1단계 (링크) | 2단계 (SSO/임베드) | 3단계 (위젯/BFF) |
|---|---|---|---|
| CORS | 불필요 — 브라우저가 REA API를 직접 호출하지 않음 | 불필요 (iframe은 별도 문서 컨텍스트) | 불필요 — 홈페이지 **서버(BFF)** 가 REA를 호출, 브라우저 직접 호출 금지 (§5.1-3) |
| 세션 공유 | 없음 — 각자 로그인 | 없음 — 서명 일회용 토큰 교환 방식 (서드파티 쿠키 금지, SameSite=Lax 이상) | 없음 |
| 임베드 시 | — | `X-Frame-Options: SAMEORIGIN` + 홈페이지 도메인 allowlist, iframe `sandbox` | — |

향후 브라우저→REA 직접 호출이 필요해지는 시점에만 CORS allowlist를 검토하며,
그 경우에도 `*` 와일드카드는 사용하지 않고 홈페이지 도메인만 명시한다.

---

## 9. 6단계 작업 3 — 홈페이지 연동 준비 검증 결과 (2026-10-13)

> 상세 실측 기록: `docs/phase6-task3-buildit.md`. wemade-reorg는 읽기 전용 조사(수정 없음).
> 본 절은 4단계 설계의 암묵적 전제("홈페이지에 상담 신청 폼이 있다")를 실측으로 정정한다.

### 9.1 핵심 질문에 대한 답변

**Q. 기존 홈페이지 제작 기능(편집기)을 변경하지 않고, 홈페이지 상담 신청 → REA 상담관리 연결이 가능한가?**

**A. 조건부 가능.** 홈페이지 편집기 코드 변경 없이 연결할 수 있으나, 전제 자체가 실측과 다르다:

| 구분 | 판정 | 근거 |
|---|---|---|
| 홈페이지 → REA 학원관리 이동 링크 | **가능** (편집기 코드 변경 불필요) | 편집기는 블록 설정(config) 편집 도구. REA URL을 버튼/메뉴 링크로 다는 것은 콘텐츠 편집이지 코드 변경이 아님 |
| 홈페이지 "상담 신청" → REA 상담관리 | **조건부 가능** (REA 측 소규모 추가 필요) | 실측 결과 홈페이지에 **상담 신청 폼이 존재하지 않음** — `academy_public.html`의 "상담 신청하기"는 연락처 채널 모달(tel/mailto/카카오 링크)이며 폼·서버 저장이 없다. 연결하려면 REA 측에 "입학상담 신청(프로스펙트)" 공개 접수 API + 공개 폼 페이지가 신규로 필요. 홈페이지 측은 그 URL로의 링크만 추가 |
| 익명 방문자의 REA 상담 API 직접 호출 | **불가능** | `POST /api/rea/{academyId}/consultations`는 X-Auth-Key 필수(익명→401) + 등록 학생(studentId/authKey) 필수. REA에 방문자(프로스펙트) 개념이 없음 |
| 편집기 코드 수정 없이 홈페이지 서버가 폼 데이터를 REA로 자동 전송 | **불가능** | 홈페이지 서버/편집기에 폼 액션·웹훅 설정 기능 추가가 필요 → 편집기 변경 금지 조건 하에서는 범위 밖. 별도 승인 필요 |

### 9.2 홈페이지 상담 신청 실측 (읽기 전용)

- `templates/public/academy_public.html` L41–51: "상담 신청하기" 모달 — 주석 명시 *"학원이 입력한 전화/이메일로 연결"*. 폼 아님.
- `static/js/academy_public.js` L1168–1192: `consultChannels` → `<a href="tel:…/mailto:…">` 링크 목록 렌더링. 입력 필드·서버 전송 없음.
- `templates/public/academy_layout_preview.html` L65–70: `consultationCta` 블록 — `state=PREPARING`이면 "상담 신청은 준비 중이에요." 플레이스홀더, 아니면 클릭 액션 없는 `<button>`.
- 학원 식별: `config/SubdomainAcademyFilter.java` — 서브도메인 slug → `academies` 테이블의 `handle` ↔ `academy_id` resolve.

**결론: "상담 신청 데이터가 어디에 저장되는가" = 저장되지 않는다.** 따라서 폼→REA의 필드 매핑은 현재 코드 기준 매핑 대상이 없다.

### 9.3 REA 상담 API 계약 (연동용 정리)

**인증**: `X-Auth-Key` 헤더 (유일). `POST /api/rea/auth/key-login`만이 공개 API. 그 외 `/api/rea/**` 전부 401(키 누락·위조)/403(타 학원 URL·역할 위반).

| Method | Path | 요청 | 응답 | 비고 |
|---|---|---|---|---|
| POST | `/api/rea/{academyId}/consultations` | `{studentId\|authKey, type: INQUIRY\|NEWS, title, content?, authorMemberId?(NEWS), attachmentFileName?, attachmentFileKey?}` | `{consultation(DetailResponse), firstMessageId, notificationSentCount, notificationFailedCount, notificationSkippedCount, notificationError?}` | **등록 학생 필수** — `ParentScope.forceOwnStudent()` 검증. 방문자 호출 불가 |
| GET | `/api/rea/{academyId}/consultations` | `?status=OPEN\|ANSWERED\|CLOSED&?type=INQUIRY\|NEWS&?studentId&?authKey&?targetStudentId` | `ListResponse[] {id, academyId, studentId, studentName, type, typeLabel, status, statusLabel, title, messageCount, lastMessageAt, createdAt}` | 학부모는 본인 스레드만 |
| GET | `/api/rea/{academyId}/consultations/{id}` | `?studentId&?authKey` | `DetailResponse {…messages[], unreadCount, createdAt, updatedAt}` | |
| POST | `/api/rea/{academyId}/consultations/{id}/messages` | `{content, attachmentFileName?, attachmentFileKey?, studentId\|authKey\|memberId}` | `{message(MessageResponse), notification*}` | 학원 발신 시 학부모 알림 |
| POST | `/api/rea/{academyId}/consultations/{id}/close` | `{studentId\|authKey\|memberId}` | `DetailResponse` | |
| POST | `/api/rea/{academyId}/consultations/{id}/messages/{messageId}/read` | `{studentId\|authKey\|memberId}` | `{consultationId, messageId, readerName, readAt, alreadyRead}` | 멱등 |

**제약**: `ConsultationType`은 `INQUIRY`(등록 학부모→학원)/`NEWS`(학원→학부모) 2종뿐. "입학상담 신청" 타입 없음 — 시나리오 2에서 신규 필요.

### 9.4 권한·데이터 흐름 다이어그램

```
[홈페이지 방문자 (비인증)]                    [홈페이지 서버]                  [REA]
        │                                           │                              │
        │  ① "상담 신청" 클릭                        │                              │
        │  → 연락처 모달 (tel/mailto/카카오)          │                              │
        │    ※ 폼 없음·서버 저장 없음 (실측)          │                              │
        │                                           │                              │
        │  ② "학원관리" 메뉴 클릭                     │                              │
        ├──────────────────────────────────────────▶│                              │
        │                                           │  handle → academy_id resolve │
        │                                           │  (서브도메인, 서버사이드)       │
        │                                           │                              │
        │  ③ REA 딥링크 이동                          │                              │
        │◀──────────────────────────────────────────┤                              │
        │  https://rea.{도메인}/a/{academyId}/…      │                              │
        │                                           │                              │
        │  ④ REA 로그인 화면에서 인증키 입력 ─────────────────────────────────────▶│
        │                                           │        X-Auth-Key 헤더 인증    │
        │                                           │        (401: 키 오류 / 403: 타 학원·역할 위반)
        │                                           │                              │
[시나리오 2 — 입학상담 신청 (REA 소규모 추가 시)]                                      │
        │  ⑤ "온라인 상담 신청" 링크 클릭 ────────────────────────────────────────▶│
        │     (공개 페이지, 인증 불필요)          │                                  │
        │  ⑥ 이름·연락처·상담 내용 입력 ─────────────────────────────────────────▶│
        │                              POST /api/rea/{academyId}/inquiries/public   │
        │                              (신규 제안: rate limit + 개인정보 역할 제한)  │
        │                                           │                              │
[학원 관리자 (인증)]                          │                              │  ⑦ CNS 화면에서
        │  REA 로그인 ───────────────────────────────────────────────────────────▶│     접수 확인·답변
```

**흐름 규칙**:
- 방문자 → REA 쓰기는 **공개 접수 경로(신규)** 로만. 기존 상담 API는 인증된 등록자 전용.
- 브라우저가 REA API를 직접 호출하지 않는다(§5.1-3). 단, 시나리오 2의 공개 접수 페이지는 REA가 직접 서빙하므로 예외.
- 학원 식별자는 항상 서버사이드에서 주입. 방문자가 academyId를 입력·조작하지 않는다.

### 9.5 필드 매핑표 (시나리오 2 — REA 프로스펙트 접수 신설 전제)

| 홈페이지 측 (REA 공개 페이지 폼) | REA 접수 API (신규 제안) | 비고 |
|---|---|---|
| 이름 | `applicantName` (필수) | |
| 연락처(휴대전화) | `applicantPhone` (필수) | 개인정보 — 원장·담당 강사만 조회 |
| 상담 희망 내용 | `content` (필수) | |
| 관심 반/과정 (선택) | `interestedClass` (선택) | REA 반과 매칭은 관리자 수동 |
| 희망 상담 방식 (선택) | `preferredChannel` (선택) | 전화/방문 등 |
| academyId | URL 경로 `{academyId}` | 홈페이지 서버사이드 주입 |

### 9.6 연동 시나리오별 단계

**시나리오 1 — 링크 방식 (REA 코드 변경 없음, 즉시 가능)**
1. REA 배포 URL 확정 + `academy_id` = REA `academyId` 일치 확인
2. 홈페이지 편집기에서 헤더/푸터/관리자 패널에 "학원관리" 버튼 추가 → href를 `https://rea.{도메인}/a/{academyId}/…`로 설정 (콘텐츠 편집, 코드 변경 없음)
3. 역할별 진입점 2종: 관리자용(대시보드), 학부모용(공지·숙제 안내)
4. 완료 기준: 메뉴 클릭 → REA 로그인 화면 → 인증키 입력 → 자기 학원 데이터만 표시

**시나리오 2 — REA 공개 "입학상담 신청" 페이지 (REA 소규모 추가, 사용자 승인 필요)**
1. REA에 추가: ① `POST /api/rea/{academyId}/inquiries/public` (공개, rate limit) ② 공개 폼 페이지 1개 ③ 관리자 접수 목록 조회 (개인정보 역할 제한)
2. 홈페이지 `consultModal`에 "온라인 상담 신청" 링크 1개 추가 또는 `consultationCta` 버튼 href를 REA 공개 페이지로 설정 (콘텐츠 편집)
3. 방문자: 이름·연락처·상담 내용 입력 → REA 접수 → 원장/강사에게 알림 → CNS 화면에서 확인·답변
4. 편집기 코드 변경 없음

**시나리오 3 — 홈페이지 폼 → REA API 자동 전송 (불가 — 범위 밖)**
- 홈페이지 서버/편집기에 폼 액션·웹훅 설정 기능이 필요 → 편집기 코드 변경 필수. 본 작업의 금지 조건 하에서는 불가. 필요 시 별도 승인.

### 9.7 독립성 확인 결과

- REA 백엔드: `import f2r` 0건. 독립 Maven 프로젝트, 패키지 `com.rea.academy`. wemade-reorg 코드 의존 없음.
- REA 프론트: 독립 정적 페이지. 홈페이지 JS/CSS 의존 없음.
- 공유 요소: **URL + `academy_id`=`academyId` 값 약속뿐**. 코드·DB·세션 공유 없음. REA 독립 배포 가능.

### 9.8 막힌 부분 (추가 승인 필요)

1. 홈페이지에 상담 신청 폼이 없음 — 방문자 상담 접수를 원하면 시나리오 2(REA 공개 접수 신설) 또는 홈페이지 폼 신설(편집기 변경, 별도 승인) 중 택1
2. REA에 프로스펙트 개념이 없음 — 시나리오 2의 소규모 REA 추가 개발에 사용자 승인 필요
3. REA 배포 URL·도메인 미확정, `academy_id` 일치 확인 필요

---

*이 문서는 설계 전용이다. 코드 변경·DB 변경·배포를 포함하지 않는다.*
