#!/usr/bin/env python3
"""Interactive root-only initial owner provisioning. Never run via CI/SSM SendCommand."""
import getpass, json, os, re, secrets, shlex, subprocess, sys, tempfile, zipfile
from pathlib import Path

def require(ok,msg):
 if not ok: raise SystemExit(msg)
def env_file(path):
 d={}
 for line in Path(path).read_text().splitlines():
  line=line.strip()
  if line and not line.startswith('#') and '=' in line:
   k,v=line.split('=',1);p=shlex.split(v);d[k]=p[0] if p else ''
 return d
def cnf(path,host,user,password):
 def q(s):return '"'+s.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')+'"'
 path.write_text('[client]\nhost='+q(host)+'\nuser='+q(user)+'\npassword='+q(password)+'\nconnect-timeout=10\ndefault-character-set=utf8mb4\n');path.chmod(0o600)
def sql(path,db,query):
 p=subprocess.run(['mysql','--defaults-extra-file='+str(path),'--batch','--skip-column-names',db],input=query,text=True,capture_output=True)
 require(p.returncode==0,'Database operation failed; inspect privately. No SQL/credentials printed.')
 return p.stdout.strip()
def lit(s):return 'CONVERT(0x'+s.encode().hex()+' USING utf8mb4)'
def hash_password(password,jar,tmp):
 libs=[]
 with zipfile.ZipFile(jar) as z:
  for prefix in ('BOOT-INF/lib/spring-security-crypto-','BOOT-INF/lib/spring-jcl-'):
   found=[n for n in z.namelist() if n.startswith(prefix) and n.endswith('.jar')]
   require(len(found)==1,'Expected password encoder dependency missing')
   out=tmp/Path(found[0]).name;out.write_bytes(z.read(found[0]));libs.append(str(out))
 source=tmp/'Hash.java'
 source.write_text('import java.io.*; import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder; class Hash { public static void main(String[] a) throws Exception { String p=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)).readLine(); System.out.print(new BCryptPasswordEncoder(12).encode(p)); }}')
 p=subprocess.run(['java','--class-path',os.pathsep.join(libs),str(source)],input=password+'\n',text=True,capture_output=True)
 require(p.returncode==0 and re.fullmatch(r'\$2[aby]\$12\$[./A-Za-z0-9]{53}',p.stdout),'Password encoder failed; no account created')
 return p.stdout

