package com.briqt.screenlimit;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 全屏拦截页：临时应急使用 / 输密码解锁 / 远程申请授权。
 * 采用与设置页统一的浅色语义色板；支持展示应用真实图标、今日累计用量、解禁时刻，
 * 以及远程审批等待态的断线重进保持与立即刷新。
 */
public final class OverlayGuard {
    private static final String TAG = "ScreenLimit";

    private static int col(int id) { return ctx == null ? 0 : ctx.getColor(id); }
    private static int bg() { return col(R.color.surface); }
    private static int cardC() { return col(R.color.surface_container); }
    private static int cardHiC() { return col(R.color.surface_container_high); }
    private static int hi() { return col(R.color.text_hi); }
    private static int mid() { return col(R.color.text_mid); }
    private static int low() { return col(R.color.text_low); }
    private static int primary() { return col(R.color.primary); }
    private static int onPrimary() { return col(R.color.on_primary); }
    private static int primaryContainer() { return col(R.color.primary_container); }
    private static int onPrimaryContainer() { return col(R.color.on_primary_container); }
    private static int line() { return col(R.color.outline); }
    private static int lineVar() { return col(R.color.outline_variant); }

    private static View root;
    private static Context ctx;
    private static String shownFor;

    private static LinearLayout mainPanel, pwPanel, remotePanel, waitPanel;
    private static TextView waitTitle, waitText;
    private static PinPad pad;

    interface Actions {
        void onTempUse();
        /** @param minutes 分钟数，或 {@link Rules#GRANT_TODAY} 表示「今日不再限制」。 */
        void onPasswordSubmit(String pin, int minutes);
        void onRemoteRequest(int minutes);
        void onRemotePollNow();
        void onRemoteCancel();
        /** 关掉拦截页、留在桌面（拦截时已经退回桌面了）。 */
        void onDismiss();
    }

    /** 授权时长选项：5 / 15 / 30 / 60 分钟 / 今日不再限制。 */
    private static final int[] DUR_VALUES = {5, 15, 30, 60, Rules.GRANT_TODAY};
    private static final String[] DUR_LABELS = {"5 分钟", "15 分钟", "30 分钟", "60 分钟", "今日不限"};
    /** 当前选中的时长，默认 30 分钟。 */
    private static int selMinutes = 30;
    private static TextView[] durChips;

    static String durText(int minutes) {
        return minutes == Rules.GRANT_TODAY ? "今日不再限制该应用" : (minutes + " 分钟");
    }

    private OverlayGuard() {}

    static synchronized boolean isShown(String pkg) {
        return root != null && root.isAttachedToWindow() && pkg.equals(shownFor);
    }

    static synchronized boolean isAttached() {
        return root != null && root.isAttachedToWindow();
    }

    static synchronized String shownPkg() { return shownFor; }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** 应急按钮与它下方的说明，冷却倒计时每 tick 刷新，所以留成字段。 */
    private static Button coolBtn;
    private static TextView coolCap;

    /**
     * 展示全屏拦截浮层。
     * @param waitingRemote 若当前应用已有正在等待审批的远程申请，直接打开等待面板，避免用户误点重发把已批申请顶掉
     */
    static synchronized void showBlock(final Context c, final String pkg, Drawable appIcon,
                                       String title, String reasonBadge, String usedTodayText,
                                       String releaseHintText, long coolReadyAt, int coolUseMin,
                                       int coolIntervalMin, boolean hasPassword, boolean hasRemote,
                                       boolean waitingRemote, int waitingMinutes,
                                       final Actions actions) {
        try {
            if (c == null) return;
            if (!Settings.canDrawOverlays(c)) { Log.w(TAG, "no overlay permission"); return; }
            if (root != null && pkg.equals(shownFor) && root.isAttachedToWindow()) return;
            hide();

            Context ac = c.getApplicationContext();
            ctx = ac;
            final WindowManager wm = (WindowManager) ac.getSystemService(Context.WINDOW_SERVICE);

            ScrollView container = new ScrollView(ac);
            container.setBackgroundColor(bg());
            container.setFillViewport(true);

            LinearLayout wrap = new LinearLayout(ac);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setGravity(Gravity.CENTER);
            wrap.setPadding(dp(ac, 24), dp(ac, 44), dp(ac, 24), dp(ac, 28));
            container.addView(wrap, new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT));

            // ---- 1. 主面板 ----
            mainPanel = new LinearLayout(ac);
            mainPanel.setOrientation(LinearLayout.VERTICAL);
            mainPanel.setGravity(Gravity.CENTER_HORIZONTAL);
            mainPanel.setLayoutParams(new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT));

