"""Linux-only deployment lifecycle tests; never contact a server or real systemd."""
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / 'deploy/scripts/deploy-release.sh'
SHA = 'a' * 40


@unittest.skipUnless(sys.platform == 'linux' and shutil.which('bash'), 'Linux deployment harness')
class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'pixelflow'
        self.old = self.root / 'releases/previous'
        self.old.mkdir(parents=True)
        (self.root / 'incoming').mkdir()
        (self.root / 'current').symlink_to(self.old)
        self.migration = 'server/src/main/resources/db/mysql/V1__schema.sql'
        self.schema = 'intelligence/src/pixelflow_intelligence/analytics/mysql_schema.py'
        for name, value in ((self.migration, 'CREATE TABLE test (id INT);'), (self.schema, '# schema v1')):
            path = self.old / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value)
        self.bin = Path(self.temp.name) / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'commands.log'
        self.executable('sudo', '#!/bin/sh\nprintf "%s\\n" "$*" >> "$TEST_LOG"\n')
        self.executable('systemctl', '#!/bin/sh\nexit 3\n')  # No scheduler installed.
        self.executable('sleep', '#!/bin/sh\nexit 0\n')
        self.executable('curl', '''#!/bin/sh
if [ "${FAIL_NEW_HEALTH:-}" = 1 ] && [ "$(readlink "$PIXELFLOW_DEPLOY_ROOT/current")" != "$TEST_OLD" ]; then
  exit 22
fi
printf '{"status":"UP"}'
''')
        # Fake only Python installation/version checks. Real Python validates tar and JSON.
        self.executable('python-test', '''#!/usr/bin/env python3
import os, pathlib, sys
if sys.argv[1:3] == ['-m', 'venv']:
    binary = pathlib.Path(sys.argv[3]) / 'bin/python'
    binary.parent.mkdir(parents=True)
    binary.write_text('#!/bin/sh\\n[ "${FAIL_INSTALL:-}" != 1 ]\\n')
    binary.chmod(0o755)
elif len(sys.argv) > 2 and sys.argv[1] == '-c' and 'Python 3.11 required' in sys.argv[2]:
    pass
else:
    os.execv(sys.executable, [sys.executable] + sys.argv[1:])
''')
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        PIXELFLOW_DEPLOY_ROOT=str(self.root),
                        PIXELFLOW_DEPLOY_PYTHON=str(self.bin / 'python-test'),
                        PIXELFLOW_HEALTH_ATTEMPTS='1', TEST_LOG=str(self.log), TEST_OLD=str(self.old))

    def executable(self, name, content):
        path = self.bin / name
        path.write_text(content)
        path.chmod(0o755)

    def package(self, changed_schema=False, unsafe=False):
        files = {
            'RELEASE_COMMIT': SHA + '\n',
            'server/target/pixelflow-server-0.1.0.jar': 'fake jar',
            self.migration: (self.old / self.migration).read_text(),
            self.schema: '# changed' if changed_schema else (self.old / self.schema).read_text(),
            'intelligence/pyproject.toml': '# test fixture',
        }
        if unsafe:
            files['../escape'] = 'must not extract'
        archive = self.root / f'incoming/{SHA}-1-1.tar.gz'
        with tarfile.open(archive, 'w:gz') as output:
            for name, text in files.items():
                data = text.encode()
                item = tarfile.TarInfo(name)
                item.size = len(data)
                output.addfile(item, io.BytesIO(data))
        return hashlib.sha256(archive.read_bytes()).hexdigest()

    def run_deploy(self, digest):
        return subprocess.run(['bash', str(SCRIPT), SHA, '1-1', digest], env=self.env,
                              text=True, capture_output=True, timeout=15)

    def test_success_switches_release_and_starts_both_services(self):
        result = self.run_deploy(self.package())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.root / f'releases/{SHA}-1-1', (self.root / 'current').resolve())
        self.assertIn('start pixelflow-java.service', self.log.read_text())
        self.assertIn('start pixelflow-python.service', self.log.read_text())

    def test_failed_health_rolls_back_but_still_reports_failure(self):
        self.env['FAIL_NEW_HEALTH'] = '1'
        result = self.run_deploy(self.package())
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.old, (self.root / 'current').resolve())
        self.assertIn('Application rollback healthy', result.stderr)

    def test_schema_change_stops_before_service_mutation(self):
        result = self.run_deploy(self.package(changed_schema=True))
        self.assertEqual(4, result.returncode, result.stderr)
        self.assertEqual(self.old, (self.root / 'current').resolve())
        self.assertFalse(self.log.exists())

    def test_checksum_mismatch_stops_before_extraction(self):
        self.package()
        result = self.run_deploy('0' * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / f'releases/{SHA}-1-1').exists())
        self.assertFalse(self.log.exists())

    def test_dependency_install_failure_keeps_old_services_untouched(self):
        self.env['FAIL_INSTALL'] = '1'
        result = self.run_deploy(self.package())
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.old, (self.root / 'current').resolve())
        self.assertFalse(self.log.exists())

    def test_running_analysis_timer_is_paused_and_restored_on_rollback(self):
        self.executable('systemctl', '#!/bin/sh\nexit 0\n')
        self.env['FAIL_NEW_HEALTH'] = '1'
        result = self.run_deploy(self.package())
        self.assertNotEqual(0, result.returncode)
        commands = self.log.read_text()
        self.assertIn('stop pixelflow-analysis.timer', commands)
        self.assertIn('stop pixelflow-analysis.service', commands)
        self.assertTrue(commands.rstrip().endswith('start pixelflow-analysis.timer'))
        self.assertEqual(self.old, (self.root / 'current').resolve())

    def test_path_traversal_is_rejected(self):
        result = self.run_deploy(self.package(unsafe=True))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Unsafe archive', result.stderr)
        self.assertFalse(self.log.exists())

    def test_second_deployment_cannot_enter_while_locked(self):
        import fcntl
        digest = self.package()
        with (self.root / '.deploy.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_deploy(digest)
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertFalse(self.log.exists())


if __name__ == '__main__':
    unittest.main()
