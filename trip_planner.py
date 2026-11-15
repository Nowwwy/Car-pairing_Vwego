"""Small OSRM road-estimate adapter. Estimates exclude live traffic.

Public FOSSGIS policy: https://routing.openstreetmap.de/about.html
One process-wide network lock limits all providers to one request per second.
Multiple server processes must use a shared external rate limiter or private router.
ROUTING_CAR_URL/ROUTING_FOOT_URL accept endpoint bases or {coordinates} templates;
ROUTING_CAR_FALLBACK_URL optionally enables a car-only secondary endpoint.
"""
from collections import OrderedDict
import copy
from http.client import HTTPException
import json
import math
import os
import threading
import time
from urllib.error import URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


class RoutingUnavailable(Exception):
    """A configured road provider cannot produce a trustworthy estimate."""


_cache = OrderedDict()
_network_lock = threading.Lock()
_last_request = None
_CACHE_TTL = 60
_CACHE_LIMIT = 256
_MAX_BODY = 5_000_000
_DEFAULTS = {
    'car': 'https://routing.openstreetmap.de/routed-car/route/v1/driving',
    'foot': 'https://routing.openstreetmap.de/routed-foot/route/v1/driving',
}


def _number(value):
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _point(value):
    if not isinstance(value, dict):
        raise ValueError('Each location must be an object with latitude and longitude')
    lat, lon = value.get('latitude'), value.get('longitude')
    if not _number(lat) or not _number(lon) or not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise ValueError('Location coordinates must be finite and within latitude/longitude bounds')
    return (float(lon), float(lat))


def _coordinate(value):
    return (isinstance(value, (list, tuple)) and len(value) == 2
            and _number(value[0]) and _number(value[1])
            and -180 <= value[0] <= 180 and -90 <= value[1] <= 90)


def _distance(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, (*a, *b))
    h = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
    return 6371000 * 2 * math.asin(math.sqrt(min(1, h)))


def _parse(payload, points, max_snap):
    if not isinstance(payload, dict) or payload.get('code') != 'Ok':
        raise RoutingUnavailable('Road provider returned no route or a non-Ok response')
    routes, waypoints = payload.get('routes'), payload.get('waypoints')
    if not isinstance(routes, list) or not routes or not isinstance(routes[0], dict):
        raise RoutingUnavailable('Road provider returned no usable route')
    if not isinstance(waypoints, list) or len(waypoints) != len(points):
        raise RoutingUnavailable('Road provider returned malformed snapped waypoints')
    for waypoint, point in zip(waypoints, points):
        if not isinstance(waypoint, dict):
            raise RoutingUnavailable('Road provider returned malformed snapped waypoint')
        snap, location = waypoint.get('distance'), waypoint.get('location')
        if not _number(snap) or not 0 <= snap <= max_snap or not _coordinate(location) or _distance(point, location) > max_snap:
            raise RoutingUnavailable('No nearby routable road within the allowed snap distance')
    route = routes[0]
    duration, distance, geometry = route.get('duration'), route.get('distance'), route.get('geometry')
    if not _number(duration) or duration < 0 or not _number(distance) or distance < 0:
        raise RoutingUnavailable('Road provider returned invalid duration or distance')
    if not isinstance(geometry, dict) or geometry.get('type') != 'LineString':
        raise RoutingUnavailable('Road provider returned invalid route geometry')
    coordinates = geometry.get('coordinates')
    if not isinstance(coordinates, list) or not 2 <= len(coordinates) <= 100_000 or not all(_coordinate(p) for p in coordinates):
        raise RoutingUnavailable('Road provider returned malformed route coordinates')
    if _distance(coordinates[0], waypoints[0]['location']) > max_snap or _distance(coordinates[-1], waypoints[-1]['location']) > max_snap:
        raise RoutingUnavailable('Road provider geometry does not match snapped endpoints')
    return {'duration_seconds': duration, 'distance_meters': distance, 'geometry': coordinates}


def _configuration(profile):
    primary = os.environ.get('ROUTING_' + profile.upper() + '_URL', _DEFAULTS[profile]).strip()
    fallback = os.environ.get('ROUTING_CAR_FALLBACK_URL', '').strip() if profile == 'car' else ''
    endpoints = list(dict.fromkeys(p for p in (primary, fallback) if p))
    if not endpoints:
        raise RoutingUnavailable('No routing endpoint configured')
    for endpoint in endpoints:
        parsed = urlsplit(endpoint)
        if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise RoutingUnavailable('Routing endpoint must be an HTTP(S) base URL without credentials or query')
    try:
        max_snap = float(os.environ.get('ROUTING_MAX_SNAP_METERS', '500'))
        if not math.isfinite(max_snap) or not 0 < max_snap <= 2000:
            raise ValueError()
    except ValueError as exc:
        raise RoutingUnavailable('ROUTING_MAX_SNAP_METERS must be in (0, 2000]') from exc
    return endpoints, max_snap


