# 저장 구조 (NAS / 로컬) 정리

이 앱이 데이터를 어디에 어떤 이름/스키마로 저장하는지 한눈에 볼 수 있게 정리.
디버깅·재설치·수작업 편집 시 참조.

## fileHash — 책 식별자

**`FileUtils.computeHash(file)`** (`FileUtils.java:36`)

```
hash_input  = fileName + fileSize          ("애드립의신_5.txt" + "12345678")
digest      = SHA-256(UTF-8 bytes of hash_input)
fileHash    = digest.hex().substring(0, 16)   (앞 16자)
```

- 내용 해시가 아니라 **이름 + 크기** 기반. 본문을 편집하면 해시는 유지되지만 charOffset 은 어긋남.
- 파일명 또는 크기가 바뀌면 완전히 다른 책으로 인식.
- 두 기기에서 같은 파일을 공유하려면 **파일명 · 크기가 동일해야** 같은 해시가 나옴.
- 16자 hex = 64비트 공간. 현실적 사용량에서 충돌 거의 없음.

---

## NAS 저장

### 기준 디렉토리

- 사용자 prefs `nas_prefs` 의 `nas_path` 값 — 일반적으로 `/web/.minseo/` 또는 `/video/.minseo/`
- 앞이 Synology FileStation 의 **공유 폴더 루트부터** 시작해야 함 (예: `/video/` 는 "video" 공유 폴더)
- 코드 접근: `DsAuth.cfgPosDir` (NAS 설정 저장 시 갱신)
- 디렉토리가 없으면 FileStation 이 code 408 반환 → push/fetch 모두 실패

### 파일 타입 1 — 위치 (pos) `pos_{fileHash}.json`

**역할**: "어느 기기에서든 이 책을 마지막에 어디까지 읽었다" 단일 기록. 책당 한 파일.

**Push 주체**: `SynologyFileStationRepository.push()` — 덮어쓰기. 가장 최근에 push 한 기기의 값이 남음 (이전 값은 사라짐).

**파일 내용**:
```json
{
  "fileName": "애드립의신_5.txt",
  "fileSize": 2048576,
  "charOffset": 412000,
  "totalChars": 515000,
  "deviceId": "f142f8ff7e17e767",
  "lastUpdatedEpoch": 1745449200000
}
```

**필드** (`RemotePosition.java`):
- `fileName` : `.txt` 포함 원본 파일명
- `fileSize` : 원본 파일 바이트 수
- `charOffset` : 이 기기에서 읽던 현재 페이지 시작 character offset
- `totalChars` : 전체 글자 수 (진행률 % 계산용)
- `deviceId` : push 한 기기 ID (Settings.Secure.ANDROID_ID 앞 16자 / 에뮬레이터는 UUID)
- `lastUpdatedEpoch` : push 시각 ms

**UI 상**: "다른 단말 진행" 탭은 `/web/.minseo/` 의 모든 `pos_*.json` 을 list → 내 deviceId 와 다른 것만 표시. 같은 책을 여러 기기가 읽어도 저장은 "마지막 push 한 기기의 값" 하나뿐 — 기기별 히스토리는 유지되지 않음.

### 파일 타입 2 — 북마크 (bm) `bm_{fileHash}.json`

**역할**: 책 하나의 북마크 전체 (살아있는 것 + tombstone). 책당 한 파일, 안에 배열.

**Push 주체**: `SynologyBookmarksRepository.push()` — 호출 시점의 **로컬 전체 리스트** 를 그대로 덮어씀. NasSyncManager 의 1 초 debounce 후 실행.

**Pull → Merge → Save**: 리더 진입 시 `BookmarksConflictResolver.merge(local, remote)` → 같은 id 끼리 `max(createdAt, deletedAt)` 더 큰 쪽이 이김 → 로컬 replaceAll.

**파일 내용**:
```json
{
  "version": 1,
  "fileHash": "a1b2c3d4e5f6g7h8",
  "bookmarks": [
    {
      "id": "0123456789abcdef",
      "deviceId": "f142f8ff7e17e767",
      "charOffset": 12345,
      "preview": "그는 창밖을 보며 오래 생각했다. 비가 그치…",
      "createdAt": 1745449000000,
      "deletedAt": null
    },
    {
      "id": "fedcba9876543210",
      "deviceId": "65a502e9dc97b49d",
      "charOffset": 88000,
      "preview": "마지막 대목에서 그녀는 결국…",
      "createdAt": 1745449100000,
      "deletedAt": 1745449800000
    }
  ]
}
```

