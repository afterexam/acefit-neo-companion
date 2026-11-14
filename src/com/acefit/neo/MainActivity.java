package com.acefit.neo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Companion dashboard for ACEFAST ACEFIT NEO (FA003): battery, EQ preset, and 8 touch gestures.
 */
public final class MainActivity extends Activity implements NeoBleClient.Listener {
    private static final int REQ_BT_PERMS = 101;
    private static final String PREFS_NAME = "acefit_neo_prefs";
    private static final String KEY_LAST_MAC = "last_mac";
    private static final int MAX_LOG_LINES = 80;

    private NeoBleClient bleClient;
    private SharedPreferences prefs;
    private boolean autoConnectAttempted = false;

    private boolean isDark;
    private int cBg, cCard, cSubCard, cBorder, cPrimary, cPrimarySoft, cText, cMuted, cOk, cWarn, cErr;

    private LinearLayout permBanner;
    private TextView statusBadge;
    private TextView deviceInfoView;
    private TextView statusMsgView;
    private Button btnConnect;
    private Button btnDisconnect;

    private TextView masterRoleBadge;
    private TextView leftPctView, leftStateView, rightPctView, rightStateView;
    private ProgressBar leftBar, rightBar;

    private final TextView[] eqChips = new TextView[3];
    private final TextView[] gestureCells = new TextView[8];

    private TextView logToggleBtn;
    private TextView logContentView;
    private boolean logsVisible = false;
    private final Deque<String> logLines = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        bleClient = new NeoBleClient(this, this);

        initPalette();
        setContentView(buildContentView());
        configureEdgeToEdge();

