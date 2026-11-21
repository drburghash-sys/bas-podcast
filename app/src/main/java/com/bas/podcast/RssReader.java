package com.bas.podcast;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** RSS 2.0, common iTunes RSS tags and simple Atom podcast enclosures. */
public final class RssReader {
    private RssReader() {}
    private static final int MAX_XML = 5 * 1024 * 1024;

    public static Library.ParsedFeed fetch(String address) throws Exception {
        URL url = new URL(address.trim());
        if (!url.getProtocol().equalsIgnoreCase("https") && !url.getProtocol().equalsIgnoreCase("http"))
            throw new IOException("يجب أن يبدأ رابط RSS بـ https:// أو http://");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(13000); connection.setReadTimeout(17000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "BASPodcast/1.0 (Android RSS client)");
        connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
        try {
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
                throw new IOException("استجابة الخادم: " + connection.getResponseCode());
            try (InputStream raw = connection.getInputStream(); InputStream limited = new Limited(raw, MAX_XML)) {
                return parse(address.trim(), limited);
            }
        } finally { connection.disconnect(); }
    }

    static Library.ParsedFeed parse(String address, InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser(); p.setInput(in, null);
        Library.ParsedFeed result = new Library.ParsedFeed();
        result.feed.url = address;
        Library.Episode episode = null;
        String guid = ""; boolean channelImage = false;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName().toLowerCase(java.util.Locale.ROOT);
                String namespace = p.getNamespace();
                if (tag.equals("item") || tag.equals("entry")) {
                    episode = new Library.Episode(); episode.feedUrl = address; guid = "";
                } else if (tag.equals("image")) {
                    String href = attribute(p, "href");
                    if (!href.isEmpty()) { if (episode != null) episode.image = href; else result.feed.image = href; }
                    else if (episode == null) channelImage = true;
                } else if (tag.equals("enclosure") || tag.equals("content") && isMedia(namespace)) {
                    if (episode != null) {
                        String audio = attribute(p, "url");
                        String type = attribute(p, "type");
                        if (!audio.isEmpty() && (type.isEmpty() || type.startsWith("audio/") || type.equals("application/ogg")))
                            episode.audio = audio;
                    }
                } else if (tag.equals("link") && episode != null) {
                    if (attribute(p, "rel").equalsIgnoreCase("enclosure"))
                        episode.audio = attribute(p, "href");
                } else if (tag.equals("title")) {
                    String t = p.nextText().trim();
                    if (episode != null) episode.title = t;
                    else if (result.feed.title.isEmpty()) result.feed.title = t;
                } else if (tag.equals("description") || tag.equals("summary") || tag.equals("encoded") || tag.equals("subtitle")) {
                    String t = p.nextText();
                    if (episode != null) {
                        if (episode.description.isEmpty() || tag.equals("encoded")) episode.description = t;
                    } else if (result.feed.description.isEmpty()) result.feed.description = t;
                } else if (tag.equals("guid") || tag.equals("id")) {
                    if (episode != null) guid = p.nextText().trim();
                } else if (tag.equals("pubdate") || tag.equals("published") || tag.equals("updated")) {
                    if (episode != null && episode.published.isEmpty()) {
                        episode.published = p.nextText().trim();
                        episode.publishedMillis = parseDate(episode.published);
                    }
                } else if ((tag.equals("author") && namespace != null && namespace.contains("itunes"))
                           || (tag.equals("name") && episode == null)) {
                    if (episode == null && result.feed.author.isEmpty())
                        result.feed.author = p.nextText().trim();
                } else if (tag.equals("duration")) {
                    if (episode != null) episode.durationSeconds = duration(p.nextText().trim());
                } else if (tag.equals("url") && channelImage && episode == null) {
                    result.feed.image = p.nextText().trim();
                } else if (tag.equals("logo") || tag.equals("icon")) {
                    if (episode == null && result.feed.image.isEmpty()) result.feed.image = p.nextText().trim();
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = p.getName().toLowerCase(java.util.Locale.ROOT);
                if ((tag.equals("item") || tag.equals("entry")) && episode != null) {
                    episode.feedTitle = result.feed.title;
                    if (episode.description.length() > 4000)
                        episode.description = episode.description.substring(0, 4000);
                    if (episode.title.isEmpty()) episode.title = "حلقة بلا عنوان";
                    if (episode.image.isEmpty()) episode.image = result.feed.image;
                    if (!episode.audio.isEmpty()) {
                        episode.key = hash(address + "\n" + (guid.isEmpty() ? episode.audio : guid));
                        result.episodes.add(episode);
                    }
                    episode = null;
                    if (result.episodes.size() >= 250) break;
                } else if (tag.equals("image") && episode == null) channelImage = false;
            }
            event = p.next();
        }
        if (result.feed.title.isEmpty()) result.feed.title = new URL(address).getHost();
        if (result.feed.description.length() > 2000)
            result.feed.description = result.feed.description.substring(0, 2000);
        for (Library.Episode e : result.episodes) {
            e.feedTitle = result.feed.title;
            if (e.image.isEmpty()) e.image = result.feed.image;
        }
        return result;
    }
    private static boolean isMedia(String ns) {
        return ns != null && (ns.contains("search.yahoo.com/mrss") || ns.toLowerCase().contains("media"));
    }
    private static long parseDate(String text) {
        try { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); }
        catch (Exception ignored) {}
        try { return OffsetDateTime.parse(text).toInstant().toEpochMilli(); }
        catch (Exception ignored) {}
        try { return ZonedDateTime.parse(text).toInstant().toEpochMilli(); }
        catch (Exception ignored) {}
        return 0;
    }
    private static String attribute(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++)
            if (name.equalsIgnoreCase(p.getAttributeName(i))) return p.getAttributeValue(i) == null ? "" : p.getAttributeValue(i).trim();
        return "";
    }
    private static long duration(String str) {
        try {
            String[] parts = str.split(":"); long result = 0;
            for (String s : parts) result = result * 60L + Long.parseLong(s.trim());
            return Math.max(0, result);
        } catch (Exception ignored) { return 0; }
    }
    private static String hash(String data) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format(java.util.Locale.US, "%02x", bytes[i] & 0xff));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(data.hashCode()); }
    }
    private static final class Limited extends FilterInputStream {
        private int remaining;
        Limited(InputStream stream, int cap) { super(stream); this.remaining = cap; }
        @Override public int read() throws IOException {
            if (remaining == 0) throw new IOException("ملف RSS كبير جدًا");
            int x = super.read(); if (x != -1) remaining--; return x;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining == 0) throw new IOException("ملف RSS كبير جدًا");
            int n = super.read(b, off, Math.min(len, remaining)); if (n > 0) remaining -= n; return n;
        }
    }
}
