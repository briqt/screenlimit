package main

import (
	"encoding/json"
	"fmt"
	"html/template"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	defaultPingInterval = 45 * time.Second
	sseWriteTimeout     = 15 * time.Second
)

// 管理端 JSON API。网页继续走 Cookie + 表单 + CSRF；
// 原生管理 App 走 Authorization: Bearer，不解析 HTML，也不受 CSRF 影响
// （浏览器不会自动带上 Authorization 头）。
//
//	POST /api/admin/login      {user,password} → {token}
//	GET  /api/admin/requests   待审批列表（不含密码）
//	GET  /api/admin/events     待审批列表 SSE 实时推送流
//	POST /api/admin/approve    {id,password}
//	POST /api/admin/deny       {id}
//	GET  /api/admin/config     当前将要下发的配置 + 设备上报的应用
//	POST /api/admin/config     同网页表单的字段；apps 缺省＝不动名单

func (s *server) registerAdminAPI(mux *http.ServeMux) {
	mux.HandleFunc("/api/admin/login", s.handleAdminLogin)
	mux.HandleFunc("/api/admin/requests", s.requireBearer(s.handleAdminRequests))
	mux.HandleFunc("/api/admin/events", s.requireBearer(s.handleAdminEvents))
	mux.HandleFunc("/api/admin/approve", s.requireBearer(s.handleAdminApprove))
	mux.HandleFunc("/api/admin/deny", s.requireBearer(s.handleAdminDeny))
	mux.HandleFunc("/api/admin/config", s.requireBearer(s.handleAdminConfig))
}

