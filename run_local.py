"""Portable launcher adapted from the supplied macOS 0.13.1 runner.

No OS-specific AI runtime is required: Pleos recognizes speech; Ollama interprets it.
"""
import argparse
import json
import os
import runpy
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.request import build_opener, ProxyHandler

ROOT = Path(__file__).resolve().parent
BUNDLED_APK = ROOT / 'dist' / 'CarPairing-Integrated.apk'
ACTIVITY = 'ai.carpairing.app/.MainActivity'
DEVICE_PORT = 8765
SERVER_VERSION = '3.4-context'
LOCAL_HTTP = build_opener(ProxyHandler({}))

def port_number(value):
    port = int(value)
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("포트는 1~65535 사이여야 합니다.")
    return port


def sdk_path(explicit=None):
    configured = explicit or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured:
        return Path(configured).expanduser().resolve()
    if sys.platform == "darwin":
        default = Path.home() / "Library" / "Android" / "sdk"
    elif sys.platform == "win32":
        local_app_data = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        default = local_app_data / "Android" / "Sdk"
    else:
        default = Path.home() / "Android" / "Sdk"
    return default if default.is_dir() else None


def find_adb(sdk, explicit_sdk):
    filename = "adb.exe" if sys.platform == "win32" else "adb"
    if sdk is not None:
        candidate = sdk / "platform-tools" / filename
        if candidate.is_file():
            return str(candidate)
    if not explicit_sdk and (on_path := shutil.which("adb")):
        return on_path
    raise RuntimeError("ADB를 찾을 수 없습니다. Platform-Tools를 설치하고 ANDROID_HOME 또는 --sdk를 지정하세요.")


def adb_command(adb, *args):
    result = subprocess.run([adb, *args], capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=120)
    if result.returncode:
        detail = "\n".join(value.strip() for value in (result.stdout, result.stderr) if value.strip())
        raise RuntimeError(detail or f"ADB 실행 실패 ({result.returncode})")
    return result.stdout.strip()


def list_devices(adb):
    lines = adb_command(adb, "devices", "-l").splitlines()
    print("\n".join(lines), flush=True)
    devices = {}
    for line in lines[1:]:
        fields = line.split()
        if len(fields) >= 2 and not line.startswith("*"):
            devices[fields[0]] = fields[1]
    return devices


def select_devices(devices, requested):
    if requested:
        for serial in requested:
            if devices.get(serial) != "device":
                raise RuntimeError(f"{serial} 기기가 연결 또는 승인된 상태인지 확인하세요.")
        return list(dict.fromkeys(requested))
    emulators = [serial for serial in devices if serial.startswith("emulator-")]
    if len(emulators) != 2 or any(devices[serial] != "device" for serial in emulators):
        raise RuntimeError("Pleos 에뮬레이터 두 대를 준비하거나 --serial로 사용할 기기를 지정하세요.")
    return emulators


def check_server(port):
    try:
        with LOCAL_HTTP.open(f'http://127.0.0.1:{port}/health', timeout=2) as response:
            payload = json.load(response)
        if payload.get('service') == 'car-pairing' and payload.get('version') == SERVER_VERSION:
            return payload
    except (OSError, ValueError, AttributeError):
        pass
    raise RuntimeError(f'포트 {port}의 최신 모임 서버를 확인할 수 없습니다. 기존 서버 버전을 확인하고 server 명령을 실행하세요.')

def ready_devices(adb, devices, requested):
    serials = select_devices(devices, requested)
    for serial in serials:
        if adb_command(adb, "-s", serial, "shell", "getprop", "sys.boot_completed") != "1":
            raise RuntimeError(f"{serial}의 Android 부팅이 완료되면 다시 실행하세요.")
    return serials


