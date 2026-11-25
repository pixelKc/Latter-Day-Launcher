package com.colle.latterdaylauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the image for a Come, Follow Me lesson. No external libraries needed.
 *
 * Needs <uses-permission android:name="android.permission.INTERNET" /> in the manifest.
 * All methods do network I/O: call them from a background thread only.
 */
public final class LessonImageFetcher {

    private static final String BASE =
            "https://www.churchofjesuschrist.org/study/manual/"
                    + "come-follow-me-for-home-and-church-old-testament-2026/%02d?lang=eng";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    // Date lesson 1 begins (a Monday): 2025-12-29. Adjust if "current" is off by a week.
    private static final int START_YEAR = 2025;
    private static final int START_MONTH = Calendar.DECEMBER;
    private static final int START_DAY = 29;
    private static final int MAX_LESSON = 52;

    // Matches .../imgs/<id>/<region>/<size>/<rotation>/default...
    private static final Pattern IIIF =
            Pattern.compile("(/imgs/[^/]+/[^/]+/)([^/]+)(/\\d+/default.*)");

    private static final Pattern IMG_TAG =
            Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_TAG =
            Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private LessonImageFetcher() {}

    public static String buildUrl(int lesson) {
        return String.format(Locale.US, BASE, lesson);
    }

    /** Lesson number for today, clamped to 1..MAX_LESSON. Works on all Android versions. */
    public static int currentLesson() {
        Calendar start = Calendar.getInstance();
        start.clear();
        start.set(START_YEAR, START_MONTH, START_DAY);

        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        // round() absorbs the 1-hour difference around daylight saving changes
        long days = Math.round((today.getTimeInMillis() - start.getTimeInMillis()) / 86400000.0);
        long weeks = (days >= 0 ? days / 7 : -1) + 1;
        return (int) Math.max(1, Math.min(MAX_LESSON, weeks));
    }

    /** The result of fetching a lesson: its image and its title (title may be null). */
    public static final class Lesson {
        public final Bitmap image;
        public final String title;

        Lesson(Bitmap image, String title) {
            this.image = image;
            this.title = title;
        }
    }

