import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { createEarchivesFetch, withEarchivesIdentity } from './earchives-signing.mjs';

const endpoint = 'http://127.0.0.1:18081/mcp';
const secret = 'test-only-secret-0123456789abcdef0123456789';
process.env.BUSINESS_MCP_CHANNEL_SECRET = secret;
const body = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'status', arguments: { senderId: 'forged-user', text: '中文' } } });
const init = { method: 'POST', body, headers: { 'Mcp-Session-Id': 'session-1', Authorization: 'Bearer test' } };
const identity = senderId => ({ messageChannel: 'openclaw-weixin', senderId });

test('parallel users remain isolated, exact body and MCP session are signed', async () => {
  const fetch = createEarchivesFetch('earchives-sign', endpoint, () => assert.fail('unsigned'), async (_, request) => {
    await new Promise(resolve => setImmediate(resolve));
    const h = request.headers;
    const canonical = ['v1', 'POST', '/mcp', 'wechat', h.get('X-OpenClaw-User-Id'), 'session-1',
      h.get('X-OpenClaw-Timestamp'), h.get('X-OpenClaw-Nonce'), createHash('sha256').update(body).digest('base64url')].join('\n');
    assert.equal(h.get('X-OpenClaw-Signature'), 'v1=' + createHmac('sha256', secret).update(canonical).digest('base64url'));
    assert.equal(request.body, body);
    assert.equal(h.get('Authorization'), 'Bearer test');
    assert.equal(request.redirect, 'manual');
    return { status: 200, user: h.get('X-OpenClaw-User-Id'), nonce: h.get('X-OpenClaw-Nonce') };
  });
  const users = Array.from({ length: 40 }, (_, i) => 'user-' + i);
  const results = await Promise.all(users.map(user => withEarchivesIdentity(identity(user), async () => {
    await new Promise(resolve => setImmediate(resolve));
    return fetch(endpoint, init);
  })));
  assert.deepEqual(results.map(r => r.user), users);
  assert.equal(new Set(results.map(r => r.nonce)).size, users.length);
  const again = await withEarchivesIdentity(identity('user-0'), () => fetch(endpoint, init));
  assert.notEqual(again.nonce, results[0].nonce);
});

test('missing identity and other channels fail closed, ignoring model arguments', async () => {
  const fetch = createEarchivesFetch('earchives-sign', endpoint, () => assert.fail(), () => assert.fail());
  await assert.rejects(fetch(endpoint, init), /trusted/);
  await assert.rejects(withEarchivesIdentity({ messageChannel: 'webchat', senderId: 'a' }, () => fetch(endpoint, init)), /trusted/);
  await assert.rejects(withEarchivesIdentity(identity(''), () => fetch(endpoint, init)), /trusted/);
});

test('missing secret fails before network send', async () => {
  delete process.env.BUSINESS_MCP_CHANNEL_SECRET;
  try {
    const fetch = createEarchivesFetch('earchives-sign', endpoint, () => assert.fail(), () => assert.fail());
    await assert.rejects(withEarchivesIdentity(identity('a'), () => fetch(endpoint, init)), /BUSINESS_MCP_CHANNEL_SECRET/);
  } finally { process.env.BUSINESS_MCP_CHANNEL_SECRET = secret; }
});

test('other MCP services and discovery retain original fetch', async () => {
  const original = async (_, request) => request;
  assert.equal(createEarchivesFetch('other', endpoint, original, () => assert.fail()), original);
  const fetch = createEarchivesFetch('earchives-sign', endpoint, original, () => assert.fail());
  const discovery = { ...init, body: JSON.stringify({ method: 'tools/list' }) };
  assert.equal(await fetch(endpoint, discovery), discovery);
});

test('signed requests cannot redirect or change endpoint', async () => {
  let cancelled = false;
  const fetch = createEarchivesFetch('earchives-sign', endpoint, () => assert.fail(), async () => ({ status: 307, body: { cancel() { cancelled = true; } } }));
  await assert.rejects(withEarchivesIdentity(identity('a'), () => fetch(endpoint, init)), /must not redirect/);
  assert.equal(cancelled, true);
  await assert.rejects(fetch('http://example.com/mcp', init), /endpoint changed/);
});
