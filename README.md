# business-mcp-adapter

独立运行的个人静默签章授权 MCP 服务，供 OpenClaw 个人微信通道调用。本文按 2026-10-22 工作区代码核对。

模块使用 Java 17、Spring Boot 3.4.6 和 Redis，已加入根 POM 的 modules，也可通过独立 POM 构建。Adapter 调用现有业务 REST 接口，不直接执行文件签章。

## 文档导航

- [Windows OpenClaw 接入](OPENCLAW_WINDOWS_SETUP.md)：注册服务、动态签名补丁和排错。
- [多账号与测试指南](PERSONAL_WECHAT_MULTI_ACCOUNT_GUIDE.md)：身份签名、Redis、请求示例和隔离验收。
- [授权实现说明](PERSONAL_CA_SILENT_AUTH_CODE_CHANGES.md)：下游接口、工作流恢复、状态字段和代码入口。

## 工具与调用顺序

默认开放以下 5 个工具；客户端配置的 tools 列表可以限制可见工具。

| 工具 | 输入 | 行为 |
|---|---|---|
| `ca_psn_silent_auth_login` | 必填 username/password；可选 tenantId/clientId/grantType/code/uuid | 登录原系统并绑定当前签名微信主体 |
| `ca_psn_silent_auth_status` | 可选 includeRecords，默认 false | 查询当前用户授权有效性及最近成功记录 |
| `ca_psn_silent_auth_start` | name/mobileNo/idCardNo | 三要素校验、发送短信并返回 challengeId |
| `ca_psn_silent_auth_finish` | challengeId/authCode/riskAccepted | 用户明确确认后校验短信并推进授权，riskAccepted 必须为 true |
| `ca_psn_silent_auth_logout` | 无 | 删除当前主体会话，使当前活动 Challenge 失效 |

推荐顺序：login → status → 按需 start → 用户确认后 finish → status。start/finish/status 从 Redis 会话取得目标用户，不接受模型指定 userId。start 不返回下游 flowId/authId。

status 优先展示 `authorizationStatusText`，以 `authorizationValid` 判断当前有效性：true 为有效，false 为无有效授权，null 为未知。`latestAuth.status=2` 表示历史授权成功，不能单独证明当前有效，也不能证明本次 Challenge 已完成。

finish 返回 `completed=true` 才表示本次流程已确认完成。工具调用本身成功（ok=true）仍可能返回 APPLICATION_UNKNOWN 等待处理状态。

## 启动配置

需要 Java 17、Maven、可访问的 Redis 和原业务系统。完整默认值见 [application.yml](src/main/resources/application.yml)。

| 环境变量 | 默认值 / 要求 |
|---|---|
| BUSINESS_MCP_PORT | 18081 |
| BUSINESS_MCP_CLIENT_ID | openclaw-wechat，作为主体隔离的一部分，部署后保持稳定 |
| BUSINESS_MCP_TOKEN | local 默认 dev-token-change-me；实际接入设置独立 Token |
| BUSINESS_MCP_ENV | local；非 local 禁止默认 Token |
| BUSINESS_MCP_CHANNEL_SECRET | 必填，至少 32 字符，与 OpenClaw 签名器一致 |
| BUSINESS_MCP_DATA_KEY | 必填，Base64 解码后恰好 32 字节的 AES 密钥；重启须保持一致 |
| BUSINESS_MCP_REDIS_HOST / PORT | 127.0.0.1 / 6379 |
| BUSINESS_MCP_REDIS_DATABASE / PASSWORD / TIMEOUT | 0 / 空 / 3s |
| BUSINESS_API_BASE_URL | http://localhost:8080；设置实际业务 API 地址，必要时含 /prod-api |
| BUSINESS_API_LOGIN_CLIENT_ID | e5cd7e4891bf95d1d19206ce24a7b32e |
| BUSINESS_API_LOGIN_TENANT_ID / GRANT_TYPE | 000000 / password |
| BUSINESS_API_LOGIN_PATH | /auth/login |
| BUSINESS_API_USER_INFO_PATH | /system/user/getInfo |
| BUSINESS_MCP_MAX_REQUESTS | 60；默认窗口 1 分钟，按 MCP Token 的入口和工具分别计数 |

