package com.thunderx.telegramagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PhoneStateReceiver extends BroadcastReceiver {

    private static final String TAG = "PhoneStateReceiver";

    // ── Static state (persists across broadcasts) ──
    private static String lastState = TelephonyManager.EXTRA_STATE_IDLE;
    private static String currentNumber = null;
    private static long callStartTime = 0;
    private static boolean isIncoming = false;
    private static boolean callNotificationSent = false;  // prevent duplicate
    private static boolean answeredNotificationSent = false;
    private static boolean endedNotificationSent = false;

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent.getAction();
            if (action == null) return;

            // ─── OUTGOING CALL ───
            if (action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
                String number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
                if (number == null) number = "Unknown";

                // Reset state for new call
                currentNumber = number;
                isIncoming = false;
                callStartTime = System.currentTimeMillis();
                callNotificationSent = false;
                answeredNotificationSent = false;
                endedNotificationSent = false;

                Log.d(TAG, "📞 Outgoing: " + number);

                TelegramAgentService.sendMessage(
                        "📞 *Outgoing Call*\n" +
                        "Number: `" + number + "`\n" +
                        "Time: " + TIME_FMT.format(new Date()));
                callNotificationSent = true;
                return;
            }

            // ─── PHONE STATE CHANGED ───
            if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

                if (state == null) return;

                Log.d(TAG, "State: " + state + " | Number: " + number);

                // ═══════════════════════════════════════════
                // RINGING — incoming call
                // ═══════════════════════════════════════════
                if (state.equals(TelephonyManager.EXTRA_STATE_RINGING)) {

                    // Skip duplicate "unknown" ringing (Android sends state twice)
                    if (number != null && !number.isEmpty()) {
                        currentNumber = number;
                    } else if (currentNumber != null) {
                        // Ignore unknown state if we already have a number
                        Log.d(TAG, "Ignoring unknown ringing (already have number)");
                        return;
                    } else {
                        currentNumber = "Unknown";
                    }

                    // Skip if we already sent notification for this call
                    if (callNotificationSent) {
                        Log.d(TAG, "Ringing notification already sent — skipping");
                        return;
                    }

                    isIncoming = true;
                    callStartTime = System.currentTimeMillis();
                    callNotificationSent = true;
                    answeredNotificationSent = false;
                    endedNotificationSent = false;

                    TelegramAgentService.sendMessage(
                            "📞 *Incoming Call*\n" +
                            "Number: `" + currentNumber + "`\n" +
                            "Time: " + TIME_FMT.format(new Date()));
                }

                // ═══════════════════════════════════════════
                // OFFHOOK — call answered or outgoing in progress
                // ═══════════════════════════════════════════
                else if (state.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {

                    // For OUTGOING calls — we already notified, don't send again
                    if (!isIncoming) {
                        Log.d(TAG, "Offhook for outgoing — already notified");
                        return;
                    }

                    // For INCOMING calls — send "answered" only once
                    if (answeredNotificationSent) {
                        Log.d(TAG, "Answered notification already sent — skipping");
                        return;
                    }

                    answeredNotificationSent = true;
                    callStartTime = System.currentTimeMillis();  // reset to answer time

                    TelegramAgentService.sendMessage(
                            "📞 *Call Answered*\n" +
                            "Number: `" + currentNumber + "`\n" +
                            "Time: " + TIME_FMT.format(new Date()));
                }

                // ═══════════════════════════════════════════
                // IDLE — call ended
                // ═══════════════════════════════════════════
                else if (state.equals(TelephonyManager.EXTRA_STATE_IDLE)) {

                    // Skip if no call was tracked
                    if (!callNotificationSent) {
                        Log.d(TAG, "Idle without active call — skipping");
                        return;
                    }

                    // Skip duplicates
                    if (endedNotificationSent) {
                        Log.d(TAG, "Ended notification already sent — skipping");
                        return;
                    }
                    endedNotificationSent = true;

                    long durationMs = System.currentTimeMillis() - callStartTime;
                    long durationSec = durationMs / 1000;
                    String durationStr = formatDuration(durationSec);

                    // Determine call type
                    String callType;
                    if (lastState.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                        // Was ringing but never went OFFHOOK → Missed
                        callType = "Missed";
                    } else if (lastState.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                        // Was answered → normal ended
                        callType = isIncoming ? "Incoming" : "Outgoing";
                    } else {
                        callType = "Unknown";
                    }

                    // Build message based on call type
                    if (callType.equals("Missed")) {
                        TelegramAgentService.sendMessage(
                                "📞 *Missed Call*\n" +
                                "Number: `" + currentNumber + "`\n" +
                                "Time: " + TIME_FMT.format(new Date()));
                    } else {
                        TelegramAgentService.sendMessage(
                                "📞 *Call Ended* (" + callType + ")\n" +
                                "Number: `" + currentNumber + "`\n" +
                                "Duration: " + durationStr + "\n" +
                                "Ended: " + TIME_FMT.format(new Date()));
                    }

                    // ── Reset state for next call ──
                    resetState();
                }

                lastState = state;
            }

        } catch (Exception e) {
            Log.e(TAG, "onReceive error: " + e.getMessage());
        }
    }

    // ── Reset all state variables ──
    private void resetState() {
        currentNumber = null;
        callStartTime = 0;
        isIncoming = false;
        callNotificationSent = false;
        answeredNotificationSent = false;
        endedNotificationSent = false;
    }

    // ── Format duration: 1h 2m 3s / 2m 3s / 3s ──
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            long m = seconds / 60;
            long s = seconds % 60;
            return m + "m " + s + "s";
        } else {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            return h + "h " + m + "m " + s + "s";
        }
    }
}