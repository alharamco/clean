package com.alharam.answer;
import com.alharam.answer.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.content.SharedPreferences;
import org.json.JSONArray;
import android.widget.TextView;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.MotionEvent;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.json.JSONTokener;

class ReplyController {
    interface Host { void expandFacebook(String reason); void restoreFacebook(); void toggleFacebook(); void enterReplyFocusMode(); void exitReplyFocusMode(); void updateReplyLiveLog(String message); }
    final Activity activity; final View panel; final WebView web; final Host host;
    TextView status, log, progress; EditText replyText, replyRules, replyFallback, replyDm, replyDmMax; CheckBox replyAll, replyDmEnable; String currentRules="", currentFallback=""; StepperView sharedCount, sharedDelay;
    Button startReply, stopReply, openPost;
    Handler handler = new Handler(); Runnable nextTask; boolean running=false;
    int completed=0,target=0,candidateIndex=0,scrollSearchAttempts=0;
    final Set<String> processedCandidateKeys=new HashSet<>(); String currentCandidateKey="";
    long intervalMs=10000; String currentCommentText=""; String currentReplyText="", currentCandidateLabel="";
    boolean captchaExpanded=false;

    @SuppressLint("SetJavaScriptEnabled") ReplyController(Activity a, View p, WebView w, Host h) {
        activity=a; panel=p; web=w; host=h;
        status=p.findViewById(R.id.reply_status); log=p.findViewById(R.id.reply_log); progress=p.findViewById(R.id.reply_progress);
        replyText=p.findViewById(R.id.replyText); replyRules=p.findViewById(R.id.replyRules); replyFallback=p.findViewById(R.id.replyFallback); replyAll=p.findViewById(R.id.replyAll); replyDm=p.findViewById(R.id.replyDm); replyDmEnable=p.findViewById(R.id.replyDmEnable); replyDmMax=p.findViewById(R.id.replyDmMax); loadReplySettings(); startReply=p.findViewById(R.id.reply_start); stopReply=p.findViewById(R.id.reply_stop);
        sharedCount=a.findViewById(R.id.shared_count); sharedDelay=a.findViewById(R.id.shared_delay);
        log.setMovementMethod(new ScrollingMovementMethod());
        startReply.setOnClickListener(v->beginReplyRun()); stopReply.setOnClickListener(v->stopRun("تم إيقاف الردود"));
        updateProgress(); startCaptchaWatcher();
    }

    void toggleExpandedWeb() { host.toggleFacebook(); }

    void expandWebFullScreen(String reason) { host.expandFacebook(reason); }

    void restoreNormalLayout() { host.restoreFacebook(); }

