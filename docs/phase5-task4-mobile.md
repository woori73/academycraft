# REA 5단계 작업 4 — 모바일 사용성 검증 리포트

> 작성일: 2026-10-13
> 대상: `frontend/screens/` 중 역할별 핵심 화면 25개 (기존 34개 중)
> 뷰포트: 360px / 390px / 430px (Playwright headless Chromium 실측 렌더링, 75회)
> 검증 항목: ① 가로 스크롤 없음 ② 터치 영역 ≥44px ③ 텍스트 잘림 ④ 테이블 반응형 ⑤ 고정 헤더·하단 버튼 가림

## 1. 검증 방법

- `playwright-core` + 캐시된 Chromium 1243 headless로 각 화면을 `file://` 렌더링 후 JS 실측
  - `documentElement.scrollWidth` vs 뷰포트 (가로 스크롤)
  - `button/input/select/textarea/a` 요소 `getBoundingClientRect()` (터치 영역)
  - `text-overflow: ellipsis` 활성 여부, `position: fixed/sticky` 요소, 테이블 래퍼(`overflow-x`) 확인
- 백엔드 없이 렌더링되므로 동적 목록은 빈 상태 + **데이터 주입 대조 테스트** 병행
  (ATT-02에 31일치 출결 테이블 주입, CHAT-01에 `table.data` 주입)
- 대표 화면 10종 스크린샷 육안 검토 (360px)
- 수정 후 인라인 스크립트 `node --check` 구문 검증

## 2. 결과 요약

| 항목 | 결과 |
|---|---|
| 검증 화면 | **25개** (역할별 핵심) |
| 뷰포트 | 360 / 390 / 430px — **75회 렌더링 전체 통과** |
| 가로 스크롤 (overflow-x) | **0건** (수정 전·후 모두 없음) |
| 고정 헤더·하단 버튼 가림 | **0건** (fixed/sticky 요소 없음) |
| 의도치 않은 텍스트 잘림 | **0건** (활성 ellipsis·overflow hidden 클리핑 없음) |
| 테이블 깨짐 | **0건** (ATT-02 `.table-wrap` 스크롤, CHAT-01 `table.data` 버블 내 표시 — 데이터 주입 실측 확인) |
| 터치 영역 미달 → 수정 | **8건 발견·수정** (아래 §3) |
| 인라인 JS 구문 검사 | **25/25 통과** |

## 3. 발견·수정한 이슈 (8건)

| # | 화면 | 이슈 | 수정 내용 |
|---|---|---|---|
| 1 | **전체 25개** | 버튼·pill 링크 31~39px, input/select 37~43px로 44px 권장 미달 | `</style>` 직전에 공통 `@media (max-width: 480px)` 블록 추가: `button,.btn {min-height:44px}`, input/select/textarea `min-height:44px + font-size:16px`(iOS 자동줌 방지), 체크박스·라디오 20px+`accent-color` |
| 2 | MBR-01 | 360px에서 검색행 버튼 텍스트 2줄 줄바꿈 → 행 높이 77px로 비대 | `.search {flex-wrap:wrap}`, input 전폭(`flex:1 1 100%`), 버튼 2분할(`flex:1`) |
| 3 | CLS-01 | 일반 nav 링크(`.nav a`) 높이 33px | `.nav a {min-height:44px; inline-flex}` + "종료된 반 포함" 라벨 패딩 확대 (히트 영역 111×44 실측) |
| 4 | CLS-02 | 수업 요일 체크박스 13px | `.days label` 패딩 확대 → 칩형 라벨 히트 영역 67×46 실측 |
| 5 | CNS-05 | 역할 라디오 13px + `.login input{min-width:140px}` 상속으로 라디오가 140px로 늘어남 | `.role-pick label` 패딩 확대(히트 79×48~99×48), 공통 패치에 `min-width:0; flex:none` 추가해 라디오 20px 고정 |
| 6 | ATT-04 | 출결 사진 전송 체크박스(22px)가 라벨과 연결 안 됨 | `<label for="f-photo">` 연결 + 라벨 패딩 (히트 85×44 실측) |
| 7 | CHAT-01 | ① AI 응답 `table.data` 모바일 대응 미정의 ② `X-Auth-Key` 입력칸 placeholder 잘림("X-Auth-Key 인…") ③ 쓰기 승인 체크박스 행 16px | ① `table.data{display:block;overflow-x:auto}` ② `#authKey{flex:1;min-width:0;width:auto}` → placeholder 전체 표시 확인 ③ `.approve` 패딩 확대 (히트 85×44 실측) |
| 8 | 공통 | 체크박스·라디오 박스 자체는 20px로 검출기에 계속 잡힘 | 의도된 설계: 박스 20px + 라벨 패딩으로 실효 히트 영역 ≥44px (5개 화면 라벨 실측 모두 44px 이상) |

## 4. 화면별 × 뷰포트별 검증표

