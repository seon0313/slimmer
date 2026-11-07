# Slimmer

Android 폰의 **알림과 문자**를 Mac 메뉴바에서 보고, 답장하고, 보내는 앱입니다.
같은 Wi-Fi가 아니어도 (폰은 LTE/5G, Mac은 집 Wi-Fi) 동작합니다.

- **macOS 앱** (Swift/SwiftUI, 메뉴바 앱)
  - 폰 알림 목록, 메신저 알림에 바로 **답장** (카카오톡·메시지 등 인라인 답장을 지원하는 앱), 알림 동작 실행, 폰에서 지우기
  - **macOS 알림 센터로 미러링**하고, 배너에서 바로 답장
  - **문자(SMS/LMS/MMS) 대화 목록과 대화 내용 조회, SMS 보내기**, 새 메시지 작성
  - 메뉴바 팝오버와 별도 **메시지 창**
  - QR 코드로 페어링하고, 앱별로 알림을 숨기고, 로그인할 때 실행
- **Android 앱** (Kotlin + Jetpack Compose)
  - `NotificationListenerService`로 알림을 전달하고, `RemoteInput`으로 Mac에서 보낸 답장을 실행
  - SMS/MMS 제공자에서 대화를 읽고 `SmsManager`로 보내기 (보낸 문자는 기본 메시지 앱에도 기록됨)
  - 포그라운드 서비스(`remoteMessaging`)로 화면이 꺼져 있어도 연결 유지, 부팅하면 자동 시작
- **릴레이 서버** (`relay/`): 외부 네트워크 연결용. Cloudflare Workers(무료)에 배포하거나 Docker/Node로 직접 운영

```
 Android (Compose)                                             macOS (메뉴바)
┌──────────────────┐  ① 같은 Wi-Fi: Bonjour _slimmer._tcp   ┌──────────────────┐
│ NotificationList.│ ───────────── ws://mac:47900 ────────▶ │ 알림 목록 / 답장  │
│ SMS/MMS Provider │  ② 직접 주소 (Tailscale·포트포워딩)      │ 문자 대화 / 보내기│
│ SmsManager       │ ───────────── ws://host:port ────────▶ │ macOS 알림 미러링 │
│ Foreground Svc   │  ③ 어디서나: 릴레이                      │                  │
└──────────────────┘ ── wss://relay ──▶ [Relay] ◀── wss ── └──────────────────┘
          모든 메시지는 AES-256-GCM 종단간 암호화 (릴레이는 내용을 볼 수 없음)
```

## 연결 방식
폰은 다음 순서로 Mac에 연결하고, 연결이 끊기면 자동으로 다시 연결합니다.

1. **같은 네트워크 (LAN)**: Bonjour로 Mac을 찾아 직접 연결합니다. 가장 빠르고 서버가 필요 없습니다.
2. **직접 주소**: Mac 설정에 `호스트:포트`(예: Tailscale MagicDNS `my-mac.tailnet.ts.net:47900`)를 넣으면 그 주소로 연결합니다.
3. **릴레이**: 폰과 Mac이 둘 다 릴레이에 WebSocket으로 접속하고, 릴레이는 암호화된 프레임만 중계합니다. 폰이 LTE/5G에 있어도 동작합니다.

릴레이로 연결된 상태에서 같은 Wi-Fi에 들어오면 LAN 연결로 자동 전환합니다.

## 릴레이 서버 배포 (외부 네트워크용)
### A. Cloudflare Workers (권장, 무료)
```bash
cd relay/worker
```
```bash
npm install
```
```bash
npx wrangler login
```
```bash
npx wrangler deploy
```
배포하면 `https://slimmer-relay.<계정>.workers.dev` 주소가 나옵니다. Mac 앱 → **설정 → 외부 네트워크 (릴레이)** 에 이 주소를 넣고 **적용**하세요.
Durable Object(SQLite 백엔드)를 쓰므로 무료 플랜에서도 동작합니다. 유휴 연결은 hibernation으로 비용이 거의 들지 않습니다.

### B. 직접 운영 (Docker / Node.js)
```bash
cd relay/node
```
```bash
docker build -t slimmer-relay .
```
```bash
docker run -d --restart=always -p 8787:8787 slimmer-relay
```
앞단에 TLS(Caddy, nginx, Cloudflare Tunnel 등)를 두고 `wss://`로 노출하세요.

> 릴레이 없이 Tailscale만 써도 됩니다. 폰과 Mac에 Tailscale을 설치하고 Mac 설정의 직접 접속 주소에 `Mac의 Tailscale 이름:47900`을 넣으세요.

## 설치
[Releases](../../releases)에서 다운로드하세요.
- `Slimmer.apk`: 폰에 설치 (Android 10 이상)
- `Slimmer.dmg`: Applications로 드래그 (macOS 13 Ventura 이상)

### Mac
1. 서명되지 않은 앱이라 처음엔 Finder에서 **우클릭 → 열기** 하거나 다음을 실행하세요.
   ```bash
   xattr -dr com.apple.quarantine /Applications/Slimmer.app
   ```