    void startCaptchaWatcher() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (web != null) {
                    web.evaluateJavascript("(()=>{const s=((document.body&&document.body.innerText)||'').toLowerCase(); const e=[...document.querySelectorAll('iframe,[role=dialog],div')].some(x=>{const t=((x.innerText||'')+' '+(x.getAttribute('title')||'')+' '+(x.getAttribute('aria-label')||'')).toLowerCase(); return /captcha|recaptcha|security check|security verification|verify you are human|verification|تحقق|التحقق|تحقق أمني|اختبار أمان|تأكيد أنك لست روبوت|أنا لست روبوت/.test(t) && x.getBoundingClientRect().width>120 && x.getBoundingClientRect().height>80}); return JSON.stringify({captcha:e||/captcha|recaptcha|security check|تحقق|اختبار أمان/.test(s)});})()", value -> {
                        String r = unquote(value);
                        if (!captchaExpanded && r.contains("\"captcha\":true")) {
                            captchaExpanded = true;
                            expandWebFullScreen("تم اكتشاف نافذة تحقق/كابتشا — تم تكبير Facebook بالكامل");
                            appendLog("تم اكتشاف الكابتشا/التحقق — تم تكبير صفحة Facebook بالكامل لتسهيل الضغط على الزر السفلي");
                        }
                    });
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    void loadReplySettings() {
        SharedPreferences sp = activity.getSharedPreferences("hub_settings", 0);
        replyText.setText(sp.getString("reply_general", ""));
        replyRules.setText(sp.getString("reply_rules", ""));
        replyFallback.setText(sp.getString("reply_fallback", ""));
        replyAll.setChecked(sp.getBoolean("reply_all", true));
        replyDm.setText(sp.getString("reply_dm", ""));
        replyDmEnable.setChecked(sp.getBoolean("reply_dm_on", false));
        replyDmMax.setText(String.valueOf(sp.getInt("reply_dm_max", 20)));
    }

    void saveReplySettings() {
        activity.getSharedPreferences("hub_settings", 0).edit()
            .putString("reply_general", replyText.getText().toString())
            .putString("reply_rules", replyRules.getText().toString())
            .putString("reply_fallback", replyFallback.getText().toString())
            .putString("reply_dm", replyDm.getText().toString())
            .putBoolean("reply_dm_on", replyDmEnable.isChecked())
            .putInt("reply_dm_max", dmMax)
            .putBoolean("reply_all", replyAll.isChecked()).apply();
    }

    void beginReplyRun() {
        currentReplyText = replyText.getText().toString().trim();
        currentRules = replyRules.getText().toString().trim();
        currentFallback = replyFallback.getText().toString().trim();
        if (currentReplyText.length() == 0 && currentRules.length() == 0 && currentFallback.length() == 0) { setStatus("اكتب رداً واحداً على الأقل (عام أو بالكلمات أو للعملاء غير المطابقين)"); return; }
        currentDmText = replyDm.getText().toString().trim();
        dmEnabled = replyDmEnable.isChecked() && currentDmText.length() > 0;
        try { dmMax = Math.max(1, Integer.parseInt(replyDmMax.getText().toString().trim())); } catch (Exception e) { dmMax = 20; }
        if (replyDmEnable.isChecked() && currentDmText.length() == 0) appendLog("الرسائل الخاصة مفعّلة لكن نصها فارغ — لن تُرسل رسائل خاصة");
        saveReplySettings();
        try { intervalMs = Math.max(1000L, sharedDelay.getNumericValue() * 1000L); } catch (Exception e) { intervalMs = 10000L; }
        try { target = Math.max(1, sharedCount.getNumericValue()); } catch (Exception e) { target = 10; }
        if (replyAll.isChecked()) target = Integer.MAX_VALUE;
        stopRun(null);
        MainActivity ma=(MainActivity)activity; ma.saveSharedSettings();
        String selected=ma.sharedStartTime();
        if(selected==null){ setStatus("اختر الدقائق والساعات والفترة معًا، أو اترك وقت البدء فارغًا"); return; }
        Runnable starter=this::startReplyNow;
        if(!selected.isEmpty()){ long wait=ma.sharedStartDelayMs(); setStatus("⏰ مجدول: "+selected); appendLog("تمت جدولة الردود الساعة "+selected+"."); handler.postDelayed(starter,wait); }
        else starter.run();
    }

    void startReplyNow() {
        running=true; completed=0; candidateIndex=0; scrollSearchAttempts=0; processedCandidateKeys.clear(); pendingDm=false; dmNavigated=false; dmSent=0; dmLimitLogged=false; unchangedStreak=0; dmDoneAuthors.clear();
        currentCandidateKey=""; currentCandidateLabel=""; updateProgress();
        host.enterReplyFocusMode();
        appendLog("بدأ التشغيل — المطلوب: "+(target == Integer.MAX_VALUE ? "كل التعليقات" : String.valueOf(target))+" — الفاصل: "+(intervalMs/1000)+" ثانية"+(dmEnabled ? " — الرسائل الخاصة: مفعّلة (حد "+dmMax+")" : ""));
        // لا نعيد الواجهة للحجم الطبيعي هنا؛ Facebook يبقى مكبرًا أثناء البحث والتمرير والرد.
        scheduleNext(900);
    }

    void scheduleNext(long delay) {
        if (!running) return;
        nextTask = new Runnable() {
            @Override public void run() { processNextComment(); }
        };
        handler.postDelayed(nextTask, delay);
    }

    void processNextComment() {
        if (!running) return;
        // Keep the source project's proven reply-navigation flow intact. Sending Escape
        // on every iteration can dismiss Facebook's active reply/comment UI unexpectedly.
        if (completed >= target) {
            stopRun("اكتمل العدد المطلوب من الردود");
            return;
        }

        setStatus("جاري البحث عن الكومنت التالي — محاولة التمرير " + (scrollSearchAttempts + 1));
        String js = findAndOpenReplyJs();
        web.evaluateJavascript(js, value -> {
            String result = unquote(value);
            try {
                JSONObject o = new JSONObject(result);
                if (!o.optBoolean("ok", false)) {
                    if (scrollSearchAttempts < maxScrollAttempts()) {
                        scrollSearchAttempts++;
                        handler.postDelayed(() -> {
                            if (!running) return;
                            final String beforeJs = getCommentsScrollSignatureJs();
                            web.evaluateJavascript(beforeJs, beforeValue -> {
                                if (!running) return;
                                String before = unquote(beforeValue);
                                web.evaluateJavascript(scrollCommentsListJs(), ignored -> {});
                                handler.postDelayed(() -> {
                                    if (!running) return;
                                    web.evaluateJavascript(getCommentsScrollSignatureJs(), afterValue -> {
                                        String after = unquote(afterValue);
                                        if (before.equals(after) && ++unchangedStreak >= 3) {
                                            stopRun("تم الوصول إلى آخر التعليقات — تم إيقاف التشغيل (عدد الردود: " + completed + ")");
                                        } else {
                                            if (!before.equals(after)) unchangedStreak = 0;
                                            handler.postDelayed(() -> processNextComment(), 500);
                                        }
                                    });
                                }, 1000);
                            });
                        }, 500);
                    } else {
                        stopRun("توقف: تم تمرير قائمة التعليقات عدة مرات ولم يظهر Reply جديد");
                    }
                    return;
                }

                scrollSearchAttempts = 0; unchangedStreak = 0;
                currentCandidateKey = o.optString("key", "");
                currentCandidateLabel = o.optString("author", "");
                currentCommentText = o.optString("text", "");
                { String t = currentCommentText.length() > 40 ? currentCommentText.substring(0, 40) + "…" : currentCommentText;
                  appendLog("وُجد تعليق: " + currentCandidateLabel + " | " + t); }
                double xCss = o.optDouble("x", -1);
                double yCss = o.optDouble("y", -1);
                if (xCss < 0 || yCss < 0) {
                    handleCandidateFailure("تم العثور على Reply لكن لم يتم الحصول على إحداثياته");
                    return;
                }

                handler.postDelayed(() -> {
                    if (!running) return;
                    boolean tapped = nativeTapWebView(xCss, yCss);
                    if (!tapped) {
                        handleCandidateFailure("تعذر الضغط على Reply");
                        return;
                    }
                    setStatus("تم الضغط فعليًا على Reply — انتظار خانة الكتابة");
                    handler.postDelayed(() -> fillAndSendReply(), 2300);
                }, 350);
            } catch (Exception e) {
                appendLog("تعذر قراءة نتيجة البحث: " + e.getMessage());
                if (scrollSearchAttempts < maxScrollAttempts()) {
                    scrollSearchAttempts++;
                    handler.postDelayed(() -> {
                        if (!running) return;
                        final String beforeJs = getCommentsScrollSignatureJs();
                        web.evaluateJavascript(beforeJs, beforeValue -> {
                            if (!running) return;
                            String before = unquote(beforeValue);
                            web.evaluateJavascript(scrollCommentsListJs(), ignored -> {});
                            handler.postDelayed(() -> {
                                if (!running) return;
                                web.evaluateJavascript(getCommentsScrollSignatureJs(), afterValue -> {
                                    String after = unquote(afterValue);
                                    if (before.equals(after) && ++unchangedStreak >= 3) {
                                            stopRun("تم الوصول إلى آخر التعليقات — تم إيقاف التشغيل (عدد الردود: " + completed + ")");
                                        } else {
                                            if (!before.equals(after)) unchangedStreak = 0;
                                            handler.postDelayed(() -> processNextComment(), 500);
                                        }
                                });
                            }, 1500);
                        });
                    }, 500);
                } else {
                    stopRun("توقف بسبب نتيجة غير مفهومة بعد عدة محاولات");
                }
            }
        });
    }

    // الاختيارات الثلاثة:
    //  1) الرد العام: يُرسل لكل العملاء إذا لم تُكتب ردود بالكلمات.
    //  2) الردود بالكلمات: كل سطر  كلمة1،كلمة2 => نص الرد
    //  3) الرد لغير المطابقين: يُرسل لمن لم تنطبق عليه أي كلمة.
    // ويمكن استخدام {الاسم} لإدراج الاسم الأول لصاحب التعليق.
    String buildReplyFor(String author, String commentText) {
        String first = "";
        if (author != null) { String a = author.trim(); if (a.length() > 0) first = a.split("\\s+")[0]; }
        String comment = commentText == null ? "" : commentText.toLowerCase();
        String matched = null;
        for (String raw : currentRules.split("\\r?\\n")) {
            String line = raw.trim();
            int idx = line.indexOf("=>");
            if (idx <= 0) continue;
            String reply = line.substring(idx + 2).trim();
            if (reply.length() == 0) continue;
            for (String k : line.substring(0, idx).split("[,،|]")) {
                String key = k.trim().toLowerCase();
                if (key.length() > 0 && comment.contains(key)) { matched = reply; break; }
            }
            if (matched != null) break;
        }
        String out;
        if (matched != null) out = matched;
        else if (currentRules.length() > 0) out = currentFallback.length() > 0 ? currentFallback : currentReplyText;
        else out = currentReplyText.length() > 0 ? currentReplyText : currentFallback;
        return fillName(out, first);
    }

    static String fillName(String t, String first) {
        return t.replace("{الاسم}", first).replace("{name}", first).replaceAll("\\s+", " ").trim();
    }

    // أجزاء من ردودنا نفسها، حتى لا يرد التطبيق على ردوده السابقة.
    String ownFragmentsJson() {
        JSONArray arr = new JSONArray();
        java.util.List<String> templates = new java.util.ArrayList<>();
        templates.add(currentReplyText); templates.add(currentFallback);
        for (String raw : currentRules.split("\\r?\\n")) { int i = raw.indexOf("=>"); if (i > 0) templates.add(raw.substring(i + 2)); }
        for (String t : templates) {
            for (String seg : t.replace("{name}", "@@@").replace("{الاسم}", "@@@").split("@@@")) {
                String c = seg.replaceAll("\\s+", " ").trim().toLowerCase();
                if (c.length() >= 8) arr.put(c.length() > 40 ? c.substring(0, 40) : c);
            }
        }
        return arr.toString();
    }

    void fillAndSendReply() {
        if (!running) return;
        final String personalReply = buildReplyFor(currentCandidateLabel, currentCommentText);
        if (personalReply.length() == 0) { handleCandidateFailure("لا يوجد رد مناسب لهذا التعليق (لم تتطابق أي كلمة ولا يوجد رد افتراضي)"); return; }
        final String js = fillAndSendJs(personalReply);
        web.evaluateJavascript(js, value -> {
            String result = unquote(value);
            try {
                JSONObject o = new JSONObject(result);
                if (o.optBoolean("ok", false)) {
                    if (currentCandidateKey != null && currentCandidateKey.length() > 0) {
                        processedCandidateKeys.add(currentCandidateKey);
                    }
                    completed++;
                    pendingDm = true;
                    candidateIndex = completed;
                    updateProgress();
                    if (completed >= target) {
                        stopRun("اكتمل العدد المطلوب من الردود");
                    } else {
                        setStatus("تم الرد رقم " + completed + " — انتظار الفاصل الزمني");
                        appendLog("تم الرد بنجاح على " + (currentCandidateLabel.length() > 0 ? currentCandidateLabel : "التعليق الحالي") + " — رقم " + completed);
                        handler.postDelayed(() -> {
                            if (!running) return;
                            goBackFromReplies();
                        }, intervalMs);
                    }
                } else {
                    handleCandidateFailure(o.optString("message", "تعذر إدخال أو إرسال الرد"));
                }
            } catch (Exception e) {
                appendLog("تعذر قراءة نتيجة إرسال الرد");
                stopRun("توقف بسبب نتيجة غير مفهومة");
            }
        });
    }


    void handleCandidateFailure(String reason) {
        if (!running) return;
        appendLog("تم تخطي " + (currentCandidateLabel.length() > 0 ? currentCandidateLabel : "التعليق الحالي") + " — السبب: " + reason);
        if (currentCandidateKey != null && currentCandidateKey.length() > 0) processedCandidateKeys.add(currentCandidateKey);
        candidateIndex++;
        handler.postDelayed(() -> {
            if (!running) return;
            web.evaluateJavascript(findRepliesBackJs(), value -> {
                String result = unquote(value);
                try {
                    JSONObject o = new JSONObject(result);
                    if (o.optBoolean("ok", false)) {
                        double x = o.optDouble("x", -1);
                        double y = o.optDouble("y", -1);
                        boolean tapped = nativeTapWebView(x, y);
                        if (tapped) {
                            handler.postDelayed(() -> {
                                if (!running) return;
                                scrollSearchAttempts = 0;
                                processNextComment();
                            }, 1400);
                        } else {
                            handler.postDelayed(() -> processNextComment(), 800);
                        }
                    } else {
                        scheduleNext(500);
                    }
                } catch (Exception e) {
                    scheduleNext(500);
                }
            });
        }, 500);
    }


    void goBackFromReplies() {
        if (!running) return;
        setStatus("جاري الرجوع من الردود ثم إعادة وضع قائمة التعليقات عند آخر عميل تم الرد عليه");
        web.evaluateJavascript(findRepliesBackJs(), value -> {
            String result = unquote(value);
            if (result == null || result.trim().length() == 0 || "null".equals(result.trim())) {
                handler.postDelayed(() -> retryGoBackFromReplies(), 400);
                return;
            }
            try {
                JSONObject o = new JSONObject(result);
                if (!o.optBoolean("ok", false)) {
                    handler.postDelayed(() -> retryGoBackFromReplies(), 400);
                    return;
                }
                double xCss = o.optDouble("x", -1);
                double yCss = o.optDouble("y", -1);
                if (!nativeTapWebView(xCss, yCss)) {
                    handler.postDelayed(() -> retryGoBackFromReplies(), 400);
                    return;
                }
                handler.postDelayed(() -> {
                    if (!running) return;
                    dismissFacebookTransientUi();
                    prioritizeCommentList();
                    // مهم: لا نبدأ البحث من أعلى القائمة. نعيد موضع القائمة إلى آخر تعليق عولج.
                    handler.postDelayed(() -> restoreCommentPositionAndContinue(), 1800);
                }, 1600);
            } catch (Exception e) {
                handler.postDelayed(() -> retryGoBackFromReplies(), 400);
            }
        });
    }

    void retryGoBackFromReplies() {
        if (!running) return;
        web.evaluateJavascript(findRepliesBackJs(), value -> {
            String result = unquote(value);
            if (result == null || result.trim().length() == 0 || "null".equals(result.trim())) {
                handler.postDelayed(() -> {
                    if (!running) return;
                    if (web.canGoBack()) {
                        web.goBack();
                        handler.postDelayed(() -> restoreCommentPositionAndContinue(), 1800);
                    } else {
                        stopRun("توقف: تعذر قراءة سهم الرجوع ولا توجد صفحة سابقة للرجوع إليها");
                    }
                }, 400);
                return;
            }
            try {
                JSONObject o = new JSONObject(result);
                if (!o.optBoolean("ok", false)) {
                    if (web.canGoBack()) {
                        web.goBack();
                        handler.postDelayed(() -> restoreCommentPositionAndContinue(), 1800);
                    } else {
                        stopRun("توقف: لم يتم العثور على طريقة آمنة للرجوع إلى قائمة التعليقات");
                    }
                    return;
                }
                double x = o.optDouble("x", -1);
                double y = o.optDouble("y", -1);
                if (!nativeTapWebView(x, y)) {
                    stopRun("توقف: تعذر الضغط على سهم الرجوع بعد إعادة المحاولة");
                    return;
                }
                handler.postDelayed(() -> {
                    if (!running) return;
                    dismissFacebookTransientUi();
                    prioritizeCommentList();
                    handler.postDelayed(() -> restoreCommentPositionAndContinue(), 1800);
                }, 1400);
            } catch (Exception e) {
                handler.postDelayed(() -> retryGoBackFromReplies(), 400);
            }
        });
    }

    void restoreCommentPositionAndContinue() {
        if (!running) return;
        final String key = currentCandidateKey == null ? "" : currentCandidateKey;
        final String js = restoreCommentPositionJs(key);
        web.evaluateJavascript(js, value -> {
            if (!running) return;
            String result = unquote(value);
            try {
                JSONObject o = new JSONObject(result);
                boolean positioned = o.optBoolean("positioned", false);
                setStatus(positioned
                        ? "تم الرجوع عند آخر عميل تم الرد عليه — جاري اختيار العميل التالي"
                        : "لم يتم تحديد آخر عميل بدقة — جاري تثبيت قائمة التعليقات ثم البحث");
                // ننتظر حتى تستقر حركة القائمة قبل لمس أي Reply.
                handler.postDelayed(() -> {
                    if (!running) return;
                    afterListRestored();
                }, positioned ? 900 : 1300);
            } catch (Exception e) {
                handler.postDelayed(() -> afterListRestored(), 1200);
            }
        });
    }

    String findRepliesBackJs() {
        return "(()=>{const norm=s=>(s||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
        "const visible=e=>{if(!e)return false;const r=e.getBoundingClientRect(),st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden'&&r.bottom>0&&r.top<innerHeight};" +
        "const clickable=e=>visible(e)&&((e.matches('button,[role=button],[role=link],a'))||e.hasAttribute('aria-label')||e.hasAttribute('title')||e.hasAttribute('tabindex'));" +
        "const backWords=['back','go back','back to comments','return','return to comments','close replies','رجوع','رجوع للخلف','عودة','عودة للخلف','العودة','الرجوع','عودة للتعليقات','العودة للتعليقات','إغلاق الردود','اغلاق الردود'];const isBack=e=>{const a=norm(e.getAttribute('aria-label')),t=norm(e.getAttribute('title')),txt=norm(e.innerText);return backWords.some(x=>a===x||t===x||txt===x||a.includes(x)||t.includes(x)||txt.includes(x));};" +
        "const replyWords=['reply','replies','reply to','responses','response','رد','الرد','ردود','الردود','الرد على','استجابات','استجابة'];const isReplies=e=>{const t=norm(e.innerText),a=norm(e.getAttribute('aria-label')),tt=norm(e.getAttribute('title'));return replyWords.some(x=>t===x||a===x||tt===x||t.includes(x)||a.includes(x)||tt.includes(x));};" +
        "const headers=[...document.querySelectorAll('div,span,h1,h2,h3,[role=heading]')].filter(visible).filter(isReplies);" +
        "let best=null,bestScore=-1e9;" +
        "for(const header of headers){let p=header;for(let level=0;level<7&&p;level++,p=p.parentElement){const hr=header.getBoundingClientRect();const buttons=[...p.querySelectorAll('button,[role=button],[role=link],a,[aria-label],[title],div[tabindex]')].filter(clickable);" +
        "for(const e of buttons){const r=e.getBoundingClientRect();if(r.width>70||r.height>70)continue;let score=0;const a=norm(e.getAttribute('aria-label')),t=norm(e.getAttribute('title')),txt=norm(e.innerText);" +
        "if(isBack(e))score+=1000;if(backWords.some(x=>a.includes(x)||t.includes(x)||txt.includes(x)))score+=500;" +
        "const leftOfHeader=r.left+r.width<=hr.left+12&&r.top<=hr.bottom+90;const explicitBack=isBack(e);if(!explicitBack&&!leftOfHeader)continue;" +
        "if(r.top<Math.max(180,hr.bottom+90))score+=120;if(leftOfHeader)score+=350;if(r.left<innerWidth*0.25)score+=60;" +
        "if(txt.includes('emoji')||txt.includes('smile')||txt.includes('emoticon')||txt.includes('sticker')||txt.includes('gif')||a.includes('emoji')||a.includes('smile')||a.includes('sticker')||a.includes('gif'))score-=1500;" +
        "if(score>bestScore){bestScore=score;best=e;}}}" +
        "if(!best){const backNodes=[...document.querySelectorAll('button,[role=button],[role=link],a,[aria-label],[title],div[tabindex]')].filter(clickable).filter(isBack);" +
        "for(const e of backNodes){const r=e.getBoundingClientRect();let score=0;if(r.top<180)score+=200;if(r.left<innerWidth*0.25)score+=100;if(r.width<=70&&r.height<=70)score+=50;if(score>bestScore){bestScore=score;best=e;}}}" +
        "if(!best)return JSON.stringify({ok:false,message:'لم يتم العثور على سهم الرجوع الخاص بشاشة Replies'});" +
        "const r=best.getBoundingClientRect();return JSON.stringify({ok:true,message:'تم تحديد سهم الرجوع الخاص بشاشة Replies فقط',x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height});})()";
    }

    boolean nativeTapWebView(double xCss, double yCss) {
        try {
            float scale = web.getScale();
            float x = (float) (xCss * scale);
            float y = (float) (yCss * scale);

            if (x < 0 || y < 0 || x > web.getWidth() || y > web.getHeight()) {
                    return false;
            }

            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            boolean downResult = web.dispatchTouchEvent(down);
            down.recycle();

            SystemClock.sleep(70);

            long upTime = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0);
            boolean upResult = web.dispatchTouchEvent(up);
            up.recycle();

            return downResult || upResult;
        } catch (Exception e) {
            appendLog("خطأ أثناء الضغط الفعلي على Reply: " + e.getMessage());
            return false;
        }
    }

