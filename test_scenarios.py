"""Planning doc section 4: real HTTP/SQLite scenario contracts.

Vehicle coordinates and ETA samples are deliberate test inputs, not measurements
from Pleos, a route provider or a microphone. Native route consent is tested by
the Android suite/device run; this suite checks that server events cannot mutate
another vehicle's reported personal route.
"""
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

import server


class ScenarioTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.http.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join()

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.db = Path(temp.name) / 'scenario.sqlite3'
        server.init_storage(self.db)
        self.addCleanup(lambda: setattr(server, 'STORE', None))
        env = patch.dict(os.environ, {'CAR_PAIRING_OLLAMA': '0'})
        env.start()
        self.addCleanup(env.stop)
        self.target = dict(name='성수동 식당', latitude=37.5445, longitude=127.0557, address='')
        self.a = self.request('/rooms', dict(name='지훈', destination=self.target['name'],
                              latitude=self.target['latitude'], longitude=self.target['longitude'],
                              client_id='scenario-host'))[1]
        self.b = self.request('/join', dict(name='민수', code=self.a['code'], client_id='scenario-minsu'))[1]

    def request(self, path, body=None, member=None):
        data = dict(body or {})
        headers = {'Content-Type': 'application/json'}
        if member:
            data.setdefault('code', member['code'])
            headers['Authorization'] = 'Bearer ' + member['token']
        try:
            with urlopen(Request(self.base + path, json.dumps(data).encode(), headers), timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def sync(self, member, **values):
        status, result = self.request('/sync', dict(after=0, **values), member)
        self.assertEqual(status, 200, result)
        return result

    def route(self, member, seconds=900, place=None, **values):
        target = place or self.target
        return self.sync(member, remaining_seconds=seconds, route_name=target['name'],
                         route_latitude=target['latitude'], route_longitude=target['longitude'],
                         eta_source='road_estimate', eta_age_ms=0, **values)

    def send(self, member, text, **values):
        status, result = self.request('/messages', dict(kind='message', text=text, **values), member)
        self.assertEqual(status, 200, result)
        return result['event']

    def four_cars(self):
        c = self.request('/join', dict(name='수연', code=self.a['code'], client_id='scenario-suyeon'))[1]
        d = self.request('/join', dict(name='현우', code=self.a['code'], client_id='scenario-hyunwoo'))[1]
        members = [self.a, self.b, c, d]
        # Pangyo, Incheon, Gangnam, Yongin; four distinct provided samples.
        samples = [(37.395, 127.111, 1500), (37.456, 126.705, 3300),
                   (37.498, 127.028, 1200), (37.241, 127.177, 2700)]
        for member, (lat, lon, seconds) in zip(members, samples):
            self.route(member, seconds, latitude=lat, longitude=lon, location_age_ms=0)
        return members, samples

    def test_a_four_independent_origins_eta_and_tokens(self):
        members, samples = self.four_cars()
        self.assertEqual(len({member['token'] for member in members}), 4)
        self.assertEqual(len({member['member_id'] for member in members}), 4)
        for observer in members:
            status, result = self.request('/sync', dict(after=0), observer)
            self.assertEqual(status, 200)
            self.assertEqual(result['shared_place'], self.target)
            self.assertEqual(set(result['members']), {'지훈', '민수', '수연', '현우'})
            # Refresh this observer's original route after this no-ETA poll.
            index = members.index(observer)
            for peer in result['companions']:
                peer_index = next(i for i, member in enumerate(members) if member['member_id'] == peer['id'])
                lat, lon, seconds = samples[peer_index]
                self.assertEqual((peer['latitude'], peer['longitude']), (lat, lon))
                self.assertGreaterEqual(peer['remaining_seconds'], seconds - 2)
                self.assertLessEqual(peer['remaining_seconds'], seconds)
                self.assertEqual(peer['eta_source'], 'road_estimate')
                self.assertEqual(peer['route_name'], '성수동 식당')
                self.assertNotIn('token', peer)
            lat, lon, seconds = samples[index]
            self.route(observer, seconds, latitude=lat, longitude=lon, location_age_ms=0)

    def test_a_delay_then_parking_offer_only_to_nonarrived_cars(self):
        members, _ = self.four_cars()
        c, d = members[2:]
        delay = self.send(self.b, '나 20분 정도 늦을 것 같아')
        self.assertEqual((delay['kind'], delay['sender']), ('delay', '민수'))
        self.assertEqual(delay['text'], '20분 정도 늦을 것 같습니다.')
        self.assertEqual(delay['original'], '나 20분 정도 늦을 것 같아')
        self.assertEqual(delay['source'], 'rule')
        self.sync(self.a, arrived=True)
        self.sync(c, arrived=True)
        parking = self.send(self.a, '여기 주차장 꽉 찼어')
        self.assertEqual(parking['kind'], 'parking')
        self.assertEqual([e['kind'] for e in self.sync(c)['events']], ['delay'])
        self.assertEqual([e['kind'] for e in self.sync(self.b)['events']], ['parking'])
        self.assertEqual([e['kind'] for e in self.sync(d)['events']], ['delay', 'parking'])
        self.assertEqual(self.sync(c)['cursor'], 2)
        private_parking = dict(name='선택한 주차장', latitude=37.546, longitude=127.052, address='')
        self.route(self.b, 600, private_parking)
        self.request('/messages', dict(kind='parking_applied', text=private_parking['name'],
                     place=private_parking), self.b)
        observed = self.sync(d)
        self.assertEqual(observed['shared_place'], self.target)
        self.assertEqual(observed['events'][-1]['kind'], 'parking_applied')
        minsu = next(peer for peer in observed['companions'] if peer['id'] == self.b['member_id'])
        self.assertEqual(minsu['route_name'], private_parking['name'])
        self.assertEqual(minsu['route_latitude'], private_parking['latitude'])

    def test_b_selected_rest_stop_and_decline_leave_current_destination_intact(self):
        self.route(self.a)
        self.route(self.b, 1100)
        rest = dict(name='선택휴게소', latitude=37.294, longitude=127.397, address='테스트 주소')
        self.send(self.a, '다음 휴게소에서 잠깐 쉬었다 가자', place=rest, dwell_minutes=15)
        received = self.route(self.b, 1100)
        self.assertEqual(received['events'][0]['kind'], 'rest')
        self.assertEqual(received['events'][0]['place'], rest)
        self.assertEqual(received['events'][0]['dwell_minutes'], 15)
        # Continuing to the original destination is a valid no-mutation response.
        self.send(self.b, '휴게소 가지 말자')
        observed = self.route(self.a)
        self.assertEqual(observed['events'][0]['kind'], 'message')
        self.assertEqual(observed['shared_place'], self.target)
        self.assertEqual(observed['companions'][0]['route_name'], self.target['name'])
        self.assertFalse(any(e['kind'].endswith('_applied') for e in observed['events']))
        # An explicit native confirmation is a separate event, never another offer.
        status, ack = self.request('/messages', dict(kind='rest_applied', text=rest['name'], place=rest), self.b)
        self.assertEqual(status, 200)
        self.assertEqual(ack['event']['kind'], 'rest_applied')
        self.assertEqual(self.route(self.a)['events'][-1]['place'], rest)
        self.assertEqual(self.route(self.a)['shared_place'], self.target)

    def test_shared_destination_offer_does_not_overwrite_personal_route(self):
        self.route(self.b)
        new_place = dict(name='새 공동 목적지', latitude=37.752, longitude=128.899, address='')
        self.assertEqual(self.request('/destination', dict(place=new_place), self.b)[0], 403)
        self.assertEqual(self.request('/destination', dict(place=new_place), self.a)[0], 200)
        observed = self.route(self.a)
        self.assertEqual(observed['shared_place'], new_place)
        self.assertEqual(observed['companions'][0]['route_name'], '성수동 식당')
        received = self.route(self.b)
        self.assertEqual(received['events'][0]['kind'], 'destination')
        self.assertEqual(received['events'][0]['place'], new_place)
        self.assertFalse(any(e['kind'].endswith('_applied') for e in received['events']))
        self.assertEqual(self.route(self.a)['companions'][0]['route_latitude'], 37.5445)

    def test_reboot_preserves_leader_and_dedup_but_drops_old_location_and_eta(self):
        original = self.send(self.a, '20분 늦을 것 같아', request_id='durable-once')
        self.route(self.b, latitude=37.456, longitude=126.705, location_age_ms=0)
        self.assertEqual(self.request('/leader', dict(member_id=self.b['member_id']), self.a)[0], 200)
        server.init_storage(self.db)
        restored = self.sync(self.a)
        self.assertEqual(restored['leader_id'], self.b['member_id'])
        self.assertEqual(restored['companions'], [])
        replay = self.send(self.a, '20분 늦을 것 같아', request_id='durable-once')
        self.assertEqual(replay['id'], original['id'])
        received = self.sync(self.b)
        self.assertEqual(len(received['events']), 1)
        peer = self.sync(self.a)['companions'][0]
        self.assertNotIn('latitude', peer)
        self.assertNotIn('remaining_seconds', peer)
        self.assertNotIn('eta_source', peer)
        self.assertEqual(self.request('/leave', member=self.b)[0], 200)
        self.assertEqual(self.request('/sync', dict(after=0), self.b)[0], 401)
        server.init_storage(self.db)
        self.assertEqual(self.sync(self.a)['leader_id'], self.a['member_id'])

    def test_other_room_token_cannot_read_or_change_scenario_state(self):
        outsider = self.request('/rooms', dict(name='다른 모임', destination='다른 목적지'))[1]
        attempts = [('/sync', dict(after=0)), ('/destination', dict(place=self.target)),
                    ('/leader', dict(member_id=self.b['member_id'])), ('/leave', {}),
                    ('/messages', dict(kind='message', text='임의 메시지'))]
        for path, body in attempts:
            with self.subTest(path=path):
                self.assertEqual(self.request(path, dict(code=self.a['code'], **body), outsider)[0], 401)
        self.assertEqual(self.sync(self.a)['members'], ['지훈', '민수'])
        self.assertEqual(self.sync(self.b)['events'], [])

    def test_fresh_heartbeat_does_not_make_expired_eta_current(self):
        self.route(self.a)
        with server.lock:
            server.rooms[self.a['code']].presence[self.a['token']]['eta_observed_at'] -= 61
        peer = self.sync(self.b)['companions'][0]
        self.assertEqual(peer['name'], '지훈')
        self.assertLess(peer['seen_age_ms'], 1000)
        for field in ('remaining_seconds', 'route_name', 'eta_source', 'route_latitude', 'eta_age_ms'):
            self.assertNotIn(field, peer)


if __name__ == '__main__':
    unittest.main()