func (s *server) requireBearer(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		const pfx = "Bearer "
		if !strings.HasPrefix(h, pfx) {
			writeAPIErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if _, err := verifyJWT(s.jwtSecret, strings.TrimSpace(h[len(pfx):])); err != nil {
			writeAPIErr(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next(w, r)
	}
}

func writeAPIErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func (s *server) handleAdminLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if !s.rateOK() {
		writeAPIErr(w, http.StatusTooManyRequests, "尝试过于频繁，请稍后再试")
		return
	}
	var in struct {
		User     string `json:"user"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&in); err != nil {
		writeAPIErr(w, http.StatusBadRequest, "请求有误")
		return
	}
	if !s.cred.verify(in.User, in.Password) {
		logf("登录失败 user=%q", in.User)
		writeAPIErr(w, http.StatusUnauthorized, "用户名或密码不正确")
		return
	}
	tok, err := signJWT(s.jwtSecret, s.cred.user)
	if err != nil {
		writeAPIErr(w, http.StatusInternalServerError, "internal")
		return
	}
	logf("登录成功 user=%q", in.User)
	writeJSON(w, map[string]string{"token": tok})
}

type reqView struct {
	ID    string `json:"id"`
	Pkg   string `json:"pkg"`
	App   string `json:"app"`
	Age   string `json:"age"`
	State string `json:"state"`
}

// requestsPayload 构造待审批列表快照并顺带清理过期请求；仅在确实删除了过期记录时写盘。
func (s *server) requestsPayload() (payload map[string]any, expiredCleaned bool) {
	s.st.mu.Lock()
	expiredCleaned = s.st.gc()
	if expiredCleaned {
		s.st.save()
	}
	list := make([]reqView, 0, len(s.st.d.Reqs))
	pending := 0
	for _, q := range s.st.d.Reqs {
		if q.State == statePending {
			pending++
		}
		list = append(list, reqView{
			ID: q.ID, Pkg: q.Pkg, App: q.App,
			Age:   fmtAge(time.Since(q.Created)),
			State: string(q.State),
		})
	}
	s.st.mu.Unlock()
	sort.Slice(list, func(i, j int) bool { return list[i].ID < list[j].ID })
	return map[string]any{"pending": pending, "reqs": list}, expiredCleaned
}

func (s *server) handleAdminRequests(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	payload, cleaned := s.requestsPayload()
	if cleaned {
		s.notifyAdmin()
	}
	writeJSON(w, payload)
}

func (s *server) handleAdminEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeAPIErr(w, http.StatusInternalServerError, "streaming unsupported")
		return
	}

	h := w.Header()
	h.Set("Content-Type", "text/event-stream; charset=utf-8")
	h.Set("Cache-Control", "no-cache, no-transform")
	h.Set("Connection", "keep-alive")
	h.Set("X-Accel-Buffering", "no")

	rc := http.NewResponseController(w)
	writeEvent := func(payload map[string]any) error {
		b, err := json.Marshal(payload)
		if err != nil {
			return err
		}
		_ = rc.SetWriteDeadline(time.Now().Add(sseWriteTimeout))
		if _, err := fmt.Fprintf(w, "data: %s\n\n", b); err != nil {
			return err
		}
		flusher.Flush()
		return nil
	}

	ch, unsubscribe := s.subscribeAdmin()
	defer unsubscribe()

	initPayload, cleaned := s.requestsPayload()
	if cleaned {
		s.notifyAdminExcept(ch)
	}
	if err := writeEvent(initPayload); err != nil {
		return
	}

	interval := s.pingInterval
	if interval <= 0 {
		interval = defaultPingInterval
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-r.Context().Done():
			return
		case <-ch:
			payload, cleaned := s.requestsPayload()
			if cleaned {
				s.notifyAdminExcept(ch)
			}
			if err := writeEvent(payload); err != nil {
				return
			}
		case <-ticker.C:
			if payload, cleaned := s.requestsPayload(); cleaned {
				s.notifyAdminExcept(ch)
				if err := writeEvent(payload); err != nil {
					return
				}
			} else {
				_ = rc.SetWriteDeadline(time.Now().Add(sseWriteTimeout))
				if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
					return
				}
				flusher.Flush()
			}
		}
	}
}

func (s *server) handleAdminApprove(w http.ResponseWriter, r *http.Request) {
	s.adminJSONDecision(w, r, true)
}
func (s *server) handleAdminDeny(w http.ResponseWriter, r *http.Request) {
	s.adminJSONDecision(w, r, false)
}

func (s *server) adminJSONDecision(w http.ResponseWriter, r *http.Request, approve bool) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	var in struct {
		ID       string `json:"id"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&in); err != nil {
		writeAPIErr(w, http.StatusBadRequest, "请求有误")
		return
	}
	pw := strings.TrimSpace(in.Password)
	if approve && !isSixDigits(pw) {
		if !s.rateOK() {
			writeAPIErr(w, http.StatusTooManyRequests, "尝试过于频繁，请稍后再试")
			return
		}
		writeAPIErr(w, http.StatusBadRequest, "密码必须是 6 位数字")
		return
	}
	switch s.applyDecision(in.ID, pw, approve) {
	case http.StatusOK:
		writeJSON(w, map[string]bool{"ok": true})
	case http.StatusNotFound:
		writeAPIErr(w, http.StatusNotFound, "申请不存在或已过期")
	case http.StatusConflict:
		writeAPIErr(w, http.StatusConflict, "该申请已处理")
	default:
		writeAPIErr(w, http.StatusInternalServerError, "internal")
	}
}

// applyDecision 把一条申请标成批准或拒绝。调用方负责校验密码格式。
// 密码只写进状态、不写日志。404 表示 id 不存在，409 表示已经不是 pending。
func (s *server) applyDecision(id, pw string, approve bool) int {
	s.st.mu.Lock()
	req, ok := s.st.d.Reqs[id]
	if !ok {
		s.st.mu.Unlock()
		return http.StatusNotFound
	}
	if req.State != statePending {
		s.st.mu.Unlock()
		return http.StatusConflict
	}
	if approve {
		req.State, req.Password = stateApproved, pw
		logf("已批准 id=%s pkg=%s", req.ID, req.Pkg)
	} else {
		req.State, req.Password = stateDenied, ""
		logf("已拒绝 id=%s pkg=%s", req.ID, req.Pkg)
	}
	s.st.save()
	s.st.mu.Unlock()
	s.notifyAdmin()
	return http.StatusOK
}

// cfgView 是配置页和 GET /api/admin/config 共用的视图。
// 应用三态显示的是「将要下发的配置」，不是设备上报那一瞬间的状态。
type cfgView struct {
	Title    string    `json:"-"`
	Tab      string    `json:"-"`
	CSRF     string    `json:"-"`
	Saved    bool      `json:"-"`
	Version  int64     `json:"version"`
	Master   bool      `json:"master"`
	Guard    bool      `json:"guard"`
	WinStart string    `json:"winStart"`
	WinEnd   string    `json:"winEnd"`
	CoolUse  int       `json:"coolUse"`
	CoolIval int       `json:"coolIval"`
	Apps     []AppInfo `json:"apps"`
	AppsAt   string    `json:"appsAt"`
	NoApps   bool      `json:"noApps"`
	Pending  int       `json:"pending"`
	// CfgPending：有一版下发手机还没应用（约 60 秒内会应用）。此时页面显示的是「应用后」的值。
	CfgPending bool `json:"cfgPending"`
	// FromDevice：页面初值来自手机上报的实际状态；false 表示手机是旧版、只能显示服务端上次下发的值。
	FromDevice bool `json:"fromDevice"`
	// InitialJSON：页面当前表单初值，配置页 JS 拿它做搜索计数与"变更预览"的 diff 基准。
	Initial template.JS `json:"-"`
}

func (s *server) loadCfgView() cfgView {
	s.st.mu.Lock()
	cfg, cfgPending := s.st.effective()
	hasDev := s.st.d.Dev != nil
	apps := append([]AppInfo{}, s.st.d.Apps...)
	appsAt := s.st.d.AppsAt
	pending := 0
	for _, q := range s.st.d.Reqs {
		if q.State == statePending {
			pending++
		}
	}
	s.st.mu.Unlock()

	if cfg.Whitelist != nil || cfg.Gray != nil {
		wl := map[string]bool{}
		if cfg.Whitelist != nil {
			for _, p := range *cfg.Whitelist {
				wl[p] = true
			}
		}
		for i := range apps {
			p := apps[i].Pkg
			switch {
			case wl[p]:
				apps[i].State = "white"
			case cfg.Gray != nil && (*cfg.Gray)[p] > 0:
				apps[i].State, apps[i].Quota = "gray", (*cfg.Gray)[p]
			default:
				if cfg.Version > 0 || hasDev {
					apps[i].State = "deny"
				}
			}
		}
	}
	at := "尚未上报"
	if !appsAt.IsZero() {
		at = fmtWhen(appsAt, time.Now()) + "上报"
		if hasDev {
			at = "手机 " + at
		}
	}
	if cfgPending {
		at += " · 有改动等手机应用"
	}
	if apps == nil {
		apps = []AppInfo{}
	}
	v := cfgView{
		Version:    cfg.Version,
		Master:     cfg.Master != nil && *cfg.Master,
		Guard:      cfg.GuardSettings != nil && *cfg.GuardSettings,
		WinStart:   minToHHMM(cfg.WinStart, "00:00"),
		WinEnd:     minToHHMM(cfg.WinEnd, "23:59"),
		CoolUse:    intOr(cfg.CoolUseMin, 3),
		CoolIval:   intOr(cfg.CoolIntervalMin, 50),
		Apps:       apps,
		AppsAt:     at,
		NoApps:     len(apps) == 0,
		Pending:    pending,
		CfgPending: cfgPending,
		FromDevice: hasDev,
	}

	// 配置页 JS 的 diff 基准：与表单初值一一对应。
	type initApp struct {
		S string `json:"s"`
		Q int    `json:"q"`
	}
	appMap := map[string]initApp{}
	for _, a := range apps {
		q := a.Quota
		if q == 0 {
			q = 30
		}
		appMap[a.Pkg] = initApp{S: a.State, Q: q}
	}
	raw, err := json.Marshal(map[string]any{
		"master": v.Master, "guard": v.Guard,
		"winStart": v.WinStart, "winEnd": v.WinEnd,
		"coolUse": v.CoolUse, "coolIval": v.CoolIval,
		"apps": appMap,
	})
	if err == nil {
		v.Initial = template.JS(raw)
	}
	return v
}

type adminAppIn struct {
	Pkg   string `json:"pkg"`
	State string `json:"state"`
	Quota int    `json:"quota"`
}

type adminCfgIn struct {
	Master   *bool         `json:"master"`
	WinStart *string       `json:"winStart"`
	WinEnd   *string       `json:"winEnd"`
	Guard    *bool         `json:"guard"`
	CoolUse  *int          `json:"coolUse"`
	CoolIval *int          `json:"coolIval"`
	Apps     *[]adminAppIn `json:"apps"`
}

func (s *server) handleAdminConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, s.loadCfgView())
	case http.MethodPost:
		var body adminCfgIn
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 512<<10)).Decode(&body); err != nil {
			writeAPIErr(w, http.StatusBadRequest, "请求有误")
			return
		}
		changed, ver, msg, st := s.applyAdminConfig(body)
		if st != http.StatusOK {
			writeAPIErr(w, st, msg)
			return
		}
		writeJSON(w, map[string]any{
			"ok": true, "changed": changed, "version": ver,
			"config": s.loadCfgView(),
		})
	default:
		http.Error(w, "method", http.StatusMethodNotAllowed)
	}
}