    // ===== دوال JavaScript مشتركة =====
    // الفكرة: نبحث عن أزرار Reply الحقيقية (نص أو وصف مطابق تماماً)، ثم نعتبر "التعليق" هو أكبر حاوية
    // تحتوي زر Reply واحداً فقط. اسم العميل من أول رابط داخل الحاوية (أو أول سطر)، ولا نعتمد على أي
    // روابط خارج التعليق (مثل شريط "استخدام تطبيق فيسبوك").
    static final String COMMON_JS =
        "const norm=s=>(s||'').replace(/\\s+/g,' ').trim();" +
        "const strip=s=>norm(s).toLowerCase().replace(/[0-9٠-٩]+/g,'').replace(/[^\\p{L}]+/gu,' ').replace(/\\s+/g,' ').trim();" +
        "const replyExact=['reply','رد','الرد','ردّ','الردّ'];" +
        "const isReply=e=>{const t=norm(e.innerText).toLowerCase(),a=norm(e.getAttribute('aria-label')).toLowerCase();return replyExact.includes(t)||replyExact.includes(a)||/^(reply to|الرد على|رد على)/.test(a);};" +
        "const inBanner=e=>!!e.closest('[role=banner],header,[role=navigation]');" +
        "const clickable=e=>!!e.closest('a,button,[role=button],[role=link],[tabindex]')||getComputedStyle(e).cursor==='pointer';" +
        "const collect=()=>{const raw=[...document.querySelectorAll('button,[role=button],[role=link],a,[aria-label],div[tabindex],span')].filter(e=>isReply(e)&&!inBanner(e)&&clickable(e));return raw.filter(e=>!raw.some(o=>o!==e&&o.contains(e)));};" +
        "const containerOf=(reply,all)=>{let best=reply.parentElement||reply,p=best;while(p&&p.parentElement){const up=p.parentElement;if(up===document.body||up===document.documentElement)break;const n=all.filter(r=>up.contains(r)).length;if(n>1||norm(up.innerText).length>500||up.getBoundingClientRect().height>650)break;p=up;best=up;}return best;};" +
        "const actionRe=/^(like|reply|share|edit|react|translate|see translation|top fan|author|admin|just now|view .*|[0-9]+ repl.*|أعجبني|إعجاب|رد|الرد|مشاركة|تعديل|ترجمة|عرض .*|معجب.*|مشرف|المسؤول|الآن)$/i;" +
        "const timeRe=/^[0-9٠-٩]+\\s*\\S{0,8}$/;" +
        "const info=(reply,all)=>{const c=containerOf(reply,all);const lines=(c.innerText||'').split('\\n').map(norm).filter(x=>x.length);if(!lines.length)return null;" +
        "const al=[...c.querySelectorAll('a[href],[role=link]')].find(a=>{const t=norm(a.innerText);return t.length>1&&t.length<60&&!actionRe.test(t)&&!timeRe.test(t)&&!isReply(a);});" +
        "const author=(al?norm(al.innerText):lines[0]).slice(0,60);" +
        "const body=lines.filter(l=>l!==author&&!actionRe.test(l)&&!timeRe.test(l)).join(' ').replace(/(?:\\s+(?:like|reply|أعجبني|إعجاب|الرد|رد))+(?:\\s+[0-9٠-٩]+\\s*\\S{0,6})?\\s*$/i,'').slice(0,400);" +
        "return {container:c,authorEl:al||null,author:author,text:body,key:strip(author)+'|'+strip(body).slice(0,60)};};";

