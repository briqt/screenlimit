package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func newTestMux(t *testing.T) (*server, *http.ServeMux) {
	t.Helper()
	st, err := newStore(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	s := &server{
		st:           st,
		devToken:     "0123456789abcdef",
		csrf:         "csrf-token",
		cred:         newAdminCred("admin", "password1"),
		jwtSecret:    bytes.Repeat([]byte{7}, 32),
		subs:         make(map[chan struct{}]struct{}),
		pingInterval: defaultPingInterval,
	}
	mux := http.NewServeMux()
	s.registerAdminAPI(mux)
	mux.HandleFunc("/api/request", s.handleRequest)
	mux.HandleFunc("/api/poll", s.handlePoll)
	mux.HandleFunc("/api/cancel", s.handleCancel)
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

func TestAdminEventsUnauthorizedAndMethod(t *testing.T) {
	_, mux := newTestMux(t)
	if rr := doJSON(mux, "GET", "/api/admin/events", "", nil); rr.Code != http.StatusUnauthorized {
		t.Fatalf("no token: got %d", rr.Code)
	}
	if rr := doJSON(mux, "GET", "/api/admin/events", "invalid-token", nil); rr.Code != http.StatusUnauthorized {
		t.Fatalf("bad token: got %d", rr.Code)
	}
	tok := login(t, mux)
	if rr := doJSON(mux, "POST", "/api/admin/events", tok, nil); rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("post method: got %d", rr.Code)
	}
}

func TestAdminRequestsAvoidsUnneededDiskWrite(t *testing.T) {
	s, mux := newTestMux(t)
	tok := login(t, mux)

	// 无过期记录时，GET /api/admin/requests 不应触发写盘创建 state.json
	rr := doJSON(mux, "GET", "/api/admin/requests", tok, nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("get requests: %d", rr.Code)
	}
	if _, err := os.Stat(s.st.path); !os.IsNotExist(err) {
		t.Fatalf("unexpected state write on clean gc, err=%v", err)
	}

	// 塞入一条已过期的申请后，GET 触发 gc 清理并落盘
	s.st.mu.Lock()
	s.st.d.Reqs["old"] = &Request{
		ID: "old", Pkg: "com.example.old", App: "旧申请",
		Created: time.Now().Add(-requestTTL - time.Minute), State: statePending,
	}
	s.st.mu.Unlock()

	rr = doJSON(mux, "GET", "/api/admin/requests", tok, nil)
	if rr.Code != http.StatusOK {
		t.Fatalf("get requests after expire: %d", rr.Code)
	}
	if _, err := os.Stat(s.st.path); err != nil {
		t.Fatalf("expected state save when expired item cleaned: %v", err)
	}
}

type sseSnapshot struct {
	Pending int       `json:"pending"`
	Reqs    []reqView `json:"reqs"`
}

type sseStream struct {
	lines  chan string
	errs   chan error
	cancel context.CancelFunc
	resp   *http.Response
}

func openSSE(t *testing.T, baseURL, tok string) *sseStream {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, baseURL+"/api/admin/events", nil)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		cancel()
		t.Fatalf("connect sse: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		cancel()
		t.Fatalf("sse status %d: %s", resp.StatusCode, string(b))
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream; charset=utf-8" {
		_ = resp.Body.Close()
		cancel()
		t.Fatalf("unexpected Content-Type: %q", ct)
	}
	if cc := resp.Header.Get("Cache-Control"); cc != "no-cache, no-transform" {
		_ = resp.Body.Close()
		cancel()
		t.Fatalf("unexpected Cache-Control: %q", cc)
	}
	if xb := resp.Header.Get("X-Accel-Buffering"); xb != "no" {
		_ = resp.Body.Close()
		cancel()
		t.Fatalf("unexpected X-Accel-Buffering: %q", xb)
	}

	st := &sseStream{
		lines:  make(chan string, 32),
		errs:   make(chan error, 1),
		cancel: cancel,
		resp:   resp,
	}
	go func() {
		rd := bufio.NewReader(resp.Body)
		for {
			line, err := rd.ReadString('\n')
			if err != nil {
				st.errs <- err
				return
			}
			line = strings.TrimRight(line, "\r\n")
			if line == "" {
				continue
			}
			st.lines <- line
		}
	}()
	return st
}

