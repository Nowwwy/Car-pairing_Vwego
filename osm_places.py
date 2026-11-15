"""Real OSM facility snapshot for the Gangnam/Gyeonggi demonstration region.

Fetched from the official OSM API, 2026-11-12. This is a regional offline
fallback, not a national POI database or a parking availability service.
"""
import json
import math
from pathlib import Path


def catalog_places(kind, latitude, longitude, query='휴게소'):
    source = json.loads(Path(__file__).with_name('osm_catalog.json').read_text(encoding='utf-8'))
    result = []
    radius = 1500 if kind == 'parking' else 20000
    for item in source['places']:
        if item['kind'] != kind:
            continue
        if kind == 'rest' and query != '휴게소' and query not in item['name']:
            continue
        distance = math.hypot((item['latitude']-latitude)*111320,
                              (item['longitude']-longitude)*111320*math.cos(math.radians(latitude)))
        if distance > radius:
            continue
        place = {key:item[key] for key in ('name','latitude','longitude','address')}
        # OSM has unnamed parking polygons; expose a unique, truthful label.
        if place['name'] == '주차장':
            place['name'] = '주차장 · OSM ' + item['osm_id'][-5:]
        result.append((distance, place))
    if not result:
        raise OSError('No saved OSM facilities in this demonstration region')
    return [place for _,place in sorted(result,key=lambda pair:pair[0])[:5]]
