# 갤럭시 에어마우스

갤럭시의 동작 센서와 화면 터치로 Windows PC를 조작하는 Bluetooth LE 시험 프로젝트입니다.

> **개발 중인 시험판입니다.** 여러 기기에서의 호환성, 재연결, 연결 승인과 보안, 드래그 지연, 우클릭, 키보드 입력 및 배포는 충분히 검증되지 않았습니다. 일반 사용자 배포용이 아닙니다.

현재 개발 버전에는 갤럭시 센서 기반 커서 이동, 터치 클릭·스크롤, 길게 눌러 드래그, 텍스트 전송, Windows 감도 조절 및 연결 승인 코드가 있습니다. 일부 기능은 실기기에서 초기 동작을 확인했지만, 최신 소스 전체의 종단 간 동작은 검증되지 않았습니다. Windows 수신기는 입력에 Bluetooth 연결 암호화와 인증을 요구하며, 기기 승인 후 제어하도록 설계되었습니다. 보안 검증은 별도로 필요합니다.

## 구성

- `prototype/android/`: Android 앱 (Java, Android Gradle Plugin)
- `prototype/windows/`: Windows 수신기 (C# / .NET)
- `prototype/motion-tests/`: 움직임 필터 검사
- `prototype/pairing/`: 개발용 무선 디버깅 연결 도구
- `GALAXY_MOUSE_PLAN.md`: 초기 개발 계획
- `prototype/README.md`: 개발 환경과 시험 기록

## 개발 환경과 빌드

Windows x64에서 .NET 10 SDK, JDK 17, Gradle 8.14.6, Android SDK 플랫폼 35와 빌드 도구 35.0.0을 사용했습니다. Android 앱은 `minSdk 26`으로 설정되어 있지만 해당 범위의 실제 기기 호환성을 검증한 것은 아닙니다. Gradle은 별도 설치해야 하며, 최초 빌드 시 공식 저장소에서 Android Gradle Plugin 등을 내려받습니다.

```powershell
dotnet build prototype/windows/ConnectionTest.csproj
dotnet run --project prototype/motion-tests/MotionTests.csproj
gradle -p prototype/android assembleDebug lintDebug testDebugUnitTest
```

Windows의 한글 경로에서 Android Gradle Plugin 경로 검사에 실패한 적이 있어 ASCII 경로에서 빌드하는 것이 좋습니다. Android 단위 테스트는 현재 없습니다. 빌드 결과는 디버그용이며 배포 서명이 되어 있지 않습니다.

## 확인된 범위

한 대의 갤럭시와 한 대의 Windows PC에서 Bluetooth 시험 신호, 커서 상하좌우 이동, 터치 클릭과 세로 스크롤을 확인했습니다. 이후 성능과 재연결 관련 코드가 변경되어 **현재 저장소의 최신 조합은 다시 실기기 테스트해야 합니다.**

## 남은 작업

- 드래그 중 창 지연과 연결 복구 문제 재현·수정 및 재검증
- 제어 승인, 연결 해제, 드래그 해제와 미승인 기기 차단 검증
- 글자 전송, 우클릭, 다양한 화면 크기 및 기기 호환성 검증
- 앱 서명, 설치·업데이트, 배포 및 사용 안내 준비

아이디어와 원본 기능의 참고 프로젝트: [Coolerwave/AimMac](https://github.com/Coolerwave/AimMac).