func (st *sseStream) close() {
	st.cancel()
	_ = st.resp.Body.Close()
}

func (st *sseStream) nextData(t *testing.T) (sseSnapshot, string) {
	t.Helper()
	deadline := time.After(2 * time.Second)
	for {
		select {
		case line := <-st.lines:
			if strings.HasPrefix(line, ":") {
				continue
			}
			if !strings.HasPrefix(line, "data: ") {
				t.Fatalf("unexpected sse line: %q", line)
			}
			raw := strings.TrimPrefix(line, "data: ")
			var snap sseSnapshot
			if err := json.Unmarshal([]byte(raw), &snap); err != nil {
				t.Fatalf("bad sse json %q: %v", raw, err)
			}
			return snap, raw
		case err := <-st.errs:
			t.Fatalf("sse stream closed unexpectedly: %v", err)
		case <-deadline:
			t.Fatal("timeout waiting for sse data event")
		}
	}
}

func (st *sseStream) waitForPing(t *testing.T) {
	t.Helper()
	deadline := time.After(2 * time.Second)
	for {
		select {
		case line := <-st.lines:
			if line == ": ping" {
				return
			}
		case err := <-st.errs:
			t.Fatalf("sse stream closed while waiting for ping: %v", err)
		case <-deadline:
			t.Fatal("timeout waiting for sse ping")
		}
	}
}

func postAPI(t *testing.T, baseURL, path string, headers map[string]string, body any) {
	t.Helper()
	b, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(http.MethodPost, baseURL+path, bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		raw, _ := io.ReadAll(resp.Body)
		t.Fatalf("POST %s got %d: %s", path, resp.StatusCode, string(raw))
	}
}

func TestAdminEventsStreamLifecycleAndHeartbeat(t *testing.T) {
	s, mux := newTestMux(t)
	s.pingInterval = 45 * time.Millisecond

	// 刻意把服务端 WriteTimeout 设得很短（200ms），验证 ResponseController.SetWriteDeadline 能续期长连接
	ts := httptest.NewUnstartedServer(secHeaders(mux))
	ts.Config.WriteTimeout = 200 * time.Millisecond
	ts.Start()
	defer ts.Close()

	tok := login(t, mux)
	stream := openSSE(t, ts.URL, tok)
	defer stream.close()

	// 1. 建连后立即收到初始空快照
	snap, _ := stream.nextData(t)
	if snap.Pending != 0 || len(snap.Reqs) != 0 {
		t.Fatalf("initial snapshot want empty, got %+v", snap)
	}

	devHdr := map[string]string{"X-Device-Token": s.devToken}
	admHdr := map[string]string{"Authorization": "Bearer " + tok}

	// 2. 设备发起申请 -> 实时收到 pending 快照
	postAPI(t, ts.URL, "/api/request", devHdr, map[string]any{
		"id": "r1", "pkg": "com.example.demo", "app": "示例应用（申请 15 分钟）", "ts": 1,
	})
	snap, _ = stream.nextData(t)
	if snap.Pending != 1 || len(snap.Reqs) != 1 || snap.Reqs[0].ID != "r1" || snap.Reqs[0].State != "pending" {
		t.Fatalf("after request: %+v", snap)
	}

	// 3. 管理端批准 -> 实时收到 approved 快照，且不泄露口令
	postAPI(t, ts.URL, "/api/admin/approve", admHdr, map[string]string{
		"id": "r1", "password": "654321",
	})
	snap, raw := stream.nextData(t)
	if snap.Pending != 0 || len(snap.Reqs) != 1 || snap.Reqs[0].State != "approved" {
		t.Fatalf("after approve: %+v", snap)
	}
	if strings.Contains(raw, "654321") || strings.Contains(raw, "password") {
		t.Fatalf("password leaked in sse payload: %s", raw)
	}

	// 4. 设备取消申请 -> 实时收到移除后的快照
	postAPI(t, ts.URL, "/api/cancel", devHdr, map[string]string{"id": "r1"})
	snap, _ = stream.nextData(t)
	if snap.Pending != 0 || len(snap.Reqs) != 0 {
		t.Fatalf("after cancel: %+v", snap)
	}

	// 5. 连续等待多次心跳，跨越服务端 200ms WriteTimeout 后仍不断流且可继续收事件
	for range 6 {
		stream.waitForPing(t)
	}
	postAPI(t, ts.URL, "/api/request", devHdr, map[string]any{
		"id": "r2", "pkg": "com.example.reader", "app": "阅读应用", "ts": 2,
	})
	snap, _ = stream.nextData(t)
	if snap.Pending != 1 || len(snap.Reqs) != 1 || snap.Reqs[0].ID != "r2" {
		t.Fatalf("after surviving WriteTimeout: %+v", snap)
	}

	// 6. 心跳巡检发现 TTL 过期清理时，主动推送最新 data 快照
	s.st.mu.Lock()
	s.st.d.Reqs["r2"].Created = time.Now().Add(-requestTTL - time.Minute)
	s.st.mu.Unlock()
	snap, _ = stream.nextData(t)
	if snap.Pending != 0 || len(snap.Reqs) != 0 {
		t.Fatalf("after ttl gc on ping: %+v", snap)
	}

	// 7. 客户端断开后订阅表自动回收
	stream.close()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.subMu.Lock()
		n := len(s.subs)
		s.subMu.Unlock()
		if n == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("subscriber was not cleaned up after disconnect")
}

