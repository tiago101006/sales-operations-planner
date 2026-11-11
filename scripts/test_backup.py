"""Verifica rejeição de arquivos inválidos e recuperação/limpeza após falhas."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import backup


class BackupTests(unittest.TestCase):
    def test_inventories_original_bytes_and_rejects_traversal_links_and_duplicate_entries(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / 'imports.tar.gz'
            data = 'produto;quantidade\nSKU001;12\n'.encode()
            with tarfile.open(path, 'w:gz') as archive:
                member = tarfile.TarInfo('./original.csv');member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            self.assertEqual(backup.archive_inventory(path), {'original.csv': {'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)}})
            for name, kind in [('../fora', tarfile.REGTYPE), ('/absoluto', tarfile.REGTYPE),
                               ('link', tarfile.SYMTYPE), ('hardlink', tarfile.LNKTYPE), ('fifo', tarfile.FIFOTYPE)]:
                with self.subTest(name=name):
                    with tarfile.open(path, 'w:gz') as archive:
                        member = tarfile.TarInfo(name);member.type = kind;member.linkname = '../fora'
                        archive.addfile(member)
                    with self.assertRaises(ValueError):
                        backup.archive_inventory(path)
            with tarfile.open(path, 'w:gz') as archive:
                archive.addfile(tarfile.TarInfo('a'));archive.addfile(tarfile.TarInfo('./a'))
            with self.assertRaises(ValueError):
                backup.archive_inventory(path)

    def test_rejects_corrupted_payload_before_starting_restore_resources(self):
        with tempfile.TemporaryDirectory() as work:
            folder = Path(work)
            (folder / 'database.sql.gz').write_bytes(b'alterado')
            manifest = {'format': 'nexoplan-backup-v1', 'database': 'sop', 'backendImage': 'sha256:' + 'a'*64,
                        'mysqlImage': 'sha256:' + 'b'*64, 'payloads': {'database.sql.gz': 'c'*64}}
            (folder / 'manifest.json').write_text(json.dumps(manifest))
            with patch.object(backup, 'run', return_value=b'') as run:
                with self.assertRaisesRegex(ValueError, 'Integridade'):
                    backup.verify_backup(folder)
                self.assertTrue(all(call.args[0][:3] == ['docker', 'image', 'inspect'] for call in run.call_args_list))

    def test_restarts_backend_when_capture_fails_and_never_publishes_partial_backup(self):
        def command(args, **kwargs):
            if '{{json .Mounts}}' in args:
                return json.dumps([{'Destination': '/app/imports', 'Type': 'volume', 'Name': 'source-csv'}]).encode()
            return b'true' if '{{.State.Running}}' in args else b''
        with tempfile.TemporaryDirectory() as work, patch.object(backup, 'ROOT', Path(work)):
            with patch.object(backup, 'compose_container', side_effect=lambda x: x), patch.object(backup, 'image_of', return_value='image'), \
                 patch.object(backup, 'sql', return_value='sop'), patch.object(backup, 'run', side_effect=command) as run, \
                 patch.object(backup, 'database_inventory', side_effect=RuntimeError('simulação')):
                destination = Path(work) / 'backups'
                with self.assertRaisesRegex(RuntimeError, 'simulação'):
                    backup.create_backup(destination)
                commands = [call.args[0] for call in run.call_args_list]
                self.assertIn(['docker', 'compose', 'stop', 'backend'], commands)
                self.assertIn(['docker', 'compose', 'start', '--wait', 'backend'], commands)
                self.assertEqual(list(destination.iterdir()), [])

    def test_cleans_only_resources_created_by_failed_isolated_restore(self):
        manifest = {'database': 'sop', 'mysqlImage': 'image'}
        with patch.object(backup, 'validate_backup', return_value=manifest), patch.object(backup, 'run', return_value=b''), \
             patch.object(backup, 'wait_database', side_effect=RuntimeError('simulação')), patch.object(backup.subprocess, 'run') as cleanup:
            with self.assertRaisesRegex(RuntimeError, 'simulação'):
                backup.verify_backup(Path('/unused'))
            commands = [call.args[0] for call in cleanup.call_args_list]
            self.assertEqual(len(commands), 3)
            self.assertTrue(all(cmd[-1].startswith('nexoplan-restore-') for cmd in commands))
            self.assertFalse(any('compose' in cmd for cmd in commands))


if __name__ == '__main__':
    unittest.main()
