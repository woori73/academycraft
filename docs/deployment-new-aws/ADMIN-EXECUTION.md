# AcademyCraft 관리자 실행표 — 2026-10-19

실제 실행 전 검토 자료. 아직 AWS 변경을 실행하지 않았다.
main 193c7417 CI 성공. BuildIt 기존 작업·378개 테스트는 다시 실행하지 않는다.
읽기 전용 OIDC inspect run 39176427953 성공. 기존 BuildIt 배포 역할은
BuildIt main의 deploy-ec2.yml / migration-infra-build.yml만 신뢰하며 IAM/ALB 쓰기 권한이 없다.
저장소 bootstrap 정책의 CreateRole 대상에도 AcademyCraft 역할이 없다.
실제 bootstrap 역할의 전체 유효 권한을 새로 확대하거나 검증을 위해 쓰기 API를 시도하지 않는다.
콘솔 Site Unavailable의 세부 네트워크 원인은 미확정이며 AWS IAM 거절의 증거가 아니다.

## 관리자 실행 전 변경·비용·롤백 표

| 대상 | 허용할 변경 | 범위와 위험 | 추가 비용 | 롤백 |
|---|---|---|---|---|
| IAM | github-actions-academycraft-deploy 신규 역할·전용 인라인 정책 | main / production / 지정 workflow만 신뢰. SSM root 실행 권한은 공유 EC2 1대로 제한하지만 호스트 전체 접근 위험은 존재 | IAM 설정만; 유료 서버 생성 없음 | 미배포면 신규 역할의 전용 정책만 제거하고 역할 삭제. 기존 역할/신뢰는 유지 |
| EC2 역할 | academycraft-backup-prefix-only 정책만 추가 | 기존 정책 교체 금지. backups/academycraft/* Put/Get, 테스트 키만 Delete | 기존 S3 저장·요청 사용량 과금 | 새로 추가한 이 정책만 제거. 백업 객체 보존 |
| ALB | 기존 f2r-alb의 443에 academy.feel2real.co.kr 전용 규칙과 HTTP 8082 대상 그룹 추가 | 기본 규칙·기존 TG·인증서 교체 금지. 우선순위는 빈 값만 선택 | 새 ALB 생성 없음. 기존 ALB 처리량 과금 영향은 별도 확인 | 새 host 규칙 제거 후 새 TG 등록 해제/제거. 기존 기본 규칙 유지 |
| 보안그룹 | 기존 ALB SG → 앱 SG TCP 8082만 추가 | 0.0.0.0/0·::/0·임의 IP 개방 금지. 기존 광범위 규칙이 8082를 허용하면 중단·보고 | 새 EC2·SG 생성 없음 | 기록한 새 rule ID만 revoke |
| DB | AcademyCraft 전용 스키마·DB 계정·Flyway V1~V4 | BuildIt 스키마/계정은 변경하지 않음. 앱 계정은 academycraft.*만 권한 | 기존 RDS 사용량·용량 영향만, 새 RDS 없음 | AcademyCraft만 정지. DB·백업 보존. 기존 DB 삭제/초기화 금지 |
| 서비스 | 전용 systemd, 8082, 힙 768MB·MemoryMax 1280MB | 여유 700MB 미만 또는 기존 서비스 비정상이면 중단 | 기존 EC2 사용; 증설 금지 | AcademyCraft만 stop/disable. 기존 앱 유지 |

ALB/TG/SG 변경에 필요한 실제 ARN·rule ID와 인증서/DNS는 관리자 조회 후 확정한다.
새 인증서 발급·DNS 변경·유료 인프라·서버 증설이 필요하면 중단하고 별도 보고한다.

## 실행 경로와 IAM 최소 작업

신규 계정 **425805163960**으로 로그인한 관리자 본인의 AWS CloudShell 또는
그 계정의 관리자 AWS CLI 세션에서 실행한다. GitHub CI·기존 f2r OIDC 역할에 관리자 권한을 추가하지 않는다.
AWS 키·비밀번호를 GitHub secret/채팅에 복사하지 않는다.

최종 PR이 병합된 뒤, 그 검토 SHA를 고정하여 아래 경로를 실행한다.

```bash
aws sts get-caller-identity --query Account --output text
# 반드시 425805163960인지 확인
# 로컬 AcademyCraft 저장소를 검토한 병합 SHA로 checkout한 다음:
bash docs/deployment-new-aws/bootstrap-iam.sh
```

동시 관리자 작업 없이 한 명이 실행한다.
필요 작업: iam:GetRole/GetRolePolicy/ListRolePolicies/ListAttachedRolePolicies, iam:GetOpenIDConnectProvider(기존 GitHub provider만),
iam:CreateRole/TagRole/PutRolePolicy(AcademyCraft 역할),
iam:PutRolePolicy(f2r-ec2-ssm-role의 새 backup 정책 추가).
공유 EC2 역할에 PutRolePolicy 권한을 위임하면 다른 정책도 쓸 수 있는 위험이 있으므로
그 권한을 에이전트/OIDC에 새로 부여하지 않고 관리자가 검토한 스크립트만 직접 실행한다.
스크립트는 읽기 검사 후 쓰기 시작, 다른 기존 정책 발견 시 중단, 기존 신뢰 정책 변경 없음.
중간 실패 시 생성된 AcademyCraft 전용 역할/정책을 먼저 조회한다. 자동 정리하지 않는다.

## 기존 ALB·인증서·DNS 확인 및 적용

관리자 CLI에서 sts account 확인 후 다음 READ API로 실제 식별자를 확정한다.
- elbv2 describe-load-balancers (f2r-alb), describe-listeners (443), describe-rules,
  describe-listener-certificates, describe-target-groups, describe-target-health
- acm describe-certificate: ISSUED이고 academy.feel2real.co.kr 또는 *.feel2real.co.kr SAN 포함
- ec2 describe-instances / describe-security-groups / describe-security-group-rules:
  i-0414de33d2803c723 및 ALB SG. IPv4/IPv6와 넓은 포트 범위도 검사
- DNS: academy.feel2real.co.kr가 기존 ALB에 도달하는지 확인. 미설정이면 DNS 소유자 작업 필요

확인 후 필요한 WRITE API는 기존 VPC의 전용 create-target-group(HTTP,8082,
health /actuator/health), register-targets(위 EC2:8082),
create-rule(기존 443 listener, host-header academy.feel2real.co.kr, 신규 TG forward),
authorize-security-group-ingress(앱 SG, TCP8082, SourceSecurityGroupId=기존 ALB SG)만이다.
ALB SG egress가 8082를 막는 경우 그 SG 간 8082만 추가한다. ModifyListener/기본 규칙 교체 금지.
변경 전후 리소스 JSON과 새 rule/TG/SG rule ID는 관리자 작업 기록에 보관한다.
배포 후 target healthy, 정상 인증서 HTTPS, 인터넷 EC2:8082 접근 불가를 확인한다.

## 학원·사업체·최초 원장 대조

기존 AcademyCraft docs/phase6-task3-buildit.md는 BuildIt academy_id와 같은 값을 쓰는 설계다.
그 문서의 예시 1은 실제 운영 매핑 근거가 아니다. 기존 운영 DB 스키마/데이터 변경 금지.
BuildIt 관리자 화면의 사업체 목록과 학원 연결을 먼저 확인한다. 관리자 DB 세션으로 확인한다면
읽기 전용으로 easy_businesses(business_id, code, name), academies(academy_id, name),
academy_easy_business_links(academy_id, business_id)를 대조한다. 개인정보·로그인 ID를 CI로 출력하지 않는다.
AcademyCraft 스키마 생성 후 rea_academies(id,name), rea_members의 동일 학원·원장 기존 존재 여부를
개인 관리자 세션에서 확인한다. 기존 원장이 있으면 중복 생성하지 않는다.
현재 운영 레코드를 아직 조회하지 못했으므로 학원 ID·로그인 ID는 미확정이다.

실제 학원 연결이 확인되면 미확정 로그인 ID/원장 표시 이름만 사용자에게 확인한다.
최초 자격은 별도 검토한 오프라인 관리자 절차로 설정하고 BCrypt hash만 전용 DB에 저장한다.
initialize-owner.py가 관리자 대화형 초기화 도구다. 실제 서버 실행과 JAR 내 BCrypt 의존성 검증은 배포 이후 수행한다. 임시 공개 API, dev profile,
X-Auth-Key fallback, 자동 계정 생성으로 우회하지 않는다.
초기 비밀번호는 관리자 개인 터미널의 숨김 입력으로만 받고 argv/env/CI/저장소/채팅에 남기지 않는다.

## 설정 이후 순서

production의 main 제한·필수 승인 유지 → backup prefix 쓰기 검증 → deploy-academycraft.yml
(deploy_sha=검토한 main SHA, backup_bucket=s3://f2r-deploy-425805163960/backups/academycraft/).
ALB/연결 수를 실제 확인하기 전 CHECKED를 입력하지 않는다.
백업 및 격리 복원 성공 → 원장 자격 설정 → HTTPS 로그인 → 학생/반/출결·강사/학원 격리 검증
→ 성공한 실제 URL에만 BuildIt 카드 연결. 학부모 알림 Mock 유지.

## 부트스트랩 후 HTTPS 설정 1회 실행

2026-10-19 08:49 KST 읽기 전용 run 39178062429:
AcademyCraft 역할과 지정 main workflow 신뢰 확인, 기존 ALB는 default rule만 존재.
BuildIt inspect run 39178093763에서 실제 trust가 기준과 일치하고 기존 정책 이름 2개·managed policy 없음 확인.
RAM 가용 2049MB, 기존 서비스 active, DB 연결13/60, AcademyCraft env/JAR/schema 아직 없음.

관리자 신규 계정 CloudShell의 검토 SHA checkout에서:

```bash
python3 docs/deployment-new-aws/bootstrap-routing.py
```

필요 권한은 기존 ALB/리스너/규칙/TG·ACM 인증서·EC2/SG의 Describe,
전용 TG의 CreateTargetGroup/RegisterTargets, 기존443의 CreateRule,
앱 SG의 ALB-source TCP8082 AuthorizeSecurityGroupIngress이다. 기존 IAM 권한 확대 없음.
스크립트는 모든 조회와 인증서/DNS/SG 검사를 마친 뒤에만 쓰기 시작한다.
DNS 미설정/다른 목적지, SAN 불일치, 기존8082 외부 노출이면 쓰기 없이 중단한다.
이 경우 출력한 차단 사유와 ALB DNS(비밀 아님)를 확인하고 필요한 관리자 작업만 별도 수행한다.
DNS·인증서 발급은 이 스크립트에서 변경하지 않는다. 신규 ALB·EC2 생성 API 없음.

rollback evidence는 관리자 현재 디렉터리의 academycraft-routing-before.json(600)에 남는다.
실패 후 이 파일을 지우고 자동 재시도하지 않는다. 신규 rule ID/SG rule ID/TG ARN만 대상으로
delete-rule → revoke-security-group-ingress → deregister-targets → delete-target-group 순으로
관리자가 검토해 되돌린다. 기존 default rule/기존 SG rule/인증서/DB는 변경·삭제하지 않는다.
기존 TG를 재사용한 경우 그 TG는 삭제하지 않는다. targets 등록은 위 EC2:8082만 허용한다.

## 최초 원장 대화형 초기화

기존 신규 EC2의 개인 관리자 SSM 터미널에서 다운로드한 검토 SHA의 initialize-owner.py를
`sudo python3 initialize-owner.py`로 실행한다. CI/SendCommand/파이프 실행은 거부한다.
BuildIt DB에는 SELECT만 실행해 압구정에듀홀릭학원(공백 및 학원 접미사 정규화)의
사업체·학원·연결을 각각 확인한다. 연결 부재는 신규 설치에서 정상이다.
동명 중복 또는 연결/명칭 불일치, 조회 실패는 중단하며 신규 등록으로 간주하지 않는다.
BuildIt 레코드와 계정은 생성·변경하지 않는다. 기존 식별자는 root600
/etc/academycraft/bootstrap-identity.json 참조 기록에 보존한다. 기존 연결은 검증하여 재사용하며,
연결이 없는 경우에는 존재한다고 가정하지 않는다. SSO와 통합 인증은 활성화하지 않는다.
AcademyCraft 학원·회원 테이블이 모두 비어 있을 때만 잠금 안에서 자체 MAX(id)+1 학원 ID를 명시 발급하여
압구정에듀홀릭학원과 원장을 한 트랜잭션에서 생성한다. 두 서비스의 학원 ID는 별도 공간이다.
기존 AcademyCraft 학원/회원이 하나라도 있으면 중단한다. 자동 재실행·비밀번호 재설정 없음.
관리자가 개인 터미널에서 로그인 ID·표시 이름·숨김 비밀번호를 입력하고 CREATE로 확정한다.
JAR의 BCrypt encoder(strength12)를 사용하며 비밀번호는 Java stdin으로만 전달한다.
DB 명령은 mysql stdin, 임시 cnf는700 디렉터리 내600 파일이며 종료 시 제거한다.
쓰기 연결은 academycraft 전용 계정/스키마로 한정한다. 비밀번호·hash·auth_key 출력 없음.
성공 여부와 비밀이 아닌 조회 결과만 Work에 전달한다. 초기 비밀번호는 전달하지 않는다.
실제 웹 로그인과 학생·반·출결 확인 전에는 BuildIt 카드/위젯 연결을 완료로 처리하지 않는다.



## 최초 등록 실패 진단 및 백업 수정 (2026-10-19)

배포 EC2의 개인 SSM 터미널에서 검토된 동일 SHA의 repair-backup.py를 sudo로 실행한다.
기존 전용 백업 cron의 목적지 검증 후 원본을 /etc/academycraft/academycraft-backup.before-* (600)에
보존하고 cron만 교체한다. --no-tablespaces로 PROCESS 권한을 요구하지 않으며 DB GRANT는 하지 않는다.
명시적인 파이프라인 실패 검사, 고유 파일명, 부분 덤프 제거, 업로드 실패 종료를 적용한다.
원본 백업을 되돌릴 때에는 해당 before 파일을 cron 경로에 복사하고 chmod700한다.
복원한 구형 cron에는 최초 등록을 실행하지 말고 오류를 먼저 해결한다.

그다음 initialize-owner.py --inspect는 읽기 전용으로 다음만 표시한다:
정규화된 이름이 일치한 사업체의 원래 이름/ID/active, 학원의 원래 이름/ID/status,
양방향 연결 및 상대편 이름, exact-name 일치 여부, AcademyCraft 학원·회원 수.
동명·정규화 충돌 또는 다른 학원 연결을 확인하기 전에는 ID를 자동 선택하지 않는다.
출력에 인증정보·학생 정보는 포함하지 않는다. 조회 결과를 검토하기 전에는 원장 등록을 재시도하지 않는다.
계정 생성 모드는 자체적으로 백업을 다시 실행하며, 비제로 종료면 비밀번호 입력 전에 중단한다.
--inspect는 백업이나 DB 쓰기를 하지 않는다. BuildIt에는 SELECT만 실행한다.

변경 검증: python3 docs/deployment-new-aws/test_bootstrap.py
기존 378개 앱 테스트를 반복하지 않도록 이번 PR의 자동 전체 CI는 [skip ci]로 생략한다.
대신 백업 실패/성공과 업로드 차단, 이전 백업 보존, 조회 전용, 중복·기존 데이터 차단,
초기화 내부 백업 실패 시 비밀번호 입력 차단만 검증한다. 운영 결과는 별도로 확인해야 한다.


## ADMIN·modu 및 학원 11·12 조사

운영자가 확인한 업무: ADMIN=압구정에듀홀릭학원 운영 관리자, modu=에듀홀릭학원 홈페이지 운영.
Easy에는 학원·출판사 두 사업체가 있다. 이름만으로 학원11/12 또는 사업체를 선택하지 않는다.
inspect-operating-identity.py는 동일 디렉터리의 initialize-owner.py 도우미를 사용하며 모든 DB 조회를
START TRANSACTION READ ONLY / ROLLBACK으로 감싼다. 운영 EC2 개인 SSM에서 sudo로 실행한다.
ADMIN/modu의 user_id/login_id/role, 학원 owner/slug/handle/status, 사업체 code와 연결,
11/12의 academy_id 참조 테이블별 집계 및 원장별 학생 연결 집계, AcademyCraft 기존 데이터 수만 표시한다.
비밀번호·해시·연락처·학생 행은 조회하지 않는다. 계정/권한/홈페이지/장부·출판사/DB 변경 없음.
조회에서 확인된 역할을 먼저 보고하고 최초 원장 생성 명령은 그 후 제공한다.
AcademyCraft 원장은 별도 login_id와 별도 자격증명을 사용한다. 향후 SSO 검토용으로
BuildIt user_id, 홈페이지용 academy_id, 학원 운영용 academy_id, 학원 business_id,
AcademyCraft academy_id/member_id를 역할별로 구분해 관리해야 하며, 동일 이름/번호를 동일 계정으로 간주하지 않는다.
현재 참조 기록 파일은 자동 SSO나 접근권한을 부여하지 않는다. 초기화의 모호성 차단은 유지한다.


## 독립 최초 등록 — 학원11 삭제와 분리

--independent-eduholic 모드는 사용자가 확인한 홈페이지12/user54/modu/handle eduholic과
사업체1/code ACADEMY/active를 SELECT로 대조한다. 테스트 학원11의 존재는 차단 조건이 아니다.
실제 학원 운영용 BuildIt academy_id는 미확정이므로 홈페이지용 식별자와 별도(null)로 기록한다.
기존 LINKS가 없으면 buildit_link_verified=false를 기록하며 BuildIt에 연결 행을 만들지 않는다.
ADMIN의 학원20 초안과 users4·53·54, 출판사 사업체2를 변경하지 않는다.
AcademyCraft 자체 학원 ID와 별도 원장 login을 발급하고 SSO는 활성화하지 않는다.
admin-f2r/modu/edu-holic 로그인명을 재사용하지 않는다. 기존 빈 DB·백업·숨김 비밀번호 검사 유지.
