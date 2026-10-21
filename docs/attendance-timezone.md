# 출결 시간대(UTC/KST) — 점검 결과, 처리 원칙, 대응

## 0. 운영 점검 결과와 처리 원칙 (2026-10-20)
- 운영 점검(`diagnose-timezone`): 호스트·JVM·DB 모두 **UTC**(`TZ`/`-Duser.timezone` 없음). 출결 기록은 현재 2건(UTC 11:40~11:44 = KST 20:40~20:44)이며 **수정하지 않았다**.
- **처리 원칙(이 PR에서 코드로 고정)**
  1. 저장: DB의 DATETIME 컬럼과 API 시각 문자열은 모두 **Asia/Seoul 벽시계 시각**. 호스트·JVM·DB 서버 시간대에 의존하지 않는다.
  2. 생성: 모든 서비스·엔티티는 `LocalDateTime.now()` 대신 `ReaTime.now()`, `LocalDate.now()` 대신 `ReaTime.today()`를 쓴다(`YearMonth`도 `ReaTime.today()` 기준).
  3. 조회: "오늘"·월별·리포트 날짜 경계는 `ReaTime.today()`로 계산해 저장 기준과 같다.
  4. 표시: API가 준 시각을 그대로 표시한다(변환 없음). 화면의 기본 날짜는 UTC(`toISOString`)가 아니라 브라우저 로컬(한국) 날짜.
  5. 절대 시각(epoch) 용도(`Instant.now()`: 게이트웨이 서명 시간창, 속도 제한, 문자 API 서명)는 시간대와 무관하므로 그대로 둔다.
- **기존 데이터**: 배포 이전 행은 UTC 벽시계로 저장돼 있다(현재 출결 2건). 배포 후 새 행과 9시간 어긋나는 혼재가 생기므로 **적용 시각 T를 기록**해 두고, 보정(+9h)은 별도 승인 후에만 한다(§3). 이번 PR은 데이터를 바꾸지 않는다.

# (이전 분석) 코드 분석과 대응안

> 2026-10-20 · 기준 main `6616779b`. 운영 값은 **아직 조회하지 못했다**(운영 접근 수단 없음, `deploy-academycraft.yml`의 `diagnose-timezone`으로 승인 후 확인). 이 문서는 코드 근거와 영향·수정안이다.

## 1. 코드 근거
- 시각 저장·"오늘" 판정은 전부 JVM 기본 시간대를 쓴다: `AttendanceService`(`LocalDateTime.now()` 82·139행, `LocalDate.now()` 124·194행), `ReaAttendanceEvent`/`Correction`/`Setting`, `NoticeService`·`LearningService`·`OpsService`·에이전트 도구 등. `Clock`·`ZoneId` 사용 없음, `-Duser.timezone`·`jdbc.time_zone` 설정 없음.
- 운영 기동 설정(`deploy-academycraft.yml`): systemd `ExecStart=/usr/bin/java -Xms128m … -jar …` — **`-Duser.timezone` 없음**, `/etc/academycraft/env`에는 `DB_URL=…?serverTimezone=Asia/Seoul` 등만 있고 `TZ` 없음. → JVM 시간대 = 호스트 시간대(EC2 기본은 UTC).
- 로컬 실험(MySQL 8.0, Connector/J 8.1, `serverTimezone=Asia/Seoul`로 `LocalDateTime` 저장):
  | JVM 시간대 | Java 현재 | DB에 저장된 값 | DB `NOW()` |
  |---|---|---|---|
  | UTC | 12:13:51 | 12:13:51 | 12:13:51 |
  | Asia/Seoul | 21:13:53 | 21:13:53 | 12:13:53 (DB 서버 시간대 UTC) |
  → **`serverTimezone=Asia/Seoul`은 `LocalDateTime` 값을 바꾸지 않는다.** 저장 시각은 항상 JVM 벽시계 시각이다.
- 화면: 서버가 준 `occurredAt`(시간대 없는 ISO 문자열)을 그대로 표시 → 표시 = 저장값. 추가로 `ATT-03`·`ATT-02`는 기본 날짜를 `new Date().toISOString().slice(0,10)`(브라우저 **UTC** 날짜)로 채워, KST 00:00~09:00에는 "어제" 날짜가 기본값이 된다(서버 설정과 무관한 별도 결함).

## 2. 운영 값이 UTC일 때의 영향
- 출결 시각이 실제 한국 시각보다 9시간 이르게 저장·표시(예: 15:00 등원 → 06:00).
- 한국 시간 00:00~09:00에 기록한 건은 UTC로는 전날이라 전날 날짜로 집계된다(일별 출결부·월별 통계 어긋남). 오후~밤(KST 15:00~24:00 = UTC 06:00~15:00) 수업은 날짜는 맞고 **표시 시각만 −9시간**이다.
- 공지·상담·알림 등 다른 `created_at`도 같은 기준(UTC)이며, 에이전트 계획 만료(10분)는 같은 JVM 기준이라 영향 없음.
- 판별: `timezone` 점검의 출결 시각 분포(시간대별 건수)가 09~18시 대신 00~09시에 몰리면 UTC 저장이다.

## 3. 수정안(승인 후, 기존 기록 수정은 별도 승인)
1. **앞으로의 기록 고정(권장)**: `Clock` 빈을 `Asia/Seoul`로 고정해 서비스가 `LocalDateTime.now(clock)` 사용 + JVM `-Duser.timezone=Asia/Seoul`(systemd) 병행. 호스트 설정과 무관하게 동작. 별도 PR·테스트(시간대 UTC/KST 두 JVM에서 동일 결과) 필요.
2. **기존 기록 보정(선택)**: 적용 시각 T 이전 행을 +9시간 이동. 사전: ① 백업(S3 지정 prefix) ② 대상 `COUNT`·id 목록을 백업 테이블에 저장 ③ 단일 트랜잭션 `UPDATE … SET occurred_at = occurred_at + INTERVAL 9 HOUR WHERE created_at < T`(+ corrections·notices 등 대상 표 확정). **혼재 방지를 위해 1번 배포 직후 T를 기록**한다.
3. **롤백**: 1번은 플래그·코드 롤백 후 재시작. 2번은 백업 테이블의 id 목록으로 −9시간 역보정 또는 백업 복원. 기존 데이터는 이 문서 작성 시점까지 **수정하지 않았다**.
4. 화면 날짜 기본값은 `toISOString()` 대신 로컬 날짜 계산으로 별도 수정.

## 4. 점검 실행(읽기 전용, 승인 필요)
`deploy-academycraft.yml`을 `operation=diagnose-timezone`, `confirm=READONLY`로 실행(production 승인; 배포 역할 신뢰 정책이 이 워크플로 파일만 허용하므로 별도 진단 워크플로는 OIDC 거부됨): 호스트 `timedatectl`, JVM `TZ`/`-Duser.timezone`, DB `NOW()`·`@@session.time_zone`·`@@global.time_zone`, 출결 시각 분포(집계만). 점검용 출결 기록 생성은 별도 승인 전까지 하지 않는다.
