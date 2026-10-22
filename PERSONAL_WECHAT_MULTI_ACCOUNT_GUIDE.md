# business-mcp-adapter 个人微信多账号改造与测试指南

> 文档用途：记录 `business-mcp-adapter` 的个人微信多账号安全改造内容、启动配置、接口约定和完整测试流程，方便后续部署、排错与验收。  
> 核对日期：2026-10-22。当前仓库包含 OpenClaw 发送层补丁；业务授权使用现有后端接口。详见 [实现说明](PERSONAL_CA_SILENT_AUTH_CODE_CHANGES.md) 和 [Windows 手册](OPENCLAW_WINDOWS_SETUP.md)。  
> 注意：本文不包含任何真实 Token、签名密钥、AES 密钥、账号、手机号或身份证号。

## 1. 改造背景

旧版本使用 MCP Token 的哈希作为下游登录态缓存 Key：

```text
MCP Token Hash -> 一个 DownstreamSession
```

当一个 OpenClaw 绑定多个个人微信账号时，这些微信账号通常共享同一个 MCP Token。用户 B 登录后可能覆盖用户 A 的原系统会话，导致 A 后续查询或授权时误用 B 的身份。

本次改造后的主体关系为：

```text
OpenClaw 客户端 + wechat + 个人微信稳定用户ID
                         |
                         v
                  principalHash
                         |
             +-----------+-----------+
             |                       |
       独立下游会话              独立 Challenge/工作流
```

同一个 MCP Token 可以服务多个个人微信账号，但每个微信账号拥有独立的原系统会话和授权流程。

## 2. 改造后的系统边界

```text
个人微信用户
    -> OpenClaw
    -> MCP Token 鉴权
    -> HMAC 微信身份签名验证
    -> business-mcp-adapter
    -> Redis 会话/Challenge/状态机
    -> 原电子档案系统 REST API
    -> CA/电子签章能力
```

Adapter 负责：

- MCP JSON-RPC 接入；
- OpenClaw MCP Token 鉴权；
- 个人微信动态 HMAC 签名验证；
- 多微信用户会话隔离；
- Redis 状态保存和分布式锁；
- AES-256-GCM 敏感数据加密；
- 静默授权 Challenge 和状态机；
- 限流、审计和脱敏。

Adapter 不负责：

- 获取个人微信原始身份；该标识必须由可信 OpenClaw 通道提供；
- 直接调用 CA SDK；
- 执行 PDF 文件签章；
- 独立证明 `riskAccepted=true` 一定来自人工；OpenClaw 必须在用户明确确认后才调用 finish。

## 3. 主要代码修改

### 3.1 个人微信可信主体

新增核心类型：

- `AuthContext`：增加稳定的 `mcpClientId` 和当前客户端签名密钥；
- `ChannelPrincipal`：保存已验证的个人微信主体；
- `RequestContext`：把 requestId、MCP 客户端和微信主体统一传给工具；
- `WechatSignatureService`：校验 HMAC、时间窗口和一次性 nonce。

主体摘要规则：

```text
principalHash = SHA-256(mcpClientId | wechat | wechatUserId)
```

原始微信用户 ID 只在当前请求内存中短暂存在。Redis Key 和审计日志只使用摘要。

### 3.2 原始请求体签名

`McpController` 改为读取原始 JSON 字节。`tools/call` 在解析 JSON 方法后、执行工具前基于原始字节验证个人微信签名，防止代理或攻击者修改请求体。

`initialize` 和 `tools/list` 只需要 MCP Token；所有 `tools/call` 额外需要个人微信签名。

### 3.3 Redis 状态

Redis Key 前缀：

```text
business:mcp:v1:session:{principalHash}
business:mcp:v1:challenge:{challengeId}
business:mcp:v1:active-challenge:{principalHash}
business:mcp:v1:nonce:{mcpClientId}:{nonceHash}
business:mcp:v1:workflow-lock:{challengeId}
business:mcp:v1:rate:{tokenHash}:{mcp或toolName}
```

Redis 不可用时 Adapter 失败关闭，不会降级到共享内存会话。

### 3.4 敏感数据加密

以下数据使用 AES-256-GCM 加密后写入 Redis：

- 原系统 access token；
- 下游 flowId；
- Challenge 后续步骤需要使用的手机号。

加密特性：

- 32 字节 AES 密钥；
- 每次加密使用新的 12 字节随机 IV；
- 使用 128 位 GCM 认证标签；
- 密文被篡改时解密必须失败；
- Redis 和日志中不保存 AES 密钥。

