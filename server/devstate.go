package main

import (
	"sort"
	"time"
)

// DevState 是手机随应用名单一起上报的「此刻真正生效」的规则（v0.13.0 起）。
//
// 在它之前，配置页显示的是服务端自己「上次下发的值」：手机上改了主开关、时段、名单，
// 管理端都看不见；下发时又把这份旧值整份推回去，等于悄悄覆盖手机上的改动。
// 有了它，配置页的初值和 diff 基准一律以手机为准，下发时也只带与手机不同的那几项。
type DevState struct {
	Master          bool  `json:"master"`
	WinStart        int   `json:"winStart"`
	WinEnd          int   `json:"winEnd"`
	GuardSettings   bool  `json:"guardSettings"`
	CoolUseMin      int   `json:"coolUseMin"`
	CoolIntervalMin int   `json:"coolIntervalMin"`
	Applied         int64 `json:"applied"` // 手机已应用的服务端配置版本
}

func (d *DevState) sanitize() {
	clampInt := func(v *int, lo, hi int) {
		if *v < lo {
			*v = lo
		}
		if *v > hi {
			*v = hi
		}
	}
	clampInt(&d.WinStart, 0, 1440)
	clampInt(&d.WinEnd, 0, 1440)
	clampInt(&d.CoolUseMin, 1, 60)
	clampInt(&d.CoolIntervalMin, 1, 720)
}

// effective 返回配置页该显示的完整配置：手机上报的实际状态，叠上还没被手机应用的下发。
// 手机从没上报过实际状态（旧版客户端）时退回服务端自己存的那份。调用方持锁。
func (s *store) effective() (cfg Config, pending bool) {
	d := s.d.Dev
	if d == nil {
		return s.d.Cfg, false
	}
	m, gd := d.Master, d.GuardSettings
	ws, we := d.WinStart, d.WinEnd
	cu, ci := d.CoolUseMin, d.CoolIntervalMin
	wl, gray := devLists(s.d.Apps)
	cfg = Config{
		Version: s.d.Cfg.Version,
		Master:  &m, GuardSettings: &gd,
		WinStart: &ws, WinEnd: &we,
		CoolUseMin: &cu, CoolIntervalMin: &ci,
		Whitelist: &wl, Gray: &gray,
	}
	if p := s.d.Cfg; p.Version > d.Applied {
		pending = true
		if p.Master != nil {
			cfg.Master = p.Master
		}
		if p.GuardSettings != nil {
			cfg.GuardSettings = p.GuardSettings
		}
		if p.WinStart != nil {
			cfg.WinStart = p.WinStart
		}
		if p.WinEnd != nil {
			cfg.WinEnd = p.WinEnd
		}
		if p.CoolUseMin != nil {
			cfg.CoolUseMin = p.CoolUseMin
		}
		if p.CoolIntervalMin != nil {
			cfg.CoolIntervalMin = p.CoolIntervalMin
		}
		if p.Whitelist != nil {
			cfg.Whitelist = p.Whitelist
		}
		if p.Gray != nil {
			cfg.Gray = p.Gray
		}
	}
	return cfg, pending
}

// devLists 从上报的应用三态还原出手机上的白名单与配额表。
func devLists(apps []AppInfo) ([]string, map[string]int) {
	wl := []string{}
	gray := map[string]int{}
	for _, a := range apps {
		switch a.State {
		case "white":
			wl = append(wl, a.Pkg)
		case "gray":
			q := a.Quota
			if q <= 0 {
				q = 30
			}
			gray[a.Pkg] = q
		}
	}
	sort.Strings(wl)
	return wl, gray
}

// commit 把管理员想要的完整配置落成一版下发。与当前显示值（effective）相同则什么都不做。
// 手机上报过实际状态时，只保留与手机不同的字段——没动的项不下发，
// 免得管理端打开页面到点下发之间，手机上刚改的东西被一份旧值冲掉。调用方持锁。
func (s *store) commit(want Config) bool {
	eff, _ := s.effective()
	if want.snapshot(nil) == eff.snapshot(nil) {
		return false
	}
	out := want
	if d := s.d.Dev; d != nil {
		if out.Master != nil && *out.Master == d.Master {
			out.Master = nil
		}
		if out.GuardSettings != nil && *out.GuardSettings == d.GuardSettings {
			out.GuardSettings = nil
		}
		// 时段两端一起下发：手机把缺的一端补成本地值，单推一端可能拼出管理员没想要的时段
		if out.WinStart != nil && out.WinEnd != nil && *out.WinStart == d.WinStart && *out.WinEnd == d.WinEnd {
			out.WinStart, out.WinEnd = nil, nil
		}
		if out.CoolUseMin != nil && out.CoolIntervalMin != nil &&
			*out.CoolUseMin == d.CoolUseMin && *out.CoolIntervalMin == d.CoolIntervalMin {
			out.CoolUseMin, out.CoolIntervalMin = nil, nil
		}
		wl, gray := devLists(s.d.Apps)
		devSnap := (&Config{Whitelist: &wl, Gray: &gray}).snapshot(nil)
		if (&Config{Whitelist: out.Whitelist, Gray: out.Gray}).snapshot(nil) == devSnap {
			out.Whitelist, out.Gray = nil, nil
		}
	}
	// version 单调递增：设备只认「比本地新」，所以绝不能回退（同一秒保存两次也得 +1）
	v := time.Now().Unix()
	if v <= s.d.Cfg.Version {
		v = s.d.Cfg.Version + 1
	}
	out.Version = v
	s.d.Cfg = out
	s.save()
	return true
}