当前公开工作流要求先 login。虽然配置类仍保留 BUSINESS_API_TOKEN/CLIENT_ID，start/finish/status 调用的是 auth.current()，不会使用静态 Token 自动建立会话。

IDEA 运行主类 `com.earchives.mcp.adapter.BusinessMcpAdapterApplication`，工作目录设为项目根目录或本模块目录。应用依次导入 `./business-mcp-adapter/application-local.yml`、`./application-local.yml`（均可选）；本地文件中的显式值可能覆盖 application.yml 中的环境变量占位配置。

本地配置文件已忽略，不打包进 JAR。除下游地址外，还须提供签名密钥、AES 密钥和 Redis 配置；仅设置业务地址不足以启动。AES 密钥可首次生成后安全保存，不要每次启动都重新生成：

```powershell
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
$env:BUSINESS_MCP_DATA_KEY=[Convert]::ToBase64String($bytes)
```

在项目根目录执行（先设置上述必填项）：

```powershell
$env:JAVA_HOME='D:\CodeSoft\Java\jdk17' # 按本机 JDK 路径调整
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -f business-mcp-adapter/pom.xml -DskipTests package
java -jar business-mcp-adapter/target/business-mcp-adapter.jar
```

健康检查：`GET http://127.0.0.1:18081/actuator/health`。健康状态不代表业务登录或 CA 链路已验收。

## MCP 接入

入口为 POST /mcp，Content-Type 为 application/json，所有请求携带 `Authorization: Bearer <MCP Token>`。默认协议版本为 2025-06-18，实现单条 JSON-RPC 的 initialize、notifications/initialized、tools/list、tools/call；不提供批量调用或 SSE 订阅接口。

initialize/tools/list 不需要微信签名；tools/call 还必须携带动态头：

```text
X-OpenClaw-Channel: wechat
X-OpenClaw-User-Id: <可信通道提供的稳定用户ID>
X-OpenClaw-Timestamp: <Unix秒>
X-OpenClaw-Nonce: <16至128字符，每次请求重新生成>
X-OpenClaw-Signature: v1=<无填充Base64URL HMAC-SHA256>
Mcp-Session-Id: <可选>
```

签名原文为以下 9 行，用 LF 连接，末尾无换行：

```text
v1
POST
/mcp
wechat
<微信用户ID>
<MCP会话ID或空字符串>
<时间戳>
<nonce>
<Base64URL-NoPadding(SHA-256(实际发送的JSON字节))>
```

密钥使用 UTF-8 文本 BUSINESS_MCP_CHANNEL_SECRET，时间容差默认 5 分钟，nonce 在 Redis 保留 10 分钟。模型不能自行提供可信身份头。仓库中的 [签名器](openclaw-patch/earchives-signing.mjs) 已实现发送层签名，安装范围和版本限制见 Windows 手册。

工具结果包含 structuredContent 和文本 Data JSON。业务失败检查 isError/errorCode，协议鉴权失败检查 JSON-RPC error.data.errorCode，不能只凭 HTTP 200 判断成功。文本 JSON 超过 6000 字符会截断，结构化结果不受此限制。

## 授权恢复与边界

当前使用 POST /ca/local/auth/psn/auth 创建记录，随后调用 /ca/local/auth/psn/auth/approve。无需新增 /psn/auth/verified 接口。

创建前查询 status=1 的待批准记录；存在时返回 PENDING_APPLICATION_EXISTS。创建发送前保存 APPLICATION_UNKNOWN，批准发送前保存 APPROVAL_PENDING。结果不确定时再次 finish 只回读，不重复创建或批准。只有当前 authed=true、最近记录 status=2 且 authId 匹配本次短信流程才确认完成。

同一用户不要同时从网页和 MCP 发起申请：旧批准接口选择最新待批准记录，Adapter 无法保证跨渠道严格幂等。恢复规则和保留期限见[实现说明](PERSONAL_CA_SILENT_AUTH_CODE_CHANGES.md)。

## 验证

```powershell
mvn -f business-mcp-adapter/pom.xml test
node business-mcp-adapter/openclaw-patch/earchives-signing.test.mjs
```

Redis 集成测试依赖 Docker，不可用时自动跳过。真实 OpenClaw、微信、业务系统与 CA 端到端验收按[测试指南](PERSONAL_WECHAT_MULTI_ACCOUNT_GUIDE.md)执行。