### 3.5 五个 MCP 工具

| 工具 | 作用 | 关键安全变化 |
|---|---|---|
| `ca_psn_silent_auth_login` | 登录原系统 | 会话只绑定当前签名微信主体 |
| `ca_psn_silent_auth_start` | 三要素验证并发送短信 | 返回 `challengeId`，不暴露 flowId |
| `ca_psn_silent_auth_finish` | 验证短信并完成授权 | 只接受 Challenge，不接受 userId/mobile/authId |
| `ca_psn_silent_auth_status` | 查询授权状态 | 只能查询当前微信绑定用户 |
| `ca_psn_silent_auth_logout` | 注销 | 只清除当前微信主体的会话和 Challenge |

输入 Schema 增加：

- 字符串长度；
- 手机号、身份证号、验证码格式；
- 必填字段；
- `additionalProperties=false`；
- 结构化 `outputSchema`。

### 3.6 授权状态机

正常状态：

```text
PREPARING
  -> SMS_SENT
  -> SMS_VERIFIED
  -> APPLICATION_UNKNOWN（创建请求发送前保存）
  -> APPLICATION_CREATED
  -> APPROVAL_PENDING
  -> APPROVED
  -> VERIFIED
```

异常或待处理状态：

```text
EXPIRED
LOCKED
RETRYABLE_FAILURE
APPROVAL_UNKNOWN
VERIFY_PENDING
FAILED
```

重要语义：

- `start` 先保存 `PREPARING`，再请求下游发送短信；
- 活动指针存在且 Challenge 处于可复用状态时返回 reused=true，不重复发送短信；活动指针默认保留 5 分钟；
- Challenge 绑定微信主体、原系统用户、租户和三要素摘要；
- 验证码连续错误达到上限后进入 `LOCKED`；
- 每个下游步骤成功后立即保存状态；
- 创建异常保留 `APPLICATION_UNKNOWN`，批准异常进入 `APPROVAL_UNKNOWN`；后续 finish 只回读，不重放写请求；
- 批准成功但核验条件未满足进入 `VERIFY_PENDING`；查询异常保留原待核验状态。
- 完成条件必须同时满足当前 authed=true、最新记录 status=2、authId 匹配本次流程；详见[恢复规则](PERSONAL_CA_SILENT_AUTH_CODE_CHANGES.md)。

### 3.7 MCP 协议与审计

同时修复：

- `notifications/initialized` 不再作为未知方法报错；
- 正常处理的无 ID Notification 不返回 JSON-RPC Body；异常处理分支仍可能返回错误体；
- 只声明实际支持的 tools 能力；
- 固定工具列表使用 `listChanged=false`；
- 工具 `isError=true` 时审计不再误记为成功；
- `X-Request-Id` 限制长度和字符集；
- 登录信息、状态记录等在各自输出路径脱敏；统一响应包装器负责结构化结果和文本展示。

## 4. 本地启动配置

本地真实配置保存在：

```text
business-mcp-adapter/application-local.yml
```

该文件被 `.gitignore` 忽略，不会被打包进 JAR，也不应提交到 Git。

主要环境变量与 YAML 配置关系：

| 环境变量 | YAML位置 | 意义 |
|---|---|---|
| `BUSINESS_MCP_CLIENT_ID` | `business.mcp.auth.clients[].client-id` | OpenClaw客户端稳定名称 |
| `BUSINESS_MCP_TOKEN` | `business.mcp.auth.clients[].token` | OpenClaw访问 `/mcp` 的Bearer Token |
| `BUSINESS_MCP_CHANNEL_SECRET` | `business.mcp.auth.clients[].channel-signing-secret` | 个人微信HMAC签名密钥 |
| `BUSINESS_MCP_DATA_KEY` | `business.mcp.security.data-encryption-key` | Redis敏感数据AES密钥 |
| `BUSINESS_MCP_REDIS_HOST` | `spring.data.redis.host` | Redis地址 |
| `BUSINESS_MCP_REDIS_PORT` | `spring.data.redis.port` | Redis端口 |
| `BUSINESS_API_BASE_URL` | `business.mcp.downstream.base-url` | 原电子档案系统基础地址 |

IDEA 启动主类：

```text
com.earchives.mcp.adapter.BusinessMcpAdapterApplication
```

要求：

