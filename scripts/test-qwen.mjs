import { readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const localConfig = resolve(root, '.env.local');
if (existsSync(localConfig)) {
  for (const line of readFileSync(localConfig, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!match || match[2].startsWith('#')) continue;
    let value = match[2];
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    if (!process.env[match[1]] && value) process.env[match[1]] = value;
  }
}

const key = process.env.DASHSCOPE_API_KEY || process.env.QWEN_API_KEY || '';
const chatBase = (process.env.QWEN_BASE_URL || 'https://dashscope.aliyuncs.com/compatible-mode/v1').replace(/\/$/, '');
const nativeBase = (process.env.DASHSCOPE_HTTP_BASE_URL || 'https://dashscope.aliyuncs.com/api/v1').replace(/\/$/, '');
const chatModel = process.env.QWEN_CHAT_MODEL || 'qwen3.7-plus';
const embeddingModel = process.env.QWEN_EMBEDDING_MODEL || 'text-embedding-v4';
const asrModel = process.env.QWEN_ASR_MODEL || 'qwen-audio-3.1-asr-flash-filetrans';
const dimensions = Number(process.env.QWEN_EMBEDDING_DIMENSIONS || 1024);
const audioUrl = process.env.QWEN_ASR_AUDIO_URL || 'https://dashscope.oss-cn-beijing.aliyuncs.com/samples/audio/paraformer/hello_world_female2.wav';
const selected = new Set(process.argv.slice(2).filter(arg => !arg.startsWith('--')));
if (selected.size === 0) ['chat', 'tools', 'embedding', 'asr'].forEach(name => selected.add(name));
for (const name of selected) if (!['chat', 'tools', 'embedding', 'asr'].includes(name)) throw new Error('Unknown test name');

function scrub(value) {
  let text = String(value ?? '');
  if (key) text = text.replaceAll(key, '[REDACTED]');
  return text.replace(/sk-[A-Za-z0-9_-]+/g, '[REDACTED]').slice(0, 1200);
}

function officialApi(url) {
  const parsed = new URL(url);
  const allowed = ['dashscope.aliyuncs.com', 'dashscope-intl.aliyuncs.com', 'dashscope-us.aliyuncs.com'];
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.search ||
      !(allowed.includes(parsed.hostname) || parsed.hostname.endsWith('.maas.aliyuncs.com'))) {
    throw new Error('Credentials may only be sent to a configured official DashScope HTTPS API endpoint');
  }
}

