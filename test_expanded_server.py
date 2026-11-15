import json, tempfile, threading, unittest
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from unittest.mock import patch
from pathlib import Path
import server

class ExpandedServerTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.http=server.ThreadingHTTPServer(('127.0.0.1',0),server.Handler)
  cls.thread=threading.Thread(target=cls.http.serve_forever,daemon=True);cls.thread.start()
  cls.base='http://127.0.0.1:'+str(cls.http.server_port)
 @classmethod
 def tearDownClass(cls):cls.http.shutdown();cls.http.server_close();cls.thread.join()
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
  self.path=Path(self.tmp.name)/'rooms.sqlite3'
  server.rooms.clear()
  if hasattr(server,'init_storage'):server.init_storage(self.path)
  self.addCleanup(lambda:setattr(server,'STORE',None))
  self.env=patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'0'});self.env.start();self.addCleanup(self.env.stop)
  self.a=self.call('/rooms',dict(name='앞차',client_id='device-a',destination='강남역',latitude=37.49721,longitude=127.02805))[1]
  self.b=self.call('/join',dict(name='뒷차',client_id='device-b',code=self.a['code']))[1]
 def call(self,path,body=None,member=None):
  data=dict(body or {})
  if member:data.setdefault('code',member['code'])
  h={'Content-Type':'application/json'}
  if member:h['Authorization']='Bearer '+member['token']
  try:
   with urlopen(Request(self.base+path,json.dumps(data).encode(),h),timeout=5) as r:return r.status,json.load(r)
  except HTTPError as e:return e.code,json.load(e)
 def sync(self,member,**kw):return self.call('/sync',dict(after=0,**kw),member)[1]
 def test_leadership_and_leave_are_authenticated(self):
  self.assertTrue(self.a.get('is_leader',False))
  self.assertFalse(self.b['is_leader'])
  self.sync(self.b)
  self.assertEqual(self.call('/leader',dict(member_id=self.b['member_id']),self.b)[0],403)
  self.assertEqual(self.call('/leader',dict(member_id=self.b['member_id']),self.a)[0],200)
  self.assertEqual(self.sync(self.a)['leader_id'],self.b['member_id'])
  self.assertEqual(self.call('/leave',member=self.b)[0],200)
  self.assertEqual(self.sync(self.a)['leader_id'],self.a['member_id'])
  self.assertEqual(self.call('/sync',dict(after=0),self.b)[0],401)
 def test_private_parking_and_profile_choice_history(self):
  self.assertFalse(self.call('/profile',member=self.a)[1]['learning_enabled'])
  self.call('/preferences',dict(learning_enabled=True,plate='12가3456'),self.a)
  p=dict(name='주차장',latitude=37.497,longitude=127.029,address='')
  self.assertEqual(self.call('/choices',dict(kind='parking',place=p,accepted=True,request_id='one'),self.a)[0],200)
  self.call('/choices',dict(kind='parking',place=p,accepted=True,request_id='one'),self.a)
  self.assertEqual(self.call('/profile',member=self.a)[1]['choices_count'],1)
  saved=self.call('/parking/save',dict(place=p,floor='B2',zone='C12',note='엘리베이터 옆'),self.a)[1]
  self.assertEqual(self.call('/parking/list',member=self.b)[1]['records'],[])
  self.assertEqual(self.call('/parking/delete',dict(id=saved['id']),self.b)[0],404)
  self.assertEqual(self.call('/parking/list',member=self.a)[1]['records'][0]['floor'],'B2')
  self.assertEqual(self.call('/parking/save',dict(floor='x'*25),self.a)[0],400)
  self.call('/history/clear',member=self.a)
  self.assertEqual(self.call('/profile',member=self.a)[1]['choices_count'],0)
 def test_durable_room_restores_credentials_without_stale_presence(self):
  self.sync(self.a,latitude=37.395,longitude=127.11,location_age_ms=0,remaining_seconds=600)
  self.call('/messages',dict(kind='message',text='20분 늦을 것 같아'),self.a)
  server.init_storage(self.path)
  restored=self.sync(self.b)
  self.assertEqual(restored['members'],['앞차','뒷차'])
  self.assertEqual(restored['companions'],[])
  self.assertEqual(restored['events'][0]['kind'],'delay')
  self.sync(self.a)
  self.assertEqual(len(self.sync(self.b)['companions']),1)
 def test_discovery_requires_leader_opt_in_and_fresh_location(self):
  self.sync(self.a,latitude=37.5,longitude=127.0,location_age_ms=0)
  q=dict(latitude=37.501,longitude=127.001,location_age_ms=0)
  self.assertEqual(self.call('/nearby',q)[1]['rooms'],[])
  self.assertEqual(self.call('/discovery',dict(enabled=True),self.b)[0],403)
  self.call('/discovery',dict(enabled=True),self.a)
  self.assertEqual(self.call('/nearby',q)[1]['rooms'][0]['code'],self.a['code'])
  self.assertEqual(self.call('/nearby',dict(q,location_age_ms=30000))[0],400)
  self.assertEqual(self.call('/nearby',dict(q,latitude=36))[1]['rooms'],[])
 def test_destination_offer_and_fuel_dwell_propagation(self):
  place=dict(name='공동 숙소',latitude=37.75,longitude=128.9,address='')
  self.assertEqual(self.call('/destination',dict(place=place),self.b)[0],403)
  self.assertEqual(self.call('/destination',dict(place=place),self.a)[0],200)
  event=self.sync(self.b)['events'][0]
  self.assertEqual((event['kind'],event['place']),('destination',place))
  self.assertEqual(self.sync(self.b)['shared_place'],place)
  self.assertEqual(self.call('/messages',dict(kind='message',text='주유하고 갈게',place=place,stop_kind='fuel',dwell_minutes=10),self.a)[0],200)
  event=self.sync(self.b)['events'][-1]
  self.assertEqual((event['kind'],event['stop_kind'],event['dwell_minutes']),('rest','fuel',10))
  self.assertEqual(self.call('/messages',dict(kind='message',text='쉬자',dwell_minutes=241),self.a)[0],400)
 def test_trip_estimate_auth_default_target_and_provider_failure(self):
  origin=dict(latitude=37.395,longitude=127.11)
  result=dict(base={'duration_seconds':600},via={'duration_seconds':900},dwell_seconds=600,total_seconds=1500,added_seconds=900,traffic_included=False,provider='test')
  with patch.object(server,'estimate_trip',return_value=result,create=True) as estimate:
   self.assertEqual(self.call('/trip/estimate',dict(origin=origin,stops=[]))[0],401)
   status,response=self.call('/trip/estimate',dict(origin=origin,stops=[]),self.a)
   self.assertEqual(status,200);self.assertEqual(response['total_seconds'],1500)
   self.assertEqual(estimate.call_args.args[1]['name'],'강남역')
   self.assertEqual(self.call('/trip/estimate',dict(origin=dict(latitude=float('nan'),longitude=127),stops=[]),self.a)[0],400)
  if hasattr(server,'RoutingUnavailable'):
   with patch.object(server,'estimate_trip',side_effect=server.RoutingUnavailable('offline')):
    self.assertEqual(self.call('/trip/estimate',dict(origin=origin,stops=[]),self.a)[0],502)
 def test_invalid_create_does_not_leave_room_and_bad_dwell_skips_network(self):
  count=len(server.rooms)
  self.assertEqual(self.call('/rooms',dict(name='bad',destination='test',client_id='invalid!'))[0],400)
  self.assertEqual(len(server.rooms),count)
  with patch.object(server,'estimate_trip') as estimate:
   self.assertEqual(self.call('/trip/estimate',dict(origin=dict(latitude=37.4,longitude=127.1),destination_dwell_minutes=-1),self.a)[0],400)
   estimate.assert_not_called()
 def test_reusable_meetings_do_not_auto_join_others(self):
  self.assertEqual(self.call('/meeting/save',dict(name='월례모임'),self.a)[0],200)
  meeting=self.call('/meetings',member=self.a)[1]['meetings'][0]
  self.assertEqual(meeting['place']['name'],'강남역')
  self.assertEqual(self.call('/meetings',member=self.b)[1]['meetings'],[])
  self.assertEqual(self.call('/meeting/delete',dict(id=meeting['id']),self.b)[0],404)
  self.assertEqual(self.call('/meeting/delete',dict(id=meeting['id']),self.a)[0],200)

 def test_rejected_destination_offer_does_not_mutate_shared_destination(self):
  before=self.sync(self.b)['shared_place']
  with server.lock:
   room=server.rooms[self.a['code']]
   room.events=[dict(id=i+1,sender='앞차',kind='message',text='test',original='test',source='rule',author=self.a['token']) for i in range(2000)]
  place=dict(name='거절된 새 목적지',latitude=37.75,longitude=128.9,address='')
  self.assertEqual(self.call('/destination',dict(place=place),self.a)[0],409)
  self.assertEqual(self.sync(self.b)['shared_place'],before)

 def test_eta_metadata_describes_each_personal_route_and_null_clears_it(self):
  self.sync(self.a,remaining_seconds=900,route_name='다른 주차장',eta_source='road_estimate',
            route_latitude=37.50,route_longitude=127.02,eta_age_ms=4000)
  peer=self.sync(self.b)['companions'][0]
  self.assertEqual(peer.get('eta_source'),'road_estimate')
  self.assertEqual((peer.get('route_latitude'),peer.get('route_longitude')),(37.50,127.02))
  self.assertGreaterEqual(peer.get('eta_age_ms',-1),4000)
  self.assertLess(peer['eta_age_ms'],5000)
  self.assertGreaterEqual(peer['remaining_seconds'],899)
  self.sync(self.a,remaining_seconds=None,route_name=None,eta_source=None,
            route_latitude=None,route_longitude=None,eta_age_ms=None)
  peer=self.sync(self.b)['companions'][0]
  for field in ('remaining_seconds','route_name','eta_source','route_latitude','route_longitude','eta_age_ms'):
   self.assertNotIn(field,peer)

 def test_malformed_eta_metadata_is_rejected_before_presence_changes(self):
  cases=[dict(eta_source='invented'),dict(eta_source=[]),dict(route_latitude=37.5),
         dict(route_latitude=True,route_longitude=127),dict(route_latitude=0,route_longitude=0),
         dict(eta_age_ms=True),dict(eta_age_ms=-1),dict(eta_age_ms=60001)]
  for metadata in cases:
   with self.subTest(metadata=metadata):
    self.assertEqual(self.call('/sync',dict(after=0,remaining_seconds=300,**metadata),self.a)[0],400)

 def test_stationary_road_duration_does_not_count_down_between_polls(self):
  for source,expected in [('road_estimate',2),('pleos',0)]:
   with self.subTest(source=source):
    self.sync(self.a,remaining_seconds=2,route_name='가까운 목적지',eta_source=source,eta_age_ms=0)
    with server.lock:
     room=server.rooms[self.a['code']]
     room.presence[self.a['token']]['last_seen']=100
     room.presence[self.a['token']]['eta_observed_at']=100
     observed=server.Handler.updates(room,self.b['token'],0,105)
    peer=observed['companions'][0]
    self.assertEqual(peer['remaining_seconds'],expected)
    self.assertEqual(peer['eta_age_ms'],5000)

if __name__=='__main__':unittest.main()
