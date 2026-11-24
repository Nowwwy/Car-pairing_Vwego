# 개발 및 실행 가이드

[프로젝트 소개](../README.md)

## 준비 환경

- Python **3.10 이상** (서버는 표준 라이브러리 사용)
- Android Studio, Android SDK **37.0**, Platform-Tools
- JDK **25** (Gradle 실행), Gradle Wrapper 포함
- Pleos Connect **2.1.0** 에뮬레이터 2대와 Pleos 의존성 다운로드 환경
- Ollama 및 `qwen3:4b` 모델

개발 및 시연에 사용한 에뮬레이터는 **Pleos Connect 2.1.0 / Android API 34**, 화면은 **2560 × 1440 px** Automotive 프로필입니다. 개발 기록에는 Cold Boot와 AVD당 RAM 4 GB가 명시되어 있습니다. [개발 환경 기록](../DESIGN_GUIDE_AUDIT.md) · [Pleos 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

처음 빌드할 때 Gradle·Android·Pleos 의존성을 내려받기 위한 인터넷 연결이 필요합니다. 개발 도구와 Pleos 시스템 이미지는 저장소에 포함하지 않습니다. 일반 스마트폰의 단독 APK 실행을 보장하는 앱이 아닙니다.

## Windows에서 빠르게 실행

```powershell
git clone https://github.com/Nowwwy/Car-pairing_Vwego.git
cd Car-pairing_Vwego
```

1. `00_Check_Environment.cmd`로 SDK·ADB·APK·모델 상태를 확인합니다.
2. Ollama를 설치한 뒤 `01_Setup_Local_AI.cmd`를 실행합니다.
3. 저장소에 포함된 release APK로 바로 시연할 수 있습니다. 개발용 디버그 APK가 필요한 경우 `02_Build_App.cmd`를 실행합니다.
4. `Run-Two-Cars.cmd`로 모임 서버와 두 차량 시연을 시작합니다.
5. 1번 차량에서 모임을 만들고 2번 차량에 코드를 입력합니다. 목적지를 정하고 일행에서 수락합니다.

기본 AVD 이름은 `Pleos_Connect_2_1`, `Pleos_Pairing_Follower_1` 또는 `CarPairing_Pleos_B`입니다. 기존 실행 서버가 있다면 포트와 버전을 확인하세요.

## 이미 부팅한 에뮬레이터 / macOS / Linux

```sh
python3 run_local.py check
python3 setup_local_ai.py
python3 run_local.py server
# 별도 터미널에서 실행
python3 run_local.py setup --apk apk/CarPairing-Integrated.apk --serial emulator-5554 --serial emulator-5556
```

Windows에서는 `python3` 대신 `py -3`를 사용할 수 있습니다. SDK 경로가 기본 위치와 다르면 `ANDROID_HOME`을 지정합니다. 다른 서버 포트는 `server --port 8768`과 `setup --port 8768` 양쪽에 동일하게 지정합니다. 앱의 `127.0.0.1:8765`는 ADB reverse로 PC 서버에 연결됩니다. setup은 기존 기기에 설치·연결하며 에뮬레이터를 새로 부팅하지 않습니다.

## 검사

```sh
python3 -m unittest discover -v
./gradlew testDebugUnitTest assembleRelease
```

Windows: `gradlew.bat testDebugUnitTest assembleRelease`.

첨부 소스의 Windows 검증 기록에는 Python 234개, Android 41개, 로컬 모델/API 시나리오 10개 통과와 Pleos 에뮬레이터 2대 설치 확인이 포함되어 있습니다. [0.17 검증 기록](../CONTEXT_VERIFICATION_017.md)

제출 검증에는 macOS Python 3.12 서버 테스트 234개, Android 단위 테스트 41개, release 빌드 검사와 APK 서명 검증이 포함됩니다.

## 저장소와 배포

설치 파일은 [apk/CarPairing-Integrated.apk](../apk/CarPairing-Integrated.apk)에 있습니다. Pleos 에뮬레이터에 설치할 수 있도록 서명한 release APK입니다. 기존에 다른 키로 서명한 개발용 APK가 설치된 경우 새 에뮬레이터에서 실행하세요.

`assembleRelease`로 생성한 `app/build/outputs/apk/release/app-release-unsigned.apk`는 설치 전에 서명이 필요합니다. 개발용 빌드 도구는 `dist/CarPairing-Integrated.apk`에 별도의 디버그 APK를 생성합니다.
