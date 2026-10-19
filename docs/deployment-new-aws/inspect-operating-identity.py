#!/usr/bin/env python3
"""Private read-only evidence for BuildIt 11/12; no passwords or student rows."""
import importlib.util,os,re,sys,tempfile
from pathlib import Path

def helpers():
 spec=importlib.util.spec_from_file_location('owner_helpers',Path(__file__).with_name('initialize-owner.py'))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def inspect(h,admin_cnf,app_cnf,db):
 def read(cnf,schema,query):
  h.require(query.startswith('SELECT '),'Only SELECT is allowed')
  return h.sql(cnf,schema,'START TRANSACTION READ ONLY;\n'+query+'\nROLLBACK;')
 def report(title,query):
  print(title,flush=True);print(read(admin_cnf,db,query) or '(none)',flush=True)
 report('ACCOUNTS: user_id, login_id, role (no password/contact)',
        "SELECT user_id,login_id,role FROM users WHERE role='ADMIN' OR login_id='modu' ORDER BY user_id;")
 report('ACADEMIES: academy_id, name, slug, handle, status, owner_user_id, owner_login, owner_role',
        "SELECT a.academy_id,a.name,a.slug,a.handle,a.status,a.tutor_user_id,u.login_id,u.role FROM academies a LEFT JOIN users u ON u.user_id=a.tutor_user_id WHERE a.academy_id IN (11,12) OR u.role='ADMIN' OR u.login_id='modu' ORDER BY a.academy_id;")
 report('BUSINESSES: business_id, code, name, active (academy AND publisher retained)',
        'SELECT business_id,code,name,active FROM easy_businesses ORDER BY business_id;')
 report('LINKS: academy_id, academy_name, business_id, business_code, business_name',
        'SELECT l.academy_id,a.name,l.business_id,b.code,b.name FROM academy_easy_business_links l JOIN academies a ON a.academy_id=l.academy_id JOIN easy_businesses b ON b.business_id=l.business_id ORDER BY l.academy_id,l.business_id;')
 report('STUDENT MAP COUNTS: academy_id, tutor_user_id, student_link_count, active_link_count (no student IDs)',
        'SELECT a.academy_id,a.tutor_user_id,COUNT(m.student_id),COALESCE(SUM(m.is_active=1),0) FROM academies a LEFT JOIN tutor_student_map m ON m.tutor_user_id=a.tutor_user_id WHERE a.academy_id IN (11,12) GROUP BY a.academy_id,a.tutor_user_id ORDER BY a.academy_id;')
 # Discover actual tables rather than assuming deployed management schemas.
 tables=read(admin_cnf,db,"SELECT c.TABLE_NAME FROM information_schema.COLUMNS c JOIN information_schema.TABLES t ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME WHERE c.TABLE_SCHEMA=DATABASE() AND c.COLUMN_NAME='academy_id' AND t.TABLE_TYPE='BASE TABLE' ORDER BY c.TABLE_NAME;")
 for table in tables.splitlines():
  h.require(re.fullmatch(r'[A-Za-z0-9_]+',table),'Unexpected table identifier')
  report('REFERENCE COUNTS '+table+' (academy_id, count; no student records)',
         'SELECT /*+ MAX_EXECUTION_TIME(2000) */ academy_id,COUNT(*) FROM `'+table+'` WHERE academy_id IN (11,12) GROUP BY academy_id ORDER BY academy_id;')
 print('ACADEMYCRAFT: academy_count, member_count, owner_count',flush=True)
 print(read(app_cnf,'academycraft',"SELECT (SELECT COUNT(*) FROM rea_academies),(SELECT COUNT(*) FROM rea_members),(SELECT COUNT(*) FROM rea_members WHERE role='ACADEMY_OWNER');"),flush=True)
 print('PASS: read-only inspection finished; no account/DB/permission/backup changes.',flush=True)

def main():
 h=helpers();h.require(os.geteuid()==0 and sys.stdin.isatty() and sys.stdout.isatty(),'Use sudo in a private EC2 SSM terminal')
 os.umask(0o077)
 admin=h.env_file('/etc/f2r/env');app=h.env_file('/etc/academycraft/env')
 match=re.fullmatch(r'jdbc:mysql://([^/:]+)(?::3306)?/academycraft(?:\?.*)?',app.get('DB_URL',''))
 h.require(match and match[1]==admin.get('DB_HOST') and app.get('DB_USERNAME')=='academycraft','Unexpected DB configuration; stopped')
 db=admin.get('DB_NAME','f2r_db');h.require(re.fullmatch(r'[A-Za-z0-9_]+',db) and db!='academycraft','Unexpected BuildIt DB')
 with tempfile.TemporaryDirectory(prefix='ac-identity-readonly-') as folder:
  ac=Path(folder)/'admin.cnf';own=Path(folder)/'app.cnf'
  h.cnf(ac,match[1],admin.get('DB_USER') or admin.get('DB_USERNAME',''),admin['DB_PASSWORD'])
  h.cnf(own,match[1],app['DB_USERNAME'],app['DB_PASSWORD'])
  inspect(h,ac,own,db)
if __name__=='__main__':
 try:main()
 except (KeyError,ValueError,OSError,EOFError):raise SystemExit('Inspection stopped; credentials not printed. No account created.')
