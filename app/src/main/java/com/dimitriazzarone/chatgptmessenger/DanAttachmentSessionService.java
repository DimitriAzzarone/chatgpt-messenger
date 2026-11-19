package com.dimitriazzarone.chatgptmessenger;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Bounded foreground session while the external document picker owns the screen.
 * Does not read, transmit or persist document contents. Never restarts itself. */
public final class DanAttachmentSessionService extends Service {
    private static final String CHANNEL = "dan_attachment_selection";
    private static final int NOTIFICATION = 15511;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expire = () -> {
        mark("Timeout protezione selettore");
        finishSession();
    };

    private void mark(String state) {
        getSharedPreferences("dan_upload_diagnostics", MODE_PRIVATE).edit()
                .putString("pickerProtection", state)
                .putLong("pickerProtectionTime", System.currentTimeMillis()).commit();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Selezione allegati", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL);
        } else builder = new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("Dan · scelta file in corso")
                .setContentText("Protezione temporanea durante la scelta del file")
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE).setPriority(Notification.PRIORITY_LOW).build();
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            else startForeground(NOTIFICATION, notification);
            mark("Attiva; PID " + android.os.Process.myPid());
            handler.removeCallbacks(expire);
            handler.postDelayed(expire, 150000L); // Under Android 14's three-minute limit.
        } catch (RuntimeException error) {
            mark("Non disponibile: " + error.getClass().getSimpleName());
            finishSession();
        }
        return START_NOT_STICKY;
    }

    private void finishSession() {
        handler.removeCallbacks(expire);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onTimeout(int startId) { mark("Timeout Android"); finishSession(); }
    @Override public void onTimeout(int startId, int type) { mark("Timeout Android"); finishSession(); }
    @Override public void onDestroy() {
        handler.removeCallbacks(expire);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
