# 리더 콜드 오픈 첫 페이지 빠르게 — partial→full 두 단계 로드

## 문제

느린 단말 (예: 갤럭시 XR / 구형 탭) 에서 3MB 정도의 .txt 책을 처음 열 때, 첫 페이지가
표시되기까지 10초 이상 걸림. 사용자가 책을 탭한 뒤 한참 동안 로딩 스피너만 보이는
체감.

## 원인

`ReaderFragment.loadFile` 의 콜드 경로:

1. `FileUtils.readAllBytes` — 3MB byte[] 로드 (~100~300ms)
2. `FileUtils.decodeAuto` — **두 번 디코드**:
   - `isValidUtf8(bytes)` 가 `CharsetDecoder.decode()` 로 전체 3MB 를 한 번 훑어
     CharBuffer 생성 후 결과 버리고 `boolean` 만 리턴
   - 직후 `new String(bytes, UTF_8)` 가 같은 작업을 한 번 더 수행
   - 한국 UTF-8 텍스트 3MB → ~1~1.5M 문자 → 임시 4~6MB allocation × 2 + GC 압박
   - 느린 단말 기준 단독으로 3~6초
3. (이후 partial 윈도우 첫 페이지 + 백그라운드 풀 paginate 진행 — 여기는 빠름)

즉 **전체 파일을 두 번 디코드하느라** 첫 페이지 표시까지 모든 시간이 소비됨.

## 해결

### 1. 단일 패스 디코드 (`FileUtils.decodeAuto`)

`isValidUtf8` 분리 호출을 제거하고 `CharsetDecoder.decode().toString()` 한 번으로
끝. 실패 시 기존 CP949 폴백. 디코드 시간 절반.

### 2. Prefix 부분 읽기 + 두 단계 paginate (`ReaderFragment.loadFile`)

콜드 경로를 두 단계로 분리:

**Phase 1 — prefix:**
- `FileUtils.readBytePrefix(file, len)` 로 byte 0 ~ `min(fileLen, max(128KB, startByte_estimate + 64KB))` 만 읽기
- `startByte_estimate = startOffset × 4` (UTF-8 한글 보수적 추정)
- 추정이 어긋나 partialText.length() < startOffset 이면 256KB 씩 늘려 재시도
- `FileUtils.decodeAutoPartial` — 끝의 미완성 UTF-8 multi-byte 시퀀스 trim 후 디코드
- partial 텍스트로 paginate → 첫 페이지 즉시 표시 → `paginationReady = true`
- 사용자는 partial 안에서 페이지 이동 가능

**Phase 2 — full (백그라운드):**
- `readAllBytes` + `decodeAuto`
- 캐시 체크 → 히트면 cached offsets, 미스면 별도 `PageRenderer` 인스턴스로 풀 paginate
- 메인 스레드에서 atomic swap: `pageRenderer.setOffsets(fullOffsets)` + `text = fullText` +
  `currentPage` 를 char offset 으로 재매핑

### 3. char offset 시스템과의 호환성

이 앱의 진행률/북마크/NAS 동기화는 모두 char offset 단위. byte offset 시스템 도입은
비현실적이라, prefix 도 byte 0 부터 시작 (앞부분은 항상 디코드되어 char offset 매핑이
정확함). "앞 64KB" 의 의미는 "현재 위치 앞으로 페이지 이동 가능한 정도" 가 자동으로
보장되는 것.

### 4. partial 모드 race / 설정 변경

- `paginateGeneration` + `paginateCancelled` 패턴 그대로 사용 — 진행 중 prefix 또는
  full read 도 cancel 시 즉시 탈출
- partial 동안 사용자가 설정 (글자 크기, 굵게) 변경 → `partialMode` 플래그가 켜져
  있으면 in-memory `paginate` 가 아니라 `loadFile` 을 다시 호출해 partial→full 흐름
  전체 재시작 (partial 텍스트로만 paginate 하면 풀 paginate 가 영영 안 일어남)

## 변경 파일

- `app/src/main/java/com/example/minseo3/FileUtils.java`
  - `decodeAuto` 단일 패스화, `isValidUtf8` 제거
  - `readBytePrefix(File, long)` 추가 (RandomAccessFile 사용)
  - `decodeAutoPartial(byte[])` 추가 — trim + decodeAuto
  - `trimIncompleteUtf8Tail(byte[], int)` 헬퍼

- `app/src/main/java/com/example/minseo3/ReaderFragment.java`
  - `partialMode` 필드 추가
  - `loadFile` → `startLoadFlow` 두 단계 흐름으로 교체
  - `showSettings.onChanged` — partial 모드에서 loadFile 재호출 분기

## 캐시 호환성

`PaginationCache.CACHE_VERSION` 그대로 (2). partial paginate 의 boundaries 는 풀
paginate boundaries 의 prefix 와 동일 알고리즘이므로 캐시 형태 변경 없음.

## 기대 효과

- 콜드 오픈: 3MB 파일 첫 페이지 표시까지 **10초+ → ~수백 ms** (prefix 128KB 디코드 +
  partial paginate)
- 캐시 히트 콜드 오픈: 마찬가지로 prefix 만 디코드 후 cached offsets 으로 즉시 표시
- 풀 paginate 는 백그라운드에서 진행, 끝나면 페이지 수 / seekbar 업데이트

## 위험 / 검증 필요

- byte 추정 계수 4 — 영문 ASCII 위주 파일이면 과대추정 (안전, 더 읽을 뿐)
- prefix 끝부분 trim 으로 CP949 파일 끝 1~2 byte 손실 가능 — 첫 페이지 표시엔 영향
  없음. 풀 디코드는 끝부분 잘림 없으므로 정상
- partial 동안 `seekBar.setMax` 가 partial 페이지 수 기준 → 풀 끝나면 다시 setMax
- 실 단말 측정 (느린 XR / 폴드 등) 으로 체감 시간 확인 필요
