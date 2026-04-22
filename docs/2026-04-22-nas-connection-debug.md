# NAS 연결 실제 디버깅 기록 (2026-04-22)

Phase 2 구현이 끝나고 실제 NAS 에 처음 붙여본 날, "연결 테스트" 가 계속 실패해서
원인을 추적한 전체 과정. 단일 버그가 아니라 **다섯 개의 독립된 문제가 연쇄적으로
덮여 있었고**, 표면의 증상만 보고 하나만 고치면 그 아래 숨은 다음 것이 드러나는
식으로 풀렸다.

## 최종 결론 (TL;DR)

| # | 문제 | 증상 | 수정 |
|---|------|------|------|
| 1 | SK 브로드밴드 공유기 **hairpin NAT 미지원** | 같은 LAN 폰이 DDNS 공인 IP 로 붙으려다 TCP RST | LAN URL 지원 추가 → LAN probe 성공 시 내부 경로로 붙음 |
| 2 | Android API 28+ **cleartext HTTP 차단** | LAN probe 가 `IOException: Cleartext HTTP traffic not permitted` 로 즉시 실패 | `AndroidManifest.xml` 에 `usesCleartextTraffic="true"` |
| 3 | Synology Download 404 응답이 **HTML body** | fetchOne 이 `<!DOCTYPE` 를 JSON 파싱하려다 예외 → `connected=false` 캐스케이드 → 모든 push 스킵 | 본문이 `<` 로 시작하면 "파일 없음" 으로 간주, `null` 반환 |
| 4 | Upload 엔드포인트가 **`_sid` 를 URL 쿼리로 요구** | form field 로만 SID 전달하면 DSM 이 `code 119 (SID not found)` 로 거부 | URL 에 `?_sid=…` 추가 (form field 와 병용) |
| 5 | FileStation 경로는 **공유 폴더 루트부터** | `/소설/.minseo/` 는 DSM 에 공유 폴더가 아니라 `code 408 (No such file or directory)` | `DsFileConfig.PATH = /video/.minseo/` (Minseo21 과 같은 공유 폴더 재활용, 파일명 패턴이 달라 충돌 없음) |

## 증상

`NasSettingsActivity` 의 "연결 테스트 후 저장" 탭 → 빨간색 에러 메시지.

```
java.net.ConnectException:
  Failed to connect to gomji17.synology.me/211.49.69.174:5001
```

사용자 컨텍스트:
- NAS 자체는 정상 — 다른 폰에서 WiFi 로 접속 가능 확인
- 테스트 폰도 WiFi (같은 공유기) 연결 상태
- 앞서 시드된 기본값 (host=`gomji17.synology.me`, port=5001, user/pass/path) 그대로

## 잘못된 초기 가설

| 가설 | 각하 사유 |
|------|-----------|
| NAS 가 꺼져 있다 / 포트 막혀있다 | 다른 폰에서 정상 작동 증언으로 기각 |
| 코드 버그 — URL 조립 문제 | 로그에 `baseUrl=https://gomji17.synology.me:5001` 정상 기록 |
| 셀룰러 데이터에서 5001 차단 | 폰이 WiFi 연결 — 해당 없음. 단 "경로 이슈" 방향 감은 맞음 |

## Issue #1 — Hairpin NAT

### Trace

폰의 네트워크 상태 확인.

```bash
adb shell ip route
# → 192.168.45.0/24 dev wlan0 proto kernel scope link src 192.168.45.210

adb shell dumpsys connectivity | grep -E "WIFI CONNECTED|SSID|lp\{"
# → SSID: "SK_9E84_5G", IP: 192.168.45.210, gateway: 192.168.45.1
```

Minseo21 `DsFileConfig.LAN_URL` 이 `http://192.168.45.65:5000` 였음 → **폰과 NAS
가 같은 서브넷 `192.168.45.0/24`** 에 있음 확인.

에러의 타이밍:

```
23:20:35.417 I NAS: DsAuth.init baseUrl=https://gomji17.synology.me:5001
23:20:35.460 E NAS: java.net.ConnectException: Failed to connect to gomji17.synology.me/211.49.69.174:5001
```

