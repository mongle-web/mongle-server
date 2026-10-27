#!/usr/bin/env python3
"""Root-installed, fixed-target EC2 deployer. Never reads application secrets."""
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import signal
import shutil
import stat
import subprocess
import tempfile
import time
import urllib.request
import zipfile

CONFIG = Path('/etc/mongle/deploy.json')
STATE_DIR = Path('/var/lib/mongle-deploy')
APP_DIR = Path('/opt/mongle')
SHA = re.compile(r'[0-9a-f]{40}')
MIGRATION_CACHE = Path('/var/lib/mongle-migration')
MIGRATION_ENV = Path('/etc/mongle-migration/migration.env')
MIGRATION_UNIT = 'mongle-db-migration.service'


def emit(event, **values):
    print(json.dumps({'event': event, **values}, sort_keys=True), flush=True)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def sync_directory(path):
    directory_fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def atomic_json(path, value):
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as target:
        temporary = Path(target.name)
        try:
            json.dump(value, target, sort_keys=True)
            target.write('\n')
            target.flush()
            os.fsync(target.fileno())
            os.replace(temporary, path)
            sync_directory(path.parent)
        finally:
            temporary.unlink(missing_ok=True)


def executable_jar(path):
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n', '\n').replace('\n ', '')
        attributes = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
        if (attributes.get('Main-Class') != 'org.springframework.boot.loader.launch.JarLauncher'
                or not attributes.get('Start-Class')
                or not any(name.startswith('BOOT-INF/classes/') for name in jar.namelist())):
            raise ValueError('Not a Spring Boot executable JAR')


def parameters(environment):
    values = {key: environment.get('SSM_' + key, '') for key in
              ('CommitSha', 'JarSha256', 'RunId', 'RunNumber', 'RunAttempt', 'MigrationProtocol')}
    if values['MigrationProtocol'] != 'flyway-v1':
        raise ValueError('Flyway deployment protocol is required')
    if not SHA.fullmatch(values['CommitSha']) or not re.fullmatch(r'[0-9a-f]{64}', values['JarSha256']):
        raise ValueError('Invalid commit/checksum')
    for key in ('RunId', 'RunNumber', 'RunAttempt'):
        if not re.fullmatch(r'[1-9][0-9]{0,19}', values[key]):
            raise ValueError('Invalid CI identity')
    return values


def load_config(path=CONFIG):
    mode = path.lstat()
    if not stat.S_ISREG(mode.st_mode) or mode.st_uid != 0 or mode.st_mode & 0o022:
        raise ValueError('Deploy config must be root-owned and not writable by group/others')
    config = json.loads(path.read_text())
    if (not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', config['bucket'])
            or config['region'] != 'ap-northeast-2' or config['prefix'] != 'releases/'
            or config['aws'] not in ('/snap/bin/aws', '/usr/local/bin/aws')
            or config['publicHealth'] != 'https://api.mongle.art/actuator/health'
            or config.get('databaseMigration') is not True):
        raise ValueError('Invalid fixed deployment configuration')
    return config


