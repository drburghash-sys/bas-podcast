package com.bas.podcast;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.net.URL;
import java.util.List;

public final class DownloadHelper {
    private DownloadHelper() {}
    public static boolean isDownloaded(Library.Episode e) {
        return e != null && !e.localFile.isEmpty() && new File(e.localFile).isFile()
                && new File(e.localFile).length() > 0 && e.downloadId == -2;
    }
    public static boolean isPending(Library.Episode e) { return e != null && e.downloadId >= 0; }

    public static synchronized void enqueue(Context c, Library.Episode e) throws Exception {
        if (isDownloaded(e) || isPending(e)) return;
        String protocol = new URL(e.audio).getProtocol();
        if (!protocol.equalsIgnoreCase("https") && !protocol.equalsIgnoreCase("http"))
            throw new IllegalArgumentException("رابط الصوت غير مدعوم");
        File base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) throw new IllegalStateException("التخزين غير متاح");
        File folder = new File(base, "BASPodcast");
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("تعذر إنشاء مجلد التنزيلات");
        File file = new File(folder, e.key + ".audio");
        if (file.exists() && !file.delete()) throw new IllegalStateException("تعذر حذف التنزيل غير المكتمل");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(e.audio));
        request.setTitle(e.title);
        request.setDescription(e.feedTitle);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MUSIC, "BASPodcast/" + file.getName());
        if (Library.setting(c, "wifi_only", true))
            request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = dm.enqueue(request);
        Library.updateDownload(c, e.key, id, file.getAbsolutePath());
    }
    public static void autoDownload(Context c, List<Library.Episode> fresh) {
        if (!Library.setting(c, "auto_download", false)) return;
        for (int i = 0; i < Math.min(2, fresh.size()); i++) {
            try { enqueue(c, fresh.get(i)); }
            catch (Exception error) { android.util.Log.w("BASPodcast", "Auto-download failed", error); }
        }
    }
    public static void refreshStatus(Context c) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        for (Library.Episode e : Library.episodes(c)) {
            if (!isPending(e)) continue;
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(e.downloadId);
            try (android.database.Cursor cur = dm.query(query)) {
                if (cur == null || !cur.moveToFirst()) {
                    if (!e.localFile.isEmpty() && new File(e.localFile).isFile() && new File(e.localFile).length() > 0)
                        Library.updateDownload(c, e.key, -2, e.localFile);
                    else Library.clearDownload(c, e.key);
                    continue;
                }
                int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (!e.localFile.isEmpty() && new File(e.localFile).isFile() && new File(e.localFile).length() > 0)
                        Library.updateDownload(c, e.key, -2, e.localFile);
                    else Library.clearDownload(c, e.key);
                } else if (status == DownloadManager.STATUS_FAILED) {
                    Library.clearDownload(c, e.key);
                }
            } catch (Exception ignored) { }
        }
    }
    public static void remove(Context c, Library.Episode e) {
        if (e == null) return;
        if (e.downloadId >= 0) {
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.remove(e.downloadId);
        }
        if (!e.localFile.isEmpty()) {
            File folder = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            File file = new File(e.localFile);
            if (folder != null && file.getAbsolutePath().startsWith(new File(folder, "BASPodcast").getAbsolutePath() + File.separator))
                file.delete();
        }
        Library.clearDownload(c, e.key);
    }
}
