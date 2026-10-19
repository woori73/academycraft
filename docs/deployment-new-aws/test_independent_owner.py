import contextlib,importlib.util,io,subprocess,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('owner',Path(__file__).with_name('initialize-owner.py'));a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
class IndependentTests(unittest.TestCase):
 def scenario(self,match):
  backups=[];queries=[];label='압구정에듀홀릭학원'.encode().hex()
  def sql(path,db,q):
   queries.append((db,q))
   if db!='academycraft':
    assert q.startswith('SELECT ')
    if 'SELECT academy_id,HEX(name),status' in q:return '11\t'+label+'\tPUBLISHED\n12\t'+label+'\tPUBLISHED'
    if 'SELECT business_id,HEX(name),active' in q:return '1\t'+label+'\t1'
    if 'COUNT(*) FROM academies a JOIN users' in q:return '1' if match else '0'
    if 'COUNT(*) FROM easy_businesses' in q:return '1'
    return ''
   if 'COUNT(*) FROM rea_academies' in q:return '0\t0'
   return '0'
  def run(*args,**kwargs):backups.append(True);return subprocess.CompletedProcess(args,1)
  env=lambda p:{'DB_HOST':'db','DB_PASSWORD':'private','DB_USER':'admin'} if p=='/etc/f2r/env' else {'DB_URL':'jdbc:mysql://db/academycraft','DB_USERNAME':'academycraft','DB_PASSWORD':'private'}
  tty=type('TTY',(io.StringIO,),{'isatty':lambda self:True})()
  with patch.object(a,'env_file',env),patch.object(a,'cnf'),patch.object(a,'sql',sql),patch.object(a.os,'geteuid',return_value=0),patch.object(a.Path,'is_file',return_value=True),patch.object(a.Path,'exists',return_value=False),patch.object(a.sys,'stdin',tty),patch.object(a.sys,'stdout',tty),patch.object(a.sys,'argv',['init','--independent-eduholic']),patch.object(a.subprocess,'run',side_effect=run),patch('builtins.input',side_effect=AssertionError('No account prompt on failed backup')):
   with self.assertRaises(SystemExit):a.main()
  self.assertEqual(bool(backups),match)
  self.assertFalse(any('DELETE ' in q or 'UPDATE ' in q or 'INSERT ' in q for _,q in queries))
 def test_test_academy_retained_and_backup_still_gates(self):self.scenario(True)
 def test_wrong_production_owner_blocks(self):self.scenario(False)
if __name__=='__main__':unittest.main()
