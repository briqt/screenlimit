package com.briqt.screenlimit.admin;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 管理端主界面，底栏三页：审批 · 应用 · 设置。
 * 跨「应用」与「设置」共享工作副本 cfg，有未提交修改时按需浮现底部变更栏。
 */
public class AdminActivity extends Activity {
    private static final int REQ_NOTIF = 7;
    private static final int TAB_REQ = 0, TAB_APPS = 1, TAB_SET = 2;
    private static final String[] FILTER_LABELS = {"全部", "放行", "配额", "禁止", "已改"};
    private static final int[] QUOTA_PRESETS = {15, 30, 60};

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout host;
    private TextView topSync;
    private View saveBarView;
    private TextView barText;
    private Button barBtn;
    private TextView navReq, navApps, navSet;

    private int tab = TAB_REQ;
    private JSONArray reqs = new JSONArray();
    private int pendingCount;
    /** 审批页列表签名（含是否已存密码）。变了才重画，避免轮询冲掉正在输入的密码。 */
    private String shownReqSig = "";
    private final Map<String, String> pinDraft = new HashMap<>();
    private FrameLayout reqBannerSlot;

    private JSONObject cfg;
    private JSONObject cfgBase;
    private String listQuery = "";
    private int listFilter;
    private LinearLayout appsBox;
    private final TextView[] filterChips = new TextView[5];

    private boolean wantWatch;
    private boolean reqInFlight;
    private boolean cfgInFlight;
    private LinearLayout watchDiagBox;

