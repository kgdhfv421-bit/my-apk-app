package com.apkforge.shell;

import android.app.Application;
import android.util.Log;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.json.JSONObject;

/** Initialises Firebase from config.json so push works even when the app is woken up by FCM. */
public class App extends Application {
    static boolean pushReady = false;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            JSONObject push = Cfg.load(this).optJSONObject("push");
            if (push == null || !push.optBoolean("enabled")) return;
            JSONObject fb = push.getJSONObject("firebase");
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this, new FirebaseOptions.Builder()
                        .setApiKey(fb.getString("apiKey"))
                        .setApplicationId(fb.getString("appId"))
                        .setProjectId(fb.getString("projectId"))
                        .setGcmSenderId(fb.getString("senderId"))
                        .build());
            }
            pushReady = true;
        } catch (Exception e) {
            Log.e("APKForge", "Firebase init failed", e);
        }
    }
}