    String findAndOpenReplyJs() {
        JSONArray done = new JSONArray();
        for (String k : processedCandidateKeys) done.put(k);
        String qDone = JSONObject.quote(done.toString());
        String qOwn = JSONObject.quote(ownFragmentsJson());
        return "(()=>{const doneSet=new Set(JSON.parse(" + qDone + "));const own=JSON.parse(" + qOwn + ");" + COMMON_JS +
        "const all=collect();all.sort((a,b)=>{const ra=a.getBoundingClientRect(),rb=b.getBoundingClientRect();return ra.top-rb.top||ra.left-rb.left});" +
        "for(const reply of all){const i=info(reply,all);if(!i||i.key.length<3||doneSet.has(i.key))continue;const low=(i.author+' '+i.text).toLowerCase();if(own.some(f=>low.includes(f)))continue;" +
        "reply.scrollIntoView({block:'center',inline:'center',behavior:'auto'});const rr=reply.getBoundingClientRect();return JSON.stringify({ok:true,message:'تم العثور على Reply جديد',x:rr.left+rr.width/2,y:rr.top+rr.height/2,w:rr.width,h:rr.height,key:i.key,author:i.author,text:i.text});}" +
        "return JSON.stringify({ok:false,message:'لم يظهر Reply جديد في قائمة التعليقات الحالية',visibleReplies:all.length});})()";
    }