2. 알림 권한을 허용하세요 (폰 알림을 macOS 알림으로 보여 줄 때 필요). 로컬 네트워크 권한을 물으면 허용하세요.
3. 메뉴바 아이콘 → **설정**에서 릴레이 주소를 입력하면 QR 코드가 나옵니다.

### Android
1. 앱을 열고 **QR 스캔**으로 Mac의 QR 코드를 찍으세요. 두 기기의 **확인 코드**가 같은지 확인하세요.
   카메라를 쓸 수 없으면 Mac에서 **코드 복사** → 폰에서 **코드 붙여넣기**를 쓰세요.
2. 권한 카드에서 모두 허용하세요.
   - **알림 접근**: APK로 설치한 앱은 Android 13부터 *제한된 설정*으로 막혀 있습니다.
     **설정 → 애플리케이션 → Slimmer → ⋮ → 제한된 설정 허용**을 누른 뒤 다시 허용하세요. Android 15에서는 SMS 권한도 같은 방법으로 풀어야 할 수 있습니다.
   - **문자, 연락처**, **알림 표시**
   - **배터리 최적화 제외**: 화면이 꺼져 있어도 연결을 유지하려면 필요합니다 (삼성은 *절전 예외 앱*에도 추가 권장).

## 제한 사항
- **보내기는 SMS만** 지원합니다. 긴 문자는 여러 개의 SMS로 나눠 보냅니다. MMS/LMS는 읽기만 하고 보내지는 않습니다. 단체 대화에는 Mac에서 보낼 수 없습니다.
- **RCS(채팅+) 메시지**는 Android가 다른 앱에 공개하지 않아 문자 목록에 나오지 않습니다. 대신 메시지 앱 **알림**으로 받고 알림에서 답장할 수 있습니다.
- 기본 SMS 앱이 아니면 '읽음' 표시를 바꿀 수 없습니다.
- 페어링 키는 Mac에서는 `UserDefaults`, 폰에서는 앱 전용 저장소에 보관합니다. Mac 설정의 **새로 페어링**을 누르면 키가 바뀌고, 이전 폰은 연결되지 않습니다.

## 빌드
- Android: `cd android && ./gradlew assembleRelease` (JDK 17, Android SDK 35)
- macOS: `cd macos && ./scripts/build-dmg.sh` (Xcode 15+/Swift 5.9+)
- CI: `.github/workflows/build.yml`이 push할 때마다 빌드합니다. `v*` 태그를 push하거나 `release_tag`를 넣어 워크플로를 실행하면 APK와 DMG를 Releases에 올립니다. APK 서명용 시크릿은 ExpandDisplay와 같습니다 (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## 프로토콜
### 페어링 코드
`slimmer://pair?v=1&k=<base64url 32바이트 키 K>&n=<Mac 이름>&r=<릴레이 wss URL>&d=<직접 주소>`

| 값 | 유도 |
|---|---|
| 릴레이 방 ID | `hex(SHA256("slimmer/room" ‖ K))[0..32]` |
| Bonjour 이름 | `slimmer-` + `hex(SHA256("slimmer/lan" ‖ K))[0..16]` |
| 암호화 키 | `SHA256("slimmer/enc" ‖ K)` |
| 확인 코드 | `hex(SHA256("slimmer/fp" ‖ K))[0..8]` |

### 전송
- WebSocket 바이너리 프레임 = `nonce(12) ‖ AES-256-GCM 암호문 ‖ tag(16)`, AAD는 `"p2m"`(폰→Mac) / `"m2p"`(Mac→폰)
- 평문은 JSON `{"type", "id"(uuid), "ts"(ms), ...}`. 10분보다 오래되었거나 이미 받은 `id`면 버립니다 (재전송 방지).
- 릴레이: `GET /v1/room/<방 ID>?role=mac|phone` (WebSocket). 릴레이가 보내는 텍스트 프레임 `{"t":"peer","online":bool}`은 상대가 접속했는지 알려 줍니다.

### 메시지
| 방향 | type | 내용 |
|---|---|---|
| 폰→Mac | `hello` | device, perms{notif, sms}, battery |
| 폰→Mac | `status` | battery, charging |
| 폰→Mac | `icon` | pkg, png(base64) |
| 폰→Mac | `notif.snapshot` / `notif.posted` / `notif.removed` | items[] / n / key |
| 폰→Mac | `notif.result` | req, ok, error |
| 폰→Mac | `sms.threads` / `sms.messages` | threads[] / threadId, messages[] |
| 폰→Mac | `sms.changed` | – (문자 DB가 바뀜) |
| 폰→Mac | `sms.result` | req, ok, error, threadId |
| Mac→폰 | `notif.sync` | – |
| Mac→폰 | `notif.action` | req, key, action(index), text? (인라인 답장) |
| Mac→폰 | `notif.dismiss` / `notif.open` | key |
| Mac→폰 | `sms.threads.get` | limit |
| Mac→폰 | `sms.messages.get` | threadId, limit |
| Mac→폰 | `sms.send` | req, address, body, threadId? |
