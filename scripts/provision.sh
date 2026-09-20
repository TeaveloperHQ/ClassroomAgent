#!/usr/bin/env bash
#
# ClassroomAgent 벌크 프로비저닝 — 태블릿 하나당 한 번 실행.
# 학교 IT 담당자가 USB 또는 무선 ADB 연결된 태블릿에 대해 사용.
#
# 사용법:
#   ./provision.sh path/to/ClassRoom_Agent-x.x.x-xxxxxxx.apk
#   # 여러 기기 병렬:
#   for D in $(adb devices | tail -n +2 | awk '{print $1}' | grep -v '^$'); do
#     ANDROID_SERIAL=$D ./provision.sh "$1" &
#   done; wait
#
# 사람 손이 여전히 필요한 부분:
#   - VPN 확인 다이얼로그: 첫 실행 시 시스템이 강제로 띄움. "확인" 한 번.
#     appops set ACTIVATE_VPN allow 가 안 먹히는 OEM 이 대부분.
#     이건 안드로이드 정책상 우회 불가.

set -euo pipefail

APK="${1:-}"
PKG="com.teaveloper.classroomagent"
SVC="$PKG/.ClassWatcherService"

if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "usage: $0 <path-to-apk>" >&2
  exit 1
fi

echo "== ${ANDROID_SERIAL:-default device} 프로비저닝 시작 =="

# Play Protect 스캐너 무력화 (설치 UI 우회를 위해)
adb shell settings put global package_verifier_enable 0
adb shell settings put global package_verifier_state 0
adb shell settings put global upload_apk_enable 0
adb shell settings put global verifier_verify_adb_installs 0

# 설치. -r 은 기존 설치 유지하며 업데이트.
adb install -r "$APK"

# Android 13+ 의 "제한된 설정" 을 우회하기 위해 installer 를 Play 스토어로 표시.
# 이렇게 하면 접근성 토글 시 restricted-settings 다이얼로그가 뜨지 않음.
adb shell pm set-installer-package "$PKG" com.android.vending 2>/dev/null || true

# 오버레이 권한
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

# 배터리 최적화 화이트리스트
adb shell dumpsys deviceidle whitelist "+$PKG"

# 접근성 서비스 활성화 (기존 목록에 추가)
CURRENT=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r\n')
NEW="$SVC"
if [[ "$CURRENT" != "null" && -n "$CURRENT" && "$CURRENT" != *"$SVC"* ]]; then
  NEW="$CURRENT:$SVC"
fi
adb shell settings put secure enabled_accessibility_services "$NEW"
adb shell settings put secure accessibility_enabled 1

# VPN 사전 승인 시도 — Samsung One UI 등 일부에서만 통함.
# 안 통하면 사용자 첫 실행 시 시스템 다이얼로그 뜸.
adb shell appops set "$PKG" ACTIVATE_VPN allow 2>/dev/null || true

# 앱 실행
adb shell am start -n "$PKG/.MainActivity"

echo "== 완료. VPN 확인 다이얼로그가 뜨면 학생이 '확인' 한 번 눌러야 합니다. =="
