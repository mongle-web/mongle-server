"""Exercise actual file/link/state changes with a simulated S3/systemd/health boundary."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1] / 'cd'
spec = importlib.util.spec_from_file_location('cd_deploy', SCRIPTS / 'deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


def jar(path, payload):
    with zipfile.ZipFile(path, 'w') as output:
        output.writestr('META-INF/MANIFEST.MF', 'Main-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: com.mongle.App\n')
        output.writestr('BOOT-INF/classes/App.class', payload)


class FakeRuntime:
    def __init__(self, source):
        self.source, self.restarts = source, 0
        self.health = [True, True]
        self.interrupt = False

    def download(self, _key, destination):
        shutil.copyfile(self.source, destination)

    def restart(self):
        self.restarts += 1
        if self.interrupt:
            self.interrupt = False
            raise InterruptedError('simulated interrupted restart')

    def healthy(self, timeout=150):
        return self.health.pop(0)


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.app, self.state = self.root / 'app', self.root / 'state'
        (self.app / 'releases').mkdir(parents=True)
        self.state.mkdir()
        self.previous = self.app / 'releases/mongle-f506c173.jar'
        jar(self.previous, 'old')
        (self.app / 'current.jar').symlink_to(self.previous)
        self.source = self.root / 'source.jar'
        jar(self.source, 'new')
        self.runtime = FakeRuntime(self.source)
        self.engine = deploy.Deployer(self.app, self.state, {'prefix': 'releases/'}, self.runtime)
        self.values = {'CommitSha': 'a' * 40, 'JarSha256': deploy.digest(self.source),
                       'RunId': '100', 'RunNumber': '10', 'RunAttempt': '1'}
        self.sentinel = self.root / 'mongle.env'
        self.sentinel.write_text('unchanged secret sentinel')
        self.sentinel.chmod(0o640)

    def test_success_replaces_link_keeps_previous_and_records_identity(self):
        self.engine.run(self.values)
        self.assertEqual(self.engine.target().name, 'mongle-' + 'a' * 40 + '.jar')
        self.assertTrue(self.previous.exists())
        record = json.loads(self.engine.state_file.read_text())
        self.assertEqual(record['highestSequence'], [10, 1])
        self.assertNotIn('pending', record)
        self.assertEqual(self.runtime.restarts, 1)
        self.assertEqual(self.sentinel.read_text(), 'unchanged secret sentinel')
        self.assertEqual(self.sentinel.stat().st_mode & 0o777, 0o640)

    def test_failed_health_rolls_back_but_reports_failure(self):
        self.runtime.health = [True, False, True]
        with self.assertRaises(RuntimeError):
            self.engine.run(self.values)
        self.assertEqual(self.engine.target(), self.previous)
        self.assertEqual(self.runtime.restarts, 2)
        self.assertNotIn('pending', json.loads(self.engine.state_file.read_text()))

    def test_restart_interruption_recovers_before_exiting(self):
        self.runtime.interrupt = True
        with self.assertRaises(InterruptedError):
            self.engine.run(self.values)
        self.assertEqual(self.engine.target(), self.previous)
        self.assertEqual(self.runtime.restarts, 2)

    def test_rollback_failure_preserves_pending_for_next_command(self):
        self.runtime.health = [True, False, False]
        with self.assertRaises(RuntimeError):
            self.engine.run(self.values)
        self.assertIn('pending', json.loads(self.engine.state_file.read_text()))
        self.runtime.health = [True, True, True]
        self.engine.run(self.values)
        self.assertNotIn('pending', json.loads(self.engine.state_file.read_text()))

    def test_recovers_persisted_interruption_before_new_deployment(self):
        target = self.app / 'releases' / ('mongle-' + 'b' * 40 + '.jar')
        jar(target, 'interrupted')
        self.engine.switch(target)
        self.engine.save({'highestSequence': [9, 1], 'lastCommit': 'b' * 40,
                          'pending': {'previous': str(self.previous), 'candidate': str(target)}})
        self.runtime.health = [True, True, True]
        self.engine.run(self.values)
        self.assertEqual(self.runtime.restarts, 2)

    def test_checksum_failure_does_not_restart_or_replace_current(self):
        self.values['JarSha256'] = '0' * 64
        with self.assertRaises(ValueError):
            self.engine.run(self.values)
        self.assertEqual(self.engine.target(), self.previous)
        self.assertEqual(self.runtime.restarts, 0)

    def test_plain_jar_is_rejected_before_restart(self):
        self.source.write_text('not an executable jar')
        self.values['JarSha256'] = deploy.digest(self.source)
        with self.assertRaises(zipfile.BadZipFile):
            self.engine.run(self.values)
        self.assertEqual(self.runtime.restarts, 0)

    def test_existing_release_cannot_be_overwritten(self):
        target = self.app / 'releases' / ('mongle-' + 'a' * 40 + '.jar')
        target.write_text('different bytes')
        with self.assertRaises(ValueError):
            self.engine.run(self.values)
        self.assertEqual(target.read_text(), 'different bytes')
        self.assertEqual(self.runtime.restarts, 0)

    def test_older_run_and_attempt_are_refused_even_after_failed_newer_run(self):
        self.engine.save({'highestSequence': [11, 2], 'lastCommit': 'b' * 40})
        for number, attempt in [('10', '5'), ('11', '1')]:
            with self.subTest(number=number, attempt=attempt):
                with self.assertRaises(ValueError):
                    self.engine.run({**self.values, 'RunNumber': number, 'RunAttempt': attempt})
        self.assertEqual(self.runtime.restarts, 0)

    def test_same_run_cannot_change_commit(self):
        self.engine.save({'highestSequence': [10, 1], 'lastCommit': 'b' * 40})
        with self.assertRaises(ValueError):
            self.engine.run(self.values)

    def test_successful_retry_is_idempotent_and_does_not_restart(self):
        self.engine.run(self.values)
        self.runtime.health = [True]
        self.engine.run(self.values)
        self.assertEqual(self.runtime.restarts, 1)

    def test_symlink_and_external_rollback_targets_are_rejected(self):
        outside = self.root / 'mongle-abcdef0.jar'
        jar(outside, 'external')
        with self.assertRaises(ValueError):
            self.engine.validate_target(outside)
        link = self.app / 'releases/mongle-abcdef0.jar'
        link.symlink_to(outside)
        with self.assertRaises(ValueError):
            self.engine.validate_target(link)

    def test_already_unhealthy_previous_is_not_restarted(self):
        self.runtime.health = [False]
        with self.assertRaises(RuntimeError):
            self.engine.run(self.values)
        self.assertEqual(self.runtime.restarts, 0)

    def test_parameter_injection_is_refused(self):
        environment = {'SSM_' + k: v for k, v in self.values.items()}
        self.assertEqual(deploy.parameters(environment), self.values)
        for key, value in [('CommitSha', 'a' * 40 + ';id'), ('RunId', '1\n2'), ('RunAttempt', '-1')]:
            with self.assertRaises(ValueError):
                deploy.parameters({**environment, 'SSM_' + key: value})


class HealthTest(unittest.TestCase):
    class Response:
        status = 200

        def __init__(self, url, state):
            self.url, self.state = url, state

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            pass

        def geturl(self):
            return self.url

        def read(self, _size):
            return json.dumps({'status': self.state}).encode()

    def test_both_internal_and_public_health_are_required(self):
        public = 'https://api.mongle.art/actuator/health'
        for state in ('UP', 'DOWN'):
            with self.subTest(public_state=state), patch.object(deploy.subprocess, 'run', return_value=SimpleNamespace(returncode=0)), patch.object(deploy.urllib.request, 'build_opener') as factory:
                opener = factory.return_value
                opener.open.side_effect = lambda url, timeout: self.Response(url, state if url == public else 'UP')
                with patch.object(deploy.time, 'monotonic', side_effect=[0, 0, 0, 0, 2, 2]), patch.object(deploy.time, 'sleep'):
                    self.assertEqual(deploy.Runtime({'publicHealth': public}).healthy(timeout=1), state == 'UP')
                self.assertEqual(opener.open.call_count, 2)


if __name__ == '__main__':
    unittest.main()