`init` 과 실패 사이 **43ms** — 10초 connect timeout 에 비해 너무 빠름 → 타임아웃이
아니라 즉시 거부(RST). 같은 LAN 에 있는 기기가 자기 WAN 공인 IP 로 되돌아오는
경로를 공유기가 반영(reflect)하지 않는 "**NAT hairpin 미지원**" 패턴과 일치.

### 수정

- `DsFileConfig` 에 `LAN_HOST` / `LAN_PORT` 추가 (gitignore 된 파일이라 실제 IP 는
  레포에 안 올라감, `.sample` 에는 placeholder).
- `NasSyncManager.Prefs` 인터페이스에 `getLanHost()` / `getLanPort()` 추가,
  `save()` 파라미터 확장.
- `initDsAuthFromPrefs` 가 두 URL 모두 만들어 `DsAuth.init(baseUrl, lanUrl, ...)`
  에 전달.
- `DsAuth.resolveBase()` 의 기존 LAN probe 로직을 살려 LAN probe 성공 시 LAN 사용.
- `activity_nas_settings.xml` 에 "LAN 주소 (선택)" / "LAN 포트" 필드.
- `topUpLanFromConfigIfEmpty()` — 기존 사용자 prefs 가 있어도 LAN 필드만 보충
  (업그레이드 경로).
- `BookListActivity.onCreate` 에서 `new NasSyncManager(this)` 를 한 번 호출해
  seeding / top-up 이 앱 시작 시 실행되도록 (이전에는 Fragment 가 늦게 생성돼서
  prefs 업데이트가 지연됐음).

### 커밋 전 확인

```bash
adb shell run-as com.example.minseo3 cat \
  /data/data/com.example.minseo3/shared_prefs/nas_prefs.xml

# → <string name="nas_lan_host">192.168.45.65</string>
#   <int name="nas_lan_port" value="5000" />
```

그러나 재시도해도 여전히 실패. 다음 이슈로.

## Issue #2 — Cleartext HTTP 차단

### Trace

```
23:23:11.545 I NAS: resolveBase: LAN probe 시작 → http://192.168.45.65:5000
23:23:11.552 D NAS: probeUrl http://192.168.45.65:5000 exception:
                      IOException: Cleartext HTTP traffic to 192.168.45.65 not permitted
23:23:11.552 I NAS: resolveBase: LAN probe 실패 (7ms) → DDNS/WAN 폴백
```

LAN probe 가 7ms 만에 예외 — 네트워크 이슈가 아니라 **Android 런타임 차단**.
`targetSdk=35` 기본 설정에서 cleartext HTTP 는 허용되지 않음 (Android 9 / API 28+
기본값 변경).

### Minseo21 비교

```bash
grep cleartextTraffic /d/workspace/Minseo21/app/src/main/AndroidManifest.xml
# → android:usesCleartextTraffic="true"
```

Minseo21 은 이미 반영돼 있었는데 Minseo3 AndroidManifest 에 누락됐던 항목.

### 수정

```xml
<application
    ...
    android:usesCleartextTraffic="true"
    android:theme="@style/Theme.Minseo3">
```

보안 관점에선 `network_security_config.xml` 로 특정 도메인만 허용하는 게 더
안전하지만, Android 의 domain-config 가 CIDR 를 지원하지 않아 `192.168.x.x`
전체를 한 줄로 쓸 수 없음. 개인 사용 앱이고 외부 HTTP 접근 요구가 없어서
Minseo21 과 동일한 blanket 방식을 택함.

### 결과

```
23:25:35.987 D NAS: probeUrl http://192.168.45.65:5000 → HTTP 200
23:25:35.987 D NAS: probeUrl ... body preview='{"data":{"SYNO.API.Auth":{"maxVersion":7' → OK
23:25:35.987 I NAS: resolveBase: LAN probe 성공 (177ms) → LAN 사용
23:25:38.282 I NAS: login OK, SID=E_QIq0eR…
```

