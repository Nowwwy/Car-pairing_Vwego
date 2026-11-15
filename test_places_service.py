import io
import json
import os
import unittest
from unittest.mock import patch
import places_service as places


def response(value):
    return io.BytesIO(json.dumps(value).encode())


def element(kind='fuel', name='실제주유소', lat=37.5, lon=127.1):
    return {'type': 'node', 'id': 123, 'lat': lat, 'lon': lon, 'tags': {'amenity': kind, 'name': name}}


class PlaceTests(unittest.TestCase):
    def setUp(self):
        places.clear_cache()
        self.env = patch.dict(os.environ, {'KAKAO_REST_API_KEY': ''})
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_validation_before_network(self):
        with patch.object(places, 'urlopen') as http:
            for args in [('fuel', float('nan'), 127), ('fuel', True, 127), ('fuel', 91, 0), ('bad', 0, 0)]:
                with self.assertRaises(ValueError):
                    places.find_places(*args)
            for query in ['', 'x'*81, '\n휴게소']:
                with self.assertRaises(ValueError):
                    places.find_places('rest', 0, 0, query)
            http.assert_not_called()

    def test_fuel_filters_wrong_tags_and_bad_coordinates(self):
        items = [element('parking'), element(lat=float('nan')), element(), {'tags': 'broken'}]
        with patch.object(places, 'urlopen', return_value=response({'elements': items})) as http:
            result = places.find_places('fuel', 37.5, 127.1)
        self.assertEqual([p['name'] for p in result['places']], ['실제주유소'])
        self.assertEqual(result['provider'], 'osm-overpass')
        self.assertIn('live_availability', result['provenance'])
        self.assertLessEqual(http.call_args.kwargs['timeout'], 4)
        self.assertIn('fuel', http.call_args.args[0].data.decode())

    def test_failure_bounded_and_no_fake_fuel_fallback(self):
        with patch.object(places, 'urlopen', side_effect=OSError('offline')) as http:
            with self.assertRaises(places.PlaceUnavailable) as error:
                places.find_places('fuel', 35.1, 129.0)
        self.assertEqual(http.call_count, 2)
        self.assertEqual(len(error.exception.failures), 3)

    def test_mirror_failure_then_success_and_cache_copy(self):
        with patch.object(places, 'urlopen', side_effect=[ValueError('invalid JSON'), response({'elements': [element()]})]) as http:
            first = places.find_places('fuel', 37.5, 127.1)
            first['places'].clear()
            second = places.find_places('fuel', 37.5, 127.1)
        self.assertEqual(len(second['places']), 1)
        self.assertTrue(second['cached'])
        self.assertEqual(http.call_count, 2)
        self.assertEqual(len(second['failures']), 1)

    def test_malformed_payload_and_catalog_provenance(self):
        with patch.object(places, 'urlopen', return_value=response({'elements': {}})):
            result = places.find_places('parking', 37.49721, 127.02805)
        self.assertEqual(result['provider'], 'osm-catalog')
        self.assertEqual(result['provenance']['retrieved'], '2026-11-12')

    def test_kakao_fuel_category_and_empty_is_truthful(self):
        with patch.dict(os.environ, {'KAKAO_REST_API_KEY': 'secret'}), patch.object(places, 'urlopen', return_value=response({'documents': []})) as http:
            result = places.find_places('fuel', 37.5, 127.1)
        self.assertEqual(result['places'], [])
        self.assertEqual(result['provider'], 'kakao')
        self.assertIn('category_group_code=OL7', http.call_args.args[0].full_url)

    def test_cache_expires(self):
        with patch.object(places.time, 'monotonic', return_value=100), patch.object(places, 'urlopen', return_value=response({'elements': [element()]})):
            places.find_places('fuel', 37.5, 127.1)
        with patch.object(places.time, 'monotonic', return_value=100 + places.CACHE_TTL + 1), patch.object(places, 'urlopen', return_value=response({'elements': []})) as http:
            result = places.find_places('fuel', 37.5, 127.1)
        self.assertFalse(result['cached'])
        self.assertEqual(result['places'], [])
        self.assertEqual(http.call_count, 1)

    def test_oversized_response_fails_closed(self):
        with patch.object(places, 'urlopen', side_effect=lambda *a, **kw: io.BytesIO(b' '*(places.MAX_BYTES+1))):
            with self.assertRaises(places.PlaceUnavailable):
                places.find_places('fuel', 0, 0)

    def test_kakao_fuel_rejects_non_fuel_document(self):
        bad = {'place_name': '식당', 'x': '127.1', 'y': '37.5', 'category_group_code': 'FD6'}
        with patch.dict(os.environ, {'KAKAO_REST_API_KEY': 'secret'}), patch.object(places, 'urlopen', side_effect=[response({'documents': [bad]}), response({'elements': [element()]})]):
            result = places.find_places('fuel', 37.5, 127.1)
        self.assertEqual(result['provider'], 'osm-overpass')
        self.assertEqual(result['places'][0]['name'], '실제주유소')

class RegionalFuelSnapshotTests(unittest.TestCase):
    def test_dated_actual_fuel_snapshot_is_available_during_outage(self):
        places.clear_cache()
        with patch.dict(os.environ, {'KAKAO_REST_API_KEY': ''}), patch.object(places, 'urlopen', side_effect=OSError('offline')):
            result=places.find_places('fuel',37.49721,127.02805)
        self.assertEqual(result['provider'],'osm-catalog')
        self.assertEqual(result['provenance']['retrieved'],'2026-11-13')
        self.assertTrue(result['places'])
        self.assertTrue(any('주유소' in p['name'] for p in result['places']))
