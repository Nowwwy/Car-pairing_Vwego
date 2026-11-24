"""Optional live-provider smoke test; never writes room tokens to the report."""
import json,urllib.request,datetime
from pathlib import Path
BASE='http://127.0.0.1:8765'
def post(path,body,token=None):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 with urllib.request.urlopen(urllib.request.Request(BASE+path,json.dumps(body).encode(),headers),timeout=90) as r:return json.load(r)
def main():
 room=post('/rooms',dict(name='검증용',client_id='live-smoke',destination='강남역 2번 출구',latitude=37.49721,longitude=127.02805))
 def call(path,**body):return post(path,dict(code=room['code'],**body),room['token'])
 results={'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 try:
  origin=dict(latitude=37.395,longitude=127.110)
  stop=dict(name='성원주유소',latitude=37.5049538,longitude=127.0252514,dwell_minutes=10,kind='fuel')
  result=call('/trip/estimate',origin=origin,stops=[stop])
  results['fuel_plan']={k:v for k,v in result.items() if k not in ('base','via')}
  results['fuel_plan']['base_seconds']=result['base']['duration_seconds'];results['fuel_plan']['drive_seconds']=result['via']['duration_seconds']
  walk=call('/trip/estimate',origin=origin,destination=dict(name='주차 위치',latitude=37.49631,longitude=127.03154),stops=[],include_walk=True,destination_dwell_minutes=5)
  results['parking_walk']={k:walk[k] for k in ('walking_seconds','door_to_door_seconds','total_seconds','added_seconds','traffic_included','provider') if k in walk}
  places=call('/places',kind='fuel',latitude=37.49721,longitude=127.02805)
  results['fuel_places']={'provider':places.get('provider'),'count':len(places['places']),'first_name':places['places'][0]['name'] if places['places'] else None}
 finally:call('/leave')
 Path('LIVE_PROVIDER_VALIDATION.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps(results,ensure_ascii=True))
if __name__=='__main__':main()
