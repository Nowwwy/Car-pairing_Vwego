import io,json,unittest
from unittest.mock import patch
import report_composer as r

class Reports(unittest.TestCase):
 def model(self,core,reference):
  return patch.object(r.LOCAL_MODEL_HTTP,'open',return_value=io.BytesIO(json.dumps({'message':{'content':json.dumps({'core':core,'reference':reference})}}).encode()))
 def test_ai_resolves_reference_to_observed_name(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'1'}), self.model('','route_changed') as model:
   result=r.compose_report('내가 가는 곳 바꾼 거 뒤차에 알려줘',{'destination':'투썸플레이스 강남역점','waypoints':[]})
   self.assertEqual(result['report_text'],'투썸플레이스 강남역점으로 경로를 변경했어요.')
   self.assertEqual(result['report_source'],'local_model')
   body=json.loads(model.call_args.args[0].data)
   self.assertIn('투썸플레이스 강남역점',body['messages'][1]['content'])
 def test_invented_message_is_rejected(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'1'}), self.model('20분 늦습니다','none'):
   result=r.compose_report('일행에게 10분 정도 늦을 것 같다고 말해줘',{})
   self.assertEqual(result['report_source'],'rule')
   self.assertIn('10분 정도 늦을 것 같다',result['report_text'])
   self.assertNotIn('20분',result['report_text'])
 def test_negation_cannot_become_change(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'1'}), self.model('','route_changed'):
   result=r.compose_report('일행에게 경로 안 바꿨다고 전달해줘',{'destination':'서울역'})
   self.assertIn('안 바꿨다',result['report_text'])
   self.assertNotIn('변경했어요',result['report_text'])
 def test_waypoint_is_not_final_destination(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'0'}):
   result=r.compose_report('경유지 추가했다고 알려줘',{'destination':'강남역','waypoints':['만남의광장']})
   self.assertIn('만남의광장에 들를 예정',result['report_text'])
   self.assertIn('최종 목적지는 강남역',result['report_text'])

 def test_fillers_and_delivery_commands_are_removed(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'0'}):
   result=r.compose_report('어 그 일행에게 10분 정도 늦을 것 같다고 말해줘',{})
  self.assertEqual(result['report_text'],'10분 정도 늦을 것 같다.')
 def test_question_is_never_reported_as_completed_change(self):
  for text in ('경로 변경했냐고 물어봐','목적지를 바꿨는지 알려줘','경로 변경했어?'):
   for enabled in ('0','1'):
    with self.subTest(text=text,model=enabled), patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':enabled}), self.model('','route_changed'):
     result=r.compose_report(text,{'destination':'서울역'})
     self.assertEqual(result['report_reference'],'none')
     self.assertNotIn('서울역으로 경로를 변경했어요',result['report_text'])
 def test_every_uncertainty_qualifier_survives(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'1'}), self.model('아마 10분','none'):
   result=r.compose_report('일행에게 아마 10분 정도 늦는다고 말해줘',{})
  self.assertIn('정도',result['report_text'])
 def test_similar_sounding_place_name_is_not_filler(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'0'}):
   result=r.compose_report('어린이대공원 입구가 막혔어',{})
  self.assertEqual(result['report_text'],'어린이대공원 입구가 막혔어.')

 def test_question_cannot_be_replaced_by_current_destination(self):
  with patch.dict('os.environ',{'CAR_PAIRING_OLLAMA':'1'}), self.model('','destination'):
   result=r.compose_report('일행에게 경로 변경했냐고 말해줘',{'destination':'투썸플레이스'})
  self.assertEqual(result['report_reference'],'none')
  self.assertIn('변경했냐',result['report_text'])
