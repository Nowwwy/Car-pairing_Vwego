"""Two-car launcher: two real Pleos AVDs, one app/server, distinct GPS feeds."""
import argparse, json, os, pathlib, socket, subprocess, time
from run_local import sdk_path, find_adb, SERVER_VERSION, LOCAL_HTTP, port_number, default_apk
ROOT=pathlib.Path(__file__).resolve().parent
SDK=sdk_path()
ADB=pathlib.Path(find_adb(SDK,False)) if SDK else pathlib.Path('missing-adb')
EMU=(SDK or ROOT)/'emulator'/('emulator.exe' if os.sys.platform=='win32' else 'emulator')
APK=default_apk()
CARS=[('Pleos_Connect_2_1',5554,127.110,37.395,'1번차량'),('CarPairing_Pleos_B',5556,127.125,37.510,'2번차량')]
NATIVE_NAV='ai.umos.maps.android.navigation.app'
def adb(port,*args,check=True,timeout=20):
 return subprocess.run([str(ADB),'-s',f'emulator-{port}',*map(str,args)],capture_output=True,text=True,encoding='utf-8',errors='replace',check=check,timeout=timeout).stdout.strip()
def acquire_launcher(port=48765):
 lock=socket.socket()
 try:lock.bind(('127.0.0.1',port))
 except OSError:
  lock.close()
  raise SystemExit('CarPairing two-car launcher is already running. Use its existing window.')
 return lock

def ensure_server(port):
 def health():
  with LOCAL_HTTP.open(f'http://127.0.0.1:{port}/health',timeout=2) as response:status=json.load(response)
  if status.get('service')!='car-pairing' or status.get('version')!=SERVER_VERSION:
   raise SystemExit(f'Another server uses port {port}. Close the older CarPairing server first.')
 try:health();return
 except OSError:pass
 with open(ROOT/'server.log','a',encoding='utf-8') as log:
  subprocess.Popen([os.sys.executable,'-X','utf8',str(ROOT/'server.py')],cwd=ROOT,stdout=log,stderr=log,
                   env=dict(os.environ,CAR_PAIRING_BIND='127.0.0.1',CAR_PAIRING_PORT=str(port)))
 deadline=time.monotonic()+30
 while time.monotonic()<deadline:
  try:health();return
  except OSError:time.sleep(.5)
 raise SystemExit(f'Server readiness timeout on port {port}. See server.log.')

def navigation_status_ready(log):
 # Pleos 2.1's Android boot flag precedes its navigation engine initialization.
 # Check only the latest status: an earlier ready event may precede a restart.
 statuses=[line for line in log.splitlines() if 'naviReadyStatusFlow' in line]
 return bool(statuses) and all(value in statuses[-1] for value in (
  'appStatus=AuthComplete', 'engineStatus=NaviEngineComplete', 'mapStatus=MapComplete'))

def wait_navigation_ready(port,timeout=120,stable_for=6):
 deadline=time.monotonic()+timeout
 previous_pid=None
 ready_since=None
 while time.monotonic()<deadline:
  try:
   pid=adb(port,'shell','pidof',NATIVE_NAV,check=False,timeout=5)
   log=adb(port,'logcat','-d','--pid='+pid,'-s','Navi42:I','*:S',check=False,timeout=5) if pid.isdigit() else ''
   ready=bool(pid) and navigation_status_ready(log)
  except (OSError,subprocess.SubprocessError):
   pid=None;ready=False
  now=time.monotonic()
  if pid!=previous_pid or not ready:ready_since=None
  previous_pid=pid
  if ready:
   if ready_since is None:ready_since=now
   if now<deadline and now-ready_since>=stable_for:return True
  time.sleep(min(3,max(0,deadline-now)))
 return False

