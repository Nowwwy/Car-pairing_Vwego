"""Voice routing adapter; recognition stays in Pleos, interpretation on the PC."""
import json
import math
import os
import re
import time
from pathlib import Path
from report_composer import compose_report, context_names
from route_context import needs_route_context, recent_change, MAX_CONTEXT_AGE
from voice_requests import interpret, ground_name, recommend, ModelUnavailable, index, compact

def parking_problem(text):
    if re.search(r'만차.{0,4}아니|없.{0,6}아니|없지\s*않|꽉\s*차지\s*않',text):return False
    return bool(re.search(r'만차|(?:주차|차\s*(?:댈|세울|놓을)).*(?:없|못|꽉|가득|힘들|어렵|안\s*보)',text))

def explicit_current(text):
    return bool(re.search(r'현재\s*위치|지금\s*차\s*있는|여기|이\s*근처',text))

def parking_scope(text):
    return 'current' if explicit_current(text) else 'destination'


def distance_meters(lat, lon, place):
    a,b,c,d=map(math.radians,(lat,lon,place['latitude'],place['longitude']))
    h=math.sin((c-a)/2)**2+math.cos(a)*math.cos(c)*math.sin((d-b)/2)**2
    return round(6371000*2*math.asin(math.sqrt(min(1,h))))


def rule_intent(text):
    normalized=compact(text)
    if normalized in {'취소','취소해줘','그대로가','그대로가자','현재경로유지','이대로가자'}:
        return 'keep','any','current','',''
    # Negated or ambiguous commands must never become a navigation change.
    if re.search(r'말고|가지\s*마|바꾸지\s*마|아니',text):
        return 'clarify','any','current','',''
    category=next((kind for word,kind in [('카페','cafe'),('커피','cafe'),('밥','restaurant'),('식당','restaurant'),('주차','parking'),('주유','fuel'),('충전','charging_station'),('휴게소','rest')] if word in text),'any')
    generic={'주차장','공영주차장','유료주차장','무료주차장','카페','식당','커피','휴게소','주유소','충전소'}
    names=[(key,p['name'],kind) for key,p,kind in index() if len(key)>=3 and key not in generic and key in normalized and kind!='other' and (category=='any' or category==kind)]
    scope='destination' if re.search(r'(목적지|도착지).*?(주변|근처)',text) else 'current'
    if category=='parking':scope=parking_scope(text)
    action='waypoint' if re.search(r'들르|들러|마시고|먹고|쉬었다|경유',text) else 'recommend'
    if action!='waypoint' and re.search(r'경로\s*변경|목적지로|바꿔|변경해|안내해|가자|가줘',text):action='destination'
    query=''
    if names:
        _,query,category=max(names,key=lambda row:len(row[0]))
        scope='named'
        if action!='waypoint' and re.search(r'가자|가줘|바꿔|변경|안내',text):action='destination'
    if category=='any' and not query:action='clarify'
    return action,category,scope,query,''


def unified_intent(text):
    """Conservative offline fallback; the local model handles natural paraphrases."""
    if re.search(r'(일행|뒤차|앞차|차량).*(전달|알려|전해|말해)|전달해|전해줘|말해줘',text):
        return 'report','any','current','',''
    if parking_problem(text):return 'recommend','parking',parking_scope(text),'',''
    intent=rule_intent(text)
    if intent[0]=='keep':return intent
    request=re.search(r'추천|찾아|찾을|검색|안내|경로|변경|바꿔|가자|가줘|들르|들러|경유|마시고|먹고|쉬었다|알려줘|어디|있을까|없나',text)
    if request:return intent
    if re.search(r'막혀|막히|정체|늦|도착했|만차|문.*닫|주차.*(?:없|꽉|가득)|고장|사고|출발했',text):
        return 'report','any','current','',''
    if re.search(r'배고|출출|졸려|쉬고|커피',text):
        category='restaurant' if re.search(r'배고|출출',text) else 'cafe' if '커피' in text else 'rest'
        return 'waypoint',category,'current','',''
    return 'clarify','any','current','',''


