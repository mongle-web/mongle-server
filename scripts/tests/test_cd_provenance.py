import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'cd'
sys.path.insert(0, str(SCRIPTS))
import verify_ci
import dispatch
import render_infra


class FakeGitHub:
    def __init__(self):
        self.run = {'id': 100, 'event': 'push', 'head_branch': 'develop', 'status': 'completed',
                    'conclusion': 'success', 'head_repository': {'full_name': verify_ci.REPOSITORY},
                    'workflow_id': 20, 'head_sha': 'a' * 40, 'path': verify_ci.WORKFLOW,
                    'run_attempt': 2, 'run_number': 10}
        self.event = {'workflow_run': copy.deepcopy(self.run)}
        self.latest = 'a' * 40
        self.jobs = [{'name': 'Test and build', 'status': 'completed', 'conclusion': 'success'}]
        self.artifacts = [{'id': 200, 'name': 'server-jar-' + 'a' * 40 + '-100-2', 'expired': False,
                           'workflow_run': {'id': 100, 'head_sha': 'a' * 40}}]

    def get(self, path):
        return {'actions/runs/100': self.run, 'actions/workflows/server-ci.yml': {'id': 20},
                'git/ref/heads/develop': {'object': {'sha': self.latest}}}[path]

    def all(self, path, key):
        if key == 'jobs':
            assert path == 'actions/runs/100/attempts/2/jobs'
            return self.jobs
        return self.artifacts


ENVIRONMENT = {'GITHUB_REPOSITORY': verify_ci.REPOSITORY, 'GITHUB_EVENT_NAME': 'workflow_run',
               'GITHUB_REF': 'refs/heads/develop'}


