package com.thunderx.telegramagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

public class StorageChangeReceiver extends BroadcastReceiver {

    private static final String TAG = "StorageChangeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent.getAction();
            if (action == null) return;

            Log.d(TAG, "Storage event: " + action);

            // Trigger storage scan in the agent service
            Intent serviceIntent = new Intent(context, TelegramAgentService.class);
            serviceIntent.setAction("com.thunderx.telegramagent.SCAN_STORAGE");
            context.startService(serviceIntent);

        } catch (Exception e) {
            Log.e(TAG, "onReceive error: " + e.getMessage());
        }
    }
}