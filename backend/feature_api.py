"""Authenticated optional app features. Network estimates never hold the room lock."""
import math
from time import monotonic

FEATURE_PATHS=frozenset(('/profile','/preferences','/choices','/history/clear','/parking/save','/parking/list','/parking/delete','/leader','/leave','/discovery','/nearby','/meeting/save','/meetings','/meeting/delete','/destination','/trip/estimate','/trip/alternatives'))

def point_valid(s,point):
 return isinstance(point,dict) and s.coordinates_valid(point.get('latitude'),point.get('longitude'),85) and (point['latitude'],point['longitude'])!=(0,0)

class FeatureApiMixin:
 def feature_post(self,data):
  s=self.runtime
  if self.path=='/nearby':
   if not point_valid(s,data) or type(data.get('location_age_ms')) is not int or not 0<=data['location_age_ms']<=20000:raise ValueError('fresh position required')
   results=[]
   with s.lock:
    for code,room in s.rooms.items():
     presence=room.presence.get(room.leader,{})
     if not room.discoverable or monotonic()-presence.get('last_seen',0)>15 or monotonic()-presence.get('located_at',0)>20:continue
     distance=math.hypot((data['latitude']-presence['latitude'])*111320,(data['longitude']-presence['longitude'])*111320*math.cos(math.radians(data['latitude'])))
     if distance<=3000:results.append(dict(code=code,destination=room.destination,distance_meters=round(distance),members_count=len(room.members)))
   return 200,dict(rooms=sorted(results,key=lambda r:r['distance_meters'])[:20])
  with s.lock:
   room,token=self.member(data.get('code'))
   if room is None:return 401,{'error':'모임 연결을 확인해 주세요.'}
   client=room.clients[token]
   store=s.STORE
   target=dict(name=room.destination,latitude=room.coordinates[0],longitude=room.coordinates[1],address='') if room.coordinates else None
  if self.path=='/trip/alternatives':
   from backend.trip_planner import compare_routes
   origin=data.get('origin');destination=data.get('destination') or target
   if not point_valid(s,origin) or not point_valid(s,destination):raise ValueError
   try:return 200,compare_routes(origin,destination,data.get("stops",[]))
   except s.RoutingUnavailable as e:return 502,{'error':str(e),'traffic_included':False}
  if self.path=='/trip/estimate':
   origin=data.get('origin');destination=data.get('destination') or target
   if not point_valid(s,origin) or not point_valid(s,destination):raise ValueError('origin and destination required')
   stops=data.get('stops',[]);profile=data.get('profile','car')
   if not isinstance(stops,list) or len(stops)>2:raise ValueError('too many stops')
   destination_dwell=data.get('destination_dwell_minutes',0)
   if type(destination_dwell) is not int or not 0<=destination_dwell<=240:raise ValueError('invalid destination dwell')
   try:
    result=s.estimate_trip(origin,destination,stops,profile)
    result['dwell_seconds']+=destination_dwell*60;result['total_seconds']+=destination_dwell*60;result['added_seconds']+=destination_dwell*60
    different=target is not None and (abs(target['latitude']-destination['latitude'])>0.00001 or abs(target['longitude']-destination['longitude'])>0.00001)
    if different and profile=='car':
     baseline=s.estimate_trip(origin,target,[],profile)
     result['base']=baseline['base'];result['added_seconds']=result['total_seconds']-baseline['base']['duration_seconds']
    if data.get('include_walk') is True and different and profile=='car':
     try:
      walk=s.estimate_trip(destination,target,[],'foot')
      result['walking_seconds']=walk['total_seconds'];result['door_to_door_seconds']=result['total_seconds']+walk['total_seconds'];result['walking_geometry']=walk['via']['geometry']
     except s.RoutingUnavailable:result['walking_notice']='도보 경로는 계산하지 못했습니다.'
    return 200,result
   except s.RoutingUnavailable as e:return 502,{'error':str(e),'provider':'unavailable','traffic_included':False}
  if self.path in {'/leader','/leave','/discovery','/destination'}:
   with s.lock:
    # Membership may have changed while waiting for this second lock.
    room,token=self.member(data.get('code'))
    if room is None:return 401,{'error':'모임 연결을 확인해 주세요.'}
    if self.path=='/leave':
     room.members.pop(token);room.clients.pop(token,None);room.presence.pop(token,None)
     room.requests={k:v for k,v in room.requests.items() if k[0]!=token}
     if not room.members:
      s.rooms.pop(data['code'],None)
      if store:store.delete_room(data['code'])
     else:
      if room.leader==token:room.leader=next((t for t in room.members if monotonic()-room.presence[t]['last_seen']<15),next(iter(room.members)))
      s.persist_room(data['code'],room)
     return 200,{'left':True}
    if token!=room.leader:return 403,{'error':'현재 리더만 변경할 수 있습니다.'}
    if self.path=='/leader':
     selected=next((t for t,p in room.presence.items() if p['id']==data.get('member_id') and monotonic()-p['last_seen']<15),None)
     if selected is None:return 400,{'error':'연결된 차량을 선택해 주세요.'}
     room.leader=selected
    elif self.path=='/discovery':
     if type(data.get('enabled')) is not bool:raise ValueError
     room.discoverable=data['enabled']
    else:
     place=s.validate_place(data.get('place'))
     if place is None or not point_valid(s,place):raise ValueError
     if len(room.events)>=2000:return 409,{'error':'메시지 한도에 도달했습니다.'}
     room.destination=place['name'];room.coordinates=(place['latitude'],place['longitude'])
     room.events.append(dict(id=len(room.events)+1,sender=room.members[token],kind='destination',text=place['name']+' 목적지를 제안했습니다.',original=place['name'],source='user',place=place,author=token))
    s.persist_room(data['code'],room)
    return 200,{'updated':True,'leader_id':room.presence[room.leader]['id']}
  if store is None:return 503,{'error':'저장 기능을 사용할 수 없습니다.'}
  if self.path=='/profile':return 200,store.get_profile(client)
  if self.path=='/preferences':
   for key in ('learning_enabled','plate'):
    if key in data and data[key] is None:raise ValueError
   return 200,store.update_profile(client,data.get('learning_enabled'),data.get('plate'))
  if self.path=='/choices':
   dwell=data.get('dwell_minutes',0)
   if type(dwell) is not int or not 0<=dwell<=240:raise ValueError
   recorded=store.record_choice(client,data.get('kind'),data.get('place'),data.get('accepted'),dwell,data.get('request_id'))
   return 200,dict(recorded=recorded)
  if self.path=='/history/clear':store.clear_history(client);return 200,{'cleared':True}
  if self.path=='/parking/save':return 200,store.save_parking(client,data)
  if self.path=='/parking/list':return 200,dict(records=store.list_parking(client))
  if self.path=='/parking/delete':
   return (200,{'deleted':True}) if store.delete_parking(client,data.get('id')) else (404,{'error':'내 주차 기록을 찾을 수 없습니다.'})
  if self.path=='/meeting/save':
   if target is None:return 400,{'error':'공유 목적지가 필요합니다.'}
   return 200,store.save_meeting(client,data.get('name',room.destination),target)
  if self.path=='/meetings':return 200,dict(meetings=store.list_meetings(client))
  if self.path=='/meeting/delete':
   return (200,{'deleted':True}) if store.delete_meeting(client,data.get('id')) else (404,{'error':'내 모임 기록을 찾을 수 없습니다.'})
  return 404,{'error':'지원하지 않는 기능입니다.'}