class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeGitHub()

    def test_success_selects_exact_attempt_artifact(self):
        result = verify_ci.inspect(self.api.event, self.api, ENVIRONMENT)
        self.assertEqual(result['artifact_id'], '200')
        self.assertEqual(result['run_attempt'], '2')

    def test_pr_manual_fork_and_failure_never_deploy_even_with_artifact(self):
        for key, value in [('event', 'pull_request'), ('event', 'workflow_dispatch'),
                           ('head_branch', 'feature/76'), ('conclusion', 'failure'),
                           ('status', 'in_progress'), ('workflow_id', 99),
                           ('head_repository', {'full_name': 'attacker/fork'})]:
            with self.subTest(key=key, value=value):
                api = FakeGitHub()
                api.run[key] = value
                with self.assertRaises(ValueError):
                    verify_ci.inspect(api.event, api, ENVIRONMENT)

    def test_stale_head_attempt_and_workflow_path_are_refused(self):
        for change in ('head', 'attempt', 'path', 'job'):
            with self.subTest(change=change):
                api = FakeGitHub()
                if change == 'head':
                    api.latest = 'b' * 40
                elif change == 'attempt':
                    api.run['run_attempt'] = 3
                elif change == 'path':
                    api.run['path'] = '.github/workflows/other.yml'
                else:
                    api.jobs[0]['conclusion'] = 'skipped'
                with self.assertRaises(ValueError):
                    verify_ci.inspect(api.event, api, ENVIRONMENT)

    def test_expired_missing_duplicate_and_wrong_run_artifact_refused(self):
        for change in ('expired', 'missing', 'duplicate', 'wrong_run'):
            with self.subTest(change=change):
                api = FakeGitHub()
                if change == 'expired':
                    api.artifacts[0]['expired'] = True
                elif change == 'missing':
                    api.artifacts.clear()
                elif change == 'duplicate':
                    api.artifacts *= 2
                else:
                    api.artifacts[0]['workflow_run']['id'] = 999
                with self.assertRaises(ValueError):
                    verify_ci.inspect(api.event, api, ENVIRONMENT)

    def test_only_develop_workflow_run_context_accepted(self):
        for key, value in [('GITHUB_REPOSITORY', 'fork/project'), ('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_REF', 'refs/pull/76/merge')]:
            with self.assertRaises(ValueError):
                verify_ci.inspect(self.api.event, self.api, {**ENVIRONMENT, key: value})

    def artifact(self, root):
        identity = verify_ci.inspect(self.api.event, self.api, ENVIRONMENT)
        name = 'mongle-' + 'a' * 40 + '.jar'
        (root / name).write_bytes(b'actual tested jar bytes')
        checksum = verify_ci.hashlib.sha256((root / name).read_bytes()).hexdigest()
        metadata = {'schemaVersion': 1, 'repository': verify_ci.REPOSITORY,
                    'commitSha': 'a' * 40, 'headSha': 'a' * 40, 'eventName': 'push',
                    'ref': 'refs/heads/develop', 'runId': '100', 'runAttempt': '2',
                    'jar': name, 'deploymentEligible': True, 'sha256': checksum}
        (root / 'build-metadata.json').write_text(json.dumps(metadata))
        (root / 'SHA256SUMS').write_text(f'{checksum}  {name}\n')
        return identity, metadata

    def test_matching_artifact_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            identity, _ = self.artifact(root)
            self.assertEqual(verify_ci.verify_artifact(root, identity)['jar'], 'mongle-' + 'a' * 40 + '.jar')

    def test_tampered_bytes_metadata_extra_files_and_symlinks_refused(self):
        for change in ('bytes', 'metadata', 'extra', 'symlink', 'boolean_type'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                identity, metadata = self.artifact(root)
                if change == 'bytes':
                    (root / metadata['jar']).write_bytes(b'tampered')
                elif change in ('metadata', 'boolean_type'):
                    metadata['deploymentEligible'] = 1 if change == 'boolean_type' else False
                    (root / 'build-metadata.json').write_text(json.dumps(metadata))
                elif change == 'extra':
                    (root / 'unexpected').write_text('untrusted')
                else:
                    (root / metadata['jar']).unlink()
                    (root / metadata['jar']).symlink_to('/etc/passwd')
                with self.assertRaises(ValueError):
                    verify_ci.verify_artifact(root, identity)


class InfrastructureTest(unittest.TestCase):
    def test_roles_are_separated_and_deployment_command_is_fixed(self):
        result = render_infra.render('mongle-prod-deploy-800417964658', 'i-0ebfbdbacc2b3cc1b')
        read = result['ec2-policy.json']['Statement'][0]
        self.assertEqual(read['Action'], 's3:GetObject')
        self.assertTrue(read['Resource'].endswith('/releases/*'))
        github = result['github-policy.json']['Statement']
        self.assertEqual(github[1]['Resource'], [
            'arn:aws:ssm:ap-northeast-2:800417964658:document/Mongle-DeployV1',
            'arn:aws:ec2:ap-northeast-2:800417964658:instance/i-0ebfbdbacc2b3cc1b'])
        self.assertEqual(github[2]['Action'], 'ssm:GetCommandInvocation')
        trust = result['github-trust.json']['Statement'][0]['Condition']['StringEquals']
        self.assertEqual(trust['token.actions.githubusercontent.com:sub'], render_infra.SUBJECT)
        document = result['ssm-deploy.json']
        self.assertEqual(document['mainSteps'][0]['inputs']['runCommand'][-1],
                         '/usr/bin/python3 -I /usr/local/libexec/mongle/deploy.py')
        self.assertTrue(all(p['interpolationType'] == 'ENV_VAR' for p in document['parameters'].values()))

    def test_invalid_resource_and_document_version_cannot_enter_command(self):
        settings = {'MONGLE_CD_BUCKET': 'mongle-deploy', 'MONGLE_CD_INSTANCE_ID': 'i-' + 'a' * 17,
                    'MONGLE_CD_SSM_DOCUMENT_VERSION': '1', 'MONGLE_CD_SSM_DOCUMENT_SHA256': 'a' * 64}
        self.assertEqual(dispatch.config(settings)['SSM_DOCUMENT_VERSION'], '1')
        for key, value in [('MONGLE_CD_SSM_DOCUMENT_VERSION', '$LATEST'),
                           ('MONGLE_CD_INSTANCE_ID', 'i-abc;id'), ('MONGLE_CD_BUCKET', '../x')]:
            with self.assertRaises(ValueError):
                dispatch.config({**settings, key: value})

    def test_immutable_publication_accepts_only_identical_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'jar'
            source.write_bytes(b'tested')
            def client(*args):
                if args[1] == 'put-object':
                    raise subprocess.CalledProcessError(1, 'aws', stderr='PreconditionFailed')
                Path(args[-1]).write_bytes(b'tested')
            dispatch.upload_immutable('bucket', 'key', source, client)
            source.write_bytes(b'different')
            with self.assertRaises(ValueError):
                dispatch.upload_immutable('bucket', 'key', source, client)


class DispatchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.old_cwd = dispatch.os.getcwd()
        dispatch.os.chdir(self.root)
        self.addCleanup(dispatch.os.chdir, self.old_cwd)
        (self.root / 'build').mkdir()
        event = self.root / 'event.json'
        event.write_text('{}')
        self.environment = {'MONGLE_CD_BUCKET': 'mongle-deploy', 'MONGLE_CD_INSTANCE_ID': 'i-' + 'a' * 17,
                            'MONGLE_CD_SSM_DOCUMENT_VERSION': '3', 'MONGLE_CD_SSM_DOCUMENT_SHA256': 'b' * 64,
                            'GITHUB_EVENT_PATH': str(event), 'GH_TOKEN': 'not-a-real-token'}
        identity = {'commit': 'a' * 40, 'run_id': '100', 'run_attempt': '2', 'run_number': '10',
                    'artifact_id': '200', 'jar': 'mongle-' + 'a' * 40 + '.jar', 'sha256': 'c' * 64}
        for target, options in [
                ('os.environ', {'new': self.environment}),
                ('verify_ci.inspect', {'return_value': identity}),
                ('verify_ci.verify_artifact', {'return_value': identity}),
                ('upload_immutable', {}), ('time.sleep', {})]:
            mocker = patch.dict(dispatch.os.environ, self.environment, clear=True) if target == 'os.environ' else patch('dispatch.' + target, **options)
            value = mocker.start()
            self.addCleanup(mocker.stop)
            if target == 'verify_ci.inspect':
                self.inspector = value
        self.calls = []
        self.results = [{'Status': 'Success', 'ResponseCode': 0, 'StandardOutputContent': '{"event":"deployment-succeeded"}'}]

    def client(self, *args):
        self.calls.append(args)
        if args[1] == 'send-command':
            return {'Command': {'CommandId': 'a-command-id'}}
        value = self.results.pop(0)
        if isinstance(value, Exception):
            raise value
        return value

    def test_pins_target_document_version_hash_and_persists_result(self):
        with patch('dispatch.aws', side_effect=self.client):
            dispatch.main()
        sent = self.calls[0]
        self.assertEqual(sent[sent.index('--document-name') + 1], 'Mongle-DeployV1')
        self.assertEqual(sent[sent.index('--document-version') + 1], '3')
        self.assertEqual(sent[sent.index('--document-hash') + 1], 'b' * 64)
        self.assertEqual(sent[sent.index('--instance-ids') + 1], 'i-' + 'a' * 17)
        record = json.loads((self.root / 'build/deployment-result.json').read_text())
        self.assertEqual(record['commandId'], 'a-command-id')
        self.assertEqual(record['status'], 'Success')
        self.assertNotIn('not-a-real-token', json.dumps(record))

    def test_waits_for_eventual_invocation_and_completion(self):
        self.results = [subprocess.CalledProcessError(1, 'aws', stderr='InvocationDoesNotExist'),
                        {'Status': 'InProgress'}, {'Status': 'Success', 'ResponseCode': 0}]
        with patch('dispatch.aws', side_effect=self.client):
            dispatch.main()
        self.assertEqual(len(self.calls), 4)

    def test_failed_remote_deployment_is_not_reported_as_success(self):
        self.results = [{'Status': 'Failed', 'ResponseCode': 1}]
        with patch('dispatch.aws', side_effect=self.client), self.assertRaises(RuntimeError):
            dispatch.main()
        self.assertEqual(json.loads((self.root / 'build/deployment-result.json').read_text())['status'], 'Failed')

    def test_outdated_revision_after_upload_cannot_send_command(self):
        self.inspector.side_effect = [self.inspector.return_value, ValueError('newer HEAD')]
        with patch('dispatch.aws') as client, self.assertRaises(ValueError):
            dispatch.main()
        client.assert_not_called()

    def test_upload_failure_never_sends_remote_command(self):
        with patch('dispatch.aws') as client, patch('dispatch.upload_immutable', side_effect=OSError), self.assertRaises(OSError):
            dispatch.main()
        client.assert_not_called()

    def test_unknown_outcome_retains_command_id_and_does_not_cancel(self):
        with patch('dispatch.aws', side_effect=self.client), patch('dispatch.time.monotonic', side_effect=[0, 1051]), self.assertRaises(TimeoutError):
            dispatch.main()
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(json.loads((self.root / 'build/deployment-result.json').read_text())['commandId'], 'a-command-id')


if __name__ == '__main__':
    unittest.main()
