package com.example.desktoppet;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

import java.util.Calendar;

public class AlarmReceiver extends BroadcastReceiver {
    static final String EXTRA_ITEM_ID = "item_id";
    static final String CHANNEL_ID = "task_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(EXTRA_ITEM_ID, -1L);
        if (id < 0) return;
        AppDatabase db = new AppDatabase(context);
        Item item = db.get(id);
        if (item == null || item.done || !"TASK".equals(item.type)) return;

        ensureChannel(context);
        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, (int) (id & 0x7fffffff), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification notification = new android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pet_notification)
                .setContentTitle("费雾提醒你：" + item.title)
                .setContentText(item.content.isEmpty() ? "点一下查看今天的待办" : item.content)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_REMINDER)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .build();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) (1000 + (id & 0x3fffffff)), notification);

        PetOverlayService.showReminderIfRunning(item.title);

        if (!"NONE".equals(item.repeatType)) {
            Calendar next = Calendar.getInstance();
            next.setTimeInMillis(item.dueAt);
            if ("DAILY".equals(item.repeatType)) next.add(Calendar.DAY_OF_YEAR, 1);
            if ("WEEKLY".equals(item.repeatType)) next.add(Calendar.WEEK_OF_YEAR, 1);
            while (next.getTimeInMillis() <= System.currentTimeMillis()) {
                if ("DAILY".equals(item.repeatType)) next.add(Calendar.DAY_OF_YEAR, 1);
                else next.add(Calendar.WEEK_OF_YEAR, 1);
            }
            db.updateDue(id, next.getTimeInMillis());
            ReminderScheduler.schedule(context, id, next.getTimeInMillis());
        }
        db.close();
    }

    static void ensureChannel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("桌宠的待办和定时提醒");
        channel.enableVibration(true);
        channel.enableLights(true);
        channel.setLightColor(Color.rgb(255, 138, 52));
        nm.createNotificationChannel(channel);
    }
}
