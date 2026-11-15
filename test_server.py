import json
import os
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.parse import parse_qs
from urllib.error import HTTPError
from unittest.mock import patch
from io import BytesIO
import server
import places_service
import message_intent


class ServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:%s' % cls.http.server_port

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join()

    def setUp(self):
        places_service.clear_cache()
        with server.lock:
            server.rooms.clear()
        self.env = patch.dict(os.environ, {'CAR_PAIRING_OLLAMA': '0', 'KAKAO_REST_API_KEY': ''})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.a = self.call('/rooms', {'name': '앞차', 'destination': '목적지', 'latitude': 37.5, 'longitude': 127.1})[1]
        self.b = self.call('/join', {'name': '뒤차', 'code': self.a['code']})[1]

    def call(self, path, body=None, member=None):
        headers = {'Content-Type': 'application/json'}
        if member:
            headers['Authorization'] = 'Bearer ' + member['token']
        req = Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            with urlopen(req, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def send(self, text, **kw):
        return self.call('/messages', dict(code=self.a['code'], kind='message', text=text, **kw), self.a)

    def test_health_and_cross_group_auth(self):
        self.assertEqual(self.call('/health')[1]['service'], 'car-pairing')
        other = self.call('/rooms', {'name': '외부', 'destination': '다른 곳'})[1]
        self.assertEqual(self.call('/messages', {'code': self.a['code'], 'kind': 'message', 'text': '안녕'}, other)[0], 401)
        self.assertEqual(self.call('/places', {'code': self.a['code'], 'kind': 'rest'}, other)[0], 401)
        self.assertEqual(self.call('/sync', {'code':self.a['code'], 'after':0}, other)[0], 401)
        self.assertEqual(self.call('/events?code='+self.a['code']+'&after=0', member=other)[0], 401)

    def test_delay_and_negation_and_retry(self):
        status, result = self.send('차가 막혀서 약 15분 늦을 것 같아', request_id='retry-1')
        self.assertEqual(status, 200)
        self.assertEqual(result['event']['kind'], 'delay')
        self.assertIn('15분', result['event']['text'])
        self.assertIn('같아', result['event']['text'])
        self.assertEqual(result['source'], 'rule')
        self.assertEqual(self.send('차가 막혀서 약 15분 늦을 것 같아', request_id='retry-1')[1]['event']['id'], result['event']['id'])
        self.assertEqual(len(server.rooms[self.a['code']].events), 1)
        self.assertEqual(self.send('내용이 달라짐', request_id='retry-1')[0], 409)
        self.assertEqual(self.send('주차장이 만차는 아니야')[1]['event']['kind'], 'message')
        self.assertEqual(self.send('주차장이 꽉 차지 않았어')[1]['event']['kind'], 'message')
        self.assertEqual(self.send('주차장이 만차야')[1]['event']['kind'], 'parking')

    def test_arrived_filter_preserves_cursor_and_delay(self):
        self.send('주차장이 만차야')
        self.send('휴게소 들르자')
        self.send('10분 늦어')
        status, result = self.call('/sync', {'code': self.b['code'], 'after': 0, 'arrived': True}, self.b)
        self.assertEqual(status, 200)
        self.assertEqual(result['cursor'], 3)
        self.assertEqual([e['kind'] for e in result['events']], ['delay'])
        self.assertEqual([e['kind'] for e in self.call('/events?code='+self.b['code']+'&after=0', member=self.b)[1]['events']], ['delay'])
        self.assertEqual(self.call('/sync', {'code': self.b['code'], 'after': 3, 'arrived': 'yes'}, self.b)[0], 400)

    def test_place_propagation_and_validation(self):
        place = {'name': '실제휴게소', 'latitude': 37.2, 'longitude': 127.3, 'address': '주소'}
        status, result = self.send('여기 휴게소에서 쉬자', place=place)
        self.assertEqual(status, 200)
        self.assertEqual(result['event']['place'], place)
        received = self.call('/events?code='+self.b['code']+'&after=0', member=self.b)[1]['events'][0]
        self.assertEqual(received['place'], place)
        selected = self.send('덕평', place=place)[1]['event']
        self.assertEqual(selected['kind'], 'rest')
        self.assertEqual(selected['source'], 'user')
        for bad in [float('nan'), float('inf'), True, '37']:
            self.assertEqual(self.send('휴게소 들르자', place=dict(place, latitude=bad))[0], 400)
        self.assertEqual(self.send('a'*500)[0], 200)
        self.assertEqual(self.send('a'*501)[0], 400)
        self.assertEqual(self.call('/messages', {'code': self.a['code'], 'kind': 'rest_applied', 'text': '적용했어요', 'place': place}, self.a)[1]['event']['kind'], 'rest_applied')

    def test_osm_real_places_and_query(self):
        result = {'elements': [{'type':'node', 'id':1, 'lat':37.4, 'lon':127.2, 'tags':{'name':'테스트휴게소', 'highway':'services'}}]}
        with patch.object(places_service, 'urlopen', return_value=BytesIO(json.dumps(result).encode())) as request:
            status, payload = self.call('/places', {'code': self.a['code'], 'kind':'rest', 'query':'테스트'}, self.a)
            self.assertEqual(status, 200)
            self.assertEqual(payload['provider'], 'osm-overpass')
            self.assertEqual(payload['places'][0]['latitude'], 37.4)
            self.assertIn('보장', payload['availability_notice'])
            self.assertIn('테스트', parse_qs(request.call_args.args[0].data.decode())['data'][0])
        with patch.object(places_service, 'urlopen', side_effect=OSError('offline')), patch.object(places_service,'catalog_places',side_effect=OSError('offline')):
            self.assertEqual(self.call('/places', {'code':self.a['code'], 'kind':'parking', 'latitude':37.5, 'longitude':127.1}, self.a)[0], 502)
        self.assertEqual(self.call('/places', {'code':self.a['code'], 'kind':'parking', 'latitude':float('nan'), 'longitude':127.1}, self.a)[0], 400)

    def test_catalog_fallback_and_arrival_status(self):
        place={'name':'실제 주차장','latitude':37.5,'longitude':127.1,'address':'주소'}
        with patch.object(places_service,'urlopen',side_effect=OSError('upstream unavailable')), patch.object(places_service,'catalog_places',return_value=[place]):
            status,payload=self.call('/places',{'code':self.a['code'],'kind':'parking'},self.a)
            self.assertEqual(status,200)
            self.assertEqual(payload['provider'],'osm-catalog')
            self.assertEqual(payload['places'],[place])
        self.call('/sync',{'code':self.a['code'],'after':0,'arrived':True},self.a)
        other=self.call('/sync',{'code':self.b['code'],'after':0},self.b)[1]
        self.assertTrue(other['companions'][0]['arrived'])

    def test_ollama_result_and_network_failure_sources(self):
        with patch.dict(os.environ, {'CAR_PAIRING_OLLAMA': '1'}):
            answer = {'message': {'content': '{"kind":"delay"}'}}
            with patch.object(message_intent, 'urlopen', return_value=BytesIO(json.dumps(answer).encode())):
                result = self.send('아마 12분 정도 늦을 것 같아')[1]
            self.assertEqual(result['source'], 'ollama:qwen3:4b')
            self.assertEqual(result['event']['text'], '아마 12분 정도 늦을 것 같아')
            with patch.object(message_intent, 'urlopen', side_effect=OSError('offline')):
                self.assertEqual(self.send('늦을 것 같아')[1]['source'], 'rule')
            answer = {'message': {'content': '{"kind":"parking"}'}}
            with patch.object(message_intent, 'urlopen', return_value=BytesIO(json.dumps(answer).encode())):
                result = self.send('주차장이 만차가 아니야')[1]
                self.assertEqual(result['event']['kind'], 'message')
                self.assertEqual(result['source'], 'rule')

    def test_inference_does_not_block_room_operations(self):
        entered, release = threading.Event(), threading.Event()
        results = []
        def network(*args, **kwargs):
            entered.set()
            release.wait(3)
            raise OSError('offline')
        with patch.dict(os.environ, {'CAR_PAIRING_OLLAMA': '1'}), patch.object(message_intent, 'urlopen', side_effect=network):
            worker = threading.Thread(target=lambda: results.append(self.send('5분 늦어')))
            worker.start()
            try:
                self.assertTrue(entered.wait(2))
                # This real HTTP mutation also needs the room lock.
                started = server.monotonic()
                status, _ = self.call('/join', {'code':self.a['code'], 'name':'추가차'})
                self.assertEqual(status, 200)
                self.assertLess(server.monotonic() - started, 1)
            finally:
                release.set()
                worker.join(3)
            self.assertEqual(results[0][0], 200)

    def test_presence_ttls_and_nonfinite_destination(self):
        for bad in [float('nan'), float('inf'), -float('inf'), 10**1000]:
            self.assertEqual(self.call('/rooms', {'name':'차', 'destination':'목적지', 'latitude':bad, 'longitude':127})[0], 400)
            self.assertEqual(self.call('/sync', {'code':self.a['code'], 'after':0, 'latitude':bad, 'longitude':127, 'location_age_ms':0}, self.a)[0], 400)
        self.call('/sync', {'code':self.a['code'], 'after':0, 'latitude':37.5, 'longitude':127.1, 'location_age_ms':0}, self.a)
        with server.lock:
            presence = server.rooms[self.a['code']].presence[self.a['token']]
            presence['located_at'] = server.monotonic() - 21
        result = self.call('/sync', {'code':self.b['code'], 'after':0}, self.b)[1]
        self.assertNotIn('latitude', result['companions'][0])
        with server.lock:
            presence['last_seen'] = server.monotonic() - 16
        self.assertEqual(self.call('/sync', {'code':self.b['code'], 'after':0}, self.b)[1]['companions'], [])

    def test_kakao_rest_query_and_parking_metadata(self):
        document = {'documents':[{'place_name':'선택휴게소', 'y':'37.4', 'x':'127.2', 'road_address_name':'주소', 'address_name':'주소'}]}
        with patch.dict(os.environ, {'KAKAO_REST_API_KEY':'test-only'}), patch.object(places_service, 'urlopen', return_value=BytesIO(json.dumps(document).encode())) as request:
            status, result = self.call('/places', {'code':self.a['code'], 'kind':'rest', 'query':'선택휴게소'}, self.a)
            self.assertEqual(status, 200)
            self.assertEqual(result['provider'], 'kakao')
            self.assertEqual(parse_qs(request.call_args.args[0].full_url.split('?')[1])['query'], ['선택휴게소'])
        result = {'elements':[{'lat':37.4, 'lon':127.2, 'tags':{'amenity':'parking'}}]}
        with patch.object(places_service, 'urlopen', return_value=BytesIO(json.dumps(result).encode())):
            status, payload = self.call('/places', {'code':self.a['code'], 'kind':'parking'}, self.a)
            self.assertEqual(status, 200)
            self.assertIn('알 수 없습니다', payload['availability_notice'])
            self.assertEqual(payload['places'][0]['latitude'], 37.4)

    def test_malformed_model_and_additional_negation(self):
        with patch.dict(os.environ, {'CAR_PAIRING_OLLAMA':'1'}), patch.object(message_intent, 'urlopen', return_value=BytesIO(b'{"message":{"content":"[]"}}')):
            self.assertEqual(self.send('5분 늦어')[1]['source'], 'rule')
        for text in ['주차장이 안 꽉 찼어', '주차장 만차 아님', '주차 자리가 있어']:
            self.assertEqual(self.send(text)[1]['event']['kind'], 'message')

    def test_alternative_route_name_tracks_current_eta(self):
        body = dict(code=self.a['code'], after=0, remaining_seconds=180, route_name='합의한 대체 주차장')
        self.assertEqual(self.call('/sync', body, self.a)[0], 200)
        companion = self.call('/sync', {'code':self.b['code'], 'after':0}, self.b)[1]['companions'][0]
        self.assertEqual(companion['route_name'], '합의한 대체 주차장')
        self.assertLessEqual(companion['remaining_seconds'], 180)
        # A fresh heartbeat without a native ETA explicitly clears the pair.
        self.call('/sync', {'code':self.a['code'], 'after':0}, self.a)
        companion = self.call('/sync', {'code':self.b['code'], 'after':0}, self.b)[1]['companions'][0]
        self.assertNotIn('route_name', companion)
        self.assertNotIn('remaining_seconds', companion)
        for bad in [True, 10, [], '', 'x'*81]:
            self.assertEqual(self.call('/sync', dict(body, route_name=bad), self.a)[0], 400)
        self.assertEqual(self.call('/sync', dict(body, route_name=None), self.a)[0], 200)
        companion = self.call('/sync', {'code':self.b['code'], 'after':0}, self.b)[1]['companions'][0]
        self.assertNotIn('route_name', companion)
        self.call('/sync', body, self.a)
        with server.lock:
            server.rooms[self.a['code']].presence[self.a['token']]['last_seen'] = server.monotonic() - 16
        self.assertEqual(self.call('/sync', {'code':self.b['code'], 'after':0}, self.b)[1]['companions'], [])

    def test_configurable_ollama_timeout(self):
        answer = {'message': {'content': '{"kind":"delay"}'}}
        for configured, expected in [('', 8), ('12',12), ('99',20), ('NaN',8), ('inf',8), ('-1',8), ('bad',8)]:
            with patch.dict(os.environ, {'CAR_PAIRING_OLLAMA':'1', 'CAR_PAIRING_OLLAMA_TIMEOUT':configured}), patch.object(message_intent, 'urlopen', return_value=BytesIO(json.dumps(answer).encode())) as network:
                self.assertEqual(self.send('15분 늦을 거 같아')[1]['source'], 'ollama:qwen3:4b')
                self.assertEqual(network.call_args.kwargs['timeout'], expected)

class CatalogTests(unittest.TestCase):
    def test_regional_snapshot_has_real_ids_and_no_global_fabrication(self):
        from osm_places import catalog_places
        self.assertEqual(len(catalog_places('parking',37.49721,127.02805)),5)
        rests=catalog_places('rest',37.395,127.11)
        self.assertTrue(any('성남' in p['name'] for p in rests))
        with self.assertRaises(OSError):
            catalog_places('parking',35.1,129.0)
        with self.assertRaises(OSError):
            catalog_places('rest',37.395,127.11,'존재하지 않는 시설')


if __name__ == '__main__':
    unittest.main()
