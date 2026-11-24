import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class LauncherTests(unittest.TestCase):
    def launcher(self):
        self.assertIsNotNone(importlib.util.find_spec('scripts.run_local'), 'portable launcher is missing')
        from scripts import run_local
        return run_local

    def test_sdk_paths_with_spaces_on_each_platform(self):
        launch = self.launcher()
        with tempfile.TemporaryDirectory(prefix='car pairing ') as root:
            for system, relative in [('win32','AppData/Local/Android/Sdk'),('darwin','Library/Android/sdk'),('linux','Android/Sdk')]:
                folder = Path(root) / relative
                folder.mkdir(parents=True, exist_ok=True)
                with patch('sys.platform',system), patch.dict(os.environ,{},clear=True), patch.object(Path,'home',return_value=Path(root)):
                    self.assertEqual(launch.sdk_path(),folder)
                    binary=folder/'platform-tools'/('adb.exe' if system=='win32' else 'adb')
                    binary.parent.mkdir(exist_ok=True);binary.touch()
                    self.assertEqual(launch.find_adb(folder,True),str(binary))

    def test_no_guess_when_devices_are_ambiguous(self):
        launch=self.launcher()
        devices={'emulator-5554':'device','emulator-5556':'device','127.0.0.1:5557':'device'}
        self.assertEqual(launch.select_devices(devices,[]),['emulator-5554','emulator-5556'])
        with self.assertRaises(RuntimeError): launch.select_devices({'emulator-5554':'device'},[])
        with self.assertRaises(RuntimeError): launch.select_devices(devices,['missing'])
        self.assertEqual(launch.select_devices(devices,['emulator-5556']),['emulator-5556'])

    def test_wrong_server_is_rejected(self):
        launch=self.launcher()
        import io,json
        with patch.object(launch.LOCAL_HTTP,'open',return_value=io.BytesIO(json.dumps({'service':'car-pairing','version':'old'}).encode())):
            with self.assertRaises(RuntimeError):launch.check_server(8765)

if __name__=='__main__':unittest.main()
