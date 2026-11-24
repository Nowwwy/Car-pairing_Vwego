"""Build a named place index from a South Korea OpenStreetMap PBF."""
import argparse
import gzip
import json

import osmium


class Places(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rows = {}

    def add(self, tags, latitude, longitude):
        name = tags.get('name:ko') or tags.get('name')
        if not name or tags.get('access') in {'private', 'no'}:
            return
        amenity = tags.get('amenity')
        if amenity in {'cafe', 'restaurant', 'fuel', 'charging_station'}:
            category = amenity
        elif tags.get('railway') in {'station', 'halt'} or tags.get('public_transport') == 'station':
            category = 'station'
        elif tags.get('tourism') in {'attraction', 'museum', 'gallery', 'viewpoint'} or tags.get('leisure') == 'park':
            category = 'attraction'
        elif tags.get('shop') in {'mall', 'department_store', 'supermarket', 'convenience'}:
            category = 'shop'
        elif not (amenity in {'library', 'hospital', 'theatre', 'townhall'} or tags.get('tourism') == 'hotel'
                  or tags.get('place') in {'city', 'town', 'suburb', 'quarter', 'neighbourhood'}):
            return
        else:
            category = 'other'
        address = tags.get('addr:full') or ' '.join(
            tags.get(key, '') for key in ('addr:street', 'addr:housenumber')).strip()
        key = name, category, round(latitude, 4), round(longitude, 4)
        self.rows.setdefault(key, [name, round(latitude, 6), round(longitude, 6), address, category])

    def node(self, element):
        if element.location.valid():
            self.add(element.tags, element.location.lat, element.location.lon)

    def way(self, element):
        if not (element.tags.get('name') or element.tags.get('name:ko')):
            return
        points = [(node.lat, node.lon) for node in element.nodes if node.location.valid()]
        if points:
            self.add(element.tags, sum(point[0] for point in points) / len(points),
                     sum(point[1] for point in points) / len(points))


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('pbf')
parser.add_argument('output')
args = parser.parse_args()
handler = Places()
handler.apply_file(args.pbf, locations=True, idx='flex_mem')
with gzip.open(args.output, 'wt', encoding='utf-8') as output:
    json.dump(list(handler.rows.values()), output, ensure_ascii=False, separators=(',', ':'))
print(f'{len(handler.rows):,} named places')
