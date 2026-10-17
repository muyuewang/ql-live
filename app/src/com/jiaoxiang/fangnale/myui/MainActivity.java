package com.jiaoxiang.fangnale.myui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

public class MainActivity extends Activity implements PlayerEngine.Listener {

    private static final int PANEL_NONE = 0;
    private static final int PANEL_LEFT = 1;
    private static final int PANEL_RIGHT = 2;


    private static final int COL_BG = 0x8C0C0D11;
    private static final int COL_RED = 0xFFF03E3E;
    private static final int COL_YELLOW = 0xFFFFC93C;
    private static final int COL_TXT = 0xFFE8E8EC;
    private static final int COL_GRAY = 0xFF9A9AA2;
    private static final int COL_PILL = 0x24FFFFFF;
    private static final int COL_DLG_BG = 0xF20C0D11;

    private FrameLayout root;
    private SurfaceView surfaceView;
    private GestureView gesture;
    private LinearLayout osdBox;
    private TextView osdNum, osdName, osdStatus;
    private TextView slogan;
    private TextView indicator;

    private LinearLayout leftPanel;
    private ListView groupList;
    private ListView channelList;
    private LinearLayout rightPanel;
    private TextView btnLinePrev, btnLineNext, lineLabel, lineName;
    private ArrayList<TextView> aspectPills = new ArrayList<TextView>();
    private TextView decodeLabel;

    private PlayerEngine engine;
    private ChannelStore store;
    private AudioManager audio;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ArrayList<String> groupNames = new ArrayList<String>();
    private int curGroupIdx = 0;
    private ArrayList<ChannelStore.Channel> curChannels = new ArrayList<ChannelStore.Channel>();
    private int curChannelIdx = -1;
    private int curLineIdx = 0;
    private ArrayList<String> curLines = new ArrayList<String>();
    private ArrayList<String> curLineLabels = new ArrayList<String>();

    private ChannelStore.Channel curPlayCh; // currently playing channel (independent of list index)
    private int panelState = PANEL_NONE;
    private long lastBackAt;
    private boolean loadingChannels = true;
    private int playGen = 0; // guard against stale async source callbacks

    private final Runnable hideOsd = new Runnable() {
        public void run() { osdBox.setVisibility(View.INVISIBLE); slogan.setVisibility(View.INVISIBLE); }
    };
    private final Runnable hideIndicator = new Runnable() {
        public void run() { indicator.setVisibility(View.INVISIBLE); }
    };

