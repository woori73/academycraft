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
  # BuildIt connection is SELECT-only in this utility; never write to it.
  raw=sql(ac,db,'SELECT b.business_id,HEX(b.name),l.academy_id,HEX(a.name) FROM easy_businesses b JOIN academy_easy_business_links l ON l.business_id=b.business_id JOIN academies a ON a.academy_id=l.academy_id WHERE b.active=1 ORDER BY b.business_id,l.academy_id LIMIT 100;')
  candidates=[]
  for line in raw.splitlines():
   bid,bname,aid,aname=line.split('\t');row=(int(bid),bytes.fromhex(bname).decode(),int(aid),bytes.fromhex(aname).decode());candidates.append(row)
   print('business_id:',row[0],'business:',row[1],'academy_id:',row[2],'academy:',row[3])
  require(candidates,'No existing BuildIt academy/business mapping; stopped without guessing an ID')
  business=int(input('Existing business_id: '));academy=int(input('Existing academy_id: '))
  matches=[r for r in candidates if r[0]==business and r[2]==academy];require(len(matches)==1,'Mapping not found')
  academy_name=matches[0][3]
  require(sql(own,'academycraft',f"SELECT COUNT(*) FROM rea_members WHERE academy_id={academy} AND role='ACADEMY_OWNER';")=='0','Existing owner found; no duplicate or credential reset performed')
  login=input('New owner login ID: ').strip();name=input('Owner display name: ').strip()
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
INSERT INTO rea_academies(id,name,active,created_at,updated_at)
SELECT {academy},{lit(academy_name)},1,NOW(6),NOW(6) WHERE @got=1 AND NOT EXISTS(SELECT 1 FROM rea_academies WHERE id={academy});
INSERT INTO rea_members(academy_id,role,name,auth_key,active,created_at,updated_at,login_id,password_hash,failed_login_attempts)
SELECT {academy},'ACADEMY_OWNER',{lit(name)},{lit(key)},1,NOW(6),NOW(6),{lit(login)},{lit(hashed)},0
WHERE @got=1 AND EXISTS(SELECT 1 FROM rea_academies WHERE id={academy} AND name={lit(academy_name)} AND active=1)
AND NOT EXISTS(SELECT 1 FROM rea_members WHERE academy_id={academy} AND role='ACADEMY_OWNER');
SELECT ROW_COUNT();
COMMIT;
DO RELEASE_LOCK('academycraft.initial-owner');"""
  result=sql(own,'academycraft',query);hashed=None;query=None
  require(result=='1','Owner was not inserted. Review AcademyCraft state privately; do not auto-retry.')
  print('PASS: initial owner created. Password/hash/key not printed. BuildIt database unchanged.')
if __name__=='__main__':
 try:main()
 except (KeyError,ValueError,OSError,EOFError):raise SystemExit('Initialization stopped. Check prerequisites privately; credentials not printed.')
