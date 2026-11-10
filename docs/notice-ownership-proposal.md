# 공지 수정·삭제 소유권 제안 (조사 결과 + 변경안, 구현 없음)

> 2026-10-20 · 기준 main `6616779b`. 이 문서는 조사와 제안이며 코드 변경은 승인 후 별도 PR에서 한다.

## 1. 현재 동작 (코드 근거)
- `ReaAccessRules.TEACHER_WRITES`: 강사 허용 쓰기에 `PUT /api/rea/*/notices/*`, `DELETE /api/rea/*/notices/*`가 **작성자 구분 없이** 포함.
- `NoticeService.update/delete`: `requireNotice(academyId, id)`는 **학원 소속(+활성)만** 확인. 작성자(`authorId`) 비교 없음.
- 결과(로컬 실행 확인 2026-10-20): 일반 강사가 원장이 쓴 공지를 수정(200)·삭제(204) 가능.
- 타 학원 격리: `requireNotice`의 `academyId` 필터와 테넌트 인터셉터로 막힘(다른 학원 공지 id는 "공지를 찾을 수 없습니다"). `IsolationMatrixTest`가 공지 포함 여부는 구현 시 확인한다.
- 작성자 기록: 작성 시 컨트롤러가 로그인 주체(`auth.principalId()`)를 `authorId`로 **강제 설정**(요청 값 무시)하므로 신규 공지는 위조 불가. 단 `rea_notices.author_id`는 **NULL 허용**(V1)이고 서비스는 authorId가 없으면 NULL로 저장 → 과거·시드·외부 입력 공지에 작성자가 없을 수 있다.

## 2. 제안 정책
| 주체 | 공지 조회 | 수정·삭제 |
|---|---|---|
| 원장(ACADEMY_OWNER, BuildIt ADMIN 연결) | 자기 학원 전체 | 자기 학원 전체 |
| 일반 강사(TEACHER, BuildIt TUTOR 연결) | 자기 학원 전체 | **본인이 작성한 공지만** (`authorId == principalId`) |
| 다른 강사의 공지 / 작성자 없음(NULL) 공지 | 조회 가능 | 강사는 **403**, 원장만 가능(안전 기본값) |
| 타 학원 | 불가(기존 격리 유지) | 불가 |
- 작성은 기존대로 원장·강사 모두 가능.
- BuildIt 쪽 변경 불필요(게이트웨이는 role·memberId만 전달, AcademyCraft가 판정).

## 3. 호환성 조사 항목(구현 PR에서 수행)
1. 운영 `rea_notices`의 `author_id IS NULL` 건수와 작성자 비활성 회원 건수(읽기 전용 SELECT, 승인 후). NULL 건은 정책상 원장 전용이 되므로 강사 기능 저하 범위를 사전 파악.
2. 학원 12·20(최초 연결 학원)의 공지 작성 경로: 기존 화면은 서버가 authorId를 채우는지 로컬 확인.
3. 마이그레이션 불필요 안(권장): 스키마 변경 없이 서비스에서 판정. 백필이 필요하면 별도 승인.

## 4. 구현안(요약)
- `NoticeController.update/delete`에서 `AuthContextHolder`로 주체를 읽어 `NoticeService.update/delete(academyId, id, req, actor)`에 전달.
- 서비스: actor 역할이 TEACHER면 `notice.authorId != actor.principalId` → `ReaForbiddenException("본인이 작성한 공지만 수정·삭제할 수 있습니다.")`. OWNER·비-BuildIt(관리자 경로)는 통과. 인증 컨텍스트가 없는 내부 호출은 기존 동작 유지 여부를 결정(기본: 거부).
- 테스트(given/when/then): 강사 본인 공지 수정·삭제 허용 / 타 강사·원장 공지 403 / NULL 작성자 강사 403·원장 허용 / 원장 전체 허용 / 타 학원 id 404 / 읽음 처리·조회는 영향 없음.
- 문서: `docs/security.md` 권한표, BuildIt 도움말 07·08·09·11("강사도 모든 공지 삭제 가능" 서술 정정, 새 버전 배포 후 상태 갱신).

## 5. 위험·결정 필요
- 동작 변경이므로 운영 강사에게 안내 필요(타인 공지 수정 불가).
- NULL 작성자 공지의 강사 권한은 "원장 전용"으로 제안(대안: 학원 전체 허용 유지는 소유권 목적과 충돌).
- 운영 DB 조회(1번)와 배포는 별도 승인 후.
