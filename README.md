# yacht

요트 다이스(닌텐도 『세계의 아소비 대전 51』 규칙)에서 1:1 **승률**을 최대화하는 안드로이드 AI 앱.

- 설계·측정 결과: [docs/DESIGN.md](docs/DESIGN.md)
- APK: GitHub Actions의 **Android APK** 워크플로 실행 결과에서 `yacht-ai-apk` 아티팩트(약 63 MB)를 받아 설치

## 앱이 하는 일
- **실전 도우미**: 스위치에서 나온 주사위와 상대 점수를 입력하면, 고정할 주사위와 기록할 칸을 승률 기준으로 추천한다.
- **AI와 대전**: 앱이 주사위를 굴리고 승률 최대화 AI와 겨룬다.
- **설명**: 왜 그 수가 좋은지, 다른 선택지는 왜 못한지, 지금 상황이 어떤지, 상대 턴이 내 승률에 어떤 영향을 줬는지를 자연스러운 한국어로 알려 준다. 문장 속 숫자는 모두 엔진이 계산한 값이다.
- **기록·복기**: 실전 승률과 95% 신뢰구간, AI 예측 승률이 실제와 맞는지(보정), 판마다 추천과 다르게 둔 수와 그 이유, 게임 총평을 보여 준다.
- **승률 검증**: 같은 주사위로 승률 AI와 기대점수 AI를 휴대폰에서 직접 겨뤄 차이를 측정한다.

## 계산 방식 요약
- 상대는 기대점수 최적으로 둔다고 가정한다.
- 첫 추천은 즉시 나오고, 계산 시간(1 / 2.5 / 4초) 안에서 더 깊게 계산한다.
- 남은 칸이 약 8~9개 이하이면 상대 점수 분포에 대해 **끝까지 정확**하게 계산한다.
- 약 5개(빠른 기기는 6개) 이하이면 상대의 실제 결과에 대응하는 **대결 표**를 백그라운드에서 한 번 만들어 남은 게임 내내 재사용한다(이후 턴은 계산 0).
- 앱이 화면에서 사라지면 백그라운드 계산을 멈춘다.

## 개발

```bash
./gradlew :engine:test                                   # 엔진 테스트 (정확성·시뮬레이션 일치 검사 포함)
./gradlew :tools:installDist
tools/build/install/tools/bin/tools gen --out tables     # 계산 표 생성 (약 5초)
tools/build/install/tools/bin/tools duel --tables tables --games 2000 --players ev,win,duel-4e9   # 같은 주사위 비교
tools/build/install/tools/bin/tools bench --tables tables [--only search|duel]                    # 계산 시간
tools/build/install/tools/bin/tools explain --tables tables --games 2                             # 샘플 게임의 설명 문장 전부 출력
./gradlew -p uicheck compileKotlin                        # 안드로이드 SDK 없이 UI 컴파일 검사
# APK (안드로이드 SDK 필요)
tools/build/install/tools/bin/tools gen --out android/app/src/main/assets
./gradlew -p android :app:assembleRelease
```

CI는 APK를 빌드하는 작업과, Compose Desktop으로 주요 화면을 렌더링해 `ui-screenshots` 아티팩트로 올리는 작업을 함께 돈다.

`android/app/personal.keystore`는 개인 사이드로드용 서명 키입니다(새 빌드를 기존 앱 위에 덮어 설치하기 위함). 배포용이 아닙니다.

`prototype/ev_solver.py`는 처음 계산 검증에 쓴 Python 기준 구현(기대점수만)입니다.
