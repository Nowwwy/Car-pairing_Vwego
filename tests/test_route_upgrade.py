import unittest
from unittest.mock import patch
import backend.trip_planner as tp
from tests.test_trip_planner import A,B,S,Response,route

class RouteUpgradeTests(unittest.TestCase):
    def setUp(self):
        tp._cache.clear();tp._last_request=None

    def test_stop_and_dwell_are_in_comparison(self):
        points=[[127,37.5],[127.01,37.505],[127.02,37.51]]
        with patch.object(tp,'urlopen',return_value=Response(route(900,2500,points))) as http:
            try: result=tp.compare_routes(A,B,[S])
            except TypeError: self.fail('route comparison must accept ordered stops')
        self.assertEqual(result['routes'][0]['total_seconds'],1500)
        self.assertEqual(result['dwell_seconds'],600)
        self.assertIn('127.01,37.505',http.call_args.args[0].full_url)

    def test_fastest_and_shortest_are_ranked_from_actual_values(self):
        payload=route(900,2000)
        other=route(600,2500)['routes'][0]
        other['geometry']['coordinates'].insert(1,[127.015,37.506])
        payload['routes'].append(other)
        with patch.object(tp,'urlopen',return_value=Response(payload)):
            result=tp.compare_routes(A,B)
        self.assertEqual(result['routes'][0]['duration_seconds'],600)
        self.assertIn('예상시간 최소',result['routes'][0]['labels'])
        self.assertIn('거리 최소',result['routes'][1]['labels'])
        self.assertEqual(result['routes'][1]['delta_seconds'],300)

    def test_malformed_provider_becomes_unavailable(self):
        for payload in [[],{'code':'Ok','routes':None}, {'code':'Ok','routes':[None]}]:
            tp._cache.clear();tp._last_request=None
            with self.subTest(payload=payload),patch.object(tp,'urlopen',return_value=Response(payload)):
                try:
                    with self.assertRaises(tp.RoutingUnavailable): tp.compare_routes(A,B)
                except (TypeError,AttributeError): self.fail('provider error escaped without a usable error response')

    def test_bad_stop_input_is_rejected(self):
        for stops in [[dict(S,dwell_minutes=True)],[S,S,S],{},[dict(S,latitude=True)]]:
            with self.subTest(stops=stops):
                try:
                    with self.assertRaises(ValueError):tp.compare_routes(A,B,stops)
                except TypeError:self.fail('route comparison does not validate stops')

if __name__=='__main__':unittest.main()
