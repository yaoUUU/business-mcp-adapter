# 个人静默签章授权实现说明

按 2026-10-22 工作区代码核对。本文替代旧的逐行改造记录；旧文中的 /psn/auth/verified 部署方案和测试统计已不适用于当前实现。

## 1. 代码入口

| 文件 | 职责 |
|---|---|
| [SilentAuthWorkflowService](src/main/java/com/earchives/mcp/adapter/service/SilentAuthWorkflowService.java) | 短信 Challenge、创建/批准、只读恢复 |
| [CaPsnSilentAuthStatusTool](src/main/java/com/earchives/mcp/adapter/tool/CaPsnSilentAuthStatusTool.java) | 读取当前登录用户状态 |
| [SilentAuthStatusSummary](src/main/java/com/earchives/mcp/adapter/tool/SilentAuthStatusSummary.java) | 区分当前有效性、账户状态和历史流程状态 |
| [RedisStateService](src/main/java/com/earchives/mcp/adapter/state/RedisStateService.java) | 主体会话、Challenge、nonce 和工作流锁 |
| [SensitiveDataMasker](src/main/java/com/earchives/mcp/adapter/tool/SensitiveDataMasker.java) | 过滤内部标识、脱敏展示字段 |
| [application.yml](src/main/resources/application.yml) | 接口路径和默认配置 |

## 2. 下游接口契约

以下路径均相对于 business.mcp.downstream.base-url，由 tools.psn-silent-auth 配置覆盖。

| 步骤 | 方法与路径 | 参数位置 |
|---|---|---|
| 三要素/短信发送 | POST /ca/local/account/psn/telecom3Factors | JSON：name、mobileNo、idCardNo |
| 短信校验 | POST /ca/local/account/psn/smsVerify | JSON：flowId、authCode |
| 创建申请 | POST /ca/local/auth/psn/auth | JSON：userId、mobile |
| 批准申请 | POST /ca/local/auth/psn/auth/approve | Query：userId、authId（本次 flowId） |
| 申请列表 | GET /ca/local/auth/psn/auth | Query：userId；创建前额外传 status=1 |
| 最近成功记录 | GET /ca/local/auth/psn/auth/latest | Query：userId |
| 当前授权有效性 | GET /ca/local/auth/psn/authed | Query：userId |
| 原始账户状态 | GET /ca/local/account/psn/status | Query：userId |

三要素响应兼容 data 字符串、data 对象中的 flowId/authId/id；成功且 data=null 时，还接受 msg 中 8–64 位纯数字流程 ID。内部 flowId 加密存入 Challenge，不返回给模型。

创建使用现有接口，不传 authId；批准时通过查询参数传入 authId。批准响应解包后必须是数值 1，表示更新一条记录，否则视为结果未确认。当前无需部署旧文描述的 VerifiedPsnSealAuthModel 或新增 verified 接口。

## 3. 工作流与恢复

正常路径：

```text
PREPARING → SMS_SENT → SMS_VERIFIED
  → APPLICATION_UNKNOWN（先保存发送标记）
  → APPLICATION_CREATED → APPROVAL_PENDING（先保存发送标记）
  → APPROVED → VERIFIED
```

finish 在 Challenge 分布式锁内执行，校验微信主体、下游用户和租户均匹配。验证码校验失败会计数（包括该调用抛出的运行时异常），默认累计 5 次进入 LOCKED。

创建前必须确认待批准列表为空；非列表响应返回 DOWNSTREAM_RESPONSE_INVALID，已有记录返回 PENDING_APPLICATION_EXISTS，均不会继续创建或批准。

| 状态 | 再次 finish 的行为 |
|---|---|
| SMS_SENT / RETRYABLE_FAILURE | 调用短信校验；成功后保存 SMS_VERIFIED |
| SMS_VERIFIED | 检查待批准记录，再创建申请 |
| APPLICATION_CREATED | 继续批准；普通过期检查仍适用 |
| APPLICATION_UNKNOWN | 只查询当前有效性和最近成功记录，不重放创建 |
| APPROVAL_PENDING / APPROVAL_UNKNOWN | 只查询，不重放批准 |
| APPROVED / VERIFY_PENDING | 只查询核验 |
| VERIFIED | 返回已完成，不重新执行写操作 |
| LOCKED / EXPIRED | 拒绝继续，按错误提示处理 |

