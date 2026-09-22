package com.thunderx.telegramagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CallReceiver extends BroadcastReceiver {

    private static String lastState = TelephonyManager.EXTRA_STATE_IDLE;
    private static String savedNumber = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        try {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            if (state == null) return;

            // Capture incoming number
            if (state.equals(TelephonyManager.EXTRA_STATE_RINGING)
                    || state.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                if (number != null && !number.isEmpty()) {
                    savedNumber = number;
                }
            }

            // ===== Call started (OFFHOOK) =====
            if (state.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)
                    && !lastState.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {

                Log.d("CallReceiver", "Call started: " + savedNumber);

                Intent svc = new Intent(context, CallRecorder.class);
                svc.putExtra("action", "start");
                svc.putExtra("number", savedNumber);

                // Android 8+ requires startForegroundService
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }

            // ===== Call ended (IDLE) =====
            if (state.equals(TelephonyManager.EXTRA_STATE_IDLE)
                    && lastState.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {

                Log.d("CallReceiver", "Call ended");

                Intent svc = new Intent(context, CallRecorder.class);
                svc.putExtra("action", "stop");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }

                savedNumber = "";
            }

            lastState = state;

        } catch (Exception e) {
            Log.e("CallReceiver", "Error: " + e.getMessage());
        }
    }
}