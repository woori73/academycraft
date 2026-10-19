# AcademyCraft 기존 배포 운영 적용

2026-10-20 신규 AWS 읽기 전용 사전점검(run 39297917149)에서 기존 JAR·환경파일·academycraft 스키마·8082 리스너가 확인됐다. 기존 최초 설치 절차는 기존 JAR가 있으면 중단하므로 재실행하지 않는다.

기존 deploy-academycraft.yml의 동일 production 승인 및 IAM 신뢰 경로를 유지한다. 기본 operation은 inspect이며 INSPECT 확인 문구를 요구한다. 신규 AWS 계정·최근 RDS 복구 시점·DB endpoint 지문을 확인한 뒤 ID/역할/소속만 SELECT한다. 비밀번호·인증키·학생·연락처는 출력하지 않는다.

update는 검토된 bindings_json, DEPLOY 및 CHECKED를 요구한다. 실제 BuildIt 역할·TUTOR 소유 학원·활성 AcademyCraft 업무 소속을 확인한다. 이름/동일 ID 추정 매핑과 자동 소속 생성은 없다. 기존 bindings 제거·변경, JWT/DB 비밀번호 교체, 스키마 변경은 허용하지 않는다.

기존 JAR·양쪽 환경파일을 root 600 로컬 백업하고 AcademyCraft 일관성 덤프를 신규 S3 백업 prefix에 저장한다. 승인된 SHA의 기존 성공 CI를 확인하고 테스트 재실행 없이 패키징한다. SHA 경로의 아티팩트를 내려받아 SHA256 일치를 확인한다. 기존 공유키가 있으면 보존하고 양쪽 키가 다르면 중단한다. 없을 때만 서버 내부에서 새 키를 생성한다. 키는 로그·저장소·SSM 인자로 전달하지 않는다.

AcademyCraft만 중지·JAR 교체·기동한다. 기동 실패 시 JAR와 양쪽 환경파일을 기존 파일로 복구하고 AcademyCraft를 다시 기동한다. DB 복원·삭제·계정 변경은 하지 않는다. BuildIt·Wiki 상태를 감시하며 BuildIt은 이 단계에서 재시작하지 않는다.

그 후 기존 BuildIt 수동 배포(target=new)로 승인된 main을 적용해야 /academycraft 게이트웨이가 활성화된다. 운영 검증은 ADMIN 및 modu 각각 실제 로그인, 학생→반→출결, 상담·공지 저장/조회, 타 학원 접근 차단, PC 1440/390px 및 기존 홈페이지·장부·TESTPREP 확인으로 완료한다. health UP만으로 전체 완료 처리하지 않는다.

현재 상태: 업데이트 절차 코드 준비. 실제 inspect 소속 결과·bindings 확정·update·BuildIt 배포·실계정 QA는 미실행.