def feed_positions(failed):
 # A transient transport failure must not kill both vehicles' location feed.
 for _,port,lon,lat,_ in CARS:
  try:
   adb(port,'emu','geo','fix',lon,lat,timeout=3)
   if port in failed:
    print(f'Vehicle {port}: GPS connection restored.',flush=True);failed.discard(port)
  except (OSError,subprocess.SubprocessError):
   if port not in failed:
    print(f'Vehicle {port}: GPS connection unavailable; retrying.',flush=True);failed.add(port)

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--port',type=port_number,default=8765)
 args=parser.parse_args()
 launcher_lock=acquire_launcher()  # Keep ownership while the GPS feed runs.
 if not ADB.exists() or not EMU.exists():raise SystemExit('Install Android SDK platform-tools, emulator and the Pleos 2.1 image first. See README.md.')
 if not APK.exists():raise SystemExit('APK missing: '+str(APK))
 avd=pathlib.Path.home()/'.android/avd'; base=avd/'Pleos_Connect_2_1.avd/config.ini'
 if not base.exists():raise SystemExit('Pleos_Connect_2_1 AVD is required. See README.md.')
 second=avd/'CarPairing_Pleos_B.avd'; second.mkdir(exist_ok=True)
 if (avd/'Pleos_Pairing_Follower_1.avd/config.ini').exists():
  CARS[1]=('Pleos_Pairing_Follower_1',5556,127.125,37.510,'2번차량')
 if not (second/'config.ini').exists():
  (second/'config.ini').write_text(base.read_text().replace('AvdId=Pleos_Connect_2_1','AvdId=CarPairing_Pleos_B').replace('avd.ini.displayname=Pleos Connect - Car Pairing','avd.ini.displayname=Car Pairing - Vehicle 2'))
  (avd/'CarPairing_Pleos_B.ini').write_text(f'avd.ini.encoding=UTF-8\npath={second}\npath.rel=avd/CarPairing_Pleos_B.avd\ntarget=android-34\n')
 ensure_server(args.port)
 for name,port,lon,lat,driver in CARS:
  try:boot=adb(port,'shell','getprop','sys.boot_completed',check=False)
  except Exception:boot=''
  if boot!='1':
   subprocess.Popen([str(EMU),'-avd',name,'-port',str(port),'-memory','2048','-cores','2','-gpu',('host' if name=='Pleos_Pairing_Follower_1' else 'swiftshader'),'-feature','-Vulkan','-no-snapshot','-scale','0.45'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
   deadline=time.monotonic()+300
   while time.monotonic()<deadline:
    if adb(port,'shell','getprop','sys.boot_completed',check=False)=='1':break
    time.sleep(3)
   else:raise SystemExit('Emulator boot timeout: '+name)
  adb(port,'reverse','tcp:8765',f'tcp:{args.port}')
  adb(port,'shell','am','force-stop','--user','current','ai.carpairing.app')
  result=subprocess.run([str(ADB),'-s',f'emulator-{port}','install','-r',str(APK)],capture_output=True,text=True,timeout=120)
  if 'Success' not in result.stdout:raise SystemExit(result.stdout+result.stderr+'\nFor a signature mismatch, uninstall the old development APK first (app data will be removed).')
  adb(port,'emu','geo','fix',lon,lat)
  print(f'Vehicle {port}: waiting for Pleos navigation initialization...',flush=True)
  adb(port,'shell','am','start','--user','current','-n',NATIVE_NAV+'/.MainActivity')
  if not wait_navigation_ready(port):
   print(f'Vehicle {port}: navigation readiness not confirmed within 120s; opening CarPairing with SDK availability still pending.',flush=True)
  else:print(f'Vehicle {port}: navigation ready.',flush=True)
 # Bring both apps forward only after both native navigation apps initialize.
 # This is a one-time launch step, never a foreground watchdog during driving.
 for _,port,_,_,driver in CARS:
  adb(port,'shell','am','start','-W','--user','current','--activity-reorder-to-front','-n','ai.carpairing.app/.MainActivity','--es','driver_name',driver,timeout=45)
 print('Vehicle 1: Pangyo 37.395,127.110 / Vehicle 2: Jamsil 37.510,127.125',flush=True)
 print('Create a group on vehicle 1, join the six-digit code on vehicle 2 and accept the shared destination.',flush=True)
 print('Allow location sharing in each app. Keep this window open for fresh GPS. Ctrl+C stops GPS only.',flush=True)
 failed=set()
 while True:
  feed_positions(failed)
  time.sleep(2)
if __name__=='__main__':
 try:main()
 except KeyboardInterrupt:print('GPS feed stopped. Apps and server remain open.')
