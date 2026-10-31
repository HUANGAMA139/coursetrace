import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const packages = ['vue', 'vite', '@vitejs/plugin-vue'];
const report = { observedAt: new Date().toISOString(), node: process.version, packages: {} };
for (const name of packages) {
  const response = await fetch(`https://registry.npmjs.org/${encodeURIComponent(name)}/latest`, { signal: AbortSignal.timeout(30000), redirect: 'error' });
  if (!response.ok) throw new Error(`Package metadata request failed: ${name} HTTP ${response.status}`);
  const metadata = await response.json();
  report.packages[name] = { version: metadata.version, engines: metadata.engines || {}, peerDependencies: metadata.peerDependencies || {} };
}
const output = resolve(root, '.local', 'm0');
mkdirSync(output, { recursive: true });
writeFileSync(resolve(output, 'frontend-versions.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
