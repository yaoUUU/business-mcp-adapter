import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = 'D:/CodeSoft/nodejs/node_global/node_modules/openclaw/dist';
const gateway = 'C:/Users/23223/.openclaw/gateway.cmd';
const runtime = path.join(dist, 'pi-bundle-mcp-runtime-CwBDIKIq.js');
const selection = path.join(dist, 'selection-hR-AeOeU.js');
const helper = path.join(dist, 'earchives-signing.mjs');
const yaml = fs.readFileSync(path.join(here, '../application-local.yml'), 'utf8');
const match = yaml.match(/^\s*channel-signing-secret:\s*(.*?)\s*$/m);
if (!match) throw new Error('Adapter local signing secret not found');
let secret = match[1];
if (secret.startsWith('"')) secret = JSON.parse(secret);
else if (secret.startsWith("'")) secret = secret.slice(1, -1).replaceAll("''", "'");
else secret = secret.replace(/\s+#.*$/, '');
if (secret.length < 32 || /[\r\n"%!^&|<>]/.test(secret) || secret.includes('${')) {
  throw new Error('Signing secret cannot safely be embedded in gateway.cmd');
}
function replaceOnce(text, before, after) {
  if (text.split(before).length !== 2) throw new Error('Runtime layout changed; patch aborted');
  return text.replace(before, after);
}
let rt = fs.readFileSync(runtime, 'utf8');
let sel = fs.readFileSync(selection, 'utf8');
let cmd = fs.readFileSync(gateway, 'utf8');
if (!rt.includes('./earchives-signing.mjs')) {
  rt = 'import { createEarchivesFetch } from "./earchives-signing.mjs";\n' + rt;
  rt = replaceOnce(rt, 'fetch: fetchStreamableHttpWithRedirectScrub',
    'fetch: createEarchivesFetch(serverName, resolved.url, fetchStreamableHttpWithRedirectScrub, fetchWithUndici)');
}
if (!sel.includes('./earchives-signing.mjs')) {
  sel = 'import { withEarchivesIdentity } from "./earchives-signing.mjs";\n' + sel;
  sel = replaceOnce(sel, 'async function runAgentHarnessAttempt(params) {',
    'async function runAgentHarnessAttempt(params) {\n\treturn withEarchivesIdentity(params, () => runEarchivesAgentHarnessAttempt(params));\n}\nasync function runEarchivesAgentHarnessAttempt(params) {');
}
cmd = cmd.replace(/^.*\bset\s+"?BUSINESS_MCP_CHANNEL_SECRET=.*(?:\r?\n|$)/gmi, '');
const launch = /^.*node\.exe.*openclaw.*gateway.*$/m;
if (!launch.test(cmd)) throw new Error('Gateway launch line not found');
cmd = cmd.replace(launch, line => 'set "BUSINESS_MCP_CHANNEL_SECRET=' + secret + '"\r\n' + line);
const changes = [[helper, fs.readFileSync(path.join(here, 'earchives-signing.mjs'), 'utf8')], [runtime, rt], [selection, sel], [gateway, cmd]];
// Validate all JavaScript before changing the live installation.
for (const [file, contents] of changes.filter(([f]) => f !== gateway)) {
  const staged = path.join(here, '.validate-' + path.basename(file));
  fs.writeFileSync(staged, contents);
  try { execFileSync(process.execPath, ['--check', staged], { stdio: 'pipe' }); }
  finally { fs.unlinkSync(staged); }
}
const suffix = '.backup-earchives-' + new Date().toISOString().replace(/[:.]/g, '-');
const written = [];
try {
  for (const [file, contents] of changes) {
    const backup = fs.existsSync(file) ? file + suffix : null;
    if (backup) fs.copyFileSync(file, backup, fs.constants.COPYFILE_EXCL);
    written.push({ file, backup });
    fs.writeFileSync(file, contents);
  }
} catch (error) {
  for (const { file, backup } of written.reverse()) {
    if (backup) fs.copyFileSync(backup, file);
    else if (fs.existsSync(file)) fs.unlinkSync(file);
  }
  throw error;
}
console.log('Installed signing helper, two runtime hooks and gateway secret. Backups: ' + suffix);
