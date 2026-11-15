import os
import json
import unittest
from io import BytesIO
from unittest.mock import patch
import message_intent as intent


class NormalizationTests(unittest.TestCase):
    def test_controlled_templates(self):
        self.assertEqual(intent.normalize_text('나 20분 정도 늦을 것 같아', 'delay'), '20분 정도 늦을 것 같습니다.')
        self.assertEqual(intent.normalize_text('여기 주차장 꽉 찼어', 'parking'), '주차장이 만차라고 합니다.')
        self.assertEqual(intent.normalize_text('잠깐 쉬자', 'rest'), '잠깐 쉬어 가자고 합니다.')

    def test_unsafe_unknown_and_place_names_unchanged(self):
        for text, kind in [('아마 12분 정도 늦을 것 같아', 'delay'), ('20분은 안 늦어', 'delay'),
                           ('서울역 주차장 꽉 찼어', 'parking'), ('주차장 꽉 찼어 사고 났어', 'parking'),
                           ('휴게소 가지 말자', 'rest'), ('15분~20분 늦을 것 같아', 'delay')]:
            self.assertEqual(intent.normalize_text(text, kind), text)

    def test_explicit_text_dialect_and_rule_source(self):
        with patch.dict(os.environ, {'CAR_PAIRING_OLLAMA': '0'}):
            self.assertEqual(intent.classify('주차 자리 읎어')[0], 'parking')
            self.assertEqual(intent.classify('주차 자리 없데이')[0], 'parking')
            self.assertEqual(intent.classify('주차 자리 읎어 안 가')[1], '주차 자리 읎어 안 가')
            self.assertEqual(intent.classify('나 20분 정도 늦을 것 같아'),
                             ('delay', '20분 정도 늦을 것 같습니다.', 'rule'))

    def test_declining_a_rest_stop_never_becomes_an_offer(self):
        for text in ['휴게소 가지 말자', '휴게소 안 가도 돼', '쉬지 말고 목적지로 가자',
                     '휴게소는 들르지 않을게', '휴게소 안 들를래', '휴식 필요 없어']:
            with self.subTest(text=text), patch.dict(os.environ, {'CAR_PAIRING_OLLAMA': '0'}):
                self.assertEqual(intent.classify(text), ('message', text, 'rule'))

    def test_model_cannot_override_rest_decline(self):
        answer = {'message': {'content': '{"kind":"rest"}'}}
        with patch.dict(os.environ, {'CAR_PAIRING_OLLAMA': '1'}), patch.object(
                intent, 'urlopen', return_value=BytesIO(json.dumps(answer).encode())):
            self.assertEqual(intent.classify('휴게소 가지 말자'), ('message', '휴게소 가지 말자', 'rule'))

    def test_parking_space_particles_still_trigger_recommendation(self):
        for text in ['여기 주차장 자리가 없어', '주차할 자리가 없어', '주차 공간이 없어']:
            with self.subTest(text=text), patch.dict(os.environ, {'CAR_PAIRING_OLLAMA':'0'}):
                self.assertEqual(intent.classify(text)[0], 'parking')

    def test_parking_space_negation_does_not_trigger_recommendation(self):
        for text in ['주차장 자리가 없는 건 아니야', '주차 공간이 없는 건 아니야', '주차할 자리가 있어']:
            with self.subTest(text=text), patch.dict(os.environ, {'CAR_PAIRING_OLLAMA':'0'}):
                self.assertEqual(intent.classify(text)[0], 'message')

    def test_explicit_no_delay_does_not_mark_sender_delayed(self):
        for text in ['안 늦을 것 같아', '늦지 않을 것 같아', '늦을 것 같지 않아']:
            with self.subTest(text=text), patch.dict(os.environ, {'CAR_PAIRING_OLLAMA':'0'}):
                self.assertEqual(intent.classify(text), ('message', text, 'rule'))

    def test_model_cannot_override_explicit_no_delay(self):
        answer = {'message': {'content': '{"kind":"delay"}'}}
        with patch.dict(os.environ, {'CAR_PAIRING_OLLAMA':'1'}), patch.object(intent, 'urlopen', return_value=BytesIO(json.dumps(answer).encode())):
            self.assertEqual(intent.classify('안 늦을 것 같아'), ('message', '안 늦을 것 같아', 'rule'))