async function requestJson(url, { body, authenticated = true, headers = {}, timeoutMs = 45000 } = {}) {
  if (authenticated) officialApi(url);
  else {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:' || !parsed.hostname.endsWith('.aliyuncs.com')) throw new Error('Unexpected transcription result host');
  }
  const response = await fetch(url, {
    method: body === undefined ? 'GET' : 'POST',
    redirect: 'error',
    signal: AbortSignal.timeout(timeoutMs),
    headers: { ...(authenticated ? { Authorization: `Bearer ${key}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  });
  const raw = await response.text();
  let data;
  try { data = JSON.parse(raw); } catch { throw new Error(`HTTP ${response.status}: invalid JSON from service`); }
  if (!response.ok) {
    const error = new Error(scrub(data.error?.message || data.message || `HTTP ${response.status}`));
    error.status = response.status;
    error.code = scrub(data.error?.code || data.code || '');
    throw error;
  }
  return data;
}

const reportDir = resolve(root, '.local', 'm0');
mkdirSync(reportDir, { recursive: true });
const report = { observedAt: new Date().toISOString(), credentialPresent: Boolean(key), chatBase, nativeBase,
  note: 'Real service smoke tests. The tool uses a local fixed outline, not a course search service. ASR input is an official public sample.', results: {} };
const reportPath = resolve(reportDir, 'qwen-smoke.json');
function persist() { writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n'); }
async function run(name, action) {
  if (!selected.has(name)) return;
  const start = Date.now();
  process.stdout.write(`[${name}] starting\n`);
  try {
    if (!key) throw new Error('DASHSCOPE_API_KEY is not configured');
    report.results[name] = { status: 'passed', ...(await action()), elapsedMs: Date.now() - start };
  } catch (error) {
    report.results[name] = { status: 'failed', message: scrub(error.message), code: scrub(error.code || error.cause?.code || ''),
      ...(error.status ? { httpStatus: error.status } : {}), elapsedMs: Date.now() - start };
  }
  persist();
  process.stdout.write(JSON.stringify({ test: name, ...report.results[name] }) + '\n');
}

const baseChat = { model: chatModel, enable_thinking: false, max_tokens: 256, stream: false };
await run('chat', async () => {
  const data = await requestJson(`${chatBase}/chat/completions`, { body: { ...baseChat, messages: [{ role: 'user', content: '这是 CourseTrace 接口连通测试。请仅回答：CourseTrace 接口验证成功。' }] } });
  const content = data.choices?.[0]?.message?.content;
  if (typeof content !== 'string' || !content.includes('CourseTrace')) throw new Error('Missing expected assistant text');
  return { requestedModel: chatModel, returnedModel: data.model, text: scrub(content), usage: data.usage };
});

await run('tools', async () => {
  const messages = [{ role: 'user', content: '请调用 get_course_outline 获取演示课程的大纲，再用一句话告诉我它讲了哪些知识。' }];
  const tool = { type: 'function', function: { name: 'get_course_outline', description: '返回 M0 演示课程大纲。', parameters: { type: 'object', properties: {}, additionalProperties: false } } };
  const first = await requestJson(`${chatBase}/chat/completions`, { body: { ...baseChat, messages, tools: [tool], tool_choice: { type: 'function', function: { name: 'get_course_outline' } } } });
  const assistant = first.choices?.[0]?.message;
  const calls = assistant?.tool_calls;
  if (!calls?.length || calls.length > 2) throw new Error('Expected a bounded tool call');
  messages.push(assistant);
  for (const call of calls) {
    if (call.function?.name !== 'get_course_outline') throw new Error('Unexpected tool');
    const args = JSON.parse(call.function.arguments || '{}');
    if (Object.keys(args).length !== 0) throw new Error('Unexpected tool arguments');
    messages.push({ role: 'tool', tool_call_id: call.id, content: JSON.stringify({ source: 'm0-local-fixture', chapters: ['二叉树定义', '前序遍历', '中序遍历'] }) });
  }
  const second = await requestJson(`${chatBase}/chat/completions`, { body: { ...baseChat, messages, tools: [tool], tool_choice: 'none' } });
  const content = second.choices?.[0]?.message?.content;
  if (typeof content !== 'string' || !content.includes('遍历')) throw new Error('Final response did not use the tool result');
  return { model: chatModel, toolName: 'get_course_outline', toolCalls: calls.length, toolExecution: 'local fixed test data', text: scrub(content) };
});

await run('embedding', async () => {
  const data = await requestJson(`${chatBase}/embeddings`, { body: { model: embeddingModel, dimensions, input: ['前序遍历先访问根节点。', '二叉树的前序遍历顺序是什么？'], encoding_format: 'float' } });
  if (data.data?.length !== 2 || data.data.some(item => item.embedding?.length !== dimensions || item.embedding.some(v => !Number.isFinite(v)))) throw new Error('Invalid embedding count, dimensions or numeric values');
  return { model: embeddingModel, count: data.data.length, dimensions, usage: data.usage };
});

await run('asr', async () => {
  const submitted = await requestJson(`${nativeBase}/services/audio/asr/transcription`, { headers: { 'X-DashScope-Async': 'enable' }, body: { model: asrModel, input: { file_urls: [audioUrl] }, parameters: { channel_id: [0], language_hints: ['zh', 'en'] } } });
  const taskId = submitted.output?.task_id;
  if (!taskId) throw new Error(scrub(submitted.message || 'Missing ASR task_id'));
  // Persist a submitted task immediately so interrupted runs can be diagnosed without resubmission.
  report.asrSubmission = { taskId, model: asrModel, submittedAt: new Date().toISOString() };
  persist();
  process.stdout.write('[asr] task submitted; polling existing task\n');
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    const result = await requestJson(`${nativeBase}/tasks/${encodeURIComponent(taskId)}`, { timeoutMs: 15000 });
    const status = result.output?.task_status;
    if (status === 'SUCCEEDED') {
      const item = result.output?.results?.find(r => r.transcription_url);
      if (!item) throw new Error(scrub(result.output?.results?.[0]?.message || 'No transcription result URL'));
      const transcript = await requestJson(item.transcription_url, { authenticated: false });
      const sentences = transcript.transcripts?.flatMap(t => t.sentences || []) || [];
      if (!sentences.length || sentences.some(s => !Number.isFinite(s.begin_time) || !Number.isFinite(s.end_time) || s.begin_time < 0 || s.end_time <= s.begin_time || !s.text?.trim())) throw new Error('ASR response lacks valid sentence text and start/end timestamps');
      const summary = { model: asrModel, taskId, audioSource: 'official public sample', durationMs: transcript.properties?.original_duration_in_milliseconds,
        sentenceCount: sentences.length, sentences: sentences.map(s => ({ beginMs: s.begin_time, endMs: s.end_time, text: scrub(s.text) })) };
      writeFileSync(resolve(reportDir, 'asr-sample.json'), JSON.stringify(summary, null, 2) + '\n');
      return summary;
    }
    if (['FAILED', 'CANCELED', 'UNKNOWN'].includes(status)) throw new Error(scrub(`${status}: ${result.output?.message || result.message || JSON.stringify(result.output?.results || [])}`));
    await new Promise(resolve => setTimeout(resolve, 3000));
  }
  throw new Error(`ASR task not finished in 180s; query the persisted task ID ${taskId} before any resubmission`);
});

persist();
process.stdout.write(`Report: ${reportPath}\n`);
if (Object.values(report.results).some(result => result.status !== 'passed')) process.exitCode = 1;
