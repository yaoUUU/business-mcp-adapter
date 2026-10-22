import { AsyncLocalStorage } from 'node:async_hooks';
import { createHash, createHmac, randomBytes } from 'node:crypto';

const identityContext = new AsyncLocalStorage();

// Called with gateway execution metadata, never with model tool arguments.
export function withEarchivesIdentity(params, callback) {
  return identityContext.run(Object.freeze({
    channel: params.messageChannel ?? params.messageProvider,
    senderId: typeof params.senderId === 'string' ? params.senderId.trim() : '',
  }), callback);
}

export function createEarchivesFetch(serverName, serverUrl, originalFetch, directFetch) {
  if (serverName !== 'earchives-sign') return originalFetch;
  const target = new URL(serverUrl);
  if (target.pathname !== '/mcp' || target.search || target.hash || target.username || target.password) {
    throw new Error('earchives-sign requires a /mcp endpoint without query or credentials');
  }
  return async (url, init) => {
    const actual = new URL(typeof url === 'string' || url instanceof URL ? url : url.url);
    if (actual.href !== target.href) throw new Error('earchives-sign endpoint changed');
    if (init?.method?.toUpperCase() !== 'POST') return originalFetch(url, init);
    // SDK serializes once before invoking fetch. Sign those exact UTF-8 bytes.
    if (typeof init.body !== 'string') throw new Error('Unsupported earchives-sign request body');
    const message = JSON.parse(init.body);
    if (Array.isArray(message)) throw new Error('earchives-sign does not support JSON-RPC batches');
    if (message.method !== 'tools/call') return originalFetch(url, init);
    const identity = identityContext.getStore();
    if (identity?.channel !== 'openclaw-weixin' || !identity.senderId || identity.senderId.length > 256 || /[\r\n]/.test(identity.senderId)) {
      throw new Error('earchives-sign requires trusted openclaw-weixin sender identity');
    }
    const secret = process.env.BUSINESS_MCP_CHANNEL_SECRET;
    if (!secret || secret.length < 32) throw new Error('BUSINESS_MCP_CHANNEL_SECRET must contain at least 32 characters');
    const headers = new Headers(init.headers);
    const timestamp = String(Math.floor(Date.now() / 1000));
    const nonce = randomBytes(24).toString('base64url');
    const bodyHash = createHash('sha256').update(init.body, 'utf8').digest('base64url');
    const canonical = ['v1', 'POST', '/mcp', 'wechat', identity.senderId,
      (headers.get('Mcp-Session-Id') ?? '').trim(), timestamp, nonce, bodyHash].join('\n');
    headers.set('X-OpenClaw-Channel', 'wechat');
    headers.set('X-OpenClaw-User-Id', identity.senderId);
    headers.set('X-OpenClaw-Timestamp', timestamp);
    headers.set('X-OpenClaw-Nonce', nonce);
    headers.set('X-OpenClaw-Signature', 'v1=' + createHmac('sha256', secret).update(canonical, 'utf8').digest('base64url'));
    const response = await directFetch(url, { ...init, headers, redirect: 'manual' });
    if ([301, 302, 303, 307, 308].includes(response.status)) {
      await response.body?.cancel();
      throw new Error('earchives-sign signed requests must not redirect');
    }
    return response;
  };
}
