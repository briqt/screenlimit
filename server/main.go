// screenlimit 远程授权 + 远程配置服务端（需求规格 F5 / F9）。
//
// 单体程序、**仅标准库**、状态落一个 JSON 文件。三组接口：
//
//	设备侧（需 X-Device-Token）
//	  POST /api/request   发起授权申请
//	  GET  /api/poll      轮询审批结果（密码只下发一次）
//	  POST /api/cancel    取消申请
//	  POST /api/apps      上报可管理应用列表（配置页要用它渲染三态）
//	  GET  /api/config    拉取远程配置（version 比本地新就无条件应用）
//
//	管理侧网页（JWT Cookie，登录页 /login）
//	  GET  /              待审批
//	  GET  /config        规则配置
//	  POST /admin/*       审批 / 保存配置
//
//	管理侧 App（Authorization: Bearer，见 admin_api.go）
//	  POST /api/admin/login · GET /api/admin/requests
//	  POST /api/admin/approve|deny · GET|POST /api/admin/config
//
//	PWA
//	  /manifest.webmanifest · /sw.js · /icon-192.png · /icon-512.png
//
// 与 v0.6 设计的差异：原设计是「纯 IP:端口、无域名无 TLS、局域网自用」。
// 现在挂在公网 Caddy 后面，任何人扫到子域都能试着批准请求——而批准的代价是
// 解锁使用者手机上的应用。所以设备侧强制 token、管理侧 JWT 登录、失败限速。
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	requestTTL = 15 * time.Minute
	failWindow = 1 * time.Minute
	failMax    = 10
)

type state string

const (
	statePending  state = "pending"
	stateApproved state = "approved"
	stateDenied   state = "denied"
)

// Request 是一次远程授权申请。
// Password 只在 state=approved 且尚未被设备取走时非空；取走即清空（F5.3）。
type Request struct {
	ID       string    `json:"id"`
	Pkg      string    `json:"pkg"`
	App      string    `json:"app"`
	TS       int64     `json:"ts"`
	Created  time.Time `json:"created"`
	State    state     `json:"state"`
	Password string    `json:"password,omitempty"`
}

type diskState struct {
	Reqs      map[string]*Request `json:"reqs"`
	Cfg       Config              `json:"cfg"`
	Apps      []AppInfo           `json:"apps"`
	AppsAt    time.Time           `json:"appsAt"`
	Dev       *DevState           `json:"dev,omitempty"` // 手机上报的实际规则；旧客户端不报则为 nil
	JWTSecret string              `json:"jwtSecret"`
}

type store struct {
	mu   sync.Mutex
	path string
	d    diskState
}

func newStore(path string) (*store, error) {
	s := &store{path: path}
	s.d.Reqs = map[string]*Request{}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(b, &s.d); err != nil {
		// 宁可从空状态起，也不要启动失败——这台机器上没人盯着崩溃
		logf("state 文件损坏，从空状态启动: %v", err)
		s.d = diskState{Reqs: map[string]*Request{}}
	}
	if s.d.Reqs == nil {
		s.d.Reqs = map[string]*Request{}
	}
	return s, nil
}

// save 必须在持有 mu 时调用。先写临时文件再 rename，避免断电留下半截 JSON。
func (s *store) save() {
	b, err := json.MarshalIndent(s.d, "", "  ")
	if err != nil {
		logf("序列化失败: %v", err)
		return
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		logf("写盘失败: %v", err)
		return
	}
	if err := os.Rename(tmp, s.path); err != nil {
		logf("rename 失败: %v", err)
	}
}

// gc 清掉过期的 pending 与已经终结很久的记录。调用方须持有 mu。
func (s *store) gc() {
	now := time.Now()
	for id, r := range s.d.Reqs {
		switch r.State {
		case statePending:
			if now.Sub(r.Created) > requestTTL {
				delete(s.d.Reqs, id)
			}
		default:
			if r.Password == "" && now.Sub(r.Created) > requestTTL {
				delete(s.d.Reqs, id)
			} else if now.Sub(r.Created) > 2*requestTTL {
				delete(s.d.Reqs, id)
			}
		}
	}
}

type server struct {
	st        *store
	devToken  string
	csrf      string
	cred      *adminCred
	jwtSecret []byte

	failMu sync.Mutex
	fails  []time.Time
}

// logf 统一走它：提醒自己**任何时候都不要把密码写进日志**。
func logf(format string, a ...any) { log.Printf(format, a...) }

func (s *server) checkDevice(w http.ResponseWriter, r *http.Request) bool {
	got := r.Header.Get("X-Device-Token")
	if subtle.ConstantTimeCompare([]byte(got), []byte(s.devToken)) != 1 {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return false
	}
	return true
}