**필드** (`Bookmark.java`):
- `id` : 결정적 (`sha1(fileHash + ':' + charOffset)` 앞 16 hex). 같은 페이지에 두 기기가 만들면 같은 id → union 에서 자동 병합
- `deviceId` : 생성/수정 기기
- `charOffset` : 북마크 지점 (페이지 시작 offset)
- `preview` : 본문 40자 미리보기 (`\n`/`\t` → 공백)
- `createdAt` : 생성 시각 ms
- `deletedAt` : null = alive, 값 있음 = tombstone. UI 는 alive 만 표시. 삭제 전파에 필수 (zombie 방지)

### NAS 기준 디렉토리 구조

```
/{공유폴더}/.minseo/
  ├── pos_a1b2c3d4e5f6g7h8.json      (책 1 — 애드립의신_5)
  ├── pos_f0e1d2c3b4a59876.json      (책 2)
  ├── ...
  ├── bm_a1b2c3d4e5f6g7h8.json       (책 1 북마크)
  ├── bm_f0e1d2c3b4a59876.json
  └── ...
```

Lists API 로 이 디렉토리를 ls → client-side 에서 prefix (`pos_` / `bm_`) 로 필터.

---

## 로컬 저장 (기기 내부)

### `context.getFilesDir()` = 앱 프라이빗 데이터 디렉토리

Uninstall 시 삭제. 다른 앱 접근 불가.

Android 기준 실제 경로 (Fold): `/data/data/com.example.minseo3/files/`

#### 파일 1 — 읽기 진행 `reading_progress.json`

**역할**: 모든 책의 "어디까지 읽었다" 기록. 책당 1 entry.

**Owner**: `LocalProgressRepository.java`

**Save 시점**:
- 리더에서 페이지 이동 시 5 초 debounce (`scheduleSave` → `saveRunnable`)
- `flushSaveNow()` — `ReaderFragment.onPause` 에서 즉시 저장

**파일 내용** (JSON 배열, LinkedHashMap 순서 — 최근에 쓴 게 뒤):
```json
[
  {
    "fileHash": "a1b2c3d4e5f6g7h8",
    "filePath": "/storage/emulated/0/소설/동화/애드립의신_5.txt",
    "charOffset": 412000,
    "totalChars": 515000,
    "lastRead": 1745449200000
  },
  ...
]
```

**필드** (`LocalProgressRepository.Entry`):
- 전부 `RemotePosition` 과 같은 필드 + `filePath` (로컬 절대 경로, 이 기기 전용)
- NAS 의 pos_*.json 과 1:1 대응되지만 locally 는 flat 리스트

**읽기**: `get(fileHash)` → 해당 entry 또는 null.

#### 파일 2 — 북마크 `bookmarks.json`

**역할**: 모든 책의 북마크. 책당 배열, 모아서 한 파일.

**Owner**: `BookmarksRepository.java`

**Save 시점**: 모든 mutation 즉시 persist (`invalidateAndPersist` → `persist`)

**파일 내용**:
```json
{
  "version": 1,
  "byFileHash": {
    "a1b2c3d4e5f6g7h8": [
      { "id": "...", "deviceId": "...", "charOffset": 12345,
        "preview": "...", "createdAt": 1745..., "deletedAt": null },
      ...
    ],
    "f0e1d2c3b4a59876": [ ... ]
  }
}
```

**구조**: `Bookmark` 스키마는 NAS 와 동일. 차이는 grouping — 로컬은 byFileHash 맵 (모든 책 한 파일) / NAS 는 책당 파일.

**변형 시점**:
- `toggleAtPage` (⭐ 탭 시)
- `deleteById` (리스트 삭제)
- `undeleteById` (undo)
- `replaceAll` (NAS pull merge 결과 반영)

각 경로에서 `invalidateAndPersist` 호출 → 디스크 flush + listener 브로드캐스트.

### `context.getCacheDir()` = 앱 프라이빗 캐시

Uninstall + "앱 데이터 삭제 (캐시)" 로 비워짐. 앱 동작에 critical 하지 않음.

#### 파일 3 — 페이지네이션 캐시 `pagination/{hash}_{sizeSp}_{w}x{h}.bin`

**역할**: 책 한 번 paginate 하면 (charOffset 배열) 다음 오픈 시 재계산 없이 로드 — StaticLayout 빌드 수초 아낌.

**Owner**: `PaginationCache.java`

**키**: `(fileHash, textSizeSp, widthPx, heightPx)`
- 예: `a1b2c3d4e5f6g7h8_17_1080x1920.bin`
- 폰트 크기 바꾸면 key 달라져 cache miss → 재pagination
- 회전 (가로 ↔ 세로) → w/h 바뀌어 cache miss → 재pagination

