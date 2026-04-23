# NHSignalBuddy

Android signal app for US stocks (manual execution on NH app).

## Universe (default)
- MRK
- WMT
- CAT
- MU
- STX

## Rules
- Buy candidate: SMA50 > SMA200 and VIX < 25
- Sell signal: SMA50 < SMA200 OR +15% TP OR -8% SL

## Structure
- `signal_backend/generate_signals.py`
  - Pulls Yahoo daily prices
  - Calculates rule state
  - Writes `signal_backend/signals_latest.json`
- `app/`
  - Android UI skeleton (Kotlin) that can render signal cards

## Quick start (backend)
```powershell
python "C:\Users\windows\Desktop\NHSignalBuddy\signal_backend\generate_signals.py"
```

Then check:
- `C:\Users\windows\Desktop\NHSignalBuddy\signal_backend\signals_latest.json`

## Macro trend cap (KR + US, PC와 동일 JSON)

### 내장 `macro_scenario_input.json` 이란?

- `app/src/main/assets/macro_scenario_input.json` 은 **APK에 포함(내장)** 되며, **앱을 다시 빌드·설치**할 때만 휴 대기에 반영됩니다.
- **「macro 새로고침」**은 캐시를 비우고 **현재 쓰는 소스(내장이면 APK 안의 assets)** 를 다시 읽을 뿐이므로, **PC에서 JSON만 편집한 것이 자동으로 폰에 들어가지는 않습니다.**
- PC와 동일한 최신 json을 쓰려면: (1) **「macro JSON 파일 선택」**으로 휴 대기上的 파일(다운로드·Syncthing 동기화 등)을 지정하거나, (2) Android Studio로 앱을 **다시 Run/설치**해 내장 사본을 갱신하세요.

### 권장: Syncthing `kiwoom` 폴더 — **「kiwoom 폴더 선택 (Syncthing·트리)」** (v1.0.2+)

- **단일 파일** 피커(`macro JSON 파일 선택`)는 Syncthing이 둔 **앱 전용·수신 전용 경로**에서 문서를 열 권한을 못 주는 경우가 많습니다.
- 대신 **폴더(트리) 권한** `ACTION_OPEN_DOCUMENT_TREE`로 **PC와 동기화된 `kiwoom` 루트**를 한 번만 지정하면, 앱이 그 안의 **`macro_scenario_input.json`** 를 직접 읽습니다. 이후 **「macro 새로고침」**으로 Syncthing이 덮어쓴 최신 본이 반영됩니다.
- 트리와 단일 파일은 **둘 중 하나만** 유지됩니다(파일 선택 시 트리 URI 해제, 그 반대도 동일).
- Syncthing **휴 측 경로**를 `문서`·`다운로드`처럼 피커에 잘 보이는 곳으로 두면 선택이 쉽습니다(앱 **설정 → 폴더**에서 로컬 경로 변경).

### 권장: SAF 단일 파일 (재빌드 불필요)

- 메인 화면 **「macro JSON 파일 선택」** → `macro_scenario_input.json` 지정 (JSON / 텍스트). 피커는 가능하면 **Documents** 루트에서 열림(`EXTRA_INITIAL_URI`, API 26+).
- **「macro 새로고침」**: `MacroScenarioRepository.loadMacroData(forceReload = true)` 로 디스크/동기화 반영 후 헤더·종목당 캡 참고만 갱신 (네트워크 TR 없음). 동일 소스에 대해 **5초 이내** 반복 호출은 메모리 캐시로 I/O 생략(수동 새로고침은 항상 최신 읽기).
- **Syncthing + macro 새로고침 (추천 워크플로)**  
  1. PC: `키움/macro_scenario_input.json` 을 편집·저장한다.  
  2. Syncthing으로 PC 폴더와 폰의 동일 폴더(예: Download/키움 등)를 맞춘다.  
  3. 앱에서 한 번 **「macro JSON 파일 선택」**으로 그 파일을 지정해 두면, 이후에는 파일 경로가 같을 때마다 동기화만으로 내용이 갱신된다.  
  4. 폰에 파일이 반영된 뒤 앱에서 **「macro 새로고침」**을 누르면(또는 **시작 시 자동** 켜면 실행 시) 최신 트렌드 캡이 반영된다.  
  5. 재부팅 뒤 가끔 **영구 URI 권한이 사라지면** 읽기 실패 시 내장 스냅샷으로 폴백되며, 그때는 **파일을 다시 선택**하면 된다. (Logcat `MacroScenarioRepo` 태그에 `read_persist_matched` 로 디버깅 가능)
- **「시작 시 자동」** 체크 시 앱 실행 직후 macro JSON을 한 번 더 읽음.
- 선택한 `Uri`는 `pref_macro_json_uri` 로 저장되고 **읽기 영구 권한**(`takePersistableUriPermission`, 실패 시 세션 권한)을 사용합니다.
- **「내장으로」** 는 URI를 지우고 `assets/macro_scenario_input.json` 스냅샷만 사용합니다.

### 폴백

- URI가 없거나 읽기 실패 시: **내장 assets** JSON으로 표시하고, 오류 시 빨간 안내 + 파일 선택 버튼 강조.

### 표시 내용

- 상단·헤더·종목 카드: **US 종목당 유효 달러**, `grok.kr_regime_cap_krw` 시 **KR 참고 캡(× ai)**.

## Next steps
자세한 완료·잔여 과제: [`docs/IMPROVEMENTS.md`](docs/IMPROVEMENTS.md)

1. ~~Create Android Studio project in `NHSignalBuddy/app`~~
2. ~~Connect UI to `signals_latest.json` (local first)~~ — **「시그널 JSON 선택」** (SAF) + **「Yahoo로」** 로 전환, 실패 시 Yahoo·assets
3. Add FCM push for BUY/SELL transitions (선택) — 앱은 이미 **로컬** 전이 알림 지원
