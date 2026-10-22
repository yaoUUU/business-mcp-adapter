# Windows 本机 OpenClaw 接入 business-mcp-adapter 操作手册

## 1. 文档目标

本文说明如何在 Windows 本机把 OpenClaw 连接到 `business-mcp-adapter`，并说明个人微信多账号隔离、MCP 工具发现、动态 HMAC 签名以及常见故障的检查方法。

本文于 2026-10-22 按仓库补丁代码核对；以下为补丁适配目标，不表示已重新检测运行中的安装：

- OpenClaw：`2026.5.22`
- OpenClaw 配置文件：`C:\Users\23223\.openclaw\openclaw.json`
- Adapter MCP 地址：`http://127.0.0.1:18081/mcp`
- MCP 服务名称：`earchives-sign`
- 个人微信渠道：`openclaw-weixin`

> 安全提示：不要把真实 MCP Token、微信渠道签名密钥、AES 数据密钥、原系统账号密码或短信验证码写入本文、提交到 Git，或发送到聊天日志。

## 2. 接入链路与必要条件

完整调用链如下：

```text
个人微信用户
  → openclaw-weixin
  → OpenClaw Agent
  → business-mcp-adapter /mcp
  → 原业务系统和 CA 接口
```

启动前需要确认：

1. `business-mcp-adapter` 已监听 `18081`。
2. Adapter 能访问 Redis；默认地址为 `127.0.0.1:6379`。
3. 原业务系统可从 Adapter 所在机器访问。
4. OpenClaw 已连接个人微信渠道。
5. OpenClaw 使用的 Bearer Token 与 Adapter 的 `business.mcp.auth.clients[].token` 完全一致。
6. 实际调用工具前，OpenClaw 必须具备本文第 7 节所述的动态 HMAC 签名能力。

## 3. 备份 OpenClaw 配置

修改前备份：

```powershell
$configPath = Join-Path $env:USERPROFILE '.openclaw\openclaw.json'
$backupPath = "$configPath.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
Copy-Item -LiteralPath $configPath -Destination $backupPath
```

如需回滚，应先停止 OpenClaw，然后将备份复制回 `openclaw.json`。不要在 OpenClaw 正在写配置时直接覆盖文件。

## 4. 配置个人微信会话隔离

执行：

```powershell
openclaw config set session.dmScope per-account-channel-peer
```

该配置按照“微信接入账号 + 渠道 + 消息发送人”隔离私聊会话。它可以避免多个微信接入账号或多个个人微信用户共享同一个 OpenClaw 私聊上下文。

配置后检查：

```powershell
openclaw config get session.dmScope
```

期望结果：

```text
per-account-channel-peer
```

这个设置只负责 OpenClaw 会话隔离，不能代替 Adapter 的微信主体签名和 Redis 数据隔离。

## 5. 注册 business-mcp-adapter

OpenClaw 配置中的目标结构如下。`<与 Adapter 一致的 MCP Token>` 必须替换为真实值，但不得提交到 Git。

```json
{
  "mcp": {
    "servers": {
      "earchives-sign": {
        "url": "http://127.0.0.1:18081/mcp",
        "transport": "streamable-http",
        "connectionTimeoutMs": 10000,
        "headers": {
          "Authorization": "Bearer <与 Adapter 一致的 MCP Token>"
        }
      }
    }
  }
}
```

也可以使用 OpenClaw CLI 写入单个 MCP 服务。以下命令中的 Token 只是占位符：

```powershell
openclaw mcp set earchives-sign '{"url":"http://127.0.0.1:18081/mcp","transport":"streamable-http","connectionTimeoutMs":10000,"headers":{"Authorization":"Bearer <与 Adapter 一致的 MCP Token>"}}'
```

如果 OpenClaw 和 Adapter 不在同一台机器，应把 `127.0.0.1` 改成 Adapter 所在机器的可达 IP 或域名，并配置防火墙和 HTTPS。

## 6. 检查 MCP 注册结果

执行：

