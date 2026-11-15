# CarPairing 0.15 사용법

이 묶음은 소스 수정본입니다. Python 검사는 완료했지만 0.15 Android APK의 빌드와 실제 기기 동작은 아직 확인하지 못했습니다. 기존 0.14.4의 검증 결과를 새 버전 검증으로 간주하지 마세요.

## 유지한 화면과 기능

기존 지도 중심 화면, 내 차·일행 ETA 카드, 모임 코드·QR, 위치 공유, 공동 목적지 수락, 주차/휴식/주유 계획, 주차 층수·메모, 리더 변경, 모임 저장, 지도 캐시, 장소 선택 이력 추천, 음성 안내 설정을 유지합니다.

## Windows

1. `00_Check_Environment.cmd`: SDK 경로, ADB 기기, APK와 로컬 AI 모델 존재 여부를 표시합니다.
2. `01_Setup_Local_AI.cmd`: 설치된 Ollama를 실행하고 필요한 기본 모델 qwen3:4b를 내려받아 준비합니다. Ollama가 없으면 설치 주소를 안내합니다.
3. `02_Build_App.cmd`: Android 앱과 단위 테스트를 빌드합니다. JDK 25·SDK 37.0·Pleos 의존성과 최초 다운로드 네트워크가 필요합니다. 성공 시 `dist/CarPairing-Integrated.apk`를 만듭니다.
4. 이전 서버를 종료하고 `Run-Two-Cars.cmd`: 로컬 서버와 기존 Pleos 두 차량 시연을 실행합니다. 기존 AVD 이름은 Pleos_Connect_2_1 및 Pleos_Pairing_Follower_1 또는 CarPairing_Pleos_B를 사용합니다.
5. 각 차량에서 위치·마이크 권한을 허용하고 모임 코드로 연결합니다. 이전 모임 상태를 이어 쓰려면 종료된 서버의 carpairing.sqlite3를 별도로 백업한 뒤 새 폴더로 복사할 수 있습니다. 실행 중 DB를 복사하지 마세요.

앱 서명이 다른 경우 자동 삭제하지 않습니다. 이전 앱 삭제는 데이터가 지워지는 작업이므로 직접 판단해야 합니다. 앱과 서버의 버전은 함께 업데이트합니다.

## 이미 부팅한 기기 / macOS / Linux

Mac 버전의 휴대 가능한 SDK/ADB 탐색 방식을 반영했습니다. Unix gradlew의 줄바꿈도 LF로 수정했습니다. 아래 명령의 `python3`는 Windows에서 `py -3`로 바꿉니다.

```sh
python3 run_local.py check
python3 run_local.py build
python3 run_local.py server
# 별도 터미널에서:
python3 run_local.py setup --serial emulator-5554 --serial emulator-5556
```

SDK가 기본 위치에 없으면 `ANDROID_HOME`을 설정하거나 `python3 run_local.py --sdk "/내 SDK 경로" check`처럼 지정합니다. 다른 호스트 포트는 `server --port 8768`과 `setup --port 8768` 양쪽에 똑같이 지정합니다. 앱 내부 포트 8765는 ADB reverse로 연결됩니다. setup은 에뮬레이터를 생성하거나 부팅하지 않습니다.

Mac 전용 llama.cpp 자동 설치기를 가져오지 않았습니다. 기본 AI는 Windows와 Mac 모두 Ollama/qwen3:4b이며, Pleos STT가 음성을 문자로 바꾸고 PC의 로컬 모델이 요청을 해석합니다. 환경 경로 분기는 자동 테스트했지만 이번 버전의 실제 Mac/Linux 에뮬레이터 실행은 확인하지 못했습니다.

## 음성 / 텍스트 요청

- “목적지 근처 카페로 경로 변경” → 실제 장소 후보 → 번호 선택 → 확인 → 내 차량 안내 적용.
- “커피 마시고 가자” → 카페를 경유할 후보.
- “일행에게 경로 변경했다고 말해줘” → 관찰한 목적지·경유지 이름을 포함한 문장.
- 내비 맥락이 비어 있어도 자신의 최근 30분 내 적용 완료 기록이 있으면 변경 장소를 보완합니다. 기록도 없으면 되묻습니다.
- “일행에게 10분 정도 늦을 것 같다고 말해줘” → 수치와 불확실성을 유지한 핵심 문장.
- 음성으로 안내 적용을 확인하면 변경 장소가 포함된 완료 알림을 자동 공유합니다. 실패한 알림은 최대 30분 내 재시도하며, 동일 요청 ID로 중복 표시를 막습니다. 다른 모임에는 전송하지 않습니다.
- 추천 결과에 로컬 AI/기본 처리 표시를 추가했습니다. 모델이 없거나 오류가 나면 제한적인 규칙 처리를 사용합니다.
- 텍스트 입력에는 기존 `메시지 보내기`와 새 `AI 요청 처리 · 추천 또는 문장 정리`가 함께 있습니다. AI 요청은 240자 이하, 직접 메시지는 500자 이하입니다. 취소·실패 시 입력을 바로 지우지 않습니다.

음성 번호 선택과 최종 확인은 기존 버튼으로 다시 듣기를 시작할 수 있습니다. 무응답·취소로 안내가 바뀌지 않습니다. 다른 차량의 안내도 상대 운전자의 별도 수락이 필요합니다.

## 마이크

에뮬레이터 `⋯ → Microphone → Enable Host Microphone Access`를 켜고 Windows 설정의 마이크 접근을 허용하세요. 호스트 입력 장치도 실제 헤드셋 마이크인지 확인합니다. 마이크가 없는 PC는 텍스트 AI 요청을 사용합니다. 이번 검증은 실제 사람 발화의 인식률·소음·사투리를 포함하지 않습니다.

## 경로 비교

`더보기 → 여러 도로 경로 비교`에서 OSRM이 실제 반환한 최대 3개 경로를 비교합니다. 수락한 경유지 최대 2곳을 포함하고 예상시간 최소/거리 최소 라벨을 표시합니다. 항목을 누르면 선택한 경로를 지도에서 봅니다.

경유지를 포함할 때 제공자가 한 개만 반환할 수도 있습니다. 추가 후보를 만들어내지 않습니다. 앱 비교 화면에는 정차 시간이 별도이며, 서버 API에 dwell_minutes를 지정한 경우 해당 체류 시간을 total_seconds에 더합니다. 목적지·경유지가 바뀌면 이전 비교 결과를 사용하지 않습니다.

실시간 교통 정체를 반영한 최적 우회 추천이나 선택한 도로 형상을 Pleos 내비에 강제 적용하는 기능은 아닙니다. 기존 장소 수락 이력 추천과 임의 경로 이탈 자동 학습은 서로 다릅니다.

## 데이터와 출처

제공된 Mac 0.13.1의 한국 OSM 장소 스냅샷과 기존 지도 데이터를 유지했습니다. 장소 이름·좌표가 실제 데이터에 있어도 영업·주차 재고·가격·진입 방향까지 보장하지 않습니다.

- OSRM 경로 API: https://project-osrm.org/docs/v5.24.0/api/
- Android Emulator 마이크: https://developer.android.com/studio/run/emulator-extended-controls
- 지도 데이터 © OpenStreetMap contributors / ODbL: https://www.openstreetmap.org/copyright
- Ollama 설치: https://ollama.com/download

개인 DB·접근 토큰·실행 로그·개인 SDK 경로는 배포 ZIP에서 제외합니다. 검증되지 않은 구버전 APK도 넣지 않습니다.
