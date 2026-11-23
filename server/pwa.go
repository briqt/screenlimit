package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math"
	"net/http"
	"sync"
)

// PWA：manifest + service worker + 图标。
// 图标用 image/png 现画，避免往仓库里塞二进制、也省得部署时多传文件。

const manifestJSON = `{
  "name": "屏幕限时守护",
  "short_name": "限时守护",
  "description": "远程授权与规则配置",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "orientation": "portrait",
  "background_color": "#0F1115",
  "theme_color": "#0F1115",
  "icons": [
    {"src":"/icon-192.png","sizes":"192x192","type":"image/png","purpose":"any"},
    {"src":"/icon-512.png","sizes":"512x512","type":"image/png","purpose":"any"},
    {"src":"/icon-512.png","sizes":"512x512","type":"image/png","purpose":"maskable"}
  ]
}`

// Service Worker 刻意**只缓存图标与 manifest**，页面一律走网络。
// 审批页缓存了会出人命：看到的是旧的待审批列表，可能重复批准或漏批。
const swJS = `
const SHELL = 'sl-shell-v1';
const ASSETS = ['/icon-192.png','/icon-512.png','/manifest.webmanifest'];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks =>
    Promise.all(ks.filter(k => k !== SHELL).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  // 只有静态资源走缓存优先；审批页/配置页一律网络，避免看到过期状态
  if (ASSETS.includes(url.pathname)) {
    e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
  }
});
`

var (
	iconOnce sync.Once
	icon192  []byte
	icon512  []byte
)

// drawIcon 画一个圆角方底 + 白色盾牌，和 App 的自适应图标同一个意象。
func drawIcon(size int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	bg := color.RGBA{0x3B, 0x6F, 0xE0, 0xFF} // 守护蓝，与 App 图标背景一致
	fg := color.RGBA{0xFF, 0xFF, 0xFF, 0xFF}
	f := float64(size)
	radius := f * 0.22

	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			fx, fy := float64(x)+0.5, float64(y)+0.5
			if !insideRoundRect(fx, fy, f, radius) {
				continue
			}
			if insideShield(fx/f, fy/f) {
				img.Set(x, y, fg)
			} else {
				img.Set(x, y, bg)
			}
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}

func insideRoundRect(x, y, size, r float64) bool {
	cx := math.Min(math.Max(x, r), size-r)
	cy := math.Min(math.Max(y, r), size-r)
	dx, dy := x-cx, y-cy
	return dx*dx+dy*dy <= r*r || (x >= r && x <= size-r) || (y >= r && y <= size-r)
}

// insideShield 用归一化坐标（0..1）判断是否落在盾牌里：
// 上半是矩形，下半向中心收成尖角。
func insideShield(u, v float64) bool {
	const top, bottom, halfW = 0.26, 0.80, 0.21
	if v < top || v > bottom {
		return false
	}
	// 0.26~0.58 直边，之后线性收窄到 0
	var w float64
	if v <= 0.58 {
		w = halfW
	} else {
		w = halfW * (bottom - v) / (bottom - 0.58)
	}
	return math.Abs(u-0.5) <= w
}

func registerPWA(mux *http.ServeMux) {
	iconOnce.Do(func() {
		icon192 = drawIcon(192)
		icon512 = drawIcon(512)
	})
	mux.HandleFunc("/manifest.webmanifest", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/manifest+json; charset=utf-8")
		w.Header().Set("Cache-Control", "public, max-age=3600")
		_, _ = w.Write([]byte(manifestJSON))
	})
	mux.HandleFunc("/sw.js", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
		w.Header().Set("Cache-Control", "no-cache")
		_, _ = w.Write([]byte(swJS))
	})
	icons := map[string]*[]byte{"/icon-192.png": &icon192, "/icon-512.png": &icon512}
	for path, data := range icons {
		d := data
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "image/png")
			w.Header().Set("Cache-Control", "public, max-age=86400")
			_, _ = w.Write(*d)
		})
	}
}