def main():
 require(os.geteuid()==0 and sys.stdin.isatty() and sys.stdout.isatty(),'Run in a private interactive root terminal, never CI or SendCommand')
 os.umask(0o077)
 admin=env_file('/etc/f2r/env');app=env_file('/etc/academycraft/env')
 m=re.fullmatch(r'jdbc:mysql://([^/:]+)(?::3306)?/academycraft(?:\?.*)?',app.get('DB_URL',''))
 require(m and m[1]==admin.get('DB_HOST'),'DB host mismatch; stopped')
 require(app.get('DB_USERNAME')=='academycraft','Unexpected app DB account')
 db=admin.get('DB_NAME','f2r_db');require(re.fullmatch(r'[A-Za-z0-9_]+',db) and db!='academycraft','Unexpected BuildIt database name')
 with tempfile.TemporaryDirectory(prefix='academycraft-owner-') as td:
  tmp=Path(td);ac=tmp/'admin.cnf';own=tmp/'app.cnf'
  cnf(ac,m[1],admin.get('DB_USER') or admin.get('DB_USERNAME',''),admin['DB_PASSWORD'])
  cnf(own,m[1],app['DB_USERNAME'],app['DB_PASSWORD'])
  # Read each source independently: missing cross-service links are valid on first install.
  target='압구정에듀홀릭학원'
  def normalized(value):return re.sub(r'\s+','',value).removesuffix('학원')
  businesses=[];academies=[];links=[]
  for line in sql(ac,db,'SELECT business_id,HEX(name),active FROM easy_businesses;').splitlines():
   ident,label,active=line.split('\t');label=bytes.fromhex(label).decode()
   if normalized(label)==normalized(target):
    print('BuildIt business match:',int(ident),label,'active:',active)
    if active=='1':businesses.append((int(ident),label))
  for line in sql(ac,db,'SELECT academy_id,HEX(name),status FROM academies;').splitlines():
   ident,label,status=line.split('\t');label=bytes.fromhex(label).decode()
   if normalized(label)==normalized(target):
    print('BuildIt academy match:',int(ident),label,'status:',status)
    academies.append((int(ident),label))
  conditions=[]
  if businesses:conditions.append('l.business_id IN ('+','.join(str(r[0]) for r in businesses)+')')
  if academies:conditions.append('l.academy_id IN ('+','.join(str(r[0]) for r in academies)+')')
  if conditions:
   raw=sql(ac,db,'SELECT l.business_id,l.academy_id,HEX(b.name),HEX(a.name) FROM academy_easy_business_links l JOIN easy_businesses b ON b.business_id=l.business_id JOIN academies a ON a.academy_id=l.academy_id WHERE '+' OR '.join(conditions)+' ORDER BY l.business_id,l.academy_id;')
   for line in raw.splitlines():
    bid,aid,bname,aname=line.split('\t');links.append((int(bid),int(aid),bytes.fromhex(bname).decode(),bytes.fromhex(aname).decode()))
  print('Read-only BuildIt business candidates (id, original name):',businesses or 'none')
  print('Read-only BuildIt academy candidates (id, original name):',academies or 'none')
  print('Read-only links (business_id, academy_id, business name, academy name):',links or 'none')
  for kind,rows in (('business',businesses),('academy',academies)):
   for ident,label in rows:print(kind,ident,'exact_name_match:',label==target,'normalized_match:',normalized(label)==normalized(target))
  counts=sql(own,'academycraft','SELECT (SELECT COUNT(*) FROM rea_academies),(SELECT COUNT(*) FROM rea_members);')
  print('Read-only AcademyCraft counts (academies, members):',counts)
  if sys.argv[1:]==['--inspect']:
   print('INSPECT ONLY: no backup repair, account, academy or DB writes performed.');return
  independent=sys.argv[1:]==['--independent-eduholic']
  require(not sys.argv[1:] or independent,'Unknown option; use --inspect or --independent-eduholic')
  require(counts=='0\t0','Not an empty first installation; no duplicate academy/account/reset performed')
  if independent:
   # User-confirmed production homepage; 11 is a retained test academy, never a prerequisite to delete.
   require(sql(ac,db,"SELECT COUNT(*) FROM academies a JOIN users u ON u.user_id=a.tutor_user_id WHERE a.academy_id=12 AND a.tutor_user_id=54 AND a.handle='eduholic' AND u.login_id='modu';")=='1','Production homepage identity changed; stopped')
   require(sql(ac,db,"SELECT COUNT(*) FROM easy_businesses WHERE business_id=1 AND code='ACADEMY' AND active=1;")=='1','Academy business identity changed; stopped')
   identity={'academy_name':target,'buildit_business_id':1,'buildit_homepage_academy_id':12,
             'buildit_homepage_user_id':54,'buildit_management_academy_id':None,
             'buildit_link_verified':any(r[:2]==(1,12) for r in links),
             'excluded_test_academy_id':11,'sso_enabled':False}
  else:
   require(len(businesses)<=1 and len(academies)<=1 and len(links)<=1,'Ambiguous BuildIt identity; review candidate IDs above; no account created')
   require(not links or (businesses and academies and links[0][:2]==(businesses[0][0],academies[0][0])),'BuildIt link/name mismatch; private review required')
   identity={'academy_name':target,'buildit_business_id':businesses[0][0] if businesses else None,
             'buildit_academy_id':academies[0][0] if academies else None,
             'buildit_link_verified':bool(links),'sso_enabled':False}
  require(not Path('/etc/academycraft/bootstrap-identity.json').exists(),'Bootstrap identity already exists; review privately')
  academy_name=target
  print('AcademyCraft first academy:',academy_name,'(independent DB; BuildIt unchanged)')
  backup=subprocess.run(['/etc/cron.daily/academycraft-backup'],capture_output=True,text=True)
  require(backup.returncode==0,'Backup failed; initialization stopped before password/account creation. No backup output or credentials printed.')
  print('PASS: pre-registration backup completed.')
  login=input('New owner login ID: ').strip();name=input('Owner display name: ').strip()
  require(login not in ('admin-f2r','modu','edu-holic'),'Use a separate AcademyCraft owner login ID')
  require(re.fullmatch(r'[A-Za-z0-9_.@-]{3,50}',login) and 1<=len(name)<=50,'Invalid login/display name')
  require(sql(own,'academycraft','SELECT COUNT(*) FROM rea_members WHERE login_id='+lit(login)+';')=='0','Login ID already exists')
  password=getpass.getpass('Initial password (hidden, at least 12 characters): ')
  require(len(password)>=12 and re.search(r'[A-Za-z]',password) and re.search(r'[0-9]',password) and len(password.encode())<=72 and '\n' not in password and '\r' not in password,'Password must be 12+ characters and at most 72 UTF-8 bytes')
  require(password==getpass.getpass('Confirm password (hidden): '),'Password mismatch')
  hashed=hash_password(password,'/opt/academycraft/academycraft.jar',tmp);password=None
  require(input('Create this one owner in AcademyCraft only? Type CREATE: ')=='CREATE','Cancelled')
  # One connection, transaction and named lock prevent duplicate initial owners from this tool.
  key=secrets.token_hex(16)
  query=f"""START TRANSACTION;
SET @got=GET_LOCK('academycraft.initial-owner',5);
SET @empty=((SELECT COUNT(*) FROM rea_academies)+(SELECT COUNT(*) FROM rea_members)=0);
SET @candidate=(SELECT COALESCE(MAX(id),0)+1 FROM rea_academies);
INSERT INTO rea_academies(id,name,active,created_at,updated_at)
SELECT @candidate,{lit(academy_name)},1,NOW(6),NOW(6) WHERE @got=1 AND @empty;
SET @academy=IF(ROW_COUNT()=1,@candidate,NULL);
INSERT INTO rea_members(academy_id,role,name,auth_key,active,created_at,updated_at,login_id,password_hash,failed_login_attempts)
SELECT @academy,'ACADEMY_OWNER',{lit(name)},{lit(key)},1,NOW(6),NOW(6),{lit(login)},{lit(hashed)},0
WHERE @academy IS NOT NULL;
SELECT ROW_COUNT(),@academy;
COMMIT;
DO RELEASE_LOCK('academycraft.initial-owner');"""
  result=sql(own,'academycraft',query);hashed=None;query=None
  fields=result.split('\t')
  require(len(fields)==2 and fields[0]=='1' and fields[1].isdigit(),'Owner was not inserted. Review AcademyCraft state privately; do not auto-retry.')
  identity['academycraft_academy_id']=int(fields[1])
  record=Path('/etc/academycraft/bootstrap-identity.json')
  with record.open('x') as out:json.dump(identity,out,ensure_ascii=False,indent=2)
  record.chmod(0o600)
  print('PASS: initial owner created. Password/hash/key not printed. BuildIt database unchanged.')
if __name__=='__main__':
 try:main()
 except (KeyError,ValueError,OSError,EOFError):raise SystemExit('Initialization stopped. Check prerequisites privately; credentials not printed.')