            // 应用图标 + 守护角标
            FrameLayout iconWrap = new FrameLayout(ac);
            GradientDrawable iconBg = new GradientDrawable();
            iconBg.setShape(GradientDrawable.RECTANGLE);
            iconBg.setCornerRadius(dp(ac, 20));
            iconBg.setColor(cardC());
            iconBg.setStroke(dp(ac, 1), lineVar());
            iconWrap.setBackground(iconBg);
            int boxSz = dp(ac, 76);
            LinearLayout.LayoutParams iwLp = new LinearLayout.LayoutParams(boxSz, boxSz);
            iwLp.bottomMargin = dp(ac, 14);
            iconWrap.setLayoutParams(iwLp);

            if (appIcon != null) {
                ImageView iv = new ImageView(ac);
                iv.setImageDrawable(appIcon);
                int isz = dp(ac, 52);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(isz, isz, Gravity.CENTER);
                iconWrap.addView(iv, ilp);
            } else {
                TextView lockEmoji = label(ac, "🔒", 32, hi(), 0, 0);
                iconWrap.addView(lockEmoji, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            }
            mainPanel.addView(iconWrap);

            // 应用名称
            TextView t1 = label(ac, title, 23, hi(), 0, dp(ac, 8));
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            mainPanel.addView(t1);

            // 状态胶囊
            TextView badge = new TextView(ac);
            badge.setText(reasonBadge);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            badge.setTextColor(col(R.color.danger));
            badge.setPadding(dp(ac, 12), dp(ac, 5), dp(ac, 12), dp(ac, 5));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dp(ac, 99));
            badgeBg.setColor(col(R.color.danger_container));
            badge.setBackground(badgeBg);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.bottomMargin = dp(ac, 18);
            mainPanel.addView(badge, blp);

            // 双列统计微卡片（今日已用 | 自动解禁）
            LinearLayout metricsCard = new LinearLayout(ac);
            metricsCard.setOrientation(LinearLayout.HORIZONTAL);
            metricsCard.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable mBg = new GradientDrawable();
            mBg.setCornerRadius(dp(ac, 16));
            mBg.setColor(cardC());
            mBg.setStroke(dp(ac, 1), lineVar());
            metricsCard.setBackground(mBg);
            metricsCard.setPadding(dp(ac, 14), dp(ac, 14), dp(ac, 14), dp(ac, 14));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.bottomMargin = dp(ac, 20);
            metricsCard.setLayoutParams(mlp);

