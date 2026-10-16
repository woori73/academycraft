# AcademyCraft 전용 IAM 역할 설계

작성: 2026-10-16
상태: 설계 (실제 생성은 미승인)

## 1. 역할 기본 정보

| 항목 | 값 |
|---|---|
| 역할 이름 | `github-actions-academycraft-deploy` |
| 역할 ARN (예정) | `arn:aws:iam::067573916809:role/github-actions-academycraft-deploy` |
| 용도 | AcademyCraft GitHub Actions 배포 전용 |
| BuildIt 역할과 관계 | **완전 분리** — `github-actions-f2r-deploy` 재사용 금지 |

## 2. 신뢰 정책 (Trust Policy)

GitHub OIDC subject 새 형식 적용 (ID 포함):

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "Federated": "arn:aws:iam::067573916809:oidc-provider/token.actions.githubusercontent.com"
            },
            "Action": "sts:AssumeRoleWithWebIdentity",
            "Condition": {
                "StringEquals": {
                    "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
                },
                "StringLike": {
                    "token.actions.githubusercontent.com:sub": "repo:woori73@299170593/academycraft@1421088210:environment:production"
                }
            }
        }
    ]
}
```

### Subject 형식 검증

| 구성 요소 | 값 | 출처 |
|---|---|---|
| owner | `woori73@299170593` | GitHub API 실측 (login + id) |
| repo | `academycraft@1421088210` | GitHub API 실측 (name + id) |
| environment | `production` | 배포 워크플로우의 `environment: production` |

**주의**: `environment: production`을 지정한 job만 이 subject를 받는다.
`environment`가 없는 job은 `ref` 기반 subject가 되어 거부된다.

## 3. 권한 정책 (최소 권한)

BuildIt 역할의 권한과 분리. SSM·S3만 허용.

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "SSMSendCommand",
            "Effect": "Allow",
            "Action": [
                "ssm:SendCommand"
            ],
            "Resource": [
                "arn:aws:ec2:ap-northeast-2:067573916809:instance/i-09d7cabb5a42d1135",
                "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"
            ]
        },
        {
            "Sid": "SSMGetCommandStatus",
            "Effect": "Allow",
            "Action": [
                "ssm:GetCommandInvocation",
                "ssm:ListCommands",
                "ssm:ListCommandInvocations"
            ],
            "Resource": "*",
            "Condition": {
                "StringEquals": {
                    "aws:RequestedRegion": "ap-northeast-2"
                }
            }
        },
        {
            "Sid": "S3DeployArtifacts",
            "Effect": "Allow",
            "Action": [
                "s3:PutObject",
                "s3:GetObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::feel2real-buildit-deploy",
                "arn:aws:s3:::feel2real-buildit-deploy/academycraft/*"
            ]
        },
        {
            "Sid": "S3BackupWrite",
            "Effect": "Allow",
            "Action": [
                "s3:PutObject",
                "s3:GetObject",
                "s3:DeleteObject"
            ],
            "Resource": "arn:aws:s3:::feel2real-buildit-deploy/backups/academycraft/*"
        }
    ]
}
```

**백업 버킷 정책**: `s3://feel2real-buildit-deploy/backups/academycraft/` 로 고정.
워크플로우의 `backup_bucket` 입력은 이 prefix와 일치해야 하며,
일치하지 않으면 사전점검에서 실패한다. 임의 버킷 지정 불가.

### 제외된 권한 (BuildIt 역할에는 있지만 여기서는 불필요)

- EC2 직접 조작 (`ec2:*`) — SSM 경유만 사용
- IAM 변경 — 없음
- 다른 S3 버킷 — `academycraft/` prefix로 제한
- RDS·ALB 변경 — 콘솔 수동 작업으로 분리

## 4. GitHub Environment "production" 보호 규칙 (설계)

GitHub 저장소 Settings > Environments > production 에서 설정 (수동):

1. **Required reviewers**: 1명 이상 승인 필수
2. **Deployment branches**: `main` 브랜치만 허용
3. **Environment secrets**: DB 관련 시크릿 제거 (Secrets Manager로 이관)
   - 유지: 없음 (배포에 필요한 민감값은 모두 EC2 내부에서 조회)
   - 설정: `AWS_ROLE_ARN` (민감하지 않은 ARN, 변수로도 가능)

## 5. EC2 인스턴스 프로파일 요구사항 (별도 작업, 미승인)

배포 스크립트가 EC2 내부에서 Secrets Manager를 읽으려면
인스턴스 프로파일에 다음 권한이 필요하다. **실제 변경은 미승인.**

```json
{
    "Effect": "Allow",
    "Action": [
        "secretsmanager:GetSecretValue"
    ],
    "Resource": "arn:aws:secretsmanager:ap-northeast-2:067573916809:secret:academycraft/db-admin-*"
}
```

Secrets Manager 시크릿 이름 (예정): `academycraft/db-admin`
형식: `{"host": "...", "username": "...", "password": "..."}`

## 6. 생성 절차 (승인 후 수동 실행)

1. IAM 콘솔 → Roles → Create role
2. Trusted entity: Web identity → `token.actions.githubusercontent.com`
3. Audience: `sts.amazonaws.com`
4. 2항의 신뢰 정책 JSON 붙여넣기
5. 3항의 권한 정책 연결 (인라인 정책으로 생성)
6. 역할 이름: `github-actions-academycraft-deploy`
7. GitHub Environment `production`에 4항의 보호 규칙 설정

**예상 추가 비용**: 
- IAM 역할: $0 (무료)
- Secrets Manager: **월 $0.40/시크릿** + API 호출 $0.05/10,000건
  - 1개 시크릿(`academycraft/db-admin`) 기준: 월 약 $0.40 (연 약 $4.80)
  - API 호출은 배포 시에만 발생하여 무시 가능한 수준
  - **무료 대안**: SSM Parameter Store (SecureString)는 표준 파라미터 무료.
    Secrets Manager의 자동 로테이션이 불필요하면 Parameter Store로 전환 가능.
    (단, 현재 설계는 Secrets Manager 기준)
