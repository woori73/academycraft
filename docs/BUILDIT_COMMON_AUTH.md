# BuildIt 기존 회원 연결

기존 배포·JWT·학생·반·출결 API는 보존한다. BuildIt `/academycraft` 게이트웨이를 통한 요청만 HMAC으로 추가 인증한다. 별도 로그인 ID·비밀번호를 만들거나 운영 DB를 자동 수정하지 않는다.

설정(운영 적용 별도 승인):
- 두 앱의 `ACADEMYCRAFT_GATEWAY_SECRET`: 보호된 환경파일에 동일한 새 랜덤 32바이트 이상 값. 채팅·CI·저장소 출력 금지. 기존 JWT 키와 분리.
- AcademyCraft `ACADEMYCRAFT_BUILDIT_BINDINGS`: JSON 배열. 각 항목은 `userId`, `role`(BuildIt ADMIN/TUTOR/STUDENT), `builditAcademyId`, `academyId`(AcademyCraft), `memberId` 또는 `studentId` 중 하나. 실제 존재하고 활성인 내부 소속만 허용한다. TUTOR/STUDENT 한 회원당 한 학원, ADMIN은 승인된 여러 학원 선택 가능, 한 내부 소속당 한 회원으로 모호성을 차단한다.
- ADMIN도 지정된 AcademyCraft 소속 역할만 사용한다. TUTOR의 원장 범위는 실제 BuildIt 소유 학원이 일치할 때만 허용한다. 강사 연결은 명시 설정된 소속과 기존 TEACHER 권한으로 제한한다. STUDENT는 연결된 본인 학생 정보·출결 GET만 허용한다.
- 기존 내부 회원은 업무 담당·감사 식별용이다. 신규 로그인 계정 생성이 아니며, 이 변경은 내부 회원/학원조차 자동 생성하지 않는다. 현재 내부 데이터가 비어 있다면 운영 활성화 전에 소속 데이터 준비 범위만 따로 승인받는다. BuildIt DB는 읽기 전용이다.
- 기본 키·연결 목록이 비면 접근 차단. `X-Auth-Key` 운영 폴백은 계속 비활성. 외부 클라이언트가 보낸 인증 헤더·쿠키는 업스트림으로 전달하지 않는다.
- 서명은 메서드·경로/쿼리·본문 SHA256·사용자/역할·발급 시각·nonce를 포함하고 30초 만료 및 재사용 차단한다. 현재 단일 EC2 배포를 대상으로 한다.

배포 순서(별도 승인): 기존 백업·롤백 경로 재사용 → AcademyCraft 변경 배포(설정 비활성) → 확인된 내부 소속 연결과 보호된 키 설정 → BuildIt 배포 → 실제 기존 계정 브라우저 검증. 실패 시 연결 설정 제거 및 두 기존 JAR로 롤백한다. 신규 AWS 리소스나 BuildIt 스키마 변경은 없다. 운영 쓰기 검증은 금지하고 격리된 H2 테스트만 사용한다.

실제 운영 계정의 브라우저 검증과 PC/390px 캡처는 병합·운영 배포 승인 후 별도 수행해야 한다. CI 결과를 실제 로그인 성공으로 간주하지 않는다.

2026-10-20 보완: 공통 BuildIt 진입은 ADMIN·TUTOR 전용. ADMIN context의 academyOptions는 활성 승인 소속만 포함하고 선택값은 요청별 서버 검증한다. 상담·공지 기존 API를 연결하고 작성자 ID는 서버 principal로 고정한다. 공통 탐색 메뉴는 사용 가능한 학생·반·출결·상담·공지 화면만 표시한다. 공지 수정은 기존 PUT을 사용한다. 권한 관리는 보호된 binding 설정 검토 방식이며 신규 회원의 자동 소속 생성은 없다. 운영 배포/DB 변경 및 실제 계정 검증은 미실행이다.
