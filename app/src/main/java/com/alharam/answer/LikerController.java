package com.alharam.answer;

import android.app.Activity;
import android.os.Handler;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;
import org.json.JSONObject;

class LikerController {
    final Activity activity;
    final android.view.View panel;
    final WebView web;
    TextView results, status;
    Button startButton, stopButton;
    RadioButton scopeMain, scopeWithReplies;
    Handler handler = new Handler();
    Runnable task;
    int completed = 0, target = 0;
    long intervalMs = 10000;

    LikerController(Activity a, android.view.View p, WebView w) {
        activity=a; panel=p; web=w;
        results=p.findViewById(R.id.liker_results);
        status=p.findViewById(R.id.liker_status);
        startButton=p.findViewById(R.id.liker_like);
        stopButton=p.findViewById(R.id.liker_stop);
        scopeMain=p.findViewById(R.id.liker_scope_main);
        scopeWithReplies=p.findViewById(R.id.liker_scope_with_replies);
        startButton.setOnClickListener(v->startLikeRun());
        stopButton.setOnClickListener(v->stopRun("تم إيقاف الإعجاب يدويًا."));
    }

    void startLikeRun() {
        stopRun(null);
        MainActivity ma=(MainActivity)activity;
        ma.saveSharedSettings();
        target=Math.max(1,ma.sharedCount.getNumericValue());
        intervalMs=Math.max(1000,ma.sharedDelay.getNumericValue()*1000L);
        boolean includeReplies=scopeWithReplies!=null && scopeWithReplies.isChecked();
        completed=0;
        results.setText("بدأ الإعجاب — المطلوب: "+target+" — النطاق: "+(includeReplies?"التعليقات الرئيسية + الردود":"التعليقات الرئيسية فقط"));
        status.setText("جاري البحث عن أول تعليق غير مُعجب به...");
        String selected=ma.sharedStartTime();
        if(selected==null){ status.setText("اختر الدقائق والساعات والفترة معًا، أو اترك وقت البدء فارغًا"); return; }
        Runnable starter=()->runOne(includeReplies);
        if(!selected.isEmpty()){
            long wait=ma.sharedStartDelayMs();
            status.setText("⏰ مجدول: "+selected);
            appendLog("تمت جدولة الإعجاب الساعة "+selected+".");
            handler.postDelayed(starter,wait);
        } else starter.run();
    }

    void runOne(final boolean includeReplies) {
        if(task==null && completed>=target) return;
        if(completed>=target){ stopRun("تم تنفيذ "+completed+" إعجابًا — اكتمل العدد المطلوب."); return; }
        status.setText("جاري البحث عن الإعجاب الجديد رقم "+(completed+1)+"...");
        dismissTransientUi();
        web.evaluateJavascript(buildActualLikeJs(includeReplies), value -> {
            if(task==null && completed==0 && value==null) return;
            try {
                JSONObject o=new JSONObject(unquote(value));
                boolean ok=o.optBoolean("ok",false);
                if(ok && o.optBoolean("clicked",false)){
                    completed++;
                    String message="تم تنفيذ الإعجاب رقم "+completed+" — "+o.optString("message","تم الإعجاب بتعليق جديد");
                    appendLog(message);
                    status.setText(message);
                    results.setText(message+(results.length()>0?"\n"+results.getText().toString():""));
                    if(completed>=target){ task=null; stopRun("تم تنفيذ "+completed+" إعجابًا — اكتمل العدد المطلوب."); return; }
                    task=new Runnable(){@Override public void run(){ if(task!=null) runOne(includeReplies); }};
                    handler.postDelayed(task,intervalMs);
                    return;
                }
                if(o.optBoolean("scrolled",false)){
                    String m=o.optString("message","تم تمرير قائمة التعليقات إلى الأمام للبحث عن تعليق جديد.");
                    appendLog(m); status.setText(m);
                    task=new Runnable(){@Override public void run(){ if(task!=null) runOne(includeReplies); }};
                    handler.postDelayed(task,900);
                    return;
                }
                String reason=o.optString("message","لم يتم العثور على تعليق جديد صالح للإعجاب.");
                stopRun("توقف الإعجاب: "+reason);
            } catch(Exception e){
                stopRun("توقف الإعجاب: تعذر قراءة نتيجة العملية — "+e.getMessage());
            }
        });
    }