        if (hasBtPermissions()) {
            permBanner.setVisibility(View.GONE);
            autoConnectAttempted = true;
            bleClient.connect(prefs.getString(KEY_LAST_MAC, ""));
        } else {
            permBanner.setVisibility(View.VISIBLE);
            requestBtPermissions();
        }
        renderState(bleClient.getState());
    }

    @Override
    protected void onDestroy() {
        bleClient.disconnect();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BT_PERMS) return;
        if (hasBtPermissions()) {
            permBanner.setVisibility(View.GONE);
            if (!autoConnectAttempted && bleClient.getStatus() == NeoBleClient.ConnStatus.DISCONNECTED) {
                autoConnectAttempted = true;
                bleClient.connect(prefs.getString(KEY_LAST_MAC, ""));
            }
        } else {
            permBanner.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onConnectionChanged(NeoBleClient.ConnStatus st, String name, String mac, String msg) {
        if (st == NeoBleClient.ConnStatus.CONNECTED && !TextUtils.isEmpty(mac)) {
            prefs.edit().putString(KEY_LAST_MAC, mac).apply();
        }
        updateConnectionUi(st, name, mac, msg);
    }

    @Override
    public void onStateUpdated(NeoProtocol.NeoState state, NeoProtocol.PacketType packetType) {
        renderState(state);
    }

    @Override
    public void onLog(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        if (logLines.size() >= MAX_LOG_LINES) {
            logLines.removeFirst();
        }
        logLines.addLast(ts + " " + line);
        if (logsVisible && logContentView != null) {
            refreshLogText();
        }
    }

    private void initPalette() {
        int nightMask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isDark = nightMask == Configuration.UI_MODE_NIGHT_YES;
        if (isDark) {
            cBg = 0xFF0B1120;
            cCard = 0xFF162032;
            cSubCard = 0xFF1E293B;
            cBorder = 0xFF2B3B52;
            cPrimary = 0xFF38BDF8;
            cPrimarySoft = 0xFF0F2D46;
            cText = 0xFFF1F5F9;
            cMuted = 0xFF94A3B8;
            cOk = 0xFF34D399;
            cWarn = 0xFFFBBF24;
            cErr = 0xFFF87171;
        } else {
            cBg = 0xFFF3F6FB;
            cCard = 0xFFFFFFFF;
            cSubCard = 0xFFF8FAFC;
            cBorder = 0xFFE2E8F0;
            cPrimary = 0xFF0284C7;
            cPrimarySoft = 0xFFE0F2FE;
            cText = 0xFF0F172A;
            cMuted = 0xFF64748B;
            cOk = 0xFF059669;
            cWarn = 0xFFD97706;
            cErr = 0xFFDC2626;
        }
    }

    private void configureEdgeToEdge() {
        View decor = getWindow().getDecorView();
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null && !isDark) {
            ctrl.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        decor.requestApplyInsets();
    }

    private View buildContentView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        int basePad = dp(16);
        scroll.setPadding(basePad, basePad + statusBarHeightFallback(), basePad, dp(24));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(basePad + sys.left, basePad + sys.top, basePad + sys.right, dp(24) + sys.bottom);
            return WindowInsets.CONSUMED;
        });

        root.addView(buildPermBanner());
        root.addView(buildHeaderCard());
        root.addView(buildBatteryCard());
        root.addView(buildEqCard());
        root.addView(buildGestureCard());
        root.addView(buildLogCard());
        return scroll;
    }

    private View buildPermBanner() {
        permBanner = makeCard(0xFFFEF3C7, 0xFFF59E0B);
        permBanner.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView msg = makeText("需要蓝牙扫描与连接权限才能通信", 13, isDark ? 0xFF1E293B : 0xFF78350F, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(msg, lp);

        Button grantBtn = makeActionButton("授予权限", true);
        grantBtn.setOnClickListener(v -> requestBtPermissions());
        row.addView(grantBtn);
        permBanner.addView(row);
        return permBanner;
    }

    private View buildHeaderCard() {
        LinearLayout card = makeCard(cCard, cBorder);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = makeText("ACEFIT NEO 伴侣", 20, cText, true);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusBadge = makeBadge("未连接", cMuted);
        topRow.addView(statusBadge);
        card.addView(topRow);

        deviceInfoView = makeText("等待连接 ACEFAST ACEFIT NEO", 13, cMuted, false);
        deviceInfoView.setPadding(0, dp(6), 0, dp(2));
        card.addView(deviceInfoView);

        statusMsgView = makeText("点击下方连接并读取耳机实时状态", 12, cMuted, false);
        card.addView(statusMsgView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);

        btnConnect = makeActionButton("连接 / 刷新", true);
        btnConnect.setOnClickListener(v -> {
            if (!hasBtPermissions()) {
                requestBtPermissions();
                return;
            }
            if (bleClient.getStatus() == NeoBleClient.ConnStatus.CONNECTED) {
                bleClient.refreshAll();
            } else {
                bleClient.connect(prefs.getString(KEY_LAST_MAC, ""));
            }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.setMarginEnd(dp(8));
        btnRow.addView(btnConnect, b1);

        btnDisconnect = makeActionButton("断开连接", false);
        btnDisconnect.setOnClickListener(v -> bleClient.disconnect());
        btnRow.addView(btnDisconnect, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(btnRow);
        return card;
    }

    private View buildBatteryCard() {
        LinearLayout card = makeCard(cCard, cBorder);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.addView(makeText("双耳状态", 15, cText, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        masterRoleBadge = makeBadge("等待同步", cMuted);
        hdr.addView(masterRoleBadge);
        card.addView(hdr);

        LinearLayout podsRow = new LinearLayout(this);
        podsRow.setOrientation(LinearLayout.HORIZONTAL);
        podsRow.setPadding(0, dp(12), 0, dp(8));

        LinearLayout leftBox = buildPodBox("左耳 (L)", true);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMarginEnd(dp(8));
        podsRow.addView(leftBox, lp1);

        LinearLayout rightBox = buildPodBox("右耳 (R)", false);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMarginStart(dp(8));
        podsRow.addView(rightBox, lp2);
        card.addView(podsRow);

        TextView note = makeText("注：ACEFIT NEO (FA003) 协议仅回报双耳电量与主从状态，不上报充电仓电量。", 11, cMuted, false);
        card.addView(note);
        return card;
    }

    private LinearLayout buildPodBox(String title, boolean isLeft) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackground(makeRoundRect(cSubCard, cBorder, dp(12), 1));

        TextView label = makeText(title, 13, cMuted, true);
        box.addView(label);

        TextView pct = makeText("--%", 26, cText, true);
        pct.setPadding(0, dp(4), 0, dp(2));
        box.addView(pct);

        TextView st = makeText("未连接", 12, cMuted, false);
        st.setPadding(0, 0, 0, dp(8));
        box.addView(st);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        if (isLeft) {
            leftPctView = pct;
            leftStateView = st;
            leftBar = bar;
        } else {
            rightPctView = pct;
            rightStateView = st;
            rightBar = bar;
        }
        return box;
    }

    private View buildEqCard() {
        LinearLayout card = makeCard(cCard, cBorder);
        card.addView(makeText("音效模式 (EQ)", 15, cText, true));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);

        for (int i = 0; i < 3; i++) {
            final int mode = i;
            TextView chip = makeText(NeoProtocol.SOUND_LABELS[i], 14, cText, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8), dp(12), dp(8), dp(12));
            chip.setBackground(makeRoundRect(cSubCard, cBorder, dp(10), 1));
            chip.setOnClickListener(v -> {
                if (!ensureConnected()) return;
                bleClient.setSoundMode(mode);
                bleClient.getState().soundMode = mode;
                bleClient.getState().soundLoaded = true;
                renderState(bleClient.getState());
            });
            eqChips[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) lp.setMarginEnd(dp(8));
            row.addView(chip, lp);
        }
        card.addView(row);
        return card;
    }

    private View buildGestureCard() {
        LinearLayout card = makeCard(cCard, cBorder);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.addView(makeText("触控手势自定义", 15, cText, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView resetBtn = makeText("恢复出厂默认", 13, cPrimary, true);
        resetBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        resetBtn.setOnClickListener(v -> confirmResetGestures());
        hdr.addView(resetBtn);
        card.addView(hdr);

        LinearLayout colHdr = new LinearLayout(this);
        colHdr.setOrientation(LinearLayout.HORIZONTAL);
        colHdr.setPadding(0, dp(10), 0, dp(4));
        colHdr.addView(makeText("手势", 12, cMuted, true), new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView lHdr = makeText("左耳 (L)", 12, cMuted, true);
        lHdr.setGravity(Gravity.CENTER);
        colHdr.addView(lHdr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rHdr = makeText("右耳 (R)", 12, cMuted, true);
        rHdr.setGravity(Gravity.CENTER);
        colHdr.addView(rHdr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(colHdr);

        String[] rowNames = new String[] { "单击", "双击", "三击", "长按" };
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            row.addView(makeText(rowNames[r], 14, cText, true), new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int side = 0; side < 2; side++) {
                final int slot = r * 2 + side;
                TextView cell = makeText("--", 13, cText, false);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(6), dp(10), dp(6), dp(10));
                cell.setBackground(makeRoundRect(cSubCard, cBorder, dp(10), 1));
                cell.setOnClickListener(v -> openGesturePicker(slot));
                gestureCells[slot] = cell;
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (side == 0) cp.setMarginEnd(dp(6));
                else cp.setMarginStart(dp(6));
                row.addView(cell, cp);
            }
            card.addView(row);
        }
        return card;
    }

    private View buildLogCard() {
        LinearLayout card = makeCard(cCard, cBorder);

        logToggleBtn = makeText("展开协议调试日志", 13, cMuted, true);
        logToggleBtn.setPadding(0, dp(2), 0, dp(2));
        logToggleBtn.setOnClickListener(v -> {
            logsVisible = !logsVisible;
            logToggleBtn.setText(logsVisible ? "收起协议调试日志" : "展开协议调试日志");
            logContentView.setVisibility(logsVisible ? View.VISIBLE : View.GONE);
            if (logsVisible) refreshLogText();
        });
        card.addView(logToggleBtn);

        logContentView = makeText("", 11, cMuted, false);
        logContentView.setTypeface(Typeface.MONOSPACE);
        logContentView.setPadding(0, dp(8), 0, 0);
        logContentView.setVisibility(View.GONE);
        card.addView(logContentView);
        return card;
    }

    private void updateConnectionUi(NeoBleClient.ConnStatus st, String name, String mac, String msg) {
        String label;
        int color;
        if (st == NeoBleClient.ConnStatus.CONNECTED) {
            label = "已连接";
            color = cOk;
        } else if (st == NeoBleClient.ConnStatus.CONNECTING || st == NeoBleClient.ConnStatus.SCANNING) {
            label = st == NeoBleClient.ConnStatus.SCANNING ? "搜索中" : "连接中";
            color = cWarn;
        } else {
            label = "未连接";
            color = cMuted;
        }
        statusBadge.setText(label);
        statusBadge.setTextColor(color);
        statusBadge.setBackground(makeRoundRect(withAlpha(color, 0x22), color, dp(20), 1));

        if (!TextUtils.isEmpty(mac)) {
            deviceInfoView.setText((TextUtils.isEmpty(name) ? NeoProtocol.TARGET_DEVICE_NAME : name) + " · " + mac);
        } else {
            deviceInfoView.setText("目标设备: " + NeoProtocol.TARGET_DEVICE_NAME);
        }
        statusMsgView.setText(msg);

        boolean connected = st == NeoBleClient.ConnStatus.CONNECTED;
        btnConnect.setText(connected ? "刷新状态" : "连接耳机");
        btnDisconnect.setEnabled(st != NeoBleClient.ConnStatus.DISCONNECTED);
        btnDisconnect.setAlpha(st != NeoBleClient.ConnStatus.DISCONNECTED ? 1f : 0.45f);
    }

    private void renderState(NeoProtocol.NeoState s) {
        if (s.detailLoaded) {
            masterRoleBadge.setText(s.leftIsMain ? "左耳为主" : "右耳为主");
            masterRoleBadge.setTextColor(cPrimary);
            masterRoleBadge.setBackground(makeRoundRect(cPrimarySoft, cPrimary, dp(20), 1));
        }

        renderPod(leftPctView, leftStateView, leftBar, s.detailLoaded, s.batteryLoaded, s.leftConnected, s.leftCharging, s.leftBattery);
        renderPod(rightPctView, rightStateView, rightBar, s.detailLoaded, s.batteryLoaded, s.rightConnected, s.rightCharging, s.rightBattery);

        for (int i = 0; i < 3; i++) {
            boolean active = s.soundLoaded && s.soundMode == i;
            eqChips[i].setTextColor(active ? cPrimary : cText);
            eqChips[i].setBackground(makeRoundRect(active ? cPrimarySoft : cSubCard, active ? cPrimary : cBorder, dp(10), active ? 2 : 1));
        }

        for (int i = 0; i < 8; i++) {
            gestureCells[i].setText(s.gestureLoaded ? NeoProtocol.actionLabelForCode(s.gestures[i]) : "--");
            gestureCells[i].setTextColor(s.gestureLoaded ? cText : cMuted);
        }
    }

    private void renderPod(TextView pctView, TextView stView, ProgressBar bar,
                           boolean detailLoaded, boolean batteryLoaded, boolean conn, boolean chg, int bat) {
        if (!detailLoaded && !batteryLoaded) {
            pctView.setText("--%");
            stView.setText("等待读取");
            stView.setTextColor(cMuted);
            bar.setProgress(0);
            bar.setProgressTintList(ColorStateList.valueOf(cMuted));
            return;
        }
        if (detailLoaded && !conn) {
            pctView.setText("--%");
            stView.setText("未连接");
            stView.setTextColor(cMuted);
            bar.setProgress(0);
            bar.setProgressTintList(ColorStateList.valueOf(cMuted));
            return;
        }
        int clamped = Math.max(0, Math.min(100, bat));
        pctView.setText(batteryLoaded ? (clamped + "%") : "--%");
        stView.setText(chg ? "充电中" : "使用中");
        int barColor = clamped <= 20 ? cErr : (clamped <= 40 ? cWarn : cOk);
        stView.setTextColor(chg ? cPrimary : cOk);
        bar.setProgress(batteryLoaded ? clamped : 0);
        bar.setProgressTintList(ColorStateList.valueOf(barColor));
    }

    private void openGesturePicker(int slot) {
        if (!ensureConnected()) return;
        String current = bleClient.getState().gestures[slot];
        int checked = 0;
        for (int i = 0; i < NeoProtocol.ACTION_CODES.length; i++) {
            if (NeoProtocol.ACTION_CODES[i].equals(current)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(NeoProtocol.GESTURE_SLOT_LABELS[slot])
                .setSingleChoiceItems(NeoProtocol.ACTION_LABELS, checked, (dialog, which) -> {
                    String chosen = NeoProtocol.ACTION_CODES[which];
                    bleClient.getState().gestures[slot] = chosen;
                    bleClient.getState().gestureLoaded = true;
                    renderState(bleClient.getState());
                    bleClient.setGestureSlot(slot, chosen);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmResetGestures() {
        if (!ensureConnected()) return;
        new AlertDialog.Builder(this)
                .setTitle("恢复出厂手势")
                .setMessage("确认将左右耳全部 8 项触控手势重置为默认配置？")
                .setPositiveButton("确定恢复", (d, w) -> bleClient.resetGestures())
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean ensureConnected() {
        if (bleClient.getStatus() != NeoBleClient.ConnStatus.CONNECTED) {
            Toast.makeText(this, "请先连接 ACEFAST ACEFIT NEO", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, REQ_BT_PERMS);
        } else {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, REQ_BT_PERMS);
        }
    }

    private void refreshLogText() {
        StringBuilder sb = new StringBuilder();
        for (String line : logLines) {
            sb.append(line).append('\n');
        }
        logContentView.setText(sb.toString());
    }

    private LinearLayout makeCard(int bg, int stroke) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(makeRoundRect(bg, stroke, dp(16), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView makeText(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView makeBadge(String s, int color) {
        TextView tv = makeText(s, 11, color, true);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        tv.setBackground(makeRoundRect(withAlpha(color, 0x22), color, dp(20), 1));
        return tv;
    }

    private Button makeActionButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int btnFill = primary ? 0xFF0284C7 : cSubCard;
        int btnBorder = primary ? 0xFF0284C7 : cBorder;
        b.setTextColor(primary ? Color.WHITE : cText);
        b.setBackground(makeRoundRect(btnFill, btnBorder, dp(10), 1));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setStateListAnimator(null);
        return b;
    }

    private int statusBarHeightFallback() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? getResources().getDimensionPixelSize(resId) : dp(36);
    }

    private GradientDrawable makeRoundRect(int fill, int stroke, int radiusPx, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
