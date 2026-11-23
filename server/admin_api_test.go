package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func newTestMux(t *testing.T) (*server, *http.ServeMux) {
	t.Helper()
	st, err := newStore(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	s := &server{
		st:        st,
		devToken:  "0123456789abcdef",
		csrf:      "csrf-token",
		cred:      newAdminCred("admin", "password1"),
		jwtSecret: bytes.Repeat([]byte{7}, 32),
	}
	mux := http.NewServeMux()
	s.registerAdminAPI(mux)
	mux.HandleFunc("/api/request", s.handleRequest)
	mux.HandleFunc("/api/poll", s.handlePoll)
	mux.HandleFunc("/api/config", s.handleConfig)
	return s, mux
}

func doJSON(mux *http.ServeMux, method, path, token string, body any) *httptest.ResponseRecorder {
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	return rr
}

func login(t *testing.T, mux *http.ServeMux) string {
	t.Helper()
	rr := doJSON(mux, "POST", "/api/admin/login", "", map[string]string{
		"user": "admin", "password": "password1",
	})
	if rr.Code != 200 {
		t.Fatalf("login %d %s", rr.Code, rr.Body.String())
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil || out.Token == "" {
		t.Fatalf("token: %v %s", err, rr.Body.String())
	}
	return out.Token
}

func TestAdminLoginRejects(t *testing.T) {
	_, mux := newTestMux(t)
	rr := doJSON(mux, "POST", "/api/admin/login", "", map[string]string{
		"user": "admin", "password": "nope-nope",
	})
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("got %d", rr.Code)
	}
	rr = doJSON(mux, "GET", "/api/admin/requests", "", nil)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("no token got %d", rr.Code)
	}
	rr = doJSON(mux, "GET", "/api/admin/requests", "not-a-jwt", nil)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("bad token got %d", rr.Code)
	}
}

