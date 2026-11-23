# 远程授权服务端

单体 Go 程序，**仅依赖标准库**，状态持久化在单个 JSON 文件中。负责接收守护端发起的远程解锁申请、同步可管理应用列表与今日用量，并向管理端 App 及内置网页/PWA 提供审批与增量规则下发。

## 接口协议

### 设备侧（请求头须携带 `X-Device-Token: <SL_DEVICE_TOKEN>`，否则返回 `401`）

| 方法 | 路径 | 入参 | 说明 |
|---|---|---|---|
| `POST` | `/api/request` | `{id,pkg,app,ts}` | 发起解锁申请（同包名待审批自动合并，15 分钟过期） |
| `GET` | `/api/poll?id=` | — | 轮询审批状态：`pending` / `approved` / `denied` / `expired` |
| `POST` | `/api/cancel` | `{id}` | 设备端主动取消申请 |
| `POST` | `/api/apps` | 应用列表与今日用量 | 上报设备可管理应用、当前三态及今日已用分钟数 |
| `GET` | `/api/config` | — | 拉取远程配置，当 `version` 新于本地时应用变更 |

批准下发的临时口令/授权**只消费一次**：设备端取走后立即从服务端内存与状态文件中清空。

### 管理侧网页与 PWA（JWT Cookie，登录页 `/login`）

- `GET /`：待审批列表（支持一键批准 / 拒绝）
- `GET /config`：规则配置（修改 → 逐条 diff 预览 → 确认下发）
- `POST /admin/approve|deny|config`：表单操作（带 CSRF 校验与失败限速）
- 内置 PWA：`/manifest.webmanifest`、`/sw.js`、`/icon-192.png`、`/icon-512.png`（纯标准库 `image/png` 动态生成；Service Worker 仅缓存图标与 manifest，审批与配置数据始终走实时网络）。

### 管理侧原生 App（`Authorization: Bearer <token>`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/admin/login` | `{user,password}` → `{token}`（PBKDF2-HMAC-SHA256 12 万轮校验，失败限速） |
| `GET` | `/api/admin/requests` | 获取待审批请求列表（响应不含敏感口令） |
| `GET` | `/api/admin/events` | SSE 实时审批事件流（建连立推全量快照，申请/审批/取消实时推送，45 秒心跳保活） |
| `POST` | `/api/admin/approve` | `{id,password}` 批准解锁 |
| `POST` | `/api/admin/deny` | `{id}` 拒绝申请 |
| `GET` | `/api/admin/config` | 读取当前配置与设备上报的应用列表 |
| `POST` | `/api/admin/config` | 下发增量配置（未传入的字段保持设备本地原样） |

## 环境变量配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SL_LISTEN` | `127.0.0.1:8931` | 监听地址（建议仅监听回环，由 Caddy / Nginx 反向代理提供 HTTPS） |
| `SL_STATE` | `/var/lib/screenlimit/state.json` | 状态文件路径（自动生成并持久化 JWT 密钥） |
| `SL_DEVICE_TOKEN` | 必填（建议 ≥16 字符） | 设备通信令牌，需与守护端「设备令牌」一致 |
| `SL_ADMIN_USER` | `admin` | 管理员登录用户名 |
| `SL_ADMIN_PASSWORD` | 必填（≥8 字符） | 管理员登录密码 |

## 部署示例

```bash
# 1. 编译静态二进制
cd server && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags="-s -w" -o screenlimit-server .

# 2. 上传至服务器并交由 systemd 运行（由反向代理终结 TLS，例如 https://sl.example.com -> 127.0.0.1:8931）
```

在守护端「更多 → 安全与远程」中填写：
- **远程授权服务器**：`https://sl.example.com`（公网务必使用 `https://`；局域网自用可填 `IP:端口`）
- **设备令牌**：与服务端 `SL_DEVICE_TOKEN` 保持一致

## 冒烟验证

```bash
TOK="$SL_DEVICE_TOKEN"
BASE="https://sl.example.com"
curl -s -XPOST "$BASE/api/request" -H "X-Device-Token: $TOK" \
  -H 'Content-Type: application/json' -d '{"id":"t1","pkg":"com.example.demo","app":"示例应用","ts":1}'
curl -s "$BASE/api/poll?id=t1" -H "X-Device-Token: $TOK"   # pending
# 在管理端或浏览器登录 $BASE 批准后再次轮询：
curl -s "$BASE/api/poll?id=t1" -H "X-Device-Token: $TOK"   # approved（单次消费后即清空）
```
