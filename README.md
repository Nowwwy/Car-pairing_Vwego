# Vwego · CarPairing

**같은 목적지로 따로 이동하는 두 차량이 위치, 도착 예상 시간(ETA), 주행 상황을 공유하는 Pleos 차량용 앱입니다.** 현대자동차그룹 차량용 앱 개발 공모전 프로젝트입니다.

앱 **0.17-context** · Android `versionCode 22` · 모임 서버 **3.4-context**

[설치용 APK](apk/CarPairing-Integrated.apk) · [개발 및 실행 가이드](docs/development.md) · [검증 기록](CONTEXT_VERIFICATION_017.md)

## 앱에서 할 수 있는 일

| 상황 | 동작 |
| --- | --- |
| 두 차량이 함께 출발할 때 | 한 차량이 모임을 만들고 다른 차량이 코드로 참여합니다. 두 차량의 위치와 ETA를 확인합니다. |
| 목적지나 경유지를 바꾸고 싶을 때 | 각 차량의 운전자가 음성이나 텍스트로 장소를 검색하고 자신의 안내를 변경할 수 있습니다. 일행에게 목적지 변경을 제안하면 상대 운전자가 수락하거나 거절할 수 있습니다. |
| 목적지 근처에서 주차가 어려울 때 | 앱은 현재 차량의 활성 목적지와 직전 발화를 바탕으로 대체 주차장 후보와 기준지에서의 직선 거리를 표시합니다. “그중 가장 가까운 곳”처럼 이어서 요청할 수 있고, 안내 변경에는 확인이 필요합니다. |
| 늦거나 중간에 들러야 할 때 | 일행에게 지연 상황을 알리고 휴식·주유·주차를 계획합니다. 도로 경로 비교와 주차 층수·메모도 지원합니다. |

## 두 차량으로 시연하기

Python 3.10+, Android SDK 및 Platform-Tools, Pleos Connect 2.1.0 에뮬레이터 두 대가 필요합니다. AI 시연에는 PC의 Ollama와 `qwen3:4b`가 필요합니다.

1. Windows에서 `00_Check_Environment.cmd`로 환경을 확인하고, Ollama 설치 후 `01_Setup_Local_AI.cmd`를 실행합니다.
2. `Run-Two-Cars.cmd`로 모임 서버와 차량 앱을 시작합니다. 저장소에 서명된 시연용 APK가 포함되어 있습니다. 소스에서 디버그 APK를 만들려면 `02_Build_App.cmd`를 실행합니다.
3. 1번 차량에서 모임을 만들고 2번 차량에서 코드로 참여합니다. 공동 목적지를 정하고 2번 차량에서 수락합니다.
4. 한 차량에서 “목적지 근처 카페로 변경해줘”라고 요청하고 후보 선택과 확인을 거칩니다. 이어 “근처에 주차할 자리가 없어”라고 요청하면 변경된 목적지 주변 후보가 나옵니다. “그중 가장 가까운 곳으로 해줘”로 후보를 선택합니다.
5. “일행에게 10분 정도 늦을 것 같다고 알려줘”라고 요청하고 상대 차량에서 알림을 확인합니다.

위치 권한을 허용하세요. 음성 시연에는 앱의 마이크 권한과 에뮬레이터의 호스트 마이크 접근이 필요합니다. 제보 화면에서 텍스트로도 AI 요청을 입력할 수 있습니다. 이미 켜진 에뮬레이터에 설치하는 방법과 macOS/Linux 명령은 [개발 및 실행 가이드](docs/development.md)를 참고하세요.

## 코드와 문서 찾기

| 경로 | 역할 |
| --- | --- |
| [`app/`](app/) | Android 차량 앱과 단위 테스트. Pleos SDK로 위치·음성·길안내를 연동합니다. |
| [`server.py`](server.py), [`journey_store.py`](journey_store.py) | 개발·시연용 Python 모임 서버와 SQLite 저장소. |
| [`voice_api.py`](voice_api.py), [`voice_requests.py`](voice_requests.py), [`voice_choices.py`](voice_choices.py) | Ollama를 이용한 요청 해석, 장소 후보 조회, 후속 선택 처리. |
| [`places_service.py`](places_service.py), [`trip_planner.py`](trip_planner.py) | OpenStreetMap 기반 장소 데이터와 OSRM 기반 경로 비교. 실제 길안내는 Pleos에서 실행합니다. |
| [`run_local.py`](run_local.py), [`run_two.py`](run_two.py) | 로컬 서버, APK 설치 및 에뮬레이터 시연 도구. |
| 루트의 `test_*.py` | 서버, 장소 검색, 음성 요청 및 실행 도구 검사. |
| [`docs/`](docs/) | 개발·실행 문서와 프로젝트 자료. |

[0.17 검증 기록](CONTEXT_VERIFICATION_017.md)에는 Python 검사 234개, Android 단위 테스트 41개, 실제 로컬 모델/API 시나리오 10개, Pleos 에뮬레이터 두 대 설치 결과가 기록되어 있습니다. [Pleos 개발 환경 기록](DESIGN_GUIDE_AUDIT.md)과 [이전 버전 업그레이드 가이드](UPGRADE_GUIDE.md)도 참고할 수 있습니다.

## 시연 범위

- 주차장 후보는 수록된 장소 색인을 사용합니다. 실시간 빈자리, 요금, 영업 상태는 확인할 수 없습니다.
- 경로 비교에서 실시간 교통은 반영하지 않습니다. Pleos 기본 내비의 흰 지도 배경 문제는 시연 환경에서 남아 있습니다.
- 음성 요청이 애매하면 다시 확인합니다. 모임 서버는 로컬 개발과 시연을 위한 구성입니다.