首次创建或批准异常时会立即返回未完成结果；后续 finish 才进入只读核验。批准已确认但核验条件未满足时进入 VERIFY_PENDING；查询异常不会抹掉创建/批准结果不确定的状态。

首次确认完成必须同时满足：

1. authed 严格为布尔 true；
2. 最近成功记录 status 为 2；
3. 记录 authId 与本次 Challenge 的 flowId 一致。

历史有效授权不能证明本次流程成功。APPLICATION_UNKNOWN 对应的待批准记录尚无本次流程标识，无法仅凭记录存在判断归属，应在原系统核对。status 只能查询账号当前授权，恢复本次 Challenge 必须调用 finish。

默认 Challenge 有效期 5 分钟，操作记录每次保存后保留 24 小时，活动指针每次保存后保留 5 分钟，工作流锁租期 2 分钟。待核验状态及 VERIFIED 允许越过短信有效期查询/返回结果，但仍要求会话存在、Challenge 记录未被 Redis 清理。恢复时保留原 challengeId。

start 仅在活动指针仍存在且对应状态符合复用条件时返回 reused=true；不能据此宣称永久阻止重复申请。当前配置了 start-cooldown，但工作流未读取它。session.ttl 同样不是实际登录缓存的过期来源：登录返回 expire_in 时按其计算，否则使用 downstream.login-cache-ttl（默认 25 分钟）。

## 4. 状态展示约定

| 字段 | 含义 |
|---|---|
| authorizationValid | 当前有效性；true / false / null（未知） |
| authorizationStatusText | 优先展示的结论文字 |
| authed | 与 authorizationValid 一致的兼容字段，不是身份认证状态 |
| upstreamAccountStatus | 上游原始账户响应，仅供诊断 |
| accountStatus | 根据有效性等信息归一化的账户状态 |
| statusConflict / statusConflictReason | 上游账户状态与当前有效性矛盾时提示 |
| latestAuth | 最近成功授权记录，不保证当前仍有效 |
| records | 仅 includeRecords=true 时查询并返回 |
| expiresAt | 最近记录提供 authEndDate 时复制该值 |

授权记录 status：1 进行中、2 授权成功、3 授权失败、4 取消授权。归一化 accountStatus.status：有效时为 3；无有效授权但有历史/申请迹象时为 2；上游明确未申请时为 1；无法判断时为 null。两种 status 不能混用。

各查询失败时以 `{available:false}` 表示不可用，授权有效性查询失败或非布尔值时返回 null。不能把未知解释为未授权，也不能因账户接口异常而推翻已确认的有效授权。

输出过滤 flowId、authId、personId、caLocalAuthId、creatorName 等内部字段，contact 按手机号脱敏；challengeId 和 userId 保留。会话 token、Challenge flowId 和 Challenge 手机号加密存储；会话中的用户资料并非全部加密。

## 5. 回归与部署

[SilentAuthWorkflowServiceTest](src/test/java/com/earchives/mcp/adapter/service/SilentAuthWorkflowServiceTest.java) 覆盖旧创建接口、批准参数、发送前状态持久化、超时不重放、已有待批准记录、历史授权误判防护、过期只读恢复及查询失败。[CaPsnSilentAuthStatusToolTest](src/test/java/com/earchives/mcp/adapter/tool/CaPsnSilentAuthStatusToolTest.java) 覆盖有效、过期、未知、查询失败和状态冲突。[SensitiveDataMaskerTest](src/test/java/com/earchives/mcp/adapter/tool/SensitiveDataMaskerTest.java) 覆盖内部字段过滤和 contact 脱敏。

```powershell
mvn -f business-mcp-adapter/pom.xml test
node business-mcp-adapter/openclaw-patch/earchives-signing.test.mjs
```

以本次执行输出为准，不沿用旧文测试计数。Redis Testcontainers 在 Docker 不可用时跳过。

更新代码后重新构建并重启 Adapter；部署配置若覆盖 psn-auth-path，改为 /ca/local/auth/psn/auth。未过期的 SMS_VERIFIED 可继续 finish，无需重复发送短信。持久化状态避免盲目重放，但旧后端批准最新待批准记录，仍有网页/MCP 并发竞争，不能保证跨渠道严格幂等。
