# ClassroomAgent 배포 매뉴얼

학생 태블릿에 ClassroomAgent 를 배포하는 두 가지 경로를 다룬다.

- **개별 세팅** — 교사·학생이 각 태블릿에서 직접 진행. 첫 배포·개별 재설치 시.
- **벌크 프로비저닝** — 학교 IT 담당자가 여러 태블릿을 한 번에 설정. 학기 초 대량 배부 시.

앱 자체는 권한을 자동으로 부여할 수 없다 (안드로이드 정책). 사람이 직접 켜는 과정이 반드시 있으며, 이 문서가 그 절차를 다룬다.

---

## 개별 세팅

각 태블릿에서 아래 순서로 진행한다. 예상 시간 약 5분.

### 0. 사전 조건

- 태블릿이 학교 Wi-Fi 에 연결돼 있어야 함
- 안드로이드 버전 8 (API 26) 이상
- 삼성 갤럭시 탭 계열 기준 문구로 안내함 — 다른 OEM 은 유사

### 1. Play Protect 스캔 잠시 끄기

Play Protect 가 사이드로드 APK 를 정책적으로 차단한다. 설치 진행을 위해 잠시 끄고, 설치 완료 후 다시 켠다 (기존 앱은 삭제되지 않음).

1. **Play 스토어** 앱 열기
2. 우상단 프로필 아이콘 탭
3. **Play 프로텍트** 선택
4. 우상단 톱니바퀴(설정) 아이콘 탭
5. **"Play 프로텍트로 앱 스캔"** 토글을 **끄기**
6. 확인 다이얼로그에서 **"끄기"** 선택

Google 계정이 없는 태블릿(공기계)은 Play 스토어 로그인이 필요할 수 있다. 세팅용 관리자 구글 계정을 미리 준비해두면 편하다. 로그인 후 위 설정을 마치면 로그아웃해도 설정 상태는 유지된다.

### 2. APK 다운로드

1. 태블릿 브라우저에서 배포 QR 코드 스캔 또는 포털 링크 직접 접속
2. `ClassRoom_Agent-x.x.x-xxxxxxx.apk` 다운로드

### 3. APK 설치

1. 다운로드된 APK 를 파일 관리자에서 탭
2. "출처를 알 수 없는 앱 설치" 권한을 브라우저·파일 관리자에 부여 (안내 나오면)
3. 설치 진행

### 4. 4가지 권한 부여

앱을 처음 실행하면 학년/반/번호/이름 입력 다이얼로그가 뜬다. 입력 후 아래 4개 권한을 순서대로 켠다.

**각 권한은 앱 밖 시스템 설정에서 켜야 함.** 앱은 안내만 하고 실제 토글은 사용자가 한다.

#### 4-1. 접근성 서비스 (필수)

수업 중 학생이 여는 앱을 감지하는 데 사용한다. 이 권한이 없으면 앱이 아무것도 못 한다.

경로 (삼성 One UI):
```
설정 → 접근성 → 설치된 앱 → ClassroomAgent → 사용
```
확인 다이얼로그 뜨면 "허용".

OEM 별 진입 경로:
- **삼성**: 설정 → 접근성 → 설치된 앱 → ClassroomAgent
- **Pixel/AOSP**: 설정 → 접근성 → 다운로드한 앱 → ClassroomAgent
- **샤오미/미유아이**: 설정 → 추가 설정 → 접근성 → ClassroomAgent
- **LG**: 설정 → 일반 → 접근성 → ClassroomAgent

안드로이드 13 이상에서 "**제한된 설정**" 오류가 나면:
1. 설정 → 앱 → ClassroomAgent → 우상단 ⋮ → **"제한된 설정 허용"**
2. 다시 접근성 화면으로 돌아가 토글

#### 4-2. 다른 앱 위에 표시 (필수)

수업 안내 배너 표시용.

경로:
```
설정 → 앱 → ClassroomAgent → 다른 앱 위에 표시 → 허용
```

#### 4-3. 배터리 최적화 예외 (권장)

수업 중 앱이 백그라운드에서 종료되지 않도록.

경로:
```
설정 → 배터리 및 절전 → 배터리 최적화 → ClassroomAgent → 최적화 안 함
```

#### 4-4. VPN 승인 (필수)

수업 중 금지 사이트 로컬 차단용. 외부로 데이터를 전송하지 않는다.

앱을 다시 열면 VPN 승인 다이얼로그가 자동으로 뜬다.
- "**이 앱이 VPN 연결을 만들려고 합니다**"
- **"확인"** 탭

한 번 승인하면 이후 자동 활성화.

### 5. Play Protect 다시 켜기