    String restoreCommentPositionJs(String lastKey) {
        String qKey = JSONObject.quote(lastKey == null ? "" : lastKey);
        return "(()=>{const target=" + qKey + ";" + COMMON_JS +
        "const all=collect();let match=null;for(const reply of all){const i=info(reply,all);if(i&&target&&i.key===target){match=reply;break;}}" +
        "if(!match)return JSON.stringify({positioned:false,reason:'لم يتم العثور على آخر تعليق تم الرد عليه في DOM الحالي'});" +
        "let sc=null;let p=match;for(let i=0;i<14&&p;i++,p=p.parentElement){const cs=getComputedStyle(p);if(p.scrollHeight>p.clientHeight+30&&/(auto|scroll)/.test(cs.overflowY)){sc=p;break;}}" +
        "if(!sc){p=match;for(let i=0;i<14&&p;i++,p=p.parentElement){if(p.scrollHeight>p.clientHeight+30){sc=p;break;}}}" +
        "if(sc){const r=match.getBoundingClientRect(),sr=sc.getBoundingClientRect();const delta=(r.top-sr.top)-(sr.height*0.22);sc.scrollTop=Math.max(0,Math.min(sc.scrollHeight-sc.clientHeight,sc.scrollTop+delta));sc.dispatchEvent(new Event('scroll',{bubbles:true}));}else{match.scrollIntoView({block:'start',inline:'nearest',behavior:'auto'});}" +
        "return JSON.stringify({positioned:true});})()";
    }

    // اسم العميل داخل تعليقه (للضغط عليه وفتح صفحته)
    String findAuthorLinkJs(String key) {
        String qKey = JSONObject.quote(key == null ? "" : key);
        return "(()=>{const target=" + qKey + ";" + COMMON_JS +
        "const all=collect();let hit=null;for(const r of all){const i=info(r,all);if(i&&i.key===target){hit=i;break;}}" +
        "if(!hit||!hit.authorEl)return JSON.stringify({ok:false,message:'لم يتم العثور على رابط اسم العميل'});" +
        "hit.authorEl.scrollIntoView({block:'center',inline:'center',behavior:'auto'});const r=hit.authorEl.getBoundingClientRect();" +
        "return JSON.stringify({ok:true,x:r.left+r.width/2,y:r.top+r.height/2,url:location.href});})()";
    }