func TestAdminEventsConcurrentRace(t *testing.T) {
	s, mux := newTestMux(t)
	s.pingInterval = 30 * time.Millisecond

	ts := httptest.NewServer(secHeaders(mux))
	defer ts.Close()

	tok := login(t, mux)
	const subCount = 5
	streams := make([]*sseStream, subCount)
	for i := range subCount {
		streams[i] = openSSE(t, ts.URL, tok)
		// 消费首帧
		streams[i].nextData(t)
	}
	defer func() {
		for _, st := range streams {
			st.close()
		}
	}()

	// 后台持续消费各订阅流，验证无阻塞、无死锁、无数据竞争
	var drainWG sync.WaitGroup
	stopDrain := make(chan struct{})
	for _, st := range streams {
		drainWG.Add(1)
		go func(st *sseStream) {
			defer drainWG.Done()
			for {
				select {
				case <-stopDrain:
					return
				case <-st.lines:
				}
			}
		}(st)
	}

	devHdr := map[string]string{"X-Device-Token": s.devToken}
	admHdr := map[string]string{"Authorization": "Bearer " + tok}

	var wg sync.WaitGroup
	for i := range 12 {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			id := fmt.Sprintf("req-%02d", i)
			pkg := fmt.Sprintf("com.example.p%02d", i)
			postAPI(t, ts.URL, "/api/request", devHdr, map[string]any{
				"id": id, "pkg": pkg, "app": "并发测试应用", "ts": i,
			})
			switch i % 3 {
			case 0:
				postAPI(t, ts.URL, "/api/admin/approve", admHdr, map[string]string{
					"id": id, "password": "123456",
				})
			case 1:
				postAPI(t, ts.URL, "/api/admin/deny", admHdr, map[string]string{"id": id})
			case 2:
				postAPI(t, ts.URL, "/api/cancel", devHdr, map[string]string{"id": id})
			}
			_ = doJSON(mux, "GET", "/api/admin/requests", tok, nil)
		}(i)
	}
	wg.Wait()
	close(stopDrain)
	drainWG.Wait()

	// 再发一条终态请求，确认所有订阅者都能最终收到包含 final 的快照
	postAPI(t, ts.URL, "/api/request", devHdr, map[string]any{
		"id": "final", "pkg": "com.example.final", "app": "终态校验", "ts": 99,
	})
	for idx, st := range streams {
		found := false
		deadline := time.After(2 * time.Second)
		for !found {
			select {
			case line := <-st.lines:
				if strings.HasPrefix(line, "data: ") && strings.Contains(line, `"id":"final"`) {
					found = true
				}
			case <-deadline:
				t.Fatalf("subscriber %d did not receive final snapshot", idx)
			}
		}
		st.close()
	}

	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		s.subMu.Lock()
		n := len(s.subs)
		s.subMu.Unlock()
		if n == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("subscribers leaked after concurrent test")
}