세팅이 끝나면 Play 스토어 → 프로필 → Play 프로텍트 → 설정 → "Play 프로텍트로 앱 스캔" **다시 켜기**.

이후 Play Protect 가 ClassroomAgent 를 "위험한 앱" 이라고 알림을 띄울 수 있다. 이미 설치된 앱은 자동 삭제하지 않으니 알림을 무시하면 된다. 사용자가 실수로 "제거" 를 누르지 않도록 학생에게 미리 안내.

### 6. 확인

앱 메인 화면에 학년/반/번호/이름 이 표시되고 상단 알림 영역에 "수업 관리 서비스 실행 중" 이 뜨면 정상.

---

## 벌크 프로비저닝

학교 IT 담당자가 여러 태블릿을 USB / 무선 ADB 로 한 번에 세팅한다. 태블릿 하나당 30초 정도.

### 사전 조건

- 관리자용 노트북·PC 에 `adb` 설치돼 있어야 함
- 태블릿마다 **개발자 옵션 → USB 디버깅** (또는 무선 디버깅) 활성화

### 프로비저닝 스크립트

`scripts/provision.sh` (또는 아래 인라인) 을 태블릿 하나당 한 번 실행:

```bash
#!/usr/bin/env bash
set -e
APK="ClassRoom_Agent-x.x.x-xxxxxxx.apk"
PKG="com.teaveloper.classroomagent"
SVC="$PKG/.ClassWatcherService"

# Play Protect 스캐너 무력화
adb shell settings put global package_verifier_enable 0
adb shell settings put global package_verifier_state 0
adb shell settings put global upload_apk_enable 0
adb shell settings put global verifier_verify_adb_installs 0

# 설치 (Play Protect UI 우회)
adb install -r "$APK"

# 오버레이 권한
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

# 배터리 최적화 제외
adb shell dumpsys deviceidle whitelist "+$PKG"

# 접근성 서비스 활성화
CURRENT=$(adb shell settings get secure enabled_accessibility_services)
if [[ "$CURRENT" != *"$SVC"* ]]; then
  NEW="$SVC"
  [[ "$CURRENT" != "null" && -n "$CURRENT" ]] && NEW="$CURRENT:$SVC"
  adb shell settings put secure enabled_accessibility_services "$NEW"
  adb shell settings put secure accessibility_enabled 1
fi

# VPN 사전 승인 (일부 One UI 에서만 통함)
adb shell appops set "$PKG" ACTIVATE_VPN allow 2>/dev/null || true

# 앱 실행
adb shell am start -n "$PKG/.MainActivity"

echo "== 완료 =="
```

**여전히 사람 손이 필요한 부분**:

- **VPN 확인 다이얼로그** — `appops set ACTIVATE_VPN allow` 가 안 먹히는 OEM 에서는 첫 실행 시 시스템이 다이얼로그를 강제로 띄운다. "확인" 한 번 눌러야 함. 안드로이드 정책상 우회 불가.
- **Android 13+ 제한된 설정** — 접근성 켜기 전에 아래로 `installer_package` 를 지정:
```bash
adb shell pm set-installer-package "$PKG" com.android.vending
```

### 여러 대 병렬 처리

```bash
for DEVICE in $(adb devices | tail -n +2 | awk '{print $1}' | grep -v '^$'); do
  ANDROID_SERIAL=$DEVICE ./provision.sh &
done
wait
```

수십 대 태블릿도 몇 분이면 끝.

### 학번 자동 입력

기기별 학년/반/번호/이름을 CSV 로 관리하고 `adb shell content insert` 로 직접 SharedPreferences 를 채우면 첫 실행 시 다이얼로그를 스킵할 수 있다. 필요하면 IT 담당자에게 개별 요청.

---

## 트러블슈팅

**"설치가 안 됩니다" 경고 (Play Protect 차단)**
- Play Protect 스캔이 켜져 있음. 위 1번 순서대로 잠시 끄기.

**"파싱 오류"**
- APK 다운로드가 손상됐거나 안드로이드 8 미만 태블릿. 다시 다운로드.

**"앱이 설치되지 않음" / "패키지 충돌"**
- 예전 debug 빌드가 남아있음. `adb uninstall com.teaveloper.classroomagent` 또는 앱 삭제 후 재설치.

**접근성 켜도 앱이 반응 없음**
- 안드로이드 13+ 제한된 설정 오류일 수 있음. 위 4-1 하단 참고.

**Play Protect 가 계속 "위험한 앱" 알림을 띄움**
- 알림 무시 (이미 설치된 앱은 자동 삭제 안 함).
- 장기적으로는 Google Play Protect Appeal 로 서명 인증서 화이트리스트 등재하면 알림 안 뜸.
