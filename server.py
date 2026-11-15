"""Local Car Pairing room and message server. Run with Python 3.10+."""

from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
import secrets
import re
import sys
from threading import Lock
from time import monotonic
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen
from message_intent import classify
from route_context import APPLIED_KINDS, completion_text
import time
from osm_places import catalog_places
from journey_store import JourneyStore
from trip_planner import estimate_trip, RoutingUnavailable
from places_service import find_places as lookup_places, PlaceUnavailable
from feature_api import FeatureApiMixin, FEATURE_PATHS
from voice_api import VoiceApiMixin


@dataclass
class Room:
    destination: str
    coordinates: tuple[float, float] | None = None
    members: dict[str, str] = field(default_factory=dict)
    events: list[dict] = field(default_factory=list)
    presence: dict[str, dict] = field(default_factory=dict)
    requests: dict[tuple[str, str], tuple[str, dict]] = field(default_factory=dict)
    clients: dict[str,str] = field(default_factory=dict)
    leader: str = ""
    discoverable: bool = False


rooms: dict[str, Room] = {}
lock = Lock()
MESSAGE_KINDS = {"message", "parking_applied", "rest_applied", "destination_applied", "destination_changed"}
STORE = None

def persist_room(code,room):
    if STORE is None:return
    snapshot=dict(destination=room.destination,coordinates=room.coordinates,members=room.members,clients=room.clients,leader=room.leader,discoverable=room.discoverable,events=room.events,requests=[[t,r,f,v] for (t,r),(f,v) in room.requests.items()],presence={t:{"id":v["id"],"arrived":v.get("arrived",False)} for t,v in room.presence.items()})
    STORE.save_room(code,snapshot)

def init_storage(path):
    global STORE
    STORE=JourneyStore(path)
    rooms.clear()
    for code,value in STORE.load_rooms().items():
        room=Room(destination=value["destination"],coordinates=tuple(value["coordinates"]) if value.get("coordinates") else None,members=value["members"],clients=value["clients"],leader=value["leader"],discoverable=value.get("discoverable",False),events=value.get("events",[]))
        room.requests={(t,r):(f,v) for t,r,f,v in value.get("requests",[])}
        room.presence={t:dict(v,last_seen=monotonic()-60) for t,v in value["presence"].items()}
        rooms[code]=room


def coordinates_valid(latitude, longitude, limit=90):
    return (type(latitude) in (int, float) and type(longitude) in (int, float)
            and -limit <= latitude <= limit and -180 <= longitude <= 180
            and math.isfinite(latitude) and math.isfinite(longitude))


def validate_place(value):
    if value is None:
        return None
    if not isinstance(value, dict) or not coordinates_valid(value.get('latitude'), value.get('longitude')):
        raise ValueError
    if not isinstance(value.get('name'), str) or not 1 <= len(value['name'].strip()) <= 120:
        raise ValueError
    address = value.get('address', '')
    if not isinstance(address, str) or len(address) > 300:
        raise ValueError
    return dict(name=value['name'].strip(), latitude=value['latitude'], longitude=value['longitude'], address=address)