    // زر المراسلة في صفحة العميل (عربي وإنجليزي)، مع تجاهل شريط فيسبوك العلوي حتى لا نفتح صندوق رسائلك.
    String findMessageButtonJs() {
        return "(()=>{const norm=s=>(s||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
        "const visible=e=>{const r=e.getBoundingClientRect(),st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden';};" +
        "const words=['message','send message','send a message','new message','message us','مراسلة','رسالة','رسالة جديدة','إرسال رسالة','ارسال رسالة','راسلني','مراسلته','مراسلتها'];" +
        "const bad=e=>!!e.closest('[role=banner],header,[role=navigation],[role=complementary]');" +
        "const els=[...document.querySelectorAll('a,button,[role=button],[role=link],div[tabindex],span')].filter(e=>visible(e)&&!bad(e));" +
        "let best=null,score=-1;for(const e of els){const t=norm(e.innerText),a=norm(e.getAttribute('aria-label'));let s=0;if(words.includes(t))s=3;else if(words.includes(a))s=2;else continue;" +
        "if(e.tagName==='SPAN'&&!e.closest('a,button,[role=button],[role=link],[tabindex]'))continue;if(s>score){score=s;best=e;}}" +
        "if(!best)return JSON.stringify({ok:false,message:'لا يوجد زر مراسلة'});" +
        "best.scrollIntoView({block:'center',inline:'center',behavior:'auto'});const r=best.getBoundingClientRect();" +
        "return JSON.stringify({ok:true,x:r.left+r.width/2,y:r.top+r.height/2,label:norm(best.innerText||best.getAttribute('aria-label'))});})()";
    }

    // كتابة الرسالة الخاصة وإرسالها (زر الإرسال أو Enter)
    String fillAndSendDmJs(String text) {
        String q = JSONObject.quote(text);
        return "(()=>{const text=" + q + ";const norm=s=>(s||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
        "const visible=e=>{const r=e.getBoundingClientRect(),st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden';};" +
        "const bad=e=>!!e.closest('[role=banner],header,[role=navigation]');" +
        "const boxes=[...document.querySelectorAll('textarea,[contenteditable=true],[role=textbox]')].filter(e=>visible(e)&&!bad(e));" +
        "const hint=e=>{const s=norm((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('data-placeholder')||''));return /message|رسالة|aa|اكتب/.test(s);};" +
        "let box=boxes.find(hint)||boxes[boxes.length-1];" +
        "if(!box)return JSON.stringify({ok:false,message:'لم تظهر خانة كتابة الرسالة'});" +
        "box.scrollIntoView({block:'center',inline:'center'});box.focus();" +
        "if(box.tagName==='TEXTAREA'){const p=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');if(p&&p.set)p.set.call(box,text);else box.value=text;}else{const r=document.createRange();r.selectNodeContents(box);r.deleteContents();r.insertNode(document.createTextNode(text));}" +
        "box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));box.dispatchEvent(new Event('change',{bubbles:true}));" +
        "const scope=box.closest('form,[role=dialog],[aria-modal=true]')||box.parentElement?.parentElement?.parentElement||document;" +
        "const send=[...scope.querySelectorAll('button,[role=button],[aria-label]')].filter(visible).find(e=>{const t=norm(e.innerText),a=norm(e.getAttribute('aria-label'));return ['send','إرسال','ارسال','press enter to send','send message'].some(x=>t===x||a===x);});" +
        "if(send){send.click();return JSON.stringify({ok:true,via:'button'});}" +
        "for(const t of ['keydown','keypress','keyup']){box.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}" +
        "return JSON.stringify({ok:true,via:'enter'});})()";
    }

    String dmStillTypedJs(String text) {
        String q = JSONObject.quote(text.length() > 12 ? text.substring(0, 12) : text);
        return "(()=>{const t=" + q + ";const b=[...document.querySelectorAll('textarea,[contenteditable=true],[role=textbox]')].find(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0;});" +
        "return JSON.stringify({still:!!b&&(((b.value||b.innerText||'')+'').includes(t))});})()";
    }

    String scrollCommentsListJs() {
        return "(()=>{const vis=e=>{if(!e)return false;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&r.bottom>0&&r.top<innerHeight};"+
        "const replies=['reply','replies','رد','الرد','ردود','الردود','reply to','رد على'];let best=null,bestScore=-1;"+
        "for(const e of document.querySelectorAll('button,[role=button],[role=link],a,[aria-label],div[tabindex]')){if(!vis(e))continue;const t=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();if(!replies.some(x=>t.includes(x)))continue;let p=e;for(let i=0;i<14&&p;i++,p=p.parentElement){if(p.scrollHeight>p.clientHeight+40){const r=p.getBoundingClientRect();let score=0;if(r.height>250)score+=500;if(r.width>250)score+=150;if(r.top<innerHeight&&r.bottom>0)score+=150;if(r.height>r.width*0.45)score+=100;if(score>bestScore){bestScore=score;best=p;}}}}"+
        "if(!best){for(const e of document.querySelectorAll('div,section,main,[role=dialog],[role=feed],[role=main]')){if(!vis(e)||e.scrollHeight<=e.clientHeight+40)continue;const r=e.getBoundingClientRect();let score=r.height+Math.min(r.width,500);if(r.top<innerHeight&&r.bottom>0&&score>bestScore){bestScore=score;best=e;}}}"+
        "if(!best){window.scrollBy({top:Math.round(innerHeight*0.6),behavior:'auto'});return JSON.stringify({ok:true,mode:'window'});}"+
        "const max=Math.max(0,best.scrollHeight-best.clientHeight);const step=Math.max(150,Math.min(best.clientHeight*0.6,700));const old=best.scrollTop;best.scrollTop=Math.min(max,old+step);best.dispatchEvent(new Event('scroll',{bubbles:true}));return JSON.stringify({ok:true,mode:'container',top:best.scrollTop,max:max,moved:best.scrollTop-old});})()";
    }

    String getCommentsScrollSignatureJs() {
        return "(()=>{const vis=e=>{if(!e)return false;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&r.bottom>0&&r.top<innerHeight};const words=['reply','replies','رد','الرد','ردود','الردود'];let best=null,bestScore=-1;for(const e of document.querySelectorAll('button,[role=button],[role=link],a,[aria-label],div[tabindex]')){if(!vis(e))continue;const t=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();if(!words.some(x=>t.includes(x)))continue;let p=e;for(let i=0;i<14&&p;i++,p=p.parentElement){if(p.scrollHeight>p.clientHeight+40){const r=p.getBoundingClientRect();const score=(r.height>250?500:0)+(r.width>250?150:0)+r.height;if(score>bestScore){bestScore=score;best=p;}}}}if(best)return Math.round(best.scrollTop)+'|'+Math.round(best.scrollHeight-best.clientHeight);return Math.round(window.scrollY||0)+'|window';})()";
    }

