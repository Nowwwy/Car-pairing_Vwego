"""Bounded real POI lookup. No provider reports live vacancies or fuel stock.

Kakao is used when configured, followed by two public Overpass mirrors and
finally the dated regional OSM catalog. Empty valid results are authoritative.
"""
import copy
import json
import math
import os
import re
import threading
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from osm_places import catalog_places

MIRRORS = ('https://overpass-api.de/api/interpreter',
           'https://overpass.kumi.systems/api/interpreter')
HTTP_TIMEOUT = 3
CACHE_TTL = 120
MAX_CACHE = 128
MAX_BYTES = 1024 * 1024
_cache = {}
_cache_lock = threading.Lock()


class PlaceUnavailable(OSError):
    def __init__(self, failures):
        super().__init__('장소 검색 서비스가 응답하지 않았습니다.')
        self.failures = failures
        self.provider = 'unavailable'


def clear_cache():
    with _cache_lock:
        _cache.clear()


def _coordinates(latitude, longitude):
    return all(isinstance(value, (int, float)) and not isinstance(value, bool)
               and math.isfinite(value) and abs(value) <= bound
               for value, bound in ((latitude, 90), (longitude, 180)))


def _load(request):
    with urlopen(request, timeout=HTTP_TIMEOUT) as response:
        raw = response.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError('oversized provider response')
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError('invalid provider response')
    return value


def _place(name, latitude, longitude, address=''):
    if not _coordinates(latitude, longitude):
        raise ValueError('invalid place coordinates')
    if not isinstance(name, str) or not name.strip() or len(name) > 200:
        raise ValueError('invalid place name')
    if not isinstance(address, str) or len(address) > 500:
        raise ValueError('invalid place address')
    return dict(name=name, latitude=latitude, longitude=longitude, address=address)


def _kakao(kind, latitude, longitude, query, key):
    params = dict(x=longitude, y=latitude, radius=1500 if kind == 'parking' else 20000,
                  sort='distance', size=5)
    endpoint = 'keyword' if kind == 'rest' else 'category'
    params.update({'query': query} if kind == 'rest' else
                  {'category_group_code': 'PK6' if kind == 'parking' else 'OL7'})
    request = Request('https://dapi.kakao.com/v2/local/search/' + endpoint + '.json?' + urlencode(params),
                      headers={'Authorization': 'KakaoAK ' + key})
    documents = _load(request).get('documents')
    if not isinstance(documents, list):
        raise ValueError('missing documents')
    result = []
    for item in documents:
        if not isinstance(item, dict):
            continue
        if kind in {'parking', 'fuel'} and item.get('category_group_code') != ('PK6' if kind == 'parking' else 'OL7'):
            continue
        try:
            result.append(_place(item['place_name'], float(item['y']), float(item['x']),
                                 item.get('road_address_name') or item.get('address_name', '')))
        except (KeyError, ValueError, TypeError, OverflowError):
            continue
    if documents and not result:
        raise ValueError('all provider places malformed')
    return result[:5]


