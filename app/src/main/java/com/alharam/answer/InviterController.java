package com.alharam.answer;
import com.alharam.answer.R;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

class InviterController {
    final Activity activity; final android.view.View panel; final WebView web;
    TextView status, log; ScrollView logScroll; StepperView sharedCount, sharedDelay;
    Handler handler=new Handler(Looper.getMainLooper()); boolean running=false; int sent=0,noInvitePasses=0; Runnable task;

    InviterController(Activity a, android.view.View p, WebView w){
      activity=a; panel=p; web=w;
      status=p.findViewById(R.id.inviter_status); log=p.findViewById(R.id.inviter_log); logScroll=p.findViewById(R.id.inviter_logScroll);
      sharedCount=a.findViewById(R.id.shared_count); sharedDelay=a.findViewById(R.id.shared_delay);
      p.findViewById(R.id.inviter_start).setOnClickListener(v->{scheduleOrStart();});
      p.findViewById(R.id.inviter_stop).setOnClickListener(v->stop());
    }

    void scheduleOrStart() {
      MainActivity ma=(MainActivity)activity; ma.saveSharedSettings();
      String selected=ma.sharedStartTime();
      if(selected==null){ addLog("اختر الدقائق والساعات والفترة معًا، أو اترك وقت البدء فارغًا للبدء فورًا."); return; }
      if(selected.isEmpty()){ startNow(); return; }
      status.setText("⏰ مجدول: "+selected); addLog("تمت جدولة الدعوات الساعة "+selected+".");
      if(task!=null) handler.removeCallbacks(task); task=new Runnable(){public void run(){startNow();}}; handler.postDelayed(task,ma.sharedStartDelayMs());
    }

    void startNow() {
        running = true;
        sent = 0;
        noInvitePasses = 0;
        int max = Math.min(1000, Math.max(1, sharedCount.getNumericValue()));
        int d = Math.max(1, Math.min(300, sharedDelay.getNumericValue()));
        status.setText("🟢 يعمل: 0 / " + max);
        addLog("بدأ التشغيل من صفحة الـInvite الحالية؛ لن يتم فتح صفحات العملاء.");
        task = new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (sent >= max) { finishRun("اكتمل الحد المحدد: " + sent + " دعوة."); return; }

                String js = "(function(){" +
                        "const norm=t=>(t||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
                        "const visible=e=>{if(!e||e.disabled||e.getAttribute('aria-disabled')==='true')return false;const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';};" +
                        "const isInvite=t=>t==='invite'||t==='دعوة'||t==='invite friends'||t==='دعوة الأصدقاء';" +
                        "const candidates=[...document.querySelectorAll('button,[role=\\\"button\\\"]')].filter(visible);" +
                        "let x=candidates.find(e=>{let t=norm(e.innerText||e.textContent);if(!isInvite(t))return false;if(e.closest('a[href]'))return false;return true;});" +
                        "if(x){x.scrollIntoView({block:'center',inline:'nearest'});x.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,view:window}));x.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,view:window}));x.click();return JSON.stringify({state:'clicked',text:norm(x.innerText||x.textContent),y:window.scrollY});}" +
                        "const before=window.scrollY;const maxY=Math.max(0,document.documentElement.scrollHeight-window.innerHeight);const atEnd=before>=Math.max(0,maxY-40);if(!atEnd)window.scrollBy(0,650);return JSON.stringify({state:atEnd?'end':'scrolled',y:before,maxY:maxY});" +
                        "})()";
                web.evaluateJavascript(js, v -> {
                    if (!running) return;
                    String r = v == null ? "" : v.replace("\\\"", "\"").replace("\\\\", "\\");
                    if (r.contains("\"state\":\"clicked\"")) {
                        sent++; noInvitePasses = 0;
                        status.setText("🟢 تمت دعوة " + sent + " / " + max);
                        addLog("تم الضغط على زر Invite الحقيقي — الدعوة رقم " + sent + ".");
                        handler.postDelayed(task, d * 1000L);
                    } else if (r.contains("\"state\":\"end\"")) {
                        noInvitePasses++;
                        if (noInvitePasses >= 2) finishRun("انتهت قائمة الـInvite — تم تنفيذ " + sent + " دعوة.");
                        else handler.postDelayed(task, 900);
                    } else {
                        noInvitePasses = 0;
                        addLog("لا يوجد زر Invite في الجزء الظاهر؛ تم التمرير داخل نفس الصفحة للبحث عن التالي.");
                        handler.postDelayed(task, 900);
                    }
                });
            }
        };
        handler.post(task);
    }

    void finishRun(String message) {
        running = false;
        if (task != null) handler.removeCallbacks(task);
        status.setText("✅ انتهى التشغيل");
        addLog("✅ " + message);
    }

    void stop() {
        int total = sent;
        running = false;
        if (task != null) handler.removeCallbacks(task);
        status.setText("🔴 متوقف");
        addLog("تم الإيقاف يدويًا — إجمالي الدعوات: " + total + ".");
    }

    void addLog(String s) {
        String tm = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        log.setText("[" + tm + "] " + s + (log.length() == 0 ? "" : "\n" + log.getText().toString()));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_UP));
    }


}
