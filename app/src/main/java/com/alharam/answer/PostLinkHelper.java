package com.alharam.answer;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** تنظيف روابط فيسبوك وتوليد صيغ بديلة لفتح المنشور نفسه لا الصفحة. */
final class PostLinkHelper {
    private static final Set<String> DROP = new HashSet<>(Arrays.asList(
        "app", "mibextid", "fbclid", "ref", "ref_src", "ref_type", "__cft__[0]", "__tn__", "notif_id", "notif_t",
        "rdid", "share_url", "sfnsn", "paipv", "eav", "_rdr", "hc_ref", "fref", "refsrc", "extid", "entry_point",
        "fb_source", "comment_tracking", "__xts__[0]", "_ft_"));

    static final class Parts { String scheme = "https", host = "", path = "", query = ""; Map<String, String> q = new LinkedHashMap<>(); }

    static String dec(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; } }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    static Parts split(String url) {
        Parts p = new Parts();
        String u = url == null ? "" : url.trim();
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        int sch = u.indexOf("://");
        if (sch > 0) { p.scheme = u.substring(0, sch).toLowerCase(); u = u.substring(sch + 3); }
        int cut = u.length();
        for (char c : new char[]{'/', '?'}) { int i = u.indexOf(c); if (i >= 0 && i < cut) cut = i; }
        p.host = u.substring(0, cut).toLowerCase();
        String rest = u.substring(cut);
        int qi = rest.indexOf('?');
        if (qi >= 0) { p.path = rest.substring(0, qi); p.query = rest.substring(qi + 1); } else p.path = rest;
        for (String kv : p.query.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k = dec(e >= 0 ? kv.substring(0, e) : kv), v = e >= 0 ? dec(kv.substring(e + 1)) : "";
            p.q.put(k, v);
        }
        return p;
    }

    static boolean isFb(String host) {
        return host.equals("facebook.com") || host.endsWith(".facebook.com") || host.equals("fb.com") || host.endsWith(".fb.com") || host.equals("fb.watch");
    }

    static String build(String host, String path, Map<String, String> q) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return "https://" + host + path + (sb.length() > 0 ? "?" + sb : "");
    }

    /** يزيل الإضافات التتبعية ويفك روابط التحويل. */
    static String clean(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        u = u.split("\\s+")[0];
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) u = "https://" + u;
        Parts p = split(u);
        if ((p.host.equals("l.facebook.com") || p.host.equals("lm.facebook.com") || p.host.equals("l.messenger.com")) && p.q.containsKey("u"))
            return clean(p.q.get("u"));
        if (p.scheme.startsWith("fb")) {
            if (p.q.containsKey("href")) return clean(p.q.get("href"));
            return u;
        }
        if (!isFb(p.host)) return u;
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : p.q.entrySet()) if (!DROP.contains(e.getKey())) kept.put(e.getKey(), e.getValue());
        return build(p.host, p.path, kept);
    }

    /** الصيغ التي تُجرَّب بالترتيب حتى يُفتح المنشور. */
    static List<String> variants(String raw) {
        List<String> out = new ArrayList<>();
        String c = clean(raw);
        if (c.isEmpty()) return out;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(c);
        Parts p = split(c);
        if (isFb(p.host)) {
            String sid = p.q.get("story_fbid"), id = p.q.get("id");
            String qs = p.query.isEmpty() ? "" : "?" + p.query;
            if (sid != null && !sid.isEmpty() && id != null && !id.isEmpty())
                set.add("https://" + p.host + "/story.php?story_fbid=" + enc(sid) + "&id=" + enc(id));
            if (!p.host.equals("m.facebook.com")) set.add("https://m.facebook.com" + p.path + qs);
            if (!p.host.equals("www.facebook.com")) set.add("https://www.facebook.com" + p.path + qs);
            if (sid != null && !sid.isEmpty() && id != null && !id.isEmpty())
                set.add("https://m.facebook.com/story.php?story_fbid=" + enc(sid) + "&id=" + enc(id));
        }
        out.addAll(set);
        return out;
    }

    /** هل الرابط الحالي لمنشور (وليس صفحة رئيسية أو خلاصة)؟ */
    static boolean looksLikePost(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        if (u.contains("story_fbid=") || u.contains("/posts/") || u.contains("/permalink") || u.contains("fbid=")
            || u.contains("/videos/") || u.contains("/reel/") || u.contains("/watch/?v=") || u.contains("/photo")
            || u.contains("comment_id=") || u.contains("/story.php")) return true;
        return false;
    }

    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
        "", "home.php", "story.php", "permalink.php", "photo", "photo.php", "groups", "watch", "reel", "share", "stories",
        "events", "pages", "marketplace", "login", "l.php", "hashtag", "search", "friends", "messages", "notifications", "menu", "bookmarks"));

    /** يستخرج معرّف الحساب أو اسم المستخدم من رابط ملف شخصي (لاستخدامه مع m.me). */
    static String profileTarget(String url) {
        if (url == null || url.isEmpty()) return "";
        if (url.startsWith("/")) url = "https://www.facebook.com" + url;
        Parts p = split(url);
        if (!isFb(p.host)) return "";
        String path = p.path;
        String id = p.q.get("id");
        if (path.contains("profile.php") && id != null && id.matches("\\d+")) return id;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/people/[^/]+/(\\d+)").matcher(path);
        if (m.find()) return m.group(1);
        String first = path.startsWith("/") ? path.substring(1) : path;
        int s = first.indexOf('/'); if (s >= 0) first = first.substring(0, s);
        first = dec(first);
        if (RESERVED.contains(first.toLowerCase()) || !first.matches("[A-Za-z0-9._-]+")) return "";
        return first;
    }
}
