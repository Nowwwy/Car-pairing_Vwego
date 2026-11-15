# Car Pairing 시나리오 A/B 완료 계획

> For agentic workers: Use superpowers:executing-plans for integration and verification.

**Goal:** 원본 1차 기획서 4번의 A/B 흐름을 Pleos 앱에서 구현하고 실제 두 AVD로 검증한다.
**Architecture:** 기존 Kotlin 앱·Python 동기화 서버를 유지한다. Pleos는 위치/차량상태/음성/목적지·경유지 연동에 사용하며 외부 OSRM은 별도 출발지의 도로 예상시간을 제공한다.
**Tech Stack:** Kotlin, Pleos Connect SDK, Python, SQLite, OSM/FOSSGIS.
**Spec:** upload/1차 기획서.docx §4·§5; 사용자의 자율 구현 승인.

## Global Constraints
- 운전자 수락 전 내비 목적지/경유지를 바꾸지 않는다.
- 실제 교통이 없는 예상시간은 명시하고 API 실패 시 임의 시간으로 대체하지 않는다.
- 시나리오 A의 여러 일행과 B의 휴게소 수락/거절을 검증한다.
- 원본 §5-4의 추적 모드 등 확장은 MVP 완료와 구분한다.

## Review Focus
- 오래된 위치/ETA와 목적지 변경 후 늦게 도착한 응답.
- 느린 AI/장소 API가 모임 동기화를 막는 문제.
- 이미 도착한 차에 만차 경로변경 제안이 가는 문제.
- 네트워크/지도 타일 장애에서 빈 화면과 앱 충돌.
- 여러 일행의 선택 변경, 내비 SDK 초기화 지연.

## Tasks
- [x] NavRoutePreview/RoomApi/MainActivity: 도로 ETA와 출처 공유, 일행 선택, 독립 전송 큐; 실패/오래된 값 테스트.
- [x] server/message_intent: A/B HTTP 회귀검사, 목적지 변경 원자성, 부정문 경유 제안 방지, ETA 메타데이터 검증.
- [x] StreetMapView/trip_planner: 지도 복원·저장 타일, 지역 대체 지도, 외부 경로 실패·캐시 검증.
- [x] run_two: 기존 AVD 우선·2GB·후발대 host GPU, 재실행 시 최신 APK 사용.
- [x] Python 전체·Android 빌드/단위검사, 두 AVD 실제 제보/만차/거절/경유 수락, 4인 서버검사.
- [x] 짧은 설명서, 기능대조표, 검증결과와 미검증 경계 작성; 최신 APK/소스 저장.

실차·실제 마이크/청감 검증은 미완료로 분리. 실제 확인 범위는 docs/VERIFICATION_20261114.md에 기록.
