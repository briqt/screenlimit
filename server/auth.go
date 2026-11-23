package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

// 管理员登录：HS256 JWT 放 HttpOnly Cookie。
//
// 为什么自己写而不引库：整个服务端坚持零第三方依赖（和 App 侧同一条约束）。
// HS256 的 JWT 说到底就是 base64url(header).base64url(payload).HMAC-SHA256，
// 加上 PBKDF2 一共不到 120 行，比拉一个 jwt-go 进来更好审。

const (
	jwtTTL       = 90 * 24 * time.Hour // 管理端 App 与 PWA 一次登录 90 天免重输（见项目总纲 §2）
	pbkdf2Rounds = 120_000
	cookieName   = "sl_admin"
)

// ---------- PBKDF2-HMAC-SHA256（stdlib 没有，手写一份）----------

func pbkdf2(password, salt []byte, iter, keyLen int) []byte {
	h := sha256.New
	hLen := sha256.Size
	blocks := (keyLen + hLen - 1) / hLen
	out := make([]byte, 0, blocks*hLen)
	buf := make([]byte, 4)
	for b := 1; b <= blocks; b++ {
		binary.BigEndian.PutUint32(buf, uint32(b))
		m := hmac.New(h, password)
		m.Write(salt)
		m.Write(buf)
		u := m.Sum(nil)
		t := make([]byte, hLen)
		copy(t, u)
		for i := 1; i < iter; i++ {
			m = hmac.New(h, password)
			m.Write(u)
			u = m.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		out = append(out, t...)
	}
	return out[:keyLen]
}

type adminCred struct {
	user string
	salt []byte
	key  []byte
}

func newAdminCred(user, password string) *adminCred {
	salt := make([]byte, 16)
	_, _ = rand.Read(salt)
	return &adminCred{user: user, salt: salt, key: pbkdf2([]byte(password), salt, pbkdf2Rounds, 32)}
}

func (a *adminCred) verify(user, password string) bool {
	// 两边都比，且都用常数时间，避免用户名对不对被时间侧信道看出来
	uOK := subtle.ConstantTimeCompare([]byte(user), []byte(a.user)) == 1
	got := pbkdf2([]byte(password), a.salt, pbkdf2Rounds, 32)
	pOK := subtle.ConstantTimeCompare(got, a.key) == 1
	return uOK && pOK
}

// ---------- JWT (HS256) ----------

type claims struct {
	Sub string `json:"sub"`
	Iat int64  `json:"iat"`
	Exp int64  `json:"exp"`
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func signJWT(secret []byte, sub string) (string, error) {
	hdr := b64([]byte(`{"alg":"HS256","typ":"JWT"}`))
	now := time.Now()
	pl, err := json.Marshal(claims{Sub: sub, Iat: now.Unix(), Exp: now.Add(jwtTTL).Unix()})
	if err != nil {
		return "", err
	}
	body := hdr + "." + b64(pl)
	m := hmac.New(sha256.New, secret)
	m.Write([]byte(body))
	return body + "." + b64(m.Sum(nil)), nil
}

var errBadToken = errors.New("bad token")

func verifyJWT(secret []byte, tok string) (*claims, error) {
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		return nil, errBadToken
	}
	body := parts[0] + "." + parts[1]
	m := hmac.New(sha256.New, secret)
	m.Write([]byte(body))
	want := m.Sum(nil)
	got, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || subtle.ConstantTimeCompare(got, want) != 1 {
		return nil, errBadToken
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, errBadToken
	}
	var c claims
	if err := json.Unmarshal(raw, &c); err != nil {
		return nil, errBadToken
	}
	if time.Now().Unix() >= c.Exp {
		return nil, errBadToken
	}
	return &c, nil
}

// ---------- HTTP ----------

func (s *server) issueCookie(w http.ResponseWriter) error {
	tok, err := signJWT(s.jwtSecret, s.cred.user)
	if err != nil {
		return err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     cookieName,
		Value:    tok,
		Path:     "/",
		HttpOnly: true,
		Secure:   true, // 只走 https；本服务永远在 Caddy 后面
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(jwtTTL / time.Second),
	})
	return nil
}

func (s *server) loggedIn(r *http.Request) bool {
	ck, err := r.Cookie(cookieName)
	if err != nil {
		return false
	}
	_, err = verifyJWT(s.jwtSecret, ck.Value)
	return err == nil
}

/** 未登录一律送到 /login，API 除外（API 走设备令牌）。 */
func (s *server) requireAdmin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.loggedIn(r) {
			if strings.HasPrefix(r.URL.Path, "/admin/") {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
				return
			}
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}
		next(w, r)
	}
}

func (s *server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		if s.loggedIn(r) {
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		renderLogin(w, "")
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method", http.StatusMethodNotAllowed)
		return
	}
	if !s.rateOK() {
		renderLogin(w, "尝试过于频繁，请稍后再试")
		return
	}
	if err := r.ParseForm(); err != nil {
		renderLogin(w, "请求有误")
		return
	}
	u, p := r.FormValue("user"), r.FormValue("password")
	if !s.cred.verify(u, p) {
		logf("登录失败 user=%q", u)
		renderLogin(w, "用户名或密码不正确")
		return
	}
	if err := s.issueCookie(w); err != nil {
		http.Error(w, "internal", http.StatusInternalServerError)
		return
	}
	logf("登录成功 user=%q", u)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *server) handleLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: "", Path: "/", HttpOnly: true,
		Secure: true, SameSite: http.SameSiteLaxMode, MaxAge: -1,
	})
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}
