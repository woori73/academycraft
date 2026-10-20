"""Run only on the existing new-account EC2 through the protected deploy workflow.
inspect is SELECT-only. update preserves accounts/schema and rolls back files on failure.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlparse


def run(args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs).stdout


def env(path):
    result = {}
    for line in Path(path).read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            parts = shlex.split(value)
            result[key] = parts[0] if parts else ''
    return result


def dbinfo(values, academy=False):
    if academy:
        url = urlparse(values['DB_URL'].removeprefix('jdbc:'))
        return url.hostname, url.path.strip('/'), values['DB_USERNAME'], values['DB_PASSWORD']
    return values['DB_HOST'], values.get('DB_NAME', 'f2r_db'), values.get('DB_USER') or values['DB_USERNAME'], values['DB_PASSWORD']


def client(info, sql):
    host, schema, user, password = info
    if not re.fullmatch(r'[A-Za-z0-9_]+', schema):
        raise ValueError('Invalid schema')
    def quote(value):
        return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n') + '"'
    with tempfile.NamedTemporaryFile(mode='w', prefix='academy-ops-') as f:
        os.chmod(f.name, 0o600)
        f.write('[client]\nhost='+quote(host)+'\nuser='+quote(user)+'\npassword='+quote(password)+'\nconnect-timeout=10\n')
        f.flush()
        return run(['mysql', '--defaults-extra-file='+f.name, '--batch', '--skip-column-names', schema, '-e', sql]).decode().strip().splitlines()


def active(service):
    run(['systemctl', 'is-active', '--quiet', service])


def health():
    body=run(['curl', '-fsS', '--max-time', '5', 'http://127.0.0.1:8082/actuator/health'])
    return json.loads(body)['status']=='UP'


# 허용 Flyway 이력: V4(구) / V5(연결 매핑) / V6(AI 승인 계획 테이블, 추가 전용). 그 외는 중단한다.
KNOWN_SCHEMA_VERSIONS = (['1','2','3','4'], ['1','2','3','4','5'], ['1','2','3','4','5','6'])


def write_env(path, updates):
    # Preserve every unrelated setting; no credentials are printed or passed through SSM arguments.
    lines=Path(path).read_text().splitlines()
    lines=[line for line in lines if line.partition('=')[0] not in updates]
    lines += [key+'='+shlex.quote(value) for key,value in updates.items()]
    fd,name=tempfile.mkstemp(prefix='.academy-ops-',dir=str(Path(path).parent))
    try:
        os.fchmod(fd,0o600)
        with os.fdopen(fd,'w') as f:
            f.write('\n'.join(lines)+'\n'); f.flush(); os.fsync(f.fileno())
        os.replace(name,path)
    finally:
        if os.path.exists(name): os.unlink(name)


def validate_bindings(bindings, build_db, academy_db):
    if not isinstance(bindings,list) or not bindings: raise ValueError('Explicit bindings required')
    pairs=set(); members=set(); roles={}
    for b in bindings:
        if set(b)-{'userId','role','builditAcademyId','academyId','memberId','studentId'}: raise ValueError('Unknown binding field')
        for key in ('userId','builditAcademyId','academyId','memberId'):
            if type(b.get(key)) is not int or b[key]<=0: raise ValueError('Positive binding IDs required')
        if b.get('studentId') is not None or b.get('role') not in ('ADMIN','TUTOR'): raise ValueError('Staff-only bindings required')
        u,a,m,ba=b['userId'],b['academyId'],b['memberId'],b['builditAcademyId']
        pair=(u,a if b['role']=='ADMIN' else 'single')
        if pair in pairs or m in members or (u in roles and roles[u]!=b['role']): raise ValueError('Ambiguous bindings')
        pairs.add(pair);members.add(m);roles[u]=b['role']
        found=client(build_db,f"SELECT role FROM users WHERE user_id={u}")
        if found != [b['role']]: raise ValueError('Current BuildIt role mismatch')
        if client(build_db,f"SELECT COUNT(*) FROM academies WHERE academy_id={ba}") != ['1']: raise ValueError('BuildIt academy missing')
        if b['role']=='TUTOR' and client(build_db,f"SELECT COUNT(*) FROM academies WHERE academy_id={ba} AND tutor_user_id={u}") != ['1']:
            raise ValueError('TUTOR does not own approved BuildIt academy')
        found=client(academy_db,f"SELECT role FROM rea_members WHERE id={m} AND academy_id={a} AND active=1")
        if found not in (['ACADEMY_OWNER'],['TEACHER']): raise ValueError('Active AcademyCraft staff membership missing')
        if client(academy_db,f"SELECT COUNT(*) FROM rea_academies WHERE id={a} AND active=1") != ['1']: raise ValueError('Inactive AcademyCraft academy')
    if not any(b['role']=='TUTOR' for b in bindings) or not any(b['role']=='ADMIN' for b in bindings): raise ValueError('Both roles must be explicitly mapped')


def approved_source(build_db):
    rows=client(build_db,"SELECT JSON_OBJECT('id',a.academy_id,'owner',a.tutor_user_id,'name',a.name,'slug',a.slug,'handle',a.handle,'userId',u.user_id,'login',u.login_id,'role',u.role,'memberName',u.name) FROM academies a JOIN users u ON u.user_id=a.tutor_user_id WHERE a.academy_id IN (12,20) ORDER BY a.academy_id")
    rows=[json.loads(r) for r in rows]
    if len(rows)!=2 or [(r['id'],r['userId'],r['role']) for r in rows]!=[(12,54,'TUTOR'),(20,4,'ADMIN')] or rows[0]['login']!='modu':
        raise ValueError('Approved BuildIt ownership or identity mismatch')
    for r in rows:
        if not r['name'] or not r['slug'] or not r['memberName']: raise ValueError('Incomplete source academy identity')
    return rows


def sqltext(value):
    return "CONVERT(0x"+str(value).encode().hex()+" USING utf8mb4)"


def bootstrap_members(source,db):
    # One MySQL connection, named lock, transaction, unique mapping keys. No UPDATE/DELETE.
    # Any SQL error closes the connection and rolls back all newly inserted rows.
    sql=["SET NAMES utf8mb4;", "SET @got=GET_LOCK('academycraft-approved-bootstrap-12-20',20);",
         "CREATE TEMPORARY TABLE bootstrap_guard (n INT NOT NULL CHECK(n=1));",
         "INSERT INTO bootstrap_guard VALUES (@got);", "START TRANSACTION;"]
    for r in source:
        bid=r['id']; digest=hashlib.sha256(json.dumps(r,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
        existing=client(db,f"SELECT source_identity_sha256 FROM rea_buildit_academy_links WHERE buildit_academy_id={bid}")
        if existing and existing!=[digest]: raise ValueError('Existing mapping source identity mismatch')
        if not existing:
            # Pre-existing unlinked academies are never adopted by name or ID.
            sql += ["SET @aid=(SELECT COALESCE(MAX(id),0)+1 FROM rea_academies);",
                    f"INSERT INTO rea_academies(id,name,memo,active,created_at) VALUES(@aid,{sqltext(r['name'])},{sqltext('BuildIt academy '+str(bid)+'; slug='+r['slug'])},1,NOW(6));",
                    f"INSERT INTO rea_buildit_academy_links VALUES({bid},@aid,'{digest}',NOW(6));"]
        for uid,role,name in [(r['userId'],r['role'],r['memberName'])]+([(4,'ADMIN',source[1]['memberName'])] if bid==12 else []):
            sql += [f"SET @aid=(SELECT academy_id FROM rea_buildit_academy_links WHERE buildit_academy_id={bid});",
                    f"INSERT INTO rea_members(academy_id,role,name,auth_key,active,created_at) SELECT @aid,'ACADEMY_OWNER',{sqltext(name)},REPLACE(UUID(),'-',''),1,NOW(6) WHERE NOT EXISTS(SELECT 1 FROM rea_buildit_member_links WHERE buildit_user_id={uid} AND buildit_academy_id={bid});",
                    f"INSERT INTO rea_buildit_member_links SELECT {uid},{bid},LAST_INSERT_ID(),'{role}',NOW(6) WHERE NOT EXISTS(SELECT 1 FROM rea_buildit_member_links WHERE buildit_user_id={uid} AND buildit_academy_id={bid});"]
    sql += ["COMMIT;", "DO RELEASE_LOCK('academycraft-approved-bootstrap-12-20');"]
    client(db,'\n'.join(sql))
    result=client(db,"SELECT JSON_OBJECT('userId',m.buildit_user_id,'role',m.buildit_role,'builditAcademyId',m.buildit_academy_id,'academyId',a.academy_id,'memberId',m.member_id) FROM rea_buildit_member_links m JOIN rea_buildit_academy_links a USING(buildit_academy_id) ORDER BY m.buildit_user_id,m.buildit_academy_id")
    bindings=[json.loads(r) for r in result]
    if {(b['userId'],b['builditAcademyId'],b['role']) for b in bindings}!={(54,12,'TUTOR'),(4,12,'ADMIN'),(4,20,'ADMIN')}: raise ValueError('Unexpected existing member mapping')
    return bindings


def main():
    mode=sys.argv[1]
    if mode not in ('inspect','update'): raise ValueError('Unsupported operation')
    build=env('/etc/f2r/env'); academy=env('/etc/academycraft/env')
    build_db=dbinfo(build); academy_db=dbinfo(academy,True)
    approved=set(sys.argv[2].split(','))
    for info in (build_db,academy_db):
        if hashlib.sha256(info[0].encode()).hexdigest() not in approved: raise ValueError('DB is not in verified new AWS account')
    active('f2r-app');active('buildit-wiki');active('academycraft')
    versions=client(academy_db,'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank')
    if versions not in KNOWN_SCHEMA_VERSIONS: raise ValueError('Unexpected existing schema; no migration/downgrade permitted')
    if mode=='inspect':
        # Output no password, tokens, auth keys, names, contact information or student records.
        result={
            'modu':client(build_db,"SELECT user_id,role FROM users WHERE login_id='modu'"),
            'admins':client(build_db,"SELECT user_id,role FROM users WHERE role='ADMIN'"),
            'builditAcademies':client(build_db,"SELECT academy_id,tutor_user_id FROM academies ORDER BY academy_id"),
            'academycraftAcademies':client(academy_db,"SELECT id,active FROM rea_academies ORDER BY id"),
            'academycraftMemberships':client(academy_db,"SELECT id,academy_id,role,active,(login_id IS NOT NULL) FROM rea_members ORDER BY id"),
            'sharedKeyPresent':[bool(build.get('ACADEMYCRAFT_GATEWAY_SECRET')),bool(academy.get('ACADEMYCRAFT_GATEWAY_SECRET'))],
            'bindingsPresent':bool(academy.get('ACADEMYCRAFT_BUILDIT_BINDINGS')),
            'schemaVersions':versions,
            'existingJarSha256':hashlib.sha256(Path('/opt/academycraft/academycraft.jar').read_bytes()).hexdigest(),
        }
        print(json.dumps(result));return
    bindings=json.loads(base64.b64decode(sys.argv[3],validate=True))
    bootstrap = bindings == {'bootstrapApproved': [12, 20]}
    if not bootstrap: validate_bindings(bindings,build_db,academy_db)
    source = approved_source(build_db) if bootstrap else None
    # Refuse to replace existing assignments. Additional setup needs an explicit mapping review.
    previous=json.loads(academy.get('ACADEMYCRAFT_BUILDIT_BINDINGS','[]'))
    for old in previous:
        if not bootstrap and old not in bindings: raise ValueError('Existing membership binding would be removed or changed')
    keys={x for x in (build.get('ACADEMYCRAFT_GATEWAY_SECRET'),academy.get('ACADEMYCRAFT_GATEWAY_SECRET')) if x}
    if len(keys)>1: raise ValueError('Existing shared keys disagree')
    key=next(iter(keys),None) or secrets.token_urlsafe(48)
    if len(key.encode())<32: raise ValueError('Existing shared key too short; no rotation performed')
    sha=sys.argv[4]; expected=sys.argv[5]
    if not re.fullmatch('[0-9a-f]{40}',sha) or not re.fullmatch('[0-9a-f]{64}',expected): raise ValueError('Invalid pinned artifact')
    backup=Path('/backup/academycraft')/('shared-session-'+time.strftime('%Y%m%dT%H%M%S')+'-'+str(os.getpid()))
    backup.mkdir(mode=0o700,parents=False,exist_ok=False)
    os.chmod(backup,0o700)
    files=['/opt/academycraft/academycraft.jar','/etc/academycraft/env','/etc/f2r/env']
    for i,path in enumerate(files):
        shutil.copy2(path,backup/str(i));os.chmod(backup/str(i),0o600)
    if shutil.disk_usage(backup).free < 1024**3: raise ValueError('Insufficient backup/rollback disk space')
    for i,path in enumerate(files):
        if hashlib.sha256(Path(path).read_bytes()).digest()!=hashlib.sha256((backup/str(i)).read_bytes()).digest(): raise ValueError('File backup verification failed')
    # Transaction-consistent backup without running the existing retention/deletion cron.
    host,schema,user,password=academy_db
    dump=backup/'academycraft.sql'
    procenv=dict(os.environ,MYSQL_PWD=password)
    with dump.open('wb') as out:
        subprocess.run(['mysqldump','-h',host,'-u',user,'--single-transaction','--no-tablespaces','--routines',schema],env=procenv,stdout=out,stderr=subprocess.PIPE,check=True)
    os.chmod(dump,0o600)
    if dump.stat().st_size==0: raise ValueError('Empty backup')
    prefix='s3://f2r-deploy-425805163960/backups/academycraft/'+backup.name
    run(['aws','s3','cp',str(dump),prefix+'/academycraft.sql','--only-show-errors'])
    run(['aws','s3','cp',prefix+'/academycraft.sql',str(backup/'verify.sql'),'--only-show-errors'])
    if hashlib.sha256(dump.read_bytes()).digest()!=hashlib.sha256((backup/'verify.sql').read_bytes()).digest(): raise ValueError('Remote backup verification failed')
    stage=backup/'candidate.jar'
    run(['aws','s3','cp','s3://f2r-deploy-425805163960/academycraft/'+sha+'/academycraft.jar',str(stage),'--only-show-errors'])
    if hashlib.sha256(stage.read_bytes()).hexdigest()!=expected: raise ValueError('Artifact digest mismatch')
    try:
        if not bootstrap:
            write_env('/etc/academycraft/env',{'ACADEMYCRAFT_GATEWAY_SECRET':key,'ACADEMYCRAFT_BUILDIT_BINDINGS':json.dumps(bindings,separators=(',',':'))})
        write_env('/etc/f2r/env',{'ACADEMYCRAFT_GATEWAY_SECRET':key})
        # Existing unit/DB passwords/JWT secrets and BuildIt accounts are untouched.
        candidate=Path('/opt/academycraft')/('candidate-'+sha+'.jar')
        shutil.copyfile(stage,candidate);os.chmod(candidate,0o640)
        shutil.chown(candidate,user='academycraft',group='academycraft')
        run(['systemctl','stop','academycraft'])
        os.replace(candidate,'/opt/academycraft/academycraft.jar')
        run(['systemctl','start','academycraft'])
        for _ in range(60):
            active('f2r-app');active('buildit-wiki')
            try:
                if health(): break
            except (subprocess.CalledProcessError,ValueError): pass
            time.sleep(3)
        else: raise ValueError('AcademyCraft health timeout')
        after=client(academy_db,'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank')
        if after not in KNOWN_SCHEMA_VERSIONS[1:]: raise ValueError('Unexpected schema after additive migration')
        if bootstrap:
            bindings=bootstrap_members(source,academy_db)
            validate_bindings(bindings,build_db,academy_db)
            if any(old not in bindings for old in previous): raise ValueError('Existing binding mismatch')
            write_env('/etc/academycraft/env',{'ACADEMYCRAFT_GATEWAY_SECRET':key,'ACADEMYCRAFT_BUILDIT_BINDINGS':json.dumps(bindings,separators=(',',':'))})
            run(['systemctl','restart','academycraft'])
            for _ in range(60):
                try:
                    if health(): break
                except (subprocess.CalledProcessError,ValueError): pass
                time.sleep(3)
            else: raise ValueError('Configured AcademyCraft health timeout')
    except BaseException:
        for i,path in enumerate(files):
            shutil.copyfile(backup/str(i),path);shutil.copystat(backup/str(i),path)
        shutil.chown('/opt/academycraft/academycraft.jar',user='academycraft',group='academycraft')
        run(['systemctl','restart','academycraft'])
        raise
    print(json.dumps({'updated':sha,'backup':str(backup),'bindings':len(bindings),'builditRestarted':False,'schemaVersions':after,'mapping':bindings}))


if __name__=='__main__':
    try: main()
    except Exception as e:
        # Never echo command arguments, configuration contents or DB errors.
        print('AcademyCraft operation stopped: '+(str(e) if type(e) is ValueError else type(e).__name__),file=sys.stderr)
        sys.exit(1)
