# 屏幕限时守护 · screenlimit

Android 屏幕使用时间管理工具。**时段驱动 + 默认禁止 + 白/灰名单 + 每应用每日配额**。
**管理者与被管者分离**：被管者持有安装守护端的手机但不知道解锁密码；管理者通过 6 位密码、独立管理端 App 或网页端进行审批与远程配置。

## 核心机制

主开关打开、且当前处于设定的限制时段内时，按以下优先级链实时判定前台应用：

```
自身 / 硬安全系统应用 → 时段外或未开启 → 白名单 → 生效中临时授权 → 灰名单剩余配额 → 默认禁止
```

| 应用类别 | 行为 |
|---|---|
| 白名单 | 始终可用，不计时 |
| 灰名单 | 每日独立配额（分钟），仅亮屏前台累加，耗尽即拦截，次日 0 点重置 |
| **其余一切** | **默认禁止** —— 新装、重装、改名的应用自动落入限制范围 |
| 桌面 / 系统 UI / 输入法 / 设置 / 安装器 / 本应用 | 永不拦截（硬安全白名单，防止锁死设备） |

被拦截时弹出全屏遮挡层并显示恢复时刻，提供三条可控出口：
- **应急使用**：整机共用冷却预算（默认单次 3 分钟 / 50 分钟冷却）
- **6 位密码解锁**：可选 5 / 15 / 30 / 60 分钟或今日不限
- **远程申请**：向自建服务端发起申请，管理者在管理端 App 或网页端批准后手机自动解锁

所有临时授权均**仅在该应用处于亮屏前台时按秒扣减**（切回桌面或息屏不消耗，支持随时一键提前结束授权）；应用时钟基于 `elapsedRealtime` 锚定可信时钟，手动修改系统时间无法绕过时段与配额。

## 仓库组成

| 目录 | 说明 | 当前版本 |
|---|---|---|
| `app/` | 守护端 `com.briqt.screenlimit`：前台判定、全屏拦截、密码门设置页、系统设置看守防拆、可信时钟 | `v0.15.1` |
| `admin/` | 管理端 `com.briqt.screenlimit.admin`：SSE 实时审批推送、强提醒弹窗与快捷审批、远程规则差异预览与下发 | `v0.4.1` |
| `server/` | 远程授权与配置服务端（Go 纯标准库单体，内置管理网页与 PWA，见 [server/README.md](server/README.md)） | — |

守护端与管理端均采用 **Java 17 + Android 平台原生 View 纯代码构建，零第三方依赖**（`minSdk 29` / `targetSdk 35`）。

## 下载与构建

可直接在 [Releases](../../releases) 下载预编译的 `screenlimit-v*.apk`（守护端）与 `screenlimit-admin-v*.apk`（管理端），或本地构建：

```bash
# Debug 构建
./gradlew :app:assembleDebug :admin:assembleDebug

# 自定义固定签名构建 Release（保持签名一致方可跨版本覆盖升级而不丢失手机端规则）
SCREENLIMIT_KEYSTORE=/path/to/release.jks \
SCREENLIMIT_KEYSTORE_PASSWORD=... \
SCREENLIMIT_KEY_ALIAS=... \
SCREENLIMIT_KEY_PASSWORD=... \
  ./gradlew :app:assembleRelease :admin:assembleRelease

# 服务端测试与构建
cd server && go test -race ./... && go build -trimpath -ldflags="-s -w" -o screenlimit-server .
```

## 安装后权限与保活设置

首次安装守护端后，按应用内状态卡指引依次授予：
1. **使用情况访问权限**（前台应用事件真源）
2. **显示在其他应用上层**（全屏拦截遮挡层）
3. **无障碍服务**（窗口切换毫秒级加速与卸载/权限页防拆看守；即使无障碍被系统关闭，2 秒 UsageStats 轮询仍独立兜底拦截）
4. **忽略电池优化 / 后台不受限**与通知权限，并在应用内设置 6 位管理密码

常见国产 ROM 额外保活项（避免后台进程被系统冻结）：
- **ColorOS / RealmeUI**：系统设置 → 应用详情 → 耗电管理 → 设为「完全允许后台行为」，并在最近任务卡片中为应用加锁。
- **HyperOS / MIUI**：系统设置 → 应用设置 → 开启「自启动」并将省电策略设为「无限制」。

## 代码结构

```
app/src/main/java/com/briqt/screenlimit/
├── MonitorService.java        判定唯一入口（亮屏 tick、息屏零唤醒、今日时长回补、远程轮询）
├── AccessibilityMonitor.java  无障碍事件加速器与防拆触发入口
├── Tamper.java                防拆看守（卸载确认、应用信息、无障碍/悬浮窗/使用情况与日期时间页）
├── OverlayGuard.java          全屏拦截层与三条解锁出口
├── Rules.java                 持久化规则、配额与临时授权分账、可信时钟
├── RemoteConfig.java          远程配置落地（缺省字段保持本地原样）
├── AppCache.java              应用分类（硬安全 / 基础工具 / 可管理）与新装包即时识别
├── MainActivity.java          设置页（守护 / 应用 / 更多，6 位密码门）
├── PinPad.java                自绘 6 位数字键盘
├── RemoteClient.java          设备侧 HTTP 通信
├── Watchdog.java              非唤醒定时巡检兜底
└── BootReceiver.java          开机自启
```

## 许可证

[MIT](LICENSE)
