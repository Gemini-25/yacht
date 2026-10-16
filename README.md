# yacht

요트 다이스(닌텐도 『세계의 아소비 대전 51』 규칙)에서 1:1 승률을 최대화하는 안드로이드 AI 앱.

- 설계: [docs/DESIGN.md](docs/DESIGN.md)
- APK: GitHub Actions의 **Android APK** 워크플로 실행 결과에서 `yacht-ai-apk` 아티팩트를 받아 설치

## 개발

```bash
./gradlew :engine:test                                   # 엔진 테스트
./gradlew :tools:installDist
tools/build/install/tools/bin/tools gen --out tables     # 표 생성 (약 5초)
tools/build/install/tools/bin/tools sim --tables tables --games 6000 --a win --b ev
tools/build/install/tools/bin/tools bench --tables tables
./gradlew -p uicheck compileKotlin                        # 안드로이드 SDK 없이 UI 컴파일 검사
# APK (안드로이드 SDK 필요)
tools/build/install/tools/bin/tools gen --out android/app/src/main/assets
./gradlew -p android :app:assembleRelease
```

`android/app/personal.keystore`는 개인 사이드로드용 서명 키입니다(새 빌드를 기존 앱 위에 덮어 설치하기 위함). 배포용이 아닙니다.

`prototype/ev_solver.py`는 처음 계산 검증에 쓴 Python 기준 구현입니다.
