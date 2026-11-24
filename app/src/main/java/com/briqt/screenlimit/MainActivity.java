package com.briqt.screenlimit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 守护端主界面，底栏三页：
 *   守护 — 实时守护状态、当前生效的临时授权（可一键提前结束）、今日使用排行（点按直改规则）、受控时段（含快捷预设）
 *   应用 — 固定顶栏搜索与状态筛选、智能排序应用列表、单步底部抽屉改状态+配额
 *   更多 — 防绕过与基础工具白名单、应急使用参数、后台保活、密码与远程服务器
 *
 * 全部用平台原生控件构建，无 XML 布局、无第三方 UI 依赖。
 */
public class MainActivity extends Activity {

    // 应用名单筛选段
    private static final int F_ALL = 0, F_MANAGED = 4, F_GRAY = 2, F_DENY = 3, F_WHITE = 1;
    private static final int TAB_GUARD = 0, TAB_APPS = 1, TAB_MORE = 2;

    private AppCache cache;
    private List<AppCache.App> apps;

    private LinearLayout statusBody, grantsCardWrap, grantsBody, segRow, rows, presetRow;
    private TextView statusTitle, statusDesc, listStat, lockNowBtn;
    private EditText filter;
    private Button winStartBtn, winEndBtn, coolUseBtn, coolIntervalBtn, batValBtn, pwRow, srvRow, tokRow;
    private View windowRows;
    private Switch master, guardSw, recentsSw;

    private LinearLayout usageBody;
    private TextView usageTitle, usageDesc;
    private boolean expandAllUsage;
    private FrameLayout host;

    /**
     * 本次停留是否已验过密码。
     * 正常离开页面（Home / 锁屏 / 切别的应用）立即作废；
     * 若是从本页内部点击「去设置」跳往系统权限/电池设置页，给 2 分钟自跳宽限，避免配一圈权限反复重输密码。
     */
    private boolean unlockedNow;
    private long internalSettingsJumpAt;
    private boolean locked;
    private FrameLayout pinLayer;
    private PinPad setupPad;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private int seg = F_ALL;
    private boolean showAllPerms;
    private int tab = TAB_GUARD;
    private ScrollView pageGuard, pageMore, appsScroll;
    private LinearLayout pageApps;
    private LinearLayout navBar;
    private TextView tabGuard, tabApps, tabMore;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cache = new AppCache(getApplicationContext());
        RemoteConfig.ensureIconVisible(this);
        applyHideRecents(Rules.hideRecents(this));
        if (getActionBar() != null) getActionBar().hide();
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        pageGuard = page(header(), statusCard(), grantsCard(), usageCard(), rulesCard());
        pageApps = appsPage();
        pageMore = page(hardenCard(), emergencyCard(), keepAliveCard(), securityCard(), footer());

        host = new FrameLayout(this);
        host.addView(pageGuard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(pageApps, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(pageMore, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        navBar = navBar();
        LinearLayout shell = col();
        shell.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        shell.addView(navBar);
        applyShellInsets(shell, host);
        FrameLayout root = new FrameLayout(this);
        root.addView(shell);
        pinLayer = new FrameLayout(this);
        pinLayer.setBackgroundColor(c(R.color.surface));
        pinLayer.setVisibility(View.GONE);
        pinLayer.setClickable(true);
        pinLayer.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(0, in.getSystemWindowInsetTop(), 0, in.getSystemWindowInsetBottom());
            return in;
        });
        root.addView(pinLayer);
        setContentView(root);
        showTab(TAB_GUARD);
    }

