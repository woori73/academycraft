"""Targeted regression checks; no AWS, DB or credentials used."""
import importlib.util,io,os,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
HERE=Path(__file__).parent

def load(name):
 spec=importlib.util.spec_from_file_location(name,HERE/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

class BackupTests(unittest.TestCase):
 def scenario(self,fail):
  repair=load('repair-backup')
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'etc/academycraft').mkdir(parents=True);(root/'etc/cron.daily').mkdir();(root/'bin').mkdir();(root/'backup').mkdir()
   (root/'etc/academycraft/env').write_text('DB_PASSWORD=private\nDB_USERNAME=academycraft\n')
   for name,body in [('mysqldump','exit 9' if fail else 'echo "-- Dump completed on test"'),('aws','touch "'+str(root/'uploaded')+'"')]:
    p=root/'bin'/name;p.write_text('#!/bin/bash\n'+body+'\n');p.chmod(0o700)
   script=root/'etc/cron.daily/academycraft-backup'
   script.write_text('''#!/bin/bash
set -euo pipefail
DATE=$(date +%F)
BACKUP_DIR="'''+str(root/'backup')+'''"
S3_BUCKET="s3://f2r-deploy-425805163960/backups/academycraft/"
set -a; . "'''+str(root/'etc/academycraft/env')+'''"; set +a
MYSQL_PWD="$DB_PASSWORD" mysqldump -h db -u "$DB_USERNAME" \\
 --single-transaction --routines academycraft \\
 | gzip > "$BACKUP_DIR/academycraft-$DATE.sql.gz"
unset MYSQL_PWD
gzip -t "$BACKUP_DIR/academycraft-$DATE.sql.gz"
aws s3 cp "$BACKUP_DIR/academycraft-$DATE.sql.gz" "$S3_BUCKET/" || exit 1
''');script.chmod(0o700)
   original=script.read_text();good=root/'backup/academycraft-previous.sql.gz';good.write_bytes(b'old-good')
   def mapped(p):return root/str(p).lstrip('/') if str(p).startswith('/etc/') else Path(p)
   with patch.object(repair,'Path',side_effect=mapped),patch.object(repair.os,'geteuid',return_value=0),patch.dict(os.environ,{'PATH':str(root/'bin')+os.pathsep+os.environ['PATH']}),patch('sys.stdout',io.StringIO()):
    if fail:
     with self.assertRaises(SystemExit):repair.main()
    else:repair.main()
   self.assertIn('--no-tablespaces',script.read_text());self.assertEqual(good.read_bytes(),b'old-good')
   self.assertEqual((root/'uploaded').exists(),not fail)
   self.assertEqual(len(list((root/'backup').glob('*.sql.gz'))),1 if fail else 2)
   before=list((root/'etc/academycraft').glob('academycraft-backup.before-*'));self.assertEqual(before[0].read_text(),original)
   self.assertEqual(script.stat().st_mode&0o777,0o700)
 def test_dump_failure_blocks_upload_and_removes_partial(self):self.scenario(True)
 def test_dump_success_preserves_prior_and_uploads(self):self.scenario(False)

class OwnerTests(unittest.TestCase):
 def scenario(self,mode):
  owner=load('initialize-owner');queries=[];backup=[]
  label='압구정에듀홀릭학원'.encode().hex()
  def sql(path,db,q):
   queries.append((db,q))
   if db!='academycraft':
    self.assertTrue(q.startswith('SELECT '))
    if 'FROM easy_businesses' in q:return '7\t'+label+'\t1\n8\t'+label+'\t1' if mode in ('ambiguous','inspect') else ''
    return ''
   if 'START TRANSACTION' in q:return '1\t1'
   if 'COUNT(*) FROM rea_academies' in q:return '1\t0' if mode=='existing' else '0\t0'
   return '0'
  def run(*args,**kw):backup.append(True);return subprocess.CompletedProcess(args,1)
  env=lambda p:{'DB_HOST':'db','DB_PASSWORD':'private','DB_USER':'admin'} if p=='/etc/f2r/env' else {'DB_URL':'jdbc:mysql://db/academycraft','DB_USERNAME':'academycraft','DB_PASSWORD':'private'}
  tty=type('TTY',(io.StringIO,),{'isatty':lambda s:True})()
  with patch.object(owner,'env_file',env),patch.object(owner,'cnf'),patch.object(owner,'sql',sql),patch.object(owner.os,'geteuid',return_value=0),patch.object(owner.Path,'is_file',return_value=True),patch.object(owner.Path,'exists',return_value=False),patch.object(owner.sys,'stdin',tty),patch.object(owner.sys,'stdout',tty),patch.object(owner.sys,'argv',['init','--inspect'] if mode=='inspect' else ['init']),patch.object(owner.subprocess,'run',side_effect=run),patch('builtins.input',side_effect=AssertionError('Must not ask password/account fields')):
   if mode=='inspect':owner.main()
   else:
    with self.assertRaises(SystemExit):owner.main()
  self.assertFalse(any('INSERT INTO' in q for _,q in queries))
  self.assertEqual(bool(backup),mode=='backup-fail')
  if mode=='inspect':self.assertIn('Read-only AcademyCraft counts',tty.getvalue())
 def test_inspection_shows_candidates_without_writing(self):self.scenario('inspect')
 def test_ambiguity_blocks_creation(self):self.scenario('ambiguous')
 def test_existing_data_blocks_creation(self):self.scenario('existing')
 def test_backup_failure_blocks_before_password(self):self.scenario('backup-fail')
if __name__=='__main__':unittest.main()