class Runtime:
    """Only fixed service/URLs and a configured bucket may be accessed."""
    def __init__(self, config):
        self.config = config

    def download(self, key, destination):
        subprocess.run([self.config['aws'], 's3api', 'get-object', '--bucket', self.config['bucket'],
                        '--key', key, '--region', self.config['region'], str(destination)],
                       check=True, timeout=120, stdout=subprocess.DEVNULL,
                       env={**os.environ, 'AWS_PAGER': '', 'AWS_MAX_ATTEMPTS': '3',
                            'AWS_RETRY_MODE': 'standard'})

    def migration_account(self):
        account = pwd.getpwnam('mongle-migrate')
        app_account = pwd.getpwnam('mongle')
        cache = MIGRATION_CACHE.lstat()
        secret = MIGRATION_ENV.lstat()
        if (account.pw_uid in (0, app_account.pw_uid) or account.pw_gid in (0, app_account.pw_gid)
                or not stat.S_ISDIR(cache.st_mode) or cache.st_uid != 0
                or cache.st_gid != account.pw_gid or cache.st_mode & 0o027
                or not stat.S_ISREG(secret.st_mode) or secret.st_uid != 0 or secret.st_mode & 0o077):
            raise ValueError('Install the protected migration account, cache and environment first')
        return account

    def migrate(self, jar, checksum):
        account = self.migration_account()
        # App-owned releases are not executable with migration secrets. Copy and re-hash into
        # a root-owned directory that neither the app nor the migrator can modify.
        protected = MIGRATION_CACHE / jar.name
        with tempfile.NamedTemporaryFile(dir=MIGRATION_CACHE, delete=False) as temporary:
            staged = Path(temporary.name)
        try:
            shutil.copyfile(jar, staged)
            if digest(staged) != checksum:
                raise ValueError('Migration JAR checksum mismatch')
            executable_jar(staged)
            os.chown(staged, 0, account.pw_gid)
            os.chmod(staged, 0o640)
            with staged.open('rb') as source:
                os.fsync(source.fileno())
            os.replace(staged, protected)
            sync_directory(MIGRATION_CACHE)
        finally:
            staged.unlink(missing_ok=True)
        command = ['/usr/bin/systemd-run', '--quiet', '--wait', '--pipe', '--collect',
                   '--service-type=exec', '--unit=' + MIGRATION_UNIT,
                   '--property=User=mongle-migrate', '--property=Group=mongle-migrate',
                   '--property=WorkingDirectory=' + str(MIGRATION_CACHE),
                   '--property=EnvironmentFile=' + str(MIGRATION_ENV),
                   '--property=RuntimeMaxSec=240', '--property=TimeoutStopSec=15',
                   '--property=KillMode=control-group', '--property=NoNewPrivileges=true',
                   '--property=PrivateTmp=true', '--property=ProtectSystem=strict',
                   '--property=ProtectHome=true', '--property=InaccessiblePaths=/etc/mongle /var/lib/mongle-deploy',
                   '--property=UMask=0077', '/usr/bin/java', '-Xms32m', '-Xmx256m',
                   '-jar', str(protected), '--db-migration=migrate']
        try:
            result = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True,
                                    text=True, timeout=270)
            if result.returncode != 0:
                raise RuntimeError('Database migration failed; inspect migration history before retrying')
            value = json.loads(result.stdout)
            if (value.get('event') != 'migration-completed' or value.get('operation') != 'migrate'
                    or type(value.get('migrationsExecuted')) is not int or value['migrationsExecuted'] < 0):
                raise ValueError('Migration completion evidence missing')
            emit('database-migrated', migrationsExecuted=value['migrationsExecuted'])
        except BaseException:
            with ignored_signals():
                subprocess.run(['/usr/bin/systemctl', 'stop', MIGRATION_UNIT], capture_output=True, timeout=30)
            # Never print child output, JDBC errors, SQL or environment-file contents.
            raise RuntimeError('Database migration did not complete; application was not switched') from None

    def restart(self):
        subprocess.run(['/usr/bin/systemctl', 'restart', 'mongle.service'], check=True, timeout=45)

    def healthy(self, timeout=150):
        deadline = time.monotonic() + timeout
        # Both requests must succeed in the same round. No proxy from the service environment.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.monotonic() < deadline:
            try:
                active = subprocess.run(['/usr/bin/systemctl', 'is-active', '--quiet', 'mongle.service'],
                                        timeout=5).returncode == 0
                if not active:
                    raise ValueError('Service is not active')
                for url in ('http://127.0.0.1:8080/actuator/health', self.config['publicHealth']):
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        return False
                    with opener.open(url, timeout=min(5, remaining)) as response:
                        if (response.status != 200 or response.geturl() != url
                                or json.loads(response.read(65536)).get('status') != 'UP'):
                            raise ValueError('Health is not UP')
                return True
            except (OSError, ValueError, subprocess.TimeoutExpired):
                time.sleep(min(2, max(0, deadline - time.monotonic())))
        return False