- Java 17；
- Redis 已启动；
- 原业务系统已启动；
- Working directory 为项目根目录或 `business-mcp-adapter`；
- `data-encryption-key` 解码后恰好为 32 字节；
- `channel-signing-secret` 至少 32 字符。

## 5. OpenClaw 个人微信签名协议

所有 `tools/call` 必须包含：

```http
Authorization: Bearer <MCP Token>
X-OpenClaw-Channel: wechat
X-OpenClaw-User-Id: <个人微信稳定用户ID>
X-OpenClaw-Timestamp: <Unix秒>
X-OpenClaw-Nonce: <至少16字符的随机值>
X-OpenClaw-Signature: v1=<Base64URL-HMAC-SHA256>
Mcp-Session-Id: <可选>
```

签名原文必须严格使用 LF 换行，最后一行不追加换行：

```text
v1
POST
/mcp
wechat
<微信用户ID>
<MCP会话ID或空字符串>
<时间戳>
<nonce>
<Base64URL(SHA-256(原始JSON字节))>
```

签名规则：

```text
signature = "v1=" + Base64URL-NoPadding(
    HMAC-SHA256(channelSigningSecret, canonicalText)
)
```

安全限制：

- 只接受渠道值 `wechat`，不接受企业微信类型；
- 时间偏差最多 5 分钟；
- nonce 在 Redis 保存 10 分钟并只能使用一次；
- 修改微信用户 ID、会话 ID 或 JSON Body 都会导致签名失败。

## 6. 测试前检查

### 6.1 服务健康

访问：

```text
http://127.0.0.1:18081/actuator/health
```

预期：

```json
{"status":"UP"}
```

如果为 `DOWN`，检查 Redis 地址、端口和密码。

### 6.2 Postman环境变量

创建变量：

```text
mcpUrl=http://127.0.0.1:18081/mcp
mcpToken=<application-local.yml中的token>
channelSecret=<application-local.yml中的channel-signing-secret>
wechatUserId=test-wechat-user-A
mcpSessionId=
```

不要把真实密钥写进共享 Postman Collection。

## 7. Postman动态签名脚本

把下面脚本加入每个 `tools/call` 请求的 Pre-request Script：

```javascript
const channel = "wechat";
const userId = pm.environment.get("wechatUserId");
const sessionId = pm.environment.get("mcpSessionId") || "";
const secret = pm.environment.get("channelSecret");
const timestamp = Math.floor(Date.now() / 1000).toString();
const nonce = pm.variables.replaceIn("{{$guid}}");
const rawBody = pm.variables.replaceIn(pm.request.body.raw || "");
pm.request.body.update(rawBody); // 固定实际发送字节，避免动态变量被再次展开

function base64Url(wordArray) {
    return CryptoJS.enc.Base64.stringify(wordArray)
        .replace(/\+/g, "-")
        .replace(/\//g, "_")
        .replace(/=+$/g, "");
}

const bodyHash = base64Url(CryptoJS.SHA256(rawBody));
const canonical = [
    "v1",
    "POST",
    "/mcp",
    channel,
    userId,
    sessionId,
    timestamp,
    nonce,
    bodyHash
].join("\n");

const signature = "v1=" + base64Url(
    CryptoJS.HmacSHA256(canonical, secret)
);

pm.request.headers.upsert({key: "Authorization", value: "Bearer " + pm.environment.get("mcpToken")});
pm.request.headers.upsert({key: "X-OpenClaw-Channel", value: channel});
pm.request.headers.upsert({key: "X-OpenClaw-User-Id", value: userId});
pm.request.headers.upsert({key: "X-OpenClaw-Timestamp", value: timestamp});
pm.request.headers.upsert({key: "X-OpenClaw-Nonce", value: nonce});
pm.request.headers.upsert({key: "X-OpenClaw-Signature", value: signature});

if (sessionId) {
    pm.request.headers.upsert({key: "Mcp-Session-Id", value: sessionId});
} else {
    pm.request.headers.remove("Mcp-Session-Id");
}
```

请求 Content-Type：

```http
Content-Type: application/json
```

## 8. MCP基础测试

### 8.1 initialize

不需要微信签名：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {},
    "clientInfo": {"name": "postman-test", "version": "1.0.0"}
  }
}
```

预期返回协议版本和：

```json
"capabilities": {"tools": {"listChanged": false}}
```

### 8.2 tools/list

不需要微信签名：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

预期返回五个工具，并确认 status/finish Schema 中不存在公开 `userId`。

## 9. 单个微信用户完整流程

### 9.1 登录

Postman变量：

```text
wechatUserId=test-wechat-user-A
```

请求：

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "ca_psn_silent_auth_login",
    "arguments": {
      "username": "原系统测试账号A",
      "password": "原系统测试密码A",
      "tenantId": "000000"
    }
  }
}
```

