import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, resolve, join } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(root, '.local', 'm1');
mkdirSync(output, { recursive: true });
const modulePath = process.env.COURSETRACE_PLAYWRIGHT_MODULE || join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs');
const { chromium } = await import(pathToFileURL(modulePath).href);
const base = process.env.COURSETRACE_WEB_URL || 'http://127.0.0.1:5173';
const report = { observedAt: new Date().toISOString(), base, checks: [], createdCourses: [], consoleErrors: [] };
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const alice = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
const page = await alice.newPage();
page.on('pageerror', error => report.consoleErrors.push(error.message));

async function check(name, action) {
  try { await action(); report.checks.push({ name, status: 'passed' }); console.log(`PASS ${name}`); }
  catch (error) { report.checks.push({ name, status: 'failed', message: error.message }); throw error; }
}

async function login(targetPage, username) {
  await targetPage.getByLabel('账号', { exact: true }).fill(username);
  await targetPage.getByLabel('密码', { exact: true }).fill('CourseTrace@123');
  await targetPage.getByRole('button', { name: '进入学习空间' }).click();
  await targetPage.getByRole('heading', { name: /^我的课程/ }).waitFor();
  await targetPage.locator('[aria-label="正在加载课程"]').waitFor({ state: 'hidden' });
}

async function ensureCourse(targetPage, title, description) {
  const existingResponse = await targetPage.request.get(`${base}/api/courses?size=50`);
  assert.equal(existingResponse.status(), 200);
  const existing = (await existingResponse.json()).data.items.find(course => course.title === title && course.description === description);
  if (existing) return existing.id;
  await targetPage.getByRole('button', { name: '新建课程', exact: true }).click();
  const dialog = targetPage.getByRole('dialog', { name: '为新知识，留个位置。' });
  await dialog.locator('#course-title').fill(title);
  await dialog.locator('#course-description').fill(description);
  const [createdResponse] = await Promise.all([
    targetPage.waitForResponse(response => response.url().endsWith('/api/courses') && response.request().method() === 'POST'),
    dialog.getByRole('button', { name: '创建课程', exact: true }).click()
  ]);
  assert.equal(createdResponse.status(), 201);
  const course = (await createdResponse.json()).data;
  report.createdCourses.push({ id: course.id, title: course.title });
  await dialog.waitFor({ state: 'hidden' });
  await targetPage.getByRole('button', { name: title, exact: true }).waitFor();
  return course.id;
}

try {
  await check('Login page and wrong-password feedback', async () => {
    await page.goto(base);
    await page.getByRole('heading', { name: '欢迎回来' }).waitFor();
    await page.screenshot({ path: resolve(output, 'login-desktop.png'), fullPage: true });
    await page.getByLabel('密码', { exact: true }).fill('not-the-password');
    await page.getByRole('button', { name: '进入学习空间' }).click();
    await page.getByRole('alert').filter({ hasText: '账号或密码不正确' }).waitFor();
  });

  await check('Session login, rotation, HttpOnly and /me', async () => {
    const previous = (await alice.cookies()).find(cookie => cookie.name === 'COURSETRACE_SESSION')?.value;
    await login(page, 'student');
    const cookie = (await alice.cookies()).find(item => item.name === 'COURSETRACE_SESSION');
    assert.ok(cookie?.httpOnly);
    assert.equal(cookie.sameSite, 'Lax');
    assert.notEqual(cookie.value, previous);
    const me = await alice.request.get(`${base}/api/auth/me`);
    assert.equal(me.status(), 200);
    const data = (await me.json()).data;
    assert.equal(data.username, 'student');
    assert.equal('passwordHash' in data, false);
    assert.equal('password' in data, false);
  });

  let courseId;
  await check('Create courses through Vue and persist after refresh', async () => {
    courseId = await ensureCourse(page, '数据结构与算法', '从线性表到树与图，理解数据的组织方式与算法的思考路径。');
    await ensureCourse(page, '高等数学', '从极限、导数到积分，把抽象概念连成清晰的知识体系。');
    await page.reload();
    await page.getByRole('heading', { name: /^我的课程/ }).waitFor();
    await page.getByLabel('搜索课程名称').fill('数据结构与算法');
    await page.getByLabel('搜索课程名称').press('Enter');
    await page.getByRole('button', { name: '数据结构与算法', exact: true }).waitFor();
  });

  await check('Course detail and keyword search', async () => {
    await page.getByRole('button', { name: '查看课程：数据结构与算法', exact: true }).click();
    await page.getByRole('heading', { name: '数据结构与算法', exact: true }).waitFor();
    await page.getByRole('button', { name: '返回我的课程', exact: true }).click();
    await page.getByLabel('搜索课程名称').waitFor();
    await page.getByLabel('搜索课程名称').fill('数据结构');
    await Promise.all([
      page.waitForResponse(response => response.url().includes('/api/courses?') && response.status() === 200),
      page.getByLabel('搜索课程名称').press('Enter')
    ]);
    await page.locator('[aria-label="正在加载课程"]').waitFor({ state: 'hidden' });
    assert.ok(await page.getByTestId('course-card').count() >= 1);
    await page.getByRole('button', { name: '数据结构与算法', exact: true }).waitFor();
    await page.getByLabel('搜索课程名称').fill('M1不存在的课程ABCXYZ');
    await page.getByLabel('搜索课程名称').press('Enter');
    await page.getByRole('heading', { name: '还没有找到这门课程' }).waitFor();
    await page.getByRole('button', { name: '查看全部课程', exact: true }).click();
    await page.locator('[aria-label="正在加载课程"]').waitFor({state:'hidden'});
    assert.ok(await page.getByTestId('course-card').count() > 0);
  });

  await check('CSRF rejects an authenticated write without token', async () => {
    const response = await alice.request.post(`${base}/api/courses`, { data: { title: 'Must not be created' } });
    assert.equal(response.status(), 403);
    assert.equal((await response.json()).code, 'CSRF_INVALID');
  });

  await check('Independent browser accounts cannot read another course', async () => {
    const bob = await browser.newContext({ locale: 'zh-CN' });
    try {
      const bobPage = await bob.newPage();
      await bobPage.goto(base);
      await bobPage.getByRole('heading', { name: '欢迎回来' }).waitFor();
      await login(bobPage, 'student2');
      const list = await bob.request.get(`${base}/api/courses?size=50`);
      assert.ok(!(await list.json()).data.items.some(course => course.id === courseId));
      const forbidden = await bob.request.get(`${base}/api/courses/${courseId}`);
      assert.equal(forbidden.status(), 404);
      assert.equal((await forbidden.json()).code, 'COURSE_NOT_FOUND');
    } finally { await bob.close(); }
  });

  await check('Desktop and mobile render without horizontal overflow', async () => {
    await page.screenshot({ path: resolve(output, 'courses-desktop.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: resolve(output, 'courses-mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.ok(await page.locator('.mobile-logout').isVisible());
  });

  await check('Logout invalidates the session and refresh returns to login', async () => {
    await page.locator('.mobile-logout').click();
    await page.getByRole('heading', { name: '欢迎回来' }).waitFor();
    assert.equal((await alice.request.get(`${base}/api/auth/me`)).status(), 401);
    assert.equal((await alice.request.get(`${base}/api/courses`)).status(), 401);
    await page.reload();
    await page.getByRole('heading', { name: '欢迎回来' }).waitFor();
  });
  assert.deepEqual(report.consoleErrors, []);
} catch (error) {
  report.failure = error.message;
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  await browser.close();
  writeFileSync(resolve(output, 'browser-verification.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
}
