package com.alharam.answer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/** جسر بين ReplyController وخدمة إمكانية الوصول: مهمة رسالة واحدة في كل مرة. */
final class DmBridge {
    interface Listener { void onResult(boolean ok, String reason, String dump); }

    static volatile boolean active = false;
    static volatile String text = "";
    static volatile Listener listener = null;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static boolean serviceEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String pkg = c.getPackageName();
            return s.contains(pkg + "/" + MessengerAccessibilityService.class.getName()) || s.contains(pkg + "/.MessengerAccessibilityService");
        } catch (Exception e) { return false; }
    }

    static boolean serviceConnected() { return MessengerAccessibilityService.instance != null; }

    static boolean begin(String t, Listener l) {
        if (MessengerAccessibilityService.instance == null) return false;
        text = t; listener = l; active = true;
        main.post(() -> {
            MessengerAccessibilityService s = MessengerAccessibilityService.instance;
            if (s != null) s.onJobStarted();
        });
        return true;
    }

    static void cancel() { active = false; listener = null; }

    static void finish(boolean ok, String reason, String dump) {
        active = false;
        final Listener l = listener; listener = null;
        if (l != null) main.post(() -> l.onResult(ok, reason, dump));
    }
}