def setup_devices(args, adb, serials):
    apk = args.apk.expanduser().resolve()
    if not apk.is_file():
        raise RuntimeError(f"APK 파일을 찾을 수 없습니다. {apk}")
    check_server(args.port)
    for serial in serials:
        print(f"{serial} 설치 및 연결 중", flush=True)
        print(adb_command(adb, "-s", serial, "install", "-r", str(apk)))
        adb_command(adb, "-s", serial, "reverse", f"tcp:{DEVICE_PORT}", f"tcp:{args.port}")
        print(adb_command(adb, "-s", serial, "shell", "am", "start", "--user", "current", "-n", ACTIVITY))
    print("Car Pairing에서 한 기기로 모임을 만들고 다른 기기로 참가하세요.")


def build(sdk):
    env = os.environ.copy()
    if sdk is not None:
        if not sdk.is_dir(): raise RuntimeError(f'Android SDK 폴더가 없습니다: {sdk}')
        env['ANDROID_HOME'] = str(sdk)
        env.pop('ANDROID_SDK_ROOT', None)
    if not env.get('JAVA_HOME'):
        candidates = [Path(os.environ.get('LOCALAPPDATA', str(Path.home()/'AppData/Local'))) / 'Programs/AndroidStudio/android-studio/jbr',
                      Path('C:/Program Files/Android/Android Studio/jbr'),
                      Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')]
        for candidate in candidates:
            if candidate.is_dir(): env['JAVA_HOME'] = str(candidate); break
    wrapper = [str(ROOT/'gradlew.bat')] if sys.platform == 'win32' else ['sh',str(ROOT/'gradlew')]
    result = subprocess.call([*wrapper, ':app:assembleDebug', ':app:testDebugUnitTest'], cwd=ROOT, env=env)
    if result == 0:
        BUNDLED_APK.parent.mkdir(exist_ok=True)
        shutil.copy2(ROOT/'app/build/outputs/apk/debug/app-debug.apk', BUNDLED_APK)
        print(f'빌드 완료: {BUNDLED_APK}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('check', help='SDK, ADB, 연결된 기기와 AI 상태 점검')
    commands.add_parser('build', help='APK 빌드 및 Android 단위 테스트')
    server = commands.add_parser('server', help='모임 서버 실행')
    server.add_argument('--port', type=port_number, default=DEVICE_PORT)
    setup = commands.add_parser('setup', help='이미 부팅한 기기에 APK 설치 및 연결')
    setup.add_argument('--port', type=port_number, default=DEVICE_PORT)
    setup.add_argument('--serial', action='append', default=[])
    setup.add_argument('--apk', type=Path, default=BUNDLED_APK)
    args = parser.parse_args()
    try:
        if args.command == 'server':
            os.environ.update(CAR_PAIRING_BIND='127.0.0.1', CAR_PAIRING_PORT=str(args.port))
            runpy.run_path(str(ROOT/'server.py'), run_name='__main__'); return 0
        sdk = sdk_path(args.sdk)
        if args.command == 'build': return build(sdk)
        adb = find_adb(sdk,args.sdk)
        print(f'Android SDK: {sdk}\nADB: {adb}\nAPK: {BUNDLED_APK} ({"있음" if BUNDLED_APK.is_file() else "빌드 필요"})')
        devices = list_devices(adb)
        if args.command == 'setup':
            setup_devices(args, adb, ready_devices(adb,devices,args.serial))
        else:
            from setup_local_ai import ready, MODEL
            info=ready()
            names=[x.get('name') for x in (info or {}).get('models',[])]
            print(f'로컬 AI: {MODEL} / {"설치됨" if MODEL in names else "01_Setup_Local_AI.cmd 실행 필요"}')
            print('음성: 에뮬레이터 ⋯ > Microphone > Enable Host Microphone Access 및 Windows 마이크 접근 허용을 확인하세요.')
        return 0
    except KeyboardInterrupt: return 130
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        print(f'실행 실패: {error}',file=sys.stderr); return 1

if __name__ == '__main__': raise SystemExit(main())
