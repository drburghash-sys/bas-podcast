package com.bas.podcast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** BAS Podcast — Arabic Android RSS podcast app with local downloads. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16,22,38), CARD = Color.rgb(27,36,56);
    private static final int CARD2 = Color.rgb(35,49,71), TEAL = Color.rgb(64,222,180);
    private static final int GOLD = Color.rgb(255,223,159), WHITE = Color.rgb(244,248,251);
    private static final int MUTED = Color.rgb(170,183,203);
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final ExecutorService ART = Executors.newFixedThreadPool(2);
    private static final LruCache<String,Bitmap> CACHE = new LruCache<>(32);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, body, miniPlayer;
    private String page = "library", selectedFeed = "";
    private boolean busy = false;
    private MediaController controller;
    private ListenableFuture<MediaController> future;
    private Library.Episode waiting;
    private SeekBar seek;
    private TextView timing;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (controller != null && seek != null) {
                long pos = controller.getCurrentPosition(), dur = controller.getDuration();
                if (dur > 0 && dur != androidx.media3.common.C.TIME_UNSET) {
                    seek.setMax((int)Math.min(dur/1000, Integer.MAX_VALUE));
                    if (!seek.isPressed()) seek.setProgress((int)(pos/1000));
                }
                if (timing != null) timing.setText(time(pos)+" / "+time(dur));
            }
            ui.postDelayed(this,1000);
        }
    };
    private final Player.Listener listener = new Player.Listener() {
        @Override public void onIsPlayingChanged(boolean value) { updatePlayer(); }
        @Override public void onMediaItemTransition(MediaItem item,int reason) { updatePlayer(); }
        @Override public void onPlaybackStateChanged(int state) { updatePlayer(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);
        FeedSyncJob.schedule(this);
        connectPlayer();
        draw();
        Intent incoming = getIntent();
        if (Intent.ACTION_SEND.equals(incoming.getAction()) && "text/plain".equals(incoming.getType())) {
            String link = incoming.getStringExtra(Intent.EXTRA_TEXT);
            ui.postDelayed(() -> addFeedDialog(link == null ? "" : link),450);
        }
        ui.post(tick);
    }
    @Override protected void onResume() {
        super.onResume();
        if (root != null) refreshDownloads();
    }
    private void connectPlayer() {
        future = new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).buildAsync();
        future.addListener(() -> {
            try {
                controller = future.get();
                controller.addListener(listener);
                if (waiting != null) { Library.Episode e = waiting; waiting = null; play(e); }
                updatePlayer();
            } catch (Exception e) { toast("تعذر الاتصال بمشغّل الصوت"); }
        },ContextCompat.getMainExecutor(this));
    }
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private LinearLayout.LayoutParams lp(int w,int h,int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));
        p.topMargin=dp(top);return p;
    }
    private GradientDrawable bg(int color,int radius) {
        GradientDrawable d = new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return v;
    }
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    private TextView label(String value,int size,int color,boolean bold) {
        TextView v = new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);
        v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        v.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return v;
    }
    private TextView button(String value,boolean prominent,Runnable action) {
        TextView b = label(value,13,prominent?BG:WHITE,true);
        b.setPadding(dp(12),dp(11),dp(12),dp(11));
        b.setGravity(Gravity.CENTER);b.setBackground(bg(prominent?TEAL:CARD2,12));
        b.setOnClickListener(v->action.run());return b;
    }
    private LinearLayout card() {
        LinearLayout l = column();l.setBackground(bg(CARD,17));
        l.setPadding(dp(14),dp(14),dp(14),dp(14));return l;
    }
    private void heading(String title,String desc) {
        body.addView(label(title,23,WHITE,true));
        if(desc!=null&&!desc.isEmpty())body.addView(label(desc,12,MUTED,false),lp(-1,-2,5));
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void draw() {
        root = column();root.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets b=insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(0,b.top,0,b.bottom);return insets;
        });
        setContentView(root);
        LinearLayout header=row();header.setPadding(dp(18),dp(14),dp(18),dp(12));
        LinearLayout branding=column();
        branding.addView(label("◉  بودكاست BAS",23,GOLD,true));
        branding.addView(label("استمع على مزاجك",11,MUTED,false));
        header.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(button("+  RSS",true,()->addFeedDialog("")));
        root.addView(header);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        body=column();body.setPadding(dp(16),dp(9),dp(16),dp(24));scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        miniPlayer=column();root.addView(miniPlayer);
        LinearLayout tabs=row();tabs.setBackgroundColor(CARD);
        tab(tabs,"⌂","مكتبتي","library");tab(tabs,"◷","الجديدة","recent");
        tab(tabs,"↓","التنزيلات","downloads");tab(tabs,"⚙","الإعدادات","settings");
        root.addView(tabs);
        drawPage();updatePlayer();
    }
    private void tab(LinearLayout tabs,String icon,String title,String id) {
        LinearLayout item=column();item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2),dp(8),dp(2),dp(10));
        TextView top=label(icon,21,page.equals(id)?TEAL:MUTED,true);
        top.setGravity(Gravity.CENTER);item.addView(top);
        TextView bottom=label(title,11,page.equals(id)?TEAL:MUTED,page.equals(id));
        bottom.setGravity(Gravity.CENTER);item.addView(bottom);
        item.setOnClickListener(v->{page=id;selectedFeed="";draw();});
        tabs.addView(item,new LinearLayout.LayoutParams(0,-2,1));
    }
    private void drawPage() {
        if(body==null)return;
        body.removeAllViews();
        switch(page) {
            case "recent":recent();break;
            case "downloads":downloads();break;
            case "settings":settings();break;
            case "feed":feed();break;
            default:library();
        }
    }
    private void empty(String title,String help,boolean add) {
        LinearLayout v=card();
        TextView big=label("◎",40,TEAL,true);big.setGravity(Gravity.CENTER);v.addView(big);
        TextView t=label(title,18,WHITE,true);t.setGravity(Gravity.CENTER);v.addView(t,lp(-1,-2,12));
        TextView s=label(help,13,MUTED,false);s.setGravity(Gravity.CENTER);v.addView(s,lp(-1,-2,8));
        if(add)v.addView(button("إضافة بودكاست",true,()->addFeedDialog("")),lp(-1,-2,16));
        body.addView(v,lp(-1,-2,23));
    }
    private void library() {
        heading("مكتبتي","اشتراكاتك محفوظة على جهازك، دون حساب أو إعلانات");
        List<Library.Feed> feeds=Library.feeds(this);
        if(feeds.isEmpty()){empty("مكتبتك جاهزة","أضف رابط RSS لمتابعة بودكاستك المفضّل",true);return;}
        body.addView(button(busy?"جارٍ التحديث":"↻ تحديث الجميع",false,this::refreshAll),lp(-1,-2,17));
        body.addView(label("الاشتراكات · "+feeds.size(),17,WHITE,true),lp(-1,-2,22));
        List<Library.Episode> episodes=Library.episodes(this);
        for(Library.Feed f:feeds) {
            LinearLayout c=row();c.setBackground(bg(CARD,17));c.setPadding(dp(12),dp(12),dp(12),dp(12));
            c.addView(cover(f.image),lp(74,74,0));
            LinearLayout info=column();info.setPadding(dp(13),0,0,0);
            info.addView(label(f.title,17,WHITE,true));
            info.addView(label(f.author.isEmpty()?"بودكاست":f.author,12,MUTED,false),lp(-1,-2,5));
            int count=0;for(Library.Episode e:episodes)if(e.feedUrl.equals(f.url))count++;
            info.addView(label(count+" حلقة",12,TEAL,true),lp(-1,-2,6));
            c.addView(info,new LinearLayout.LayoutParams(0,-2,1));
            c.setOnClickListener(v->{page="feed";selectedFeed=f.url;draw();});
            body.addView(c,lp(-1,-2,12));
        }
    }
    private void feed() {
        Library.Feed f=null;
        for(Library.Feed x:Library.feeds(this))if(x.url.equals(selectedFeed))f=x;
        if(f==null){page="library";library();return;}
        final Library.Feed current=f;
        body.addView(button("العودة إلى مكتبتي",false,()->{page="library";draw();}));
        LinearLayout hero=row();hero.setPadding(dp(12),dp(12),dp(12),dp(12));hero.setBackground(bg(CARD,17));
        hero.addView(cover(current.image),lp(85,85,0));
        LinearLayout desc=column();desc.setPadding(dp(13),0,0,0);
        desc.addView(label(current.title,19,GOLD,true));
        desc.addView(label(current.author,12,MUTED,false),lp(-1,-2,7));
        hero.addView(desc,new LinearLayout.LayoutParams(0,-2,1));
        body.addView(hero,lp(-1,-2,15));
        LinearLayout actions=row();
        actions.addView(button("↻ تحديث",true,()->refreshOne(current.url)));
        actions.addView(button("إلغاء الاشتراك",false,()->unsubscribe(current)),lp(-2,-2,0));
        body.addView(actions,lp(-1,-2,12));
        body.addView(label("الحلقات",18,WHITE,true),lp(-1,-2,20));
        int count=0;
        for(Library.Episode e:Library.episodes(this))if(e.feedUrl.equals(current.url)){episodeCard(e);count++;}
        if(count==0)empty("لا توجد حلقات","قد يكون الرابط غير مخصص لبودكاست صوتي",false);
    }
    private void recent() {
        heading("أحدث الحلقات","حلقات اشتراكاتك مرتبة بحسب تاريخ النشر");
        List<Library.Episode> list=Library.episodes(this);
        if(list.isEmpty()){empty("لا توجد حلقات بعد","أضف رابط RSS لتظهر الحلقات هنا",true);return;}
        for(int i=0;i<Math.min(70,list.size());i++)episodeCard(list.get(i));
    }
    private void downloads() {
        heading("التنزيلات","استمع دون إنترنت بعد اكتمال تحميل الملف");
        int count=0;
        for(Library.Episode e:Library.episodes(this)) {
            if(DownloadHelper.isDownloaded(e)||DownloadHelper.isPending(e)){episodeCard(e);count++;}
        }
        if(count==0)empty("لا توجد تنزيلات","اختر «تحميل» بجانب أي حلقة",false);
        body.addView(button("↻ فحص التنزيلات",false,this::refreshDownloads),lp(-1,-2,12));
    }
    private void episodeCard(Library.Episode e) {
        LinearLayout c=card();
        LinearLayout top=row();top.addView(cover(e.image),lp(66,66,0));
        LinearLayout info=column();info.setPadding(dp(12),0,0,0);
        info.addView(label(e.title,15,WHITE,true));
        info.addView(label(e.feedTitle,11,MUTED,false),lp(-1,-2,4));
        String status=DownloadHelper.isDownloaded(e)?"✓ محفوظة":DownloadHelper.isPending(e)?"↓ جارٍ التحميل":e.completed?"✓ مكتملة":"";
        if(e.durationSeconds>0)status+="  ·  "+time(e.durationSeconds*1000L);
        info.addView(label(status,11,DownloadHelper.isDownloaded(e)?TEAL:MUTED,false),lp(-1,-2,6));
        top.addView(info,new LinearLayout.LayoutParams(0,-2,1));c.addView(top);
        LinearLayout actions=row();
        actions.addView(button("▶ استماع",true,()->play(e)),new LinearLayout.LayoutParams(0,dp(42),1));
        String text=DownloadHelper.isDownloaded(e)?"حذف الملف":DownloadHelper.isPending(e)?"إلغاء التحميل":"↓ تحميل";
        actions.addView(button(text,false,()->{
            if(DownloadHelper.isDownloaded(e)||DownloadHelper.isPending(e)){
                DownloadHelper.remove(this,e);drawPage();
            }else{
                try{DownloadHelper.enqueue(this,e);toast("بدأ تنزيل الحلقة");drawPage();}
                catch(Exception ex){toast("تعذر التنزيل: "+ex.getMessage());}
            }
        }),new LinearLayout.LayoutParams(0,dp(42),1));
        c.addView(actions,lp(-1,-2,12));
        c.setOnClickListener(v->showInfo(e));
        body.addView(c,lp(-1,-2,11));
    }
    private void showInfo(Library.Episode e) {
        String txt=Html.fromHtml(e.description==null?"":e.description,Html.FROM_HTML_MODE_COMPACT).toString().trim();
        if(txt.length()>900)txt=txt.substring(0,900)+"…";
        new AlertDialog.Builder(this).setTitle(e.title).setMessage(txt.isEmpty()?e.feedTitle:txt)
                .setPositiveButton("استماع",(d,w)->play(e))
                .setNeutralButton("نسخ رابط الحلقة",(d,w)->{
                    ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(android.content.ClipData.newPlainText("Podcast",e.audio));
                    toast("تم نسخ رابط الصوت");
                }).setNegativeButton("إغلاق",null).show();
    }
    private void settings() {
        heading("الإعدادات","تحكم في التخزين والتحديث واستهلاك البيانات");
        toggle("تحديث RSS دوريًا","كل ست ساعات تقريبًا وفق سياسات أندرويد","auto_refresh",true);
        toggle("التحميل التلقائي للحلقات الجديدة","حتى حلقتين لكل اشتراك عند ظهور حلقات جديدة","auto_download",false);
        toggle("التحميل عبر Wi-Fi فقط","لعمليات التحميل القادمة","wifi_only",true);
        toggle("حذف الملف بعد الاستماع","عند اكتمال تشغيل الحلقة المحمّلة","auto_delete",false);
        LinearLayout about=card();
        about.addView(label("بودكاست BAS — الإصدار 1.0",16,GOLD,true));
        about.addView(label("بياناتك محفوظة على الهاتف. لا تسجيل دخول، لا إعلانات، لا تتبع. روابط RSS الخاصة التي تتطلب مصادقة غير مدعومة.",13,MUTED,false),lp(-1,-2,12));
        body.addView(about,lp(-1,-2,20));
    }
    @SuppressWarnings("deprecation")
    private void toggle(String title,String detail,String key,boolean def) {
        LinearLayout c=row();c.setPadding(dp(13),dp(13),dp(13),dp(13));c.setBackground(bg(CARD,17));
        LinearLayout labels=column();
        labels.addView(label(title,15,WHITE,true));
        labels.addView(label(detail,12,MUTED,false),lp(-1,-2,5));
        c.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch control=new Switch(this);control.setChecked(Library.setting(this,key,def));
        control.setOnCheckedChangeListener((b,on)->{
            Library.setSetting(this,key,on);
            if(key.equals("auto_refresh"))FeedSyncJob.schedule(this);
        });
        c.addView(control);body.addView(c,lp(-1,-2,12));
    }
    private void addFeedDialog(String initial) {
        EditText input=new EditText(this);input.setSingleLine(false);input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(initial);input.setHint("https://example.com/podcast.rss");
        input.setPadding(dp(14),dp(15),dp(14),dp(15));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("إضافة رابط RSS")
                .setView(input).setNegativeButton("إلغاء",null).setPositiveButton("إضافة",null).create();
        dialog.setOnShowListener(w->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String url=input.getText().toString().trim();
            if(!(url.startsWith("https://")||url.startsWith("http://"))){
                input.setError("أدخل رابط RSS يبدأ بـ https:// أو http://");return;
            }
            if(busy){toast("انتظر اكتمال التحديث الحالي");return;}
            busy=true;dialog.dismiss();toast("جارٍ قراءة RSS…");
            IO.execute(()->{
                try{
                    Library.ParsedFeed parsed=RssReader.fetch(url);
                    Library.merge(this,parsed);
                    ui.post(()->{busy=false;page="library";draw();toast("أُضيف «"+parsed.feed.title+"» — "+parsed.episodes.size()+" حلقة");});
                }catch(Exception e){ui.post(()->{busy=false;toast("فشل قراءة RSS: "+e.getMessage());});}
            });
        }));
        dialog.show();
    }
    private void refreshOne(String url) {
        if(busy){toast("التحديث جارٍ بالفعل");return;}
        busy=true;
        IO.execute(()->{
            try{
                List<Library.Episode> fresh=Library.merge(this,RssReader.fetch(url));
                DownloadHelper.autoDownload(this,fresh);
                ui.post(()->{busy=false;drawPage();toast("حلقات جديدة: "+fresh.size());});
            }catch(Exception e){ui.post(()->{busy=false;toast("فشل التحديث: "+e.getMessage());});}
        });
    }
    private void refreshAll() {
        if(busy){toast("التحديث جارٍ بالفعل");return;}
        busy=true;
        IO.execute(()->{
            int added=0,errors=0;
            for(Library.Feed f:Library.feeds(this)){
                try{
                    List<Library.Episode> fresh=Library.merge(this,RssReader.fetch(f.url));
                    DownloadHelper.autoDownload(this,fresh);added+=fresh.size();
                }catch(Exception e){errors++;}
            }
            final int n=added,bad=errors;
            ui.post(()->{busy=false;drawPage();toast(n+" حلقة جديدة"+(bad>0?"؛ تعذر تحديث "+bad:""));});
        });
    }
    private void unsubscribe(Library.Feed f) {
        new AlertDialog.Builder(this).setTitle("إلغاء الاشتراك")
                .setMessage("هل تريد حذف اشتراك «"+f.title+"» وحلقاته المحمّلة؟")
                .setNegativeButton("إلغاء",null).setPositiveButton("حذف",(d,w)->{
                    for(Library.Episode e:Library.episodes(this))if(e.feedUrl.equals(f.url))DownloadHelper.remove(this,e);
                    Library.unsubscribe(this,f.url);page="library";selectedFeed="";draw();
                }).show();
    }
    private void refreshDownloads() {
        IO.execute(()->{
            DownloadHelper.refreshStatus(this);
            ui.post(()->{if(page.equals("downloads")||page.equals("feed")||page.equals("recent"))drawPage();});
        });
    }
    private ImageView cover(String url) {
        ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(bg(CARD2,13));img.setClipToOutline(true);img.setTag(url);
        if(url==null||url.isEmpty())return img;
        Bitmap cached=CACHE.get(url);
        if(cached!=null){img.setImageBitmap(cached);return img;}
        ART.execute(()->{
            HttpURLConnection connection=null;
            try{
                URL target=new URL(url);
                if(!target.getProtocol().equalsIgnoreCase("https")&&!target.getProtocol().equalsIgnoreCase("http"))return;
                connection=(HttpURLConnection)target.openConnection();
                connection.setConnectTimeout(9000);connection.setReadTimeout(9000);
                if(connection.getContentLengthLong()>5_000_000L)return;
                try(InputStream stream=connection.getInputStream()){
                    BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;
                    Bitmap image=BitmapFactory.decodeStream(stream,null,o);
                    if(image!=null){CACHE.put(url,image);ui.post(()->{
                        if(url.equals(img.getTag()))img.setImageBitmap(image);
                    });}
                }
            }catch(Exception ignored){}finally{if(connection!=null)connection.disconnect();}
        });
        return img;
    }
    private void play(Library.Episode episode) {
        if(controller==null){waiting=episode;toast("جارٍ تجهيز المشغل…");return;}
        Library.Episode e=Library.get(this,episode.key);if(e==null)e=episode;
        String source=DownloadHelper.isDownloaded(e)?Uri.fromFile(new File(e.localFile)).toString():e.audio;
        if(source==null||source.isEmpty()){toast("الحلقة لا تحتوي على ملف صوتي");return;}
        MediaMetadata.Builder metadata=new MediaMetadata.Builder().setTitle(e.title).setArtist(e.feedTitle);
        if(!e.image.isEmpty())metadata.setArtworkUri(Uri.parse(e.image));
        MediaItem item=new MediaItem.Builder().setMediaId(e.key).setUri(source)
                .setMediaMetadata(metadata.build()).build();
        controller.setMediaItem(item);controller.prepare();
        if(e.positionMs>0&&!e.completed)controller.seekTo(e.positionMs);
        controller.play();updatePlayer();
    }
    private void updatePlayer() {
        if(miniPlayer==null)return;
        miniPlayer.removeAllViews();seek=null;timing=null;
        if(controller==null||controller.getCurrentMediaItem()==null)return;
        MediaItem item=controller.getCurrentMediaItem();
        LinearLayout c=column();c.setPadding(dp(12),dp(9),dp(12),dp(9));c.setBackground(bg(CARD,16));
        String title=item.mediaMetadata.title==null?"حلقة صوتية":item.mediaMetadata.title.toString();
        c.addView(label(title,14,WHITE,true));
        LinearLayout buttons=row();
        buttons.addView(playerButton("↺15",()->controller.seekTo(Math.max(0,controller.getCurrentPosition()-15000))));
        buttons.addView(playerButton(controller.isPlaying()?"Ⅱ":"▶",()->{
            if(controller.isPlaying())controller.pause();else controller.play();
        }));
        buttons.addView(playerButton("15↻",()->controller.seekTo(controller.getCurrentPosition()+15000)));
        String speed=String.format(Locale.US,"%.2g×",controller.getPlaybackParameters().speed);
        buttons.addView(playerButton(speed,()->{
            float x=controller.getPlaybackParameters().speed;
            controller.setPlaybackSpeed(x<1?1:x<1.25f?1.25f:x<1.5f?1.5f:x<2?2:0.75f);
            updatePlayer();
        }));
        c.addView(buttons,lp(-1,-2,5));
        seek=new SeekBar(this);seek.setMax(100);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(TEAL));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int p,boolean user){}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){
                if(controller!=null)controller.seekTo((long)b.getProgress()*1000L);
            }
        });
        c.addView(seek);
        timing=label("00:00 / 00:00",10,MUTED,false);c.addView(timing);
        miniPlayer.addView(c);
    }
    private TextView playerButton(String s,Runnable task) {
        TextView v=label(s,16,TEAL,true);v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8),dp(9),dp(8),dp(9));
        v.setOnClickListener(w->task.run());return v;
    }
    private String time(long ms) {
        if(ms<0||ms==androidx.media3.common.C.TIME_UNSET)return "--:--";
        long s=ms/1000,h=s/3600,m=(s%3600)/60;
        return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,s%60)
                :String.format(Locale.US,"%02d:%02d",m,s%60);
    }
    @Override public void onBackPressed() {
        if(page.equals("feed")){page="library";selectedFeed="";draw();}
        else if(!page.equals("library")){page="library";draw();}
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(tick);
        if(controller!=null)controller.removeListener(listener);
        if(future!=null)MediaController.releaseFuture(future);
        super.onDestroy();
    }
}