检查：

- `isError=false`；
- 返回正确 userId；
- 手机号脱敏；
- 不返回 access token 和密码。

### 9.2 查询状态

```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "tools/call",
  "params": {
    "name": "ca_psn_silent_auth_status",
    "arguments": {"includeRecords": false}
  }
}
```

预期只查询账号 A 对应用户。优先展示 authorizationStatusText，以 authorizationValid 判断当前授权：true 有效、false 无有效授权、null 未知。latestAuth.status=2 只表示历史成功；upstreamAccountStatus 不得覆盖有效性结论。includeRecords=true 时额外返回历史记录。

### 9.3 发起短信Challenge

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "tools/call",
  "params": {
    "name": "ca_psn_silent_auth_start",
    "arguments": {
      "name": "测试姓名",
      "mobileNo": "测试手机号",
      "idCardNo": "测试身份证号"
    }
  }
}
```

预期：

```json
{
  "challengeId": "UUID",
  "mobile": "137****0000",
  "status": "SMS_SENT",
  "reused": false
}
```

响应中不应出现 flowId、authId、完整手机号或身份证号。

未过期时重复 start，预期 `reused=true`，且不重复发送短信。

### 9.4 完成授权

```json
{
  "jsonrpc": "2.0",
  "id": 13,
  "method": "tools/call",
  "params": {
    "name": "ca_psn_silent_auth_finish",
    "arguments": {
      "challengeId": "start返回的challengeId",
      "authCode": "短信验证码",
      "riskAccepted": true
    }
  }
}
```

可能结果：

- `VERIFIED` 且 completed=true：本次流程授权并回读确认成功；
- `APPLICATION_UNKNOWN`：创建结果不确定，到原系统核对，重试 finish 不会重复创建；
- `VERIFY_PENDING`：批准已确认，但最终核验条件尚未满足；
- `APPROVAL_UNKNOWN`：批准请求结果不确定，需要稍后重新核验。

完成后再次调用 status，确认账号当前授权状态。未完成时保留 challengeId，重试 finish 核验本次流程；isError=false 或 ok=true 不等于 completed=true。

### 9.5 注销

```json
{
  "jsonrpc": "2.0",
  "id": 14,
  "method": "tools/call",
  "params": {
    "name": "ca_psn_silent_auth_logout",
    "arguments": {}
  }
}
```

注销后调用 status，预期 `DOWNSTREAM_AUTH_REQUIRED`。

## 10. 多个人微信隔离测试

1. 设置 `wechatUserId=test-wechat-user-A`，登录原系统账号 A；
2. 设置 `wechatUserId=test-wechat-user-B`，使用相同 MCP Token 登录账号 B；
3. 切回 A 调用 status，必须仍返回 A；
4. 切回 B 调用 status，必须仍返回 B；
5. A 注销后，B 必须继续正常使用。

验收关系：

```text
同一个 MCP Token
  +-- 微信 A -> 会话 A
  +-- 微信 B -> 会话 B