```powershell
openclaw config validate
openclaw mcp list
openclaw mcp show earchives-sign --json
```

检查结果应满足：

- 服务名为 `earchives-sign`；
- URL 是 `http://127.0.0.1:18081/mcp`；
- transport 是 `streamable-http`；
- 存在 `Authorization` 请求头；
- OpenClaw 工具档案不是 `minimal`；
- `tools.deny` 不包含 `bundle-mcp`。

OpenClaw `2026.5.22` 的 `mcp list/show/set/unset` 只管理注册表，不会连接目标服务器。因此注册成功不等于 Adapter 可达，也不等于工具调用成功。

可使用以下命令检查端口：

```powershell
Test-NetConnection 127.0.0.1 -Port 18081
Test-NetConnection 127.0.0.1 -Port 6379
```

## 7. 动态 HMAC 签名是实际调用工具的必要条件

Adapter 对 `initialize` 和 `tools/list` 只校验 Bearer Token，但所有 `tools/call` 还必须携带以下动态请求头：

```http
X-OpenClaw-Channel: wechat
X-OpenClaw-User-Id: <当前个人微信消息发送人的稳定ID>
X-OpenClaw-Timestamp: <Unix秒>
X-OpenClaw-Nonce: <每次请求唯一的随机nonce>
X-OpenClaw-Signature: v1=<Base64URL-HMAC-SHA256>
Mcp-Session-Id: <当前会话ID，可选>
```

签名原文必须严格为：

```text
v1
POST
/mcp
wechat
<微信稳定用户ID>
<Mcp-Session-Id或空字符串>
<Unix时间戳>
<随机nonce>
<SHA-256原始JSON请求体的无填充Base64URL值>
```

签名必须在最终 JSON 请求体序列化完成之后计算，因为正文中的任何字节变化都会导致摘要和签名改变。

当前 OpenClaw `2026.5.22` 的 MCP 注册配置只提供静态 `headers`，不能仅靠 `openclaw.json` 动态生成微信用户 ID、时间戳、nonce、正文摘要和 HMAC。因此：

- 静态配置可以完成 MCP 服务注册；仓库已提供出站签名器，安装见第 13 节；
- Bearer Token 正确时，可以完成 `initialize` 和 `tools/list`；
- 如果没有额外的出站 MCP 请求签名器，`tools/call` 会被 Adapter 拒绝；
- `X-OpenClaw-User-Id` 不能配置为所有用户共用的固定值，否则会破坏多用户隔离。

动态签名器必须位于 OpenClaw 最终发送 MCP HTTP 请求的位置，并从可信的微信路由元数据中取得发送人稳定 ID。不能使用昵称、群名片或用户在消息正文中自报的 ID。

## 8. OpenClaw 中的建议调用顺序

动态签名器接通后，让 Agent 按以下顺序调用：

1. `ca_psn_silent_auth_login`
   - 传入当前微信用户提供的原系统账号和密码；
   - 登录态只绑定当前签名微信主体。
2. `ca_psn_silent_auth_start`
   - 只传入 `name`、`mobileNo`、`idCardNo`；
   - 保存返回的 `challengeId`，不要要求用户提供下游 `flowId` 或 `authId`。
3. `ca_psn_silent_auth_finish`
   - 传入 `challengeId`、短信 `authCode` 和 `riskAccepted=true`；
   - 只有用户明确确认授权后才能调用。
4. `ca_psn_silent_auth_status`
   - 只查询当前微信主体绑定的原系统用户；
   - 不允许模型传入其他人的 `userId`。
5. `ca_psn_silent_auth_logout`
   - 删除当前微信主体的下游登录态；
   - 使当前主体未完成的 Challenge 失效。

## 9. 微信端验收流程

使用微信 A：

```text
登录电子档案系统，账号是 A 的账号，密码是 A 的密码
```

登录成功后：

```text
为张三申请静默签章，手机号是 138xxxxxxxx，身份证号是 xxxxxxxxxxxxxxxxxx
```