LAN probe 177ms, 로그인 성공. 설정 저장 완료. 사용자 성공 보고.

## Issue #3 — fetchOne HTML 404 → connected 캐스케이드

### Trace

설정 저장까지는 성공했지만, 그 뒤 책 열고 페이지 넘기고 5초 대기 후 로그
확인해도 **Upload 요청이 전혀 안 나감**.

```
23:25:36.437 D NAS: HTTP 404 ← .../Download?path=.../pos_b749a053fab4a253.json
23:25:36.438 W NasSync: fetchOne failed (b749a053fab4a253):
                Value <!DOCTYPE of type java.lang.String cannot be converted to JSONObject
```

DSM 은 Download 엔드포인트에서 "파일 없음" 을 **HTTP 404 + HTML 본문**으로 응답.
우리 파서는 본문이 `{` 로 시작하지 않는데도 `new JSONObject(body)` 를 호출해
`JSONException` 발생 → `fetchOne.onError` 호출 → `NasSyncManager` 가 **`connected`
를 false 로 전환**. 그 후 모든 `push()` 호출이 `!connected` 로 조용히 스킵.

즉 **파싱 버그 하나가 connection health 를 거짓 false 로 만들어 전체 sync 를
죽임**.

### 수정

`SynologyFileStationRepository.fetchOne` / `downloadPos` 둘 다:

```java
String body = DsHttp.httpGet(url);
if (body.startsWith("<")) return null;   // HTML 404 → 없음으로 간주
if (body.startsWith("{") && body.contains("\"success\"")) {
    ...envelope 처리...
}
```

이제 "파일 없음" 이 JSON envelope 대신 HTML 로 와도 null 로 조용히 반환.

## Issue #4 — Upload `_sid` URL 파라미터 필수

### Trace

HTML 404 fix 후 재시도. Upload 요청은 나가는데 실패.

```
23:32:47.994 D NasSync: push scheduled hash=b749a053fab4a253 offset=446/5135
23:32:48.093 I NAS: SID 만료 감지 (code=119) → reLoginSync 재시도
23:32:48.364 I NAS: reLoginSync OK
23:32:48.392 W NasSync: push failed: upload failed (code=119)
```

SID 새로 받아도 **또 119**. 119 = "SID not found" — 서버가 SID 를 인식 못 함.

### Minseo21 비교

```java
// DsPlayback.java
/** uploadFile 에 전달할 엔드포인트 URL (SID 는 form field + URL param 양쪽). */
private static String uploadEndpoint(String sid) throws Exception {
    return DsAuth.apiBase() + "/webapi/entry.cgi?_sid=" + URLEncoder.encode(sid, "UTF-8");
}
```

주석이 모든 걸 말해줌. Minseo21 은 이미 알고 있었음 — **Upload 엔드포인트는
`_sid` 를 URL 쿼리로도 받아야 인식함**. 다른 엔드포인트 (List/Download) 는 form
field 하나로 충분하지만 Upload 는 그렇지 않음. DSM API 의 비일관성.

### 수정

`SynologyFileStationRepository.push`:

```java
String url = DsAuth.apiBase() + "/webapi/entry.cgi?_sid="
        + URLEncoder.encode(sid, "UTF-8");
DsHttp.uploadFile(url, resolvePosDir(), "pos_" + fileHash + ".json", body, sid);
```

## Issue #5 — FileStation 공유 폴더 루트 요구

### Trace

URL 파라미터 수정 후 또 시도. 이번엔 다른 에러.

```
23:37:13.137 D NasSync: push scheduled hash=b749a053fab4a253 offset=2054/5135
23:37:13.139 I NasSync: push upload url=http://192.168.45.65:5000/webapi/entry.cgi?_sid=...
              dest=/소설/.minseo file=pos_b749a053fab4a253.json bytes=153
23:37:23.435 I NasSync: push response: {"error":{"code":408},"success":false}
```

`code 408` = "No such file or directory". `create_parents=true` 를 보냈는데도 타깃
경로를 못 찾음.

### 원인

