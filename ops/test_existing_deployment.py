import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('existing',Path(__file__).with_name('existing-deployment.py'))
ops=importlib.util.module_from_spec(spec);spec.loader.exec_module(ops)

class Guards(unittest.TestCase):
    def test_given_flyway_history_when_checked_then_v4_to_v6_allowed_and_others_rejected(self):
        # given / when / then
        self.assertIn(['1','2','3','4','5','6'], ops.KNOWN_SCHEMA_VERSIONS)
        self.assertIn(['1','2','3','4','5'], ops.KNOWN_SCHEMA_VERSIONS)
        self.assertNotIn(['1','2','3','4','5','6','7'], ops.KNOWN_SCHEMA_VERSIONS)
        self.assertNotIn(['1','2','3'], ops.KNOWN_SCHEMA_VERSIONS)

    def binding(self,role='TUTOR',user=54,academy=7,member=9):
        return dict(userId=user,role=role,builditAcademyId=12,academyId=academy,memberId=member)

    def test_existing_environment_values_round_trip_without_losing_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'env'
            path.write_text("DB_PASSWORD='keep $this & secret'\nJWT_SECRET=unchanged\n")
            ops.write_env(path,{'ACADEMYCRAFT_BUILDIT_BINDINGS':'[{"userId":54}]','ACADEMYCRAFT_GATEWAY_SECRET':'random-key'})
            value=ops.env(path)
            self.assertEqual(value['DB_PASSWORD'],'keep $this & secret')
            self.assertEqual(value['JWT_SECRET'],'unchanged')
            self.assertEqual(value['ACADEMYCRAFT_BUILDIT_BINDINGS'],'[{"userId":54}]')
            self.assertEqual(path.stat().st_mode & 0o777,0o600)

    def test_tutor_cannot_map_an_academy_they_do_not_own(self):
        def query(db,sql):
            if 'SELECT role FROM users' in sql:return ['TUTOR']
            if 'tutor_user_id' in sql:return ['0']
            return ['1']
        with patch.object(ops,'client',side_effect=query):
            with self.assertRaisesRegex(ValueError,'does not own'):
                ops.validate_bindings([self.binding()],None,None)

    def test_stale_role_cannot_become_admin(self):
        with patch.object(ops,'client',return_value=['TUTOR']):
            with self.assertRaisesRegex(ValueError,'role mismatch'):
                ops.validate_bindings([self.binding(role='ADMIN')],None,None)

    def test_unknown_fields_and_student_access_rejected_before_query(self):
        b=self.binding();b['password']='must not be accepted'
        with patch.object(ops,'client') as query:
            with self.assertRaises(ValueError):ops.validate_bindings([b],None,None)
            with self.assertRaises(ValueError):ops.validate_bindings([self.binding(role='STUDENT')],None,None)
            query.assert_not_called()

    def test_both_existing_roles_validate_with_explicit_staff_memberships(self):
        def query(db,sql):
            if 'FROM users' in sql:return ['ADMIN' if 'user_id=4' in sql else 'TUTOR']
            if 'FROM rea_members' in sql:return ['ACADEMY_OWNER']
            return ['1']
        with patch.object(ops,'client',side_effect=query):
            ops.validate_bindings([self.binding(),self.binding('ADMIN',4,7,10)],None,None)
            with self.assertRaisesRegex(ValueError,'Ambiguous'):
                ops.validate_bindings([self.binding(),self.binding('ADMIN',4,7,9)],None,None)

class BootstrapGuards(unittest.TestCase):
    def test_source_ownership_mismatch_stops_before_any_insert(self):
        import json
        rows=[dict(id=12,userId=54,role='TUTOR',login='modu',name='A',slug='a',memberName='M'),dict(id=20,userId=99,role='ADMIN',login='admin',name='B',slug='b',memberName='N')]
        with patch.object(ops,'client',return_value=[json.dumps(r) for r in rows]) as query:
            with self.assertRaisesRegex(ValueError,'identity mismatch'):ops.approved_source(None)
            self.assertEqual(query.call_count,1)

    def test_existing_source_mismatch_never_adopts_or_updates(self):
        source=[dict(id=12,userId=54,role='TUTOR',name='A',slug='a',memberName='M'),dict(id=20,userId=4,role='ADMIN',name='B',slug='b',memberName='N')]
        with patch.object(ops,'client',return_value=['different-fingerprint']) as query:
            with self.assertRaisesRegex(ValueError,'identity mismatch'):ops.bootstrap_members(source,None)
            self.assertTrue(all('SELECT' in c.args[1] for c in query.call_args_list))

    def test_sql_text_is_hex_encoded_not_interpolated(self):
        value="학원'); DELETE FROM users; --"
        quoted=ops.sqltext(value)
        self.assertNotIn('DELETE',quoted)
        self.assertIn(value.encode().hex(),quoted)

if __name__=='__main__':unittest.main()
