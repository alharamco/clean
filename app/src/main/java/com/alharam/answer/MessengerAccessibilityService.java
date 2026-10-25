package com.alharam.answer;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * تكتب الرسالة في ماسنجر وتضغط إرسال ثم ترجع لتطبيقنا.
 * لا تفعل شيئاً إلا عندما تكون هناك مهمة نشطة في DmBridge.
 */
public class MessengerAccessibilityService extends AccessibilityService {
    static volatile MessengerAccessibilityService instance;
    static final String MESSENGER_PKG = "com.facebook.orca";

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    // 0 خامل، 1 انتظار فتح ماسنجر، 2 كتابة، 3 إرسال، 4 تحقق، 5 رجوع للتطبيق
    private int phase = 0, sendTries = 0, backTries = 0;
    private long phaseAt = 0, lastBackAt = 0;
    private boolean okResult = false, broughtFront = false;
    private String reason = "", failDump = "", chunk = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { step(); } catch (Throwable t) { fail("خطأ داخلي: " + t); }
            if (DmBridge.active) h.postDelayed(this, 700); else polling = false;
        }
    };

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    void onJobStarted() {
        phase = 1; phaseAt = SystemClock.uptimeMillis();
        sendTries = 0; backTries = 0; okResult = false; broughtFront = false; reason = ""; failDump = ""; lastBackAt = 0;
        String t = DmBridge.text == null ? "" : DmBridge.text;
        chunk = t.length() > 10 ? t.substring(0, 10) : t;
        if (!polling) { polling = true; h.postDelayed(tick, 900); }
    }

    private void fail(String why) {
        if (phase == 5) return;
        okResult = false; reason = why;
        try { failDump = dump(getRootInActiveWindow()); } catch (Throwable ignored) { failDump = ""; }
        phase = 5; phaseAt = SystemClock.uptimeMillis();
    }

    private void complete() {
        phase = 0;
        DmBridge.finish(okResult, reason, failDump);
    }

    private void step() {
        if (!DmBridge.active || phase == 0) return;
        long now = SystemClock.uptimeMillis(), el = now - phaseAt;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = (root != null && root.getPackageName() != null) ? root.getPackageName().toString() : "";
        boolean inMessenger = MESSENGER_PKG.equals(pkg);

        switch (phase) {
            case 1:
                if (inMessenger) { phase = 2; phaseAt = now; }
                else if (el > 25000) fail("لم يفتح تطبيق ماسنجر خلال 25 ثانية");
                break;
            case 2: {
                if (!inMessenger) { if (el > 30000) fail("خرج التطبيق من ماسنجر قبل كتابة الرسالة"); break; }
                String block = detectBlocker(root);
                if (block != null) { fail(block); break; }
                AccessibilityNodeInfo box = findComposer(root);
                if (box != null) {
                    Bundle b = new Bundle();
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, DmBridge.text);
                    if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) { phase = 3; phaseAt = now; }
                } else if (el > 30000) fail("لم تظهر خانة كتابة الرسالة في ماسنجر");
                break;
            }
            case 3: {
                if (!inMessenger) break;
                AccessibilityNodeInfo send = findSend(root);
                if (send != null) { click(send); sendTries++; phase = 4; phaseAt = now; }
                else if (el > 12000) fail("لم يظهر زر الإرسال في ماسنجر");
                break;
            }
            case 4: {
                if (el < 1600) break;
                AccessibilityNodeInfo box = inMessenger ? findComposer(root) : null;
                CharSequence left = box == null ? null : box.getText();
                boolean still = left != null && chunk.length() > 0 && left.toString().contains(chunk);
                if (!still) { okResult = true; reason = ""; phase = 5; phaseAt = now; }
                else if (sendTries < 3) { phase = 3; phaseAt = now; }
                else fail("كُتبت الرسالة ولم تُرسل");
                break;
            }
            case 5: {
                if (getPackageName().equals(pkg)) { complete(); break; }
                if (!broughtFront) {
                    broughtFront = true;
                    try {
                        Intent i = new Intent(this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(i);
                    } catch (Throwable ignored) { }
                    lastBackAt = now;
                    break;
                }
                if (now - lastBackAt > 1600) {
                    if (backTries < 4) { performGlobalAction(GLOBAL_ACTION_BACK); backTries++; lastBackAt = now; }
                    else { if (okResult) reason = "تم الإرسال لكن تعذر الرجوع للتطبيق تلقائياً"; complete(); }
                }
                break;
            }
            default: break;
        }
    }

    // ---------- أدوات البحث في شاشة ماسنجر ----------
    private List<AccessibilityNodeInfo> all(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty() && out.size() < 600) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null) continue;
            out.add(n);
            for (int i = n.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) stack.add(c);
            }
        }
        return out;
    }

    private AccessibilityNodeInfo findComposer(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : all(root)) {
            boolean edit = n.isEditable() || (n.getClassName() != null && n.getClassName().toString().contains("EditText"));
            if (edit && n.isVisibleToUser()) best = n;
        }
        return best;
    }

    private static boolean isSendWord(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("send") || t.equals("send message") || t.equals("إرسال") || t.equals("ارسال") || t.equals("إرسال الرسالة") || t.equals("ارسال الرسالة");
    }

    private AccessibilityNodeInfo findSend(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isVisibleToUser()) continue;
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            String t = n.getText() == null ? "" : n.getText().toString();
            if (isSendWord(d) || isSendWord(t)) return n;
        }
        return null;
    }

    private void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int i = 0; i < 4 && p != null; i++) {
            if (p.isClickable() && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            p = p.getParent();
        }
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private String detectBlocker(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : all(root)) {
            String t = ((n.getText() == null ? "" : n.getText().toString()) + " " + (n.getContentDescription() == null ? "" : n.getContentDescription().toString())).toLowerCase(Locale.ROOT);
            if (t.contains("enter your pin") || t.contains("restore your chat") || t.contains("رمز pin") || t.contains("أدخل رمز")) return "ماسنجر يطلب رمز PIN — أدخله يدوياً مرة واحدة ثم أعد المحاولة";
            if (t.contains("can't message") || t.contains("cannot message") || t.contains("you can't reply") || t.contains("لا يمكنك مراسلة") || t.contains("لا يمكنك الرد")) return "لا يمكن مراسلة هذا الحساب";
            if (t.contains("isn't available") || t.contains("غير متاح") ) return "الحساب غير متاح في ماسنجر";
        }
        return null;
    }

    private String dump(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (AccessibilityNodeInfo n : all(root)) {
            String t = n.getText() == null ? "" : n.getText().toString().replace('\n', ' ');
            String d = n.getContentDescription() == null ? "" : n.getContentDescription().toString().replace('\n', ' ');
            if (t.isEmpty() && d.isEmpty()) continue;
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            sb.append(cls).append(n.isClickable() ? "*" : "").append(":").append(t.length() > 40 ? t.substring(0, 40) : t);
            if (!d.isEmpty()) sb.append("{").append(d.length() > 40 ? d.substring(0, 40) : d).append("}");
            sb.append(" | ");
            if (++count >= 40) break;
        }
        return sb.toString();
    }
}