    // ---------------- lifecycle ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"}, 100);
            }
        } catch (Throwable ignored) {}

        store = new ChannelStore(this);
        store.ensureSeeded();
        copyEngineJs();
        engine = new PlayerEngine(this, this);

        buildUi();
        loadChannelsAsync();
        restorePrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.destroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
    }

    // ---------------- ui helpers ----------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView newText(String text, float sizeSp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** pill-style button */
    private TextView pill(String text, float sizeSp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(COL_TXT);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(roundBg(COL_PILL, 20));
        tv.setPadding(dp(16), 0, dp(16), 0);
        return tv;
    }

    private void setPillActive(TextView tv, boolean active) {
        if (active) {
            tv.setBackground(roundBg(COL_RED, 20));
            tv.setTextColor(Color.WHITE);
        } else {
            tv.setBackground(roundBg(COL_PILL, 20));
            tv.setTextColor(COL_TXT);
        }
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(COL_GRAY);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(4), 0, 0, dp(6));
        return tv;
    }

    // ---------------- styled dialog (unified dark theme, remote friendly) ----------------

    /** dark rounded card + red-accent title + pill buttons; returns shown-or-not Dialog */
    private android.app.Dialog styledDialog(String title, View body,
            String posText, final Runnable posAction,
            String negText, final Runnable negAction,
            String neutralText, final Runnable neutralAction) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.65f);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg(COL_DLG_BG, 16));

        // title row: red accent bar + bold title
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(18), dp(14), dp(18), dp(14));
        View bar = new View(this);
        bar.setBackground(roundBg(COL_RED, 2));
        titleRow.addView(bar, lp(dp(4), dp(16)));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(COL_TXT);
        t.setPadding(dp(10), 0, 0, 0);
        titleRow.addView(t);
        card.addView(titleRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View div = new View(this);
        div.setBackgroundColor(0x1AFFFFFF);
        card.addView(div, lp(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout bodyWrap = new LinearLayout(this);
        bodyWrap.setOrientation(LinearLayout.VERTICAL);
        bodyWrap.setPadding(dp(18), dp(12), dp(18), dp(4));
        // respect caller-set height (e.g. capped ScrollView), else wrap content
        ViewGroup.LayoutParams ex = body.getLayoutParams();
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ex != null && ex.height > 0) ? ex.height : ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyWrap.addView(body, blp);
        card.addView(bodyWrap, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(dp(14), dp(8), dp(14), dp(14));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(38), 1);
        bp.setMargins(dp(6), 0, dp(6), 0);
        if (neutralText != null) btnRow.addView(dlgBtn(neutralText, false, dlg, neutralAction), new LinearLayout.LayoutParams(bp));
        if (negText != null) btnRow.addView(dlgBtn(negText, false, dlg, negAction), new LinearLayout.LayoutParams(bp));
        if (posText != null) {
            final TextView posBtn = dlgBtn(posText, true, dlg, posAction);
            btnRow.addView(posBtn, new LinearLayout.LayoutParams(bp));
            dlg.setOnShowListener(new DialogInterface.OnShowListener() {
                public void onShow(DialogInterface d) { posBtn.requestFocus(); }
            });
        }
        card.addView(btnRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout rootF = new FrameLayout(this);
        int w = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420));
        rootF.addView(card, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dlg.setContentView(rootF);
        return dlg;
    }

    /** dialog pill button: primary = red filled; focus state brightens (TV remote) */
    private TextView dlgBtn(String text, final boolean primary, final android.app.Dialog dlg, final Runnable action) {
        final TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setBackground(roundBg(primary ? COL_RED : COL_PILL, 19));
        b.setTextColor(primary ? Color.WHITE : COL_TXT);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) {
                    b.setBackground(roundBg(primary ? 0xFFFF6B6B : 0x42FFFFFF, 19));
                    b.setTextColor(Color.WHITE);
                } else {
                    b.setBackground(roundBg(primary ? COL_RED : COL_PILL, 19));
                    b.setTextColor(primary ? Color.WHITE : COL_TXT);
                }
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                if (action != null) action.run();
            }
        });
        return b;
    }

    /** clickable list row inside a dialog (line switcher, DLNA devices) */
    private TextView dlgRow(String text, boolean active) {
        final TextView r = new TextView(this);
        r.setText(text);
        r.setTextSize(15);
        r.setTextColor(active ? COL_RED : COL_TXT);
        r.setFocusable(true);
        r.setFocusableInTouchMode(true);
        r.setBackground(roundBg(active ? 0x1AF03E3E : 0x00000000, 8));
        r.setPadding(dp(10), dp(9), dp(10), dp(9));
        r.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                boolean act = r.getText().toString().contains("（当前）") || r.getText().toString().startsWith("▶");
                r.setBackground(roundBg(has ? 0x2AFFFFFF : (act ? 0x1AF03E3E : 0x00000000), 8));
            }
        });
        return r;
    }

    private TextView dlgMessage(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(COL_TXT);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, dp(2), 0, dp(8));
        return tv;
    }

    // ---------------- ui build ----------------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // original app renders into a SurfaceView (player_surface_vlc + setDisplay(SurfaceHolder)).
        // SurfaceView uses hardware overlay path; TextureView re-composites every frame on GPU and stutters
        // on weak devices/TV boxes.
        surfaceView = new SurfaceView(this);
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        surfaceView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            public void surfaceCreated(android.view.SurfaceHolder h) {
                engine.setSurface(h.getSurface());
                surfaceView.post(new Runnable() { public void run() { applyAspect(); } });
            }
            public void surfaceChanged(android.view.SurfaceHolder h, int format, int w, int hh) {
                surfaceView.post(new Runnable() { public void run() { applyAspect(); } });
            }
            public void surfaceDestroyed(android.view.SurfaceHolder h) {
                engine.setSurface(null);
            }
        });
        root.addView(surfaceView);

        gesture = new GestureView(this);
        gesture.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(gesture);

        buildOsd();
        buildIndicator();
        buildLeftPanel();
        buildRightPanel();

        setContentView(root);
    }

    private void buildOsd() {
        // big channel number + name + status (centered, TV-home style)
        osdBox = new LinearLayout(this);
        osdBox.setOrientation(LinearLayout.VERTICAL);
        osdBox.setGravity(Gravity.CENTER);
        // full-width box + centered children: width never shifts when status text changes
        FrameLayout.LayoutParams obLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        osdBox.setLayoutParams(obLp);
        osdBox.setVisibility(View.INVISIBLE);

        osdNum = new TextView(this);
        osdNum.setTextSize(88);
        osdNum.setTypeface(null, android.graphics.Typeface.BOLD);
        osdNum.setTextColor(Color.WHITE);
        osdNum.setShadowLayer(8, 2, 3, 0xAA000000);
        osdNum.setGravity(Gravity.CENTER);
        osdBox.addView(osdNum);

        osdName = new TextView(this);
        osdName.setTextSize(26);
        osdName.setTextColor(Color.WHITE);
        osdName.setShadowLayer(6, 2, 2, 0xAA000000);
        osdName.setGravity(Gravity.CENTER);
        osdName.setPadding(0, dp(2), 0, 0);
        osdBox.addView(osdName);

        osdStatus = new TextView(this);
        osdStatus.setTextSize(15);
        osdStatus.setTextColor(0xCCBBBBBE);
        osdStatus.setShadowLayer(4, 1, 1, 0x99000000);
        osdStatus.setGravity(Gravity.CENTER);
        osdStatus.setPadding(0, dp(10), 0, 0);
        osdBox.addView(osdStatus);

        root.addView(osdBox);

        slogan = newText("轻流直播 · 无广告观看", 13, 0x66FFFFFF);
        FrameLayout.LayoutParams slLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER);
        slLp.bottomMargin = dp(24);
        slogan.setLayoutParams(slLp);
        slogan.setVisibility(View.INVISIBLE);
        root.addView(slogan);
    }

    private void buildIndicator() {
        indicator = newText("", 18, Color.WHITE);
        indicator.setShadowLayer(4, 2, 2, Color.BLACK);
        indicator.setBackground(roundBg(0x66000000, 10));
        indicator.setPadding(dp(16), dp(8), dp(16), dp(8));
        FrameLayout.LayoutParams indLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        indicator.setLayoutParams(indLp);
        indicator.setVisibility(View.INVISIBLE);
        root.addView(indicator);
    }

    private void buildLeftPanel() {
        leftPanel = new LinearLayout(this);
        leftPanel.setOrientation(LinearLayout.VERTICAL);
        leftPanel.setBackgroundColor(COL_BG);
        int panelW = (int) (getResources().getDisplayMetrics().widthPixels * 0.40f);
        FrameLayout.LayoutParams lpL = new FrameLayout.LayoutParams(panelW,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT);
        leftPanel.setLayoutParams(lpL);
        leftPanel.setVisibility(View.GONE);

        // groups + channels
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        groupList = new ListView(this);
        groupList.setCacheColorHint(0);
        groupList.setDivider(null);
        groupList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        groupList.setAdapter(new GroupAdapter());
        groupList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { selectGroup(pos); }
        });
        row.addView(groupList, new LinearLayout.LayoutParams((int) (panelW * 0.32f), ViewGroup.LayoutParams.MATCH_PARENT));

        channelList = new ListView(this);
        channelList.setCacheColorHint(0);
        channelList.setDivider(null);
        channelList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        channelList.setAdapter(new ChannelAdapter());
        channelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { playChannelAt(pos); }
        });
        row.addView(channelList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        leftPanel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(leftPanel);
    }

    private void buildRightPanel() {
        rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        rightPanel.setBackgroundColor(COL_BG);
        rightPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        FrameLayout.LayoutParams lpR = new FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.36f),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
        rightPanel.setLayoutParams(lpR);
        rightPanel.setVisibility(View.GONE);

        // content column inside a scroll
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        rightContent = content;

        // ---- 播放控制 ----
        TextView ctlLabel = sectionLabel("播放控制");
        ctlLabel.setPadding(dp(4), dp(4), 0, dp(6));
        rightContent.addView(ctlLabel);

        addCtrlBtn("节目单（EPG）", new Runnable() { public void run() { showEpgPanel(); }});
        addCtrlBtn("投屏到电视", new Runnable() { public void run() { showCastPanel(); }});
        addCtrlBtn("收藏本台", new Runnable() { public void run() { toggleFav(); }});
        addCtrlBtn("自定义源", new Runnable() { public void run() { showCustomSourceDialog(); }});
        addCtrlBtn("更新节目源", new Runnable() { public void run() {
            toast("正在重新加载...");
            loadChannelsAsync();
            hidePanels();
        }});
        addCtrlBtn("退出应用", new Runnable() { public void run() { confirmExit(); }});

        // ---- 解码模式（与线路同款箭头样式）----
        TextView decLabelSec = sectionLabel("解码模式");
        decLabelSec.setPadding(dp(4), dp(14), 0, dp(6));
        rightContent.addView(decLabelSec);

        LinearLayout decRow = new LinearLayout(this);
        decRow.setOrientation(LinearLayout.HORIZONTAL);
        decRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnDecPrev = circleBtn("‹");
        btnDecPrev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchDecode(-1); }
        });
        decRow.addView(btnDecPrev, lp(dp(64), dp(40)));

        decodeLabel = newText("", 16, COL_TXT);
        decodeLabel.setGravity(Gravity.CENTER);
        decRow.addView(decodeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnDecNext = circleBtn("›");
        btnDecNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchDecode(1); }
        });
        decRow.addView(btnDecNext, lp(dp(64), dp(40)));

        rightContent.addView(decRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshDecodeLabel();

        // ---- 线路 ----
        TextView lineLabelLp = sectionLabel("线路");
        lineLabelLp.setPadding(dp(4), dp(14), 0, dp(6));
        rightContent.addView(lineLabelLp);

        LinearLayout lineRow = new LinearLayout(this);
        lineRow.setOrientation(LinearLayout.HORIZONTAL);
        lineRow.setGravity(Gravity.CENTER_VERTICAL);

        btnLinePrev = circleBtn("‹");
        btnLinePrev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchLine(-1); }
        });
        lineRow.addView(btnLinePrev, lp(dp(64), dp(40)));

        LinearLayout lineCenter = new LinearLayout(this);
        lineCenter.setOrientation(LinearLayout.VERTICAL);
        lineCenter.setGravity(Gravity.CENTER);
        lineLabel = newText("-/-", 16, COL_TXT);
        lineCenter.addView(lineLabel);
        lineName = newText("", 12, COL_GRAY);
        lineCenter.addView(lineName);
        lineRow.addView(lineCenter, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnLineNext = circleBtn("›");
        btnLineNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchLine(1); }
        });
        lineRow.addView(btnLineNext, lp(dp(64), dp(40)));

        rightContent.addView(lineRow, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 画面比例 ----
        TextView aspLabel = sectionLabel("画面比例");
        aspLabel.setPadding(dp(4), dp(14), 0, dp(6));
        rightContent.addView(aspLabel);

        LinearLayout aspRow = new LinearLayout(this);
        aspRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"适应", "填充", "16:9", "4:3", "拉伸"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout.LayoutParams apl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            if (i > 0) apl.leftMargin = dp(8);
            TextView p = pill(names[i], 13);
            final int idx = i;
            p.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    engine.setAspect(idx);
                    prefs().edit().putInt("aspect", idx).apply();
                    refreshAspectPills();
                }
            });
            p.setLayoutParams(apl);
            aspectPills.add(p);
            aspRow.addView(p);
        }
        refreshAspectPills();
        rightContent.addView(aspRow);

        // wrap content in scroll, inside the full-height right panel
        rightContent = content;
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rightPanel.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(rightPanel);
    }

    private LinearLayout rightContent;

    private void refreshDecodeLabel() {
        String m = engine.getDecodeMode();
        decodeLabel.setText("ijk1".equals(m) ? "硬解" : "ijk0".equals(m) ? "软解" : "系统播放器");
    }

    private void switchDecode(int delta) {
        String[] modes = {"ijk1", "ijk0", "media"}; // 硬解/软解/系统播放器
        String cur = engine.getDecodeMode();
        int idx = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(cur)) idx = i;
        int next = ((idx + delta) % modes.length + modes.length) % modes.length;
        String nm = modes[next];
        engine.setDecodeMode(nm);
        prefs().edit().putString("decode", nm).apply();
        refreshDecodeLabel();
        toast("ijk1".equals(nm) ? "已切换为硬解" : "ijk0".equals(nm) ? "已切换为软解" : "已切换为系统播放器");
        // replay current channel with new decoder
        ChannelStore.Channel ch = curPlayCh != null ? curPlayCh : curChannel();
        if (ch != null && !curLines.isEmpty()) {
            int idx2 = curLineIdx;
            final int gen = ++playGen;
            engine.setChannelLines(curLines, idx2);
            startPlay(gen, ch, curLines, idx2);
        }
    }

    private void refreshAspectPills() {
        int cur = engine.getAspect();
        for (int i = 0; i < aspectPills.size(); i++) setPillActive(aspectPills.get(i), i == cur);
    }

    private TextView circleBtn(String arrow) {
        TextView b = new TextView(this);
        b.setText(arrow);
        b.setTextSize(22);
        b.setTextColor(COL_TXT);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x22FFFFFF);
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(20));
        b.setBackground(d);
        return b;
    }

    private String ctrlTextFor(String label) { return label; }

    private void addCtrlBtn(String label, final Runnable action) {
        addCtrlBtn2(label, action);
    }

    /** returns the button so caller can keep a reference */
    private TextView addCtrlBtn2(String label, final Runnable action) {
        TextView b = pill(label, 14);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        bpl.topMargin = dp(2);
        b.setLayoutParams(bpl);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) { v.setBackground(roundBg(0x55FFFFFF, 20)); }
                else { v.setBackground(roundBg(COL_PILL, 20)); }
            }
        });
        rightContent.addView(b);
        return b;
    }

    private void copyEngineJs() {
        try {
            String[] names = {"encrypt_root.js", "encrypt_migu_live.js", "encrypt_other_live.js",
                    "native_layer.js", "jquery-2.1.1.min.js"};
            for (String n : names) {
                java.io.File f = new java.io.File(getFilesDir(), n);
                if (f.exists() && f.length() > 0) continue;
                java.io.InputStream in = getAssets().open("myui_js/" + n);
                java.io.OutputStream out = new java.io.FileOutputStream(f);
                byte[] buf = new byte[65536];
                int c;
                while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
                in.close();
                out.close();
            }
        } catch (Throwable t) {
            // engine will fall back to its own paths
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("myui", 0);
    }

    // ---------------- data ----------------

    private void loadChannelsAsync() {
        loadingChannels = true;
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                for (int i = 0; i < 30 && !ok; i++) {
                    ok = store.reload();
                    if (!ok) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
                    }
                }
                final boolean found = ok;
                ui.post(new Runnable() {
                    public void run() {
                        loadingChannels = false;
                        if (found) {
                            buildGroupList();
                            ((GroupAdapter) groupList.getAdapter()).notifyDataSetChanged();
                            // boot autoplay: first channel starting from 收藏, walking down groups
                            ChannelStore.Channel ch = null;
                            for (int i = 0; i < groupNames.size() && ch == null; i++) {
                                ArrayList<ChannelStore.Channel> list;
                                String g = groupNames.get(i);
                                if ("自定义".equals(g)) list = CustomSource.loadChannels(MainActivity.this);
                                else if ("收藏".equals(g)) list = store.getFavorites();
                                else list = store.getGroup(g);
                                if (!list.isEmpty()) {
                                    selectGroup(i);
                                    ch = list.get(0);
                                }
                            }
                            if (ch != null) playChannelDirect(ch);
                            refreshProvsOnline();
                        }
                    }
                });
            }
        }).start();
    }

    private void setSourceTab(int tab) {
        buildGroupList();
        selectGroup(0);
    }

    private void buildGroupList() {
        ArrayList<String> ordered = new ArrayList<String>();
        ordered.add("收藏");
        ordered.addAll(store.getProvs());
        if (!CustomSource.loadChannels(this).isEmpty()) ordered.add("自定义");
        groupNames = ordered;
    }

    /** group tabs from server (same order as original: 收藏/央视/卫视/高清/轮播/体育/影视/省份...) */
    private void refreshProvsOnline() {
        OnlineSource.fetchProvs(new OnlineSource.ProvsCallback() {
            public void onProvs(final ArrayList<String> provs) {
                ui.post(new Runnable() { public void run() {
                    String keep = curGroupIdx >= 0 && curGroupIdx < groupNames.size()
                            ? groupNames.get(curGroupIdx) : null;
                    ArrayList<String> ordered = new ArrayList<String>(provs);
                    if (!ordered.contains("收藏")) ordered.add(0, "收藏");
                    if (!CustomSource.loadChannels(MainActivity.this).isEmpty()
                            && !ordered.contains("自定义")) ordered.add("自定义");
                    groupNames = ordered;
                    ((GroupAdapter) groupList.getAdapter()).notifyDataSetChanged();
                    int at = keep != null ? ordered.indexOf(keep) : -1;
                    if (at >= 0 && at != curGroupIdx) {
                        curGroupIdx = at;
                        ((GroupAdapter) groupList.getAdapter()).notifyDataSetChanged();
                    }
                }});
            }
            public void onError() { /* keep local groups */ }
        });
    }

    /** fetch group channels + current program online (signed, same as original), merge db urls */
    private void fetchGroupOnline(final String g) {
        OnlineSource.fetchGroup(this, g, new OnlineSource.GroupCallback() {
            public void onChannels(final ArrayList<ChannelStore.Channel> list) {
                ui.post(new Runnable() { public void run() {
                    // stale: user switched to another group meanwhile
                    if (curGroupIdx < 0 || curGroupIdx >= groupNames.size()
                            || !g.equals(groupNames.get(curGroupIdx))) return;
                    // merge playable urls from db
                    for (ChannelStore.Channel c : list) {
                        if (c.nowplay != null && c.nowplay.trim().length() > 0) {
                            epgNowCache.put(c.ename, c.nowplay.trim());
                        }
                        ChannelStore.Channel db = store.findByEname(c.ename);
                        if (db != null) c.urls = db.urls;
                    }
                    curChannels = list;
                    if (curChannelIdx >= curChannels.size()) curChannelIdx = -1;
                    ((ChannelAdapter) channelList.getAdapter()).notifyDataSetChanged();
                }});
            }
            public void onError() { /* keep db list */ }
        });
    }

    private void selectGroup(int idx) {
        if (idx < 0 || idx >= groupNames.size()) idx = 0;
        curGroupIdx = idx;
        String g = groupNames.get(idx);
        if ("自定义".equals(g)) {
            curChannels = CustomSource.loadChannels(this);
        } else if ("收藏".equals(g)) {
            curChannels = store.getFavorites();
        } else {
            curChannels = store.getGroup(g);
            fetchGroupOnline(g);
        }
        ((GroupAdapter) groupList.getAdapter()).notifyDataSetChanged();
        ((ChannelAdapter) channelList.getAdapter()).notifyDataSetChanged();
        channelList.setSelection(0);
        if (curChannelIdx >= curChannels.size()) curChannelIdx = -1;
        prefs().edit().putString("lastGroup", g).apply();
    }

    // ---------------- playback ----------------

    private void restorePrefs() {
        engine.setDecodeMode(prefs().getString("decode", "ijk1")); // 默认 ijk1 硬解，URL 提示可覆盖
        engine.setAspect(prefs().getInt("aspect", PlayerEngine.ASPECT_FIT));
        refreshAspectPills();
        refreshDecodeLabel();
        boolean rot = prefs().getBoolean("rot", false);
        setRequestedOrientation(rot ? 6 : 0);
    }

    private void playChannelAt(int idx) {
        if (idx < 0 || idx >= curChannels.size()) return;
        curChannelIdx = idx;
        ChannelStore.Channel ch = curChannels.get(idx);
        playChannel(ch);
        ((ChannelAdapter) channelList.getAdapter()).notifyDataSetChanged();
    }

    private void playChannel(final ChannelStore.Channel ch) {
        final int gen = ++playGen;
        curPlayCh = ch;
        curPlayCh = ch;
        ArrayList<String> dbLines = ch.getLines();
        curLineIdx = 0;
        curLines = new ArrayList<String>(dbLines);
        curLineLabels = buildLabels(dbLines);
        engine.setChannelLines(curLines, 0);
        showBigOsd(ch, "正在获取线路...");
        prefs().edit().putString("lastGroup", groupNames.get(curGroupIdx))
                .putString("lastEname", ch.ename).apply();
        hidePanels();

        if (ch.ename != null && ch.ename.startsWith("custom_")) {
            startPlay(gen, ch, curLines, 0);
            return;
        }
        // online fresh lines first (same as original app), db lines as fallback
        OnlineSource.fetch(this, ch.ename, new OnlineSource.Callback() {
            public void onLines(final ArrayList<String> live, final ArrayList<String> labels) {
                ui.post(new Runnable() { public void run() {
                    if (gen != playGen) return; // stale
                    ArrayList<String> lines = new ArrayList<String>();
                    lines.addAll(live);
                    if (lines.isEmpty()) lines.addAll(ch.getLines());
                    curLines = lines;
                    curLineLabels = labels;
                    curLineIdx = 0;
                    engine.setChannelLines(lines, 0);
                    startPlay(gen, ch, lines, 0);
                }});
            }
            public void onError() {
                ui.post(new Runnable() { public void run() {
                    if (gen != playGen) return;
                    ArrayList<String> lines = ch.getLines();
                    if (lines.isEmpty()) {
                        showBigOsd(ch, "该频道暂无可用线路");
                        return;
                    }
                    curLines = lines;
                    curLineLabels = buildLabels(lines);
                    curLineIdx = 0;
                    engine.setChannelLines(lines, 0);
                    startPlay(gen, ch, lines, 0);
                }});
            }
        });
        // network watchdog: fall back to db lines if online fetch hangs
        ui.postDelayed(new Runnable() { public void run() {
            if (gen == playGen && !engine.isBusyParsing() && engine.getCurrentUrl() == null) {
                ArrayList<String> lines = ch.getLines();
                if (!lines.isEmpty()) {
                    curLines = lines;
                    engine.setChannelLines(lines, 0);
                    startPlay(gen, ch, lines, 0);
                }
            }
        }}, 7000);
    }

    private void startPlay(int gen, ChannelStore.Channel ch, ArrayList<String> lines, int lineIdx) {
        if (gen != playGen) return;
        curLineIdx = lineIdx;
        renderedOnce = false;
        engine.playLine(lines.get(lineIdx));
        refreshLineLabel(ch);
    }

    private void playChannelDirect(ChannelStore.Channel ch) {
        // restore path: channel may not be in current group list
        curChannelIdx = -1;
        playChannel(ch);
    }

    /** resolve the list index of the actually-playing channel (restore/digit path may leave curChannelIdx=-1) */
    private int baseChannelIdx() {
        if (curChannelIdx >= 0 && curChannelIdx < curChannels.size()) return curChannelIdx;
        if (curPlayCh != null) {
            for (int i = 0; i < curChannels.size(); i++) {
                if (curChannels.get(i).ename != null && curChannels.get(i).ename.equals(curPlayCh.ename)) return i;
            }
        }
        return 0;
    }

    private void switchChannel(int delta) {
        if (curChannels.isEmpty()) return;
        int n = curChannels.size();
        playChannelAt(((baseChannelIdx() + delta) % n + n) % n);
    }

    private void switchLine(int delta) {
        ChannelStore.Channel ch = curPlayCh != null ? curPlayCh : curChannel();
        if (ch == null || curLines.isEmpty()) return;
        int n = curLines.size();
        int idx = ((curLineIdx + delta) % n + n) % n;
        final int gen = ++playGen;
        curLines = new ArrayList<String>(curLines); // keep same list
        engine.setChannelLines(curLines, idx);
        startPlay(gen, ch, curLines, idx);
        showBigOsd(ch, null);
        refreshLineLabel(ch);
    }

    private ChannelStore.Channel curChannel() {
        if (curChannelIdx >= 0 && curChannelIdx < curChannels.size()) return curChannels.get(curChannelIdx);
        return null;
    }

    private void toggleFav() {
        ChannelStore.Channel ch = curPlayCh != null ? curPlayCh : curChannel();
        if (ch == null) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs().getString("favs", "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                if (ch.ename.equals(arr.optString(i))) removed = true;
                else out.put(arr.get(i));
            }
            if (!removed) out.put(ch.ename);
            prefs().edit().putString("favs", out.toString()).apply();
            toast(removed ? "已取消收藏" : "已收藏");
        } catch (Throwable ignored) {}
    }

    private void saveState() {
        ChannelStore.Channel ch = curChannel();
        if (ch != null) prefs().edit().putString("lastEname", ch.ename).apply();
    }

    // ---------------- engine callbacks ----------------

    private boolean renderedOnce;

    public void onRendering() {
        renderedOnce = true;
        ui.post(new Runnable() { public void run() {
            showBigOsdStatus("播放中");
            for (int delay : new int[]{800, 2000, 4000}) {
                ui.postDelayed(new Runnable() { public void run() {
                    engine.fetchVideoSize();
                    applyAspect();
                }}, delay);
            }
            ui.removeCallbacks(hideOsd);
            ui.postDelayed(hideOsd, 2500);
        }});
    }

    public void onFailed(final String reason) {
        ui.post(new Runnable() { public void run() {
            showBigOsd(curChannel(), "播放失败：" + reason);
            // 线路全部失败时弹窗，可重试或退出
            styledDialog("播放失败",
                    dlgMessage("抱歉，当前频道线路异常，请稍后重试或切换频道！"),
                    "重试", new Runnable() { public void run() {
                        ChannelStore.Channel ch = curPlayCh != null ? curPlayCh : curChannel();
                        if (ch != null) playChannelAt(baseChannelIdx());
                    }},
                    "退出", new Runnable() { public void run() { finishAffinity(); }},
                    null, null).show();
        }});
    }

    public void onBuffering(final boolean start) {
        ui.post(new Runnable() { public void run() {
            if (start) showBigOsdStatus("缓冲中...");
            else if (renderedOnce) showBigOsdStatus("播放中"); // 缓冲结束收回提示
        }});
    }

    public void onVideoSize(int w, int h) {
        applyAspect();
    }

    private void showBigOsd(ChannelStore.Channel ch, String status) {
        int num = curChannelIdx >= 0 ? curChannelIdx + 1 : 1;
        osdNum.setText(String.format("%02d", num));
        if (ch != null) osdName.setText(ch.name);
        showBigOsdStatus(status);
        osdBox.setVisibility(View.VISIBLE);
        slogan.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
    }

    private void showBigOsdStatus(String status) {
        if (status == null) status = "";
        osdStatus.setText(status);
        if (osdBox.getVisibility() != View.VISIBLE) {
            osdBox.setVisibility(View.VISIBLE);
            slogan.setVisibility(View.VISIBLE);
        }
        if (status.length() == 0 || "播放中".equals(status)) {
            ui.removeCallbacks(hideOsd);
            ui.postDelayed(hideOsd, 2500);
        }
    }

    private ArrayList<String> buildLabels(ArrayList<String> lines) {
        ArrayList<String> out = new ArrayList<String>();
        for (String l : lines) out.add(OnlineSource.lineLabel(l));
        return out;
    }

    private void refreshLineLabel(ChannelStore.Channel ch) {
        int n = curLines.size();
        lineLabel.setText("线路 " + (curLineIdx + 1) + "/" + n);
        String name = curLineIdx < curLineLabels.size() ? curLineLabels.get(curLineIdx) : "";
        if (name == null || name.length() == 0)
            name = OnlineSource.lineLabel(curLines.get(curLineIdx));
        lineName.setText(name);
    }

    private void applyAspect() {
        int vw = engine.getVideoWidth(), vh = engine.getVideoHeight();
        int pw = root.getWidth(), ph = root.getHeight();
        if (vw <= 0 || vh <= 0 || pw <= 0 || ph <= 0) return;
        float bufferAspect = (float) vw / vh;
        float viewAspect = (float) pw / ph;
        float targetAspect;
        switch (engine.getAspect()) {
            case PlayerEngine.ASPECT_FILL: targetAspect = viewAspect; break;
            case PlayerEngine.ASPECT_16_9: targetAspect = 16f / 9f; break;
            case PlayerEngine.ASPECT_4_3: targetAspect = 4f / 3f; break;
            case PlayerEngine.ASPECT_STRETCH: targetAspect = -1f; break; // full stretch
            case PlayerEngine.ASPECT_FIT:
            default: targetAspect = bufferAspect; break;
        }
        int w, h;
        if (targetAspect < 0f) { // STRETCH: fill parent ignoring ratio
            w = pw; h = ph;
        } else if (targetAspect > viewAspect) {
            w = pw; h = Math.round(pw / targetAspect);
        } else {
            h = ph; w = Math.round(ph * targetAspect);
        }
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        surfaceView.setLayoutParams(p);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---------------- panels ----------------

    private void showPanels(int which) {
        android.util.Log.i("myui", "showPanels which=" + which);
        panelState = which;
        leftPanel.setVisibility(which == PANEL_LEFT ? View.VISIBLE : View.GONE);
        rightPanel.setVisibility(which == PANEL_RIGHT ? View.VISIBLE : View.GONE);
        if (which == PANEL_LEFT) {
            ((GroupAdapter) groupList.getAdapter()).notifyDataSetChanged();
            ((ChannelAdapter) channelList.getAdapter()).notifyDataSetChanged();
            // auto-scroll to the channel currently playing
            final int pos = locateCurChannel();
            if (pos >= 0) {
                channelList.setSelection(pos);
                channelList.post(new Runnable() { public void run() {
                    channelList.setSelectionFromTop(pos, dp(80));
                }});
            }
            channelList.requestFocus();
        } else if (which == PANEL_RIGHT) {
            ChannelStore.Channel ch = curChannel();
            if (ch != null) refreshLineLabel(ch);
            refreshAspectPills();
            refreshDecodeLabel();
        }
    }

    private void hidePanels() {
        showPanels(PANEL_NONE);
    }

    // index of currently playing channel in the open list, -1 if not found
    private int locateCurChannel() {
        if (curPlayCh != null && curPlayCh.ename != null) {
            for (int i = 0; i < curChannels.size(); i++) {
                if (curPlayCh.ename.equals(curChannels.get(i).ename)) return i;
            }
        }
        return curChannelIdx >= 0 && curChannelIdx < curChannels.size() ? curChannelIdx : -1;
    }

    private void togglePanels() {
        if (loadingChannels) return;
        showPanels(panelState == PANEL_NONE ? PANEL_LEFT : PANEL_NONE);
    }

    // ---------------- dialogs ----------------

    private void showLinePanel() {
        ChannelStore.Channel ch = curPlayCh != null ? curPlayCh : curChannel();
        if (ch == null || curLines.isEmpty()) { toast("未在播放"); return; }
        final ArrayList<String> lines = curLines;
        String[] names = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            String label = i < curLineLabels.size() && curLineLabels.get(i).length() > 0
                    ? curLineLabels.get(i) : OnlineSource.lineLabel(lines.get(i));
            names[i] = (i + 1) + ". " + label + (i == curLineIdx ? "  （当前）" : "");
        }
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] dlgHolder = new android.app.Dialog[1];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView r = dlgRow(names[i], i == curLineIdx);
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                    final int gen = ++playGen;
                    engine.setChannelLines(lines, idx);
                    startPlay(gen, ch, lines, idx);
                    showBigOsd(ch, null);
                }
            });
            rows.addView(r, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(rows);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.5f);
        sc.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));
        dlgHolder[0] = styledDialog("换线路", sc, null, null, "取消", null, null, null);
        dlgHolder[0].show();
        hidePanels();
    }

    private void showCastPanel() {
        hidePanels();
        final String url = engine.getLastFinalUrl();
        if (url == null || url.length() == 0) { toast("请先播放一个频道再投屏"); return; }
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);
        final TextView tip = newText("正在搜索同一WiFi下的电视设备...", 14, 0xFF888888);
        box.addView(tip);
        final android.app.Dialog dlg = styledDialog("DLNA投屏", box,
                null, null, "关闭", null, null, null);
        dlg.show();
        final java.util.ArrayList<DlnaCast.Device> devices = new java.util.ArrayList<DlnaCast.Device>();
        DlnaCast.scan(new DlnaCast.ScanCallback() {
            public void onDevice(final DlnaCast.Device d) {
                runOnUiThread(new Runnable() { public void run() {
                    devices.add(d);
                    tip.setText("发现设备，点击投屏：");
                    TextView b = dlgRow("📺 " + d.name, false);
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            b.setText("📺 " + d.name + "（投屏中...）");
                            new Thread(new Runnable() { public void run() {
                                final String err = DlnaCast.cast(d, url);
                                runOnUiThread(new Runnable() { public void run() {
                                    toast(err == null ? "已投屏到：" + d.name : "投屏失败：" + err);
                                    if (err == null) dlg.dismiss();
                                }});
                            }}).start();
                        }
                    });
                    box.addView(b);
                }});
            }
            public void onDone(int count) {
                runOnUiThread(new Runnable() { public void run() {
                    if (devices.isEmpty()) tip.setText("未发现电视设备\n\n请确认：手机与电视连同一WiFi，电视已开启DLNA（多屏互动/投屏服务）");
                }});
            }
        });
    }

    private void confirmExit() {
        styledDialog("退出", dlgMessage("确定要退出应用吗？"),
                "确定", new Runnable() { public void run() { finish(); } },
                "取消", null, null, null).show();
    }

    private void showEpgPanel() {
        final ChannelStore.Channel ch = curPlayCh != null ? curPlayCh : curChannel();
        if (ch == null) { toast("未在播放"); return; }
        hidePanels();
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        TextView loading = newText("加载中...", 14, COL_GRAY);
        loading.setPadding(0, dp(10), 0, dp(10));
        rows.addView(loading);
        final ScrollView sc = new ScrollView(this);
        sc.addView(rows);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        sc.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH));
        final android.app.Dialog dlg = styledDialog("节目预告 - " + ch.name, sc,
                null, null, "关闭", null, null, null);
        dlg.show();
        EpgFetcher.fetch(ch.ename, new EpgFetcher.Callback() {
            public void onResult(ArrayList<EpgFetcher.Program> list) {
                if (isFinishing() || !dlg.isShowing()) return;
                rows.removeAllViews();
                if (list.isEmpty()) {
                    TextView empty = newText("暂无节目数据", 14, COL_GRAY);
                    empty.setPadding(0, dp(10), 0, dp(10));
                    rows.addView(empty);
                    return;
                }
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
                final LinearLayout[] curRow = new LinearLayout[1];
                for (EpgFetcher.Program p : list) {
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(10), dp(8), dp(10), dp(8));
                    TextView time = new TextView(MainActivity.this);
                    time.setText(fmt.format(new java.util.Date(p.start * 1000L)) + " - "
                            + fmt.format(new java.util.Date(p.end * 1000L)));
                    time.setTextSize(13);
                    time.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                    time.setTextColor(p.now ? COL_RED : COL_GRAY);
                    row.addView(time, lp(dp(122), ViewGroup.LayoutParams.WRAP_CONTENT));
                    TextView name = new TextView(MainActivity.this);
                    name.setText((p.now ? "▶ " : "") + p.title);
                    name.setTextSize(15);
                    name.setTextColor(p.now ? COL_RED : COL_TXT);
                    row.addView(name, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    if (p.now) {
                        row.setBackground(roundBg(0x1AF03E3E, 8));
                        curRow[0] = row;
                    }
                    rows.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                if (curRow[0] != null) {
                    sc.post(new Runnable() { public void run() {
                        sc.smoothScrollTo(0, Math.max(0, curRow[0].getTop() - dp(30)));
                    }});
                }
            }
        });
    }

    private void showCustomSourceDialog() {
        hidePanels();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);
        final EditText input = new EditText(this);
        input.setHint("粘贴 直播源链接(txt/m3u) 或 单个直播地址");
        input.setTextColor(COL_TXT);
        input.setHintTextColor(0x669A9AA2);
        input.setBackground(roundBg(0x1FFFFFFF, 8));
        input.setPadding(dp(10), dp(9), dp(10), dp(9));
        box.addView(input);
        ArrayList<CustomSource.Entry> entries = CustomSource.getEntries(this);
        TextView info = newText("当前本机源频道数：" + entries.size() + "（重新添加将替换）", 13, COL_GRAY);
        info.setGravity(Gravity.START);
        info.setPadding(pad, dp(8), pad, dp(6));
        box.addView(info);
        styledDialog("本机源（自定义直播源）", box,
                "确定", new Runnable() { public void run() {
                    String url = input.getText().toString().trim();
                    if (url.length() == 0) return;
                    toast("正在获取直播源...");
                    CustomSource.fetch(MainActivity.this, url, new Runnable() {
                        public void run() {
                            toast("本机源加载完成");
                            loadChannelsAsync();
                        }
                    });
                }},
                "取消", null,
                "清空本机源", new Runnable() { public void run() {
                    CustomSource.removeEntry(MainActivity.this, -2);
                    loadChannelsAsync();
                }}).show();
    }

    // ---------------- indicator ----------------

    private void showIndicator(String text) {
        indicator.setText(text);
        indicator.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideIndicator);
        ui.postDelayed(hideIndicator, 1200);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- gestures ----------------

    private class GestureView extends View {
        private float downX, downY;
        private boolean moved;
        private int gestureMode; // 0 none 1 volume 2 brightness
        private long downAt;

        public GestureView(Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downAt = System.currentTimeMillis();
                    moved = false;
                    gestureMode = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (Math.abs(dx) > dp(40) && Math.abs(dx) > Math.abs(dy)) {
                        moved = true;
                        return true;
                    }
                    if (Math.abs(dy) > dp(24)) {
                        if (gestureMode == 0) {
                            float w = getWidth();
                            gestureMode = downX < w / 3f ? 1 : (downX > w * 2f / 3f ? 2 : 0);
                        }
                        if (gestureMode == 1) {
                            float step = -dy / getHeight() * 15;
                            adjustVolume(step > 0);
                            downY = e.getY();
                            moved = true;
                        } else if (gestureMode == 2) {
                            float step = -dy / getHeight();
                            adjustBrightness(step);
                            downY = e.getY();
                            moved = true;
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getX() - downX;
                    if (!moved && Math.abs(dx) < dp(12)) {
                        if (panelState != PANEL_NONE) {
                            // a layer is open: any tap on empty area just closes it;
                            // tap again on the corresponding half to reopen
                            showPanels(PANEL_NONE);
                        } else {
                            showPanels(downX < getWidth() / 2f ? PANEL_LEFT : PANEL_RIGHT);
                        }
                    } else if (Math.abs(dx) > dp(90)) {
                        switchChannel(dx < 0 ? 1 : -1);
                    }
                    gestureMode = 0;
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }
    }

    private void adjustVolume(boolean up) {
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int nv = Math.max(0, Math.min(max, up ? cur + 1 : cur - 1));
        if (nv != cur) audio.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0);
        showIndicator("音量 " + nv * 100 / max + "%");
    }

    private void adjustBrightness(float delta) {
        Window w = getWindow();
        WindowManager.LayoutParams attr = w.getAttributes();
        float b = attr.screenBrightness;
        if (b < 0) b = 0.5f;
        b = Math.max(0.01f, Math.min(1f, b + delta));
        attr.screenBrightness = b;
        w.setAttributes(attr);
        showIndicator("亮度 " + Math.round(b * 100) + "%");
    }

    // ---------------- keys (tv remote) ----------------

    private int numBuf = -1;
    private Runnable numCommit;

    /** channel number entry: accumulate digits, jump after 1.2s idle */
    private void onDigit(int d) {
        numBuf = numBuf < 0 ? d : numBuf * 10 + d;
        if (numBuf > 999) numBuf = d;
        // big OSD shows the number being typed
        osdNum.setText(String.valueOf(numBuf));
        osdName.setText("选台中");
        osdStatus.setText("");
        osdBox.setVisibility(View.VISIBLE);
        osdBox.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        final int target = numBuf;
        if (numCommit != null) ui.removeCallbacks(numCommit);
        numCommit = new Runnable() { public void run() {
            numBuf = -1;
            jumpToChannel(target);
        }};
        ui.postDelayed(numCommit, 1200);
    }

    private void jumpToChannel(int n) {
        if (curChannels.isEmpty()) { toast("频道列表为空"); return; }
        if (n < 1 || n > curChannels.size()) {
            showBigOsdStatus("无此频道（共 " + curChannels.size() + " 个）");
            ui.removeCallbacks(hideOsd);
            ui.postDelayed(hideOsd, 1500);
            return;
        }
        playChannelAt(n - 1);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (panelState == PANEL_NONE) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP: switchChannel(-1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN: switchChannel(1); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: switchLine(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: switchLine(1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER: showPanels(PANEL_LEFT); return true;
                case KeyEvent.KEYCODE_MENU: showPanels(PANEL_RIGHT); return true;
                case KeyEvent.KEYCODE_BACK: confirmExit(); return true;
                case KeyEvent.KEYCODE_VOLUME_UP: adjustVolume(true); return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN: adjustVolume(false); return true;
            }
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                onDigit(code - KeyEvent.KEYCODE_0);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (panelState == PANEL_LEFT) {
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_DPAD_LEFT: hidePanels(); return true;
                case KeyEvent.KEYCODE_MENU: showPanels(PANEL_RIGHT); return true;
            }
            return super.dispatchKeyEvent(e);
        }
        // PANEL_RIGHT
        switch (code) {
            case KeyEvent.KEYCODE_BACK: hidePanels(); return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------- adapters ----------------

    private class GroupAdapter extends BaseAdapter {
        public int getCount() { return groupNames.size(); }
        public Object getItem(int pos) { return groupNames.get(pos); }
        public long getItemId(int pos) { return pos; }
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView tv = (TextView) convert;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setTextSize(15);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(4), dp(12), dp(4), dp(12));
            }
            String g = groupNames.get(pos);
            tv.setText(g);
            boolean cur = pos == curGroupIdx;
            if (cur) {
                tv.setBackground(roundBg(COL_RED, 4));
                tv.setTextColor(Color.WHITE);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                tv.setBackground(new android.graphics.drawable.ColorDrawable(0));
                tv.setTextColor(0xFFB4B4BA);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
            return tv;
        }
    }

    // epg cache for channel list subtitle: ename -> "20:00 新闻联播"
    private final java.util.HashMap<String, String> epgNowCache = new java.util.HashMap<String, String>();
    private final java.util.HashSet<String> epgPending = new java.util.HashSet<String>();
    private boolean epgRefreshQueued;

    private void requestEpgNow(final ChannelStore.Channel ch) {
        if (ch.ename == null || epgNowCache.containsKey(ch.ename) || !epgPending.add(ch.ename)) return;
        EpgFetcher.fetch(ch.ename, new EpgFetcher.Callback() {
            public void onResult(ArrayList<EpgFetcher.Program> list) {
                String s = "";
                EpgFetcher.Program next = null;
                for (EpgFetcher.Program p : list) {
                    if (p.now) { s = fmtProgram(p, true); break; }
                    if (next == null && p.start > System.currentTimeMillis() / 1000L) next = p;
                }
                if (s.length() == 0 && next != null) s = "稍后 " + fmtProgram(next, false);
                epgNowCache.put(ch.ename, s);
                if (!epgRefreshQueued) {
                    epgRefreshQueued = true;
                    ui.postDelayed(new Runnable() { public void run() {
                        epgRefreshQueued = false;
                        if (panelState == PANEL_LEFT) {
                            ((ChannelAdapter) channelList.getAdapter()).notifyDataSetChanged();
                        }
                    }}, 300);
                }
            }
        });
    }

    private String fmtProgram(EpgFetcher.Program p, boolean now) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
        String t = fmt.format(new java.util.Date(p.start * 1000L)) + " " + p.title;
        return now ? "▶ " + t : t;
    }

    private class ChannelAdapter extends BaseAdapter {
        public int getCount() { return curChannels.size(); }
        public Object getItem(int pos) { return curChannels.get(pos); }
        public long getItemId(int pos) { return pos; }
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convert;
            TextView num, name, prog;
            if (cell == null) {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(dp(16), dp(8), dp(10), dp(8));
                num = new TextView(MainActivity.this);
                num.setTextSize(14);
                num.setGravity(Gravity.CENTER);
                num.setLayoutParams(new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.addView(num);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(8), 0, 0, 0);
                name = new TextView(MainActivity.this);
                name.setTextSize(16);
                col.addView(name);
                prog = new TextView(MainActivity.this);
                prog.setTextSize(11);
                prog.setTextColor(0x99FFFFFF);
                prog.setPadding(0, dp(1), 0, 0);
                prog.setSingleLine(true);
                prog.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(prog);
                cell.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cell.setTag(new TextView[]{num, name, prog});
            } else {
                num = ((TextView[]) cell.getTag())[0];
                name = ((TextView[]) cell.getTag())[1];
                prog = ((TextView[]) cell.getTag())[2];
            }
            ChannelStore.Channel ch = curChannels.get(pos);
            boolean cur = curPlayCh != null && ch.ename != null && ch.ename.equals(curPlayCh.ename);
            num.setText(String.valueOf(pos + 1));
            num.setTextColor(cur ? COL_YELLOW : 0xFF77777D);
            name.setText(ch.name);
            name.setTextColor(cur ? COL_YELLOW : COL_TXT);
            name.setTypeface(null, cur ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            // current program subtitle: online nowplaycontent first, then epg cache, else lazy fetch
            String np = ch.nowplay != null ? ch.nowplay.trim() : null;
            if (np == null || np.length() == 0) {
                np = (ch.ename != null && epgNowCache.containsKey(ch.ename))
                        ? epgNowCache.get(ch.ename) : null;
            }
            if (np != null && np.length() > 0) {
                prog.setText(np);
                prog.setVisibility(View.VISIBLE);
            } else if (np != null) {
                prog.setVisibility(View.GONE); // cached: server has no data for this channel
            } else {
                prog.setVisibility(View.GONE);
                if (panelState == PANEL_LEFT) requestEpgNow(ch);
            }
            cell.setBackgroundColor(cur ? 0x14FFFFFF : 0x00000000);
            return cell;
        }
    }
}
