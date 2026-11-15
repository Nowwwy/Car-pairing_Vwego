import concurrent.futures
import os
import subprocess
import sys
import tempfile
import unittest

from journey_store import JourneyStore

P = {'id': 'p1', 'name': '주차장', 'latitude': 37.5, 'longitude': 127.0, 'address': '서울'}
Q = {'id': 'p2', 'name': '다른 주차장', 'latitude': 37.6, 'longitude': 127.1}


class JourneyStoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, 'journeys.sqlite3')
        self.store = JourneyStore(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def test_room_snapshot_survives_restart_and_delete(self):
        snapshot = {'token': 'private', 'members': [{'id': 'a'}], 'events': [1]}
        self.store.save_room('123456', snapshot)
        snapshot['events'].append(2)
        other = JourneyStore(self.path)
        self.assertEqual(other.load_rooms(), {'123456': {'token': 'private', 'members': [{'id': 'a'}], 'events': [1]}})
        other.delete_room('123456')
        self.assertEqual(self.store.load_rooms(), {})

    def test_learning_requires_opt_in_deduplicates_and_is_private(self):
        self.assertEqual(self.store.get_profile('alice'), {'learning_enabled': False, 'plate': '', 'choices_count': 0})
        self.assertFalse(self.store.record_choice('alice', 'parking', P, True))
        self.store.update_profile('alice', learning_enabled=True, plate='12가3456')
        self.assertTrue(self.store.record_choice('alice', 'parking', P, True, request_id='req-1'))
        self.assertFalse(self.store.record_choice('alice', 'parking', P, True, request_id='req-1'))
        self.assertEqual(JourneyStore(self.path).get_profile('alice')['choices_count'], 1)
        self.assertEqual(self.store.get_profile('bob')['choices_count'], 0)
        self.store.clear_history('alice')
        self.assertEqual(self.store.get_profile('alice')['choices_count'], 0)
        self.assertEqual(self.store.get_profile('alice')['plate'], '12가3456')

    def test_ranking_uses_accepted_and_rejected_choices_without_mutating_places(self):
        self.store.update_profile('alice', learning_enabled=True)
        for _ in range(3):
            self.store.record_choice('alice', 'parking', P, True)
        self.store.record_choice('alice', 'parking', Q, True)
        ranked = self.store.rank_places('alice', 'parking', [Q, P])
        self.assertEqual([p['id'] for p in ranked], ['p1', 'p2'])
        self.assertEqual(ranked[0]['used_count'], 3)
        self.assertIn('3회', ranked[0]['recommendation'])
        for _ in range(4):
            self.store.record_choice('alice', 'parking', P, False)
        self.assertEqual(self.store.rank_places('alice', 'parking', [P, Q])[0]['id'], 'p2')
        self.assertNotIn('used_count', P)
        self.assertEqual([p['id'] for p in self.store.rank_places('bob', 'parking', [Q, P])], ['p2', 'p1'])
        self.store.update_profile('alice', learning_enabled=False)
        self.assertEqual([p['id'] for p in self.store.rank_places('alice', 'parking', [Q, P])], ['p2', 'p1'])

    def test_parking_and_meetings_survive_restart_and_enforce_owner_delete(self):
        parking = self.store.save_parking('alice', {'place': P, 'floor': 'B2', 'zone': 'A', 'note': '기둥 옆'})
        meeting = self.store.save_meeting('alice', '주말 모임', P)
        other = JourneyStore(self.path)
        self.assertEqual(other.list_parking('alice'), [parking])
        self.assertEqual(other.list_meetings('alice'), [meeting])
        self.assertEqual(other.list_parking('bob'), [])
        self.assertFalse(other.delete_parking('bob', parking['id']))
        self.assertFalse(other.delete_meeting('bob', meeting['id']))
        self.assertTrue(other.delete_parking('alice', parking['id']))
        self.assertTrue(other.delete_meeting('alice', meeting['id']))
        absent = other.save_parking('alice', {'floor': '1'})
        self.assertIsNone(absent['place'])

    def test_validation_rejects_invalid_coordinates_and_unbounded_fields(self):
        bad_places = [dict(P, latitude=float('nan')), dict(P, longitude=float('inf')), dict(P, latitude=91), {'name': 'missing'}]
        for place in bad_places:
            with self.subTest(place=place), self.assertRaises(ValueError):
                self.store.save_meeting('alice', '모임', place)
        for field, size in [('floor', 25), ('zone', 41), ('note', 121)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.store.save_parking('alice', {field: 'x' * size})
        with self.assertRaises(ValueError):
            self.store.update_profile('alice', learning_enabled='false')
        with self.assertRaises(ValueError):
            self.store.get_profile('../bad')
        with self.assertRaises(ValueError):
            self.store.record_choice('alice', 'parking', P, True, dwell_minutes=float('nan'))

    def test_concurrent_writes_and_duplicate_requests_are_atomic(self):
        self.store.update_profile('alice', learning_enabled=True)
        def write(i):
            store = JourneyStore(self.path) if i % 2 else self.store
            return store.record_choice('alice', 'parking', P, True, request_id='same' if i < 10 else f'req-{i}')
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            recorded = list(pool.map(write, range(40)))
        self.assertEqual(sum(recorded), 31)
        self.assertEqual(JourneyStore(self.path).get_profile('alice')['choices_count'], 31)

    def test_process_restart_reads_committed_private_data(self):
        self.store.save_room('654321', {'token': 'secret'})
        self.store.update_profile('alice', plate='42가1234')
        script = ("from journey_store import JourneyStore; import sys; "
                  "s=JourneyStore(sys.argv[1]); "
                  "assert s.load_rooms()['654321']['token']=='secret'; "
                  "assert s.get_profile('alice')['plate']=='42가1234'; "
                  "s.save_parking('alice', {'note':'from second process'})")
        subprocess.run([sys.executable, '-c', script, self.path], check=True)
        self.assertEqual(self.store.list_parking('alice')[0]['note'], 'from second process')

    def test_invalid_kind_is_validation_error_and_clear_preserves_other_client(self):
        with self.assertRaises(ValueError):
            self.store.record_choice('alice', [], P, True)
        for client in ('alice', 'bob'):
            self.store.update_profile(client, learning_enabled=True)
            self.store.record_choice(client, 'fuel', P, True)
        self.store.clear_history('alice')
        self.assertEqual(self.store.get_profile('bob')['choices_count'], 1)
        self.assertEqual(self.store.get_profile('alice')['choices_count'], 0)

    def test_per_client_caps_keep_recent_records(self):
        self.store.update_profile('alice', learning_enabled=True)
        for i in range(505):
            self.store.record_choice('alice', 'parking', P, True, request_id=f'r{i}')
        for i in range(102):
            self.store.save_parking('alice', {'note': str(i)})
        for i in range(52):
            self.store.save_meeting('alice', str(i), P)
        self.assertEqual(self.store.get_profile('alice')['choices_count'], 500)
        self.assertEqual(len(self.store.list_parking('alice')), 100)
        self.assertEqual(self.store.list_parking('alice')[0]['note'], '101')
        self.assertEqual(len(self.store.list_meetings('alice')), 50)
        self.assertEqual(self.store.list_meetings('alice')[0]['name'], '51')


if __name__ == '__main__':
    unittest.main()
