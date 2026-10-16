# BuildIt AWS OIDC 인증 장애 — Claude 인수인계서

작성: 2026-10-16 17:40 KST (Muse)
상태: **미해결** — Muse는 AWS OIDC 관련 작업을 중단함

---

## 1. 장애 발생 전 정상 작동했던 설정

**IAM 역할**: `arn:aws:iam::067573916809:role/github-actions-f2r-deploy`

**신뢰 정책** (2026-10-16 13:37 KST까지 정상 작동):

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
                    "token.actions.githubusercontent.com:sub": "repo:woori73/feel2real-buildit:*"
                }
            }
        }
    ]
}
```

16개 BuildIt 워크플로우가 이 역할로 OIDC 인증 중이었음 (`environment:` 미사용, 전부 ref 기반 subject).

---

## 2. Muse의 변경 내역 (변경 전·후 및 시각)

**변경 목적**: AcademyCraft 배포 워크플로우(`woori73/academycraft`)가 같은 역할을 수임할 수 있게 신뢰 조건 추가

**변경 전** (백업: `docs/iam-trust-policy-backup-20261016.json`):
- `sub`: `"repo:woori73/feel2real-buildit:*"` (단일 문자열)

**변경 후** (2026-10-16 15:36 KST, 사용자가 콘솔에서 직접 적용):
- `sub`: `["repo:woori73/feel2real-buildit:*", "repo:woori73/academycraft:environment:production"]` (배열)

**그 후 조치**:
- 16:38 KST: 불가시 문자 의심 → 사용자가 두 조건을 직접 타이핑으로 재입력 후 저장
- 16:44 KST: 사용자가 콘솔 보기 화면에서 실제 저장값을 복사 → JSON 구조 완벽함을 확인
- 17:04 KST: A/B 테스트 위해 BuildIt 전용 원래 정책으로 일시 복구 (아래 3항 참조)

---

## 3. A/B 테스트 결과

원래 작동하던 정책(1항의 JSON 그대로)으로 되돌린 후 테스트:

| 항목 | 값 |
|---|---|
| 브랜치 | `main` |
| 워크플로우 | EC2 Diagnose (read-only, manual) |
| 이벤트 | `workflow_dispatch` |
| Run ID | `38871511477` |
| 결과 | **실패** — `Could not assume role with OIDC: Not authorized to perform sts:AssumeRoleWithWebIdentity` |

**결론**: 신뢰 정책 내용은 원인이 아님. 원래 정책으로도 실패하므로 정책 바깥에 원인이 있음.

---

## 4. GitHub Actions 실행 기록

**마지막 성공**:
- Run `38851617533` — Deploy to EC2, `workflow_dispatch`, 2026-10-16 04:37 UTC (13:37 KST)
- https://github.com/woori73/feel2real-buildit/actions/runs/38851617533

**실패 시작** (신뢰 정책 변경 직후):
- Run `38862211032` — EC2 Diagnose, 2026-10-16 06:36 UTC (15:36 KST) — 첫 실패
- https://github.com/woori73/feel2real-buildit/actions/runs/38862211032

**이후 실패** (전부 동일 OIDC 오류):
- `38862880457`, `38863691952`, `38864150780`, `38864438340`, `38868594238`, `38871511477` (EC2 Diagnose)
- `38862388700` (Production Diagnostic)
- `38871839790` (DesignCraft production read-only pr)
- `38871287967` (DesignCraft CLI recovery preflight)

**대조**: OIDC를 쓰지 않는 워크플로우(Build Check, Prod login modal check)는 같은 기간 정상 성공. GitHub Actions 자체는 정상이며 AWS OIDC 인증만 차단됨.

---

## 5. 현재 IAM·OIDC 정보

- **역할 ARN**: `arn:aws:iam::067573916809:role/github-actions-f2r-deploy`
- **OIDC Provider**: `arn:aws:iam::067573916809:oidc-provider/token.actions.githubusercontent.com`
- **현재 신뢰 정책**: BuildIt 전용으로 일시 복구된 상태 (1항과 동일)
- **확인된 subject** (결정적, GitHub 공개 문서 + 실행 메타데이터):
  `repo:woori73/feel2real-buildit:ref:refs/heads/main`
- **확인된 audience**: `sts.amazonaws.com` (`aws-actions/configure-aws-credentials@v4` 기본값)
- **AcademyCraft 조건**: 원인 확정 전까지 다시 추가하지 말 것 (사용자 지시)

---

## 6. 원인 후보 — 확인된 것과 미확인 사항

### 확인 완료 (원인 아님)
- [x] 신뢰 정책 JSON 구조 — 유효함 (AWS 저장 성공, 보기 화면에서 원본 확인)
- [x] JSON 중복 키 — A/B 테스트로 배제 (원래 정책도 실패)
- [x] 불가시 문자 — 사용자가 직접 타이핑으로 재입력 후에도 실패
- [x] Subject 형식 — 결정적으로 `repo:woori73/feel2real-buildit:ref:refs/heads/main`, `:*` 패턴과 매칭되어야 정상
- [x] 전파 지연 — 30분 이상 대기 후에도 실패, 7회 연속 동일 오류
- [x] GitHub Actions 자체 문제 — 비-OIDC 워크플로우는 정상

### 미확인 (Claude 조사 필요)
- [ ] **OIDC Provider 상태**: thumbprint, 최근 수정 시각, 삭제 후 재생성 여부
- [ ] **역할 자체 변경 여부**: `github-actions-f2r-deploy`가 삭제 후 재생성됐는지
- [ ] **CloudTrail**: `AssumeRoleWithWebIdentity` 실패 이벤트의 상세 (거부된 subject, 정확한 오류 코드)
- [ ] AWS 계정 수준 변경 (SCP 등) — 가능성은 낮음

---

## 7. 관련 GitHub 자료 위치

- **AcademyCraft 저장소** (`woori73/academycraft`):
  - 배포 워크플로우: `.github/workflows/deploy-academycraft.yml` (commit `e37431bf` 최초, `6184e3f` environment 수정)
  - 신뢰 정책 백업: `docs/iam-trust-policy-backup-20261016.json`
  - 배포 계획: `docs/P0-4-deploy-plan.md`
- **BuildIt 저장소** (`woori73/feel2real-buildit`):
  - 진단 워크플로우: `.github/workflows/ec2-diagnose.yml` (workflow ID `358590616`)
  - 배포 워크플로우: `.github/workflows/deploy-ec2.yml`

---

## 8. 차단된 작업

- **DesignCraft 운영 점검**: `DesignCraft production read-only pr` (`38871839790`), `DesignCraft CLI recovery preflight` (`38871287967`)가 OIDC 오류로 실패 중
- **AcademyCraft 배포 준비**: 전면 중단 (워크플로우는 준비됐으나 실행 불가)
- **BuildIt 진단**: 모든 OIDC 기반 진단 워크플로우 실행 불가

---

## 9. Claude 권장 조사 순서

1. **IAM 콘솔 → Identity providers** → `token.actions.githubusercontent.com`:
   thumbprint 값, 생성/수정 시각 확인. GitHub 공식 thumbprint와 비교.
2. **CloudTrail Event history** → 이벤트 이름 `AssumeRoleWithWebIdentity` 필터:
   최근 실패 이벤트에서 거부된 subject와 상세 오류 확인 (읽기 전용).
3. **IAM → Roles** → `github-actions-f2r-deploy`:
   역할 생성 시각 확인 (최근 재생성 여부), 연결된 권한 정책에 변경 없는지 확인.
4. 원인 확정 후 최소 수정 → `EC2 Diagnose` 1회 실행으로 검증.
5. BuildIt 복구 확인 후 AcademyCraft 조건(`repo:woori73/academycraft:environment:production`) 재추가 검토.

**주의**: 비밀 토큰·비밀번호를 문서·로그에 기록하지 말 것. OIDC `sub`·`aud`는 공개 클레임이라 기록 가능.