Synology FileStation Upload 는 `path` 파라미터가 **DSM 의 공유 폴더(Shared Folder)
루트부터** 시작해야 함. `create_parents=true` 는 공유 폴더 **아래** 중간
디렉토리만 만들어 주고, 공유 폴더 자체는 만들지 않음. `/소설/` 은 DSM 에 등록된
공유 폴더 이름이 아니라 클라이언트가 임의로 고른 경로였음.

Download/List 가 404 / 성공으로 보였던 이유: 정상 응답에 공유 폴더 유효성 검사가
엄격하지 않고 "없음" 으로 떨어지는 것과 구분 불가능.

### 수정

`DsFileConfig.PATH` 를 Minseo21 이 이미 공유 폴더로 열어둔 경로로 변경:

```java
public static final String PATH = "/video/.minseo/";
```

파일명 패턴이 다름:

- Minseo21: `{user}_positions.json` (사용자 번들), `pos_{videoHash}.json` (비디오별)
- Minseo3: `pos_{fileHash}.json` — **fileHash = sha256(파일명 + 파일크기) 앞 16자**,
  텍스트 파일 기준이라 hash 공간이 다름 — 충돌 없음

기존 단말의 prefs 는 `/소설/.minseo/` 가 이미 저장돼 있으므로 sed 로 일회성 치환:

```bash
adb shell run-as com.example.minseo3 sh -c \
  'sed -i "s|/소설/.minseo/|/video/.minseo/|" /data/data/.../nas_prefs.xml'
```

새 앱 설치나 앱 데이터 초기화 시점부터는 `DsFileConfig.PATH` 기본값으로 시드됨.

### 결과 — 성공

```
23:45:40.458 D NasSync: push scheduled hash=b749a053fab4a253 offset=2605/5135
23:45:40.461 I NasSync: push upload url=http://192.168.45.65:5000/webapi/entry.cgi?_sid=...
              dest=/video/.minseo file=pos_b749a053fab4a253.json bytes=153
23:45:40.680 I NasSync: push response: {"data":{"blSkip":false,"file":"pos_b749a053fab4a253.json",
              "pid":8175,"progress":1},"success":true}
```

NAS 에 실제로 파일 생성. NAS 탭에서도 해당 기록이 표시됨 확인.

## 트레이스 로그 — 유지 / 제거 정책

디버그 중 5개 층을 풀려고 각 지점에 `Log.d` / `Log.i` 를 많이 심었다. 모두
**문제 해결 후 제거**해서 production 로그를 깔끔하게 유지.

### 제거한 것
- `DsHttp.probeUrl` — HTTP 코드 / body 프리뷰 / 예외 타입
- `DsAuth.resolveBase` — probe 시작/성공/실패 + 소요시간
- `DsAuth.init` — lanUrl / user / posDir 상세 (baseUrl + lanUrl 만 남김)
- `NasSyncManager.push` — "push scheduled / skipped" 진단 로그
- `SynologyFileStationRepository.push` — 업로드 URL / 응답 본문 전체

### 유지한 것
- 원래 있던 에러 로그 (`fetchOne failed`, `push failed`, SID 만료 재시도 등)
- `DsAuth.init` 의 baseUrl + lanUrl 한 줄 (최소 환경 정보)
- `DsAuth` 의 `LAN 연결:` / `외부 연결:` 요약 — 어느 경로로 붙었는지 1 줄
- `DsAuth.reLoginSync OK` — 세션 만료 감지 시 재로그인 동작 기록
- `login OK, SID=…` (앞 8자만) — 로그인 성공 기록

## 배운 점

1. **"코드 버그 아님" ≠ "끝"**. Network 에러는 OS 정책 / 라우터 동작 / DSM API
   비일관성 등 바깥 레이어에서 막힐 일이 많다. 첫 실패 로그를 보고 "서버 문제
   같다" 로 접지 말고 진단 로그를 먼저 추가해서 **경로의 모든 단계**를 찍으면
   원인 공간을 빠르게 좁힐 수 있다.