class Handler(VoiceApiMixin, FeatureApiMixin, BaseHTTPRequestHandler):
    def reply(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def read_payload(self):
        self.connection.settimeout(5)
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= 4096:
            raise ValueError
        body = json.loads(self.rfile.read(size))
        if not isinstance(body, dict):
            raise ValueError
        return body

    def do_POST(self):
        try:
            data = self.read_payload()
            if self.path in FEATURE_PATHS:
                status,response=self.feature_post(data)
            elif self.path == "/voice-choice":
                status, response = self.voice_choice(data)
            elif self.path == "/voice-request":
                status, response = self.voice_request(data)
            elif self.path == "/places":
                status, response = self.find_places(data)
            elif self.path == "/messages":
                status, response = self.send_message(data)
            else:
                with lock:
                    status, response = self.post(data)
        except (ValueError, TypeError, OSError, OverflowError):
            status, response = 400, {"error": "요청 형식을 확인해 주세요."}
        self.reply(status, response)

    def post(self, data):
        if self.path in {"/rooms", "/join"}:
            name = data.get("name")
            if not isinstance(name, str) or not 1 <= len(name.strip()) <= 24:
                return 400, {"error": "이름은 1~24자로 입력해 주세요."}
            client=data.get("client_id") or secrets.token_urlsafe(24)
            if not isinstance(client,str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}",client):raise ValueError
            plate=data.get("plate","")
            if not isinstance(plate,str) or len(plate)>24:raise ValueError
            if self.path == "/rooms":
                destination = data.get("destination")
                if not isinstance(destination, str) or not 1 <= len(destination.strip()) <= 40:
                    return 400, {"error": "목적지는 1~40자로 입력해 주세요."}
                if len(rooms) >= 100:
                    return 503, {"error": "시연 서버의 모임 한도에 도달했습니다."}
                latitude, longitude = data.get("latitude"), data.get("longitude")
                coordinates = None
                if latitude is not None or longitude is not None:
                    if not coordinates_valid(latitude, longitude):
                        return 400, {"error": "목적지 좌표를 확인해 주세요."}
                    coordinates = (latitude, longitude)
                code = f"{secrets.randbelow(1_000_000):06d}"
                while code in rooms:
                    code = f"{secrets.randbelow(1_000_000):06d}"
                rooms[code] = Room(destination.strip(), coordinates)
            else:
                code = data.get("code")
            if not isinstance(code, str) or code not in rooms:
                return 404, {"error": "모임 코드를 확인해 주세요."}
            room = rooms[code]
            if STORE and plate:STORE.update_profile(client,plate=plate)
            if len(room.members) >= 16:
                return 409, {"error": "이 모임은 최대 16대까지 참여할 수 있습니다."}
            token = secrets.token_urlsafe(24)
            room.members[token] = name.strip()
            room.clients[token] = client
            if not room.leader:room.leader=token
            room.presence[token] = {"id": secrets.token_hex(8), "last_seen": monotonic()}
            result = {"code": code, "token": token, "destination": room.destination,
                      "cursor": len(room.events), "members": list(room.members.values()), "is_leader":token==room.leader,"member_id":room.presence[token]["id"]}
            if room.coordinates is not None:
                result["latitude"], result["longitude"] = room.coordinates
            persist_room(code,room)
            return 200, result
        if self.path == "/sync":
            room, token = self.member(data.get("code"))
            if room is None:
                return 401, {"error": "모임 연결이 만료됐습니다. 다시 참여해 주세요."}
            cursor = data.get("after")
            if type(cursor) is not int or not 0 <= cursor <= len(room.events):
                return 400, {"error": "메시지 위치를 확인해 주세요."}
            if "arrived" in data and type(data["arrived"]) is not bool:
                return 400, {"error": "도착 여부는 참/거짓으로 입력해 주세요."}
            latitude, longitude = data.get("latitude"), data.get("longitude")
            age, remaining = data.get("location_age_ms"), data.get("remaining_seconds")
            if any(value is not None for value in (latitude, longitude, age)):
                if (not coordinates_valid(latitude, longitude, 85)
                        or (latitude, longitude) == (0, 0)
                        or type(age) is not int or not 0 <= age <= 20_000):
                    return 400, {"error": "차량 위치 형식을 확인해 주세요."}
            if remaining is not None and (type(remaining) is not int or not 0 <= remaining <= 604_800):
                return 400, {"error": "예상 도착시간 형식을 확인해 주세요."}
            route_name = data.get("route_name")
            if route_name is not None and (not isinstance(route_name, str) or not 1 <= len(route_name.strip()) <= 80):
                return 400, {"error": "안내 목적지 이름은 1~80자로 입력해 주세요."}
            eta_source, eta_age = data.get("eta_source"), data.get("eta_age_ms")
            route_latitude, route_longitude = data.get("route_latitude"), data.get("route_longitude")
            if eta_source is not None and (not isinstance(eta_source, str) or eta_source not in {"road_estimate", "pleos"}):
                return 400, {"error": "예상 도착시간 출처를 확인해 주세요."}
            if eta_age is not None and (type(eta_age) is not int or not 0 <= eta_age <= 60_000):
                return 400, {"error": "예상 도착시간 갱신 시각을 확인해 주세요."}
            if route_latitude is not None or route_longitude is not None:
                if not coordinates_valid(route_latitude, route_longitude, 85) or (route_latitude, route_longitude) == (0, 0):
                    return 400, {"error": "안내 목적지 좌표를 확인해 주세요."}
            now = monotonic()
            presence = room.presence[token]
            presence["last_seen"] = now
            if "arrived" in data and presence.get("arrived")!=data["arrived"]:
                presence["arrived"] = data["arrived"]
                persist_room(data["code"],room)
            if age is not None:
                presence.update(latitude=latitude, longitude=longitude, located_at=now - age / 1000)
            presence["remaining_seconds"] = remaining
            presence["route_name"] = route_name.strip() if route_name is not None and remaining is not None else None
            # Road estimates are sampled travel durations; only native navigation
            # provides a countdown. A heartbeat must not renew either sample's age.
            presence["eta_source"] = eta_source if remaining is not None else None
            presence["eta_observed_at"] = now - eta_age / 1000 if remaining is not None and eta_age is not None else None
            presence["route_latitude"] = route_latitude if remaining is not None else None
            presence["route_longitude"] = route_longitude if remaining is not None else None
            return 200, self.updates(room, token, cursor, now)
        return 404, {"error": "요청 주소를 확인해 주세요."}

    @staticmethod
    def public_event(event):
        return {key: value for key, value in event.items() if key != 'author'}

    def send_message(self, data):
        kind, text = data.get('kind'), data.get('text')
        if not isinstance(kind, str) or kind not in MESSAGE_KINDS:
            return 400, {"error": "지원하지 않는 메시지입니다."}
        if not isinstance(text, str) or not 1 <= len(text.strip()) <= 500:
            return 400, {"error": "메시지는 1~500자로 입력해 주세요."}
        text = text.strip()
        place = validate_place(data.get('place'))
        if kind in APPLIED_KINDS and place is None:
            return 400, {'error':'안내 적용을 확인한 장소가 필요합니다.'}
        request_id = data.get('request_id')
        if request_id is not None and (not isinstance(request_id, str) or not 1 <= len(request_id) <= 128):
            return 400, {"error": "요청 식별자를 확인해 주세요."}
        dwell=data.get('dwell_minutes',0)
        stop_kind=data.get('stop_kind','rest')
        if type(dwell) is not int or not 0<=dwell<=240 or stop_kind not in {'rest','fuel','parking'}:raise ValueError
        applied_at_ms=data.get('applied_at_ms')
        if applied_at_ms is not None and (type(applied_at_ms) is not int or not 0<applied_at_ms<=(time.time()+60)*1000):
            raise ValueError('invalid application time')
        fingerprint_fields=[kind,text,place,stop_kind,dwell]
        if applied_at_ms is not None:fingerprint_fields.append(applied_at_ms)
        fingerprint = json.dumps(fingerprint_fields, sort_keys=True, ensure_ascii=False)
        with lock:
            room, token = self.member(data.get('code'))
            if room is None:
                return 401, {"error": "모임 연결이 만료됐습니다. 다시 참여해 주세요."}
            previous = room.requests.get((token, request_id)) if request_id is not None else None
            if previous:
                return (200, previous[1]) if previous[0] == fingerprint else (409, {"error": "같은 요청 식별자의 내용이 다릅니다."})
        # Local inference may take time; never hold the shared room lock here.
        if kind in APPLIED_KINDS:
            detected, readout, source = kind, completion_text(kind,place,dwell,stop_kind), 'navigation_context'
        elif kind == 'message' and place is not None:
            # Selecting a concrete facility is an explicit rest-stop invitation.
            detected, readout, source = 'rest', text, 'user'
        else:
            detected, readout, source = classify(text) if kind == 'message' else (kind, text, 'user')
        with lock:
            if rooms.get(data.get("code")) is not room or token not in room.members:return 401,{"error":"모임 연결이 만료됐습니다."}
            previous = room.requests.get((token, request_id)) if request_id is not None else None
            if previous:
                return (200, previous[1]) if previous[0] == fingerprint else (409, {"error": "같은 요청 식별자의 내용이 다릅니다."})
            if len(room.events) >= 2000:
                return 409, {"error": "이 모임의 시연 메시지 한도에 도달했습니다."}
            event = dict(id=len(room.events) + 1, sender=room.members[token], kind=detected,
                         text=readout, original=text, source=source, author=token,stop_kind=stop_kind,dwell_minutes=dwell)
            if kind in APPLIED_KINDS:event['confirmed_at']=applied_at_ms/1000 if applied_at_ms is not None else time.time()
            if place is not None:
                event['place'] = place
            room.events.append(event)
            result = dict(sent=True, event=self.public_event(event), source=source)
            if request_id is not None:
                room.requests[(token, request_id)] = (fingerprint, result)
            persist_room(data["code"],room)
            return 200, result

    def find_places(self, data):
        with lock:
            room,token=self.member(data.get('code'))
            if room is None:return 401,{'error':'모임 연결을 확인해 주세요.'}
            center=room.coordinates
            client=room.clients[token]
        latitude,longitude=data.get('latitude'),data.get('longitude')
        if latitude is None and longitude is None and center:latitude,longitude=center
        if not coordinates_valid(latitude,longitude):raise ValueError
        kind=data.get('kind')
        try:
            result=lookup_places(kind,latitude,longitude,data.get('query','휴게소'))
        except PlaceUnavailable as e:return 502,{'error':str(e),'provider':e.provider,'failures':e.failures}
        if STORE:result['places']=STORE.rank_places(client,kind,result['places'])
        return 200,result

    def member(self, code):
        token = self.headers.get("Authorization", "").removeprefix("Bearer ")
        room = rooms.get(code) if isinstance(code, str) else None
        return (room, token) if room is not None and token in room.members else (None, token)

    @staticmethod
    def updates(room, token, cursor, now):
        batch = room.events[cursor:cursor + 50]
        events = [
            Handler.public_event(event)
            for event in batch if event["author"] != token
            and not (room.presence[token].get("arrived", False) and event["kind"] in {"parking", "rest"})
        ]
        companions = []
        for key, member in room.presence.items():
            if key == token:
                continue
            seen_age = max(0, now - member["last_seen"])
            if seen_age >= 15:
                continue
            companion = {"id": member["id"], "name": room.members[key], "seen_age_ms": int(seen_age * 1000), "arrived": member.get("arrived", False), "leader":key==room.leader,"plate":STORE.get_profile(room.clients[key])["plate"] if STORE else ""}
            if "located_at" in member and now - member["located_at"] <= 20:
                companion.update(latitude=member["latitude"], longitude=member["longitude"],
                                 location_age_ms=max(0, int((now - member["located_at"]) * 1000)))
            remaining = member.get("remaining_seconds")
            eta_observed_at = member.get("eta_observed_at")
            eta_age = max(0, now - eta_observed_at) if eta_observed_at is not None else None
            if remaining is not None and seen_age < 15 and (eta_age is None or eta_age < 60):
                companion["remaining_seconds"] = remaining if member.get("eta_source") == "road_estimate" else max(0, remaining - int(seen_age))
                if member.get("route_name") is not None:
                    companion["route_name"] = member["route_name"]
                for field in ("eta_source", "route_latitude", "route_longitude"):
                    if member.get(field) is not None:
                        companion[field] = member[field]
                if eta_age is not None:
                    companion["eta_age_ms"] = int(eta_age * 1000)
            companions.append(companion)
        return {"cursor": cursor + len(batch), "events": events,
                "members": list(room.members.values()), "companions": companions,
                "leader_id":room.presence[room.leader]["id"] if room.leader else "",
                "shared_place":dict(name=room.destination,latitude=room.coordinates[0],longitude=room.coordinates[1],address="") if room.coordinates else None}

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/health":
            self.reply(200, {"service": "car-pairing", "version": "3.4-context", "status": "ok"})
            return
        if url.path != "/events":
            self.reply(404, {"error": "요청 주소를 확인해 주세요."})
            return
        query = parse_qs(url.query)
        try:
            cursor = int(query.get("after", ["0"])[0])
            if cursor < 0:
                raise ValueError
        except ValueError:
            self.reply(400, {"error": "메시지 위치를 확인해 주세요."})
            return
        with lock:
            room, token = self.member(query.get("code", [""])[0])
            if room is None:
                status, payload = 401, {"error": "모임 연결이 만료됐습니다. 다시 참여해 주세요."}
            elif cursor > len(room.events):
                status, payload = 400, {"error": "메시지 위치를 확인해 주세요."}
            else:
                status, payload = 200, self.updates(room, token, cursor, monotonic())
        self.reply(status, payload)

    def log_message(self, format, *args):
        if args and ("GET /events?" in args[0] or "POST /sync " in args[0]) and str(args[1]) == "200":
            return
        super().log_message(format, *args)


Handler.runtime=sys.modules[__name__]

if __name__ == "__main__":
    init_storage(os.environ.get("CAR_PAIRING_DB", os.path.join(os.path.dirname(__file__),"carpairing.sqlite3")))
    host = os.environ.get("CAR_PAIRING_BIND", "127.0.0.1")
    port = int(os.environ.get("CAR_PAIRING_PORT", "8765"))
    with ThreadingHTTPServer((host, port), Handler) as server:
        print(f"Car Pairing room server · {host}:{port}", flush=True)
        server.serve_forever()
