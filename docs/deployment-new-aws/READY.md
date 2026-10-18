# 신규 AWS AcademyCraft 배포 준비 — 2026-10-19

아직 운영 배포 완료가 아니다. 실제 URL이 확인되기 전 BuildIt 카드는 준비 중으로 유지한다.

## 확인한 운영 조건

- 계정 425805163960, EC2 i-0414de33d2803c723, t3.medium, 2 vCPU
- RAM 3835MB, 가용 2034MB, 루트 디스크 여유 27064MB, swap 없음
- BuildIt 메모리 약 1.05GiB, Wiki 약 67MiB, 둘 다 active
- MySQL 8.4.8, 연결 13/60. 관리자에게 전용 스키마·계정 생성 권한 있음
- 신규 계정 RDS 스냅샷 available, 자동 백업/PITR 조회 성공
- AcademyCraft 스키마·환경·JAR 없음. 전용 IAM 배포 역할 없음
- 기존 ALB HTTPS 기본 규칙만 존재. AcademyCraft host 라우팅 없음
- 근거: BuildIt Actions 39157068373 (academycraft-preflight), 운영 데이터 SELECT/SHOW만 수행

## GitHub 운영 보호

production 환경 생성, main 브랜치만 허용, woori73 승인 필요, 관리자 우회 비활성화.

## 관리자 작업이 필요한 항목 (미실행)

1. bootstrap-iam.sh로 전용 배포 역할과 EC2의 백업 전용 S3 prefix 권한 구성.
   - role 신뢰는 AcademyCraft production 환경의 정확한 두 OIDC sub 형식만 허용.
   - 다른 GitHub 저장소·BuildIt 배포 역할의 신뢰 정책은 변경하지 않음.
   - 이 역할의 SSM 권한은 공유 신규 EC2에서 root 명령을 실행할 수 있으므로 main·승인 게이트 유지 필수.
2. 기존 ALB에 AcademyCraft HTTP 8082 대상 그룹과 academy.feel2real.co.kr host 전용 443 규칙 추가.
   - 기존 기본 규칙·BuildIt 대상 그룹·인스턴스·RDS는 유지.
   - EC2의 8082는 기존 ALB 보안그룹에서만 접근 허용. 인터넷 직접 개방 금지.
   - 기존 인증서의 wildcard SAN 및 기존 wildcard DNS가 해당 host를 포괄하는지 확인.
3. 최초 원장 계정 운영 설정: 사용자 계정 자동 생성·인증 우회 없이 실제 원장 계정을 별도 확정.

현재 자동화의 실제 권한으로 위 관리자 설정을 수행할 수 있는지 미검증.
Work의 AWS 콘솔 접근은 Site Unavailable로 차단되어 콘솔 작업을 실행하지 못함.

## 배포 workflow 변경

- 신규 계정/인스턴스/전용 역할/기존 S3 prefix 고정. 모든 AWS job에서 계정 재확인.
- 신규 유료 Secrets Manager를 만들지 않고 /etc/f2r/env에서 DB 관리자 정보를 호스트 메모리로만 일시 조회.
- 쓰기 전에 신규 계정 RDS endpoint 해시와 호스트의 DB_HOST를 대조해 구 AWS DB 접근을 차단한다.
- 앱에는 별도 academycraft DB 사용자/스키마와 자체 JWT만 전달. BuildIt 데이터 변경·초기화 금지.
- JVM heap 768MB, metaspace 192MB, code cache 96MB, stack 512KB, Tomcat 최대 40 thread.
- MemoryHigh 1100MB / MemoryMax 1280MB, DB pool 8. 시작 후 가용 700MB 미만이면 중단.
- 앱 health UP을 확인한 후 백업/격리 복원을 수행. 학부모 알림/재설정 발송 OFF.
- 최초 배포 전용으로 기존 AcademyCraft JAR 발견 시 중단. 실패 시 AcademyCraft만 stop/disable하고 DB·환경·백업은 보존.
- 기존 backup/restore 검증 재사용. 이미 적용한 V1~V3 SQL은 변경하지 않음.

## 배포 후 미완료 검증

실제 원장 로그인 → 학생 등록/조회/수정 → 반 생성/배정 → 출결 입력/정정/월별/반별 조회 → 강사/학원 격리.
실제 HTTPS URL 확인 후 BuildIt 바로가기 반영. 출결 위젯·SSO는 별도 권한 매핑 검토.

## 비용

이번 작업으로 AWS 유료 리소스 생성·증설 없음. 새 EC2·RDS·ALB·Secrets Manager를 만들지 않는 구성이다.
IAM 역할·기존 ALB의 대상 그룹/규칙 구성은 별도 관리자 작업으로 준비한다.
기존 EC2·RDS·ALB·S3의 사용량 과금은 유지되며 새 비용이 필요한 변경은 사전 승인 후 수행한다.
