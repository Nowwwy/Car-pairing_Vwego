import unittest
import time
import json
from unittest.mock import patch
import test_server
import server

PLACE={'name':'스타벅스 강남점','latitude':37.498,'longitude':127.028,'address':'서울 강남구'}

class ContextRelayTests(test_server.ServerTests):
    def applied(self, member=None):
        member=member or self.a
        return self.call('/messages',dict(code=member['code'],kind='destination_applied',text='목적지를 변경했습니다.',place=PLACE,request_id='confirmed-route-1'),member)

    def request(self,text,member=None):
        member=member or self.a
        return self.call('/voice-request',dict(code=member['code'],text=text,allow_report=True),member)

    def test_route_completion_contains_real_place_and_is_idempotent(self):
        status,result=self.applied()
        self.assertEqual(status,200)
        self.assertIn(PLACE['name'],result['event']['text'])
        self.assertEqual(result['event']['id'],self.applied()[1]['event']['id'])
        self.assertEqual(len(server.rooms[self.a['code']].events),1)

    def test_relay_resolves_own_recent_applied_place(self):
        self.applied()
        status,data=self.request('일행에게 경로 변경했다고 말해줘')
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'report')
        self.assertIn(PLACE['name'],data.get('report_text',''))
        self.assertIn('목적지',data['report_text'])
        # Interpretation must not publish: the app sends after this response.
        self.assertEqual(len(server.rooms[self.a['code']].events),1)

    def test_android_empty_navigation_uses_recent_confirmed_change(self):
        self.applied()
        status,data=self.call('/voice-request',dict(code=self.a['code'],text='일행에게 경로 변경했다고 말해줘',allow_report=True,
            report_context={'destination':'','waypoints':[]}),self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'report')
        self.assertIn(PLACE['name'],data.get('report_text',''))

    def test_report_identifies_navigation_dependency(self):
        status,data=self.call('/voice-request',dict(code=self.a['code'],text='일행에게 어디로 가는지 알려줘',allow_report=True,
            report_context={'destination':'서울역','waypoints':[]}),self.a)
        self.assertEqual(status,200)
        self.assertEqual(data.get('report_reference'),'destination')

    def test_missing_foreign_or_stale_change_is_not_invented(self):
        self.applied(self.b)
        self.assertEqual(self.request('일행에게 경로 변경했다고 말해줘')[1]['action'],'clarify')
        self.applied()
        with patch('route_context.time.time',return_value=time.time()+1801):
            self.assertEqual(self.request('일행에게 경로 변경했다고 말해줘')[1]['action'],'clarify')

    def test_explicit_relay_keeps_numbers_and_negation(self):
        _,data=self.request('일행에게 20분 늦는다고 전달해줘')
        self.assertEqual(data.get('report_text'),'20분 늦는다.')
        _,data=self.request('일행에게 경로 변경 안 했다고 말해줘')
        self.assertNotEqual(data['action'],'clarify')
        self.assertIn('안 했',data.get('report_text',''))

    def test_confirmed_change_without_place_rejected(self):
        status,_=self.call('/messages',dict(code=self.a['code'],kind='destination_applied',text='변경했어요'),self.a)
        self.assertEqual(status,400)

    def test_delayed_notice_keeps_real_application_time(self):
        applied_at=int((time.time()-1790)*1000)
        status,result=self.call('/messages',dict(code=self.a['code'],kind='destination_applied',text='변경했습니다.',place=PLACE,
            request_id='delayed',applied_at_ms=applied_at),self.a)
        self.assertEqual(status,200)
        self.assertAlmostEqual(result['event']['confirmed_at'],applied_at/1000,places=3)
        with patch('route_context.time.time',return_value=time.time()+20):
            self.assertEqual(self.request('일행에게 경로 변경했다고 말해줘')[1]['action'],'clarify')

    def test_parking_completion_retains_planned_duration(self):
        status,result=self.call('/messages',dict(code=self.a['code'],kind='parking_applied',text='20분 주차할 계획입니다.',
            place=PLACE,dwell_minutes=20,stop_kind='parking'),self.a)
        self.assertEqual(status,200)
        self.assertIn('20분',result['event']['text'])

    def test_invalid_application_timestamp_rejected(self):
        for timestamp in [True,-1,int((time.time()+3600)*1000),'now']:
            with self.subTest(timestamp=timestamp):
                status,_=self.call('/messages',dict(code=self.a['code'],kind='destination_applied',text='변경했습니다.',place=PLACE,
                    applied_at_ms=timestamp),self.a)
                self.assertEqual(status,400)

    def test_legacy_stored_retry_stays_idempotent(self):
        _,result=self.applied()
        # v0.14 persisted the five-field fingerprint; upgrade must recognize it.
        server.rooms[self.a['code']].requests[(self.a['token'],'confirmed-route-1')]=(
            json.dumps(['destination_applied','목적지를 변경했습니다.',PLACE,'rest',0],sort_keys=True,ensure_ascii=False),result)
        status,retried=self.applied()
        self.assertEqual(status,200)
        self.assertEqual(retried['event']['id'],result['event']['id'])
        self.assertEqual(len(server.rooms[self.a['code']].events),1)

if __name__=='__main__':unittest.main()