2. **겹친 실패는 수정 순서가 중요**. 각 단계마다 증상이 달라지는데, 명시적
   진단 로그가 "다음 단계 신호" 를 줘야 혼동 없이 이어진다. 만약 단순 try/catch
   로 "그냥 실패" 만 잡고 있었으면 #1 고쳐도 "실패 메시지가 똑같네?" 로
   멈췄을 것이다.
3. **파싱 실패 ≠ 연결 실패**. Issue #3 에서 JSON 파싱 에러 하나가
   `connected=false` 를 유발해서 후속 모든 push 가 조용히 죽었음. **에러
   분류** 를 엄격히 — 네트워크 레벨과 프로토콜 레벨을 구분해서 상태 flag 에
   반영해야 함.
4. **Minseo21 docs / 코드 주석이 금광**. Issue #4 의 `uploadEndpoint` 주석은
   "SID 는 form field + URL param 양쪽" 한 줄로 며칠 삽질을 절약시킬 수
   있었음. Minseo21 을 포팅할 때는 **코드뿐 아니라 주석도 맥락이므로 그 의미를
   따라가야** 한다.
5. **공유 폴더 루트 = 벤더 제약**. Issue #5 는 Synology 특화 제약이라 추상화된
   `RemoteProgressRepository` 인터페이스에서는 보이지 않음. 다른 백엔드
   (WebDAV, Google Drive 등) 를 나중에 붙일 때도 각각의 루트 제약 / 권한 모델을
   확인해야 함.
6. **hairpin NAT 은 한국 가정용 공유기에서 흔하다**. LAN URL 자동 probe 를
   기본값으로 둔 Minseo21 설계가 이 상황을 이미 흡수하고 있었다는 걸 뒤늦게
   인지. Minseo3 도 같은 설계로 정리함 → `DsFileConfig.LAN_HOST` / `LAN_PORT`
   와 UI 필드 추가.

## 관련 파일

- `app/src/main/AndroidManifest.xml` — `usesCleartextTraffic`
- `app/src/main/java/com/example/minseo3/nas/DsAuth.java` — `resolveBase`, `init`, LAN URL wiring
- `app/src/main/java/com/example/minseo3/nas/DsHttp.java` — `probeUrl` (unchanged, but now effective with cleartext flag)
- `app/src/main/java/com/example/minseo3/nas/SynologyFileStationRepository.java` — `push` URL `?_sid=`, `fetchOne` HTML 404 handling
- `app/src/main/java/com/example/minseo3/NasSyncManager.java` — Prefs LAN 필드, `topUpLanFromConfigIfEmpty`, constructor 에서 seeding / top-up
- `app/src/main/java/com/example/minseo3/NasSettingsActivity.java` — LAN 필드 바인딩, 테스트 결과 표시
- `app/src/main/res/layout/activity_nas_settings.xml` — LAN host/port EditText + "연결 테스트 후 저장" 버튼
- `app/src/main/java/com/example/minseo3/nas/DsFileConfig.java` — `LAN_HOST/LAN_PORT/PATH` (gitignored)
- `app/src/main/java/com/example/minseo3/nas/DsFileConfig.java.sample` — 템플릿
- `app/src/main/java/com/example/minseo3/BookListActivity.java` — `new NasSyncManager(this)` 로 앱 시작 시 seeding 보장
- `app/src/test/java/com/example/minseo3/NasSyncManagerTest.java` — InMemoryPrefs 에 LAN 필드 추가

## 참고

- Minseo21 선례: `D:\workspace\Minseo21\docs\refactor-dsfile-split.md`
  (ISSUE-001 / ISSUE-003)
- Android cleartext 정책:
  https://developer.android.com/privacy-and-security/risks/cleartext
- DSM FileStation API 레퍼런스: Synology 공식 개발자 문서 (auth.cgi, entry.cgi,
  FileStation.Upload / Download / List)
- DSM 에러 코드: 100 (Unknown), 101 (Invalid parameter), 105 (No permission),
  106 (Session timeout), 107 (Duplicate login), 119 (SID not found),
  408 (No such file or directory), 412 (Not enough permission)
