"""Ground companion readouts in recent, app-confirmed navigation events."""
import re
import time

APPLIED_KINDS={'destination_applied','destination_changed','parking_applied','rest_applied'}
MAX_CONTEXT_AGE=30*60
RELAY_END=r'(?:전달해\s*줘|전해\s*줘|알려\s*줘|말해\s*줘|전달해\s*주세요|알려\s*주세요)[.!?\s]*$'

def is_relay(text):
    return bool(re.search(RELAY_END,text))

def relay_core(text):
    core=re.sub(r'^(?:우리\s*)?(?:일행|뒤차|앞차|후발대|선발대)(?:에게|한테|에)\s*','',text.strip())
    return re.sub(RELAY_END,'',core).strip()

def needs_route_context(text):
    if not is_relay(text):return False
    core=re.sub(r'\s+','',relay_core(text))
    return bool(re.fullmatch(r'(?:내가)?(?:방금|지금|아까)?(?:경로|목적지)(?:를)?(?:변경했|바꿨)(?:다고|다|어|어요)?',core))

def completion_text(kind,place,dwell=0,stop_kind="rest"):
    message=(f"{place['name']}을 경유지로 추가했습니다." if kind=='rest_applied'
            else f"목적지를 {place['name']}(으)로 변경했습니다.")
    if dwell:
        purpose='주차' if kind=='parking_applied' else '주유' if stop_kind=='fuel' else '정차'
        message+=f" {purpose} 예정 시간은 {dwell}분입니다."
    return message

def recent_change(events,author):
    for event in reversed(events):
        if event.get('author')!=author or event.get('kind') not in APPLIED_KINDS:continue
        age=time.time()-event.get('confirmed_at',0)
        if not 0<=age<=MAX_CONTEXT_AGE or not event.get('place'):return None
        return completion_text(event['kind'],event['place'],event.get('dwell_minutes',0),event.get('stop_kind','rest'))
    return None
