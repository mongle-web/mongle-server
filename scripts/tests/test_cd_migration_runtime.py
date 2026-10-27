"""Exercise the credential boundary and real protected-JAR staging, with systemd simulated."""
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('migration_deploy', Path(__file__).resolve().parents[1] / 'cd/deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class MigrationRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.cache = self.root / 'protected'
        self.cache.mkdir()
        self.source = self.root / ('mongle-' + 'a' * 40 + '.jar')
        with zipfile.ZipFile(self.source, 'w') as output:
            output.writestr('META-INF/MANIFEST.MF', 'Main-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: com.mongle.App\n')
            output.writestr('BOOT-INF/classes/App.class', 'verified source')
        self.account = SimpleNamespace(pw_uid=1234, pw_gid=self.cache.stat().st_gid)
        self.addCleanup(patch.stopall)
        patch.object(deploy, 'MIGRATION_CACHE', self.cache).start()
        patch.object(deploy.os, 'chown').start()
        self.runtime = deploy.Runtime({})

    def test_migration_uses_rehashed_protected_copy_unprivileged_systemd_and_separate_environment(self):
        with patch.object(self.runtime, 'migration_account', return_value=self.account), patch.object(deploy.subprocess, 'run') as run:
            run.return_value = SimpleNamespace(returncode=0, stdout=json.dumps({
                'event': 'migration-completed', 'operation': 'migrate', 'migrationsExecuted': 0}))
            self.runtime.migrate(self.source, deploy.digest(self.source))
            command = run.call_args.args[0]
            self.assertIn('--property=User=mongle-migrate', command)
            self.assertIn('--property=EnvironmentFile=/etc/mongle-migration/migration.env', command)
            self.assertIn('--property=RuntimeMaxSec=240', command)
            self.assertEqual(command[-1], '--db-migration=migrate')
            settings = (Path(__file__).resolve().parents[2] /
                        'src/main/java/com/mongle/backend/migration/MigrationSettings.java').read_text()
            socket = int(re.search(r'setProperty\(\s*"socketTimeout"\s*,\s*"([0-9]+)"\s*\)', settings).group(1))
            runtime = int(next(value for value in command if value.startswith('--property=RuntimeMaxSec=')).split('=')[-1])
            self.assertGreater(socket, runtime * 1000)
            self.assertLess(socket, run.call_args.kwargs['timeout'] * 1000)
            self.assertIn(str(self.cache / self.source.name), command)
            self.assertNotIn(str(self.source), command)
            self.assertEqual(deploy.digest(self.cache / self.source.name), deploy.digest(self.source))
            self.assertEqual((self.cache / self.source.name).stat().st_mode & 0o777, 0o640)

    def test_changed_jar_is_rejected_before_systemd_can_receive_migration_secrets(self):
        with patch.object(self.runtime, 'migration_account', return_value=self.account), patch.object(deploy.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'checksum'):
                self.runtime.migrate(self.source, '0' * 64)
            run.assert_not_called()
            self.assertEqual(list(self.cache.iterdir()), [])

    def test_timeout_stops_only_fixed_unit_and_does_not_forward_child_errors(self):
        with patch.object(self.runtime, 'migration_account', return_value=self.account), patch.object(deploy.subprocess, 'run') as run:
            run.side_effect = [subprocess.TimeoutExpired(['private-value'], 270), SimpleNamespace(returncode=0)]
            with self.assertRaises(RuntimeError) as raised:
                self.runtime.migrate(self.source, deploy.digest(self.source))
            self.assertNotIn('private-value', str(raised.exception))
            self.assertEqual(run.call_args.args[0], ['/usr/bin/systemctl', 'stop', deploy.MIGRATION_UNIT])

    def test_unsuccessful_or_missing_completion_evidence_is_rejected_without_raw_logs(self):
        for code, output in [(1, 'private-database-password'), (0, 'private SQL'),
                             (0, '{"event":"migration-completed","operation":"info"}')]:
            with self.subTest(code=code, output=output), patch.object(self.runtime, 'migration_account', return_value=self.account), patch.object(deploy.subprocess, 'run') as run:
                run.side_effect = [SimpleNamespace(returncode=code, stdout=output), SimpleNamespace(returncode=0)]
                with self.assertRaises(RuntimeError) as raised:
                    self.runtime.migrate(self.source, deploy.digest(self.source))
                self.assertNotIn('private', str(raised.exception))

    def test_unprotected_migration_directories_are_rejected(self):
        self.cache.chmod(0o777)
        secret = self.root / 'migration.env'
        secret.write_text('not read by deployer')
        with patch.object(deploy, 'MIGRATION_ENV', secret), patch.object(deploy.pwd, 'getpwnam', return_value=self.account):
            with self.assertRaises(ValueError):
                self.runtime.migration_account()


if __name__ == '__main__':
    unittest.main()