class Deployer:
    def __init__(self, app, state_dir, config, runtime, owner=None):
        self.app, self.state_dir, self.config, self.runtime = app, state_dir, config, runtime
        self.releases = app / 'releases'
        self.current = app / 'current.jar'
        self.state_file = state_dir / 'state.json'
        self.owner = owner

    def target(self):
        if not self.current.is_symlink():
            raise ValueError('current.jar must be an existing symlink')
        target = self.current.resolve(strict=True)
        return self.validate_target(target)

    def validate_target(self, target):
        target = Path(target)
        if (target.parent != self.releases or not re.fullmatch(r'mongle-[0-9a-f]{7,40}\.jar', target.name)
                or target.is_symlink() or not target.is_file()):
            raise ValueError('Release target must be a regular JAR inside releases')
        return target

    def switch(self, target):
        self.validate_target(target)
        temporary = self.app / ('.current-' + str(os.getpid()))
        try:
            temporary.symlink_to(target)
            os.replace(temporary, self.current)
            sync_directory(self.app)
        finally:
            temporary.unlink(missing_ok=True)

    def save(self, state):
        atomic_json(self.state_file, state)

    def rollback(self, state):
        previous = self.validate_target(state['pending']['previous'])
        emit('rollback-started', previous=previous.name)
        self.switch(previous)
        self.runtime.restart()
        if not self.runtime.healthy():
            raise RuntimeError('Previous release also failed health; pending recovery preserved')
        state.pop('pending')
        state['activeJar'] = previous.name
        self.save(state)
        emit('rollback-succeeded', activeJar=previous.name)

    def run(self, values):
        if self.app.is_symlink() or self.releases.is_symlink():
            raise ValueError('Application directories must not be symlinks')
        state = json.loads(self.state_file.read_text()) if self.state_file.exists() else {}
        # Recover a previous killed/interrupted deployment before accepting anything new.
        if state.get('pending'):
            self.rollback(state)
        sequence = (int(values['RunNumber']), int(values['RunAttempt']))
        highest = tuple(state.get('highestSequence', [0, 0]))
        if sequence < highest:
            raise ValueError('Refusing an older CI run/attempt')
        if sequence == highest and state.get('lastCommit') != values['CommitSha']:
            raise ValueError('Same CI identity cannot deploy a different commit')
        previous = self.target()
        commit = values['CommitSha']
        target = self.releases / f'mongle-{commit}.jar'
        key = (f"{self.config['prefix']}{commit}/{values['RunId']}/{values['RunAttempt']}/"
               f'mongle-{commit}.jar')
        with tempfile.TemporaryDirectory(prefix='.deploy-', dir=self.releases) as directory:
            staged = Path(directory) / target.name
            self.runtime.download(key, staged)
            if digest(staged) != values['JarSha256']:
                raise ValueError('Downloaded JAR checksum mismatch')
            executable_jar(staged)
            if target.exists() or target.is_symlink():
                if target.is_symlink() or not target.is_file() or digest(target) != values['JarSha256']:
                    raise ValueError('Existing release differs; refusing to overwrite')
            else:
                os.chmod(staged, 0o640)
                if self.owner:
                    os.chown(staged, *self.owner)
                # Download/validation completed before the service is stopped.
                with staged.open('rb') as source:
                    os.fsync(source.fileno())
                os.replace(staged, target)
                sync_directory(self.releases)
        if not self.runtime.healthy(timeout=20):
            raise RuntimeError('Previous release is not healthy; automatic deployment refused')
        state.update(highestSequence=list(sequence), lastCommit=commit, runId=values['RunId'])
        state['migration'] = {'commit': commit, 'status': 'running'}
        self.save(state)
        try:
            self.runtime.migrate(target, values['JarSha256'])
        except BaseException:
            with ignored_signals():
                state['migration']['status'] = 'failed'
                self.save(state)
            raise
        state['migration']['status'] = 'applied'
        self.save(state)
        if target == previous:
            state['activeJar'] = target.name
            self.save(state)
            emit('already-active', commit=commit)
            return
        state['pending'] = {'previous': str(previous), 'candidate': str(target)}
        self.save(state)
        try:
            self.switch(target)
            self.runtime.restart()
            if not self.runtime.healthy():
                raise RuntimeError('Candidate failed internal/public health')
            state.pop('pending')
            state['activeJar'] = target.name
            self.save(state)
            emit('deployment-succeeded', commit=commit, runId=values['RunId'])
        except BaseException:
            # A single TERM/INT should initiate recovery, rather than kill recovery as well.
            with ignored_signals():
                if not state.get('pending'):
                    state['pending'] = {'previous': str(previous), 'candidate': str(target)}
                self.rollback(state)
            raise


@contextlib.contextmanager
def ignored_signals():
    handlers = {sig: signal.signal(sig, signal.SIG_IGN) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        yield
    finally:
        for sig, handler in handlers.items():
            signal.signal(sig, handler)


def main():
    if os.geteuid() != 0:
        raise PermissionError('SSM deployer must run as root')
    os.umask(0o077)
    values = parameters(os.environ)
    config = load_config()
    STATE_DIR.mkdir(mode=0o700, exist_ok=True)
    if (STATE_DIR.is_symlink() or STATE_DIR.stat().st_uid != 0
            or STATE_DIR.stat().st_mode & 0o077):
        raise ValueError('Deployment state directory must be owned by root')
    account = pwd.getpwnam('mongle')
    with (STATE_DIR / 'deploy.lock').open('a') as lock:
        # A second SSM command fails visibly instead of running concurrently.
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        def interrupted(signum, _frame):
            raise InterruptedError(f'Deployment interrupted by signal {signum}')
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, interrupted)
        Deployer(APP_DIR, STATE_DIR, config, Runtime(config),
                 (account.pw_uid, account.pw_gid)).run(values)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Exception messages may include URLs/service output; do not dump application logs.
        emit('deployment-failed', errorType=type(error).__name__, reason=str(error))
        raise SystemExit(1)
