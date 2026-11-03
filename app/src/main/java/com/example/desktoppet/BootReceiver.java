package com.example.desktoppet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        AppDatabase db = new AppDatabase(context);
        for (Item item : db.getPendingTasks()) {
            ReminderScheduler.schedule(context, item.id, item.dueAt);
        }
        db.close();

        boolean autoStart = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
                .getBoolean("auto_start", false);
        if (autoStart && Settings.canDrawOverlays(context)) {
            Intent serviceIntent = new Intent(context, PetOverlayService.class);
            try {
                context.startForegroundService(serviceIntent);
            } catch (RuntimeException ignored) {
                // Some vendor ROMs block boot-time foreground services. The task alarms remain active.
            }
        }
    }
}