// applyAdminConfig 把 JSON 收成和网页表单一样的 map，再交给 applyForm。
// apps 为 nil 时不碰名单——和「缺省即不动」同一条语义。
// apps 非 nil 时以服务端已有的上报列表为目录：提交里没提到的包按禁止处理，
// 提交里多出来的包直接忽略（不能靠管理端凭空加一个设备没上报的包）。
func (s *server) applyAdminConfig(body adminCfgIn) (changed bool, version int64, msg string, status int) {
	s.st.mu.Lock()
	defer s.st.mu.Unlock()
	cur, _ := s.st.effective()
	form := map[string][]string{}

	if on := pickBool(body.Master, cur.Master); on {
		form["master"] = []string{"1"}
	}
	if on := pickBool(body.Guard, cur.GuardSettings); on {
		form["guard"] = []string{"1"}
	}
	ws := minToHHMM(cur.WinStart, "00:00")
	if body.WinStart != nil && strings.TrimSpace(*body.WinStart) != "" {
		ws = strings.TrimSpace(*body.WinStart)
	}
	we := minToHHMM(cur.WinEnd, "23:59")
	if body.WinEnd != nil && strings.TrimSpace(*body.WinEnd) != "" {
		we = strings.TrimSpace(*body.WinEnd)
	}
	form["winStart"] = []string{ws}
	form["winEnd"] = []string{we}

	coolUse := intOr(cur.CoolUseMin, 3)
	if body.CoolUse != nil {
		coolUse = *body.CoolUse
	}
	coolIval := intOr(cur.CoolIntervalMin, 50)
	if body.CoolIval != nil {
		coolIval = *body.CoolIval
	}
	form["coolUse"] = []string{strconv.Itoa(coolUse)}
	form["coolIval"] = []string{strconv.Itoa(coolIval)}

	var apps []AppInfo
	if body.Apps != nil {
		if len(s.st.d.Apps) == 0 {
			return false, cur.Version, "手机还没上报应用列表，名单部分暂不可编辑", http.StatusConflict
		}
		by := map[string]adminAppIn{}
		for _, a := range *body.Apps {
			by[a.Pkg] = a
		}
		apps = s.st.d.Apps
		for _, a := range apps {
			st := "deny"
			q := 30
			if in, ok := by[a.Pkg]; ok {
				switch in.State {
				case "white", "gray", "deny":
					st = in.State
				}
				if in.Quota > 0 {
					q = in.Quota
				}
			}
			form["st_"+a.Pkg] = []string{st}
			form["q_"+a.Pkg] = []string{strconv.Itoa(q)}
		}
	}

	want := cur
	want.applyForm(form, apps)
	changed = s.st.commit(want)
	if changed {
		logf("配置已更新 version=%d", s.st.d.Cfg.Version)
	}
	return changed, s.st.d.Cfg.Version, "", http.StatusOK
}

