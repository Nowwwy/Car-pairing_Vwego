# Pleos 디자인 가이드 검토 및 적용 기록

2026-11-11 · 제공된 34개 URL의 문서 본문 검토.
일부 한국어 URL의 직접 조회 실패는 공식 영문 동일 페이지로 보완했다. Toast Popup은 Tabs 문서의 링크로 확인했다.
표는 모든 컴포넌트를 구현했다는 뜻이 아니라 2번 범위에서의 적용 여부를 구분한다.
SVG 그림의 모든 치수 및 브랜드별 마스킹을 실측 검증하지는 않았다.

| 가이드 | 2번 구현에서의 처리 | 공식 문서 |
|---|---|---|
| 기본 원칙 | 핵심 정보와 명확한 동작, 정적인 홈 화면. 대비 검사 수행. 실제 주행 규제 연동은 추후. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro) |
| 레이아웃 | 시스템 영역과 앱 창 분리. 현재 앱 창 실측 너비/높이에 따라 가로·세로 재배치. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout) |
| Status bar | OS 소유 영역. 앱 내부에 시계·네트워크·도어 상태를 복제하지 않음. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/status-bar) |
| GNB | OS 소유 영역. 내비·공조·최근 앱 버튼을 앱 내부에 복제하지 않음. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/gnb) |
| App library | OS 소유 영역. 별도 앱 서랍 구현 없이 런처 진입점 제공. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/app-library) |
| Window policy | 고정 방향을 선언하지 않고 resizeableActivity 활성화. 실제 Pleos 창 전환 검증은 남음. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window) |
| Colors | Car Pairing 브랜드 토큰 유지, 역할별 배경/표면/글자/강조 분리, 대비 계산. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/colors) |
| Typography | 본문·제목·레이블 위계와 sp 사용. Asta Sans 400·500을 번들하고 시스템 폴백 지정. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/typography) |
| Iconography | 홈은 모호한 아이콘 대신 텍스트 동작 제공. 시스템 아이콘 임의 복제 없음. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/iconography) |
| App icon | 임시 Adaptive Icon의 foreground/background 분리. 최종 브랜드 마스킹 검증은 남음. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/contents-creation/app-icon) |
| Animation | 자동 이동·점멸·자동 스크롤 없음. 버튼 누름 피드백만 제공. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/animation) |
| Shadow | 기본 카드의 낮은 높이와 표면 차이 사용. 팝업 고도는 해당 기능 개발 때 적용. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/foundations/shadow) |
| Basic button | 주요/보조 동작을 구분하고 누름 효과 및 최소 높이 제공. 로딩 상태는 서버 연동 때 구현. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/basic-button) |
| Toggle button | 홈에 이진 선택이 없어 추가하지 않음. 후속 화면에서 상태·비활성 표현 적용. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/toggle-button) |
| Text button | 추가 정보와 뒤로 가기 역할의 텍스트 동작. 단순한 동작 문구 사용. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/text-button) |
| Icon button | 홈에 아이콘 전용 동작은 없음. 추후 접근성 레이블과 영역 확인. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/icon-button) |
| Keypad | 이번 입력 정보는 읽기 전용 시안. 실제 키패드·음성 입력은 아직 미구현. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/actions/keypad) |
| Bar | 앱 콘텐츠에 세로 ScrollView 제공. 시스템 Window bar는 복제하지 않음. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/bar) |
| Controllers | 볼륨·온도 등 값 조절은 홈에 필요하지 않아 미추가. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/controllers) |
| Indicator | 다중 페이지 캐러셀이 없어 미추가. 선택과 현재값 구분 원칙 검토. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/indicator) |
| Dropdown | 홈에 추가 선택 메뉴가 없어 미추가. 추후 열림/닫힘/비활성 상태 적용. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/dropdown) |
| Text field | 모임 정보는 라벨과 읽기 전용 값으로 표시. 편집/오류 검증은 후속 범위. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/fields/text-field) |
| Number field | 초대 코드 숫자 확인 레이아웃. 실제 입력/유효성 확인 및 연결은 미구현. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/fields/number-field) |
| Page navigation | 정보 화면의 이전 버튼과 시스템 Back으로 홈 복귀. 최상위 Back은 앱을 뒤로 보냄. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/page-navigation) |
| System popup | 현재 홈에 필요한 확인 대화상자가 없어 미추가. 추후 확인/취소를 명확히 분리. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/system-popup) |
| Toast popup | 앱 영역 내 일시 메시지와 여백 원칙 확인. 이번 홈에는 불필요한 메시지 미추가. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/popups/toast-popup) |
| Segmented menu / Tabs | 홈에 병렬 콘텐츠 탭이 없어 미추가. 선택 상태가 필요한 후속 화면용. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/segmented-menu) |
| Checkbox | 다중 선택 항목이 없어 미추가. 선택/누름/비활성 원칙 검토. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/selections/checkbox) |
| Radio | 단일 선택 항목이 없어 미추가. 후속 선택 화면의 상호배타성 기준. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/selections/radio) |
| Switch | 이진 설정이 없어 미추가. 후속 설정 화면에 적용 예정. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/selections/switches) |
| System notification | OS 알림과 앱 내부 메시지 구분. 실제 알림 서비스 미구현. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/notification/system-notification) |
| Spinner | 날짜·시간 선택이 없어 미추가. 일반 로딩 애니메이션과 구분. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/spinner) |
| Toast | 필요할 때 앱 내부 하단 여백을 유지하도록 문서화. 현재 미사용. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/toast) |
| Widgets | OS 위젯 핸들/페이지/정보 영역 확인. 홈 앱 창 구현에 위젯을 억지로 포함하지 않음. | [문서](https://document.pleos.ai/docs/connect/guide/docs-design/components/widgets) |

## 실행·배포 기준

Pleos 에뮬레이터 공식 설치 가이드: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator

AGP 9.1.1 호환성: https://developer.android.com/build/releases/agp-9-1-0-release-notes

공식 Pleos 이미지 저장소를 SDK Manager에 추가했다. 사용자 승인 후 Pleos Connect 2.1.0 API 34 이미지를 설치하고 2560×1440 Automotive 프로필로 AVD를 생성했다. Cold boot와 RAM 4GB를 적용했다.

## Pleos 에뮬레이터 검증

Pleos 앱 라이브러리에서 실행하고 창 핸들로 가로·세로 창을 전환했다. 두 창에서 홈 카드 배치, 모임 만들기 및 초대 코드로 참여 화면, 앱과 시스템 뒤로 가기를 확인했다. 창 크기를 바꿔도 초대 코드 화면이 유지됐다. 세로 창에서 글꼴 배율 2.0으로 화면 표시와 초대 코드 진입·복귀를 확인했다. 앱 라이브러리를 열었다 닫은 뒤 모임 만들기 화면으로 복귀했다.

실차와 다른 Pleos 디스플레이 프로필 검증은 남아 있다.

