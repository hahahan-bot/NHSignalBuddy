# NHSignalBuddy 개선·로드맵

README **Next steps**와 실제 앱 동작을 맞춘 체크리스트입니다.

## 완료·반영

| 항목 | 설명 |
|------|------|
| Android 앱 (워치리스트, 포지션, Yahoo 시그널) | `MainActivity` + `YahooSignalEngine` |
| 네트워크 실패 시 `assets/signals_latest.json` 폴백 | `SignalRepository` + `refreshSignals` |
| 매수/매도 **전이** 시 로컬 알림 | `SignalAlertManager` (FCM 아님) |
| `macro_scenario_input.json` — 단일 파일·트리(SAF) | `MacroUriPreferences` + `MacroScenarioRepository` |
| **`signals_latest.json` 외장 연결 (SAF)** | `SignalUriPreferences` + 파일 선택 시 Yahoo 대신 PC본 사용, 실패 시 기존 Yahoo·assets 흐름으로 폴백 |

## 남은 선택 과제 (선행 조건·비용)

| 항목 | 메모 |
|------|------|
| **FCM**으로 BUY/SELL 푸시 | Firebase 프로젝트, 서버/클라우드 스케줄러, 앱 `google-services.json` 필요. 로컬 알림은 이미 있음. |
| 시그널 **폴더 자동 스캔** (트리) | `macro`와 달리 파일명·경로를 고정할지 정책 결정 후 `DocumentFile` traverse |

## 흐름 요약

- **시그널 JSON 미지정**: Yahoo 실시간 → 실패 시 `assets` 스냅샷.  
- **시그널 JSON 지정**: 지정 URI에서 로드(포지션 `SignalRules.applyPosition` 적용) → 읽기 실패 시 토스트 후 Yahoo·assets와 동일 폴백.  
- **macro**와 **signals**는 서로 독립 URI.