func pickBool(in, cur *bool) bool {
	if in != nil {
		return *in
	}
	return cur != nil && *cur
}

// cst 固定东八区：服务器跑在 UTC 上，管理员看的是北京时间。不依赖系统 tzdata。
var cst = time.FixedZone("CST", 8*3600)

// fmtWhen 给「多久之前」一个人话说法：1 小时内说几分钟前，当天说时刻，更早带日期。
func fmtWhen(t, now time.Time) string {
	d := now.Sub(t)
	switch {
	case d < time.Minute:
		return "刚刚"
	case d < time.Hour:
		return strconv.Itoa(int(d/time.Minute)) + " 分钟前"
	}
	lt, ln := t.In(cst), now.In(cst)
	if lt.YearDay() == ln.YearDay() && lt.Year() == ln.Year() {
		return "今天 " + lt.Format("15:04") + " "
	}
	return lt.Format("01-02 15:04") + " "
}

// fmtAge：「刚刚 / 3 分钟前 / 1 小时 5 分前」，别把 Go 的 1m23s 直接给人看。
func fmtAge(d time.Duration) string {
	switch {
	case d < time.Minute:
		return "刚刚"
	case d < time.Hour:
		return strconv.Itoa(int(d/time.Minute)) + " 分钟前"
	default:
		h := int(d / time.Hour)
		m := int(d%time.Hour) / int(time.Minute)
		if m == 0 {
			return strconv.Itoa(h) + " 小时前"
		}
		return strconv.Itoa(h) + " 小时 " + strconv.Itoa(m) + " 分前"
	}
}