> P = 통과 (①~⑤ 전체), M = 위 §3의 수정 적용 후 통과

### 원장

| 화면 | 360px | 390px | 430px | 비고 |
|---|---|---|---|---|
| MBR-01 학생 관리 | M(#2) | M(#2) | M(#2) | 검색행 wrap 구조 변경 |
| CLS-01 반 목록 | M(#1,#3) | M(#1,#3) | M(#1,#3) | nav 링크·라벨 44px |
| CLS-02 반 생성 폼 | M(#1,#4) | M(#1,#4) | M(#1,#4) | 요일 칩 44px+ |
| NTC-03 공지 작성 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| OPS-01 일정·캘린더 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| OPS-02 통계 대시보드 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| OPS-03 엑셀 IO | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| CHAT-01 AI 채팅 | M(#1,#7) | M(#1,#7) | M(#1,#7) | 테이블 스크롤·인증키칸·승인행 |

### 강사

| 화면 | 360px | 390px | 430px | 비고 |
|---|---|---|---|---|
| ATT-01 출결 키패드 | P | P | P | 키패드 원래 ≥44px, 수정 불필요 |
| ATT-02 월별 출결 | M(#1) | M(#1) | M(#1) | 31일 테이블 주입 실측: `.table-wrap` 내 스크롤, 페이지 overflow 없음 |
| ATT-03 출결 명부 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| ATT-04 출결 설정 | M(#1,#6) | M(#1,#6) | M(#1,#6) | for 연결·라벨 44px |
| CNS-01 상담 목록 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| CNS-02 상담 문의 폼 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| CNS-03 문의 모음 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| CNS-04 아이소식 발송 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| CNS-05 상담 상세 | M(#1,#5) | M(#1,#5) | M(#1,#5) | 라디오 20px 고정·라벨 44px |

### 학부모

| 화면 | 360px | 390px | 430px | 비고 |
|---|---|---|---|---|
| NTC-01 공지 목록 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| NTC-02 반 소식 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| NTC-04 공지 상세 | M(#1) | M(#1) | M(#1) | 뒤로가기 버튼 44px |
| GRD-01 성적 등록 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| GRD-02 성적 조회 | M(#1) | M(#1) | M(#1) | 공통 패치만 |

### 학생

| 화면 | 360px | 390px | 430px | 비고 |
|---|---|---|---|---|
| HW-01 숙제 등록 | M(#1) | M(#1) | M(#1) | 공통 패치만 |
| HW-02 숙제 검사 | M(#1) | M(#1) | M(#1) | 상태 select·점수 input 44px |
| HW-03 숙제 안내 | M(#1) | M(#1) | M(#1) | 공통 패치만 |

## 5. 수정된 화면 파일 목록 (25개)

공통 패치: 25개 전체. 화면별 특화 추가: MBR-01, CLS-01, CLS-02, CNS-05, ATT-04, CHAT-01.

- MBR-01-student-list.html, CLS-01-class-list.html, CLS-02-class-form.html
- NTC-03-notice-form.html
- OPS-01-schedule-calendar.html, OPS-02-dashboard.html, OPS-03-excel-io.html
- CHAT-01-ai-chat.html
- ATT-01-attendance-keypad.html, ATT-02-attendance-monthly.html, ATT-03-attendance-roster.html, ATT-04-attendance-settings.html
- CNS-01-consultation-list.html, CNS-02-consultation-form.html, CNS-03-inquiry-inbox.html, CNS-04-news-send.html, CNS-05-consultation-detail.html
- NTC-01-notice-list.html, NTC-02-class-news.html, NTC-04-notice-detail.html
- GRD-01-grade-form.html, GRD-02-grade-list.html
- HW-01-homework-form.html, HW-02-homework-check.html, HW-03-homework-list.html

## 6. 경계 준수 확인

- 백엔드 코드 수정 없음 (프론트 `screens/`만)
- `components/api-client.js` 인증 로직 미수정
- CHAT-01 `rea-chat-auth-key` 저장 키 이름 유지 (JS 미수정, CSS만 변경)
- 모든 수정은 `@media (max-width: 480px)` 범위로 한정 → 데스크톱 레이아웃 불변
- 인라인 스크립트 `node --check`: 25/25 통과

## 7. 남은 참고 사항

- ATT-01 키패드는 수정 전부터 터치 친화적(버튼 고정 크기)으로 3개 뷰포트 모두 무수정 통과
- 가로 스크롤·고정 요소 가림·텍스트 잘림은 25개 화면 전체에서 애초에 0건 (정적 점검 `mobile-check.md` 결과와 일치)
- 체크박스·라디오 박스 자체(20px)는 자동 검출에 계속 잡히나, 라벨 히트 영역이 전부 44px 이상임을 실측으로 확인한 의도된 설계
- 빈 상태 렌더링 기준 검증이므로, 실제 데이터가 많은 화면(ATT-02 달력·CHAT-01 긴 응답)은 운영 데이터로 육안 재확인 권장
