package com.thunderx.telegramagent;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationListener extends NotificationListenerService {

    private static final String TAG = "NotificationListener";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "✅ Listener connected");
        try {
            TelegramAgentService.setNotificationListenerConnected(true);
        } catch (Exception e) {
            Log.e(TAG, "setNotificationListenerConnected error: " + e.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.d(TAG, "❌ Listener disconnected");
        try {
            TelegramAgentService.setNotificationListenerConnected(false);
        } catch (Exception e) {
            Log.e(TAG, "setNotificationListenerConnected error: " + e.getMessage());
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            if (sbn.getNotification() == null) return;

            String packageName = sbn.getPackageName();
            if (packageName == null) return;

            // ═══════════════════════════════════════════════
            //  Only skip our own notifications
            // ═══════════════════════════════════════════════
            if (packageName.equals(getPackageName())) return;

            Notification notification = sbn.getNotification();

            // ═══════════════════════════════════════════════
            //  Skip ongoing notifications (music, download, etc.)
            //  These fire every second and cause spam
            // ═══════════════════════════════════════════════
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;

            // ═══════════════════════════════════════════════
            //  Extract text with multiple fallbacks
            // ═══════════════════════════════════════════════
            Bundle extras = notification.extras;
            if (extras == null) return;

            String title = extractText(extras, Notification.EXTRA_TITLE);
            String text  = extractText(extras, Notification.EXTRA_TEXT);

            // Try BIG_TEXT (WhatsApp/Telegram long messages)
            if (text == null || text.isEmpty()) {
                text = extractText(extras, Notification.EXTRA_BIG_TEXT);
            }

            // Try SUB_TEXT
            if (text == null || text.isEmpty()) {
                text = extractText(extras, Notification.EXTRA_SUB_TEXT);
            }

            // Try SUMMARY_TEXT
            if (text == null || text.isEmpty()) {
                text = extractText(extras, Notification.EXTRA_SUMMARY_TEXT);
            }

            // Try INFO_TEXT
            if (text == null || text.isEmpty()) {
                text = extractText(extras, Notification.EXTRA_INFO_TEXT);
            }

            // Try TEXT_LINES (multi-line notifications)
            if (text == null || text.isEmpty()) {
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (CharSequence line : lines) {
                        if (line != null) sb.append(line).append("\n");
                    }
                    text = sb.toString().trim();
                }
            }

            // ═══════════════════════════════════════════════
            //  Skip empty notifications
            // ═══════════════════════════════════════════════
            if ((title == null || title.isEmpty()) &&
                (text == null || text.isEmpty())) {
                return;
            }

            // ═══════════════════════════════════════════════
            //  Truncate very long text (Telegram limit)
            // ═══════════════════════════════════════════════
            if (text != null && text.length() > 800) {
                text = text.substring(0, 800) + "...";
            }
            if (title != null && title.length() > 150) {
                title = title.substring(0, 150) + "...";
            }

            Log.d(TAG, "📱 [" + packageName + "] " + title + " : " + text);

            // ═══════════════════════════════════════════════
            //  Send to Telegram
            // ═══════════════════════════════════════════════
            TelegramAgentService.onNotificationReceived(packageName, title, text);

        } catch (Exception e) {
            Log.e(TAG, "onNotificationPosted error: " + e.getMessage());
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not used
    }

    // ═══════════════════════════════════════════════════
    //  Helper: Extract text from CharSequence variants
    // ═══════════════════════════════════════════════════
    private String extractText(Bundle extras, String key) {
        try {
            Object value = extras.get(key);
            if (value == null) return null;

            if (value instanceof CharSequence) {
                return value.toString();
            } else if (value instanceof String) {
                return (String) value;
            }
            return value.toString();
        } catch (Exception e) {
            return null;
        }
    }
}