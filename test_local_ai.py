import unittest,json,io
from unittest.mock import patch
import voice_requests as vr

class LocalAI(unittest.TestCase):
 def test_ollama_interprets_indirect_request(self):
  reply={'action':'waypoint','category':'restaurant','query':'','area':'','scope':'current'}
  stream=io.BytesIO(json.dumps({'message':{'content':json.dumps(reply)}}).encode())
  with patch.dict('os.environ',{'CAR_PAIRING_VOICE_ENGINE':'ollama'}), patch.object(vr.LOCAL_MODEL_HTTP,'open',return_value=stream) as http:
   self.assertEqual(vr.interpret('배가 출출하네 간단히 요기하고 가자'),('waypoint','restaurant','current','',''))
   self.assertTrue(http.call_args.args[0].full_url.endswith('/api/chat'))
   body=json.loads(http.call_args.args[0].data)
   self.assertEqual(body['format']['type'],'object')
 def test_invalid_model_response_is_rejected(self):
  stream=io.BytesIO(json.dumps({'message':{'content':'{"action":"teleport"}'}}).encode())
  with patch.dict('os.environ',{'CAR_PAIRING_VOICE_ENGINE':'ollama'}), patch.object(vr.LOCAL_MODEL_HTTP,'open',return_value=stream):
   with self.assertRaises(vr.ModelUnavailable):vr.interpret('가자')

 def test_context_and_specific_clarification_roundtrip(self):
  reply={'action':'clarify','category':'parking','query':'','area':'','scope':'destination','question':'현재 위치와 목적지 중 어느 쪽에서 찾을까요?'}
  stream=io.BytesIO(json.dumps({'message':{'content':json.dumps(reply)}}).encode())
  context={'destination':{'name':'새 카페','coordinates':[37.5,127.0]}}
  with patch.object(vr.LOCAL_MODEL_HTTP,'open',return_value=stream) as http:
   result=vr.interpret('그 근처로',previous='주차할 곳이 없어',context=context)
  sent=json.loads(json.loads(http.call_args.args[0].data)['messages'][1]['content'])
  self.assertEqual(sent['navigation'],context)
  self.assertEqual(result.question,reply['question'])
 def test_failed_parking_lot_is_not_recommended_again(self):
  from voice_api import distance_meters
  rows=[('full',{'name':'만차 주차장','latitude':37.5,'longitude':127.0,'address':''},'parking'),('other',{'name':'다른 주차장','latitude':37.501,'longitude':127.0,'address':''},'parking')]
  with patch.object(vr,'index',return_value=rows):
   data=vr.recommend(('recommend','parking','destination','',''),None,(37.5,127.0),[],distance_meters,excluded=[(37.5,127.0)])
  self.assertEqual([p['name'] for p in data['places']],['다른 주차장'])

 def test_trailing_relay_instruction_is_removed(self):
  from report_composer import clean_core
  self.assertEqual(clean_core('주차장이 만차라고 일행에게 알려줘'),'주차장이 만차')
