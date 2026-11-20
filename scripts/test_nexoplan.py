"""Launcher safeguards; no Docker calls or changes to the real .env."""
import importlib.machinery
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


loader = importlib.machinery.SourceFileLoader('nexoplan_launcher', str(Path(__file__).resolve().parents[1] / 'nexoplan'))
spec = importlib.util.spec_from_loader(loader.name, loader)
launcher = importlib.util.module_from_spec(spec)
loader.exec_module(launcher)


class LocalAiSetupTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = self.root / '.env'
        self.original = 'MYSQL_DATABASE=existing\nMYSQL_PASSWORD=test-only\nSOP_AI_ENABLED=false\n'
        self.env.write_text(self.original)
        self.env.chmod(0o600)
        root_patch = patch.object(launcher, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)

    @patch.object(launcher, 'start')
    @patch.object(launcher, 'ai_configuration', return_value=(False, 'qwen3.5:4b-q4_K_M'))
    @patch.object(launcher, 'compose')
    def test_failed_download_preserves_private_configuration(self, compose, config, start):
        compose.side_effect = [None, subprocess.CalledProcessError(1, ['ollama', 'pull'])]
        with self.assertRaises(subprocess.CalledProcessError):
            launcher.prepare_ai()
        self.assertEqual(self.env.read_text(), self.original)
        start.assert_not_called()

    @patch.object(launcher, 'start')
    @patch.object(launcher, 'ai_configuration', return_value=(False, 'qwen3.5:4b-q4_K_M'))
    @patch.object(launcher, 'compose')
    def test_success_enables_only_ai_and_preserves_file_permissions(self, compose, config, start):
        launcher.prepare_ai()
        launcher.prepare_ai()
        text = self.env.read_text()
        self.assertEqual(text, self.original.replace('SOP_AI_ENABLED=false', 'SOP_AI_ENABLED=true'))
        self.assertEqual(text.count('SOP_AI_ENABLED='), 1)
        self.assertEqual(self.env.stat().st_mode & 0o777, 0o600)
        compose.assert_any_call('exec', '-T', 'ollama', 'ollama', 'pull', 'qwen3.5:4b-q4_K_M')
        start.assert_called_with(False)

    @patch.object(launcher, 'compose')
    def test_setup_requires_existing_private_configuration(self, compose):
        self.env.unlink()
        with self.assertRaises(RuntimeError):
            launcher.prepare_ai()
        compose.assert_not_called()


if __name__ == '__main__':
    unittest.main()