func (s *server) rateOK() bool {
	s.failMu.Lock()
	defer s.failMu.Unlock()
	now := time.Now()
	kept := s.fails[:0]
	for _, t := range s.fails {
		if now.Sub(t) < failWindow {
			kept = append(kept, t)
		}
	}
	s.fails = kept
	if len(s.fails) >= failMax {
		return false
	}
	s.fails = append(s.fails, now)
	return true
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// ---------------------------------------------------------------- 设备侧

func (s *server) handleRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if !s.checkDevice(w, r) {
		return
	}
	var in struct {
		ID  string `json:"id"`
		Pkg string `json:"pkg"`
		App string `json:"app"`
		TS  int64  `json:"ts"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&in); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	if in.ID == "" || len(in.ID) > 64 {
		http.Error(w, "bad id", http.StatusBadRequest)
		return
	}
	s.st.mu.Lock()
	s.st.gc()
	// 同一应用若已有未处理的旧申请，自动清理替换，避免重复点击产生多条废单
	if in.Pkg != "" {
		for k, old := range s.st.d.Reqs {
			if k != in.ID && old.Pkg == in.Pkg && old.State == statePending {
				delete(s.st.d.Reqs, k)
			}
		}
	}
	if len(s.st.d.Reqs) > 200 {
		s.st.mu.Unlock()
		http.Error(w, "too many", http.StatusTooManyRequests)
		return
	}
	s.st.d.Reqs[in.ID] = &Request{
		ID: in.ID, Pkg: clip(in.Pkg, 128), App: clip(in.App, 128),
		TS: in.TS, Created: time.Now(), State: statePending,
	}
	s.st.save()
	s.st.mu.Unlock()
	logf("新申请 id=%s pkg=%s", in.ID, in.Pkg)
	writeJSON(w, map[string]string{"ok": "1"})
}

func (s *server) handlePoll(w http.ResponseWriter, r *http.Request) {
	if !s.checkDevice(w, r) {
		return
	}
	id := r.URL.Query().Get("id")
	s.st.mu.Lock()
	defer s.st.mu.Unlock()
	s.st.gc()
	req, ok := s.st.d.Reqs[id]
	if !ok {
		writeJSON(w, map[string]string{"state": "expired"})
		return
	}
	switch req.State {
	case stateApproved:
		pw := req.Password
		req.Password = "" // 消费一次即清空（F5.3）
		s.st.save()
		logf("下发授权 id=%s pkg=%s", req.ID, req.Pkg)
		writeJSON(w, map[string]string{"state": string(stateApproved), "password": pw})
	case stateDenied:
		writeJSON(w, map[string]string{"state": string(stateDenied)})
	default:
		writeJSON(w, map[string]string{"state": string(statePending)})
	}
}

func (s *server) handleCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if !s.checkDevice(w, r) {
		return
	}
	var in struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&in)
	s.st.mu.Lock()
	delete(s.st.d.Reqs, in.ID)
	s.st.save()
	s.st.mu.Unlock()
	writeJSON(w, map[string]string{"ok": "1"})
}

/** 设备上报可管理应用列表。配置页靠它渲染三态选择。 */
func (s *server) handleApps(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if !s.checkDevice(w, r) {
		return
	}
	var in struct {
		Apps []AppInfo `json:"apps"`
		Cfg  *DevState `json:"cfg"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 512<<10)).Decode(&in); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	for i := range in.Apps {
		in.Apps[i].Pkg = clip(in.Apps[i].Pkg, 128)
		in.Apps[i].Label = clip(in.Apps[i].Label, 64)
	}
	// 保持手机端上报的智能优先级排序（今日有使用/已设配额/第三方应用排在前面，系统基础工具垫底）
	s.st.mu.Lock()
	s.st.d.Apps = in.Apps
	s.st.d.AppsAt = time.Now()
	if in.Cfg != nil {
		in.Cfg.sanitize()
		s.st.d.Dev = in.Cfg
	}
	s.st.save()
	s.st.mu.Unlock()
	logf("设备上报应用 %d 个", len(in.Apps))
	writeJSON(w, map[string]string{"ok": "1"})
}

/** 设备拉配置。version 比本地新就无条件应用（服务端说了算）。 */
func (s *server) handleConfig(w http.ResponseWriter, r *http.Request) {
	if !s.checkDevice(w, r) {
		return
	}
	s.st.mu.Lock()
	cfg := s.st.d.Cfg
	s.st.mu.Unlock()
	writeJSON(w, cfg)
}

// ---------------------------------------------------------------- 管理侧

func (s *server) handleApprove(w http.ResponseWriter, r *http.Request) { s.adminAction(w, r, true) }
func (s *server) handleDeny(w http.ResponseWriter, r *http.Request)    { s.adminAction(w, r, false) }