    private ScrollView page(View... children) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = col();
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        for (View child : children) root.addView(child);
        sv.addView(root);
        return sv;
    }

    private LinearLayout navBar() {
        LinearLayout bar = col();
        bar.setBackgroundColor(c(R.color.surface_container));
        View line = new View(this);
        line.setBackgroundColor(c(R.color.outline_variant));
        bar.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        tabGuard = navItem("守护概览");
        tabApps = navItem("应用规则");
        tabMore = navItem("更多设置");
        tabGuard.setOnClickListener(v -> showTab(TAB_GUARD));
        tabApps.setOnClickListener(v -> showTab(TAB_APPS));
        tabMore.setOnClickListener(v -> showTab(TAB_MORE));
        row.addView(tabGuard, navLp());
        row.addView(tabApps, navLp());
        row.addView(tabMore, navLp());
        bar.addView(row);
        return bar;
    }

    private void showTab(int which) {
        tab = which;
        pageGuard.setVisibility(which == TAB_GUARD ? View.VISIBLE : View.GONE);
        pageApps.setVisibility(which == TAB_APPS ? View.VISIBLE : View.GONE);
        pageMore.setVisibility(which == TAB_MORE ? View.VISIBLE : View.GONE);
        paintNav(tabGuard, which == TAB_GUARD);
        paintNav(tabApps, which == TAB_APPS);
        paintNav(tabMore, which == TAB_MORE);
        if (which == TAB_GUARD) {
            refreshStatus();
            refreshGrants();
            refreshUsage();
            refreshRules();
        } else if (which == TAB_APPS) {
            buildRows();
        } else if (which == TAB_MORE) {
            refreshRules();
        }
    }

    private void paintNav(TextView t, boolean on) {
        t.setTextColor(c(on ? R.color.primary : R.color.text_low));
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private TextView navItem(String label) {
        TextView t = tv(label, 14, R.color.text_low);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, dp(14));
        return t;
    }

    private LinearLayout.LayoutParams navLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void applyShellInsets(View shell, View host) {
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, sysBottom, ime = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                l = bars.left; t = bars.top; r = bars.right; sysBottom = bars.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                sysBottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, 0);
            boolean keyboard = ime > sysBottom;
            navBar.setVisibility(keyboard ? View.GONE : locked ? View.INVISIBLE : View.VISIBLE);
            navBar.setPadding(0, 0, 0, keyboard ? 0 : sysBottom);
            host.setPadding(0, 0, 0, keyboard ? ime : 0);
            if (keyboard) {
                ScrollView cur = tab == TAB_APPS ? appsScroll : tab == TAB_MORE ? pageMore : pageGuard;
                if (cur != null) scrollFocusIntoView(cur);
            }
            return insets;
        });
        shell.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        internalSettingsJumpAt = 0L;
        if (MonitorService.INSTANCE == null) {
            startMonitor();
            getWindow().getDecorView().postDelayed(this::refreshStatus, 600);
        }
        apps = null;
        refreshStatus();
        refreshGrants();
        refreshRules();
        refreshUsage();
        buildRows();
        gate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelSetup();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (internalSettingsJumpAt > 0 && SystemClock.elapsedRealtime() - internalSettingsJumpAt < 120_000L) {
            return;
        }
        unlockedNow = false;
    }

    /** 内部发起跳转系统权限/管家设置页：标记自跳时间戳，2 分钟内返回免重复输密码。 */
    private void startSystemSettings(Intent intent) {
        internalSettingsJumpAt = SystemClock.elapsedRealtime();
        startActivity(intent);
    }

    // ---------------------------------------------------------------- 密码门

    private boolean unlocked() {
        return unlockedNow;
    }

    private void gate() {
        locked = Rules.hasPassword(this) && !unlocked();
        host.setVisibility(locked ? View.INVISIBLE : View.VISIBLE);
        navBar.setVisibility(locked ? View.INVISIBLE : View.VISIBLE);
        if (lockNowBtn != null) {
            lockNowBtn.setVisibility(Rules.hasPassword(this) && !locked ? View.VISIBLE : View.GONE);
        }
        if (!locked) {
            if (setupPad == null) pinLayer.setVisibility(View.GONE);
            return;
        }
        PinPad pad = new PinPad(this)
                .texts("输入管理密码", "修改限时规则需要管理者的 6 位密码")
                .footer("退出", v -> finish());
        pad.listener(pin -> {
            if (Rules.tryPassword(this, pin)) {
                unlockedNow = true;
                gate();
            } else {
                pad.error(Rules.pwFailText(this));
                holdWhileLocked(pad);
            }
        });
        showPinLayer(pad);
        holdWhileLocked(pad);
    }

    private void holdWhileLocked(PinPad pad) {
        long left = Rules.pwLockLeft(this);
        pad.setEnabled(left == 0);
        if (left == 0) return;
        pad.error(Rules.pwFailText(this));
        ui.postDelayed(() -> {
            if (pinLayer.getVisibility() != View.VISIBLE) return;
            if (Rules.pwLockLeft(this) == 0) {
                pad.setEnabled(true);
                pad.error("");
            } else {
                holdWhileLocked(pad);
            }
        }, 1000);
    }

    private void showPinLayer(PinPad pad) {
        pinLayer.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        FrameLayout center = new FrameLayout(this);
        center.addView(pad.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        sv.addView(center);
        pinLayer.addView(sv);
        pinLayer.setVisibility(View.VISIBLE);
        pinLayer.requestApplyInsets();
    }

    private void showPasswordDialog() {
        boolean change = Rules.hasPassword(this);
        final String[] first = {null};
        PinPad pad = new PinPad(this)
                .texts(change ? "设置新密码" : "设置管理密码", "6 位数字。之后进入本页、在拦截页解锁都用它")
                .footer("取消", v -> cancelSetup());
        pad.listener(pin -> {
            if (first[0] == null) {
                first[0] = pin;
                pad.texts("再输入一次", "确认刚才的 6 位密码");
                return;
            }
            if (!first[0].equals(pin)) {
                first[0] = null;
                pad.texts(change ? "设置新密码" : "设置管理密码", "6 位数字。之后进入本页、在拦截页解锁都用它");
                pad.error("两次不一致，请重新设置");
                return;
            }
            Rules.setPassword(this, pin);
            unlockedNow = true;
            cancelSetup();
            toast("密码已保存，之后进入本页需要输入");
            refreshRules();
            refreshStatus();
            gate();
        });
        setupPad = pad;
        showPinLayer(pad);
    }

    private void cancelSetup() {
        if (setupPad == null) return;
        setupPad = null;
        pinLayer.setVisibility(View.GONE);
        pinLayer.removeAllViews();
        gate();
    }

    @Override
    public void onBackPressed() {
        if (setupPad != null) { cancelSetup(); return; }
        if (locked) { finish(); return; }
        super.onBackPressed();
    }

    private void scrollFocusIntoView(final ScrollView sv) {
        if (sv == null) return;
        sv.postDelayed(() -> {
            View f = sv.findFocus();
            if (f == null || f == sv) return;
            int[] loc = new int[2];
            f.getLocationInWindow(loc);
            int visibleTop = sv.getPaddingTop();
            int visibleBottom = sv.getHeight() - sv.getPaddingBottom();
            if (loc[1] + f.getHeight() + dp(12) <= visibleBottom) return;
            sv.smoothScrollBy(0, loc[1] - visibleTop - dp(8));
        }, 200);
    }

    // ---------------------------------------------------------------- 顶部与概览卡片

    private View header() {
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, dp(2), 0, dp(10));

        TextView title = tv("屏幕限时守护", 25, R.color.text_hi);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lockNowBtn = tv("🔒 锁定", 12.5f, R.color.primary);
        lockNowBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable lbg = new GradientDrawable();
        lbg.setCornerRadius(dp(99));
        lbg.setColor(c(R.color.primary_container));
        lockNowBtn.setBackground(lbg);
        lockNowBtn.setOnClickListener(v -> {
            unlockedNow = false;
            gate();
        });
        topRow.addView(lockNowBtn);
        return topRow;
    }

    private View statusCard() {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(6), dp(16), dp(10));

        statusTitle = tv("", 18, R.color.text_hi);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusDesc = tv("", 12.5f, R.color.text_mid);
        LinearLayout texts = col();
        texts.addView(statusTitle);
        texts.addView(statusDesc);
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        statusBody = new LinearLayout(this);
        statusBody.setOrientation(LinearLayout.VERTICAL);
        card.addView(statusBody);
        return card;
    }

    /** 当前生效中的临时授权卡片：一目了然剩余可用时长，且支持一键「提前结束授权」立即恢复锁定。 */
    private View grantsCard() {
        grantsCardWrap = card();
        LinearLayout head = rowBody();
        head.setPadding(dp(16), dp(2), dp(16), dp(4));
        head.setMinimumHeight(0);
        TextView t = tv("⏱ 生效中的临时授权", 13.5f, R.color.primary);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hint = tv("未打开不消耗 · 次日清零", 11.5f, R.color.text_low);
        head.addView(hint);
        grantsCardWrap.addView(head);

        grantsBody = col();
        grantsCardWrap.addView(grantsBody);
        grantsCardWrap.setVisibility(View.GONE);
        return grantsCardWrap;
    }

    private void refreshGrants() {
        if (grantsCardWrap == null || grantsBody == null) return;
        Map<String, Integer> active = Rules.activeGrants(this);
        if (active.isEmpty()) {
            grantsCardWrap.setVisibility(View.GONE);
            return;
        }
        grantsCardWrap.setVisibility(View.VISIBLE);
        grantsBody.removeAllViews();
        for (Map.Entry<String, Integer> e : active.entrySet()) {
            final String pkg = e.getKey();
            int sec = e.getValue();
            grantsBody.addView(divider(dp(16)));
            LinearLayout r = rowBody();
            r.setPadding(dp(16), dp(6), dp(14), dp(6));

            ImageView icon = new ImageView(this);
            Drawable d = cache.icon(pkg);
            if (d != null) icon.setImageDrawable(d);
            int sz = dp(30);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
            ip.rightMargin = dp(10);
            r.addView(icon, ip);

            LinearLayout mid = col();
            TextView name = tv(cache.label(pkg), 14.5f, R.color.text_hi);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            String desc = sec == Rules.GRANT_TODAY
                    ? "今日不再限制（至 24:00）"
                    : ("剩余可用 " + fmtSecExact(sec) + "（打开应用时扣减）");
            TextView sub = tv(desc, 12, R.color.text_mid);
            mid.addView(name);
            mid.addView(sub);
            r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView revoke = tv("结束授权", 12, R.color.danger);
            revoke.setPadding(dp(10), dp(6), dp(10), dp(6));
            GradientDrawable rbg = new GradientDrawable();
            rbg.setCornerRadius(dp(8));
            rbg.setColor(c(R.color.danger_container));
            revoke.setBackground(rbg);
            revoke.setOnClickListener(v -> {
                Rules.revokeGrant(this, pkg);
                toast("已结束「" + cache.label(pkg) + "」的临时授权");
                refreshGrants();
                refreshUsage();
                buildRows();
            });
            r.addView(revoke);
            grantsBody.addView(r);
        }
    }

    private static String fmtSecExact(int sec) {
        if (sec <= 0) return "0 秒";
        int m = sec / 60, s = sec % 60;
        if (m == 0) return s + " 秒";
        if (s == 0) return m + " 分钟";
        return m + " 分 " + s + " 秒";
    }

    /** 今日使用卡片：点击任意应用可直接弹出该应用的规则设置抽屉。 */
    private View usageCard() {
        LinearLayout card = card();
        LinearLayout head = rowBody();
        head.setPadding(dp(16), dp(2), dp(16), dp(6));
        head.setMinimumHeight(0);
        usageTitle = tv("今日使用", 13, R.color.text_low);
        head.addView(usageTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        usageDesc = tv("", 11.5f, R.color.text_low);
        head.addView(usageDesc);
        card.addView(head);
        usageBody = col();
        card.addView(usageBody);
        return card;
    }

    private void refreshUsage() {
        if (usageBody == null) return;
        Map<String, Integer> stats = Rules.statsToday(this);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(stats.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        int total = 0;
        for (Map.Entry<String, Integer> e : list) total += e.getValue();
        usageDesc.setText(total < 60 ? "不到 1 分钟" : "共 " + fmtDur(total));

        usageBody.removeAllViews();
        if (list.isEmpty()) {
            TextView empty = tv("今天还没有使用记录", 12.5f, R.color.text_low);
            empty.setPadding(dp(16), dp(4), dp(16), dp(6));
            usageBody.addView(empty);
            return;
        }
        int max = Math.max(1, list.get(0).getValue());
        int limit = expandAllUsage ? list.size() : Math.min(6, list.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Integer> e = list.get(i);
            usageBody.addView(usageRow(e.getKey(), e.getValue(), max));
        }
        if (list.size() > 6) {
            TextView more = tv(expandAllUsage
                            ? "▲ 收起列表"
                            : ("▼ 展开全部 " + list.size() + " 个今日用过的应用"),
                    12, R.color.primary);
            more.setPadding(dp(16), dp(8), dp(16), dp(4));
            more.setOnClickListener(v -> {
                expandAllUsage = !expandAllUsage;
                refreshUsage();
            });
            usageBody.addView(more);
        }
    }

    private View usageRow(final String pkg, int sec, int max) {
        LinearLayout r = rowBody();
        r.setMinimumHeight(0);
        r.setPadding(dp(14), dp(6), dp(16), dp(6));
        clickable(r);
        r.setOnClickListener(v -> {
            AppCache.App target = findApp(pkg);
            if (target != null) showAppRuleSheet(target);
        });

        ImageView icon = new ImageView(this);
        Drawable d = cache.icon(pkg);
        if (d != null) icon.setImageDrawable(d);
        int sz = dp(28);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
        ip.rightMargin = dp(10);
        r.addView(icon, ip);

        LinearLayout mid = col();
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = tv(cache.label(pkg), 13.5f, R.color.text_hi);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int st = stateOf(pkg);
        String badgeText = st == F_WHITE ? "始终可用"
                : st == F_GRAY ? ("配额 " + Rules.grayQuota(this, pkg) + "m") : "禁止";
        TextView stTv = tv(badgeText + " · " + fmtDur(sec), 11.5f, R.color.text_mid);
        line.addView(stTv);
        mid.addView(line);

        int color = st == F_WHITE ? R.color.ok : st == F_GRAY ? R.color.primary : R.color.danger;
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(3));
        tg.setColor(c(R.color.surface_container_high));
        track.setBackground(tg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        tlp.topMargin = dp(4);
        track.setLayoutParams(tlp);
        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(3));
        bg.setColor(c(color));
        bar.setBackground(bg);
        float w = Math.max(0.03f, sec / (float) max);
        track.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - w));
        mid.addView(track);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private AppCache.App findApp(String pkg) {
        if (apps == null) apps = cache.listManageable(this);
        for (AppCache.App a : apps) {
            if (a.pkg.equals(pkg)) return a;
        }
        if (cache.isManageable(pkg)) {
            return new AppCache.App(pkg, cache.label(pkg), cache.isSystem(pkg), cache.isBasicTool(pkg));
        }
        return null;
    }

    private static String fmtDur(int sec) {
        if (sec < 60) return sec + " 秒";
        int m = sec / 60;
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }

    private View rulesCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("限时规则与时段"));

        LinearLayout mrow = rowBody();
        mrow.setPadding(dp(16), 0, dp(12), 0);
        mrow.addView(tv("启用限时", 15, R.color.text_hi),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        master = new Switch(this);
        master.setChecked(Rules.master(this));
        master.setOnCheckedChangeListener((v, on) -> {
            Rules.setMaster(this, on);
            refreshStatus();
            refreshRules();
            refreshUsage();
        });
        mrow.addView(master);
        card.addView(mrow);
        card.addView(divider(dp(16)));

        LinearLayout win = col();
        // 快捷时段预设胶囊行（解决 24:00 全天不好选的问题）
        presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(dp(14), dp(10), dp(14), dp(4));
        win.addView(presetRow);

        winStartBtn = valueButton();
        winEndBtn = valueButton();
        win.addView(clickRow("开始时间", winStartBtn, v -> pickTime(true)));
        win.addView(divider(dp(16)));
        win.addView(clickRow("结束时间", winEndBtn, v -> pickTime(false)));
        windowRows = win;
        card.addView(win);
        return card;
    }

    private void buildWindowPresets() {
        if (presetRow == null) return;
        presetRow.removeAllViews();
        int s = Rules.winStart(this), e = Rules.winEnd(this);
        boolean isAllDay = Rules.allDay(this);
        boolean isNight = (s == 22 * 60 && e == 7 * 60);
        boolean isWork = (s == 9 * 60 && e == 18 * 60);

        presetRow.addView(presetChip("全天 (00:00–24:00)", isAllDay, () -> applyWindowPreset(0, 1440)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
        presetRow.addView(presetChip("夜间 (22:00–07:00)", isNight, () -> applyWindowPreset(22 * 60, 7 * 60)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
        presetRow.addView(presetChip("工作 (09:00–18:00)", isWork, () -> applyWindowPreset(9 * 60, 18 * 60)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
    }

    private View presetChip(String text, boolean active, Runnable onClick) {
        TextView t = tv(text, 11.5f, active ? R.color.on_primary_container : R.color.text_mid);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(6), dp(7), dp(6), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(c(active ? R.color.primary_container : R.color.surface_container_high));
        g.setStroke(dp(1), c(active ? R.color.primary : R.color.outline_variant));
        t.setBackground(g);
        if (active) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private void applyWindowPreset(int start, int end) {
        Rules.setWindow(this, start, end);
        refreshRules();
        refreshStatus();
        refreshUsage();
    }

    // ---------------------------------------------------------------- 应用规则页

    private LinearLayout appsPage() {
        LinearLayout page = col();

        // 上方固定区：标题计数行 + 搜索框 + 4 态筛选胶囊（固定在顶部，不随下方列表滚走）
        LinearLayout topWrap = col();
        topWrap.setPadding(dp(16), dp(8), dp(16), dp(10));

        LinearLayout topCard = card();
        topCard.setPadding(0, dp(10), 0, dp(10));
        topCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = rowBody();
        head.setMinimumHeight(0);
        head.setPadding(dp(16), dp(2), dp(16), dp(8));
        TextView t = tv("应用规则名单", 13, R.color.text_low);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        listStat = tv("", 11.5f, R.color.text_low);
        head.addView(listStat);
        topCard.addView(head);

        filter = new EditText(this);
        filter.setHint("🔍 搜索应用名称");
        filter.setSingleLine(true);
        filter.setBackgroundResource(R.drawable.filter_bg);
        filter.setPadding(dp(14), dp(10), dp(14), dp(10));
        filter.setTextSize(14);
        filter.setTextColor(c(R.color.text_hi));
        filter.setHintTextColor(c(R.color.text_low));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = dp(14);
        fp.rightMargin = dp(14);
        topCard.addView(filter, fp);
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                buildRows();
                if (appsScroll != null) appsScroll.scrollTo(0, 0);
            }
            public void afterTextChanged(Editable e) {}
        });

        segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(dp(14), dp(10), dp(14), dp(2));
        hsv.setClipToPadding(false);
        hsv.addView(segRow);
        topCard.addView(hsv);

        topWrap.addView(topCard);
        page.addView(topWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 下方独立滚动区：仅滚动应用列表卡片
        appsScroll = new ScrollView(this);
        appsScroll.setFillViewport(true);
        LinearLayout listWrap = col();
        listWrap.setPadding(dp(16), 0, dp(16), dp(24));

        LinearLayout listCard = card();
        listCard.setPadding(0, dp(6), 0, dp(6));
        listCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rows = col();
        listCard.addView(rows);
        listWrap.addView(listCard);
        appsScroll.addView(listWrap);

        page.addView(appsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void seedBasicTools() {
        int n = 0;
        for (AppCache.App a : cache.listManageable(this)) {
            if (a.basicTool && !Rules.isWhitelisted(this, a.pkg)) {
                Rules.setWhitelisted(this, a.pkg, true);
                n++;
            }
        }
        toast(n > 0 ? "已加入 " + n + " 个系统基础工具" : "系统基础工具已全部在白名单中");
        apps = null;
        buildRows();
        refreshUsage();
    }

    // ---------------------------------------------------------------- 更多设置页

    private View hardenCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("守护与防绕过"));

        guardSw = new Switch(this);
        guardSw.setChecked(Rules.guardSettings(this));
        guardSw.setOnCheckedChangeListener((v, on) -> {
            Rules.setGuardSettings(this, on);
            if (on && !a11yOn()) toast("需要开启无障碍服务才会生效");
        });
        card.addView(switchRow("看守系统设置",
                "限时期间拦截卸载、无障碍与本应用权限页（需开启无障碍）",
                guardSw));
        card.addView(divider(dp(16)));
        card.addView(settingRow("基础工具白名单",
                "将电话、短信、相机、日历等系统工具加入白名单",
                actionButton("一键补齐"), v -> seedBasicTools()));
        return card;
    }

    /** 应急使用参数设置卡（对齐管理端与网页端，支持手机本机查看与修改）。 */
    private View emergencyCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("应急使用"));
        coolUseBtn = valueButton();
        coolIntervalBtn = valueButton();
        card.addView(clickRow("每次应急可用时长", coolUseBtn, v -> showCoolUseDialog()));
        card.addView(divider(dp(16)));
        card.addView(clickRow("两次应急冷却间隔", coolIntervalBtn, v -> showCoolIntervalDialog()));
        card.addView(hintText("仅在应用前台打开时扣减，用后整机进入冷却"));
        return card;
    }

    private View switchRow(String title, String desc, Switch sw) {
        LinearLayout r = rowBody();
        r.setPadding(dp(16), dp(9), dp(12), dp(9));
        LinearLayout mid = col();
        mid.addView(tv(title, 15, R.color.text_hi));
        TextView d = tv(desc, 12, R.color.text_mid);
        d.setPadding(0, dp(2), dp(8), 0);
        mid.addView(d);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(sw);
        return r;
    }

    private View settingRow(String title, String desc, Button actionBtn, View.OnClickListener l) {
        LinearLayout r = rowBody();
        r.setPadding(dp(16), dp(9), actionBtn != null ? dp(2) : dp(16), dp(9));
        if (l != null) {
            clickable(r);
            r.setOnClickListener(l);
        }
        LinearLayout mid = col();
        mid.addView(tv(title, 15, R.color.text_hi));
        TextView d = tv(desc, 12, R.color.text_mid);
        d.setPadding(0, dp(2), actionBtn != null ? dp(8) : 0, 0);
        mid.addView(d);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (actionBtn != null) r.addView(actionBtn);
        return r;
    }

    private Button actionButton(String text) {
        Button b = valueButton();
        b.setText(text);
        b.setTextColor(c(R.color.primary));
        return b;
    }

    private void syncBatteryBtn() {
        if (batValBtn == null) return;
        boolean ok = batteryOk();
        batValBtn.setText(ok ? "已开启" : "去设置");
        batValBtn.setTextColor(c(ok ? R.color.ok : R.color.primary));
    }

    private View keepAliveCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("后台保活"));

        card.addView(settingRow("允许自启动",
                "防止重启后守护无法自动拉起",
                actionButton("去设置"), v -> openStartupManager()));
        card.addView(divider(dp(16)));

        batValBtn = actionButton("");
        syncBatteryBtn();
        card.addView(settingRow("后台不受限",
                "电池耗电管理需设为完全允许后台行为",
                batValBtn, v -> askBattery()));
        card.addView(divider(dp(16)));

        card.addView(settingRow("最近任务加锁",
                "在多任务界面长按本应用卡片加锁，防一键清理",
                null, null));
        card.addView(divider(dp(16)));

        recentsSw = new Switch(this);
        recentsSw.setChecked(Rules.hideRecents(this));
        recentsSw.setOnCheckedChangeListener((v, on) -> {
            Rules.setHideRecents(this, on);
            applyHideRecents(on);
            toast(on ? "卡片已从最近任务里藏起" : "卡片已恢复显示");
        });
        card.addView(switchRow("隐藏后台卡片",
                "先加锁再开启，从多任务列表中隐藏卡片",
                recentsSw));
        return card;
    }

    private void applyHideRecents(boolean hide) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                t.setExcludeFromRecents(hide);
            }
        } catch (Exception e) {
            toast("切换失败：" + e.getMessage());
        }
    }

    private TextView hintText(String s) {
        TextView t = tv(s, 12, R.color.text_mid);
        t.setPadding(dp(16), dp(2), dp(16), dp(8));
        return t;
    }

    private void openStartupManager() {
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("允许自启动")
                .setMessage("这一项只能在厂商的管家里开，常见路径：\n\n"
                        + "· ColorOS：手机管家 → 权限与隐私 → 自启动管理 → 打开「屏幕限时守护」\n"
                        + "· 也可以在系统设置里搜「自启动」\n\n"
                        + "不开的话，手机重启后守护不会自己起来。")
                .setNegativeButton("知道了", null)
                .setPositiveButton("打开手机管家", (d, w) -> {
                    String[] mgr = {"com.coloros.phonemanager", "com.oplus.safecenter",
                                    "com.miui.securitycenter"};
                    for (String p : mgr) {
                        Intent i = getPackageManager().getLaunchIntentForPackage(p);
                        if (i == null) continue;
                        try {
                            startSystemSettings(i);
                            return;
                        } catch (Exception ignored) {}
                    }
                    toast("没找到厂商管家，请在系统设置里搜索「自启动」");
                })
                .show();
    }

    private View securityCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("安全与远程"));
        pwRow = valueButton();
        srvRow = valueButton();
        tokRow = valueButton();
        card.addView(clickRow("解锁密码（6 位数字）", pwRow, v -> showPasswordDialog()));
        card.addView(divider(dp(16)));
        card.addView(clickRow("远程授权服务器", srvRow, v -> showServerDialog()));
        card.addView(divider(dp(16)));
        card.addView(clickRow("设备令牌", tokRow, v -> showTokenDialog()));
        return card;
    }

    private View footer() {
        String ver;
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "?";
        }
        TextView t = tv("屏幕限时守护 v" + ver, 11, R.color.text_low);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    // ---------------------------------------------------------------- 状态探测

    private static final class Perm {
        final String name, fixHint;
        final boolean ok, required;
        final Runnable fix;
        Perm(String name, boolean ok, boolean required, String fixHint, Runnable fix) {
            this.name = name; this.ok = ok; this.required = required;
            this.fixHint = fixHint; this.fix = fix;
        }
    }

    private List<Perm> perms() {
        List<Perm> l = new ArrayList<>();
        l.add(new Perm("监控服务", MonitorService.INSTANCE != null, true, "点此启动常驻服务", () -> {
            startMonitor();
            toast(MonitorService.INSTANCE != null ? "已启动" : "启动失败");
            refreshStatus();
        }));
        l.add(new Perm("使用情况访问", usageGranted(), true, "判定前台应用所必需", () ->
                startSystemSettings(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));
        l.add(new Perm("显示在其他应用上层", Settings.canDrawOverlays(this), true, "拦截页所必需", () ->
                startSystemSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));
        l.add(new Perm("无障碍服务", a11yOn(), false, "开启后切换应用会被立刻发现（不开也能拦，只是慢一拍）", () ->
                startSystemSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        l.add(new Perm("后台不受限", batteryOk(), false,
                "建议开启，否则息屏后可能被系统杀掉。ColorOS 还要在「耗电管理」里选「完全允许后台行为」",
                this::askBattery));
        l.add(new Perm("解锁密码", Rules.hasPassword(this), false,
                "未设密码时任何人都能进本页改规则；设了之后进本页需先验密码", this::showPasswordDialog));
        long drift = Rules.clockDrift(this);
        if (Math.abs(drift) >= 120_000) {
            long m = Math.abs(drift) / 60_000;
            l.add(new Perm("系统时间", false, false,
                    "系统时间比守护时钟" + (drift > 0 ? "快" : "慢") + " " + m
                            + " 分钟，限时按守护时钟执行。确认系统时间无误可点此校准", () -> {
                Rules.reanchorClock(this);
                toast("已按当前系统时间校准");
                refreshStatus();
            }));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(new Perm("通知", notifOk(), false,
                    "开始限制、配额快用完、远程申请有结果时提醒一次", this::requestNotif));
        }
        return l;
    }

    private void askBattery() {
        Intent[] chain = {
            new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())),
            new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())),
        };
        for (Intent i : chain) {
            if (i.resolveActivity(getPackageManager()) == null) continue;
            try {
                startSystemSettings(i);
                return;
            } catch (Exception ignored) {}
        }
        toast("系统没给入口，请到设置里手动允许后台运行");
    }

    private boolean notifOk() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (notifOk()) {
            startSystemSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        internalSettingsJumpAt = SystemClock.elapsedRealtime();
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshStatus();
    }

    private void startMonitor() {
        try {
            startForegroundService(new Intent(this, MonitorService.class));
        } catch (Exception ignored) {}
    }

    private void refreshStatus() {
        List<Perm> ps = perms();
        int missReq = 0, missOpt = 0;
        for (Perm p : ps) {
            if (p.ok) continue;
            if (p.required) missReq++; else missOpt++;
        }
        int missing = missReq + missOpt;

        boolean on = Rules.master(this);
        boolean inWin = Rules.inWindow(this);
        String cool = !Rules.coolReady(this) ? " · 应急冷却中" : "";
        String advice = missOpt > 0 ? " · " + missOpt + " 项建议未开启" : "";
        if (missReq > 0) {
            statusTitle.setText("还有 " + missReq + " 项必需权限未完成");
            statusTitle.setTextColor(c(R.color.danger));
            statusDesc.setText("未完成前限时不会真正生效");
        } else if (!on) {
            statusTitle.setText("已就绪，未启用限时");
            statusTitle.setTextColor(c(R.color.text_hi));
            statusDesc.setText("打开下方「启用限时」即开始守护" + cool + advice);
        } else if (!inWin) {
            statusTitle.setText("已启用 · 当前不在受控时段");
            statusTitle.setTextColor(c(R.color.warn));
            statusDesc.setText(windowText() + " 之外不作限制" + cool + advice);
        } else {
            statusTitle.setText("● 正在守护中");
            statusTitle.setTextColor(c(R.color.ok));
            statusDesc.setText("生效时段：" + windowText() + cool + advice);
        }

        statusBody.removeAllViews();
        for (Perm p : ps) {
            if (p.ok && !showAllPerms) continue;
            statusBody.addView(divider(dp(16)));
            statusBody.addView(permRow(p));
        }
        if (missing < ps.size()) {
            statusBody.addView(togglePermsRow(ps.size() - missing));
        }
    }

    private View permRow(Perm p) {
        int tone = p.ok ? R.color.text_low : p.required ? R.color.danger : R.color.warn;
        LinearLayout r = rowBody();
        r.setPadding(dp(16), dp(4), dp(14), dp(4));
        clickable(r);
        r.addView(statusDot(p.ok, p.required));
        LinearLayout mid = col();
        mid.addView(tv(p.name + (p.ok || p.required ? "" : "（建议）"), 15, R.color.text_hi));
        TextView hint = tv(p.ok ? "已就绪" : p.fixHint, 11.5f, tone);
        mid.addView(hint);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!p.ok) {
            TextView go = tv("去设置", 13, R.color.primary);
            go.setPadding(dp(8), 0, dp(4), 0);
            r.addView(go);
        }
        r.setOnClickListener(v -> p.fix.run());
        return r;
    }

    private View togglePermsRow(int okCount) {
        TextView t = tv(showAllPerms ? "收起已完成项" : "✓ 已完成 " + okCount + " 项系统配置 · 点此展开",
                12, R.color.text_low);
        t.setPadding(dp(16), dp(10), dp(16), dp(6));
        t.setOnClickListener(v -> {
            showAllPerms = !showAllPerms;
            refreshStatus();
        });
        return t;
    }

    private View statusDot(boolean ok, boolean required) {
        int fg = ok ? R.color.ok : required ? R.color.danger : R.color.warn;
        int bg = ok ? R.color.ok_container : required ? R.color.danger_container : R.color.warn_container;
        TextView d = new TextView(this);
        d.setText(ok ? "✓" : "!");
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        d.setTextColor(c(fg));
        d.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(c(bg));
        d.setBackground(g);
        int sz = dp(24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.rightMargin = dp(12);
        d.setLayoutParams(lp);
        return d;
    }

    // ---------------------------------------------------------------- 规则刷新

    private String windowText() {
        int s = Rules.winStart(this), e = Rules.winEnd(this);
        if ((s == 0 && e >= 1440) || s == e) return "全天 (00:00–24:00)";
        return fmt(s) + " – " + (e >= 1440 ? "24:00" : fmt(e)) + (e < s ? "（跨天）" : "");
    }

    private void refreshRules() {
        boolean on = Rules.master(this);
        master.setChecked(on);
        windowRows.setAlpha(on ? 1f : 0.45f);
        winStartBtn.setText(fmt(Rules.winStart(this)));
        int e = Rules.winEnd(this);
        winEndBtn.setText((e >= 1440 ? "24:00" : fmt(e))
                + (e < Rules.winStart(this) ? " 跨天" : ""));
        buildWindowPresets();
        if (coolUseBtn != null) coolUseBtn.setText(Rules.coolUseMin(this) + " 分钟");
        if (coolIntervalBtn != null) coolIntervalBtn.setText(Rules.coolIntervalMin(this) + " 分钟");
        syncBatteryBtn();
        pwRow.setText(Rules.hasPassword(this) ? "已设置" : "未设置");
        srvRow.setText(TextUtils.isEmpty(Rules.serverAddr(this)) ? "未设置" : Rules.serverAddr(this));
        tokRow.setText(TextUtils.isEmpty(Rules.deviceToken(this)) ? "未设置" : "已设置");
        if (guardSw != null) guardSw.setChecked(Rules.guardSettings(this));
        if (recentsSw != null) recentsSw.setChecked(Rules.hideRecents(this));
        if (lockNowBtn != null) {
            lockNowBtn.setVisibility(Rules.hasPassword(this) && !locked ? View.VISIBLE : View.GONE);
        }
    }

    private String fmt(int min) {
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    private void pickTime(boolean start) {
        int raw = start ? Rules.winStart(this) : Rules.winEnd(this);
        int cur = Math.min(raw, 1439);
        new TimePickerDialog(this, R.style.AppDialog, (view, hour, minute) -> {
            int v = hour * 60 + minute;
            // 结束时间选 23:59 或 00:00（当开始为 00:00）时当作 24:00
            if (!start && (v >= 1439 || (v == 0 && Rules.winStart(this) == 0))) v = 1440;
            Rules.setWindow(this, start ? v : Rules.winStart(this),
                    start ? Rules.winEnd(this) : v);
            refreshRules();
            refreshStatus();
            refreshUsage();
        }, cur / 60, cur % 60, true).show();
    }

    // ---------------------------------------------------------------- 应用列表与单步规则抽屉

    private int stateOf(String pkg) {
        if (Rules.isWhitelisted(this, pkg)) return F_WHITE;
        if (Rules.isGray(this, pkg)) return F_GRAY;
        return F_DENY;
    }

    private void buildRows() {
        if (rows == null) return;
        if (apps == null) {
            apps = new ArrayList<>(cache.listManageable(this));
        }
        final Map<String, Integer> todayStats = Rules.statsToday(this);

        // 智能排序：1. 正在生效临时授权 2. 今日有使用记录（按分钟降序） 3. 已设配额应用 4. 第三方应用 5. 系统应用 6. 系统基础工具
        apps.sort((a, b) -> {
            boolean ta = Rules.grantActive(this, a.pkg);
            boolean tb = Rules.grantActive(this, b.pkg);
            if (ta != tb) return ta ? -1 : 1;
            int ma = todayStats.getOrDefault(a.pkg, 0) / 60;
            int mb = todayStats.getOrDefault(b.pkg, 0) / 60;
            if ((ma > 0) != (mb > 0)) return ma > 0 ? -1 : 1;
            if (ma != mb) return mb - ma;
            boolean ga = stateOf(a.pkg) == F_GRAY;
            boolean gb = stateOf(b.pkg) == F_GRAY;
            if (ga != gb) return ga ? -1 : 1;
            if (a.basicTool != b.basicTool) return a.basicTool ? 1 : -1;
            if (a.system != b.system) return a.system ? 1 : -1;
            return a.label.compareToIgnoreCase(b.label);
        });

        int nw = 0, ng = 0, nd = 0;
        for (AppCache.App a : apps) {
            switch (stateOf(a.pkg)) {
                case F_WHITE: nw++; break;
                case F_GRAY: ng++; break;
                default: nd++;
            }
        }
        listStat.setText("共 " + apps.size() + " 个");
        buildSegments(apps.size(), nw, ng, nd);

        String q = filter == null ? "" : filter.getText().toString().trim().toLowerCase();
        rows.removeAllViews();
        boolean first = true;
        int shown = 0;
        for (AppCache.App app : apps) {
            int st = stateOf(app.pkg);
            if (seg == F_MANAGED && st == F_WHITE) continue;
            if (seg != F_ALL && seg != F_MANAGED && st != seg) continue;
            if (!q.isEmpty() && !app.label.toLowerCase().contains(q)
                    && !app.pkg.toLowerCase().contains(q)) continue;
            if (!first) rows.addView(divider(dp(68)));
            first = false;
            rows.addView(rowFor(app));
            shown++;
        }
        if (shown == 0) {
            TextView empty = tv("没有匹配的应用", 13, R.color.text_low);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            rows.addView(empty);
        }
    }

    private void buildSegments(int all, int nw, int ng, int nd) {
        segRow.removeAllViews();
        segRow.addView(segChip("全部 " + all, F_ALL));
        segRow.addView(segChip("有配额 " + ng, F_GRAY));
        segRow.addView(segChip("默认禁止 " + nd, F_DENY));
        segRow.addView(segChip("始终可用 " + nw, F_WHITE));
    }

    private View segChip(String text, int which) {
        boolean on = seg == which;
        TextView t = tv(text, 12, on ? R.color.on_primary_container : R.color.text_mid);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        if (on) t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(c(on ? R.color.primary_container : R.color.surface_container_high));
        g.setStroke(dp(1), c(on ? R.color.primary : R.color.outline_variant));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            seg = which;
            buildRows();
            if (appsScroll != null) appsScroll.scrollTo(0, 0);
        });
        return t;
    }

    private View rowFor(AppCache.App app) {
        LinearLayout r = rowBody();
        r.setPadding(dp(14), dp(8), dp(12), dp(8));
        clickable(r);

        ImageView icon = new ImageView(this);
        Drawable d = cache.icon(app.pkg);
        if (d != null) icon.setImageDrawable(d);
        int sz = dp(38);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
        ip.rightMargin = dp(12);
        r.addView(icon, ip);

        LinearLayout mid = col();
        TextView name = tv(app.label, 14.5f, R.color.text_hi);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        mid.addView(name);
        mid.addView(tv(subtitleFor(app), 11, R.color.text_low));
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        r.addView(stateChip(app.pkg));
        r.setOnClickListener(v -> showAppRuleSheet(app));
        return r;
    }

    private String subtitleFor(AppCache.App app) {
        StringBuilder sb = new StringBuilder();
        if (Rules.grantActive(this, app.pkg)) {
            if (Rules.grantTodayActive(this, app.pkg)) {
                sb.append("⏱ 今日临时不限 · ");
            } else {
                sb.append("⏱ 授权余 ").append((Rules.grantRemainingSeconds(this, app.pkg) + 59) / 60).append("分 · ");
            }
        }
        int statSec = Rules.statSecondsToday(this, app.pkg);
        if (stateOf(app.pkg) == F_GRAY) {
            int used = Rules.usedSecondsToday(this, app.pkg) / 60;
            int quota = Rules.grayQuota(this, app.pkg);
            sb.append("配额已用 ").append(used).append("/").append(quota).append("分");
        } else if (statSec >= 60) {
            sb.append("今日已用 ").append(statSec / 60).append("分");
        } else if (app.basicTool) {
            sb.append("系统工具");
        } else if (app.system) {
            sb.append("系统应用");
        } else {
            sb.append("第三方应用");
        }
        return sb.toString();
    }

    private View stateChip(String pkg) {
        String text;
        int fg, bg;
        switch (stateOf(pkg)) {
            case F_WHITE:
                text = "始终可用"; fg = R.color.ok; bg = R.color.ok_container; break;
            case F_GRAY:
                text = Rules.grayQuota(this, pkg) + " 分/天";
                fg = R.color.primary; bg = R.color.primary_container; break;
            default:
                text = "默认禁止"; fg = R.color.danger; bg = R.color.danger_container;
        }
        TextView t = tv(text, 11.5f, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(c(bg));
        t.setBackground(g);
        return t;
    }

    // ---------------------------------------------------------------- 单步应用规则底部抽屉

    /**
     * 统一的单步应用规则抽屉：在一个底部弹窗内同时完成「三态切换 + 每日配额档位/自定义 + 提前结束临时授权」，
     * 消灭原先「先弹单选框、关掉再弹输入框」的割裂流程。
     */
    private void showAppRuleSheet(final AppCache.App app) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = col();
        box.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable bg = new GradientDrawable();
        float r = dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(c(R.color.surface_container));
        box.setBackground(bg);

        // 头部：图标 + 应用名 + 今日用量
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, dp(14));

        ImageView icon = new ImageView(this);
        Drawable ic = cache.icon(app.pkg);
        if (ic != null) icon.setImageDrawable(ic);
        int isz = dp(44);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
        ilp.rightMargin = dp(12);
        head.addView(icon, ilp);

        LinearLayout hText = col();
        TextView title = tv(app.label, 18, R.color.text_hi);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        int statSec = Rules.statSecondsToday(this, app.pkg);
        String statStr = statSec < 60 ? "今日尚未使用" : ("今日已使用 " + fmtDur(statSec));
        TextView sub = tv(statStr, 12, R.color.text_mid);
        hText.addView(title);
        hText.addView(sub);
        head.addView(hText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);

        // 若该应用当前有临时授权，提供一键结束入口
        if (Rules.grantActive(this, app.pkg)) {
            LinearLayout gBanner = new LinearLayout(this);
            gBanner.setOrientation(LinearLayout.HORIZONTAL);
            gBanner.setGravity(Gravity.CENTER_VERTICAL);
            gBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable gBg = new GradientDrawable();
            gBg.setCornerRadius(dp(12));
            gBg.setColor(c(R.color.warn_container));
            gBanner.setBackground(gBg);
            LinearLayout.LayoutParams gblp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gblp.bottomMargin = dp(14);
            gBanner.setLayoutParams(gblp);

            String gTxt = Rules.grantTodayActive(this, app.pkg)
                    ? "⏱ 当前「今日不限」授权生效中"
                    : ("⏱ 临时授权还剩 " + fmtSecExact(Rules.grantRemainingSeconds(this, app.pkg)));
            TextView gLabel = tv(gTxt, 12.5f, R.color.warn);
            gLabel.setTypeface(Typeface.DEFAULT_BOLD);
            gBanner.addView(gLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView gRevoke = tv("立即结束", 12, R.color.danger);
            gRevoke.setTypeface(Typeface.DEFAULT_BOLD);
            gRevoke.setPadding(dp(8), dp(4), dp(4), dp(4));
            gRevoke.setOnClickListener(v -> {
                Rules.revokeGrant(this, app.pkg);
                toast("已结束临时授权");
                d.dismiss();
                refreshGrants();
                refreshUsage();
                buildRows();
            });
            gBanner.addView(gRevoke);
            box.addView(gBanner);
        }

        // 三态选择卡片行
        final int[] selState = {stateOf(app.pkg)};
        final TextView[] stateCards = new TextView[3];
        final int[] stateVals = {F_WHITE, F_GRAY, F_DENY};
        final String[] stateTitles = {"始终可用", "每日配额", "默认禁止"};
        final String[] stateSubs = {"白名单不限制", "按天限时可用", "受控时段拦截"};

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.bottomMargin = dp(14);
        stateRow.setLayoutParams(srlp);

        // 配额设置区（仅选中 F_GRAY 时展开）
        final LinearLayout quotaBox = col();
        final EditText quotaEt = new EditText(this);
        final TextView errTv = tv(" ", 12, R.color.danger);

        final Runnable syncStateUI = () -> {
            for (int i = 0; i < 3; i++) {
                boolean on = selState[0] == stateVals[i];
                TextView sc = stateCards[i];
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(14));
                int bgCol = R.color.surface_container_high;
                int strokeCol = R.color.outline_variant;
                int fgCol = R.color.text_mid;
                if (on) {
                    if (stateVals[i] == F_WHITE) {
                        bgCol = R.color.ok_container; strokeCol = R.color.ok; fgCol = R.color.ok;
                    } else if (stateVals[i] == F_GRAY) {
                        bgCol = R.color.primary_container; strokeCol = R.color.primary; fgCol = R.color.on_primary_container;
                    } else {
                        bgCol = R.color.danger_container; strokeCol = R.color.danger; fgCol = R.color.danger;
                    }
                }
                g.setColor(c(bgCol));
                g.setStroke(dp(on ? 1.5f : 1f), c(strokeCol));
                sc.setBackground(g);
                sc.setTextColor(c(fgCol));
                sc.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
            quotaBox.setVisibility(selState[0] == F_GRAY ? View.VISIBLE : View.GONE);
            errTv.setText(" ");
        };

        for (int i = 0; i < 3; i++) {
            final int val = stateVals[i];
            TextView sc = new TextView(this);
            sc.setText(stateTitles[i] + "\n" + stateSubs[i]);
            sc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            sc.setLineSpacing(dp(2), 1f);
            sc.setGravity(Gravity.CENTER);
            sc.setPadding(dp(6), dp(12), dp(6), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 2) lp.rightMargin = dp(8);
            sc.setLayoutParams(lp);
            sc.setOnClickListener(v -> {
                selState[0] = val;
                syncStateUI.run();
            });
            stateCards[i] = sc;
            stateRow.addView(sc);
        }
        box.addView(stateRow);

        // 配额快捷档位与输入框
        TextView qTitle = tv("每天可用时长（分钟，0 点重置）", 12.5f, R.color.text_mid);
        qTitle.setPadding(0, 0, 0, dp(8));
        quotaBox.addView(qTitle);

        int[] presets = {10, 15, 30, 45, 60, 90, 120};
        HorizontalScrollView qHsv = new HorizontalScrollView(this);
        qHsv.setHorizontalScrollBarEnabled(false);
        LinearLayout qChips = new LinearLayout(this);
        qChips.setOrientation(LinearLayout.HORIZONTAL);
        for (final int m : presets) {
            TextView chip = tv(m + " 分钟", 12.5f, R.color.on_primary_container);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable cg = new GradientDrawable();
            cg.setCornerRadius(dp(10));
            cg.setColor(c(R.color.primary_container));
            chip.setBackground(cg);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(8);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> {
                quotaEt.setText(String.valueOf(m));
                quotaEt.setSelection(quotaEt.getText().length());
            });
            qChips.addView(chip);
        }
        qHsv.addView(qChips);
        LinearLayout.LayoutParams qhlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qhlp.bottomMargin = dp(10);
        quotaBox.addView(qHsv, qhlp);

        quotaEt.setHint("自定义分钟数（1–1440）");
        quotaEt.setText(String.valueOf(Rules.grayQuota(this, app.pkg)));
        quotaEt.setSelection(quotaEt.getText().length());
        quotaEt.setSingleLine(true);
        quotaEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        quotaEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        quotaEt.setTextColor(c(R.color.text_hi));
        quotaEt.setHintTextColor(c(R.color.text_low));
        quotaEt.setBackgroundResource(R.drawable.filter_bg);
        quotaEt.setPadding(dp(14), dp(12), dp(14), dp(12));
        quotaBox.addView(quotaEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(quotaBox);

        errTv.setPadding(dp(4), dp(6), 0, dp(8));
        box.addView(errTv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = sheetButton("取消", false);
        Button save = sheetButton("保存规则", true);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp1.rightMargin = dp(10);
        btns.addView(cancel, lp1);
        btns.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1.3f));
        box.addView(btns);

        syncStateUI.run();

        cancel.setOnClickListener(v -> d.dismiss());
        Runnable submit = () -> {
            if (selState[0] == F_WHITE) {
                Rules.setWhitelisted(this, app.pkg, true);
                Rules.setGray(this, app.pkg, null);
            } else if (selState[0] == F_GRAY) {
                int minutes = 0;
                try {
                    minutes = Integer.parseInt(quotaEt.getText().toString().trim());
                } catch (NumberFormatException ignored) {}
                if (minutes <= 0 || minutes > 1440) {
                    errTv.setText("请输入 1–1440 之间的分钟数");
                    return;
                }
                Rules.setWhitelisted(this, app.pkg, false);
                Rules.setGray(this, app.pkg, minutes);
            } else {
                Rules.setWhitelisted(this, app.pkg, false);
                Rules.setGray(this, app.pkg, null);
            }
            d.dismiss();
            refreshUsage();
            buildRows();
        };
        save.setOnClickListener(v -> submit.run());
        quotaEt.setImeOptions(EditorInfo.IME_ACTION_DONE);
        quotaEt.setOnEditorActionListener((v, id, ev) -> { submit.run(); return true; });

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
    }

    // ---------------------------------------------------------------- 应急与远程参数弹窗

    private void showCoolUseDialog() {
        LinearLayout presets = presetRowForNumbers(new int[]{3, 5, 10, 15});
        final EditText[] holder = new EditText[1];
        bindPresetClicks(presets, holder);
        holder[0] = inputSheet("每次应急可用时长",
                "点击拦截页「应急使用」后授予当前应用的可用分钟数（仅在该应用前台打开时扣减）。",
                "分钟数（1–60）", String.valueOf(Rules.coolUseMin(this)),
                InputType.TYPE_CLASS_NUMBER, presets, s -> {
                    int m = parsePositive(s);
                    if (m < 1 || m > 60) return "请输入 1–60 之间的分钟数";
                    Rules.setCoolParams(this, m, Rules.coolIntervalMin(this));
                    refreshRules();
                    return null;
                });
    }

    private void showCoolIntervalDialog() {
        LinearLayout presets = presetRowForNumbers(new int[]{30, 50, 60, 120});
        final EditText[] holder = new EditText[1];
        bindPresetClicks(presets, holder);
        holder[0] = inputSheet("两次应急冷却间隔",
                "用掉一次应急后，整机所有应用共同进入冷却的自然时间分钟数。",
                "分钟数（5–720）", String.valueOf(Rules.coolIntervalMin(this)),
                InputType.TYPE_CLASS_NUMBER, presets, s -> {
                    int m = parsePositive(s);
                    if (m < 5 || m > 720) return "请输入 5–720 之间的分钟数";
                    Rules.setCoolParams(this, Rules.coolUseMin(this), m);
                    refreshRules();
                    return null;
                });
    }

    private LinearLayout presetRowForNumbers(int[] values) {
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            int m = values[i];
            TextView t = tv(m + " 分钟", 13, R.color.on_primary_container);
            t.setTag(m);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(c(R.color.primary_container));
            t.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, i == values.length - 1 ? 0 : dp(8), dp(12));
            t.setLayoutParams(lp);
            presets.addView(t);
        }
        return presets;
    }

    private void bindPresetClicks(LinearLayout presets, final EditText[] holder) {
        for (int i = 0; i < presets.getChildCount(); i++) {
            View child = presets.getChildAt(i);
            final Object tag = child.getTag();
            child.setOnClickListener(v -> {
                EditText et = holder[0];
                if (et == null || tag == null) return;
                et.setText(String.valueOf(tag));
                et.setSelection(et.getText().length());
            });
        }
    }

    private static int parsePositive(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void showTokenDialog() {
        inputSheet("设备令牌",
                "与服务端 SL_DEVICE_TOKEN 一致。对不上服务端会直接拒绝，防止别人往你的审批页灌申请。",
                "至少 8 位", Rules.deviceToken(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                null, v -> {
                    String t = v.trim();
                    if (!t.isEmpty() && t.length() < 8) return "令牌至少 8 位";
                    Rules.setDeviceToken(this, t);
                    refreshRules();
                    return null;
                });
    }

    private void showServerDialog() {
        inputSheet("远程授权服务器",
                "拦截页「远程申请」发到这台服务器，管理者批准后手机自动解锁。带 https:// 走加密；只填 IP:端口是明文，仅限局域网。",
                "https://sl.example.com", Rules.serverAddr(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                null, v -> {
                    Rules.setServerAddr(this, v);
                    refreshRules();
                    return null;
                });
    }

    private EditText inputSheet(String title, String desc, String hint, String value, int inputType,
                                View extra, java.util.function.Function<String, String> onSave) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = col();
        box.setPadding(dp(22), dp(22), dp(22), dp(18));
        GradientDrawable bg = new GradientDrawable();
        float r = dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(c(R.color.surface_container));
        box.setBackground(bg);

        TextView t = tv(title, 19, R.color.text_hi);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView ds = tv(desc, 13, R.color.text_mid);
        ds.setLineSpacing(0, 1.25f);
        ds.setPadding(0, dp(6), 0, dp(16));
        box.addView(ds);
        if (extra != null) box.addView(extra);

        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value);
        et.setSelection(et.getText().length());
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        et.setTextColor(c(R.color.text_hi));
        et.setHintTextColor(c(R.color.text_low));
        et.setBackgroundResource(R.drawable.filter_bg);
        et.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView err = tv(" ", 12.5f, R.color.danger);
        err.setPadding(dp(4), dp(8), 0, dp(10));
        box.addView(err);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = sheetButton("取消", false);
        Button save = sheetButton("保存", true);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lp1.rightMargin = dp(10);
        btns.addView(cancel, lp1);
        btns.addView(save, new LinearLayout.LayoutParams(0, dp(50), 1f));
        box.addView(btns);

        cancel.setOnClickListener(v -> d.dismiss());
        Runnable submit = () -> {
            String e = onSave.apply(et.getText().toString());
            if (e == null) d.dismiss();
            else err.setText(e);
        };
        save.setOnClickListener(v -> submit.run());
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setOnEditorActionListener((v, id, ev) -> { submit.run(); return true; });

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        et.requestFocus();
        return et;
    }

    private Button sheetButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setStateListAnimator(null);
        b.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_outline);
        b.setTextColor(c(primary ? R.color.on_primary : R.color.text_mid));
        return b;
    }

    // ---------------------------------------------------------------- 控件工厂

    private int c(int id) { return getColor(id); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView tv(String s, float sp, int colorRes) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c(colorRes));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = tv(s, 13, R.color.text_low);
        t.setPadding(dp(16), dp(2), dp(16), dp(8));
        return t;
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout rowBody() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(dp(54));
        return l;
    }

    private void clickable(View v) {
        v.setClickable(true);
        TypedArray ta = obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
        v.setForeground(ta.getDrawable(0));
        ta.recycle();
    }

    private View divider(int leftMargin) {
        View v = new View(this);
        v.setBackgroundColor(c(R.color.outline_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = leftMargin;
        v.setLayoutParams(lp);
        return v;
    }

    private Button valueButton() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(c(R.color.text_mid));
        b.setBackground(null);
        b.setPadding(dp(8), 0, dp(14), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setClickable(false);
        b.setFocusable(false);
        return b;
    }

    private View clickRow(String title, Button valueBtn, View.OnClickListener l) {
        LinearLayout body = rowBody();
        body.setPadding(dp(16), 0, dp(4), 0);
        clickable(body);
        body.addView(tv(title, 15, R.color.text_hi),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(valueBtn);
        body.setOnClickListener(l);
        return body;
    }

    // ---------------------------------------------------------------- 权限探测

    private boolean a11yOn() {
        String e = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return e != null && e.contains(getPackageName() + "/");
    }

    private boolean batteryOk() {
        android.os.PowerManager pm = (android.os.PowerManager)
                getSystemService(android.content.Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean usageGranted() {
        android.app.AppOpsManager aom = (android.app.AppOpsManager)
                getSystemService(android.content.Context.APP_OPS_SERVICE);
        return aom.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