def _road(points, endpoints, max_snap, alternatives=False):
    global _last_request
    coords = ';'.join(f'{lon:.7f}'.rstrip('0').rstrip('.') + ',' + f'{lat:.7f}'.rstrip('0').rstrip('.') for lon, lat in points)
    key = (tuple(endpoints), tuple(points), max_snap, alternatives)
    # This lock is exclusively for routing HTTP/cache; callers never need a room lock.
    with _network_lock:
        now = time.monotonic()
        cached = _cache.get(key)
        if cached and now - cached[0] < _CACHE_TTL:
            _cache.move_to_end(key)
            return copy.deepcopy(cached[1])
        errors = []
        for endpoint in endpoints:
            if _last_request is not None:
                pause = 1.0 - (time.monotonic() - _last_request)
                if pause > 0:
                    time.sleep(pause)
            _last_request = time.monotonic()
            url = endpoint.replace('{coordinates}', coords) if '{coordinates}' in endpoint else endpoint.rstrip('/') + '/' + coords
            url += '?' + urlencode({'overview': 'full', 'geometries': 'geojson', 'steps': 'false', 'alternatives': 'true' if alternatives else 'false'})
            request = Request(url, headers={'User-Agent': 'CarPairing/1.0 (road estimate; no live traffic)', 'Accept': 'application/json'})
            try:
                with urlopen(request, timeout=12) as response:
                    if response.status != 200:
                        raise RoutingUnavailable('Road provider HTTP status ' + str(response.status))
                    raw = response.read(_MAX_BODY + 1)
                if len(raw) > _MAX_BODY:
                    raise RoutingUnavailable('Road provider response exceeds size limit')
                payload = json.loads(raw)
                if alternatives:
                    if not isinstance(payload,dict) or not isinstance(payload.get('routes'),list):
                        raise RoutingUnavailable('Road provider returned malformed alternatives')
                    parsed=[]
                    for candidate in payload['routes'][:3]:
                        try:
                            row=_parse(dict(payload, routes=[candidate]), points, max_snap)
                        except RoutingUnavailable:
                            continue
                        if not any(old['geometry']==row['geometry'] for old in parsed):parsed.append(row)
                    if not parsed:raise RoutingUnavailable('No route alternatives available')
                    result=(parsed,endpoint)
                else:
                    result = (_parse(payload, points, max_snap), endpoint)
            except (URLError, OSError, HTTPException, ValueError, RoutingUnavailable) as exc:
                # Include provider identity, never arbitrary HTML or response bodies.
                reason = str(exc) if isinstance(exc, RoutingUnavailable) else type(exc).__name__
                errors.append(f'{urlsplit(endpoint).netloc}: {reason}')
                continue
            _cache[key] = (time.monotonic(), result)
            _cache.move_to_end(key)
            while len(_cache) > _CACHE_LIMIT:
                _cache.popitem(last=False)
            return copy.deepcopy(result)
        raise RoutingUnavailable('Road estimate unavailable (' + '; '.join(errors) + '); try again later')


def estimate_trip(origin: dict, destination: dict, stops: list, profile='car') -> dict:
    """Estimate base and ordered-stop road routes plus explicit stop dwell time.

    Invalid user input raises ValueError; provider/configuration failures raise
    RoutingUnavailable. No geometry or ETA is fabricated on failure.
    """
    if profile not in ('car', 'foot'):
        raise ValueError('profile must be car or foot')
    start, end = _point(origin), _point(destination)
    if not isinstance(stops, list) or len(stops) > 2:
        raise ValueError('stops must be a list with at most two stops')
    points, dwell = [start], 0
    for stop in stops:
        points.append(_point(stop))
        minutes = stop.get('dwell_minutes', 0)
        if not isinstance(minutes, int) or isinstance(minutes, bool) or not 0 <= minutes <= 240:
            raise ValueError('dwell_minutes must be an integer from 0 to 240')
        dwell += minutes * 60
    points.append(end)
    endpoints, max_snap = _configuration(profile)
    base, base_provider = _road([start, end], endpoints, max_snap)
    via, via_provider = _road(points, endpoints, max_snap) if stops else (copy.deepcopy(base), base_provider)
    total = via['duration_seconds'] + dwell
    providers = list(dict.fromkeys([base_provider, via_provider]))
    return {'base': base, 'via': via, 'dwell_seconds': dwell,
            'total_seconds': total, 'added_seconds': total - base['duration_seconds'],
            'provider': ', '.join(providers), 'traffic_included': False,
            'notice': 'External road estimate; no live traffic. Routing: ' + ', '.join(providers)
                      + '. Map data © OpenStreetMap contributors (https://www.openstreetmap.org/copyright). '
                        'Fix the map: https://www.openstreetmap.org/fixthemap . '
                        'Native navigation may choose a different route.'}


def compare_routes(origin, destination, stops=None):
    """Compare provider routes through accepted stops; never fabricate alternatives."""
    points=[_point(origin)]
    stops=[] if stops is None else stops
    if not isinstance(stops,list) or len(stops)>2:
        raise ValueError('stops must be a list with at most two stops')
    dwell=0
    for stop in stops:
        points.append(_point(stop))
        minutes=stop.get('dwell_minutes',0)
        if type(minutes) is not int or not 0<=minutes<=240:
            raise ValueError('dwell_minutes must be an integer from 0 to 240')
        dwell+=minutes*60
    points.append(_point(destination))
    endpoints,max_snap=_configuration('car')
    routes,provider=_road(points,endpoints,max_snap,alternatives=True)
    routes.sort(key=lambda r:(r['duration_seconds'],r['distance_meters']))
    shortest=min(r['distance_meters'] for r in routes)
    fastest=routes[0]['duration_seconds']
    for row in routes:
        row.update(total_seconds=row['duration_seconds']+dwell,
                   delta_seconds=row['duration_seconds']-fastest,
                   labels=(["예상시간 최소"] if row['duration_seconds']==fastest else [])+
                          (["거리 최소"] if row['distance_meters']==shortest else []))
    return {'routes':routes,'provider':provider,'traffic_included':False,
            'dwell_seconds':dwell,'stops_included':len(stops),
            'notice':f'도로 경로 비교 · 수락한 경유지 {len(stops)}곳 포함 · 실시간 교통 미반영 · Pleos 실제 안내 경로와 다를 수 있음 · © OpenStreetMap contributors'}