func (s *server) adminAction(w http.ResponseWriter, r *http.Request, approve bool) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "bad form", http.StatusBadRequest)
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.FormValue("csrf")), []byte(s.csrf)) != 1 {
		http.Error(w, "csrf", http.StatusForbidden)
		return
	}
	id := r.FormValue("id")
	pw := strings.TrimSpace(r.FormValue("password"))

	if approve && !isSixDigits(pw) {
		if !s.rateOK() {
			http.Error(w, "too many attempts", http.StatusTooManyRequests)
			return
		}
		http.Error(w, "密码必须是 6 位数字", http.StatusBadRequest)
		return
	}

	// 不存在或已处理：网页上刷新回列表即可，不把内部状态码露给浏览器
	_ = s.applyDecision(id, pw, approve)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *server) handleSaveConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "bad form", http.StatusBadRequest)
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.FormValue("csrf")), []byte(s.csrf)) != 1 {
		http.Error(w, "csrf", http.StatusForbidden)
		return
	}
	s.st.mu.Lock()
	want, _ := s.st.effective()
	want.applyForm(r.Form, s.st.d.Apps)
	changed := s.st.commit(want)
	v := s.st.d.Cfg.Version
	s.st.mu.Unlock()
	if changed {
		logf("配置已更新 version=%d", v)
	}
	http.Redirect(w, r, "/config?saved="+map[bool]string{true: "1", false: "0"}[changed], http.StatusSeeOther)
}

func isSixDigits(s string) bool {
	if len(s) != 6 {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func clip(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		log.Fatalf("随机数不可用: %v", err)
	}
	return hex.EncodeToString(b)
}

func main() {
	listen := env("SL_LISTEN", "127.0.0.1:8931")
	statePath := env("SL_STATE", "/var/lib/screenlimit/state.json")
	devToken := os.Getenv("SL_DEVICE_TOKEN")
	adminUser := env("SL_ADMIN_USER", "admin")
	adminPass := os.Getenv("SL_ADMIN_PASSWORD")

	// 下限 8 而不是 16：使用者要一个自己记得住的令牌（2026-09-22 明确要求）。
	// 短令牌在公网上是可暴力猜的，猜中能读限时规则、刷授权申请——但批准仍要管理员登录。
	// 这是使用者知情后的取舍，所以只警告不拦。
	if len(devToken) < 8 {
		log.Fatal("SL_DEVICE_TOKEN 未设置或太短（至少 8 字符）")
	}
	if len(devToken) < 16 {
		log.Printf("警告：SL_DEVICE_TOKEN 只有 %d 字符，公网上可被暴力猜中，建议 ≥16", len(devToken))
	}
	if len(adminPass) < 8 {
		log.Fatal("SL_ADMIN_PASSWORD 未设置或太短（至少 8 字符）")
	}
	if err := os.MkdirAll(filepath.Dir(statePath), 0o700); err != nil {
		log.Fatalf("建目录失败: %v", err)
	}
	st, err := newStore(statePath)
	if err != nil {
		log.Fatalf("读状态失败: %v", err)
	}
	// JWT 密钥持久化：重启后已登录的 PWA 不用重新登录
	if st.d.JWTSecret == "" {
		st.d.JWTSecret = randHex(32)
		st.save()
	}
	secret, err := hex.DecodeString(st.d.JWTSecret)
	if err != nil || len(secret) < 16 {
		log.Fatal("JWT 密钥损坏，删掉 state.json 里的 jwtSecret 再启动")
	}

	s := &server{
		st: st, devToken: devToken, csrf: randHex(16),
		cred: newAdminCred(adminUser, adminPass), jwtSecret: secret,
	}

	mux := http.NewServeMux()
	// 设备侧
	mux.HandleFunc("/api/request", s.handleRequest)
	mux.HandleFunc("/api/poll", s.handlePoll)
	mux.HandleFunc("/api/cancel", s.handleCancel)
	mux.HandleFunc("/api/apps", s.handleApps)
	mux.HandleFunc("/api/config", s.handleConfig)
	s.registerAdminAPI(mux)
	// 管理侧网页
	mux.HandleFunc("/login", s.handleLogin)
	mux.HandleFunc("/logout", s.handleLogout)
	mux.HandleFunc("/admin/approve", s.requireAdmin(s.handleApprove))
	mux.HandleFunc("/admin/deny", s.requireAdmin(s.handleDeny))
	mux.HandleFunc("/admin/config", s.requireAdmin(s.handleSaveConfig))
	mux.HandleFunc("/config", s.requireAdmin(s.handleConfigPage))
	// PWA 与健康检查（不需要登录，否则 Service Worker 装不上）
	registerPWA(mux)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "ok") })
	mux.HandleFunc("/", s.requireAdmin(s.handleIndex))

	srv := &http.Server{
		Addr:              listen,
		Handler:           secHeaders(mux),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      20 * time.Second,
	}
	logf("screenlimit 服务端监听 %s，状态 %s，管理员 %s", listen, statePath, adminUser)
	log.Fatal(srv.ListenAndServe())
}

/** 基础安全头。CSP 用 'unsafe-inline' 是因为样式和脚本都内联在单文件模板里。 */
func secHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Content-Security-Policy",
			"default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:")
		next.ServeHTTP(w, r)
	})
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
