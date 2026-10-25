package com.alharam.answer;
import com.alharam.answer.R;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity implements ReplyController.Host {
    WebView web;
    View webContainer, resizeHandle, replyLiveLogOverlay;
    TextView replyLiveLog;
    ScrollView controlScroll;
    LinearLayout controlRoot;
    Button fullscreenButton;
    Switch replySwitch, likerSwitch, inviterSwitch;
    EditText sharedUrl; Button sharedOpen; StepperView sharedCount, sharedDelay, sharedMinute, sharedHour, sharedAmpm;
    View replyPanel, likerPanel, inviterPanel;
    ReplyController replyController;
    LikerController likerController;
    InviterController inviterController;
    Handler handler = new Handler(Looper.getMainLooper());
    android.content.SharedPreferences prefs;
    boolean fullscreenWeb = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("hub_settings", 0);

        web = findViewById(R.id.web);
        webContainer = findViewById(R.id.webContainer);
        resizeHandle = findViewById(R.id.resizeHandle);
        replyLiveLogOverlay = findViewById(R.id.replyLiveLogOverlay);
        replyLiveLog = findViewById(R.id.replyLiveLog);
        controlScroll = findViewById(R.id.controlScroll);
        controlRoot = findViewById(R.id.controlRoot);
        fullscreenButton = findViewById(R.id.fullscreenButton);
        replySwitch = findViewById(R.id.replySwitch);
        likerSwitch = findViewById(R.id.likerSwitch);
        inviterSwitch = findViewById(R.id.inviterSwitch);
        sharedUrl = findViewById(R.id.shared_url);
        sharedOpen = findViewById(R.id.shared_open);
        sharedCount = findViewById(R.id.shared_count);
        sharedDelay = findViewById(R.id.shared_delay);
        sharedMinute = findViewById(R.id.shared_minute);
        sharedHour = findViewById(R.id.shared_hour);
        sharedAmpm = findViewById(R.id.shared_ampm);
        setupSharedSettings();
        replyPanel = findViewById(R.id.replyPanel);
        likerPanel = findViewById(R.id.likerPanel);
        inviterPanel = findViewById(R.id.inviterPanel);

        configureSharedFacebook();
        setupPanels();
        findViewById(R.id.exitApp).setOnClickListener(v -> finishAffinity());
        setupSharedSplitter();
        restoreSavedLayout();
        web.loadUrl("https://www.facebook.com/");
    }

    void setupSharedSettings() {
        sharedUrl.setText(prefs.getString("url", ""));
        sharedCount.configure(1, 1000, prefs.getInt("count", 10), false, "عملية");
        sharedDelay.configure(1, 300, prefs.getInt("delay", 20), false, "ثانية");
        // وقت البدء يبدأ فارغاً دائماً ولا يُحفظ (الفراغ = البدء فوراً)
        prefs.edit().remove("minuteValue").remove("hourValue").remove("ampmValue").apply();
        sharedMinute.configure(0, 59, 0, true, "دقيقة");
        sharedHour.configure(1, 12, 0, true, "ساعة");
        sharedAmpm.configureText(new String[]{"صباحًا", "مساءً"}, -1, true);
        sharedOpen.setOnClickListener(v -> {
            saveSharedSettings();
            String u = sharedUrl.getText().toString().trim();
            if (u.isEmpty()) return;
            PostOpener.open(MainActivity.this, web, u);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (replyController != null) replyController.refreshServiceStatus();
    }

    void saveSharedSettings() {
        prefs.edit().putString("url", sharedUrl.getText().toString().trim())
            .putInt("count", Math.max(1, sharedCount.getNumericValue()))
            .putInt("delay", Math.max(1, sharedDelay.getNumericValue())).apply();
    }

    String sharedStartTime() {
        int h=sharedHour.getNumericValue(), m=sharedMinute.getNumericValue(), p=sharedAmpm.getTextIndex();
        if(h<0 && m<0 && p<0) return "";
        if(h<0 || m<0 || p<0) return null;
        int h24=h%12; if(p==1) h24+=12;
        return String.format(java.util.Locale.getDefault(), "%02d:%02d %s", h, m, sharedAmpm.getTextValue());
    }

    long sharedStartDelayMs() {
        int h=sharedHour.getNumericValue(), m=sharedMinute.getNumericValue(), p=sharedAmpm.getTextIndex();
        if(h<0 || m<0 || p<0) return 0;
        int h24=h%12; if(p==1) h24+=12;
        java.util.Calendar c=java.util.Calendar.getInstance(); c.set(java.util.Calendar.HOUR_OF_DAY,h24); c.set(java.util.Calendar.MINUTE,m); c.set(java.util.Calendar.SECOND,0); c.set(java.util.Calendar.MILLISECOND,0);
        if(c.getTimeInMillis()<=System.currentTimeMillis()) c.add(java.util.Calendar.DATE,1);
        return c.getTimeInMillis()-System.currentTimeMillis();
    }

    void configureSharedFacebook() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        web.setInitialScale(0);
        s.setUserAgentString(s.getUserAgentString() + " ElHaramFacebookAutomationHub/1.0");
        web.setVerticalScrollBarEnabled(true);
        web.setHorizontalScrollBarEnabled(false);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        web.setNestedScrollingEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleSpecialUrl(view, request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return handleSpecialUrl(view, url); }
            private boolean handleSpecialUrl(WebView view, String targetUrl) {
                if (targetUrl == null || targetUrl.isEmpty()) return false;
                Uri uri = Uri.parse(targetUrl); String scheme = uri.getScheme();
                if (scheme == null) return false;
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                if ("intent".equalsIgnoreCase(scheme)) {
                    try {
                        Intent intent = Intent.parseUri(targetUrl, Intent.URI_INTENT_SCHEME);
                        String fallback = intent.getStringExtra("browser_fallback_url");
                        if (fallback != null && !fallback.trim().isEmpty()) { view.loadUrl(fallback.trim()); return true; }
                        Uri data = intent.getData();
                        if (data != null && ("http".equalsIgnoreCase(data.getScheme()) || "https".equalsIgnoreCase(data.getScheme()))) { view.loadUrl(data.toString()); return true; }
                    } catch (Exception ignored) {}
                    return true;
                }
                if ("fb".equalsIgnoreCase(scheme)) {
                    String fallback = uri.getQueryParameter("browser_fallback_url");
                    if (fallback != null && !fallback.trim().isEmpty()) view.loadUrl(fallback);
                    return true;
                }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript("(function(){try{var m=document.querySelector(\"meta[name='viewport']\");if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=yes';document.documentElement.style.width='100%';if(document.body){document.body.style.width='100%';document.body.style.maxWidth='100%';}}catch(e){}})();", null);
            }
        });
    }

    void setupPanels() {
        replyPanel.setVisibility(View.GONE); likerPanel.setVisibility(View.GONE); inviterPanel.setVisibility(View.GONE);
        replySwitch.setChecked(false); likerSwitch.setChecked(false); inviterSwitch.setChecked(false);
        replySwitch.setOnCheckedChangeListener((b, checked) -> replyPanel.setVisibility(checked ? View.VISIBLE : View.GONE));
        likerSwitch.setOnCheckedChangeListener((b, checked) -> likerPanel.setVisibility(checked ? View.VISIBLE : View.GONE));
        inviterSwitch.setOnCheckedChangeListener((b, checked) -> inviterPanel.setVisibility(checked ? View.VISIBLE : View.GONE));
        replyController = new ReplyController(this, replyPanel, web, this);
        likerController = new LikerController(this, likerPanel, web);
        inviterController = new InviterController(this, inviterPanel, web);
        // Reply is the first/default tool, while the other two stay collapsed.
        replySwitch.setChecked(true);
    }

    void setupSharedSplitter() {
        resizeHandle.setOnTouchListener(new View.OnTouchListener() {
            float downY; int startWeb;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: downY=e.getRawY(); startWeb=webContainer.getHeight(); v.setPressed(true); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawY()-downY)>8) applyWebHeight(startWeb-(int)(e.getRawY()-downY));
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (Math.abs(e.getRawY()-downY)<12) setFacebookFullscreen(true);
                        else prefs.edit().putInt("webHeight", webContainer.getHeight()).apply();
                        return true;
                    case MotionEvent.ACTION_CANCEL: v.setPressed(false); return true;
                }
                return true;
            }
        });
        fullscreenButton.setOnClickListener(v -> setFacebookFullscreen(false));
    }

    void restoreSavedLayout() {
        int saved=prefs.getInt("webHeight",0);
        if(saved>0) resizeHandle.post(()->applyWebHeight(saved));
    }

    void applyWebHeight(int h) {
        int parentH=((View)webContainer.getParent()).getHeight(); if(parentH<=0)return;
        int minWeb=180, minTop=220;
        int maxWeb=Math.max(minWeb,parentH-resizeHandle.getHeight()-minTop);
        h=Math.max(minWeb,Math.min(maxWeb,h));
        ViewGroup.LayoutParams wlp=webContainer.getLayoutParams(); wlp.height=h;
        if(wlp instanceof LinearLayout.LayoutParams)((LinearLayout.LayoutParams)wlp).weight=0;
        webContainer.setLayoutParams(wlp);
        ViewGroup.LayoutParams tlp=controlScroll.getLayoutParams(); tlp.height=Math.max(minTop,parentH-resizeHandle.getHeight()-h);
        if(tlp instanceof LinearLayout.LayoutParams)((LinearLayout.LayoutParams)tlp).weight=0;
        controlScroll.setLayoutParams(tlp);
        webContainer.requestLayout(); controlScroll.requestLayout();
    }

    @Override public void expandFacebook(String reason) { setFacebookFullscreen(true); }
    @Override public void restoreFacebook() { setFacebookFullscreen(false); }
    @Override public void toggleFacebook() { setFacebookFullscreen(!fullscreenWeb); }

    @Override public void enterReplyFocusMode() {
        setFacebookFullscreen(true);
        replyLiveLogOverlay.setVisibility(View.VISIBLE);
        replyLiveLogOverlay.bringToFront();
    }

    @Override public void exitReplyFocusMode() {
        replyLiveLogOverlay.setVisibility(View.GONE);
        setFacebookFullscreen(false);
    }

    @Override public void updateReplyLiveLog(String message) {
        if (replyLiveLog != null) replyLiveLog.setText(message == null ? "" : message);
    }

    void setFacebookFullscreen(boolean full) {
        fullscreenWeb=full;
        if(full){
            controlScroll.setVisibility(View.GONE); resizeHandle.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp=webContainer.getLayoutParams(); lp.height=ViewGroup.LayoutParams.MATCH_PARENT;
            if(lp instanceof LinearLayout.LayoutParams)((LinearLayout.LayoutParams)lp).weight=1;
            webContainer.setLayoutParams(lp); fullscreenButton.setVisibility(View.VISIBLE);
        } else {
            controlScroll.setVisibility(View.VISIBLE); resizeHandle.setVisibility(View.VISIBLE); fullscreenButton.setVisibility(View.GONE);
            int saved=prefs.getInt("webHeight",0);
            if(saved>0) applyWebHeight(saved); else {
                ViewGroup.LayoutParams lp=webContainer.getLayoutParams(); lp.height=0;
                if(lp instanceof LinearLayout.LayoutParams)((LinearLayout.LayoutParams)lp).weight=1.3f;
                webContainer.setLayoutParams(lp);
            }
        }
        web.requestLayout();
    }

    @Override public void onBackPressed() {
        if(fullscreenWeb){
            if(replyController!=null && replyController.running){ replyController.stopRun("تم إيقاف الردود والعودة إلى الواجهة الرئيسية"); }
            else { replyLiveLogOverlay.setVisibility(View.GONE); setFacebookFullscreen(false); }
            return;
        }
        if(web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if(replyController!=null) replyController.stopRun(null);
        if(likerController!=null) likerController.stopRun(null);
        if(inviterController!=null) inviterController.stop();
        if(web!=null) web.destroy();
        super.onDestroy();
    }
}
