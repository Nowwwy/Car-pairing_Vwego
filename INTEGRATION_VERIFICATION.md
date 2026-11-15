# CarPairing 0.15 검증 기록

검증일: 2026-11-15. 기준: 제공된 Mac 0.13.1 ZIP + 최신 통합 0.14.4 ZIP.

## 완료한 검사

- Python `python3 -m unittest discover`: **176개, 실패 0, 오류 0**. 기존 상속 테스트의 중복 실행을 포함한 unittest 표시 수입니다.
- Python compileall 구문 검사 통과.
- 새 동작을 먼저 실패하는 테스트로 확인: SDK/ADB 경로·기기 선택, 기존 서버 버전 거부, 경유지·체류시간 포함 비교, 실제 시간/거리 순위, 잘못된 제공자 응답, 완료 장소 포함, 중복 재시도, 자신의 최근 변경만 참조, 오래된/타인 맥락 차단, 빈 Android 내비 맥락, 실제 적용시각 유지, 잘못된 시각 거부, 기존 주차 체류시간 유지, v0.14 저장 요청의 재시도 호환.
- 코드 검토 지적 수정: 기본 메시지 버튼과 초안 유지, 늦은 경로 보고가 자동 전달되기 전 현재 안내 맥락 재확인, 빈 SDK 맥락의 확인 이력 보완, 전송 지연 시 완료 시각 재설정 방지.
- Windows/macOS/Linux 경로 탐색은 임시 디렉터리와 플랫폼 분기 테스트로 확인. 실제 세 OS 에뮬레이터 실행을 뜻하지 않음.
- Unix Gradle 실행기의 CRLF 문제를 실제 실행에서 재현한 뒤 LF로 수정. 셸 파싱 통과 후 Gradle 배포 다운로드 단계로 진입.

## 완료하지 못한 검사

- **Android assembleDebug/testDebugUnitTest 미완료**. 현재 작업 환경에서 Gradle 9.3.1 공개 다운로드가 `Network is unreachable`로 실패. SDK/Pleos 의존성도 설치돼 있지 않음.
- Windows KYSDESKTOP에는 별도 준비 폴더만 생성했으며 수정 코드·APK를 적용하지 않음. 자동 승인 검토가 소스 전송의 정확한 대상 PC/경로에 대한 명시적 승인을 요구해 전송을 거절함.
- 실제 사람의 마이크 발화→후보 선택→Pleos 경로 반영→상대 차량 TTS 전체 흐름 미검증.
- 새 경로 비교 기능의 실제 외부 OSRM 응답·Android 지도 미리보기 미검증. 단위 테스트는 통제한 실제 API 형식의 응답으로 확인.
- 실시간 교통, 장소 재고, 경로 자동 학습, 실차 안정성을 완료했다고 주장하지 않음.

## 재개 절차

정확한 수정 소스 전송 대상: KYSDESKTOP의 `C:\Users\PCPC\AppData\Local\CarPairingUpgrade015\project`.
현재 실행 중인 원본은 다른 폴더에 유지되어 있음. 승인 후 수정 소스를 이 별도 폴더에 반영하고 `python -X utf8 run_local.py build`로 빌드/Android 테스트를 실행한다. 성공한 APK만 동일 서명·앱 데이터 보존 방식으로 설치하고 두 차량 동작을 점검한다.

이전 버전 검사 이력은 docs/VERIFICATION_v0144_HISTORY.md에 별도로 보존했다. 해당 버전의 빌드 성공을 0.15의 결과로 대신하지 않는다.
