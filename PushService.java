package com.apkforge.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;

public class PushService extends FirebaseMessagingService {
    static final String CHANNEL = "apkforge_push";

    /** Fetch the FCM token and send it to the server under this app's package name. */
    static void registerToken(final Context ctx) {
        CtxHolder.ctx = ctx.getApplicationContext();
        if (!App.pushReady) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(PushService::sendToken);
    }

    static void sendToken(final String token) {
        // application context is not needed: Cfg is cached after first load
        new Thread(() -> {
            try {
                android.content.Context ctx = CtxHolder.ctx;
                if (ctx == null) return;
                JSONObject cfg = Cfg.load(ctx);
                JSONObject b = new JSONObject();
                b.put("packageName", ctx.getPackageName());
                b.put("token", token);
                b.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                b.put("android", Build.VERSION.RELEASE);
                Cfg.http(cfg.optString("api") + "/api/device/register", "POST", b.toString(), 8000);
            } catch (Exception ignored) { }
        }).start();
    }

    @Override
    public void onNewToken(String token) {
        CtxHolder.ctx = getApplicationContext();
        sendToken(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage m) {
        Map<String, String> d = m.getData();
        String title = d.get("title"), body = d.get("body"), image = d.get("image");
        if (title == null && m.getNotification() != null) {
            title = m.getNotification().getTitle();
            body = m.getNotification().getBody();
        }
        if (title == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Notifications", NotificationManager.IMPORTANCE_HIGH));
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(pi).setAutoCancel(true);
        if (image != null && !image.isEmpty()) {
            try (InputStream in = new URL(image).openStream()) {
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) nb.setStyle(new Notification.BigPictureStyle().bigPicture(bmp).setSummaryText(body)).setLargeIcon(bmp);
            } catch (Exception ignored) { }
        }
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), nb.build());
    }

    static final class CtxHolder { static Context ctx; }
}