**파일 포맷** (custom binary):
```
[int CACHE_VERSION]
[int offsetCount]
[int offset_0]
[int offset_1]
...
```

**eviction**: MAX_ENTRIES=200. 넘으면 lastModified 오래된 것부터 삭제.

**invalidation**: `CACHE_VERSION` 상수를 bump 하면 모든 구 캐시 파일 무효화 (폰트/레이아웃 상수 변경 시).

### `SharedPreferences` (앱 프라이빗 prefs, xml)

#### `nas_prefs.xml` — NAS 설정

```
nas_host          : DDNS / 외부 호스트 (예: gomji17.synology.me)
nas_port          : HTTPS port (일반적으로 5001)
nas_user          : DSM 계정
nas_pass          : DSM 비밀번호 (평문 저장)
nas_path          : NAS 저장 경로 (예: /web/.minseo/)
nas_lan_host      : 같은 공유기 내부 IP (없으면 probe 생략)
nas_lan_port      : LAN HTTP port (일반적으로 5000)
nas_enabled       : 동기화 활성 boolean
nas_device_id     : 이 기기 ID (ANDROID_ID 앞 16자 또는 UUID 폴백)
```

**seed**: `NasSyncManager.seedDefaultsIfFirstLaunch` 가 첫 실행 시 `DsFileConfig` 리플렉션으로 채움. `nas_host` 가 이미 있으면 skip (기존 사용자 설정 유지).

#### `reader_prefs.xml` — 리더 테마/설정

```
text_size_sp   : float, 글자 크기 (14, 16, 17, 18, 20, 22, 24, 28)
text_color     : int ARGB, 글자 색
bg_color       : int ARGB, 배경 색
tap_swap       : boolean, 좌우 탭 기능 스위칭
```

**공유**: `ThemePrefs.bgColor/textColor` 가 이 prefs 를 앱 전체 읽음 — 리더에서 바꾸면 내 책·즐겨찾기 탭에도 적용.

---

## 요약 표

| 저장소 | 경로 | 무엇을 | 한 파일당 | 삭제 타이밍 |
|---|---|---|---|---|
| NAS | `{공유폴더}/.minseo/pos_{hash}.json` | 책의 "최신 push 위치" 1개 | 책당 | 수동 또는 앱 내 삭제 UI |
| NAS | `{공유폴더}/.minseo/bm_{hash}.json` | 책의 모든 북마크 (alive+tombstone) | 책당 | 수동 (V1 은 tombstone 만) |
| Local files | `files/reading_progress.json` | 모든 책의 진행 | 앱 전체 (배열) | uninstall 시 |
| Local files | `files/bookmarks.json` | 모든 책의 모든 북마크 (byFileHash) | 앱 전체 | uninstall 시 |
| Local cache | `cache/pagination/{hash}_{sp}_{w}x{h}.bin` | 페이지네이션 offset 배열 | 책×폰트×화면 조합당 | 앱 캐시 삭제 또는 evict (200개 한도) |
| SharedPrefs | `shared_prefs/nas_prefs.xml` | NAS 자격증명 + 설정 | 파일 1개 | uninstall 시 |
| SharedPrefs | `shared_prefs/reader_prefs.xml` | 테마 + 폰트 + 탭 스왑 | 파일 1개 | uninstall 시 |

## 디버깅 팁

### adb 로 NAS prefs 확인

```bash
adb -s <serial> shell "run-as com.example.minseo3 cat /data/data/com.example.minseo3/shared_prefs/nas_prefs.xml"
```

### adb 로 로컬 진행 / 북마크 확인

```bash
adb -s <serial> shell "run-as com.example.minseo3 cat /data/data/com.example.minseo3/files/reading_progress.json"
adb -s <serial> shell "run-as com.example.minseo3 cat /data/data/com.example.minseo3/files/bookmarks.json"
```

### NAS 직접 확인 (DSM File Station 웹 UI 또는 SSH)

```
/{공유폴더}/.minseo/pos_{hash}.json
```

열어서 JSON 파싱하면 어떤 기기가 몇 %까지 읽었는지 바로 보임.

### 책 하나 완전 리셋 (테스트용)

- 로컬: `reading_progress.json` 에서 해당 fileHash entry 제거 + `bookmarks.json` 에서 byFileHash 키 제거
- NAS: File Station 에서 `pos_{hash}.json` + `bm_{hash}.json` 삭제
- 재설치보다 표적 테스트에 유리
