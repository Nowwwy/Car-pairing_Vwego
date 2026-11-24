from tests.test_trip_planner import TripPlannerTests,Response,route,A,B
from unittest.mock import patch
import backend.trip_planner as tp
class Alternatives(TripPlannerTests):
 def test_multiple_valid_routes_and_cache_separation(self):
  payload=route();second=route(720,2300)['routes'][0];second['geometry']['coordinates'].insert(1,[127.01,37.504]);payload['routes'].append(second)
  self.assertTrue(callable(getattr(tp,'compare_routes',None)))
  with patch.object(tp,'urlopen',return_value=Response(payload)) as http:
   result=tp.compare_routes(A,B)
  self.assertEqual(len(result['routes']),2)
  self.assertFalse(result['traffic_included'])
  self.assertIn('alternatives=true',http.call_args.args[0].full_url)