    void stopRun(String message){
        if(task!=null) handler.removeCallbacks(task);
        task=null;
        if(message!=null){ appendLog(message); status.setText(message); }
    }

    void appendLog(String s){
        if(results==null)return;
        results.setText(s+(results.length()>0?"\n"+results.getText().toString():""));
    }

    void dismissTransientUi(){
        web.evaluateJavascript("(()=>{try{const a=document.activeElement;if(a&&a.matches('input,textarea,[contenteditable=true]'))a.blur();document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}));}catch(e){}return true;})()",null);
    }

    String buildActualLikeJs(boolean includeReplies){
      return "(()=>{const keys=['like','likes','إعجاب','اعجاب','أعجبني'];" +
      "const replyWords=['reply','replies','reply to','response','responses','رد','الرد','ردود','الردود','الرد على','استجابة','استجابات'];" +
      "const norm=s=>(s||'').replace(/\\s+/g,' ').trim();" +
      "const visible=e=>{if(!e)return false;const r=e.getBoundingClientRect(),st=getComputedStyle(e);return r.width>0&&r.height>0&&st.display!=='none'&&st.visibility!=='hidden'&&r.bottom>0&&r.top<innerHeight};" +
      "const hasReplyContext=e=>{let p=e;for(let level=0;level<12&&p;level++,p=p.parentElement){const txt=norm((p.innerText||'')+' '+(p.getAttribute('aria-label')||'')).toLowerCase();if(replyWords.some(x=>txt.includes(x))){const r=p.getBoundingClientRect();if(r.width>120&&r.height>40&&r.height<2200)return p;}}return null;};" +
      "const els=[...document.querySelectorAll('button,[role=button],[role=link],a,[aria-label],div[tabindex]')];" +
      "let hit=null,best=-1;" +
      "for(const e of els){" +
      "if(!visible(e))continue;" +
      "const t=norm(e.innerText),a=norm(e.getAttribute('aria-label')),title=norm(e.getAttribute('title')),pressed=norm(e.getAttribute('aria-pressed'));" +
      "const lowA=a.toLowerCase(),hay=(t+' '+a+' '+title).toLowerCase();" +
      "if(/unlike|remove like|إلغاء الإعجاب|إلغاء اعجاب/.test(lowA))continue;" +
      "if(pressed==='true')continue;" +
      "let score=0;for(const x of keys){const lx=x.toLowerCase();if(lowA===lx)score+=100;if(title.toLowerCase()===lx)score+=80;if(t.toLowerCase()===lx)score+=70;if(hay.includes(lx))score+=10;}" +
      "if(score<=0)continue;" +
      "const context=hasReplyContext(e);" +
      "if(!context)continue;" +
      "const cr=context.getBoundingClientRect();" +
      "if(cr.width<=120||cr.height<=40)continue;" +
      "score+=300;" +
      "if((e.getAttribute('role')==='button'||e.tagName==='BUTTON')&&score>0)score+=20;" +
      "if(score>best){best=score;hit=e;}" +
      "}" +
      "if(!hit)return JSON.stringify({ok:false,clicked:false,message:'لا يوجد زر Like جديد داخل التعليقات فقط — تم تجاهل Like المنشور الرئيسي وأي Like معمول بالفعل'});" +
      "hit.scrollIntoView({block:'center',inline:'center',behavior:'auto'});" +
      "const before=norm(hit.innerText)+' | '+norm(hit.getAttribute('aria-label'))+' | '+norm(hit.getAttribute('aria-pressed'));" +
      "hit.click();" +
      "return JSON.stringify({ok:true,clicked:true,tag:hit.tagName,text:norm(hit.innerText),aria:norm(hit.getAttribute('aria-label')),role:norm(hit.getAttribute('role')),before:before,score:best,message:'تم تنفيذ Like جديد على تعليق فقط'});})()";
    }

    static String unquote(String s){
        if(s==null)return "";
        try{if(s.length()>=2&&s.charAt(0)=='\"'&&s.charAt(s.length()-1)=='\"')return new org.json.JSONTokener(s).nextValue().toString();}catch(Exception ignored){}
        return s;
    }
}