```

任何一次返回错用户都属于生产阻断问题。

## 11. 跨用户Challenge测试

1. 微信 A 调用 start，取得 `challengeId-A`；
2. 切换到微信 B；
3. B 使用 `challengeId-A` 调用 finish。

预期：

```text
CHALLENGE_PERMISSION_DENIED
```

同时不得发生短信验证、申请创建或批准调用。

## 12. 安全攻击测试

### 12.1 nonce重放

复制完整请求头和 Body，关闭动态签名脚本后原样重发。

预期：

```text
SIGNATURE_REPLAYED
```

### 12.2 正文篡改

保留旧签名，修改 challengeId、authCode 或 riskAccepted。

预期：

```text
INVALID_CHANNEL_SIGNATURE
```

### 12.3 用户替换

签名时使用用户 A，发送前将 `X-OpenClaw-User-Id` 改为 B。

预期：

```text
INVALID_CHANNEL_SIGNATURE
```

### 12.4 过期签名

使用十分钟前的时间戳重新签名。

预期：

```text
SIGNATURE_EXPIRED
```

### 12.5 验证码锁定

对同一 Challenge 连续提交错误验证码，达到上限后预期：

```text
CHALLENGE_LOCKED
```

后续即使验证码正确也不能继续，必须重新发起 Challenge。

## 13. Redis检查

使用 RedisInsight 查看：

```text
business:mcp:v1:*
```

检查：

- Key 中不存在微信原始用户 ID；
- session 中不存在明文 access token；
- challenge 中不存在明文 flowId；
- 不存在完整身份证号；
- A、B 使用不同主体摘要 Key；
- nonce Key 有 TTL；
- workflow-lock 在流程结束后被释放。

不要手工修改工作流状态。

## 14. 自动化测试

执行：

```text
mvn -f business-mcp-adapter/pom.xml test -DskipTests=false
mvn -f business-mcp-adapter/pom.xml package -DskipTests=false
```

当前自动化测试覆盖：

- HMAC合法请求；
- JSON正文篡改；
- nonce重放；
- AES-GCM随机IV；
- AES-GCM密文篡改；
- 跨微信Challenge拒绝；
- 同一MCP Token下会话Key隔离；
- Testcontainers真实Redis会话隔离；
- 旧创建接口与批准参数、创建/批准超时和进程中断后不重放；
- 待批准记录阻断、历史授权不能证明本次成功、过期后的只读恢复；
- 当前授权有效/失效/未知、上游状态冲突和内部字段脱敏。

Docker不可用时，Testcontainers Redis测试按设计跳过，纯单元测试仍必须通过。

## 15. 常见错误排查

| 错误码/现象 | 原因 | 排查方向 |
|---|---|---|
| `CHANNEL_IDENTITY_REQUIRED` | tools/call缺少个人微信头 | 检查OpenClaw动态头配置 |
| `INVALID_CHANNEL_SIGNATURE` | 密钥、正文或签名原文不一致 | 检查LF、Body原文、Base64URL |
| `SIGNATURE_EXPIRED` | 时间偏差超过5分钟 | 同步OpenClaw和Adapter时钟 |
| `SIGNATURE_REPLAYED` | nonce重复 | 每次请求生成新nonce |
| `STATE_STORE_UNAVAILABLE` | Redis不可用 | 检查地址、端口、密码和网络 |
| `DOWNSTREAM_AUTH_REQUIRED` | 当前微信未登录或会话过期 | 重新调用login |
| `CHALLENGE_PERMISSION_DENIED` | Challenge不属于当前微信/用户 | 使用当前微信自己发起的Challenge |
| `CHALLENGE_EXPIRED` | 短信流程超过TTL | 重新调用start |
| `CHALLENGE_LOCKED` | 验证码错误次数过多 | 重新发起Challenge |
| `APPLICATION_UNKNOWN`（结果状态） | 创建结果不确定 | 到原系统核对；finish 只回读 |
| `PENDING_APPLICATION_EXISTS` | 已有待批准记录 | 到原系统核对处理后再继续 |
| `APPROVAL_UNKNOWN`（结果状态） | 批准结果不确定 | 重试 finish 核验本次流程 |
| `VERIFY_PENDING`（结果状态） | 批准已确认，最终条件尚未满足 | 重试 finish；status 仅查询账号当前状态 |

## 16. 真实OpenClaw/微信最终验收

Postman测试全部通过后，再执行真实微信验收：

1. 微信 A 登录账号 A；
2. 微信 B 登录账号 B；
3. A 查询仍然是 A；
4. B 查询仍然是 B；
5. A 发起 Challenge；
6. B 使用 A 的 Challenge 必须失败；
7. A 输入验证码完成授权；
8. A 注销后无法继续查询；
9. B 不受 A 注销影响。

如果 OpenClaw 只配置静态 Authorization，没有动态生成个人微信 HMAC 请求头，则 initialize/tools/list 可以成功，但所有工具调用都会失败。这种情况下不能通过降低 Adapter 安全校验来绕过，应完善 OpenClaw 的个人微信签名接入。

## 17. 当前限制

- Adapter 无法在不修改 OpenClaw 的情况下独立证明人工确认；
- 下游接口不支持统一幂等键，Adapter 只能通过状态持久化和回读避免盲目重放，不能宣称严格 exactly-once；
- 真实Redis、OpenClaw、原系统和CA服务的端到端测试仍需在实际环境执行；
- 生产环境应使用HTTPS、独立Redis密码和密钥管理服务；
- 更换 AES 数据密钥后，旧Redis会话和Challenge无法解密，应清理旧状态并让用户重新登录。
