"""Deterministic OSRM boundary tests; public HTTP and time never leave this process."""
import io
from http.client import IncompleteRead
import json
import unittest
from unittest.mock import patch
from urllib.error import URLError

import backend.trip_planner as tp

A = {'latitude': 37.5, 'longitude': 127.0}
B = {'latitude': 37.51, 'longitude': 127.02}
S = {'latitude': 37.505, 'longitude': 127.01, 'dwell_minutes': 10}


def route(duration=600, distance=2000, points=None):
    points = points or [[127, 37.5], [127.02, 37.51]]
    return {'code': 'Ok', 'routes': [{'duration': duration, 'distance': distance,
            'geometry': {'type': 'LineString', 'coordinates': points}}],
            'waypoints': [{'distance': 3, 'location': p, 'name': ''} for p in points]}


class Response(io.BytesIO):
    status = 200
    def __init__(self, data):
        super().__init__(json.dumps(data).encode())


class TripPlannerTests(unittest.TestCase):
    def setUp(self):
        tp._cache.clear()
        tp._last_request = None
        self.now = 100.0
        self.waits = []
        self.clock = patch.object(tp.time, 'monotonic', side_effect=lambda: self.now)
        def advance(seconds):
            self.waits.append(seconds)
            self.now += seconds
        self.sleep = patch.object(tp.time, 'sleep', side_effect=advance)
        self.clock.start()
        self.sleep.start()
        self.env = patch.dict('os.environ', {}, clear=True)
        self.env.start()
        self.addCleanup(self.clock.stop)
        self.addCleanup(self.sleep.stop)
        self.addCleanup(self.env.stop)

    def test_road_durations_and_dwell_are_added_exactly(self):
        via = [[127, 37.5], [127.01, 37.505], [127.02, 37.51]]
        with patch.object(tp, 'urlopen', side_effect=[Response(route()), Response(route(900, 2500, via))]) as http:
            result = tp.estimate_trip(A, B, [S])
        self.assertEqual(result['base']['duration_seconds'], 600)
        self.assertEqual(result['via']['geometry'], via)
        self.assertEqual(result['dwell_seconds'], 600)
        self.assertEqual(result['total_seconds'], 1500)
        self.assertEqual(result['added_seconds'], 900)
        self.assertFalse(result['traffic_included'])
        self.assertIn('OpenStreetMap', result['notice'])
        self.assertIn('routing.openstreetmap.de', result['provider'])
        self.assertIn('127,37.5;127.01,37.505;127.02,37.51', http.call_args.args[0].full_url)
        self.assertEqual(self.waits, [1.0])

    def test_identical_routes_cached_and_returned_without_shared_mutation(self):
        with patch.object(tp, 'urlopen', side_effect=lambda *a, **k: Response(route())) as http:
            first = tp.estimate_trip(A, B, [])
            first['base']['geometry'][0][0] = 0
            second = tp.estimate_trip(A, B, [])
            self.assertEqual(second['base']['geometry'][0], [127, 37.5])
            self.assertEqual(http.call_count, 1)
            self.now += 61
            tp.estimate_trip(A, B, [])
            self.assertEqual(http.call_count, 2)

    def test_recent_road_cache_survives_outage_but_expired_eta_is_not_reused(self):
        with patch.object(tp, 'urlopen', return_value=Response(route())):
            first = tp.estimate_trip(A, B, [])
        with patch.object(tp, 'urlopen', side_effect=URLError('offline')) as http:
            self.now += 30
            cached = tp.estimate_trip(A, B, [])
            self.assertEqual(cached['base'], first['base'])
            http.assert_not_called()
            self.now += 31
            with self.assertRaises(tp.RoutingUnavailable):
                tp.estimate_trip(A, B, [])
            self.assertEqual(http.call_count, 1)

    def test_failed_requests_are_still_globally_rate_limited(self):
        with patch.object(tp, 'urlopen', side_effect=URLError('offline')) as http:
            for profile in ('car', 'foot', 'car'):
                with self.assertRaises(tp.RoutingUnavailable):
                    tp.estimate_trip(A, B, [], profile)
            self.assertEqual(http.call_count, 3)
        self.assertEqual(self.waits, [1.0, 1.0])

    def test_negative_driving_difference_is_preserved(self):
        with patch.object(tp, 'urlopen', side_effect=[Response(route(600)), Response(route(500, points=[[127,37.5],[127.01,37.505],[127.02,37.51]]))]):
            result = tp.estimate_trip(A, B, [{**S, 'dwell_minutes': 0}])
        self.assertEqual(result['added_seconds'], -100)

    def test_input_errors_never_call_provider(self):
        invalid = [({'latitude': float('nan'), 'longitude': 127}, B, [], 'car'),
                   ({'latitude': True, 'longitude': 127}, B, [], 'car'),
                   (A, {'latitude': 91, 'longitude': 0}, [], 'car'),
                   (A, B, [S, S, S], 'car'), (A, B, [{**S, 'dwell_minutes': 241}], 'car'),
                   (A, B, [{**S, 'dwell_minutes': 1.5}], 'car'),
                   (A, B, [{**S, 'dwell_minutes': True}], 'car'),
                   ({'latitude': 10**1000, 'longitude': 127}, B, [], 'car'),
                   (A, B, [], 'bike'), (A, B, None, 'car')]
        with patch.object(tp, 'urlopen') as http:
            for args in invalid:
                with self.subTest(args=args), self.assertRaises(ValueError):
                    tp.estimate_trip(*args)
            http.assert_not_called()

    def test_no_route_network_and_malformed_are_explicit_errors(self):
        bad = [ {'code': 'NoRoute', 'routes': []}, {},
               {**route(), 'routes': []},
               {**route(), 'waypoints': [{'distance': 4000}, {'distance': 1}]},
               {**route(), 'waypoints': []} ]
        for field, value in [('duration', float('nan')), ('distance', -1), ('geometry', {'type': 'LineString','coordinates': [[181,0],[127,37]]})]:
            value_route = route()
            value_route['routes'][0][field] = value
            bad.append(value_route)
        for payload in bad:
            with self.subTest(payload=payload), patch.object(tp, 'urlopen', return_value=Response(payload)), self.assertRaises(tp.RoutingUnavailable):
                tp.estimate_trip(A, B, [])
        with patch.object(tp, 'urlopen', side_effect=URLError('offline')), self.assertRaises(tp.RoutingUnavailable):
            tp.estimate_trip(A, B, [])

    def test_foot_uses_foot_service_and_configured_endpoints_work(self):
        with patch.object(tp, 'urlopen', side_effect=lambda *a, **k: Response(route())) as http:
            result = tp.estimate_trip(A, B, [], 'foot')
            self.assertIn('/routed-foot/route/v1/driving/', http.call_args.args[0].full_url)
            self.assertIn('routed-foot', result['provider'])
        with patch.dict('os.environ', {'ROUTING_CAR_URL': 'https://example.test/route/{coordinates}'}):
            with patch.object(tp, 'urlopen', return_value=Response(route())) as http:
                tp.estimate_trip(A, B, [])
                self.assertTrue(http.call_args.args[0].full_url.startswith('https://example.test/route/127,'))

    def test_coordinate_precision_is_preserved_in_request(self):
        origin = {'latitude': 37.5001234, 'longitude': 127.0001234}
        fixture = route(points=[[127.0001234, 37.5001234], [127.02, 37.51]])
        with patch.object(tp, 'urlopen', return_value=Response(fixture)) as http:
            tp.estimate_trip(origin, B, [])
        self.assertIn('127.0001234,37.5001234;', http.call_args.args[0].full_url)

    def test_cache_is_bounded_and_evicted_routes_are_refetched(self):
        with patch.object(tp, 'urlopen', side_effect=lambda *a, **k: Response(route())) as http:
            for index in range(257):
                tp.estimate_trip({'latitude': 37.5 + index * 0.000001, 'longitude': 127}, B, [])
            tp.estimate_trip(A, B, [])
        self.assertEqual(http.call_count, 258)
        self.assertLessEqual(len(tp._cache), 256)

    def test_provider_failure_explains_no_route(self):
        with patch.object(tp, 'urlopen', return_value=Response({'code': 'NoRoute'})):
            with self.assertRaisesRegex(tp.RoutingUnavailable, 'no route'):
                tp.estimate_trip(A, B, [])

    def test_interrupted_http_body_is_routing_unavailable(self):
        with patch.object(tp, 'urlopen', side_effect=IncompleteRead(b'{', 300)):
            with self.assertRaises(tp.RoutingUnavailable):
                tp.estimate_trip(A, B, [])

    def test_http_errors_and_invalid_json_do_not_become_estimates(self):
        bad_http = Response(route())
        bad_http.status = 503
        for response in (bad_http, io.BytesIO(b'not json')):
            if not hasattr(response, 'status'):
                response.status = 200
            with patch.object(tp, 'urlopen', return_value=response), self.assertRaises(tp.RoutingUnavailable):
                tp.estimate_trip(A, B, [])

    def test_fallback_is_opt_in_car_only_and_rate_limited(self):
        with patch.dict('os.environ', {'ROUTING_CAR_FALLBACK_URL': 'https://router.project-osrm.org/route/v1/driving'}):
            with patch.object(tp, 'urlopen', side_effect=[URLError('offline'), Response(route())]):
                result = tp.estimate_trip(A, B, [])
                self.assertIn('router.project-osrm.org', result['provider'])
                self.assertEqual(self.waits, [1.0])
            with patch.object(tp, 'urlopen', side_effect=URLError('offline')) as http, self.assertRaises(tp.RoutingUnavailable):
                tp.estimate_trip(A, B, [], 'foot')
            self.assertEqual(http.call_count, 1)


if __name__ == '__main__':
    unittest.main()
