"""Disposable CI MySQL only. Never run against production."""
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('ops',Path(__file__).with_name('existing-deployment.py'))
ops=importlib.util.module_from_spec(spec);spec.loader.exec_module(ops)
db=('127.0.0.1','academycraft_ci','academycraft_ci','ci-only-password')
source=[dict(id=12,userId=54,role='TUTOR',login='modu',name='CI 학원 12',slug='ci-12',handle=None,memberName='CI Tutor'),dict(id=20,userId=4,role='ADMIN',login='admin',name='CI 학원 20',slug='ci-20',handle=None,memberName='CI Admin')]
first=ops.bootstrap_members(source,db)
second=ops.bootstrap_members(source,db)
assert first==second
assert ops.client(db,'SELECT COUNT(*) FROM rea_academies')==['2']
assert ops.client(db,'SELECT COUNT(*) FROM rea_members')==['3']
assert ops.client(db,'SELECT COUNT(*) FROM rea_members WHERE login_id IS NOT NULL OR password_hash IS NOT NULL')==['0']
assert {(b['userId'],b['builditAcademyId']) for b in first}=={(54,12),(4,12),(4,20)}
source[0]['name']='unexpected changed source'
try: ops.bootstrap_members(source,db)
except ValueError: pass
else: raise AssertionError('Changed identity accepted')
assert ops.client(db,'SELECT COUNT(*) FROM rea_academies')==['2']
print('PASS: first setup, retry without duplicate, no credentials, changed identity rejected')