    void dismissFacebookTransientUi() {
        web.evaluateJavascript("(()=>{try{document.activeElement&&document.activeElement.blur();document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}));}catch(e){} return true;})()", null);
    }

    void prioritizeCommentList() {
        if (running) host.enterReplyFocusMode();
        else host.restoreFacebook();
    }

    boolean nativeSwipeComments() {
        try {
            int w = web.getWidth();
            int h = web.getHeight();
            if (w <= 0 || h <= 0) return false;
            float x = w * 0.50f;
            float startY = h * 0.60f;
            float endY = h * 0.40f;
            long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0);
            boolean downResult = web.dispatchTouchEvent(down);
            down.recycle();
            int steps = 14;
            for (int i = 1; i <= steps; i++) {
                long t = downTime + (i * 80L);
                float y = startY + (endY - startY) * (i / (float) steps);
                MotionEvent move = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE, x, y, 0);
                web.dispatchTouchEvent(move);
                move.recycle();
                SystemClock.sleep(80);
            }
            long upTime = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, endY, 0);
            boolean upResult = web.dispatchTouchEvent(up);
            up.recycle();
            return downResult || upResult;
        } catch (Exception e) {
            appendLog("خطأ أثناء Swipe التعليقات: " + e.getMessage());
            return false;
        }
    }

    String fillAndSendJs(String text) {
        String q = JSONObject.quote(text);
        return "(()=>{const text=" + q + ";" +
        "const norm=s=>(s||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
        "const visible=e=>{if(!e)return false;const r=e.getBoundingClientRect(),st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden'};" +
        "const isReplyHint=e=>{const s=norm((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('data-placeholder')||'')+' '+(e.innerText||''));return ['reply','replies','reply to','response','responses','respond','رد','الرد','ردود','الردود','الرد على','استجابة','استجابات','ردّ','الردّ'].some(x=>s.includes(x));};" +
        "let candidates=[...document.querySelectorAll('textarea,[contenteditable=true],[role=textbox]')].filter(visible);" +
        "let box=candidates.find(isReplyHint);" +
        "if(!box){const dialogs=[...document.querySelectorAll('[role=dialog],[aria-modal=true]')].filter(visible);for(const d of dialogs){const b=[...d.querySelectorAll('textarea,[contenteditable=true],[role=textbox]')].find(visible);if(b){box=b;break;}}}" +
        "if(!box){const active=document.activeElement;if(active&&(active.matches('textarea,[contenteditable=true],[role=textbox]'))&&visible(active))box=active;}" +
        "if(!box)return JSON.stringify({ok:false,message:'تم الضغط على Reply لكن لم يتم تحديد خانة الرد الخاصة بالكومنت'});" +
        "box.scrollIntoView({block:'center',inline:'center'});box.focus();" +
        "if(box.tagName==='TEXTAREA'){const p=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');if(p&&p.set)p.set.call(box,text);else box.value=text;}else{const r=document.createRange();r.selectNodeContents(box);r.deleteContents();r.insertNode(document.createTextNode(text));}" +
        "box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));" +
        "box.dispatchEvent(new Event('change',{bubbles:true}));" +
        "const parent=box.closest('[role=dialog],[aria-modal=true]')||box.parentElement?.parentElement?.parentElement||document;" +
        "const buttons=[...parent.querySelectorAll('button,[role=button],[aria-label]')].filter(visible);" +
        "const sendWords=['send','submit','post','publish','comment','send reply','send comment','إرسال','ارسال','إرسال الرد','ارسال الرد','إرسال التعليق','ارسال التعليق','نشر','نشر الرد','نشر التعليق','تعليق','رد'];" +
        "let send=buttons.find(e=>{const t=norm(e.innerText),a=norm(e.getAttribute('aria-label')),title=norm(e.getAttribute('title'));return sendWords.some(x=>t===x||a===x||title===x||t.includes(x)||a.includes(x)||title.includes(x))});" +
        "if(!send){send=buttons.find(e=>{const t=norm(e.innerText),a=norm(e.getAttribute('aria-label'));return (t==='send'||a==='send'||t==='إرسال'||a==='إرسال')});}" +
        "if(!send)return JSON.stringify({ok:false,message:'تمت كتابة الرد في خانة Reply الصحيحة لكن لم يتم العثور على زر الإرسال داخل نفس منطقة الرد'});" +
        "send.scrollIntoView({block:'center',inline:'center'});send.click();" +
        "return JSON.stringify({ok:true,message:'تمت كتابة الرد في خانة Reply والضغط على زر الإرسال'});})()";
    }

    // ===== الرسالة الخاصة بعد الرد العام =====
    boolean pendingDm = false, dmNavigated = false, dmEnabled = false, dmLimitLogged = false;
    int dmSent = 0, dmMax = 20, dmBackTries = 0, unchangedStreak = 0;
    String currentDmText = "", dmReturnUrl = "";
    final Set<String> dmDoneAuthors = new HashSet<>();
    final java.util.Random rnd = new java.util.Random();

    int maxScrollAttempts() { return replyAll != null && replyAll.isChecked() ? 80 : 12; }

    static String pathOnly(String u) {
        if (u == null) return "";
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    boolean shouldSendDm() { return dmEnabled && currentDmText.length() > 0 && dmSent < dmMax; }

    // بعد الرجوع من شاشة الردود: إما نبدأ الرسالة الخاصة للعميل الذي رددنا عليه، أو نكمل للتعليق التالي.
    void afterListRestored() {
        if (!running) return;
        if (pendingDm) {
            pendingDm = false;
            if (shouldSendDm()) { startDm(); return; }
            if (dmEnabled && dmSent >= dmMax && !dmLimitLogged) { dmLimitLogged = true; appendLog("بلغ حد الرسائل الخاصة (" + dmMax + ") — سيكمل الردود العامة فقط"); }
        }
        scrollSearchAttempts = 0;
        processNextComment();
    }

    void startDm() {
        final String author = currentCandidateLabel == null ? "" : currentCandidateLabel.trim();
        final String key = currentCandidateKey;
        if (author.length() == 0 || dmDoneAuthors.contains(author.toLowerCase())) {
            appendLog("تخطي الرسالة الخاصة — لا يوجد اسم للعميل أو سبقت مراسلته");
            dmAfter(500); return;
        }
        dmNavigated = false;
        setStatus("جاري فتح صفحة " + author + " للمراسلة");
        web.evaluateJavascript(findAuthorLinkJs(key), v -> {
            if (!running) return;
            try {
                final JSONObject o = new JSONObject(unquote(v));
                if (!o.optBoolean("ok", false)) { dmSkip(author, o.optString("message", "تعذر الوصول لاسم العميل")); return; }
                dmReturnUrl = o.optString("url", "");
                if (dmReturnUrl.length() == 0) { dmSkip(author, "تعذر قراءة رابط الصفحة الحالي"); return; }
                handler.postDelayed(() -> {
                    if (!running) return;
                    if (!nativeTapWebView(o.optDouble("x", -1), o.optDouble("y", -1))) { dmSkip(author, "تعذر الضغط على اسم العميل"); return; }
                    handler.postDelayed(() -> dmCheckProfile(author, 0), 3200);
                }, 400);
            } catch (Exception e) { dmSkip(author, "نتيجة غير مفهومة أثناء فتح صفحة العميل"); }
        });
    }

    // نتأكد أن الرابط تغيّر فعلاً (أي فُتحت صفحة العميل) قبل البحث عن زر المراسلة،
    // حتى لا نضغط بالخطأ على زر "إرسال رسالة" الخاص بصفحة المنشور نفسها.
    void dmCheckProfile(final String author, final int attempt) {
        if (!running) return;
        web.evaluateJavascript("location.href", v -> {
            if (!running) return;
            String url = unquote(v);
            if (pathOnly(url).equals(pathOnly(dmReturnUrl))) {
                if (attempt < 2) handler.postDelayed(() -> dmCheckProfile(author, attempt + 1), 1500);
                else dmSkip(author, "لم تفتح صفحة العميل");
                return;
            }
            dmNavigated = true;
            appendLog("فُتحت صفحة: " + url);
            dmFindMessage(author, 0);
        });
    }

    void dmFindMessage(final String author, final int attempt) {
        if (!running) return;
        web.evaluateJavascript(findMessageButtonJs(), v -> {
            if (!running) return;
            try {
                JSONObject o = new JSONObject(unquote(v));
                if (o.optBoolean("ok", false)) {
                    final double x = o.optDouble("x", -1), y = o.optDouble("y", -1);
                    appendLog("وُجد زر المراسلة: " + o.optString("label", ""));
                    handler.postDelayed(() -> {
                        if (!running) return;
                        if (!nativeTapWebView(x, y)) { dmSkip(author, "تعذر الضغط على زر المراسلة"); return; }
                        handler.postDelayed(() -> dmType(author, 0), 3500);
                    }, 400);
                    return;
                }
            } catch (Exception ignored) {}
            if (attempt < 2) handler.postDelayed(() -> dmFindMessage(author, attempt + 1), 1500);
            else dmSkip(author, "لا يوجد زر مراسلة في صفحته");
        });
    }

    void dmType(final String author, final int attempt) {
        if (!running) return;
        String first = author.trim().length() > 0 ? author.trim().split("\\s+")[0] : "";
        final String text = fillName(currentDmText, first);
        web.evaluateJavascript(fillAndSendDmJs(text), v -> {
            if (!running) return;
            try {
                JSONObject o = new JSONObject(unquote(v));
                if (o.optBoolean("ok", false)) { handler.postDelayed(() -> dmVerify(author, text), 1600); return; }
            } catch (Exception ignored) {}
            if (attempt < 3) handler.postDelayed(() -> dmType(author, attempt + 1), 1500);
            else dmSkip(author, "لم تظهر خانة الرسالة (قد يفتح فيسبوك تطبيق ماسنجر خارج التطبيق)");
        });
    }

    void dmVerify(final String author, final String text) {
        if (!running) return;
        web.evaluateJavascript(dmStillTypedJs(text), v -> {
            if (!running) return;
            boolean still = unquote(v).contains("\"still\":true");
            if (still) { dmSkip(author, "كُتبت الرسالة ولم تُرسل"); return; }
            dmSent++; dmDoneAuthors.add(author.toLowerCase());
            appendLog("تم إرسال رسالة خاصة إلى " + author + " — رقم " + dmSent);
            dmClose(true);
        });
    }

    void dmSkip(String author, String reason) {
        appendLog("تخطي الرسالة الخاصة لـ " + author + " — " + reason);
        if (dmNavigated) dmClose(false); else dmAfter(600);
    }

    void dmClose(final boolean sent) {
        dmBackTries = 0;
        handler.postDelayed(() -> dmGoBack(sent), 1200);
    }

    // الرجوع إلى صفحة التعليقات بنفس رابطها، مع محاولة الرجوع للخلف أولاً (يحافظ على القائمة)،
    // ثم إعادة تحميل الرابط كحل أخير.
    void dmGoBack(final boolean sent) {
        if (!running) return;
        web.evaluateJavascript("location.href", v -> {
            if (!running) return;
            String url = unquote(v);
            if (pathOnly(url).equals(pathOnly(dmReturnUrl))) {
                dmNavigated = false;
                long extra = sent ? intervalMs + rnd.nextInt(4000) : 800;
                dmAfter(extra);
                return;
            }
            if (dmBackTries < 5) {
                dmBackTries++;
                if (web.canGoBack()) web.goBack(); else web.evaluateJavascript("history.back()", null);
                handler.postDelayed(() -> dmGoBack(sent), 1800);
            } else {
                appendLog("تعذر الرجوع بالأزرار — إعادة تحميل صفحة التعليقات");
                web.loadUrl(dmReturnUrl);
                dmNavigated = false;
                dmAfter(6000);
            }
        });
    }

    void dmAfter(long delayMs) {
        handler.postDelayed(() -> { if (running) restoreCommentPositionAndContinue(); }, delayMs);
    }

    void stopRun(String message) {
        boolean wasRunning=running; running=false;
        if (nextTask != null) handler.removeCallbacks(nextTask);
        nextTask=null;
        if (message != null) { appendLog(message); setStatus(message); }
        if(wasRunning) host.exitReplyFocusMode();
    }

    void setStatus(String s) { status.setText(s); }
    void appendLog(String s) {
        log.setText(s + (log.length()==0 ? "" : "\n" + log.getText().toString()));
        log.post(() -> log.scrollTo(0,0));
        host.updateReplyLiveLog(s);
    }
    void updateProgress() { progress.setText("الردود: " + completed + " / " + (target == Integer.MAX_VALUE ? "كل التعليقات" : String.valueOf(target))); }

    static String unquote(String s) {
        if (s == null) return "";
        if (s.startsWith("\"") && s.endsWith("\"")) {
            try { return new JSONTokener(s).nextValue().toString(); } catch (Exception ignored) {}
        }
        return s;
    }


}
