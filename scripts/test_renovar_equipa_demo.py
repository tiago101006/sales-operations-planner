"""Testes das salvaguardas do renovador de identidades fictícias, sem usar a base local."""
import copy
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import unicodedata
from unittest.mock import patch

from PIL import Image
import renovar_equipa_demo as demo


def fixture(folder):
    names = {"admin": "Leonor Matos", "admin2": "Miguel Varela", "planeador": "Inês Seabra",
             "planeador2": "Rui Falcão", "comercial": "Carolina Pires", "aprovador": "Sofia Neves",
             "aprovador2": "Tomás Brito", "consumidor": "Duarte Esteves", "consumidor2": "Mariana Lobo", "antigo": "Pedro Teles"}
    roles = {"admin": "ADMIN", "admin2": "ADMIN", "planeador": "PLANEADOR", "planeador2": "PLANEADOR",
             "comercial": "COMERCIAL", "aprovador": "APROVADOR", "aprovador2": "APROVADOR",
             "consumidor": "CONSUMIDOR", "consumidor2": "CONSUMIDOR", "antigo": "COMERCIAL"}
    people = []
    for index, key in enumerate(demo.MAPPING):
        path = folder / (key + '.jpg')
        Image.new('RGB', (256, 256), (index * 20, 100, 90)).save(path, 'JPEG')
        local = unicodedata.normalize('NFKD', names[key]).encode('ascii', 'ignore').decode().lower().replace(' ', '.')
        people.append({'key': key, 'nome': names[key], 'email': local + '@nexoplan.com',
                       'idExistente': demo.MAPPING[key], 'papeis': [roles[key]], 'ativo': key != 'antigo',
                       'foto': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    manifest = {'versao': 1, 'sintetico': True, 'dominio': 'nexoplan.com', 'pessoas': people}
    (folder / 'manifesto.json').write_text(json.dumps(manifest), encoding='utf-8')
    return manifest


def original():
    return [{'id': key, 'nome': 'Demo ' + str(key), 'email': demo.ORIGINAL_EMAILS[key],
             'papeis': role, 'ativo': key != 6, 'password_definida': key != 7,
             'password_hash': 'not-a-real-hash', 'versao': 3, 'criado_em': '2026-11-01 10:00:00.000000',
             'foto_sha': None} for key, role in demo.ORIGINAL_ROLES.items()]


class TeamRenewalTests(unittest.TestCase):
    def test_accepts_only_exact_original_identities_and_catches_extra_users(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work);fixture(folder)
            _, people = demo.load_manifest(folder)
            self.assertEqual(demo.classify_users(original(), people), 'original')
            for field, value in [('email', 'other@example.test'), ('papeis', ['CONSUMIDOR']), ('ativo', False)]:
                changed = original();changed[0][field] = value
                with self.subTest(field=field), self.assertRaises(ValueError):
                    demo.classify_users(changed, people)
            changed = original();changed.append(dict(changed[0], id=88))
            with self.assertRaises(ValueError): demo.classify_users(changed, people)

    def test_manifest_rejects_corrupt_image_outside_path_duplicates_and_role_remapping(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work);base = fixture(folder)
            variants = []
            wrong = copy.deepcopy(base);wrong['pessoas'][0]['foto'] = '../outside.jpg';variants.append(wrong)
            wrong = copy.deepcopy(base);wrong['pessoas'][0]['sha256'] = '0' * 64;variants.append(wrong)
            wrong = copy.deepcopy(base);wrong['pessoas'][0]['email'] = wrong['pessoas'][1]['email'];variants.append(wrong)
            wrong = copy.deepcopy(base);wrong['pessoas'][0]['papeis'] = ['CONSUMIDOR'];variants.append(wrong)
            wrong = copy.deepcopy(base);wrong['pessoas'][0]['idExistente'] = 42;variants.append(wrong)
            for variant in variants:
                (folder / 'manifesto.json').write_text(json.dumps(variant))
                with self.assertRaises(ValueError): demo.load_manifest(folder)

    def test_repeat_does_not_reset_password_and_rejects_changed_converted_team(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work);fixture(folder)
            _, people = demo.load_manifest(folder)
            users = [dict(id=p['idExistente'] or i + 10, nome=p['nome'], email=p['email'], papeis=p['papeis'],
                          ativo=p['ativo'], password_definida=True, foto_sha=p['sha256']) for i, p in enumerate(people)]
            self.assertEqual(demo.classify_users(users, people), 'renovada')
            users[0]['foto_sha'] = 'changed'
            with self.assertRaises(ValueError): demo.classify_users(users, people)

    def test_private_files_and_sql_do_not_contain_clear_password(self):
        import bcrypt
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work);fixture(folder)
            _, people = demo.load_manifest(folder)
            accounts = demo.create_passwords(people)
            self.assertEqual(len({person['password'] for person in accounts}), 10)
            for person in accounts:
                self.assertGreaterEqual(len(person['password']), 12)
                self.assertTrue(bcrypt.checkpw(person['password'].encode(), person['password_hash'].encode()))
                self.assertEqual(person['password_hash'][4:6], '12')
            demo.private_access(folder, accounts)
            for name in ['acessos.md', 'contas.json']:
                self.assertEqual((folder / name).stat().st_mode & 0o777, 0o600)
            sql = demo.renewal_sql(original(), accounts)
            self.assertNotIn('FOREIGN_KEY_CHECKS', sql)
            self.assertNotIn('DELETE FROM utilizador', sql)
            self.assertIn('revogado_em=UTC_TIMESTAMP', sql)
            for account in accounts:
                self.assertNotIn(account['password'], sql)

    def test_uncertain_commit_restores_backup_and_failed_recovery_keeps_backend_stopped(self):
        for recovery_fails in [False, True]:
            with self.subTest(recovery_fails=recovery_fails), tempfile.TemporaryDirectory() as work:
                root = Path(work);folder = root / 'assets';folder.mkdir();fixture(folder)
                _, people = demo.load_manifest(folder)
                accounts = [dict(person, password='LocalDemo123!', password_hash='not-a-real-hash') for person in people]
                with patch.object(demo, 'ROOT', root), patch('sys.argv', ['renovar', str(folder), '--aplicar']), \
                     patch.object(demo.backup, 'compose_container', side_effect=lambda service: service), \
                     patch.object(demo.preparar_empresa, 'local_target', return_value=({'Image': 'image'}, {}, 'http://127.0.0.1:9999', 'volume')), \
                     patch.object(demo, 'read_users', side_effect=[original(), original()]), \
                     patch.object(demo, 'create_passwords', return_value=accounts), \
                     patch.object(demo, 'business_fingerprint', return_value='sentinela'), \
                     patch.object(demo.preparar_empresa, 'snapshot'), \
                     patch.object(demo.backup, 'run', return_value=b'') as run, \
                     patch.object(demo.backup, 'sql', side_effect=RuntimeError('Resultado SQL desconhecido')), \
                     patch.object(demo.preparar_empresa, 'restore_snapshot', side_effect=RuntimeError('Falha na recuperação') if recovery_fails else None) as restore, \
                     patch.object(demo.preparar_empresa, 'wait_web'):
                    with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError): demo.main()
                    restore.assert_called_once()
                    commands = [call.args[0] for call in run.call_args_list]
                    self.assertIn(['docker', 'stop', 'backend'], commands)
                    if recovery_fails: self.assertNotIn(['docker', 'start', 'backend'], commands)
                    else: self.assertIn(['docker', 'start', 'backend'], commands)


if __name__ == '__main__':
    unittest.main()