func TestAdminConfigOmitsAppsLeavesWhitelist(t *testing.T) {
	s, mux := newTestMux(t)
	wl := []string{"com.android.deskclock"}
	s.st.d.Cfg.Whitelist = &wl
	s.st.d.Apps = []AppInfo{{Pkg: "com.android.deskclock", Label: "时钟", State: "white"}}

	tok := login(t, mux)
	off := false
	rr := doJSON(mux, "POST", "/api/admin/config", tok, map[string]any{"master": off})
	if rr.Code != 200 {
		t.Fatalf("save %d %s", rr.Code, rr.Body.String())
	}
	if s.st.d.Cfg.Whitelist == nil || len(*s.st.d.Cfg.Whitelist) != 1 || (*s.st.d.Cfg.Whitelist)[0] != "com.android.deskclock" {
		t.Fatalf("whitelist was touched: %+v", s.st.d.Cfg.Whitelist)
	}
	if s.st.d.Cfg.Master == nil || *s.st.d.Cfg.Master {
		t.Fatalf("master not applied: %+v", s.st.d.Cfg.Master)
	}

	// 设备侧拉到的配置里白名单还在
	req := httptest.NewRequest("GET", "/api/config", nil)
	req.Header.Set("X-Device-Token", s.devToken)
	got := httptest.NewRecorder()
	mux.ServeHTTP(got, req)
	var cfg Config
	if err := json.Unmarshal(got.Body.Bytes(), &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.Whitelist == nil || len(*cfg.Whitelist) != 1 {
		t.Fatalf("device config lost whitelist: %s", got.Body.String())
	}
}

func TestAdminConfigAppsReplaceList(t *testing.T) {
	s, mux := newTestMux(t)
	s.st.d.Apps = []AppInfo{
		{Pkg: "a.clock", Label: "时钟", State: "white"},
		{Pkg: "a.game", Label: "游戏", State: "deny"},
	}
	tok := login(t, mux)
	rr := doJSON(mux, "POST", "/api/admin/config", tok, map[string]any{
		"apps": []map[string]any{
			{"pkg": "a.clock", "state": "white", "quota": 30},
			{"pkg": "a.game", "state": "gray", "quota": 15},
			{"pkg": "evil.extra", "state": "white", "quota": 1},
		},
	})
	if rr.Code != 200 {
		t.Fatalf("save %d %s", rr.Code, rr.Body.String())
	}
	if s.st.d.Cfg.Whitelist == nil || len(*s.st.d.Cfg.Whitelist) != 1 || (*s.st.d.Cfg.Whitelist)[0] != "a.clock" {
		t.Fatalf("whitelist %+v", s.st.d.Cfg.Whitelist)
	}
	if s.st.d.Cfg.Gray == nil || (*s.st.d.Cfg.Gray)["a.game"] != 15 {
		t.Fatalf("gray %+v", s.st.d.Cfg.Gray)
	}
	if _, ok := (*s.st.d.Cfg.Gray)["evil.extra"]; ok {
		t.Fatal("client invented a package")
	}
}

func TestAdminApproveOnce(t *testing.T) {
	s, mux := newTestMux(t)
	req := httptest.NewRequest("POST", "/api/request", bytes.NewBufferString(
		`{"id":"r1","pkg":"a.b","app":"知乎（申请 15 分钟）","ts":1}`))
	req.Header.Set("X-Device-Token", s.devToken)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	if rr.Code != 200 {
		t.Fatalf("request %d %s", rr.Code, rr.Body.String())
	}

	tok := login(t, mux)
	list := doJSON(mux, "GET", "/api/admin/requests", tok, nil)
	if !bytes.Contains(list.Body.Bytes(), []byte("知乎")) {
		t.Fatalf("list %s", list.Body.String())
	}
	if bytes.Contains(list.Body.Bytes(), []byte("password")) {
		t.Fatalf("password leaked into list: %s", list.Body.String())
	}

	bad := doJSON(mux, "POST", "/api/admin/approve", tok, map[string]string{"id": "r1", "password": "12"})
	if bad.Code != http.StatusBadRequest {
		t.Fatalf("short pin %d", bad.Code)
	}
	ok := doJSON(mux, "POST", "/api/admin/approve", tok, map[string]string{"id": "r1", "password": "123456"})
	if ok.Code != 200 {
		t.Fatalf("approve %d %s", ok.Code, ok.Body.String())
	}
	again := doJSON(mux, "POST", "/api/admin/approve", tok, map[string]string{"id": "r1", "password": "123456"})
	if again.Code != http.StatusConflict {
		t.Fatalf("second approve %d", again.Code)
	}

	poll := httptest.NewRequest("GET", "/api/poll?id=r1", nil)
	poll.Header.Set("X-Device-Token", s.devToken)
	pr := httptest.NewRecorder()
	mux.ServeHTTP(pr, poll)
	if !bytes.Contains(pr.Body.Bytes(), []byte(`"password":"123456"`)) {
		t.Fatalf("poll %s", pr.Body.String())
	}
	pr2 := httptest.NewRecorder()
	mux.ServeHTTP(pr2, poll)
	if bytes.Contains(pr2.Body.Bytes(), []byte("123456")) {
		t.Fatalf("password issued twice: %s", pr2.Body.String())
	}
}

// 手机上报了实际状态后：配置页以手机为准；只改一个应用时，下发里不带主开关/时段等没动的项。
func TestDevStateDrivesViewAndDelta(t *testing.T) {
	s, mux := newTestMux(t)
	body := `{"apps":[{"pkg":"a.clock","label":"时钟","state":"white"},{"pkg":"a.game","label":"游戏","state":"gray","quota":20}],` +
		`"cfg":{"master":true,"winStart":1320,"winEnd":420,"guardSettings":true,"coolUseMin":3,"coolIntervalMin":50,"applied":0}}`
	req := httptest.NewRequest("POST", "/api/apps", bytes.NewBufferString(body))
	req.Header.Set("X-Device-Token", s.devToken)
	mux.HandleFunc("/api/apps", s.handleApps)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	if rr.Code != 200 {
		t.Fatalf("apps %d %s", rr.Code, rr.Body.String())
	}
	// 服务端残留一份与手机矛盾的旧下发（已被手机应用过的版本）
	stale := false
	s.st.d.Cfg = Config{Version: 0, Master: &stale}

	tok := login(t, mux)
	var view cfgView
	_ = json.Unmarshal(doJSON(mux, "GET", "/api/admin/config", tok, nil).Body.Bytes(), &view)
	if !view.Master || view.WinStart != "22:00" || view.WinEnd != "07:00" || !view.Guard || !view.FromDevice {
		t.Fatalf("view not from device: %+v", view)
	}
	if view.Apps[1].State != "gray" || view.Apps[1].Quota != 20 {
		t.Fatalf("apps not from device: %+v", view.Apps)
	}

	rr = doJSON(mux, "POST", "/api/admin/config", tok, map[string]any{
		"apps": []map[string]any{
			{"pkg": "a.clock", "state": "white"},
			{"pkg": "a.game", "state": "deny"},
		},
	})
	if rr.Code != 200 {
		t.Fatalf("save %d %s", rr.Code, rr.Body.String())
	}
	c := s.st.d.Cfg
	if c.Master != nil || c.WinStart != nil || c.GuardSettings != nil || c.CoolUseMin != nil {
		t.Fatalf("unchanged fields were pushed: %+v", c)
	}
	if c.Whitelist == nil || len(*c.Whitelist) != 1 || c.Gray == nil || len(*c.Gray) != 0 {
		t.Fatalf("list change missing: %+v %+v", c.Whitelist, c.Gray)
	}
	// 没应用前，页面显示「应用后」的值并标出待应用
	_ = json.Unmarshal(doJSON(mux, "GET", "/api/admin/config", tok, nil).Body.Bytes(), &view)
	if !view.CfgPending || view.Apps[1].State != "deny" {
		t.Fatalf("pending view wrong: %+v", view)
	}
	// 什么都不改再存：不产生新版本
	v0 := s.st.d.Cfg.Version
	rr = doJSON(mux, "POST", "/api/admin/config", tok, map[string]any{"master": true})
	var out struct{ Changed bool }
	_ = json.Unmarshal(rr.Body.Bytes(), &out)
	if out.Changed || s.st.d.Cfg.Version != v0 {
		t.Fatalf("no-op save bumped version")
	}
}
