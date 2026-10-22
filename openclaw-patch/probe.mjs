import fs from 'node:fs';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createEarchivesFetch, withEarchivesIdentity } from 'file:///D:/CodeSoft/nodejs/node_global/node_modules/openclaw/dist/earchives-signing.mjs';

const cfg = JSON.parse(fs.readFileSync('C:/Users/23223/.openclaw/openclaw.json', 'utf8'));
const server = cfg.mcp.servers['earchives-sign'];
const yaml = fs.readFileSync(new URL('../application-local.yml', import.meta.url), 'utf8');
let secret = yaml.match(/^\s*channel-signing-secret:\s*(.*?)\s*$/m)?.[1];
if (secret?.startsWith('"')) secret = JSON.parse(secret);
else if (secret?.startsWith("'")) secret = secret.slice(1, -1).replaceAll("''", "'");
else secret = secret?.replace(/\s+#.*$/, '');
process.env.BUSINESS_MCP_CHANNEL_SECRET = secret;
const headers = Object.fromEntries(Object.entries(server.headers).map(([key, value]) => [key,
  value.replace(/\$\{([^}]+)\}/g, (_, name) => {
    if (!process.env[name]) throw new Error('Missing environment variable ' + name);
    return process.env[name];
  })]));
headers['Content-Type'] = 'application/json';
const send = (method, params) => ({ method: 'POST', headers, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }), signal: AbortSignal.timeout(10000) });
for (const method of ['initialize', 'tools/list']) {
  const response = await fetch(server.url, send(method, {}));
  const result = await response.json();
  assert.ok(result.result && !result.error, method + ' rejected');
  console.log(method + ': accepted');
}
const params = { name: 'ca_psn_silent_auth_status', arguments: {} };
const unsigned = await (await fetch(server.url, send('tools/call', params))).json();
assert.equal(unsigned.error?.data?.errorCode, 'CHANNEL_IDENTITY_REQUIRED');
console.log('Unsigned tools/call: CHANNEL_IDENTITY_REQUIRED (expected)');
const signedFetch = createEarchivesFetch('earchives-sign', server.url, fetch, fetch);
const signed = await withEarchivesIdentity({ messageChannel: 'openclaw-weixin', senderId: 'diagnostic-' + randomUUID() }, async () =>
  (await signedFetch(server.url, send('tools/call', params))).json());
assert.ok(!signed.error, 'Signed tools/call failed protocol authentication');
assert.equal(signed.result?.structuredContent?.errorCode, 'DOWNSTREAM_AUTH_REQUIRED');
console.log('Signed tools/call: DOWNSTREAM_AUTH_REQUIRED (expected for isolated unbound diagnostic identity; signature accepted)');
