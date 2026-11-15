import io,json,socket,unittest
from unittest.mock import patch,MagicMock
import run_two as r

class TwoCarLauncherTests(unittest.TestCase):
 def response(self,version=None):
  return io.BytesIO(json.dumps({'service':'car-pairing','version':version or r.SERVER_VERSION}).encode())
 def test_custom_port_reuses_healthy_server(self):
  with patch.object(r.LOCAL_HTTP,'open',return_value=self.response()) as http, patch.object(r.subprocess,'Popen') as spawn:
   r.ensure_server(8766)
   self.assertEqual(http.call_args.args[0],'http://127.0.0.1:8766/health')
   spawn.assert_not_called()
 def test_wrong_server_is_not_overwritten(self):
  with patch.object(r.LOCAL_HTTP,'open',return_value=self.response('old')), patch.object(r.subprocess,'Popen') as spawn:
   with self.assertRaises(SystemExit):r.ensure_server(8766)
   spawn.assert_not_called()
 def test_server_start_waits_until_ready_and_passes_port(self):
  with patch.object(r.LOCAL_HTTP,'open',side_effect=[ConnectionRefusedError(),ConnectionRefusedError(),self.response()]), patch.object(r.subprocess,'Popen') as spawn, patch('builtins.open',MagicMock()), patch.object(r.time,'sleep'):
   r.ensure_server(8766)
   self.assertEqual(spawn.call_args.kwargs['env']['CAR_PAIRING_PORT'],'8766')
 def test_launcher_lock_prevents_duplicate_gps_loops(self):
  first=r.acquire_launcher(0)
  try:
   with self.assertRaises(SystemExit):r.acquire_launcher(first.getsockname()[1])
  finally:first.close()

class NavigationStartupTests(unittest.TestCase):
 READY='naviReadyStatusFlow Status(appStatus=AuthComplete, engineStatus=NaviEngineComplete, mapStatus=MapComplete)'
 def test_booting_native_does_not_count_as_ready(self):
  self.assertFalse(r.navigation_status_ready('Status(appStatus=AuthComplete, engineStatus=Booting, mapStatus=MapComplete)'))
  self.assertFalse(r.navigation_status_ready(self.READY+'\nnaviReadyStatusFlow Status(appStatus=AuthComplete, engineStatus=Booting, mapStatus=MapComplete)'))
  self.assertTrue(r.navigation_status_ready(self.READY))
 def test_restart_resets_readiness_window(self):
  # PID 11 is ready once, restarts; PID 12 must independently stay ready.
  clock=[0]
  pids=iter(['11','12','12','12'])
  seen=[]
  def fake_adb(port,*args,**kwargs):
   if args[:2]==('shell','pidof'):return next(pids)
   seen.append(args);return self.READY
  with patch.object(r,'adb',side_effect=fake_adb),patch.object(r.time,'monotonic',side_effect=lambda:clock[0]),patch.object(r.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)):
   self.assertTrue(r.wait_navigation_ready(5554,timeout=20,stable_for=6))
  self.assertEqual(clock[0],9)
  self.assertEqual(len(seen),4)
 def test_timeout_is_bounded_when_native_never_initializes(self):
  clock=[0]
  with patch.object(r,'adb',return_value=''),patch.object(r.time,'monotonic',side_effect=lambda:clock[0]),patch.object(r.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)):
   self.assertFalse(r.wait_navigation_ready(5554,timeout=9))
  self.assertEqual(clock[0],9)
 def test_transient_adb_failure_during_startup_recovers(self):
  clock=[0]
  calls=iter([r.subprocess.TimeoutExpired('adb',20),'12',self.READY,'12',self.READY])
  def fake(*a,**k):
   result=next(calls)
   if isinstance(result,Exception):raise result
   return result
  with patch.object(r,'adb',side_effect=fake),patch.object(r.time,'monotonic',side_effect=lambda:clock[0]),patch.object(r.time,'sleep',side_effect=lambda n:clock.__setitem__(0,clock[0]+n)):
   self.assertTrue(r.wait_navigation_ready(5554,timeout=15,stable_for=3))
 def test_offline_vehicle_does_not_stop_other_gps_feed(self):
  failed=set()
  with patch.object(r,'adb',side_effect=[r.subprocess.TimeoutExpired('adb',20),'OK']),patch('builtins.print'):
   r.feed_positions(failed)
  self.assertEqual(failed,{5554})
  with patch.object(r,'adb',return_value='OK'),patch('builtins.print'):
   r.feed_positions(failed)
  self.assertEqual(failed,set())
 def test_both_native_apps_ready_before_either_pairing_app_opens(self):
  import tempfile
  from pathlib import Path
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder)
   config=root/'.android/avd/Pleos_Connect_2_1.avd/config.ini'
   config.parent.mkdir(parents=True);config.write_text('AvdId=Pleos_Connect_2_1')
   binary=root/'binary';binary.touch()
   events=[]
   def fake_adb(port,*args,**kwargs):
    if args[:2]==('shell','getprop'):return '1'
    if 'start' in args:
     component=args[args.index('-n')+1]
     if component=='ai.carpairing.app/.MainActivity':self.assertIn('-W',args)
     events.append(('start',port,component))
    return ''
   def ready(port):events.append(('ready',port));return True
   lock=MagicMock()
   with patch.object(r,'ADB',binary),patch.object(r,'EMU',binary),patch.object(r,'APK',binary),patch.object(Path,'home',return_value=root),patch.object(r.os.sys,'argv',['run_two.py']),patch.object(r,'acquire_launcher',return_value=lock),patch.object(r,'ensure_server'),patch.object(r,'adb',side_effect=fake_adb),patch.object(r.subprocess,'run',return_value=MagicMock(stdout='Success')),patch.object(r,'wait_navigation_ready',side_effect=ready),patch.object(r,'feed_positions',side_effect=KeyboardInterrupt),patch('builtins.print'):
    with self.assertRaises(KeyboardInterrupt):r.main()
   app_starts=[i for i,e in enumerate(events) if len(e)==3 and e[2]=='ai.carpairing.app/.MainActivity']
   native_ready=[i for i,e in enumerate(events) if e[0]=='ready']
   self.assertEqual(len(app_starts),2)
   self.assertEqual(len(native_ready),2)
   self.assertLess(max(native_ready),min(app_starts))
