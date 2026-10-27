import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from emulator import controls


class ConsoleAcknowledgementTests(unittest.TestCase):
    def test_zero_exit_authentication_ko_is_rejected(self):
        reply = subprocess.CompletedProcess([], 0,
            b"KO authentication token does not match ~/.emulator_console_auth_token\r\n", b"")
        with self.assertRaises(RuntimeError):
            controls.accept_console_result(reply)

    def test_console_ok_is_accepted(self):
        reply = subprocess.CompletedProcess([], 0, b"OK\r\n", b"")
        self.assertIs(controls.accept_console_result(reply), reply)

    def test_error_after_ok_is_rejected(self):
        reply = subprocess.CompletedProcess([], 0, b"OK\r\n", b"ERROR: console failure\n")
        with self.assertRaises(RuntimeError):
            controls.accept_console_result(reply)

    def test_zero_exit_without_console_confirmation_is_rejected(self):
        reply = subprocess.CompletedProcess([], 0, b"", b"")
        with self.assertRaises(RuntimeError):
            controls.accept_console_result(reply)

    def test_kill_confirmation_is_accepted(self):
        reply = subprocess.CompletedProcess([], 0, b"OK: killing emulator, bye bye\r\n", b"")
        self.assertIs(controls.accept_console_result(reply), reply)

    def test_console_token_lookup_uses_session_home(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory).resolve()
            doc = {"state": str(state), "adb_private_key": str(state / "own-key")}
            with patch.dict(os.environ, {"HOME": "/another-home", "ANDROID_EMULATOR_HOME": "/another-emulator"}):
                env = controls.session_environment(doc)
            self.assertEqual(env["HOME"], str(state / "home"))
            self.assertEqual(env["ANDROID_EMULATOR_HOME"], str(state / "emulator-home"))
            self.assertEqual(env["ANDROID_USER_HOME"], str(state / "android-home"))
            self.assertEqual(env["ADB_SERVER_SOCKET"], "tcp:127.0.0.1:5102")


class PrivateServerCleanupTests(unittest.TestCase):
    def test_empty_transport_list_closes_only_server5102(self):
        commands = []
        def runner(command, **options):
            commands.append(command)
            if command[-1] == "devices":
                return subprocess.CompletedProcess(command, 0, b"List of devices attached\n\n", b"")
            self.assertEqual(command, ["fake-adb", "-P", "5102", "kill-server"])
            self.assertNotIn("ADB_SERVER_SOCKET", options["env"])
            return subprocess.CompletedProcess(command, 0, b"", b"")
        report = controls.close_idle_private_server({"adb": "fake-adb"},
            {"ADB_SERVER_SOCKET": "tcp:127.0.0.1:5102"}, runner=runner, probe=lambda: False)
        self.assertEqual(report["status"], "CLOSED")
        self.assertTrue(report["server_kill_sent"])
        self.assertEqual(len(commands), 2)

    def test_another_transport_keeps_the_private_server(self):
        commands = []
        def runner(command, **options):
            commands.append(command)
            return subprocess.CompletedProcess(command, 0,
                b"List of devices attached\nemulator-5604\tdevice\n", b"")
        report = controls.close_idle_private_server({"adb": "fake-adb"}, {}, runner=runner)
        self.assertEqual(report["status"], "KEPT_OTHER_TRANSPORTS")
        self.assertFalse(report["server_kill_sent"])
        self.assertEqual(len(commands), 1)

    def test_still_connected_emulator_keeps_server_after_deadline(self):
        def runner(command, **options):
            return subprocess.CompletedProcess(command, 0,
                b"List of devices attached\nemulator-5602\toffline\n", b"")
        report = controls.close_idle_private_server({"adb": "fake-adb"}, {}, runner=runner,
                                                   clock=lambda: 0, wait_seconds=0)
        self.assertEqual(report["status"], "KEPT_EMULATOR_CONNECTED")
        self.assertFalse(report["server_kill_sent"])

    def test_malformed_device_list_never_means_empty(self):
        def runner(command, **options):
            return subprocess.CompletedProcess(command, 0, b"unexpected response\n", b"")
        report = controls.close_idle_private_server({"adb": "fake-adb"}, {}, runner=runner)
        self.assertEqual(report["status"], "INDETERMINATE")
        self.assertFalse(report["server_kill_sent"])

    def test_emulator_disappears_then_empty_server_is_closed(self):
        responses = [b"List of devices attached\nemulator-5602\tdevice\n",
                     b"List of devices attached\n\n"]
        commands = []
        def runner(command, **options):
            commands.append(command)
            return subprocess.CompletedProcess(command, 0,
                responses.pop(0) if command[-1] == "devices" else b"", b"")
        report = controls.close_idle_private_server({"adb": "fake-adb"}, {}, runner=runner,
            probe=lambda: False, clock=lambda: 0, sleep=lambda seconds: None)
        self.assertEqual(report["status"], "CLOSED")
        self.assertEqual(len(commands), 3)


if __name__ == "__main__":
    unittest.main()