            metricsCard.addView(metricCol(ac, "今日累计使用", usedTodayText),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            View vDiv = new View(ac);
            vDiv.setBackgroundColor(lineVar());
            metricsCard.addView(vDiv, new LinearLayout.LayoutParams(Math.max(1, dp(ac, 1)), dp(ac, 32)));
            metricsCard.addView(metricCol(ac, "自动恢复时间", releaseHintText),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mainPanel.addView(metricsCard);

            // 主推荐动作：返回桌面
            Button leaveBtn = pill(ac, "返回桌面", primary(), onPrimary(), true);
            leaveBtn.setTypeface(Typeface.DEFAULT_BOLD);
            leaveBtn.setOnClickListener(v -> actions.onDismiss());
            mainPanel.addView(leaveBtn);

            // 应急使用卡片按钮
            coolBtn = pill(ac, "", primaryContainer(), onPrimaryContainer(), true);
            coolBtn.setOnClickListener(v -> actions.onTempUse());
            coolCap = label(ac, "", 11.5f, low(), 0, dp(ac, 14));
            applyCooldown(coolReadyAt, coolUseMin, coolIntervalMin);
            mainPanel.addView(coolBtn);
            mainPanel.addView(coolCap);

            // 解锁入口行（密码解锁 & 远程申请并排或单列）
            if (hasPassword || hasRemote) {
                LinearLayout unlockRow = new LinearLayout(ac);
                unlockRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams urlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                urlp.topMargin = dp(ac, 2);
                unlockRow.setLayoutParams(urlp);

                if (hasPassword) {
                    Button pw = pill(ac, "🔑 密码解锁", cardC(), hi(), false);
                    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (hasRemote) plp.rightMargin = dp(ac, 8);
                    pw.setLayoutParams(plp);
                    pw.setOnClickListener(v -> showPanel(2));
                    unlockRow.addView(pw);
                }
                if (hasRemote) {
                    Button remote = pill(ac, "📨 远程申请", cardC(), hi(), false);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    remote.setLayoutParams(rlp);
                    remote.setOnClickListener(v -> showPanel(3));
                    unlockRow.addView(remote);
                }
                mainPanel.addView(unlockRow);
            } else {
                mainPanel.addView(label(ac, "未设置密码与远程服务器，只能等待恢复或使用应急额度",
                        11.5f, low(), dp(ac, 6), 0));
            }

            // ---- 2. 密码面板：与设置页密码门同一个 PinPad ----
            pwPanel = new LinearLayout(ac);
            pwPanel.setOrientation(LinearLayout.VERTICAL);
            pwPanel.setGravity(Gravity.CENTER);
            pwPanel.setVisibility(View.GONE);
            LinearLayout dur = new LinearLayout(ac);
            dur.setOrientation(LinearLayout.VERTICAL);
            dur.setGravity(Gravity.CENTER_HORIZONTAL);
            dur.addView(label(ac, "选择解锁可用时长", 13, mid(), 0, dp(ac, 8)));
            dur.addView(durRow(ac));
            dur.addView(label(ac, "⏱ 仅在该应用前台打开时按秒扣减，切出或锁屏不消耗（次日 0 点清零）",
                    11.5f, low(), dp(ac, 8), 0));
            dur.setPadding(0, 0, 0, dp(ac, 16));
            pad = new PinPad(ac)
                    .texts("输入管理密码", "解锁「" + title + "」")
                    .extra(dur)
                    .footer("← 返回拦截页", v -> showPanel(1))
                    .listener(p -> actions.onPasswordSubmit(p, selMinutes));
            pad.view.setPadding(0, 0, 0, 0);
            pwPanel.addView(pad.view);

            // ---- 3. 远程申请：先选时长再发起 ----
            remotePanel = new LinearLayout(ac);
            remotePanel.setOrientation(LinearLayout.VERTICAL);
            remotePanel.setGravity(Gravity.CENTER);
            remotePanel.setVisibility(View.GONE);
            remotePanel.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView rt = label(ac, "远程申请授权", 21, hi(), 0, dp(ac, 6));
            rt.setTypeface(Typeface.DEFAULT_BOLD);
            remotePanel.addView(rt);
            remotePanel.addView(label(ac, "为「" + title + "」申请临时可用时长",
                    13.5f, mid(), 0, dp(ac, 18)));
            remotePanel.addView(label(ac, "选择申请时长", 13, mid(), 0, dp(ac, 8)));
            remotePanel.addView(durRow(ac));
            remotePanel.addView(label(ac, "⏱ 获批后仅在该应用前台打开时累计扣减，不打开不消耗（次日 0 点清零）",
                    11.5f, low(), dp(ac, 10), dp(ac, 18)));

            Button send = pill(ac, "发起远程申请", primary(), onPrimary(), true);
            send.setTypeface(Typeface.DEFAULT_BOLD);
            send.setOnClickListener(v -> {
                if (waitTitle != null) {
                    waitTitle.setText("已申请「" + title + "」· " + durText(selMinutes));
                }
                showPanel(4);
                actions.onRemoteRequest(selMinutes);
            });
            remotePanel.addView(send);
            Button rBack = pill(ac, "返回", cardC(), mid(), false);
            rBack.setOnClickListener(v -> showPanel(1));
            remotePanel.addView(rBack);

            // ---- 4. 远程等待面板 ----
            waitPanel = new LinearLayout(ac);
            waitPanel.setOrientation(LinearLayout.VERTICAL);
            waitPanel.setGravity(Gravity.CENTER);
            waitPanel.setVisibility(View.GONE);
            waitPanel.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            waitPanel.addView(label(ac, "⏳", 38, hi(), 0, dp(ac, 10)));
            waitTitle = label(ac, "已申请「" + title + "」· " + durText(waitingMinutes > 0 || waitingMinutes == Rules.GRANT_TODAY ? waitingMinutes : selMinutes),
                    19, hi(), 0, dp(ac, 8));
            waitTitle.setTypeface(Typeface.DEFAULT_BOLD);
            waitPanel.addView(waitTitle);
            waitText = label(ac, "等待管理员批准中（每 5 秒自动检查）…", 13.5f, mid(), 0, dp(ac, 20));
            waitPanel.addView(waitText);

            Button pollNow = pill(ac, "🔄 立即检查审批结果", primary(), onPrimary(), true);
            pollNow.setOnClickListener(v -> {
                setWaitText("正在向服务器查询最新状态…");
                actions.onRemotePollNow();
            });
            waitPanel.addView(pollNow);

            Button waitLeave = pill(ac, "返回桌面（后台继续等待，获批自动通知）", cardC(), hi(), false);
            waitLeave.setOnClickListener(v -> actions.onDismiss());
            waitPanel.addView(waitLeave);

            TextView cancel = label(ac, "取消本次申请", 13.5f, col(R.color.danger), dp(ac, 10), dp(ac, 8));
            cancel.setOnClickListener(v -> { actions.onRemoteCancel(); showPanel(1); });
            waitPanel.addView(cancel);

            wrap.addView(mainPanel);
            wrap.addView(pwPanel);
            wrap.addView(remotePanel);
            wrap.addView(waitPanel);

            if (waitingRemote) {
                showPanel(4);
            } else {
                showPanel(1);
            }

            container.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            container.setFocusableInTouchMode(true);
            container.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() != KeyEvent.ACTION_UP) return true;
                if (panel == 2 || panel == 3) showPanel(1);
                else actions.onDismiss();
                return true;
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE);
            lp.gravity = Gravity.CENTER;
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);

            wm.addView(container, lp);
            root = container;
            ctx = ac;
            shownFor = pkg;
            container.requestFocus();
            Log.i(TAG, "block shown: " + pkg + (waitingRemote ? " (waiting remote)" : ""));
        } catch (Throwable t) {
            Log.e(TAG, "showBlock failed", t);
        }
    }

    private static LinearLayout metricCol(Context c, String labelText, String valueText) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextView v = label(c, valueText, 15.5f, hi(), 0, dp(c, 3));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        TextView k = label(c, labelText, 11.5f, low(), 0, 0);
        col.addView(v);
        col.addView(k);
        return col;
    }

    // ---------- 时长选择芯片行 ----------

    private static View durRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        TextView[] chips = new TextView[DUR_VALUES.length];
        for (int i = 0; i < DUR_VALUES.length; i++) {
            final int val = DUR_VALUES[i];
            TextView t = new TextView(c);
            t.setText(DUR_LABELS[i]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(c, 2), dp(c, 10), dp(c, 2), dp(c, 10));
            t.setSingleLine(true);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(c, 3), 0, dp(c, 3), 0);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> { selMinutes = val; syncDur(); });
            chips[i] = t;
            row.addView(t);
        }
        if (durChips == null) {
            durChips = chips;
        } else {
            TextView[] merged = new TextView[durChips.length + chips.length];
            System.arraycopy(durChips, 0, merged, 0, durChips.length);
            System.arraycopy(chips, 0, merged, durChips.length, chips.length);
            durChips = merged;
        }
        syncDur();
        return row;
    }

    private static void syncDur() {
        if (durChips == null) return;
        for (int i = 0; i < durChips.length; i++) {
            TextView t = durChips[i];
            boolean on = DUR_VALUES[i % DUR_VALUES.length] == selMinutes;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(t.getContext(), 11));
            g.setColor(on ? primary() : cardC());
            if (!on) g.setStroke(dp(t.getContext(), 1), line());
            t.setBackground(g);
            t.setTextColor(on ? onPrimary() : mid());
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private static int panel = 1;

    /** 1=主面板 2=密码 3=远程选时长 4=远程等待 */
    private static void showPanel(int which) {
        if (mainPanel == null) return;
        panel = which;
        mainPanel.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        pwPanel.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
        remotePanel.setVisibility(which == 3 ? View.VISIBLE : View.GONE);
        waitPanel.setVisibility(which == 4 ? View.VISIBLE : View.GONE);
        if (which == 2 && pad != null) {
            pad.clear();
            pad.error("");
        }
    }

    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());

    private static void onUi(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) r.run();
        else UI.post(r);
    }

    static void applyCooldown(long readyAt, int useMin, int intervalMin) {
        onUi(() -> {
            if (coolBtn == null || coolCap == null) return;
            Context c = coolBtn.getContext();
            boolean ready = readyAt == 0L || readyAt <= Rules.nowCached();
            if (ready) {
                coolBtn.setText("⚡ 应急使用 " + useMin + " 分钟");
                coolBtn.setEnabled(true);
                coolBtn.setAlpha(1f);
                coolCap.setText("按前台使用时间扣减 · 触发后整机进入 " + intervalMin + " 分钟冷却");
            } else {
                long left = Math.max(0, readyAt - Rules.nowCached());
                coolBtn.setText("应急冷却中 · " + fmtLeft(left));
                coolBtn.setEnabled(false);
                coolBtn.setAlpha(0.6f);
                coolCap.setText("整机冷却中，结束后恢复 " + useMin + " 分钟应急额度");
            }
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(c, 15));
            g.setColor(ready ? primaryContainer() : cardHiC());
            if (!ready) g.setStroke(dp(c, 1), lineVar());
            coolBtn.setBackground(g);
            coolBtn.setTextColor(ready ? onPrimaryContainer() : low());
        });
    }

    private static String fmtLeft(long ms) {
        long s = (ms + 999) / 1000;
        return (s / 60) + " 分 " + String.format(java.util.Locale.US, "%02d", s % 60) + " 秒";
    }

    static void toastGuard(Context c, String msg) {
        onUi(() -> {
            try {
                android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    static void setPasswordError(String msg) {
        onUi(() -> { if (pad != null) pad.error(msg); });
    }

    static void setWaitText(String msg) {
        onUi(() -> { if (waitText != null) waitText.setText(msg); });
    }

    // ---------- 哨兵窗 ----------
    private static View sentinel;

    static synchronized void ensureSentinel(Context c, boolean want) {
        try {
            Context ac = c.getApplicationContext();
            WindowManager wm = (WindowManager) ac.getSystemService(Context.WINDOW_SERVICE);
            if (!want || !Settings.canDrawOverlays(ac)) {
                if (sentinel != null) {
                    try { wm.removeView(sentinel); } catch (Exception ignored) {}
                    sentinel = null;
                }
                return;
            }
            if (sentinel != null && sentinel.isAttachedToWindow()) return;
            View v = new View(ac);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSPARENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.alpha = 0f;
            wm.addView(v, lp);
            sentinel = v;
        } catch (Throwable t) {
            Log.w(TAG, "sentinel", t);
        }
    }

    static synchronized void hide() {
        if (root == null || ctx == null) { root = null; shownFor = null; return; }
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(root);
        } catch (Exception ignored) {}
        root = null; ctx = null; shownFor = null; panel = 1;
        mainPanel = null; pwPanel = null; remotePanel = null; waitPanel = null;
        durChips = null; selMinutes = 30;
        coolBtn = null; coolCap = null;
        pad = null; waitTitle = null; waitText = null;
    }

    private static TextView label(Context c, String s, float sp, int color, int top, int bottom) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, top, 0, bottom);
        return t;
    }

    private static Button pill(Context c, String s, int bg, int fg, boolean filled) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(fg);
        b.setMinHeight(dp(c, 50));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, 15));
        g.setColor(bg);
        if (!filled) g.setStroke(dp(c, 1), line());
        b.setBackground(g);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        b.setLayoutParams(lp);
        b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        return b;
    }
}
