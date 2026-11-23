package main

import (
	"sort"
	"strconv"
	"strings"
)

// Config 是服务端下发给设备的规则。
//
// **服务端说了算**：设备拿到 version 比本地新的配置就无条件应用，不做二次确认。
// 代价写在明处——服务端一旦被攻破，就能直接把 master 关掉从而废掉限时。
//
// 所有字段都是指针：nil = 本次不下发这一项，设备保持原样。
// 这样可以只推「时段」而不动名单，避免一次误操作把整份名单冲掉。
type Config struct {
	Version int64 `json:"version"`

	Master   *bool `json:"master,omitempty"`
	WinStart *int  `json:"winStart,omitempty"` // 0..1440，end<=start 表示跨午夜
	WinEnd   *int  `json:"winEnd,omitempty"`

	Whitelist *[]string       `json:"whitelist,omitempty"`
	Gray      *map[string]int `json:"gray,omitempty"` // 包名 → 每日分钟数

	GuardSettings *bool `json:"guardSettings,omitempty"`

	CoolUseMin      *int `json:"coolUseMin,omitempty"`
	CoolIntervalMin *int `json:"coolIntervalMin,omitempty"`
}

// AppInfo 是设备上报的一个可管理应用。服务端要能在配置页上列出它们来分配三态。
type AppInfo struct {
	Pkg     string `json:"pkg"`
	Label   string `json:"label"`
	State   string `json:"state"` // white | gray | deny，设备上报时的实际状态
	Quota   int    `json:"quota,omitempty"`
	UsedMin int    `json:"usedMin,omitempty"`
}

// 这里**刻意没有 ensure()**：早先有个把 Whitelist/Gray 兜底成空集合的函数，
// 结果是这两个字段永远非 nil、永远被序列化下发，设备端照单全收——
// 管理员只想改个时段，手机上的白名单就被一份空列表冲掉了（真机踩过）。
// 「nil = 本次不下发这一项」这条语义必须一直守住。

// applyForm 把配置页提交上来的表单合进配置，返回是否真的有变化。
func (c *Config) applyForm(form map[string][]string, apps []AppInfo) bool {
	before := c.snapshot(apps)

	c.Master = boolPtr(has(form, "master"))
	c.GuardSettings = boolPtr(has(form, "guard"))

	if v, ok := hhmmToMin(first(form, "winStart")); ok {
		c.WinStart = &v
	}
	if v, ok := hhmmToMin(first(form, "winEnd")); ok {
		c.WinEnd = &v
	}
	if v, err := strconv.Atoi(strings.TrimSpace(first(form, "coolUse"))); err == nil && v > 0 {
		c.CoolUseMin = &v
	}
	if v, err := strconv.Atoi(strings.TrimSpace(first(form, "coolIval"))); err == nil && v > 0 {
		c.CoolIntervalMin = &v
	}

	// 名单：只在配置页确实渲染过应用列表时才覆盖，避免设备还没上报就把名单清空
	if len(apps) > 0 {
		wl := []string{}
		gray := map[string]int{}
		for _, a := range apps {
			switch first(form, "st_"+a.Pkg) {
			case "white":
				wl = append(wl, a.Pkg)
			case "gray":
				q, err := strconv.Atoi(strings.TrimSpace(first(form, "q_"+a.Pkg)))
				if err != nil || q <= 0 {
					q = 30
				}
				gray[a.Pkg] = q
			}
		}
		sort.Strings(wl)
		c.Whitelist = &wl
		c.Gray = &gray
	}
	return c.snapshot(apps) != before
}

// snapshot 生成一个可比较的字符串，用来判断「这次提交到底改没改东西」。
func (c *Config) snapshot(apps []AppInfo) string {
	var b strings.Builder
	b.WriteString(bs(c.Master))
	b.WriteString("|" + is(c.WinStart) + "-" + is(c.WinEnd))
	b.WriteString("|" + bs(c.GuardSettings))
	b.WriteString("|" + is(c.CoolUseMin) + "/" + is(c.CoolIntervalMin))
	if c.Whitelist != nil {
		w := append([]string{}, *c.Whitelist...)
		sort.Strings(w)
		b.WriteString("|w:" + strings.Join(w, ","))
	}
	if c.Gray != nil {
		keys := make([]string, 0, len(*c.Gray))
		for k := range *c.Gray {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		b.WriteString("|g:")
		for _, k := range keys {
			b.WriteString(k + "=" + strconv.Itoa((*c.Gray)[k]) + ",")
		}
	}
	return b.String()
}

// ---------- 小工具 ----------

func boolPtr(v bool) *bool { return &v }

func bs(p *bool) string {
	if p == nil {
		return "-"
	}
	if *p {
		return "1"
	}
	return "0"
}

func is(p *int) string {
	if p == nil {
		return "-"
	}
	return strconv.Itoa(*p)
}

func has(form map[string][]string, k string) bool {
	v, ok := form[k]
	return ok && len(v) > 0 && v[0] != "" && v[0] != "0"
}

func first(form map[string][]string, k string) string {
	if v, ok := form[k]; ok && len(v) > 0 {
		return v[0]
	}
	return ""
}

/** "22:00" → 1320。空串或格式不对返回 ok=false。 */
func hhmmToMin(s string) (int, bool) {
	s = strings.TrimSpace(s)
	p := strings.Split(s, ":")
	if len(p) != 2 {
		return 0, false
	}
	h, e1 := strconv.Atoi(p[0])
	m, e2 := strconv.Atoi(p[1])
	if e1 != nil || e2 != nil || h < 0 || h > 24 || m < 0 || m > 59 {
		return 0, false
	}
	return h*60 + m, true
}

func minToHHMM(p *int, def string) string {
	if p == nil {
		return def
	}
	v := *p
	if v >= 1440 {
		return "24:00"
	}
	return pad2(v/60) + ":" + pad2(v%60)
}

func pad2(v int) string {
	if v < 10 {
		return "0" + strconv.Itoa(v)
	}
	return strconv.Itoa(v)
}