    private final WatchService.SnapshotListener sseListener = new WatchService.SnapshotListener() {
        @Override
        public void onSnapshot(JSONArray latest, int pending) {
            if (Session.token(AdminActivity.this).isEmpty() || host == null) return;
            reqs = latest == null ? new JSONArray() : latest;
            pendingCount = pending;
            updatePendingBadge();
            String sig = sigOf(reqs, hasSavedPin());
            if (tab == TAB_REQ && !sig.equals(shownReqSig)) {
                shownReqSig = sig;
                renderRequests();
            }
        }

        @Override
        public void onUnauthorized() {
            if (!Session.token(AdminActivity.this).isEmpty()) {
                toast("登录已失效，请重新登录");
            }
            logout();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (Session.token(AdminActivity.this).isEmpty()) return;
            if (tab == TAB_REQ) fetchRequests();
            if (cfg != null && cfg.optBoolean("cfgPending") && diffs().isEmpty()) {
                fetchConfig(false);
            }
            h.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getActionBar() != null) getActionBar().hide();
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Session.token(this).isEmpty()) showLogin();
        else showShell();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!Session.token(this).isEmpty() && host != null) {
            selectTab(tabFromIntent(intent));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Session.token(this).isEmpty()) {
            WatchService.setSnapshotListener(sseListener);
            h.removeCallbacks(poll);
            h.post(poll);
            WatchService.sync(this);
            if (tab == TAB_REQ) refreshReqBanner();
            if (tab == TAB_SET) refreshWatchDiag();
        }
    }

    @Override
    protected void onPause() {
        WatchService.setSnapshotListener(null);
        h.removeCallbacks(poll);
        super.onPause();
    }

    private static int tabFromIntent(Intent intent) {
        if (intent == null) return TAB_REQ;
        String t = intent.getStringExtra("tab");
        if ("cfg".equals(t)) return TAB_APPS;
        if ("set".equals(t)) return TAB_SET;
        return TAB_REQ;
    }

    // ---------------------------------------------------------------- 登录

    private void showLogin() {
        h.removeCallbacks(poll);
        host = null;
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = col();
        root.setPadding(dp(24), dp(36), dp(24), dp(28));
        applyInsets(sv);
        sv.addView(root);

        TextView h1 = tv("限时管理", 26, R.color.text_hi);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        h1.setPadding(0, 0, 0, dp(24));
        root.addView(h1);

        EditText base = field("https://sl.example.com", Session.base(this));
        base.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field("管理员用户名", Session.user(this));
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = field("管理员密码", "");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        base.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        user.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        pass.setImeOptions(EditorInfo.IME_ACTION_GO);

        root.addView(fieldLabel("服务器"));
        root.addView(base);
        root.addView(gap(12));
        root.addView(fieldLabel("用户名"));
        root.addView(user);
        root.addView(gap(12));
        root.addView(fieldLabel("密码"));
        root.addView(pass);
        root.addView(gap(22));

        TextView err = tv("", 13, R.color.danger);
        err.setVisibility(View.GONE);
        Button go = button("登录", true);
        go.setOnClickListener(v -> {
            String b = Session.normalize(base.getText().toString());
            String u = user.getText().toString().trim();
            String p = pass.getText().toString();
            if (b.isEmpty() || u.isEmpty() || p.isEmpty()) {
                showErr(err, "服务器、用户名和密码都要填");
                return;
            }
            go.setEnabled(false);
            err.setVisibility(View.GONE);
            new Thread(() -> {
                try {
                    String tok = Api.login(b, u, p);
                    runOnUiThread(() -> {
                        Session.setBase(this, b);
                        Session.setUser(this, u);
                        Session.setToken(this, tok);
                        showShell();
                    });
                } catch (Api.Err e) {
                    runOnUiThread(() -> {
                        go.setEnabled(true);
                        showErr(err, e.getMessage());
                    });
                }
            }, "admin-login").start();
        });
        pass.setOnEditorActionListener((v, id, ev) -> {
            go.performClick();
            return true;
        });
        root.addView(go);
        root.addView(gap(8));
        root.addView(err);
        setContentView(sv);
        (user.getText().length() > 0 ? pass : user).requestFocus();
    }

    // ---------------------------------------------------------------- 主壳层

    private void showShell() {
        LinearLayout root = col();
        applyInsets(root);

        // 顶栏：左侧「限时管理」，右侧仅在应用/设置页低调显示同步状态
        LinearLayout top = row();
        top.setPadding(dp(16), dp(10), dp(16), dp(8));
        TextView h1 = tv("限时管理", 20, R.color.text_hi);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        h1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topSync = tv("", 12, R.color.text_low);
        topSync.setSingleLine(true);
        topSync.setEllipsize(TextUtils.TruncateAt.END);
        topSync.setVisibility(View.GONE);
        top.addView(h1);
        top.addView(topSync);
        root.addView(top);

        // 内容区
        host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 按需浮现的全局变更底栏（无修改时完全隐藏）
        saveBarView = buildSaveBar();
        root.addView(saveBarView);

        // 底栏三 Tab：审批 · 应用 · 设置
        LinearLayout navWrap = col();
        navWrap.setBackgroundColor(c(R.color.surface_container));
        View navLine = new View(this);
        navLine.setBackgroundColor(c(R.color.outline_variant));
        navWrap.addView(navLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        navReq = navItem("审批");
        navApps = navItem("应用");
        navSet = navItem("设置");
        navReq.setOnClickListener(v -> selectTab(TAB_REQ));
        navApps.setOnClickListener(v -> selectTab(TAB_APPS));
        navSet.setOnClickListener(v -> selectTab(TAB_SET));
        nav.addView(navReq, navLp());
        nav.addView(navApps, navLp());
        nav.addView(navSet, navLp());
        navWrap.addView(nav);
        root.addView(navWrap);

        setContentView(root);
        updatePendingBadge();

        int initTab = tabFromIntent(getIntent());
        selectTab(initTab);
        // 后台静默预拉一次配置与待批计数
        if (cfg == null) fetchConfig(false);
        if (initTab != TAB_REQ) fetchRequests();
    }

    private View buildSaveBar() {
        LinearLayout wrap = col();
        wrap.setBackgroundColor(c(R.color.surface_container));
        wrap.setVisibility(View.GONE);
        View line = new View(this);
        line.setBackgroundColor(c(R.color.outline_variant));
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        LinearLayout bar = row();
        bar.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout left = row();
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        barText = tv("", 13.5f, R.color.text_hi);
        barText.setTypeface(Typeface.DEFAULT_BOLD);
        TextView resetBtn = tv("重置", 13, R.color.text_mid);
        resetBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        resetBtn.setBackgroundResource(android.R.drawable.list_selector_background);
        resetBtn.setOnClickListener(v -> resetCfg());
        left.addView(barText);
        left.addView(resetBtn);

        barBtn = button("预览下发", true);
        barBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        barBtn.setTypeface(Typeface.DEFAULT_BOLD);
        barBtn.setMinHeight(0);
        barBtn.setMinimumHeight(0);
        barBtn.setMinWidth(0);
        barBtn.setMinimumWidth(0);
        barBtn.setPadding(dp(16), 0, dp(16), 0);
        barBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        barBtn.setOnClickListener(v -> preview());

        bar.addView(left);
        bar.addView(barBtn);
        wrap.addView(bar);
        return wrap;
    }

    private void selectTab(int which) {
        tab = which;
        reqBannerSlot = null;
        appsBox = null;
        watchDiagBox = null;
        paintNav();
        updateChrome();
        if (which == TAB_REQ) {
            renderRequests();
            fetchRequests();
        } else if (which == TAB_APPS) {
            if (cfg == null) {
                renderLoading("正在读取应用列表…");
                fetchConfig(true);
            } else {
                renderAppsTab();
            }
        } else {
            renderSettingsTab();
            if (cfg == null) fetchConfig(false);
        }
    }

    private void paintNav() {
        paintNavItem(navReq, tab == TAB_REQ);
        paintNavItem(navApps, tab == TAB_APPS);
        paintNavItem(navSet, tab == TAB_SET);
    }

    private void paintNavItem(TextView t, boolean on) {
        if (t == null) return;
        t.setTextColor(c(on ? R.color.primary : R.color.text_low));
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void updatePendingBadge() {
        if (navReq != null) {
            navReq.setText(pendingCount > 0 ? ("审批 · " + pendingCount) : "审批");
        }
    }

    /** 刷新顶栏同步状态与按需浮现的底部变更栏。 */
    private void updateChrome() {
        if (topSync != null) {
            if ((tab == TAB_APPS || tab == TAB_SET) && cfg != null) {
                String sync = cfg.optBoolean("cfgPending")
                        ? "⏳ 待手机生效"
                        : cfg.optString("appsAt", "").trim();
                if (sync.isEmpty()) {
                    topSync.setVisibility(View.GONE);
                } else {
                    topSync.setText(sync);
                    topSync.setVisibility(View.VISIBLE);
                }
            } else {
                topSync.setVisibility(View.GONE);
            }
        }
        int n = diffs().size();
        if (saveBarView != null) {
            if (n == 0) {
                saveBarView.setVisibility(View.GONE);
            } else {
                saveBarView.setVisibility(View.VISIBLE);
                if (barText != null) barText.setText("已改 " + n + " 项");
            }
        }
    }

    private void logout() {
        Session.clearToken(this);
        Session.setWatch(this, false);
        WatchService.sync(this);
        cfg = null;
        cfgBase = null;
        reqs = new JSONArray();
        pendingCount = 0;
        shownReqSig = "";
        pinDraft.clear();
        listQuery = "";
        listFilter = 0;
        host = null;
        topSync = null;
        saveBarView = null;
        navReq = null;
        navApps = null;
        navSet = null;
        reqBannerSlot = null;
        appsBox = null;
        watchDiagBox = null;
        showLogin();
    }

    // ---------------------------------------------------------------- Tab 0：审批

    private void fetchRequests() {
        if (reqInFlight) return;
        reqInFlight = true;
        new Thread(() -> {
            try {
                JSONObject j = Api.requests(this);
                runOnUiThread(() -> {
                    reqInFlight = false;
                    reqs = j.optJSONArray("reqs");
                    if (reqs == null) reqs = new JSONArray();
                    pendingCount = j.optInt("pending");
                    WatchService.pruneHandledNotifications(this, reqs);
                    updatePendingBadge();
                    String sig = sigOf(reqs, hasSavedPin());
                    if (tab == TAB_REQ && !sig.equals(shownReqSig)) {
                        shownReqSig = sig;
                        renderRequests();
                    }
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> {
                    reqInFlight = false;
                    onErr(e);
                });
            }
        }, "admin-req").start();
    }

    private void renderRequests() {
        if (host == null) return;
        WatchService.pruneHandledNotifications(this, reqs);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = page();

        // 仅在配置异常时展示单行轻提示条，正常态零横幅
        reqBannerSlot = new FrameLayout(this);
        root.addView(reqBannerSlot);
        refreshReqBanner();

        List<JSONObject> pending = new ArrayList<>();
        List<JSONObject> history = new ArrayList<>();
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject o = reqs.optJSONObject(i);
            if (o == null) continue;
            if ("pending".equals(o.optString("state"))) pending.add(o);
            else history.add(o);
        }

        for (JSONObject o : pending) {
            root.addView(requestCard(o));
        }

        if (pending.isEmpty()) {
            TextView empty = tv("暂无待处理的申请", 14, R.color.text_low);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(history.isEmpty() ? 64 : 24), 0, dp(24));
            root.addView(empty);
        }

        if (!history.isEmpty()) {
            root.addView(section("最近处理"));
            LinearLayout histCard = card();
            histCard.setPadding(dp(14), dp(6), dp(14), dp(6));
            for (int i = history.size() - 1; i >= 0; i--) {
                if (i < history.size() - 1) histCard.addView(divider());
                histCard.addView(historyRow(history.get(i)));
            }
            root.addView(histCard);
        }

        sv.addView(root);
        shownReqSig = sigOf(reqs, hasSavedPin());
        host.removeAllViews();
        host.addView(sv);
    }

    private void refreshReqBanner() {
        if (reqBannerSlot == null) return;
        reqBannerSlot.removeAllViews();
        if (!Session.watch(this)) {
            TextView b = bannerStrip("后台实时提醒未开启 · 去开启", R.color.warn, R.color.warn_container);
            b.setOnClickListener(v -> selectTab(TAB_SET));
            reqBannerSlot.addView(b);
            reqBannerSlot.setVisibility(View.VISIBLE);
        } else if (isAlertDegraded()) {
            TextView b = bannerStrip("通知横幅权限受限，可能收不到提醒 · 去处理", R.color.danger, R.color.danger_container);
            b.setOnClickListener(v -> selectTab(TAB_SET));
            reqBannerSlot.addView(b);
            reqBannerSlot.setVisibility(View.VISIBLE);
        } else {
            reqBannerSlot.setVisibility(View.GONE);
        }
    }

    private TextView bannerStrip(String text, int fg, int bg) {
        TextView t = tv(text, 13, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(c(bg));
        t.setBackground(g);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private View requestCard(JSONObject o) {
        LinearLayout card = card();
        String id = o.optString("id");
        String appName = o.optString("app", "").trim();
        if (appName.isEmpty()) appName = "未命名应用";

        // 顶行：左侧应用名称，右侧相对时间（不展示英文包名）
        LinearLayout head = row();
        TextView name = tv(appName, 16.5f, R.color.text_hi);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView age = tv(ageText(o.optString("age")), 12, R.color.text_low);
        age.setPadding(dp(8), 0, 0, 0);
        head.addView(name);
        head.addView(age);
        card.addView(head);

        String saved = Session.savedPin(this);
        boolean hasPin = isSixDigits(saved);
        EditText pinInput = null;

        if (!hasPin) {
            pinInput = field("输入 6 位解锁密码（批准后自动记住）", "");
            pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
            pinInput.setGravity(Gravity.CENTER);
            String draft = pinDraft.get(id);
            if (draft != null) pinInput.setText(draft);
            stylePinInput(pinInput, pinInput.getText().length() > 0);
            final EditText pinRef = pinInput;
            pinInput.addTextChangedListener(new SimpleWatcher(s -> {
                pinDraft.put(id, s);
                stylePinInput(pinRef, !s.isEmpty());
            }));
            card.addView(gap(10));
            card.addView(pinInput);
        }

        Button no = button("拒绝", false);
        Button ok = button("批准", true);
        final EditText finalPin = pinInput;

        ok.setOnClickListener(v -> {
            String pw = hasPin ? Session.savedPin(this)
                    : (finalPin == null ? "" : finalPin.getText().toString().trim());
            if (!isSixDigits(pw)) {
                toast("请输入 6 位数字解锁密码");
                return;
            }
            submitApprove(id, pw, !hasPin, ok, no);
        });
        if (hasPin) {
            final String labelForDialog = appName;
            ok.setOnLongClickListener(v -> {
                showManualApproveDialog(id, labelForDialog);
                return true;
            });
        }
        no.setOnClickListener(v -> submitDeny(id, ok, no));

        LinearLayout btns = row();
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        nl.rightMargin = dp(10);
        btns.addView(no, nl);
        btns.addView(ok, new LinearLayout.LayoutParams(0, dp(44), 2f));
        card.addView(gap(12));
        card.addView(btns);
        return card;
    }

    private static void stylePinInput(EditText pin, boolean hasDigits) {
        pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, hasDigits ? 17f : 13.5f);
        pin.setLetterSpacing(hasDigits ? 0.35f : 0f);
    }

    private View historyRow(JSONObject o) {
        LinearLayout r = row();
        r.setPadding(0, dp(7), 0, dp(7));
        String appName = o.optString("app", "").trim();
        if (appName.isEmpty()) appName = "未命名应用";
        TextView name = tv(appName, 14, R.color.text_hi);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView age = tv(ageText(o.optString("age")), 12, R.color.text_low);
        age.setPadding(dp(8), 0, dp(8), 0);

        String st = o.optString("state");
        TextView tag = chip(stateLabel(st), st);

        r.addView(name);
        r.addView(age);
        r.addView(tag);
        return r;
    }

    private void submitApprove(String id, String pw, boolean rememberNow, Button ok, Button no) {
        if (ok != null) ok.setEnabled(false);
        if (no != null) no.setEnabled(false);
        new Thread(() -> {
            try {
                Api.approve(this, id, pw);
                if (rememberNow) Session.setSavedPin(this, pw);
                WatchService.onRequestHandled(this, id);
                pinDraft.remove(id);
                runOnUiThread(() -> {
                    shownReqSig = "";
                    toast(rememberNow ? "已批准，已记住解锁密码" : "已批准");
                    fetchRequests();
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> {
                    if (ok != null) ok.setEnabled(true);
                    if (no != null) no.setEnabled(true);
                    onErr(e);
                });
            }
        }, "admin-approve").start();
    }

    private void submitDeny(String id, Button ok, Button no) {
        if (ok != null) ok.setEnabled(false);
        if (no != null) no.setEnabled(false);
        new Thread(() -> {
            try {
                Api.deny(this, id);
                WatchService.onRequestHandled(this, id);
                pinDraft.remove(id);
                runOnUiThread(() -> {
                    shownReqSig = "";
                    toast("已拒绝");
                    fetchRequests();
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> {
                    if (ok != null) ok.setEnabled(true);
                    if (no != null) no.setEnabled(true);
                    onErr(e);
                });
            }
        }, "admin-deny").start();
    }

    private void showManualApproveDialog(String id, String appName) {
        LinearLayout box = col();
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        EditText pin = field("输入 6 位数字解锁密码", "");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pin.setGravity(Gravity.CENTER);
        stylePinInput(pin, false);
        pin.addTextChangedListener(new SimpleWatcher(s -> stylePinInput(pin, !s.isEmpty())));
        box.addView(pin);

        AlertDialog d = new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("手动输入密码批准 · " + appName)
                .setView(box)
                .setPositiveButton("批准", null)
                .setNegativeButton("取消", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pw = pin.getText().toString().trim();
            if (!isSixDigits(pw)) {
                toast("请输入 6 位数字解锁密码");
                return;
            }
            d.dismiss();
            submitApprove(id, pw, false, null, null);
        });
    }

    // ---------------------------------------------------------------- 配置拉取与变更计算

    private void fetchConfig(boolean showError) {
        if (cfgInFlight) return;
        cfgInFlight = true;
        new Thread(() -> {
            try {
                JSONObject j = Api.config(this);
                runOnUiThread(() -> {
                    cfgInFlight = false;
                    // 有未提交修改时不用新数据冲掉手头编辑
                    if (cfg != null && !diffs().isEmpty()) return;
                    boolean wasNull = (cfg == null);
                    boolean pendingFlipped = cfg != null
                            && cfg.optBoolean("cfgPending") != j.optBoolean("cfgPending");
                    setCfg(j);
                    updateChrome();
                    boolean editingText = getCurrentFocus() instanceof EditText;
                    if (tab == TAB_APPS && (wasNull || (pendingFlipped && !editingText))) {
                        renderAppsTab();
                    } else if (tab == TAB_SET && (wasNull || (pendingFlipped && !editingText))) {
                        renderSettingsTab();
                    }
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> {
                    cfgInFlight = false;
                    if (showError || e.code == 401) onErr(e);
                    if (cfg == null && tab == TAB_APPS) {
                        renderRetry("读取应用列表失败，点击重试", () -> {
                            renderLoading("正在读取应用列表…");
                            fetchConfig(true);
                        });
                    }
                });
            }
        }, "admin-cfg").start();
    }

    private void setCfg(JSONObject j) {
        cfg = j;
        try {
            cfgBase = new JSONObject(j.toString());
        } catch (Exception e) {
            cfgBase = j;
        }
    }

    private void resetCfg() {
        if (cfgBase == null) return;
        try {
            cfg = new JSONObject(cfgBase.toString());
        } catch (Exception e) {
            return;
        }
        updateChrome();
        if (tab == TAB_APPS) renderAppsTab();
        else if (tab == TAB_SET) renderSettingsTab();
        toast("已还原修改");
    }

    private void onCfgEdited() {
        updateChrome();
        if (tab == TAB_APPS) paintFilters();
    }

    // ---------------------------------------------------------------- Tab 1：应用

    private void renderAppsTab() {
        if (host == null || cfg == null) return;
        LinearLayout frame = col();

        // 固定顶栏区（不随列表滚走）
        LinearLayout topArea = col();
        topArea.setPadding(dp(16), dp(4), dp(16), dp(4));
        if (cfg.optBoolean("noApps")) {
            topArea.addView(note("等待受控手机联网上报应用列表", R.color.warn, R.color.warn_container));
        }

        EditText search = field("搜索应用名称", listQuery);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new SimpleWatcher(t -> {
            listQuery = t;
            fillApps();
        }));
        topArea.addView(search);
        topArea.addView(gap(8));
        topArea.addView(filterRow());
        frame.addView(topArea);

        // 下方独立 ScrollView 承载应用列表卡片
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        appsBox = col();
        appsBox.setPadding(dp(16), dp(2), dp(16), dp(16));
        sv.addView(appsBox);
        frame.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fillApps();
        host.removeAllViews();
        host.addView(frame);
        updateChrome();
    }

    private View filterRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(4));
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int idx = i;
            TextView b = tv("", 11.5f, R.color.text_mid);
            b.setSingleLine(true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(7), 0, dp(7));
            b.setOnClickListener(v -> {
                listFilter = idx;
                paintFilters();
                fillApps();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i == FILTER_LABELS.length - 1 ? 0 : dp(6);
            row.addView(b, lp);
            filterChips[i] = b;
        }
        paintFilters();
        return row;
    }

    private void paintFilters() {
        int[] n = new int[5];
        JSONArray arr = cfg == null ? null : cfg.optJSONArray("apps");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            n[0]++;
            String st = a.optString("state", "deny");
            if ("white".equals(st)) n[1]++;
            else if ("gray".equals(st)) n[2]++;
            else n[3]++;
            if (appChanged(a)) n[4]++;
        }
        for (int i = 0; i < filterChips.length; i++) {
            TextView b = filterChips[i];
            if (b == null) continue;
            boolean on = listFilter == i;
            b.setText(FILTER_LABELS[i] + " " + n[i]);
            b.setBackground(chipBg(on));
            b.setTextColor(c(on ? R.color.on_primary : R.color.text_mid));
            b.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void fillApps() {
        if (appsBox == null) return;
        appsBox.removeAllViews();
        JSONArray arr = cfg == null ? null : cfg.optJSONArray("apps");
        if (arr == null || arr.length() == 0) {
            if (cfg != null && !cfg.optBoolean("noApps")) {
                TextView empty = tv("暂无应用列表", 14, R.color.text_low);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(48), 0, dp(24));
                appsBox.addView(empty);
            }
            return;
        }
        String q = listQuery.trim().toLowerCase(Locale.ROOT);
        LinearLayout card = card();
        card.setPadding(0, dp(2), 0, dp(2));
        int shown = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String state = a.optString("state", "deny");
            if (listFilter == 1 && !"white".equals(state)) continue;
            if (listFilter == 2 && !"gray".equals(state)) continue;
            if (listFilter == 3 && !"deny".equals(state)) continue;
            if (listFilter == 4 && !appChanged(a)) continue;
            String label = a.optString("label", "");
            String pkg = a.optString("pkg", "");
            if (!q.isEmpty()
                    && !label.toLowerCase(Locale.ROOT).contains(q)
                    && !pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (shown > 0) card.addView(divider());
            LinearLayout holder = col();
            bindAppRow(holder, a);
            card.addView(holder);
            shown++;
        }
        if (shown == 0) {
            TextView empty = tv(listFilter == 4 ? "暂无已修改的应用" : "没有匹配的应用", 14, R.color.text_low);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, dp(28));
            card.addView(empty);
        }
        appsBox.addView(card);
    }

    /** 单行应用：无英文包名，副标题按需显示，配额态展开 15/30/60 快捷胶囊 + 输入框。 */
    private void bindAppRow(LinearLayout holder, JSONObject a) {
        holder.removeAllViews();
        holder.setPadding(dp(14), dp(10), dp(14), dp(10));
        String state = a.optString("state", "deny");

        LinearLayout line = row();
        LinearLayout text = col();
        text.setPadding(0, 0, dp(8), 0);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String appTitle = a.optString("label", "").trim();
        if (appTitle.isEmpty()) appTitle = "未命名应用";
        TextView name = tv(appTitle, 15, R.color.text_hi);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);

        TextView sub = tv("", 11.5f, R.color.text_low);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(2), 0, 0);
        syncAppSub(sub, a);
        text.addView(sub);
        line.addView(text);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        String[] keys = {"white", "gray", "deny"};
        String[] labels = {"放行", "配额", "禁止"};
        for (int i = 0; i < 3; i++) {
            final String key = keys[i];
            boolean on = key.equals(state);
            TextView b = tv(labels[i], 12.5f, on ? R.color.on_primary : R.color.text_mid);
            b.setGravity(Gravity.CENTER);
            b.setBackground(segBg(on, key));
            b.setOnClickListener(v -> {
                if (key.equals(state)) return;
                try {
                    a.put("state", key);
                    if ("gray".equals(key) && a.optInt("quota", 0) <= 0) a.put("quota", 30);
                } catch (Exception ignored) {}
                onCfgEdited();
                if (!matchesCurrentFilter(a)) fillApps();
                else bindAppRow(holder, a);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(31));
            lp.leftMargin = dp(4);
            seg.addView(b, lp);
        }
        line.addView(seg);
        holder.addView(line);

        if ("gray".equals(state)) {
            LinearLayout qr = row();
            qr.setPadding(0, dp(9), 0, dp(1));
            TextView l = tv("每日配额", 13, R.color.text_mid);
            l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            qr.addView(l);

            final TextView[] pills = new TextView[QUOTA_PRESETS.length];
            final EditText qInput = smallNumber(String.valueOf(quotaOf(a)));
            qInput.setMinWidth(dp(56));
            qInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});

            for (int i = 0; i < QUOTA_PRESETS.length; i++) {
                final int preset = QUOTA_PRESETS[i];
                TextView p = tv(String.valueOf(preset), 12, R.color.text_mid);
                p.setGravity(Gravity.CENTER);
                p.setPadding(dp(9), dp(4), dp(9), dp(4));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.rightMargin = dp(5);
                p.setOnClickListener(v -> {
                    try {
                        a.put("quota", preset);
                    } catch (Exception ignored) {}
                    qInput.setText(String.valueOf(preset));
                    qInput.setSelection(qInput.getText().length());
                });
                qr.addView(p, plp);
                pills[i] = p;
            }
            paintQuotaPills(pills, quotaOf(a));

            qInput.addTextChangedListener(new SimpleWatcher(t -> {
                try {
                    int v = Integer.parseInt(t.trim());
                    if (v > 0 && v <= 1440) {
                        a.put("quota", v);
                        qInput.setError(null);
                    } else {
                        qInput.setError("1–1440");
                    }
                } catch (Exception e) {
                    qInput.setError("1–1440");
                }
                paintQuotaPills(pills, quotaOf(a));
                syncAppSub(sub, a);
                onCfgEdited();
            }));
            qr.addView(qInput);

            TextView unit = tv("分", 13, R.color.text_mid);
            unit.setPadding(dp(5), 0, 0, 0);
            qr.addView(unit);
            holder.addView(qr);
        }
    }

    private boolean matchesCurrentFilter(JSONObject a) {
        String st = a.optString("state", "deny");
        if (listFilter == 1) return "white".equals(st);
        if (listFilter == 2) return "gray".equals(st);
        if (listFilter == 3) return "deny".equals(st);
        if (listFilter == 4) return appChanged(a);
        return true;
    }

    private void syncAppSub(TextView sub, JSONObject a) {
        boolean changed = appChanged(a);
        int usedMin = a.optInt("usedMin", 0);
        String text;
        if (changed && usedMin > 0) {
            text = "● 已修改 · 今日已用 " + usedMin + " 分钟";
        } else if (changed) {
            text = "● 已修改";
        } else if (usedMin > 0) {
            text = "今日已用 " + usedMin + " 分钟";
        } else {
            text = "";
        }
        if (text.isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(text);
            sub.setTextColor(c(changed ? R.color.primary : R.color.text_low));
            sub.setVisibility(View.VISIBLE);
        }
    }

    private void paintQuotaPills(TextView[] pills, int currentQuota) {
        for (int i = 0; i < QUOTA_PRESETS.length; i++) {
            TextView p = pills[i];
            if (p == null) continue;
            boolean active = currentQuota == QUOTA_PRESETS[i];
            p.setBackground(miniPillBg(active));
            p.setTextColor(c(active ? R.color.on_primary_container : R.color.text_mid));
            p.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    // ---------------------------------------------------------------- Tab 2：设置

    private void renderSettingsTab() {
        if (host == null) return;
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = page();

        // 第一组：受控端规则
        root.addView(section("受控端规则"));
        if (cfg == null) {
            LinearLayout loadingCard = card();
            TextView tip = tv(cfgInFlight ? "正在读取受控端规则…" : "暂未读取到受控端规则，点击重试", 13.5f, R.color.text_low);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(10), 0, dp(10));
            loadingCard.setOnClickListener(v -> {
                fetchConfig(true);
                renderSettingsTab();
            });
            loadingCard.addView(tip);
            root.addView(loadingCard);
        } else {
            // 卡片 1：核心规则
            LinearLayout core = card();
            core.addView(switchRow("启用限时", null, "master"));
            core.addView(divider());

            TextView summary = tv("", 12, R.color.text_low);
            TextView[] startRef = new TextView[1];
            TextView[] endRef = new TextView[1];
            TextView[] presetRefs = new TextView[3];
            Runnable syncWinUi = () -> {
                String ws = cfg.optString("winStart", "00:00");
                String we = cfg.optString("winEnd", "24:00");
                if (startRef[0] != null) startRef[0].setText(ws);
                if (endRef[0] != null) endRef[0].setText(we);
                summary.setText(winSummary(ws, we));
                paintWindowPresets(presetRefs, ws, we);
            };

            core.addView(timeRow("开始时间", "winStart", startRef, syncWinUi));
            core.addView(timeRow("结束时间", "winEnd", endRef, syncWinUi));

            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout presets = row();
            presets.setPadding(0, dp(4), 0, dp(4));
            String[][] winPresetDefs = {
                    {"全天", "00:00", "24:00"},
                    {"夜间 22:00–07:00", "22:00", "07:00"},
                    {"工作 09:00–18:00", "09:00", "18:00"}
            };
            for (int i = 0; i < winPresetDefs.length; i++) {
                final String ps = winPresetDefs[i][1];
                final String pe = winPresetDefs[i][2];
                TextView chip = tv(winPresetDefs[i][0], 12, R.color.text_mid);
                chip.setPadding(dp(10), dp(5), dp(10), dp(5));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i < winPresetDefs.length - 1) clp.rightMargin = dp(6);
                chip.setOnClickListener(v -> {
                    try {
                        cfg.put("winStart", ps);
                        cfg.put("winEnd", pe);
                    } catch (Exception ignored) {}
                    syncWinUi.run();
                    onCfgEdited();
                });
                presets.addView(chip, clp);
                presetRefs[i] = chip;
            }
            hsv.addView(presets);
            core.addView(hsv);

            summary.setPadding(dp(2), dp(2), dp(2), dp(4));
            core.addView(summary);
            syncWinUi.run();

            core.addView(divider());
            core.addView(switchRow("看守系统设置", "防卸载与防关权限", "guard"));
            root.addView(core);

            // 卡片 2：应急额度
            LinearLayout cool = card();
            numberRow(cool, "单次可用时长", "coolUse", 1, 60);
            cool.addView(divider());
            numberRow(cool, "全局冷却间隔", "coolIval", 1, 720);
            root.addView(cool);
        }

        // 第二组：通知与后台（本机设置，实时生效）
        root.addView(section("通知与后台"));
        root.addView(buildWatchSettingsCard());

        // 第三组：密码与账号
        root.addView(section("密码与账号"));
        root.addView(buildAccountCard());

        sv.addView(root);
        host.removeAllViews();
        host.addView(sv);
        updateChrome();
    }

    private void paintWindowPresets(TextView[] refs, String s, String e) {
        boolean[] active = {
                (s != null && s.equals(e)) || ("00:00".equals(s) && ("24:00".equals(e) || "23:59".equals(e))),
                "22:00".equals(s) && "07:00".equals(e),
                "09:00".equals(s) && "18:00".equals(e)
        };
        for (int i = 0; i < refs.length; i++) {
            TextView t = refs[i];
            if (t == null) continue;
            t.setBackground(miniPillBg(active[i]));
            t.setTextColor(c(active[i] ? R.color.on_primary_container : R.color.text_mid));
            t.setTypeface(active[i] ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private View buildWatchSettingsCard() {
        LinearLayout card = card();
        LinearLayout r = row();
        r.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout text = col();
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(tv("实时审批提醒", 15, R.color.text_hi));
        TextView sub = tv("后台保持连接，新申请横幅响铃", 12, R.color.text_low);
        sub.setPadding(0, dp(2), dp(8), 0);
        text.addView(sub);

        Switch sw = new Switch(this);
        sw.setChecked(Session.watch(this));
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on && !notifGranted()) {
                b.setChecked(false);
                wantWatch = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
            Session.setWatch(this, on);
            WatchService.sync(this);
            refreshWatchDiag();
        });
        r.addView(text);
        r.addView(sw);
        card.addView(r);

        watchDiagBox = col();
        card.addView(watchDiagBox);
        refreshWatchDiag();
        return card;
    }

    private void refreshWatchDiag() {
        if (watchDiagBox == null) return;
        watchDiagBox.removeAllViews();
        if (!Session.watch(this)) return;

        WatchService.ensureChannels(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean battOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean appNotifOk = notifGranted() && (nm == null || nm.areNotificationsEnabled());
        NotificationChannel alertCh = nm == null ? null : nm.getNotificationChannel(WatchService.CH_ALERT);
        int alertImp = alertCh == null ? NotificationManager.IMPORTANCE_HIGH : alertCh.getImportance();
        boolean alertDegraded = !appNotifOk || alertImp < NotificationManager.IMPORTANCE_HIGH;

        NotificationChannel ongoingCh = nm == null ? null : nm.getNotificationChannel(WatchService.CH_ONGOING);
        boolean ongoingHidden = ongoingCh != null && ongoingCh.getImportance() == NotificationManager.IMPORTANCE_NONE;

        // 1. 后台不受限
        watchDiagBox.addView(divider());
        watchDiagBox.addView(watchStatusRow(
                "后台不受限", null, R.color.text_low,
                battOk ? "已开启" : "未开启 · 去开启",
                battOk ? R.color.ok : R.color.warn,
                v -> {
                    if (battOk) openAppDetails();
                    else askIgnoreBattery();
                }));

        // 2. 横幅与锁屏权限 + 测试弹窗
        watchDiagBox.addView(divider());
        LinearLayout r2 = row();
        LinearLayout left2 = col();
        left2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left2.setPadding(dp(2), dp(7), dp(8), dp(7));
        left2.setBackgroundResource(android.R.drawable.list_selector_background);
        left2.addView(tv("横幅与锁屏权限", 14, alertDegraded ? R.color.danger : R.color.text_hi));
        if (alertDegraded) {
            String errSub = (!appNotifOk || alertImp == NotificationManager.IMPORTANCE_NONE)
                    ? "通知已关闭 · 点此开启"
                    : "优先级受限 · 点此恢复";
            TextView st = tv(errSub, 11.5f, R.color.danger);
            st.setPadding(0, dp(2), 0, 0);
            left2.addView(st);
        }
        left2.setOnClickListener(v -> openChannelSettings(WatchService.CH_ALERT));

        TextView testBtn = tv("测试弹窗", 12.5f, R.color.on_primary_container);
        testBtn.setTypeface(Typeface.DEFAULT_BOLD);
        testBtn.setBackgroundResource(R.drawable.btn_tonal);
        testBtn.setPadding(dp(11), dp(6), dp(11), dp(6));
        testBtn.setOnClickListener(v -> {
            toast("2 秒后弹出测试通知");
            WatchService.scheduleTestAlert(this, 2_000L);
        });
        r2.addView(left2);
        r2.addView(testBtn);
        watchDiagBox.addView(r2);

        // 3. 隐藏常驻通知
        watchDiagBox.addView(divider());
        watchDiagBox.addView(watchStatusRow(
                "隐藏常驻通知",
                "关闭后不显示常驻条，不影响保活",
                R.color.text_low,
                ongoingHidden ? "已隐藏" : "去关闭",
                ongoingHidden ? R.color.ok : R.color.primary,
                v -> openChannelSettings(WatchService.CH_ONGOING)));
    }

    private View watchStatusRow(String title, String sub, int subCol,
                                String actionLabel, int actionCol, View.OnClickListener click) {
        LinearLayout r = row();
        r.setPadding(dp(2), dp(7), dp(2), dp(7));
        r.setBackgroundResource(android.R.drawable.list_selector_background);
        LinearLayout left = col();
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv(title, 14, R.color.text_hi));
        if (sub != null && !sub.isEmpty()) {
            TextView st = tv(sub, 11.5f, subCol);
            st.setPadding(0, dp(2), dp(8), 0);
            left.addView(st);
        }
        TextView act = tv(actionLabel, 13, actionCol);
        act.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(left);
        r.addView(act);
        r.setOnClickListener(click);
        return r;
    }

    private View buildAccountCard() {
        LinearLayout card = card();

        // 预设解锁密码
        boolean hasPin = hasSavedPin();
        LinearLayout pinRow = row();
        pinRow.setPadding(dp(2), dp(8), dp(2), dp(8));
        pinRow.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView pinTitle = tv("预设解锁密码", 15, R.color.text_hi);
        pinTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pinState = tv(hasPin ? "已保存 · 修改" : "未设置", 13.5f,
                hasPin ? R.color.ok : R.color.text_low);
        if (hasPin) pinState.setTypeface(Typeface.DEFAULT_BOLD);
        pinRow.addView(pinTitle);
        pinRow.addView(pinState);
        pinRow.setOnClickListener(v -> showPresetPinDialog());
        card.addView(pinRow);

        card.addView(divider());

        // 当前账号 + 退出登录
        LinearLayout accRow = row();
        accRow.setPadding(dp(2), dp(6), dp(2), dp(6));
        LinearLayout accLeft = col();
        accLeft.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String user = Session.user(this);
        String domain = Session.base(this).replaceFirst("^https?://", "");
        accLeft.addView(tv(user.isEmpty() ? "当前账号" : user, 15, R.color.text_hi));
        if (!domain.isEmpty()) {
            TextView dom = tv(domain, 12, R.color.text_low);
            dom.setPadding(0, dp(2), dp(8), 0);
            accLeft.addView(dom);
        }

        TextView outBtn = tv("退出登录", 13.5f, R.color.danger);
        outBtn.setTypeface(Typeface.DEFAULT_BOLD);
        outBtn.setPadding(dp(10), dp(6), dp(2), dp(6));
        outBtn.setBackgroundResource(android.R.drawable.list_selector_background);
        outBtn.setOnClickListener(v -> new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("退出登录？")
                .setMessage("退出后后台实时提醒将停止。")
                .setPositiveButton("退出", (d, w) -> logout())
                .setNegativeButton("取消", null)
                .show());

        accRow.addView(accLeft);
        accRow.addView(outBtn);
        card.addView(accRow);
        return card;
    }

    private void showPresetPinDialog() {
        boolean hasPin = hasSavedPin();
        LinearLayout box = col();
        box.setPadding(dp(20), dp(14), dp(20), dp(4));
        EditText pin = field("输入 6 位数字解锁密码", hasPin ? Session.savedPin(this) : "");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pin.setGravity(Gravity.CENTER);
        stylePinInput(pin, pin.getText().length() > 0);
        if (pin.getText().length() > 0) pin.setSelection(pin.getText().length());
        pin.addTextChangedListener(new SimpleWatcher(s -> stylePinInput(pin, !s.isEmpty())));
        box.addView(pin);

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("预设解锁密码")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null);
        if (hasPin) {
            b.setNeutralButton("清除已存密码", (d, w) -> {
                Session.setSavedPin(this, "");
                shownReqSig = "";
                if (tab == TAB_SET) renderSettingsTab();
                toast("已清除解锁密码");
            });
        }
        AlertDialog d = b.create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pw = pin.getText().toString().trim();
            if (!isSixDigits(pw)) {
                toast("请输入 6 位数字密码");
                return;
            }
            Session.setSavedPin(this, pw);
            shownReqSig = "";
            d.dismiss();
            if (tab == TAB_SET) renderSettingsTab();
            toast("已保存解锁密码");
        });
    }

    // ---------------------------------------------------------------- 系统设置跳转与诊断

    private boolean isAlertDegraded() {
        WatchService.ensureChannels(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean appNotifOk = notifGranted() && (nm == null || nm.areNotificationsEnabled());
        NotificationChannel alertCh = nm == null ? null : nm.getNotificationChannel(WatchService.CH_ALERT);
        int alertImp = alertCh == null ? NotificationManager.IMPORTANCE_HIGH : alertCh.getImportance();
        return !appNotifOk || alertImp < NotificationManager.IMPORTANCE_HIGH;
    }

    private void askIgnoreBattery() {
        Intent[] chain = {
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))
        };
        for (Intent i : chain) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        toast("无法打开电池优化设置，请手动允许后台运行");
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("无法打开应用设置");
        }
    }

    private void openChannelSettings(String channelId) {
        Intent[] chain = {
                new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))
        };
        for (Intent i : chain) {
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        toast("无法打开通知渠道设置");
    }

    // ---------------------------------------------------------------- Diff、预览与下发

    private JSONObject baseApp(String pkg) {
        JSONArray arr = cfgBase == null ? null : cfgBase.optJSONArray("apps");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && pkg.equals(o.optString("pkg"))) return o;
        }
        return null;
    }

    private boolean appChanged(JSONObject a) {
        JSONObject b = baseApp(a.optString("pkg"));
        if (b == null) return false;
        String s0 = b.optString("state", "deny"), s1 = a.optString("state", "deny");
        if (!s0.equals(s1)) return true;
        return "gray".equals(s1) && quotaOf(b) != quotaOf(a);
    }

    private static int quotaOf(JSONObject a) {
        int q = a.optInt("quota", 30);
        return q > 0 ? q : 30;
    }

    private List<String> diffs() {
        List<String> out = new ArrayList<>();
        if (cfg == null || cfgBase == null) return out;
        if (cfg.optBoolean("master") != cfgBase.optBoolean("master")) {
            out.add("启用限时：" + onOff(cfgBase.optBoolean("master")) + " → " + onOff(cfg.optBoolean("master")));
        }
        String w0 = winSummary(cfgBase.optString("winStart"), cfgBase.optString("winEnd"));
        String w1 = winSummary(cfg.optString("winStart"), cfg.optString("winEnd"));
        if (!cfg.optString("winStart").equals(cfgBase.optString("winStart"))
                || !cfg.optString("winEnd").equals(cfgBase.optString("winEnd"))) {
            out.add("限制时段：" + w0 + " → " + w1);
        }
        if (cfg.optBoolean("guard") != cfgBase.optBoolean("guard")) {
            out.add("看守系统设置：" + onOff(cfgBase.optBoolean("guard")) + " → " + onOff(cfg.optBoolean("guard")));
        }
        if (cfg.optInt("coolUse") != cfgBase.optInt("coolUse")) {
            out.add("单次可用时长：" + cfgBase.optInt("coolUse") + " → " + cfg.optInt("coolUse") + " 分钟");
        }
        if (cfg.optInt("coolIval") != cfgBase.optInt("coolIval")) {
            out.add("全局冷却间隔：" + cfgBase.optInt("coolIval") + " → " + cfg.optInt("coolIval") + " 分钟");
        }
        JSONArray arr = cfg.optJSONArray("apps");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null || !appChanged(a)) continue;
            JSONObject b = baseApp(a.optString("pkg"));
            String label = a.optString("label", "").trim();
            if (label.isEmpty()) label = "未命名应用";
            out.add(label + "：" + stateText(b) + " → " + stateText(a));
        }
        return out;
    }

    private static String stateText(JSONObject a) {
        if (a == null) return "禁止";
        String s = a.optString("state", "deny");
        if ("white".equals(s)) return "放行";
        if ("gray".equals(s)) return "配额 " + quotaOf(a) + " 分钟";
        return "禁止";
    }

    private static String onOff(boolean b) {
        return b ? "开" : "关";
    }

    static String winSummary(String s, String e) {
        if (s == null || e == null || s.isEmpty() || e.isEmpty()) return "";
        if (s.equals(e) || ("00:00".equals(s) && ("24:00".equals(e) || "23:59".equals(e)))) return "全天限制";
        int si = toMin(s), ei = toMin(e);
        if (ei <= si) return "每天 " + s + " – 次日 " + e;
        return "每天 " + s + " – " + e;
    }

    private static int toMin(String hm) {
        int[] p = parseHM(hm);
        return p[0] * 60 + p[1];
    }

    private void preview() {
        List<String> d = diffs();
        if (d.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String line : d) sb.append("• ").append(line).append('\n');
        sb.append("\n受控手机约 1 分钟内拉取并生效。");
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("确认下发 " + d.size() + " 项修改")
                .setMessage(sb.toString())
                .setPositiveButton("确认下发", (x, y) -> saveConfig())
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveConfig() {
        if (cfg == null) return;
        JSONObject body;
        try {
            body = new JSONObject();
            for (String k : new String[]{"master", "guard", "winStart", "winEnd", "coolUse", "coolIval"}) {
                body.put(k, cfg.get(k));
            }
            JSONArray apps = cfg.optJSONArray("apps");
            if (apps != null && apps.length() > 0 && !cfg.optBoolean("noApps")) {
                JSONArray slim = new JSONArray();
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject a = apps.optJSONObject(i);
                    if (a == null) continue;
                    slim.put(new JSONObject()
                            .put("pkg", a.optString("pkg"))
                            .put("state", a.optString("state", "deny"))
                            .put("quota", quotaOf(a)));
                }
                body.put("apps", slim);
            }
        } catch (Exception e) {
            toast("配置组装失败");
            return;
        }
        if (barBtn != null) barBtn.setEnabled(false);
        new Thread(() -> {
            try {
                JSONObject out = Api.saveConfig(this, body);
                JSONObject next = out.optJSONObject("config");
                boolean changed = out.optBoolean("changed");
                runOnUiThread(() -> {
                    if (barBtn != null) barBtn.setEnabled(true);
                    if (next != null) setCfg(next);
                    updateChrome();
                    if (tab == TAB_APPS) renderAppsTab();
                    else if (tab == TAB_SET) renderSettingsTab();
                    toast(changed ? "已下发，约 1 分钟内生效" : "与当前规则相同，未产生下发");
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> {
                    if (barBtn != null) barBtn.setEnabled(true);
                    updateChrome();
                    onErr(e);
                });
            }
        }, "admin-save").start();
    }

    // ---------------------------------------------------------------- 设置行构建

    private View switchRow(String title, String desc, String key) {
        LinearLayout row = row();
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        LinearLayout text = col();
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(tv(title, 15, R.color.text_hi));
        if (desc != null && !desc.isEmpty()) {
            TextView d = tv(desc, 12, R.color.text_low);
            d.setPadding(0, dp(2), dp(8), 0);
            text.addView(d);
        }
        Switch sw = new Switch(this);
        sw.setChecked(cfg.optBoolean(key));
        sw.setOnCheckedChangeListener((b, on) -> {
            try {
                cfg.put(key, on);
            } catch (Exception ignored) {}
            onCfgEdited();
        });
        row.addView(text);
        row.addView(sw);
        return row;
    }

    private View timeRow(String title, String key, TextView[] valRef, Runnable onTimePicked) {
        LinearLayout row = row();
        row.setPadding(dp(2), dp(9), dp(2), dp(9));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView t = tv(title, 15, R.color.text_hi);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = tv(cfg.optString(key, "00:00"), 15.5f, R.color.primary);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        valRef[0] = val;
        row.addView(t);
        row.addView(val);
        row.setOnClickListener(v -> {
            int[] hm = parseHM(cfg.optString(key, "00:00"));
            if (hm[0] >= 24) hm = new int[]{0, 0};
            new TimePickerDialog(this, R.style.AppDialog, (view, hour, minute) -> {
                String s = String.format(Locale.US, "%02d:%02d", hour, minute);
                if ("winEnd".equals(key) && "00:00".equals(s) && !"00:00".equals(cfg.optString("winStart"))) {
                    s = "24:00";
                }
                try {
                    cfg.put(key, s);
                } catch (Exception ignored) {}
                onTimePicked.run();
                onCfgEdited();
            }, hm[0], hm[1], true).show();
        });
        return row;
    }

    private void numberRow(LinearLayout parent, String title, String key, int min, int max) {
        LinearLayout row = row();
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        TextView t = tv(title, 15, R.color.text_hi);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        EditText et = smallNumber(String.valueOf(cfg.optInt(key)));
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        et.addTextChangedListener(new SimpleWatcher(s -> {
            try {
                int v = Integer.parseInt(s.trim());
                if (v >= min && v <= max) {
                    cfg.put(key, v);
                    et.setError(null);
                } else {
                    et.setError(min + "–" + max);
                }
            } catch (Exception e) {
                et.setError(min + "–" + max);
            }
            onCfgEdited();
        }));
        TextView unit = tv("分钟", 13, R.color.text_mid);
        unit.setPadding(dp(8), 0, 0, 0);
        row.addView(t);
        row.addView(et);
        row.addView(unit);
        parent.addView(row);
    }

    // ---------------------------------------------------------------- 基础控件与工具

    private boolean hasSavedPin() {
        return isSixDigits(Session.savedPin(this));
    }

    private static boolean isSixDigits(String s) {
        if (s == null || s.length() != 6) return false;
        for (int i = 0; i < 6; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Consumer<String> f;

        SimpleWatcher(Consumer<String> f) {
            this.f = f;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {
            f.accept(s.toString());
        }
    }

    private void onErr(Api.Err e) {
        if (e.code == 401) {
            toast("登录已失效，请重新登录");
            logout();
            return;
        }
        toast(e.getMessage() == null ? "操作失败" : e.getMessage());
    }

    private static String sigOf(JSONArray arr, boolean hasPin) {
        StringBuilder b = new StringBuilder(hasPin ? "P1|" : "P0|");
        if (arr == null) return b.toString();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            b.append(o.optString("id")).append(':').append(o.optString("state")).append(';');
        }
        return b.toString();
    }

    private boolean notifGranted() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIF && wantWatch && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            Session.setWatch(this, true);
            WatchService.sync(this);
            if (tab == TAB_REQ) renderRequests();
            else if (tab == TAB_SET) renderSettingsTab();
        }
        wantWatch = false;
    }

    private void renderLoading(String s) {
        if (host == null) return;
        TextView t = tv(s, 14, R.color.text_low);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(56), 0, 0);
        host.removeAllViews();
        host.addView(t);
    }

    private void renderRetry(String s, Runnable retry) {
        if (host == null) return;
        TextView t = tv(s, 14, R.color.text_low);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(56), dp(24), dp(24));
        t.setOnClickListener(v -> retry.run());
        host.removeAllViews();
        host.addView(t);
    }

    private static String ageText(String age) {
        if (age == null || age.isEmpty()) return "";
        if (age.endsWith("前") || age.equals("刚刚")) return age;
        return age + "前";
    }

    private static String stateLabel(String s) {
        if ("pending".equals(s)) return "待处理";
        if ("approved".equals(s)) return "已批准";
        if ("denied".equals(s)) return "已拒绝";
        return s;
    }

    private TextView chip(String label, String state) {
        TextView t = tv(label, 11.5f, R.color.warn);
        int bg = R.color.warn_container;
        int fg = R.color.warn;
        if ("approved".equals(state)) {
            bg = R.color.ok_container;
            fg = R.color.ok;
        } else if ("denied".equals(state)) {
            bg = R.color.danger_container;
            fg = R.color.danger;
        }
        t.setTextColor(c(fg));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(c(bg));
        t.setBackground(g);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        return t;
    }

    private GradientDrawable chipBg(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        if (on) {
            g.setColor(c(R.color.primary));
        } else {
            g.setColor(c(R.color.surface_container));
            g.setStroke(Math.max(1, dp(1)), c(R.color.outline));
        }
        return g;
    }

    private GradientDrawable miniPillBg(boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        if (active) {
            g.setColor(c(R.color.primary_container));
        } else {
            g.setColor(c(R.color.surface_container_high));
        }
        return g;
    }

    private GradientDrawable segBg(boolean on, String key) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        if (on) {
            int col = "white".equals(key) ? R.color.ok : "gray".equals(key) ? R.color.warn : R.color.danger;
            g.setColor(c(col));
        } else {
            g.setColor(c(R.color.surface_container_high));
        }
        return g;
    }

    private static int[] parseHM(String s) {
        int[] hm = {0, 0};
        if (s == null) return hm;
        String[] p = s.split(":");
        if (p.length != 2) return hm;
        try {
            hm[0] = Integer.parseInt(p[0]);
            hm[1] = Integer.parseInt(p[1]);
        } catch (Exception ignored) {}
        return hm;
    }

    private EditText smallNumber(String value) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setBackgroundResource(R.drawable.filter_bg);
        et.setTextColor(c(R.color.text_hi));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        et.setPadding(dp(8), dp(5), dp(8), dp(5));
        et.setGravity(Gravity.CENTER);
        et.setMinWidth(dp(64));
        et.setSingleLine(true);
        return et;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(c(R.color.outline_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView note(String s, int fg, int bg) {
        TextView t = tv(s, 12.5f, fg);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(11));
        g.setColor(c(bg));
        t.setBackground(g);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void showErr(TextView err, String msg) {
        err.setText(msg);
        err.setVisibility(View.VISIBLE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int c(int id) {
        return getColor(id);
    }

    private TextView tv(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c(color));
        return t;
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout page() {
        LinearLayout l = col();
        l.setPadding(dp(16), dp(4), dp(16), dp(20));
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = col();
        l.setBackgroundResource(R.drawable.card_bg);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    private TextView fieldLabel(String s) {
        TextView t = tv(s, 12.5f, R.color.text_mid);
        t.setPadding(dp(4), 0, 0, dp(6));
        return t;
    }

    private TextView section(String s) {
        TextView t = tv(s, 12, R.color.text_low);
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        return t;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setBackgroundResource(R.drawable.filter_bg);
        e.setTextColor(c(R.color.text_hi));
        e.setHintTextColor(c(R.color.text_low));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_outline);
        b.setTextColor(c(primary ? R.color.on_primary : R.color.text_mid));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private View gap(int dpv) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpv)));
        return v;
    }

    private TextView navItem(String s) {
        TextView t = tv(s, 14, R.color.text_low);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(13), 0, dp(13));
        return t;
    }

    private LinearLayout.LayoutParams navLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void applyInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                l = bars.left;
                t = bars.top;
                r = bars.right;
                b = Math.max(bars.bottom, ime);
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(l, t, r, b);
            return insets;
        });
        if (v.isAttachedToWindow()) v.requestApplyInsets();
        else v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { x.requestApplyInsets(); }
            @Override public void onViewDetachedFromWindow(View x) {}
        });
    }
}