    /**
     * Fetch the lesson image and title in one go.
     * @param useOgImage true = social-preview image, false = first image in page body
     */
    public static Lesson fetchLesson(int lesson, boolean useOgImage) throws IOException {
        String pageUrl = buildUrl(lesson);
        String html = new String(httpGet(pageUrl, "text/html"), "UTF-8");

        String imageUrl = findImageUrl(pageUrl, html, useOgImage);
        if (imageUrl == null) throw new IOException("No image found on " + pageUrl);

        IOException last = null;
        for (String candidate : upgradeUrl(imageUrl)) {
            try {
                byte[] data = httpGet(candidate, "image/");
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp != null) return new Lesson(bmp, findTitle(html));
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("Could not download image", last);
    }

    /** Image only (kept for convenience). */
    public static Bitmap fetchLessonImage(int lesson, boolean useOgImage) throws IOException {
        return fetchLesson(lesson, useOgImage).image;
    }

    // ---------- finding the title ----------

    /** Prefers the og:title meta tag, falls back to the <title> tag. Returns null if neither exists. */
    static String findTitle(String html) {
        Matcher m = META_TAG.matcher(html);
        while (m.find()) {
            String tag = m.group();
            if ("og:title".equalsIgnoreCase(attr(tag, "property"))) {
                String content = attr(tag, "content");
                if (content != null && !content.trim().isEmpty()) return decodeEntities(content.trim());
            }
        }
        Matcher t = Pattern.compile("<title[^>]*>(.*?)</title>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (t.find()) {
            String title = t.group(1).trim();
            if (!title.isEmpty()) return decodeEntities(title);
        }
        return null;
    }

    /** Turn things like &quot; &amp; &#8217; &#x2019; back into real characters. */
    static String decodeEntities(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement = m.group();
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                replacement = new String(Character.toChars(cp));
            } catch (IllegalArgumentException ignored) {}
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString()
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    // ---------- finding the image ----------

    static String findImageUrl(String pageUrl, String html, boolean useOgImage) {
        if (useOgImage) {
            String og = ogImage(pageUrl, html);
            if (og != null) return og;
        }

        // Prefer the <main> section so we skip header/nav icons
        String scope = html;
        int mainStart = indexOfIgnoreCase(html, "<main");
        if (mainStart >= 0) {
            int mainEnd = indexOfIgnoreCase(html, "</main>");
            scope = mainEnd > mainStart ? html.substring(mainStart, mainEnd) : html.substring(mainStart);
        }

        Matcher m = IMG_TAG.matcher(scope);
        while (m.find()) {
            String tag = m.group();
            String src = null;

            String srcset = attr(tag, "srcset");
            if (srcset != null) src = largestFromSrcset(srcset);
            if (src == null || src.isEmpty()) src = attr(tag, "src");
            if (src == null || src.isEmpty()) src = attr(tag, "data-src");

            if (src != null && !src.isEmpty() && !src.startsWith("data:")) {
                String abs = resolve(pageUrl, src);
                if (abs != null) return abs;
            }
        }
        return ogImage(pageUrl, html); // fallback
    }

    private static String ogImage(String pageUrl, String html) {
        Matcher m = META_TAG.matcher(html);
        while (m.find()) {
            String tag = m.group();
            if ("og:image".equalsIgnoreCase(attr(tag, "property"))) {
                String content = attr(tag, "content");
                if (content != null && !content.isEmpty()) return resolve(pageUrl, content);
            }
        }
        return null;
    }

    /** Pull one attribute's value out of a single HTML tag. */
    static String attr(String tag, String name) {
        Pattern p = Pattern.compile(
                "(?<![\\w-])" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(tag);
        if (!m.find()) return null;
        String v = m.group(1) != null ? m.group(1) : m.group(2);
        return v.replace("&amp;", "&").trim();
    }

    /** Pick the biggest candidate from an srcset string. */
    static String largestFromSrcset(String srcset) {
        String best = null;
        double bestSize = -1;
        // Candidates are separated by a comma followed by whitespace
        for (String part : srcset.trim().split(",\\s+")) {
            String[] bits = part.trim().split("\\s+");
            if (bits.length == 0 || bits[0].isEmpty()) continue;
            double size = 0;
            if (bits.length > 1) {
                try {
                    size = Double.parseDouble(bits[1].replaceAll("[wx]$", ""));
                } catch (NumberFormatException ignored) {}
            }
            if (size >= bestSize) {
                best = bits[0];
                bestSize = size;
            }
        }
        return best;
    }

    private static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.US).indexOf(needle.toLowerCase(Locale.US));
    }

    // ---------- quality upgrade ----------

    /** Candidate URLs from highest to lowest quality; the original is always last. */
    static List<String> upgradeUrl(String url) {
        List<String> out = new ArrayList<>();
        Matcher m = IIIF.matcher(url);
        if (m.find()) {
            String prefix = url.substring(0, m.start());
            String head = m.group(1);
            String tail = m.group(3);
            out.add(prefix + head + "max" + tail);
            out.add(prefix + head + "full" + tail);
            out.add(prefix + head + "!2560%2C" + tail);
            out.add(prefix + head + "!1920%2C" + tail);
        }
        out.add(url);
        return out;
    }

    // ---------- networking ----------

    /** GET a URL and return the body. Throws unless status is 200 and content type starts with expectedType. */
    private static byte[] httpGet(String urlStr, String expectedType) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + urlStr);
            String type = conn.getContentType();
            if (type == null || !type.toLowerCase(Locale.US).startsWith(expectedType)) {
                throw new IOException("Unexpected content type " + type + " for " + urlStr);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                return buf.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}