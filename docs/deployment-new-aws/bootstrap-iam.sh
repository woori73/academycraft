#!/usr/bin/env bash
# 신규 AWS 계정 관리자용. 사용자 승인 후만 실행. 다른 역할의 신뢰 정책은 변경하지 않는다.
set -euo pipefail
export AWS_DEFAULT_REGION=ap-northeast-2
[ "$(aws sts get-caller-identity --query Account --output text)" = 425805163960 ] || { echo 'Wrong AWS account'; exit 1; }
cd "$(dirname "$0")"
ROLE=github-actions-academycraft-deploy
if aws iam get-role --role-name "$ROLE" >/tmp/academycraft-existing-role.json 2>/dev/null; then
  python3 - <<'PY'
import json
actual=json.load(open('/tmp/academycraft-existing-role.json'))['Role']['AssumeRolePolicyDocument']
expected=json.load(open('trust.json'))
if actual!=expected: raise SystemExit('Existing role trust differs. Stopped without overwriting trust.')
PY
else
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document file://trust.json --tags Key=Project,Value=AcademyCraft >/dev/null
fi
aws iam put-role-policy --role-name "$ROLE" --policy-name academycraft-new-host-only --policy-document file://deploy-policy.json
aws iam put-role-policy --role-name f2r-ec2-ssm-role --policy-name academycraft-backup-prefix-only --policy-document file://host-backup-policy.json
rm -f /tmp/academycraft-existing-role.json
echo 'AcademyCraft role and backup prefix policy ready. No BuildIt role trust or policy replaced.'
