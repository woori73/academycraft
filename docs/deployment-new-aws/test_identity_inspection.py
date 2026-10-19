import contextlib,importlib.util,io,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('diag',Path(__file__).with_name('inspect-operating-identity.py'));d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
class InspectionTests(unittest.TestCase):
 def test_all_queries_readonly_and_no_secret_columns(self):
  queries=[]
  class H:
   @staticmethod
   def require(ok,msg):
    if not ok:raise SystemExit(msg)
   @staticmethod
   def sql(cnf,db,q):
    assert q.startswith('START TRANSACTION READ ONLY;\nSELECT ') and q.endswith('\nROLLBACK;')
    assert not any(w in q.upper() for w in ('INSERT ','UPDATE ','DELETE ','GRANT ','PASSWORD','AUTH_KEY'))
    queries.append(q)
    return 'academies\nacademy_page_versions' if 'information_schema.COLUMNS' in q else ''
  with contextlib.redirect_stdout(io.StringIO()):d.inspect(H,'admin','app','f2r_db')
  self.assertEqual(len(queries),9)
  self.assertTrue(any("login_id='modu'" in q for q in queries))
  self.assertTrue(any('tutor_student_map' in q for q in queries))
  self.assertTrue(any('MAX_EXECUTION_TIME(2000)' in q for q in queries))
if __name__=='__main__':unittest.main()
