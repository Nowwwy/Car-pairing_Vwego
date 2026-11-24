from tests import test_server
from backend import server

PLACE = {'name': '스타벅스 강남점', 'latitude': 37.498, 'longitude': 127.028, 'address': '서울 강남구'}

class DestinationFollowTests(test_server.ServerTests):
    def test_verified_personal_change_reaches_peer_without_changing_shared_destination(self):
        room = server.rooms[self.a['code']]
        original = (room.destination, room.coordinates)
        payload = dict(code=self.a['code'], kind='destination_changed', text='목적지를 변경했습니다.', place=PLACE, request_id='follow-1')
        status, sent = self.call('/messages', payload, self.a)
        self.assertEqual(status, 200)
        self.assertEqual(self.call('/messages', payload, self.a)[1]['event']['id'], sent['event']['id'])
        _, update = self.call('/sync', dict(code=self.b['code'], after=0), self.b)
        self.assertEqual(len(update['events']), 1)
        event = update['events'][0]
        self.assertEqual(event['kind'], 'destination_changed')
        self.assertEqual(event['place'], PLACE)
        self.assertIn(PLACE['name'], event['text'])
        self.assertEqual((room.destination, room.coordinates), original)
        _, sender_update = self.call('/sync', dict(code=self.a['code'], after=0), self.a)
        self.assertEqual(sender_update['events'], [])

    def test_follower_can_offer_own_confirmed_change_and_ack_is_distinct(self):
        status, sent = self.call('/messages', dict(code=self.b['code'], kind='destination_changed', text='변경했어요', place=PLACE), self.b)
        self.assertEqual(status, 200)
        status, ack = self.call('/messages', dict(code=self.a['code'], kind='destination_applied', text='수락했어요', place=PLACE), self.a)
        self.assertEqual(status, 200)
        self.assertEqual(ack['event']['kind'], 'destination_applied')
        self.assertNotEqual(sent['event']['kind'], ack['event']['kind'])

    def test_follow_offer_requires_coordinates(self):
        status, _ = self.call('/messages', dict(code=self.a['code'], kind='destination_changed', text='변경했어요'), self.a)
        self.assertEqual(status, 400)
