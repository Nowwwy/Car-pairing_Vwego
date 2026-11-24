from tests.test_server import ServerTests
from unittest.mock import patch
import unittest

class VoiceIntegration(ServerTests):
    def test_alternatives_endpoint_auth_and_provider(self):
        from tests.test_trip_planner import Response,route
        from backend import trip_planner
        body={'code':self.a['code'],'origin':{'latitude':37.5,'longitude':127},'destination':{'latitude':37.51,'longitude':127.02}}
        self.assertEqual(self.call('/trip/alternatives',body)[0],401)
        trip_planner._cache.clear()
        with patch.object(trip_planner,'urlopen',return_value=Response(route())):
            status,data=self.call('/trip/alternatives',body,self.a)
        self.assertEqual(status,200)
        self.assertEqual(len(data['routes']),1)
        self.assertFalse(data['traffic_included'])

    def test_voice_requires_membership(self):
        self.assertEqual(self.call('/voice-request', {'code':self.a['code'],'text':'취소'})[0],401)

    def test_voice_cancel_without_model(self):
        status, data=self.call('/voice-request', {'code':self.a['code'],'text':'취소'},self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'keep')

    def test_voice_rejects_invalid_coordinates(self):
        status,_=self.call('/voice-request', {'code':self.a['code'],'text':'카페 추천','current':[True,127]},self.a)
        self.assertEqual(status,400)

    def test_named_place_without_model(self):
        status,data=self.call('/voice-request', {'code':self.a['code'],'text':'서울역으로 가자'},self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'destination')
        self.assertTrue(any('서울역' in p['name'] for p in data['places']))
        self.assertEqual(data['source'],'rule')

    def test_unclear_request_does_not_invent_place(self):
        status,data=self.call('/voice-request', {'code':self.a['code'],'text':'아무데나'},self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'clarify')

    def test_unified_voice_cafe_change_recommends_near_destination(self):
        status,data=self.call('/voice-request',{'code':self.a['code'],'text':'목적지 근처 카페로 경로 변경','allow_report':True,'current':[37.1,127.0],'destination':[37.498,127.028]},self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'destination')
        self.assertEqual(data['category'],'cafe')
        self.assertEqual(data['basis'],'destination')
        self.assertTrue(data['places'])
        self.assertTrue(all(p['distance_m']<=5000 for p in data['places']))

    def test_unified_voice_report_does_not_search_or_publish(self):
        for text in ['앞에 차가 많이 막혀','목적지 근처 카페가 문 닫았어','일행에게 목적지 근처 카페로 경로 변경이라고 전달해줘']:
            with self.subTest(text=text):
                status,data=self.call('/voice-request',{'code':self.a['code'],'text':text,'allow_report':True},self.a)
                self.assertEqual(status,200)
                self.assertEqual(data['action'],'report')
                self.assertEqual(data['places'],[])
        from backend import server
        self.assertEqual(len(server.rooms[self.a['code']].events),0)

    def test_unified_voice_local_model_indirect_need(self):
        import os
        with patch.dict(os.environ,{'CAR_PAIRING_OLLAMA':'1'}), patch('backend.voice_api.interpret',return_value=('waypoint','rest','current','','')):
            status,data=self.call('/voice-request',{'code':self.a['code'],'text':'너무 졸려 잠깐 눈 붙일 데 없나','allow_report':True,'current':[37.5,127.1]},self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'waypoint')
        self.assertEqual(data['source'],'local_model')

    def test_explicit_destination_change_is_not_downgraded_to_stop(self):
        import os
        with patch.dict(os.environ,{'CAR_PAIRING_OLLAMA':'1'}), patch('backend.voice_api.interpret',return_value=('waypoint','cafe','destination','','')):
            status,data=self.call('/voice-request',{'code':self.a['code'],'text':'목적지 근처 카페로 경로 변경','allow_report':True,'destination':[37.498,127.028]},self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'destination')
        self.assertEqual(data['category'],'cafe')

    def test_report_resolves_current_destination_and_removes_instruction(self):
        status,data=self.call('/voice-request',{'code':self.a['code'],'text':'일행에게 경로변경했다고 말해줘','allow_report':True,'report_context':{'destination':'투썸플레이스 강남역점','waypoints':[]}},self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'report')
        self.assertIn('투썸플레이스 강남역점',data['report_text'])
        self.assertNotIn('말해줘',data['report_text'])
        self.assertNotIn('일행에게',data['report_text'])

    def test_unknown_route_is_clarified_not_guessed(self):
        status,data=self.call('/voice-request',{'code':self.a['code'],'text':'일행에게 경로변경했다고 말해줘','allow_report':True},self.a)
        self.assertEqual(data['action'],'clarify')
        self.assertNotIn('report_text',data)

    def test_report_preserves_delay_and_uncertainty(self):
        status,data=self.call('/voice-request',{'code':self.a['code'],'text':'일행에게 10분 정도 늦을 것 같다고 말해줘','allow_report':True},self.a)
        self.assertEqual(data['action'],'report')
        self.assertIn('10분',data['report_text'])
        self.assertIn('것 같',data['report_text'])
        self.assertNotIn('말해줘',data['report_text'])

    def test_clear_reports_cannot_be_discarded_as_keep_or_search(self):
        import os
        for text in ('나 20분 정도 늦을 것 같아',):
            with self.subTest(text=text), patch.dict(os.environ,{'CAR_PAIRING_OLLAMA':'1'}), patch('backend.voice_api.interpret',return_value=('keep','any','current','','')) as intent_model, patch('backend.voice_api.compose_report',return_value={'action':'report','report_text':text,'report_reference':'none','report_source':'rule'}):
                status,data=self.call('/voice-request',{'code':self.a['code'],'text':text,'allow_report':True},self.a)
                self.assertEqual(status,200)
                self.assertEqual(data['action'],'report')
                intent_model.assert_not_called()

    def test_parking_report_does_not_override_a_search_request(self):
        status,data=self.call('/voice-request',{'code':self.a['code'],'text':'주차장 꽉 찼으니 다른 주차장 찾아줘','allow_report':True,'destination':[37.498,127.028]},self.a)
        self.assertEqual(status,200)
        self.assertNotEqual(data['action'],'report')


    def test_parking_problem_uses_changed_personal_destination(self):
        place={'name':'변경한 카페','latitude':37.498,'longitude':127.028,'address':'서울'}
        self.call('/messages',dict(code=self.a['code'],kind='destination_changed',text='변경 완료',place=place),self.a)
        status,data=self.call('/voice-request',dict(code=self.a['code'],text='근처에 주차장 자리가 없어',allow_report=True,current=[37.1,127.0]),self.a)
        self.assertEqual(status,200)
        self.assertEqual(data['action'],'recommend')
        self.assertEqual(data['category'],'parking')
        self.assertEqual(data['basis'],'destination')
        self.assertEqual(data['search_center'],[37.498,127.028])
        self.assertTrue(data['places'])
        self.assertIn('변경한 카페',data['message'])
        self.assertIn('빈자리',data['notice'])

    def test_client_destination_wins_over_history_and_shared_target(self):
        place={'name':'예전 장소','latitude':37.4,'longitude':127.0,'address':''}
        self.call('/messages',dict(code=self.a['code'],kind='destination_changed',text='변경',place=place),self.a)
        _,data=self.call('/voice-request',dict(code=self.a['code'],text='차 댈 데가 없어',allow_report=True,destination=[37.498,127.028],destination_name='새 카페'),self.a)
        self.assertEqual(data['search_center'],[37.498,127.028])
        self.assertIn('새 카페',data['message'])

    def test_peer_change_does_not_become_my_parking_anchor(self):
        place={'name':'다른 차량 장소','latitude':37.4,'longitude':127.0,'address':''}
        self.call('/messages',dict(code=self.b['code'],kind='destination_changed',text='변경',place=place),self.b)
        _,data=self.call('/voice-request',dict(code=self.a['code'],text='주차할 곳이 없어',allow_report=True),self.a)
        self.assertEqual(data['search_center'],[37.5,127.1])

    def test_explicit_here_parking_uses_car_position(self):
        _,data=self.call('/voice-request',dict(code=self.a['code'],text='지금 차 있는 곳 근처 주차장 찾아줘',allow_report=True,current=[37.51,127.12],destination=[37.498,127.028]),self.a)
        self.assertEqual(data['basis'],'current')
        self.assertEqual(data['search_center'],[37.51,127.12])

    def test_parking_relay_stays_relay(self):
        _,data=self.call('/voice-request',dict(code=self.a['code'],text='주차장이 만차라고 일행에게 알려줘',allow_report=True),self.a)
        self.assertEqual(data['action'],'report')
        self.assertEqual(data['places'],[])

    def test_model_receives_active_route_and_previous_utterance(self):
        import os
        with patch.dict(os.environ,{'CAR_PAIRING_OLLAMA':'1'}),patch('backend.voice_api.interpret',return_value=('recommend','parking','destination','','')) as model:
            _,data=self.call('/voice-request',dict(code=self.a['code'],text='거긴 차 세울 틈이 하나도 안 보이네',previous='근처 카페 추천해줘',destination=[37.498,127.028],destination_name='현재 카페',allow_report=True),self.a)
        self.assertEqual(data['category'],'parking')
        self.assertEqual(model.call_args.kwargs['context']['destination']['name'],'현재 카페')
        self.assertEqual(model.call_args.args[1],'근처 카페 추천해줘')

    def test_parking_negation_does_not_offer_alternatives(self):
        _,data=self.call('/voice-request',dict(code=self.a['code'],text='주차장이 만차는 아니야',allow_report=True),self.a)
        self.assertEqual(data['places'],[])

    def test_explicit_current_scope_requires_current_location(self):
        _,data=self.call('/voice-request',dict(code=self.a['code'],text='현재 위치 근처 주차장 찾아줘',destination=[37.498,127.028],allow_report=True),self.a)
        self.assertEqual(data['action'],'clarify')
        self.assertEqual(data['places'],[])

    def test_voice_choice_auth_and_no_server_side_route_mutation(self):
        from backend import server
        body=dict(code=self.a['code'],stage='select',text='두 번째가 좋겠어',options=['가','나'],distances=[100,200])
        self.assertEqual(self.call('/voice-choice',body)[0],401)
        before=(server.rooms[self.a['code']].coordinates,len(server.rooms[self.a['code']].events))
        self.assertEqual(self.call('/voice-choice',body,self.a)[1]['decision'],'clarify')
        self.assertEqual(before,(server.rooms[self.a['code']].coordinates,len(server.rooms[self.a['code']].events)))

if __name__=='__main__':unittest.main()