收到短信后：

```text
验证码是 123456，我确认进行静默签章授权
```

最后：

```text
查询我的静默签章状态
```

再使用微信 B 登录另一个原系统账号并重复测试，验收以下隔离要求：

- B 的登录不会覆盖 A 的登录态；
- A 查询不到 B 的授权记录；
- A 不能完成或重放 B 的 `challengeId`；
- A 重新登录只替换 A 自己的登录态；
- 日志中不出现完整微信 ID、密码、Token、验证码、手机号、身份证号或 flowId。

## 10. 常见错误

### `UNAUTHORIZED` 或 Bearer Token 错误

原因：OpenClaw 的 `Authorization` 与 Adapter 当前 Token 不一致。

处理：同步 Token，重启或重载 OpenClaw 运行时，再重新建立会话。

### `CHANNEL_IDENTITY_REQUIRED`

原因：实际 `tools/call` 没有携带动态微信身份头。

处理：接入 OpenClaw 出站 MCP 动态签名器。增加固定请求头不能解决问题。

### `INVALID_CHANNEL_SIGNATURE`

检查：

- OpenClaw 与 Adapter 是否使用相同的 `channel-signing-secret`；
- 签名是否基于最终发送的原始 JSON 字节；
- Base64URL 是否去掉 `=` 填充；
- 签名原文中的换行、`POST`、`/mcp` 和字段顺序是否完全一致。

### `SIGNATURE_REPLAYED`

原因：重复使用了 nonce。

处理：每次 HTTP 请求生成新的安全随机 nonce；网络重试也必须重新生成 nonce、时间戳和签名。

### `SIGNATURE_EXPIRED` / `INVALID_SIGNATURE_TIMESTAMP`

原因：SIGNATURE_EXPIRED 表示时间偏差超过默认 300 秒；INVALID_SIGNATURE_TIMESTAMP 表示时间戳不是合法 Unix 秒整数。

处理：让两台机器使用可靠的时间同步服务。

### Redis 连接失败

Adapter 按安全要求失败关闭，不会退化到本机共享内存。检查：

```powershell
Test-NetConnection 127.0.0.1 -Port 6379
```

如果 Redis 位于 Docker、WSL 或远程机器，应确认 Adapter 的实际启动配置已经覆盖正确的 Redis 地址。

## 11. 每次修改后的最小检查清单

```text
[ ] OpenClaw 配置校验通过
[ ] session.dmScope = per-account-channel-peer
[ ] earchives-sign URL 和 transport 正确
[ ] OpenClaw Bearer Token 与 Adapter 一致
[ ] 18081 可达
[ ] Redis 可达
[ ] OpenClaw 工具档案不是 minimal
[ ] tools.deny 不包含 bundle-mcp
[ ] tools/list 能看到五个签章工具
[ ] tools/call 已注入动态微信身份签名
[ ] 微信 A/B 登录态、Challenge 和状态查询完全隔离
```

## 12. 相关资料

- OpenClaw MCP 文档：<https://docs.openclaw.ai/cli/mcp>
- OpenClaw 配置文档：<https://docs.openclaw.ai/cli/config>
- 腾讯个人微信插件：<https://github.com/Tencent/openclaw-weixin>
- Adapter 完整改造与测试文档：[PERSONAL_WECHAT_MULTI_ACCOUNT_GUIDE.md](./PERSONAL_WECHAT_MULTI_ACCOUNT_GUIDE.md)
## 13. 本机 OpenClaw 2026.5.22 动态签名补丁

本机采用 MCP Streamable HTTP 发送层补丁，不再依赖静态微信身份请求头。

补丁只匹配 MCP 服务名 `earchives-sign`，执行以下处理：

