package com.briqt.screenlimit.admin;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理端。能力对齐网页：登录、待审批（批准/拒绝）、规则配置（开关、时段、防绕过、应急、应用三态）。
 * 另外多一个可选的后台审批提醒。不负责拦截，也不和守护端共享数据。
 */
public class AdminActivity extends Activity {
    private static final int REQ_NOTIF = 7;

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout host;
    private TextView navReq, navCfg;
    private int tab;
    private JSONArray reqs = new JSONArray();
    /** 列表签名。变了才重画，避免每 5 秒把正在输入的密码清掉。 */
    private String shownReqSig = "";
    private final Map<String, String> pinDraft = new HashMap<>();
    private JSONObject cfg;
    private String listQuery = "";
    private int listFilter;
    private boolean wantWatch;
    private boolean reqInFlight;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (Session.token(AdminActivity.this).isEmpty()) return;
            if (tab == 0) fetchRequests();
            // 规则页：有下发等手机应用时跟着刷，好让「待应用」提示自己消失（有未提交的修改就不刷）
            if (tab == 1 && cfg != null && cfg.optBoolean("cfgPending")) fetchConfig();
            h.postDelayed(this, 5000);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getActionBar() != null) getActionBar().hide();
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Session.token(this).isEmpty()) showLogin();
        else showShell();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!Session.token(this).isEmpty() && host != null && "req".equals(intent.getStringExtra("tab"))) {
            selectTab(0);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!Session.token(this).isEmpty()) {
            h.removeCallbacks(poll);
            h.post(poll);
            WatchService.sync(this);
        }
    }

    @Override protected void onPause() {
        h.removeCallbacks(poll);
        super.onPause();
    }

    // ---------------------------------------------------------------- 登录

    private void showLogin() {
        h.removeCallbacks(poll);
        host = null;
        ScrollView sv = new ScrollView(this);
        LinearLayout root = col();
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        applyInsets(sv);
        sv.addView(root);

        TextView h1 = tv("限时管理", 26, R.color.text_hi);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(h1);
        TextView sub = tv("批准解锁、下发规则。这个应用不负责拦截。", 14, R.color.text_mid);
        sub.setPadding(0, dp(6), 0, dp(22));
        root.addView(sub);

        EditText base = field("https://sl.example.com", Session.base(this));
        base.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field("管理员用户名", Session.user(this));
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = field("管理员密码", "");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        base.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        user.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        pass.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        // 填了内容之后占位提示就没了，看不出哪格是哪格——每格上面放一个常驻标签
        root.addView(fieldLabel("服务器"));
        root.addView(base);
        root.addView(gap(12));
        root.addView(fieldLabel("用户名"));
        root.addView(user);
        root.addView(gap(12));
        root.addView(fieldLabel("密码"));
        root.addView(pass);
        root.addView(gap(20));

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
        pass.setOnEditorActionListener((v, id, ev) -> { go.performClick(); return true; });
        root.addView(go);
        root.addView(gap(8));
        root.addView(err);
        setContentView(sv);
        // 已记住服务器和用户名时直接把光标放到密码上
        (user.getText().length() > 0 ? pass : user).requestFocus();
    }

    // ---------------------------------------------------------------- 主界面

    private void showShell() {
        LinearLayout root = col();
        applyInsets(root);

        LinearLayout top = col();
        top.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView h1 = tv("限时管理", 20, R.color.text_hi);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = tv(Session.user(this) + " · " + Session.base(this).replaceFirst("^https?://", ""), 12, R.color.text_low);
        LinearLayout head = row();
        LinearLayout titles = col();
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titles.addView(h1);
        titles.addView(sub);
        head.addView(titles);
        TextView out = tv("退出登录", 13, R.color.text_mid);
        out.setPadding(dp(12), dp(8), 0, dp(8));
        out.setOnClickListener(v -> new android.app.AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("退出登录？")
                .setMessage("退出后审批提醒也会关闭，需要重新输入密码登录。")
                .setPositiveButton("退出", (d, w) -> logout())
                .setNegativeButton("取消", null)
                .show());
        head.addView(out);
        top.addView(head);
        root.addView(top);

        host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(c(R.color.surface_container));
        nav.setPadding(0, dp(4), 0, dp(4));
        navReq = navItem("待审批");
        navCfg = navItem("规则配置");
        navReq.setOnClickListener(v -> selectTab(0));
        navCfg.setOnClickListener(v -> selectTab(1));
        nav.addView(navReq, navLp());
        nav.addView(navCfg, navLp());
        root.addView(nav);

        setContentView(root);
        selectTab("cfg".equals(getIntent().getStringExtra("tab")) ? 1 : 0);
    }

    private void selectTab(int which) {
        tab = which;
        paintNav();
        if (which == 0) {
            renderRequests();
            fetchRequests();
        } else {
            if (cfg == null) renderLoading("正在读取规则…");
            else renderConfig(null);
            fetchConfig();
        }
    }

    private void paintNav() {
        navReq.setTextColor(c(tab == 0 ? R.color.primary : R.color.text_low));
        navCfg.setTextColor(c(tab == 1 ? R.color.primary : R.color.text_low));
    }

    private void logout() {
        Session.clearToken(this);
        Session.setWatch(this, false);
        WatchService.sync(this);
        cfg = null;
        reqs = new JSONArray();
        shownReqSig = "";
        pinDraft.clear();
        showLogin();
    }

    // ---------------------------------------------------------------- 待审批

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
                    String sig = sigOf(reqs);
                    if (tab == 0 && !sig.equals(shownReqSig)) {
                        shownReqSig = sig;
                        renderRequests();
                    }
                    int n = j.optInt("pending");
                    navReq.setText(n > 0 ? ("待审批 · " + n) : "待审批");
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
        ScrollView sv = new ScrollView(this);
        LinearLayout root = page();
        root.addView(watchCard());

        int shown = 0;
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject o = reqs.optJSONObject(i);
            if (o == null) continue;
            shown++;
            root.addView(requestCard(o));
        }
        if (shown == 0) {
            TextView empty = tv("当前没有待处理的申请", 14, R.color.text_low);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, dp(24));
            root.addView(empty);
        }
        TextView note = tv("密码不在服务端留存明文：批准后只暂存一次，被手机取走即清空。", 12, R.color.text_low);
        note.setPadding(dp(4), dp(8), dp(4), dp(12));
        root.addView(note);
        sv.addView(root);
        shownReqSig = sigOf(reqs);
        host.removeAllViews();
        host.addView(sv);
    }

    private View watchCard() {
        LinearLayout card = card();
        LinearLayout row = row();
        LinearLayout text = col();
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = tv("审批提醒", 15, R.color.text_hi);
        TextView d = tv("打开后每 30 秒看一次，有新申请就通知。关掉就只在这个页面里刷新。", 12, R.color.text_low);
        text.addView(t);
        text.addView(d);
        Switch sw = new Switch(this);
        sw.setChecked(Session.watch(this));
        sw.setOnCheckedChangeListener((b, on) -> {
            if (on && !notifGranted()) {
                b.setChecked(false);
                wantWatch = true;
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
            Session.setWatch(this, on);
            WatchService.sync(this);
        });
        row.addView(text);
        row.addView(sw);
        card.addView(row);
        return card;
    }

    private View requestCard(JSONObject o) {
        LinearLayout card = card();
        LinearLayout head = row();
        LinearLayout text = col();
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = tv(o.optString("app", "未命名"), 16, R.color.text_hi);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView meta = tv(o.optString("pkg") + " · " + ageText(o.optString("age")), 12, R.color.text_low);
        text.addView(name);
        text.addView(meta);
        head.addView(text);
        head.addView(chip(stateLabel(o.optString("state")), o.optString("state")));
        card.addView(head);

        if (!"pending".equals(o.optString("state"))) return card;

        String id = o.optString("id");
        EditText pin = field("6 位解锁密码", "");
        pin.setTag("pin");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        String draft = pinDraft.get(id);
        String saved = Session.savedPin(this);
        if (draft != null) pin.setText(draft);
        else if (saved.length() == 6) pin.setText(saved);
        pin.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                pinDraft.put(id, s.toString());
                // 字距只给输入的数字，不给占位提示——提示文字被拉开很难看
                pin.setLetterSpacing(s.length() > 0 ? 0.4f : 0f);
            }
        });
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pin.setGravity(Gravity.CENTER);
        pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        pin.setLetterSpacing(pin.getText().length() > 0 ? 0.4f : 0f);
        card.addView(gap(8));
        card.addView(pin);
        if (saved.length() == 6) {
            TextView savedHint = tv("✓ 已填入本机记住的解锁密码，直接点批准即可（点此清除记住）", 11.5f, R.color.primary);
            savedHint.setPadding(dp(4), dp(6), dp(4), 0);
            savedHint.setOnClickListener(v -> {
                Session.setSavedPin(this, "");
                pinDraft.remove(id);
                pin.setText("");
                savedHint.setVisibility(View.GONE);
                toast("已清除本机记住的解锁密码");
            });
            card.addView(savedHint);
        }
        Button ok = button("批准", true);
        ok.setOnClickListener(v -> {
            String pw = pin.getText().toString().trim();
            if (pw.length() != 6) {
                toast("密码必须是 6 位数字");
                return;
            }
            ok.setEnabled(false);
            new Thread(() -> {
                try {
                    Api.approve(this, id, pw);
                    Session.setSavedPin(this, pw);
                    pinDraft.remove(id);
                    runOnUiThread(() -> {
                        toast("已批准（已在本机记住解锁密码）");
                        fetchRequests();
                    });
                } catch (Api.Err e) {
                    runOnUiThread(() -> {
                        ok.setEnabled(true);
                        onErr(e);
                    });
                }
            }, "admin-approve").start();
        });
        Button no = button("拒绝", false);
        no.setOnClickListener(v -> new Thread(() -> {
            try {
                Api.deny(this, id);
                runOnUiThread(() -> {
                    toast("已拒绝");
                    fetchRequests();
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> onErr(e));
            }
        }, "admin-deny").start());
        LinearLayout btns = row();
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, dp(48), 1f);
        nl.rightMargin = dp(10);
        btns.addView(no, nl);
        btns.addView(ok, new LinearLayout.LayoutParams(0, dp(48), 2f));
        card.addView(gap(10));
        card.addView(btns);
        return card;
    }

    // ---------------------------------------------------------------- 规则配置
    //
    // 页面初值来自服务端 GET /api/admin/config：v0.13 起那是**手机上报的实际规则**（叠加尚未被手机应用的下发）。
    // 编辑只改 cfg 这份工作副本；cfgBase 是载入时的快照，用来算「改了什么」。
    // 下发前必须预览：逐条列出变更，确认才提交——手机拿到就无条件应用，没有后悔药。

    private JSONObject cfgBase;
    private LinearLayout appsBox;
    private TextView barText;
    private Button barBtn;
    private final TextView[] filterChips = new TextView[5];

    private void fetchConfig() {
        new Thread(() -> {
            try {
                JSONObject j = Api.config(this);
                runOnUiThread(() -> {
                    // 正在改的时候别用新数据冲掉手上的修改
                    if (cfg != null && !diffs().isEmpty()) return;
                    setCfg(j);
                    if (tab == 1) renderConfig(null);
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> onErr(e));
            }
        }, "admin-cfg").start();
    }

    private void setCfg(JSONObject j) {
        cfg = j;
        try { cfgBase = new JSONObject(j.toString()); } catch (Exception e) { cfgBase = j; }
    }

    private void renderConfig(String banner) {
        if (host == null || cfg == null) return;
        LinearLayout frame = col();
        ScrollView sv = new ScrollView(this);
        LinearLayout root = page();

        if (banner != null) root.addView(note(banner, R.color.ok, R.color.ok_container));
        if (cfg.optBoolean("noApps")) {
            root.addView(note("手机还没上报应用列表，名单部分暂不可编辑。手机联网后会自动上报。",
                    R.color.danger, R.color.danger_container));
        } else if (cfg.optBoolean("cfgPending")) {
            root.addView(note("⏳ 上一次下发手机还没应用，约 1 分钟内生效。下面显示的是生效后的值。",
                    R.color.warn, R.color.warn_container));
        } else if (!cfg.optBoolean("fromDevice")) {
            root.addView(note("手机端版本较旧，没有上报实际规则；下面是上次下发的值，可能与手机不一致。",
                    R.color.warn, R.color.warn_container));
        }

        root.addView(section("限时规则"));
        LinearLayout rules = card();
        rules.addView(switchRow("启用限时", "关闭时所有应用不受限制", "master"));
        rules.addView(divider());
        TextView summary = tv("", 12.5f, R.color.text_mid);
        rules.addView(timeRow("开始时间", "winStart", summary));
        rules.addView(timeRow("结束时间", "winEnd", summary));
        LinearLayout wPresets = row();
        wPresets.setPadding(0, dp(4), 0, dp(4));
        TextView pAllDay = tv("设为全天 (00:00–24:00)", 12, R.color.primary);
        pAllDay.setPadding(0, dp(4), dp(14), dp(4));
        pAllDay.setOnClickListener(v -> {
            try {
                cfg.put("winStart", "00:00");
                cfg.put("winEnd", "24:00");
            } catch (Exception ignored) {}
            renderConfig(null);
        });
        TextView pNight = tv("设为夜间 (22:00–07:00)", 12, R.color.primary);
        pNight.setPadding(0, dp(4), dp(14), dp(4));
        pNight.setOnClickListener(v -> {
            try {
                cfg.put("winStart", "22:00");
                cfg.put("winEnd", "07:00");
            } catch (Exception ignored) {}
            renderConfig(null);
        });
        wPresets.addView(pAllDay);
        wPresets.addView(pNight);
        rules.addView(wPresets);
        summary.setPadding(dp(2), dp(2), dp(2), dp(4));
        summary.setText(winSummary(cfg.optString("winStart"), cfg.optString("winEnd")));
        rules.addView(summary);
        root.addView(rules);

        root.addView(section("防绕过"));
        LinearLayout hard = card();
        hard.addView(switchRow("看守系统设置", "拦住卸载、无障碍、悬浮窗权限、应用信息等页面。依赖手机端无障碍", "guard"));
        root.addView(hard);

        root.addView(section("应急使用（整机共用）"));
        LinearLayout cool = card();
        numberRow(cool, "每次可用", "分钟", "coolUse", 1, 60);
        cool.addView(divider());
        numberRow(cool, "冷却间隔", "分钟，用掉后所有应用一起冷却", "coolIval", 1, 720);
        root.addView(cool);

        TextView appsHead = section("应用名单 · " + cfg.optString("appsAt", ""));
        root.addView(appsHead);
        EditText search = field("搜索应用名或包名", listQuery);
        search.addTextChangedListener(new SimpleWatcher(t -> {
            listQuery = t;
            fillApps();
        }));
        root.addView(search);
        root.addView(gap(8));
        root.addView(filterRow());
        appsBox = col();
        root.addView(appsBox);
        fillApps();

        TextView foot = tv("手机约 60 秒拉一次配置，拿到后无条件应用。", 12, R.color.text_low);
        foot.setPadding(dp(4), dp(8), dp(4), dp(8));
        root.addView(foot);

        sv.addView(root);
        frame.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(saveBar());
        host.removeAllViews();
        host.addView(frame);
        onCfgEdited();
    }

    /** 底部常驻条：改了几项 + 「预览下发」。没改动时按钮不可点。 */
    private View saveBar() {
        LinearLayout bar = row();
        bar.setBackgroundColor(c(R.color.surface_container));
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        barText = tv("", 13, R.color.text_mid);
        barText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        barBtn = button("预览下发", true);
        barBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(140), dp(46)));
        barBtn.setOnClickListener(v -> preview());
        bar.addView(barText);
        bar.addView(barBtn);
        return bar;
    }

    /** 任何一处编辑后调用：刷新底栏计数与筛选计数。 */
    private void onCfgEdited() {
        int n = diffs().size();
        if (barText != null) {
            barText.setText(n == 0 ? "没有修改" : ("已修改 " + n + " 项"));
            barText.setTextColor(c(n == 0 ? R.color.text_low : R.color.primary));
        }
        if (barBtn != null) {
            barBtn.setEnabled(n > 0);
            barBtn.setAlpha(n > 0 ? 1f : 0.45f);
        }
        paintFilters();
    }

    private static final String[] FILTER_LABELS = {"全部", "放行", "配额", "禁止", "已修改"};

    private View filterRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(8));
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int idx = i;
            TextView b = tv("", 12, R.color.text_mid);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(8), 0, dp(8));
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
        }
    }

    private void fillApps() {
        if (appsBox == null) return;
        appsBox.removeAllViews();
        JSONArray arr = cfg == null ? null : cfg.optJSONArray("apps");
        if (arr == null || arr.length() == 0) return;
        String q = listQuery.trim().toLowerCase();
        LinearLayout card = card();
        card.setPadding(0, dp(2), 0, dp(2));
        int n = 0;
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
            if (!q.isEmpty() && !label.toLowerCase().contains(q) && !pkg.toLowerCase().contains(q)) continue;
            if (n > 0) card.addView(divider());
            LinearLayout holder = col();
            bindAppRow(holder, a);
            card.addView(holder);
            n++;
        }
        if (n == 0) {
            TextView empty = tv(listFilter == 4 ? "还没有改动任何应用" : "没有匹配的应用", 14, R.color.text_low);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            card.addView(empty);
        }
        appsBox.addView(card);
    }

    /** 一行一个应用：名字/包名在左，三态分段在右；配额态下方多一行分钟数。原地重绑，不重建整页。 */
    private void bindAppRow(LinearLayout holder, JSONObject a) {
        holder.removeAllViews();
        holder.setPadding(dp(14), dp(10), dp(14), dp(10));
        String state = a.optString("state", "deny");
        LinearLayout line = row();
        LinearLayout text = col();
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = tv(a.optString("label", a.optString("pkg")), 15, R.color.text_hi);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int usedMin = a.optInt("usedMin", 0);
        String subPrefix = (appChanged(a) ? "● 已修改 · " : "")
                + (usedMin > 0 ? ("今日已用 " + usedMin + "分 · ") : "");
        TextView pkg = tv(subPrefix + a.optString("pkg"), 11,
                appChanged(a) ? R.color.primary : (usedMin > 0 ? R.color.text_mid : R.color.text_low));
        pkg.setSingleLine(true);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        text.addView(name);
        text.addView(pkg);
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
                try {
                    a.put("state", key);
                    if ("gray".equals(key) && a.optInt("quota", 0) <= 0) a.put("quota", 30);
                } catch (Exception ignored) {}
                bindAppRow(holder, a);
                onCfgEdited();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(32));
            lp.leftMargin = dp(4);
            seg.addView(b, lp);
        }
        line.addView(seg);
        holder.addView(line);

        if ("gray".equals(state)) {
            LinearLayout qr = row();
            qr.setPadding(0, dp(8), 0, 0);
            TextView l = tv("每天可用", 13, R.color.text_mid);
            l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            qr.addView(l);
            EditText q = smallNumber(String.valueOf(a.optInt("quota", 30)));
            q.addTextChangedListener(new SimpleWatcher(t -> {
                try {
                    int v = Integer.parseInt(t.trim());
                    if (v > 0 && v <= 1440) a.put("quota", v);
                } catch (Exception ignored) {}
                onCfgEdited();
            }));
            qr.addView(q);
            TextView unit = tv("分钟", 13, R.color.text_mid);
            unit.setPadding(dp(8), 0, 0, 0);
            qr.addView(unit);
            holder.addView(qr);
        }
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

    // ---------- 变更计算 ----------

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

    /** 与载入时相比的全部改动，一条一句人话。 */
    private java.util.List<String> diffs() {
        java.util.List<String> out = new java.util.ArrayList<>();
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
            out.add("应急每次可用：" + cfgBase.optInt("coolUse") + " → " + cfg.optInt("coolUse") + " 分钟");
        }
        if (cfg.optInt("coolIval") != cfgBase.optInt("coolIval")) {
            out.add("应急冷却间隔：" + cfgBase.optInt("coolIval") + " → " + cfg.optInt("coolIval") + " 分钟");
        }
        JSONArray arr = cfg.optJSONArray("apps");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null || !appChanged(a)) continue;
            JSONObject b = baseApp(a.optString("pkg"));
            out.add(a.optString("label", a.optString("pkg")) + "：" + stateText(b) + " → " + stateText(a));
        }
        return out;
    }

    private static String stateText(JSONObject a) {
        String s = a.optString("state", "deny");
        if ("white".equals(s)) return "放行";
        if ("gray".equals(s)) return "配额 " + quotaOf(a) + " 分钟";
        return "禁止";
    }

    private static String onOff(boolean b) { return b ? "开" : "关"; }

    /** 「全天限制」「每天 22:00 – 次日 07:00」。开始=结束或 00:00–24:00 都是全天。 */
    static String winSummary(String s, String e) {
        if (s == null || e == null || s.isEmpty() || e.isEmpty()) return "";
        if (s.equals(e) || ("00:00".equals(s) && "24:00".equals(e))) return "全天限制";
        int si = toMin(s), ei = toMin(e);
        if (ei <= si) return "每天 " + s + " – 次日 " + e;
        return "每天 " + s + " – " + e;
    }

    private static int toMin(String hm) {
        int[] p = parseHM(hm);
        return p[0] * 60 + p[1];
    }

    // ---------- 预览与下发 ----------

    private void preview() {
        java.util.List<String> d = diffs();
        if (d.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String line : d) sb.append("• ").append(line).append('\n');
        sb.append("\n手机约 1 分钟内拿到并直接应用。");
        new android.app.AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle("确认下发 " + d.size() + " 项修改")
                .setMessage(sb.toString())
                .setPositiveButton("确认下发", (x, y) -> saveConfig())
                .setNegativeButton("返回修改", null)
                .show();
    }

    private void saveConfig() {
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
                    if (next != null) setCfg(next);
                    renderConfig(changed ? "已下发，手机约 1 分钟内生效" : "与手机当前规则相同，没有下发");
                });
            } catch (Api.Err e) {
                runOnUiThread(() -> {
                    onCfgEdited();
                    onErr(e);
                });
            }
        }, "admin-save").start();
    }

    // ---------------------------------------------------------------- 行

    private View switchRow(String title, String desc, String key) {
        LinearLayout row = row();
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        LinearLayout text = col();
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(tv(title, 15, R.color.text_hi));
        TextView d = tv(desc, 12, R.color.text_low);
        d.setPadding(0, dp(2), dp(8), 0);
        text.addView(d);
        Switch sw = new Switch(this);
        sw.setChecked(cfg.optBoolean(key));
        sw.setOnCheckedChangeListener((b, on) -> {
            try { cfg.put(key, on); } catch (Exception ignored) {}
            onCfgEdited();
        });
        row.addView(text);
        row.addView(sw);
        return row;
    }

    private View timeRow(String title, String key, TextView summary) {
        LinearLayout row = row();
        row.setPadding(dp(2), dp(10), dp(2), dp(10));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        TextView t = tv(title, 15, R.color.text_hi);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = tv(cfg.optString(key, "00:00"), 16, R.color.primary);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);
        row.addView(val);
        row.setOnClickListener(v -> {
            int[] hm = parseHM(cfg.optString(key, "00:00"));
            if (hm[0] >= 24) hm = new int[]{0, 0};   // 24:00 在选择器里等于 00:00
            new TimePickerDialog(this, R.style.AppDialog, (view, hour, minute) -> {
                String s = String.format(java.util.Locale.US, "%02d:%02d", hour, minute);
                // 结束选 00:00 且开始不是 00:00，按「到午夜」理解，发 24:00，避免被当成全天
                if ("winEnd".equals(key) && "00:00".equals(s) && !"00:00".equals(cfg.optString("winStart"))) {
                    s = "24:00";
                }
                try { cfg.put(key, s); } catch (Exception ignored) {}
                val.setText(s);
                summary.setText(winSummary(cfg.optString("winStart"), cfg.optString("winEnd")));
                onCfgEdited();
            }, hm[0], hm[1], true).show();
        });
        return row;
    }

    private void numberRow(LinearLayout parent, String title, String desc, String key, int min, int max) {
        LinearLayout row = row();
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        LinearLayout text = col();
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(tv(title, 15, R.color.text_hi));
        text.addView(tv(desc + "（" + min + "–" + max + "）", 12, R.color.text_low));
        EditText et = smallNumber(String.valueOf(cfg.optInt(key)));
        // 边输边写回工作副本：之前只在点保存时才读，中途点一下筛选重画页面，输入就丢了
        et.addTextChangedListener(new SimpleWatcher(t -> {
            try {
                int v = Integer.parseInt(t.trim());
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
        row.addView(text);
        row.addView(et);
        parent.addView(row);
    }

    private EditText smallNumber(String value) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setBackgroundResource(R.drawable.filter_bg);
        et.setTextColor(c(R.color.text_hi));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setPadding(dp(10), dp(7), dp(10), dp(7));
        et.setGravity(Gravity.CENTER);
        et.setMinWidth(dp(72));
        et.setSingleLine(true);
        return et;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(c(R.color.outline_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView note(String s, int fg, int bg) {
        TextView t = tv(s, 13, fg);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(c(bg));
        t.setBackground(g);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    /** 只关心「改完之后」的 TextWatcher。 */
    private static final class SimpleWatcher implements android.text.TextWatcher {
        private final java.util.function.Consumer<String> f;
        SimpleWatcher(java.util.function.Consumer<String> f) { this.f = f; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(android.text.Editable s) { f.accept(s.toString()); }
    }

    // ---------------------------------------------------------------- 杂项

    private void onErr(Api.Err e) {
        if (e.code == 401) {
            toast("登录已失效，请重新登录");
            logout();
            return;
        }
        toast(e.getMessage() == null ? "失败" : e.getMessage());
    }

    /** 待审批列表的身份：id + 状态。没变就不重画。 */
    private static String sigOf(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            b.append(o.optString("id")).append(':').append(o.optString("state")).append(';');
        }
        return b.toString();
    }

    private boolean notifGranted() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIF && wantWatch && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            Session.setWatch(this, true);
            WatchService.sync(this);
            if (tab == 0) renderRequests();
        }
        wantWatch = false;
    }

    private void renderLoading(String s) {
        if (host == null) return;
        TextView t = tv(s, 14, R.color.text_low);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(48), 0, 0);
        host.removeAllViews();
        host.addView(t);
    }

    /** 新服务端给的是「3 分钟前」；老服务端给 Go 的 1m23s，补个「前」。 */
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
        TextView t = tv(label, 12, R.color.warn);
        int bg = R.color.warn_container;
        int fg = R.color.warn;
        if ("approved".equals(state)) { bg = R.color.ok_container; fg = R.color.ok; }
        if ("denied".equals(state)) { bg = R.color.danger_container; fg = R.color.danger; }
        t.setTextColor(c(fg));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(c(bg));
        t.setBackground(g);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        return t;
    }

    private GradientDrawable chipBg(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        if (on) g.setColor(c(R.color.primary));
        else {
            g.setColor(c(R.color.surface_container_high));
            g.setStroke(dp(1), c(R.color.outline));
        }
        return g;
    }

    private static int parseRange(String s, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min || v > max) return -1;
            return v;
        } catch (Exception e) {
            return -1;
        }
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

    private void showErr(TextView err, String msg) {
        err.setText(msg);
        err.setVisibility(View.VISIBLE);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int c(int id) { return getColor(id); }

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
        l.setPadding(dp(16), dp(4), dp(16), dp(16));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private View gap(int dpv) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpv)));
        return v;
    }

    private TextView navItem(String s) {
        TextView t = tv(s, 13, R.color.text_low);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    private LinearLayout.LayoutParams navLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void applyInsets(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                l = bars.left; t = bars.top; r = bars.right;
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
        // 登录后第二次 setContentView 时窗口的 insets 已经派发过，新内容收不到，
        // 标题就顶进状态栏、底栏压着手势条（真机实测）。挂上窗口时再要一次。
        if (v.isAttachedToWindow()) v.requestApplyInsets();
        else v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { x.requestApplyInsets(); }
            @Override public void onViewDetachedFromWindow(View x) {}
        });
    }
}
