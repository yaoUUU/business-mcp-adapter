package com.earchives.mcp.adapter.auth;

/**
 * 工具执行所需的可信请求上下文。
 *
 * <p>工具只能从这里取得个人微信主体，禁止从模型可编辑的 arguments 中读取 userId、
 * 微信标识或会话标识，避免模型通过参数替换造成水平越权。</p>
 */
public record RequestContext(String requestId, AuthContext client, ChannelPrincipal principal) {
}