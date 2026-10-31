import { createHash, createHmac, randomUUID } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const local = {};
for (const line of readFileSync(resolve(root, '.env.local'), 'utf8').split(/\r?\n/)) {
  const match = line.match(/^([A-Z][A-Z0-9_]*)=(.*)$/);
  if (match) local[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
}
const report = { observedAt: new Date().toISOString(), results: {} };
async function run(name, action) {
  try { report.results[name] = { status: 'passed', ...await action() }; }
  catch (error) { report.results[name] = { status: 'failed', message: error.message }; }
  console.log(JSON.stringify({ service: name, ...report.results[name] }));
}
async function json(url, method = 'GET', body) {
  const response = await fetch(url, { method, signal: AbortSignal.timeout(10000), ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}) });
  if (!response.ok) throw new Error(`Local service returned HTTP ${response.status}`);
  return response.json();
}

await run('minio', async () => {
  const health = await fetch('http://127.0.0.1:9000/minio/health/ready', { signal: AbortSignal.timeout(10000) });
  if (!health.ok) throw new Error(`MinIO readiness HTTP ${health.status}`);
  // Sign a read-only S3 request. Credentials stay in memory and are sent only to the local stack.
  const date = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '');
  const day = date.slice(0, 8);
  const hash = value => createHash('sha256').update(value).digest('hex');
  const hmac = (key, value) => createHmac('sha256', key).update(value).digest();
  const payloadHash = hash('');
  const signed = 'host;x-amz-content-sha256;x-amz-date';
  const headers = `host:127.0.0.1:9000\nx-amz-content-sha256:${payloadHash}\nx-amz-date:${date}\n`;
  const canonical = `GET\n/\n\n${headers}\n${signed}\n${payloadHash}`;
  const scope = `${day}/us-east-1/s3/aws4_request`;
  const signingKey = hmac(hmac(hmac(hmac(`AWS4${local.MINIO_ROOT_PASSWORD}`, day), 'us-east-1'), 's3'), 'aws4_request');
  const signature = createHmac('sha256', signingKey).update(`AWS4-HMAC-SHA256\n${date}\n${scope}\n${hash(canonical)}`).digest('hex');
  const result = await fetch('http://127.0.0.1:9000/', { signal: AbortSignal.timeout(10000), headers: {
    'x-amz-date': date, 'x-amz-content-sha256': payloadHash,
    Authorization: `AWS4-HMAC-SHA256 Credential=coursetrace/${scope}, SignedHeaders=${signed}, Signature=${signature}`
  } });
  const xml = await result.text();
  if (!result.ok || !xml.includes('ListAllMyBucketsResult')) throw new Error(`MinIO signed S3 request failed: HTTP ${result.status}`);
  return { readiness: true, authenticatedS3Request: true };
});

await run('qdrant', async () => {
  const base = 'http://127.0.0.1:6333';
  const collection = `coursetrace_m0_${randomUUID().replaceAll('-', '')}`;
  let created = false;
  try {
    const info = await json(`${base}/`);
    await json(`${base}/collections/${collection}`, 'PUT', { vectors: { size: 1024, distance: 'Cosine' } });
    created = true;
    const vector = Array(1024).fill(0); vector[0] = 1;
    await json(`${base}/collections/${collection}/points?wait=true`, 'PUT', { points: [{ id: 1, vector, payload: { source: 'm0-probe' } }] });
    const found = await json(`${base}/collections/${collection}/points/query`, 'POST', { query: vector, limit: 1, with_payload: true });
    if (found.result?.points?.[0]?.id !== 1 || found.result.points[0].payload?.source !== 'm0-probe') throw new Error('Qdrant probe point was not retrieved');
    return { version: info.version, dimensions: 1024, upsertAndQuery: true, input: 'synthetic unit vector; no model call' };
  } finally {
    if (created) await json(`${base}/collections/${collection}`, 'DELETE');
  }
});
const output = resolve(root, '.local', 'm0'); mkdirSync(output, { recursive: true });
writeFileSync(resolve(output, 'infra-http.json'), JSON.stringify(report, null, 2) + '\n');
if (Object.values(report.results).some(result => result.status !== 'passed')) process.exitCode = 1;
