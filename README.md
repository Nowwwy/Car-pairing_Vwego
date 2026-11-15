# Vwego · CarPairing

두 차량이 모임 코드로 연결해 목적지, 도착 예상 시간, 주행 상황을 공유하는 Pleos 차량용 앱입니다.

현재 버전: **0.17-context** · Android `versionCode 22` · 모임 서버 `3.4-context`.

## 주요 기능

- 호스트 모임 생성 → 일행 코드 참여 → 공동 목적지 수락/거절
- 차량별 위치와 ETA 표시, 일행 목적지 변경 제안 및 수락
- 음성/텍스트로 주차장·카페 검색, 목적지·경유지 변경, 지연 연락
- 변경한 내 차량 목적지와 직전 발화를 이용한 로컬 AI 요청 해석
- “근처에 주차할 자리가 없어” → 현재 목적지 주변 대체 주차장 후보와 거리 표시
- “그중 가장 가까운 곳으로 해줘” 같은 후보 선택 해석과 최종 변경 확인
- 휴식·주유·주차 계획, 주차 층수/메모, 도로 경로 비교

1번 호스트와 2번 참여 차량 모두 음성 요청과 개인 목적지 변경을 사용할 수 있습니다. 일행 차량의 안내 변경에는 해당 운전자의 수락이 필요합니다.

## 구성

| 영역 | 구현 |
| --- | --- |
| 차량 앱 | Kotlin/Java, Android, Pleos SDK |
| 차량 위치·음성·내비 연동 | Pleos Vehicle / FusedLocation / SpeechToText / TextToSpeech / NaviHelper |
| 모임·상태·이벤트·추천 | Python 서버와 SQLite |
| 문장 의도·후속 선택 해석 | PC의 Ollama `qwen3:4b` |
| 지도·장소 | OpenStreetMap 데이터 및 장소 색인 |
| 경로 비교 | OSRM 응답 기반 비교; 실제 길안내는 Pleos |

페어링, 목적지 제안/수락, 이벤트 전달, AI 맥락 구성, 후보 선택 검증 및 화면은 프로젝트에서 구현했습니다. ChatGPT 서비스에 연결된 앱은 아닙니다.

## 준비 환경

- Python **3.10 이상** (서버는 표준 라이브러리 사용)
- Android Studio, Android SDK **37.0**, Platform-Tools
- JDK **25** (Gradle 실행), Gradle Wrapper 포함
- Pleos Connect **2.1** 에뮬레이터 2대와 Pleos 의존성 다운로드 환경
- Ollama 및 `qwen3:4b` 모델

처음 빌드할 때 Gradle·Android·Pleos 의존성을 내려받기 위한 인터넷 연결이 필요합니다. 개발 도구와 Pleos 시스템 이미지는 저장소에 포함하지 않습니다. 일반 스마트폰의 단독 APK 실행을 보장하는 앱이 아닙니다.

## Windows에서 빠르게 실행

```powershell
git clone https://github.com/Nowwwy/Car-pairing_Vwego.git
cd Car-pairing_Vwego
```

1. `00_Check_Environment.cmd`로 SDK·ADB·APK·모델 상태를 확인합니다.
2. Ollama를 설치한 뒤 `01_Setup_Local_AI.cmd`를 실행합니다.
3. `02_Build_App.cmd`를 실행합니다. 앱 빌드와 Android 단위 테스트 후 `dist/CarPairing-Integrated.apk`가 생성됩니다.
4. `Run-Two-Cars.cmd`로 모임 서버와 두 차량 시연을 시작합니다.
5. 1번 차량에서 모임을 만들고 2번 차량에 코드를 입력합니다. 목적지를 정하고 일행에서 수락합니다.

이미 제공받은 `CarPairing-v0.17-debug.apk`가 있으면 `dist` 폴더를 만들고 `CarPairing-Integrated.apk`라는 이름으로 복사하여 3단계 빌드를 생략할 수 있습니다. APK 설치·서버 연결 설명은 [APK 설치 안내](docs/APK_INSTALL.md)를 참고하세요.

기본 AVD 이름은 `Pleos_Connect_2_1`, `Pleos_Pairing_Follower_1` 또는 `CarPairing_Pleos_B`입니다. 기존 실행 서버가 있다면 포트와 버전을 확인하세요.

## 이미 부팅한 에뮬레이터 / macOS / Linux

```sh
python3 run_local.py check
python3 run_local.py build
python3 run_local.py server
# 별도 터미널에서 실행
python3 run_local.py setup --serial emulator-5554 --serial emulator-5556
```

Windows에서는 `python3` 대신 `py -3`를 사용할 수 있습니다. SDK 경로가 기본 위치와 다르면 `ANDROID_HOME`을 지정합니다. 다른 서버 포트는 `server --port 8768`과 `setup --port 8768` 양쪽에 동일하게 지정합니다. 앱의 `127.0.0.1:8765`는 ADB reverse로 PC 서버에 연결됩니다. setup은 기존 기기에 설치·연결하며 에뮬레이터를 새로 부팅하지 않습니다.

## 시연 순서

1. 호스트 생성 → 코드 참여 → 목적지 설정 → 일행 수락
2. “목적지 근처 카페로 변경해줘” → 후보 선택 → 변경 확인
3. “근처에 주차할 자리가 없어” → **변경된 목적지** 주변 주차장 후보
4. “그중에서 가장 가까운 곳으로 해줘” → 선택 확인 → 안내 변경
5. “일행에게 10분 정도 늦을 것 같다고 알려줘” → 상대 차량 알림

에뮬레이터의 호스트 마이크 접근과 앱 마이크 권한을 허용하세요. 마이크가 없으면 제보 화면의 텍스트 AI 처리를 사용할 수 있습니다.

## 검사

```sh
python3 -m unittest discover -v
./gradlew testDebugUnitTest assembleDebug
```

Windows: `gradlew.bat testDebugUnitTest assembleDebug`.

0.17 기능 검증: Windows Python **234개**, Android **41개**, 실제 로컬 모델/API 시나리오 **10개** 통과. 상세 범위는 [검증 기록](docs/VERIFICATION_v017.md)에 있습니다. 실제 사람의 마이크 발화·실차 주행·macOS/Linux 에뮬레이터 동작을 모두 검증했다는 의미는 아닙니다.

## 현재 한계

- 주차장 후보는 수록된 장소 색인 기준입니다. 모든 주변 주차장이나 실시간 빈자리·요금·영업 상태를 확인하지 않습니다.
- 경로 비교는 실시간 교통 정체를 반영한 최적 경로 보장이 아닙니다. 임의 주행 경로 자동 학습은 구현하지 않았습니다.
- 로컬 모델이 모든 표현을 정확히 이해한다고 보장하지 않습니다. 애매한 답은 재확인하고 목적지 변경은 별도 확인을 거칩니다.
- Pleos 기본 내비의 흰 지도 배경 문제는 별도 환경 문제로 남아 있습니다.
- 현재 서버는 개발·시연용입니다. 인터넷에 공개하는 운영 서비스로 배포하기 위한 구성은 포함하지 않습니다.

## 저장소와 배포

소스·테스트·필수 지도/폰트 데이터만 Git에 포함합니다. APK, SDK 경로, DB, 로그, 개인 키는 `.gitignore`로 제외합니다. APK는 GitHub Releases의 첨부 파일로 관리하는 것을 권장합니다.

[GitHub 업로드 안내](docs/GITHUB_UPLOAD.md) · [외부 구성요소 안내](THIRD_PARTY_NOTICES.md)
