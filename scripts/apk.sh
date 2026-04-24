#!/usr/bin/env bash
#
# Minseo3 APK 매니저 — 연결된 adb 디바이스에 APK 설치 / 앱 데이터 삭제.
# Git Bash (Windows) 또는 Linux / macOS 어디에서든 실행 가능.
#
# 사용법:
#   bash scripts/apk.sh
#
# 변수:
#   APK_FILE  — 설치할 APK 경로 (절대 또는 스크립트 기준 상대). 기본값은
#               ./app/build/outputs/apk/debug/app-debug.apk
#   PACKAGE   — 데이터 삭제 대상 패키지.
#
set -u

# ── 설정 ────────────────────────────────────────────────────────────────────
APK_FILE="${APK_FILE:-app/build/outputs/apk/debug/Minseo3.apk}"
PACKAGE="${PACKAGE:-com.example.minseo3}"

# 알려진 디바이스 시리얼 → 사람 이름. 목록에 없는 기기는 getprop 모델명으로 폴백.
declare -A DEVICE_NAMES=(
  [R54Y1003KXN]="탭"
  [R3CT70FY0ZP]="폴드"
  [R3CX705W62D]="플립"
  [T813128GB25301890106]="미니"
)

# 스크립트 위치에서 프로젝트 루트로 이동 — APK_FILE 이 상대 경로여도 작동.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# ── 디바이스 조회 ───────────────────────────────────────────────────────────

# 연결된 디바이스 시리얼만 stdout 으로 한 줄에 하나씩.
list_devices() {
  adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}'
}

# 시리얼을 "시리얼 (이름)" 형태로 표시. 이름이 없으면 시리얼만.
display_name() {
  local serial="$1"
  local name="${DEVICE_NAMES[$serial]:-}"
  if [ -n "$name" ]; then
    echo "$serial ($name)"
    return
  fi
  # 매핑에 없으면 기기 모델명 조회 (2초 타임아웃 — 무반응 방지).
  local model
  model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')
  if [ -n "$model" ]; then
    echo "$serial ($model)"
  else
    echo "$serial"
  fi
}

# ── 메뉴 핸들러 ─────────────────────────────────────────────────────────────

action_list_devices() {
  echo ""
  echo "== Connected devices =="
  local devices
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -eq 0 ]; then
    echo "  (연결된 디바이스 없음)"
    return
  fi
  for s in "${devices[@]}"; do
    echo "  - $(display_name "$s")"
  done
}

install_on_device() {
  local serial="$1"
  if [ ! -f "$APK_FILE" ]; then
    echo "  ✗ APK 없음: $APK_FILE"
    echo "    ./gradlew assembleDebug 먼저 실행하세요."
    return 1
  fi
  echo "  → $(display_name "$serial") 에 설치 중..."
  adb -s "$serial" install -r "$APK_FILE"
}

clear_on_device() {
  local serial="$1"
  echo "  → $(display_name "$serial") 에서 $PACKAGE 데이터 삭제 중..."
  adb -s "$serial" shell pm clear "$PACKAGE"
}

uninstall_on_device() {
  local serial="$1"
  echo "  → $(display_name "$serial") 에서 $PACKAGE 제거 중..."
  adb -s "$serial" uninstall "$PACKAGE"
}

# 디바이스 선택 서브메뉴. callback 함수 이름을 받아 선택된 시리얼로 호출.
pick_device_and_run() {
  local title="$1"
  local callback="$2"
  local devices
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -eq 0 ]; then
    echo ""
    echo "  (연결된 디바이스 없음)"
    return
  fi
  echo ""
  echo "== $title =="
  local i=1
  for s in "${devices[@]}"; do
    echo "  $i) $(display_name "$s")"
    i=$((i+1))
  done
  echo "  0) 취소"
  echo ""
  read -rsn1 -p "선택> " choice
  echo "$choice"
  if [ -z "$choice" ] || [ "$choice" = "0" ]; then
    return
  fi
  if ! [[ "$choice" =~ ^[0-9]+$ ]]; then
    echo "  잘못된 입력."
    return
  fi
  local idx=$((choice-1))
  if [ "$idx" -lt 0 ] || [ "$idx" -ge "${#devices[@]}" ]; then
    echo "  범위 밖 번호."
    return
  fi
  echo ""
  "$callback" "${devices[$idx]}"
}

action_install_device()   { pick_device_and_run "install — 디바이스 선택" install_on_device; }
action_clear_device()     { pick_device_and_run "clear data — 디바이스 선택" clear_on_device; }
action_uninstall_device() { pick_device_and_run "uninstall — 디바이스 선택" uninstall_on_device; }

action_install_all() {
  local devices
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -eq 0 ]; then
    echo ""
    echo "  (연결된 디바이스 없음)"
    return
  fi
  echo ""
  echo "== install all ($((${#devices[@]})) devices) =="
  for s in "${devices[@]}"; do
    install_on_device "$s"
  done
}

action_build() {
  echo ""
  echo "== build — ./gradlew assembleDebug =="
  ./gradlew assembleDebug
}

action_clear_all() {
  local devices
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -eq 0 ]; then
    echo ""
    echo "  (연결된 디바이스 없음)"
    return
  fi
  echo ""
  echo "== clear all data ($((${#devices[@]})) devices) =="
  for s in "${devices[@]}"; do
    clear_on_device "$s"
  done
}

action_uninstall_all() {
  local devices
  mapfile -t devices < <(list_devices)
  if [ "${#devices[@]}" -eq 0 ]; then
    echo ""
    echo "  (연결된 디바이스 없음)"
    return
  fi
  echo ""
  echo "== uninstall all ($((${#devices[@]})) devices) =="
  for s in "${devices[@]}"; do
    uninstall_on_device "$s"
  done
}

# ── 메인 루프 ───────────────────────────────────────────────────────────────

main_menu() {
  while true; do
    echo ""
    echo "┌────────────────────────────────────────────────────┐"
    echo "│  Minseo3 APK Manager                               │"
    echo "│  APK:     $APK_FILE"
    echo "│  Package: $PACKAGE"
    echo "├────────────────────────────────────────────────────┤"
    echo "│  1) devices             — 연결된 디바이스 목록"
    echo "│  2) install device      — 디바이스 선택해 설치"
    echo "│  3) install all devices — 전체 설치"
    echo "│  4) clear data          — 디바이스 선택해 데이터 삭제"
    echo "│  5) clear all data      — 전체 데이터 삭제"
    echo "│  6) uninstall device    — 디바이스 선택해 앱 제거"
    echo "│  7) uninstall all       — 전체 앱 제거"
    echo "│  9) build               — ./gradlew assembleDebug"
    echo "│  0) exit"
    echo "└────────────────────────────────────────────────────┘"
    read -rsn1 -p "> " choice
    echo "$choice"
    case "$choice" in
      1) action_list_devices ;;
      2) action_install_device ;;
      3) action_install_all ;;
      4) action_clear_device ;;
      5) action_clear_all ;;
      6) action_uninstall_device ;;
      7) action_uninstall_all ;;
      9) action_build ;;
      0|q) echo "bye"; exit 0 ;;
      "") ;;  # 빈 입력(Enter) 은 조용히 넘김
      *) echo "  잘못된 선택: $choice" ;;
    esac
  done
}

main_menu
