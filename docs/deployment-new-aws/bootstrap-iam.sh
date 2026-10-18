#!/usr/bin/env bash
# Run only in an authenticated NEW-account administrator session, never in CI.
set -euo pipefail
umask 077
export AWS_DEFAULT_REGION=ap-northeast-2
[ "$(aws sts get-caller-identity --query Account --output text)" = 425805163960 ] || { echo 'Wrong AWS account'; exit 1; }
cd "$(dirname "$0")"
python3 - <<'PY'
import json, subprocess, sys
from pathlib import Path
ACCOUNT='425805163960'
ROLE='github-actions-academycraft-deploy'
def aws(*args, missing=False):
    p=subprocess.run(['aws',*args,'--output','json'],capture_output=True,text=True)
    if p.returncode:
        if missing and '(NoSuchEntity)' in p.stderr: return None
        # Never interpret AccessDenied, throttling or a network error as absence.
        raise SystemExit('AWS operation failed: '+args[0]+' '+args[1]+'; no fallback mutation attempted')
    return json.loads(p.stdout) if p.stdout.strip() else {}
def load(name): return json.loads(Path(name).read_text())
def check_policy(role,name,expected):
    current=aws('iam','get-role-policy','--role-name',role,'--policy-name',name,missing=True)
    if current is not None and current['PolicyDocument']!=expected:
        raise SystemExit('Existing named policy differs; stopped without replacing: '+role+'/'+name)
    return current is not None
trust=load('trust.json')
policy=load('deploy-policy.json')
backup=load('host-backup-policy.json')
# Complete ALL read checks before the first write. No shared role trust is changed.
role=aws('iam','get-role','--role-name',ROLE,missing=True)
if role and role['Role']['AssumeRolePolicyDocument']!=trust:
    raise SystemExit('Existing AcademyCraft trust differs; stopped without overwriting')
if role and role['Role']['Arn']!='arn:aws:iam::'+ACCOUNT+':role/'+ROLE:
    raise SystemExit('Unexpected role ARN')
if role:
    attached=aws('iam','list-attached-role-policies','--role-name',ROLE)['AttachedPolicies']
    names=aws('iam','list-role-policies','--role-name',ROLE)['PolicyNames']
    if attached or set(names)-{'academycraft-new-host-only'}:
        raise SystemExit('Unexpected AcademyCraft policies; stopped without modification')
aws('iam','get-role','--role-name','f2r-ec2-ssm-role')
aws('iam','get-open-id-connect-provider','--open-id-connect-provider-arn',
    'arn:aws:iam::'+ACCOUNT+':oidc-provider/token.actions.githubusercontent.com')
owned_policy=check_policy(ROLE,'academycraft-new-host-only',policy) if role else False
backup_policy=check_policy('f2r-ec2-ssm-role','academycraft-backup-prefix-only',backup)
if not role:
    aws('iam','create-role','--role-name',ROLE,'--assume-role-policy-document',json.dumps(trust),
        '--max-session-duration','3600','--tags','Key=Project,Value=AcademyCraft')
    print('Created AcademyCraft role')
if not owned_policy:
    aws('iam','put-role-policy','--role-name',ROLE,'--policy-name','academycraft-new-host-only',
        '--policy-document',json.dumps(policy))
    print('Added AcademyCraft deploy policy')
if not backup_policy:
    aws('iam','put-role-policy','--role-name','f2r-ec2-ssm-role',
        '--policy-name','academycraft-backup-prefix-only','--policy-document',json.dumps(backup))
    print('Added AcademyCraft backup prefix policy')
assert aws('iam','get-role','--role-name',ROLE)['Role']['AssumeRolePolicyDocument']==trust
assert check_policy(ROLE,'academycraft-new-host-only',policy)
assert check_policy('f2r-ec2-ssm-role','academycraft-backup-prefix-only',backup)
print('PASS: exact trust and both policies verified. Existing BuildIt roles/trust unchanged.')
PY