1. 从 OpenClaw 当前入站运行上下文取得可信 senderId 和消息渠道；MCP 会话 ID 从实际 HTTP 请求头读取。
2. 只接受渠道 `openclaw-weixin`，不从模型工具参数读取微信身份。
3. 对最终发送的原始 JSON 字节计算 SHA-256。
4. 每次请求生成新的 Unix 秒时间戳和 24 字节安全随机 nonce。
5. 从 Gateway 环境变量 `BUSINESS_MCP_CHANNEL_SECRET` 读取 HMAC 密钥。
6. 注入 Adapter 要求的全部微信身份和签名请求头。
7. 其他 MCP 服务继续使用 OpenClaw 原来的 HTTP 传输逻辑。

修改的 OpenClaw 文件：

```text
D:\CodeSoft\nodejs\node_global\node_modules\openclaw\dist\selection-hR-AeOeU.js
D:\CodeSoft\nodejs\node_global\node_modules\openclaw\dist\pi-bundle-mcp-runtime-CwBDIKIq.js
D:\CodeSoft\nodejs\node_global\node_modules\openclaw\dist\earchives-signing.mjs
```

安装器将上述目录和 gateway.cmd 路径写死，不会自动发现其他安装位置。安装前核对源码中的路径与打包文件名；每次备份后缀由当前时间生成，以安装器输出为准。

补丁源码、安装器和测试保存在本项目 `openclaw-patch/`：

- `earchives-signing.mjs`：通过 `AsyncLocalStorage` 隔离每次执行的可信身份，
  将 `openclaw-weixin` 映射为协议中的 `wechat`，只对 `earchives-sign` 的
  `tools/call` 签名；缺少身份或密钥时拒绝发送，签名请求禁止重定向。
- `install.mjs`：检查当前打包文件结构、验证语法、备份并安装补丁，
  从本地适配器配置读取密钥并注入 `gateway.cmd`，不输出密钥。
  当前 OpenClaw 安装目录需要 Windows 管理员写入权限。
- `earchives-signing.test.mjs`：验证协议签名、40 用户并发隔离、nonce 更新、
  缺少身份或密钥时拒绝、其他服务及发现接口保持原行为、禁止重定向。
- `probe.mjs`：连接本机适配器，用随机的未绑定诊断身份执行只读状态查询；
  无签名应返回 `CHANNEL_IDENTITY_REQUIRED`，有效签名应进入业务层并返回
  `DOWNSTREAM_AUTH_REQUIRED`，不执行真实账号登录。

从项目根目录验证：

```powershell
node business-mcp-adapter/openclaw-patch/earchives-signing.test.mjs
node business-mcp-adapter/openclaw-patch/probe.mjs
```

回滚时停止网关，将本次备份分别恢复到原路径；若签名 helper 原先存在也恢复其备份，仅在本次新增时移除
`dist\earchives-signing.mjs`，然后启动网关。回滚会恢复原来的缺少签名行为。

> OpenClaw 升级或重新安装会覆盖并可能重命名打包文件。升级后必须重新核对补丁，
> 不能机械复制旧版本文件，否则可能破坏新版本运行时。

签名 helper 运行时读取 Gateway 进程的 BUSINESS_MCP_CHANNEL_SECRET；MCP Token 由注册配置的 Authorization 提供，若配置使用环境变量占位符，也须向 Gateway 提供对应变量：

```text
BUSINESS_MCP_TOKEN
BUSINESS_MCP_CHANNEL_SECRET
```

安装器从模块 application-local.yml 读取字面量 channel-signing-secret（不支持 `${...}` 占位符），写入 gateway.cmd；仅在当前终端设置环境变量不足以满足该安装器。probe.mjs 同样依赖此本地文件，并读取固定位置的 OpenClaw 配置及已安装 helper。

如需重新生成 Gateway 启动文件，应先完成该操作，再安装补丁，否则 gateway install --force 可能覆盖密钥注入。使用具备目标目录写权限的终端，从项目根目录执行：

```powershell
node business-mcp-adapter/openclaw-patch/install.mjs
openclaw gateway restart
openclaw gateway status
node business-mcp-adapter/openclaw-patch/probe.mjs
```

首次启动可能需要约 30 至 60 秒加载模型与个人微信渠道。

