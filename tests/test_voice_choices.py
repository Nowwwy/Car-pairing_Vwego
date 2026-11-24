import unittest
from unittest.mock import patch
import backend.voice_choices as v
from backend.voice_requests import ModelUnavailable
class VoiceChoiceTests(unittest.TestCase):
 def body(self,stage='select',text='아까 추천한 것 중 뒤에 말한 데로 해줘'):
  return dict(stage=stage,text=text,options=['첫 주차장','두 번째 주차장'],distances=[420,250])
 def test_semantic_selection_is_grounded_to_visible_options(self):
  with patch.object(v,'local_completion',return_value={'decision':'select','index':2}) as model:
   result=v.resolve_choice(self.body())
  self.assertEqual(result,{'decision':'select','index':2,'source':'local_model'})
  self.assertEqual(model.call_args.args[2]['options'],['첫 주차장','두 번째 주차장'])
 def test_out_of_range_and_boolean_index_are_not_choices(self):
  for index in (0,3,True):
   with self.subTest(index=index),patch.object(v,'local_completion',return_value={'decision':'select','index':index}):
    self.assertEqual(v.resolve_choice(self.body())['decision'],'clarify')
 def test_select_step_cannot_skip_final_confirmation(self):
  with patch.object(v,'local_completion',return_value={'decision':'confirm','index':0}):
   self.assertEqual(v.resolve_choice(self.body())['decision'],'clarify')
 def test_natural_consent_and_negation(self):
  with patch.object(v,'local_completion',return_value={'decision':'confirm','index':0}):
   self.assertEqual(v.resolve_choice(self.body('confirm','좋겠네 그 장소로 안내 시작해 줘'))['decision'],'confirm')
   for text in ('아직 바꾸지 말아줘','거기로 가면 되는 거야?','아니 그대로 두자','나중에 해줘'):
    self.assertEqual(v.resolve_choice(self.body('confirm',text))['decision'],'clarify')
 def test_offline_model_does_not_apply_or_select(self):
  with patch.object(v,'local_completion',side_effect=ModelUnavailable()):
   self.assertEqual(v.resolve_choice(self.body())['decision'],'clarify')
 def test_invalid_input(self):
  for body in (dict(self.body(),options=[]),dict(self.body(),distances=[1]),dict(self.body(),stage='drive'),dict(self.body(),text='a'*241)):
   with self.assertRaises(ValueError):v.resolve_choice(body)
 def test_model_cannot_misnumber_explicit_second_choice(self):
  with patch.object(v,'local_completion',return_value={'decision':'select','index':1}):
   self.assertEqual(v.resolve_choice(self.body(text='아까 말한 것 중 두 번째가 좋겠어'))['index'],2)
 def test_nearest_uses_real_distances_not_model_arithmetic(self):
  with patch.object(v,'local_completion',return_value={'decision':'select','index':1,'criterion':'nearest'}):
   self.assertEqual(v.resolve_choice(self.body(text='그중에서 가장 가까운 곳으로 해줘'))['index'],2)
 def test_duplicate_names_need_a_number_or_grounded_distance(self):
  body=dict(self.body(text='그 주차장으로 해줘'),options=['주차장','주차장'])
  with patch.object(v,'local_completion',return_value={'decision':'select','index':1}):
   self.assertEqual(v.resolve_choice(body)['decision'],'clarify')
