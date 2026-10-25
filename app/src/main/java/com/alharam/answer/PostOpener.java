package com.alharam.answer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.Toast;
import java.util.List;

/** يفتح المنشور من أي رابط: ينظّفه، ثم يجرّب صيغاً بديلة إذا انتهى في الصفحة الرئيسية. */
final class PostOpener {
    private static int token = 0;
    private static final Handler h = new Handler(Looper.getMainLooper());

    static void open(final Context ctx, final WebView web, final String raw) {
        final List<String> vs = PostLinkHelper.variants(raw);
        if (vs.isEmpty()) return;
        attempt(ctx, web, vs, 0, ++token);
    }

    private static void attempt(final Context ctx, final WebView web, final List<String> vs, final int i, final int tk) {
        web.loadUrl(vs.get(i));
        h.postDelayed(() -> {
            if (tk != token) return;
            String url = web.getUrl();
            if (PostLinkHelper.looksLikePost(url)) { toast(ctx, "تم فتح المنشور"); return; }
            if (i + 1 < vs.size()) {
                toast(ctx, "جاري تجربة صيغة أخرى للرابط (" + (i + 2) + "/" + vs.size() + ")");
                attempt(ctx, web, vs, i + 1, tk);
            } else toast(ctx, "تعذر فتح المنشور مباشرة. الرابط الحالي: " + url);
        }, 8000);
    }

    private static void toast(Context c, String m) { try { Toast.makeText(c, m, Toast.LENGTH_LONG).show(); } catch (Exception ignored) { } }
}