class VoiceApiMixin:
    def voice_choice(self,data):
        from voice_choices import resolve_choice
        with self.runtime.lock:
            room,_=self.member(data.get("code"))
            if room is None:return 401,{"error":"모임 연결을 확인해 주세요."}
        return 200,resolve_choice(data)

    def voice_request(self,data):
        s=self.runtime
        with s.lock:
            room,token=self.member(data.get('code'))
            if room is None:return 401,{'error':'모임 연결을 확인해 주세요.'}
            target=room.coordinates
            confirmed_change=recent_change(room.events,token)
            changed=next((e for e in reversed(room.events) if e.get('author')==token and e.get('kind') in {'destination_changed','destination_applied','parking_applied'} and 0<=time.time()-e.get('confirmed_at',0)<=MAX_CONTEXT_AGE and e.get('place')),None)
            original_name=room.destination
        text,previous=data.get('text'),data.get('previous','')
        if not isinstance(text,str) or not 1<=len(text.strip())<=240 or not isinstance(previous,str) or len(previous)>240:raise ValueError
        points=[]
        for key in ('current','destination'):
            p=data.get(key)
            if p is not None and (not isinstance(p,list) or len(p)!=2 or not s.coordinates_valid(p[0],p[1],85) or p==[0,0]):raise ValueError
            points.append(tuple(p) if p is not None else None)
        allow_report=data.get('allow_report',False)
        if not isinstance(allow_report,bool):raise ValueError
        destination_name=data.get('destination_name','')
        if not isinstance(destination_name,str) or len(destination_name)>160:raise ValueError
        observed_destination,observed_waypoints=context_names(data.get('report_context'))
        active=points[1]
        if active is not None:
            active_name=destination_name.strip() or observed_destination
        elif changed:
            place=changed['place'];active=(place['latitude'],place['longitude']);active_name=place['name']
        else:active=target;active_name=original_name
        context={'destination':{'name':active_name,'coordinates':active},'current':points[0],'waypoints':observed_waypoints}
        started=time.monotonic()
        source='rule'
        intent=unified_intent(text) if allow_report else rule_intent(text)
        fallback=intent
        explicit_relay=bool(re.search(r'(일행|뒤차|앞차|차량).*(전달|알려|전해|말해)|전달해|전해줘|말해줘',text))
        # Clear situation reports already have an action. Use the model once for
        # composing the report, not again to potentially cancel or reroute it.
        if os.environ.get('CAR_PAIRING_OLLAMA','1').lower() not in {'0','false','off'} and intent[0]!='keep' and not (allow_report and (explicit_relay or intent[0]=='report')):
            try:
                intent=interpret(text,previous,allow_report=allow_report,context=context)
                source='local_model'
                if fallback[1]=='parking' and parking_problem(text) and intent[0] in {'report','keep','clarify'}:
                    intent=fallback;source='rule'
                if fallback[0]=='destination' and intent[0] in ('waypoint','recommend'):
                    intent=('destination',)+intent[1:]
                # Explicit route changes and explicit relays must not be swapped.
                if allow_report and ((fallback[0]=='destination' and intent[0]=='report') or re.search(r'(일행|뒤차|앞차|차량).*(전달|알려|전해|말해)|전달해|전해줘|말해줘',text)):
                    intent=fallback;source='rule'
            except ModelUnavailable:pass
        question=getattr(intent,'question','')
        places=json.loads(Path(__file__).with_name('osm_catalog.json').read_text(encoding='utf-8'))['places']
        intent=ground_name(intent,text,places)
        if intent[1]=='parking' and not intent[3] and not intent[4]:
            intent=(intent[0],intent[1],parking_scope(text) if parking_problem(text) or explicit_current(text) else intent[2],intent[3],intent[4])
        # A failed lot should not be the first recommendation again.
        excluded=[active] if active and intent[1]=='parking' and parking_problem(text) and intent[2]=='destination' else []
        if explicit_current(text) and points[0] is None and intent[0] not in {'report','keep'}:
            result={'action':'clarify','places':[],'basis':'','message':'현재 차량 위치를 확인하지 못했습니다. 목적지 주변에서 찾아드릴까요?'}
        else:result=recommend(intent,points[0],active,places,distance_meters,excluded=excluded)
        if result.get('basis') in {'destination','current'}:
            center=active if result['basis']=='destination' else points[0]
            result['search_center']=list(center) if center else None
        if result.get('places'):
            basis=(active_name+' 주변') if result['basis']=='destination' and active_name else '현재 차량 주변' if result['basis']=='current' else '요청하신 지역'
            label='대체 주차장' if intent[1]=='parking' else '장소'
            result['message']=f'{basis}에서 {label} 후보를 찾았습니다. 원하는 곳을 선택해 주세요.'
        elif result['action']=='clarify' and not result.get('message'):
            result['message']=question or '어떤 장소를 원하시나요? 현재 위치 주변인지 목적지 주변인지 함께 말씀해 주세요.'
        elif result['action'] not in {'keep','report'} and not result.get('message'):
            result['message']='요청한 범위에서 등록된 장소를 찾지 못했습니다. 다른 지역이나 장소 종류를 말씀해 주세요.'
        result.update(source=source,notice='장소 후보 · 영업/빈자리/가격/조용함 등 상세 조건 미확인',
                      interpretation={'action':intent[0],'category':intent[1],'scope':intent[2],'query':intent[3],'area':intent[4]})
        if allow_report and result['action']=='report':
            context=data.get('report_context')
            # New apps supply the freshly observed SDK route. Older/temporarily
            # disconnected clients may resolve only their own recent applied event.
            observed_destination, observed_waypoints=context_names(context)
            if not observed_destination and not observed_waypoints and needs_route_context(text) and confirmed_change:
                result.update(action='report',report_text=confirmed_change,report_source='navigation_context',report_reference='route_changed')
            else:
                result.update(compose_report(text,context,budget=max(0,25-(time.monotonic()-started))))
        return 200,result