def _overpass(endpoint, kind, latitude, longitude, query):
    radius = 1500 if kind == 'parking' else 20000
    selector = {'parking': '["amenity"="parking"]', 'fuel': '["amenity"="fuel"]',
                'rest': '["highway"~"^(services|rest_area)$"]'}[kind]
    if kind == 'rest' and query != '휴게소':
        selector += '["name"~' + json.dumps(re.escape(query), ensure_ascii=False) + ']'
    ql = f'[out:json][timeout:2];nwr{selector}(around:{radius},{latitude},{longitude});out center tags 40;'
    request = Request(endpoint, data=urlencode({'data': ql}).encode(),
                      headers={'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'CarPairing/2.0'})
    payload = _load(request)
    elements = payload.get('elements')
    if not isinstance(elements, list) or payload.get('remark'):
        raise ValueError('incomplete Overpass result')
    result = []
    for item in elements:
        if not isinstance(item, dict):
            continue
        tags = item.get('tags')
        if not isinstance(tags, dict):
            continue
        if (kind in {'parking', 'fuel'} and tags.get('amenity') != kind or
                kind == 'rest' and tags.get('highway') not in {'services', 'rest_area'}):
            continue
        if kind == 'rest' and query != '휴게소' and query not in tags.get('name', ''):
            continue
        point = item.get('center', item)
        if not isinstance(point, dict):
            continue
        # A generic facility type is truthful, never an invented business name.
        name = tags.get('name') or {'parking': '주차장', 'rest': '휴게소', 'fuel': '주유소'}[kind]
        address = tags.get('addr:full') or ' '.join(tags[k] for k in
                  ('addr:city', 'addr:street', 'addr:housenumber') if isinstance(tags.get(k), str))
        try:
            result.append(_place(name, point.get('lat'), point.get('lon'), address))
        except (ValueError, TypeError):
            continue
    if elements and not result:
        raise ValueError('no valid matching provider facilities')
    result.sort(key=lambda p: math.hypot(p['latitude']-latitude,
                (p['longitude']-longitude)*math.cos(math.radians(latitude))))
    return result[:5]


def find_places(kind, latitude, longitude, query='휴게소'):
    if kind not in {'parking', 'rest', 'fuel'}:
        raise ValueError('unsupported place kind')
    if not _coordinates(latitude, longitude):
        raise ValueError('invalid search coordinates')
    if (not isinstance(query, str) or not 1 <= len(query.strip()) <= 80 or
            any(ord(char) < 32 for char in query)):
        raise ValueError('query must contain 1 to 80 characters')
    query = query.strip()
    key = os.environ.get('KAKAO_REST_API_KEY', '').strip()
    cache_key = (kind, latitude, longitude, query, bool(key))
    now = time.monotonic()
    with _cache_lock:
        cached = _cache.get(cache_key)
        if cached and now - cached[0] < CACHE_TTL:
            result = copy.deepcopy(cached[1])
            result['cached'] = True
            return result
    failures = []
    attempts = ([('kakao', lambda: _kakao(kind, latitude, longitude, query, key),
                  'https://developers.kakao.com/docs/latest/ko/local/dev-guide')] if key else [])
    attempts += [('osm-overpass', lambda endpoint=endpoint: _overpass(endpoint, kind, latitude, longitude, query), endpoint)
                 for endpoint in MIRRORS]
    attempts += [('osm-catalog', lambda: catalog_places(kind, latitude, longitude, query),
                  'https://www.openstreetmap.org')]
    for provider, fetch, source in attempts:
        try:
            found = fetch()
        except (OSError, ValueError, KeyError, TypeError, OverflowError) as error:
            # Do not expose keys, URLs with credentials, or raw upstream response text.
            failures.append({'provider': provider, 'reason': type(error).__name__})
            continue
        notice = {'parking': '주차장의 실제 빈자리는 알 수 없습니다.',
                  'rest': '휴게소의 현재 주행 방향 및 진입 가능 여부는 보장되지 않습니다.',
                  'fuel': '주유소의 현재 영업 여부, 연료 종류 및 재고는 확인되지 않았습니다.'}[kind]
        provenance = {'source': source, 'live_availability': False}
        if provider.startswith('osm'):
            provenance.update(license='ODbL-1.0', attribution='© OpenStreetMap contributors')
        if provider == 'osm-catalog':
            date='2026-11-13' if kind=='fuel' else '2026-11-12'
            provenance.update(retrieved=date, coverage='Gangnam parking and fuel; selected Gyeonggi rest areas')
            notice += f' 저장된 지역 한정 시설 정보입니다({date}).'
        result = dict(places=found, provider=provider, availability_notice=notice,
                      provenance=provenance, failures=failures, cached=False)
        with _cache_lock:
            if len(_cache) >= MAX_CACHE:
                _cache.pop(next(iter(_cache)))
            _cache[cache_key] = (time.monotonic(), copy.deepcopy(result))
        return result
    raise PlaceUnavailable(failures)
