import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
const { chromium } = await import(
  pathToFileURL(
    join(
      process.env.USERPROFILE,
      ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs",
    ),
  ).href
);
const browser = await chromium.launch({ channel: "msedge", headless: true });
const context = await browser.newContext();
const page = await context.newPage();
const base = "http://127.0.0.1:5173";
const report = {
  observedAt: new Date().toISOString(),
  configuredMaxBytes: 1024,
};
try {
  await page.goto(base);
  await page.getByLabel("账号", { exact: true }).fill("student");
  await page.getByLabel("密码", { exact: true }).fill("CourseTrace@123");
  await page.getByRole("button", { name: "进入学习空间" }).click();
  await page.getByRole("heading", { name: /^我的课程/ }).waitFor();
  await page
    .locator('[aria-label="正在加载课程"]')
    .waitFor({ state: "hidden" });
  const list = (await (await context.request.get(base + "/api/courses")).json())
    .data.items;
  const course =
    list.find((item) => item.title === "数据结构与算法") || list[0];
  await page.getByRole("button", { name: course.title, exact: true }).click();
  await page.getByRole("heading", { name: "添加课程视频" }).waitFor();
  const policy = (
    await (await context.request.get(base + "/api/video-upload-policy")).json()
  ).data;
  assert.equal(policy.maxBytes, 1024);
  await page
    .getByLabel("选择课程视频", { exact: true })
    .setInputFiles(resolve(".local/m2/sample-silent.mp4"));
  await page
    .getByRole("alert")
    .filter({ hasText: "文件超过 1 KB 上传上限" })
    .waitFor();
  const token = (
    await (await context.request.get(base + "/api/auth/csrf")).json()
  ).data;
  const path = base + "/api/courses/" + course.id + "/videos";
  const before = (await (await context.request.get(path)).json()).data.total;
  const upload = await context.request.post(path, {
    headers: { [token.headerName]: token.token },
    multipart: {
      file: {
        name: "oversized.mp4",
        mimeType: "video/mp4",
        buffer: readFileSync(resolve(".local/m2/sample-silent.mp4")),
      },
    },
  });
  assert.equal(upload.status(), 413, await upload.text());
  assert.equal((await upload.json()).code, "FILE_TOO_LARGE");
  assert.equal(
    (await (await context.request.get(path)).json()).data.total,
    before,
  );
  Object.assign(report, {
    status: "passed",
    frontendUsesServerPolicy: true,
    actualMultipartResponse: 413,
    successRowsUnchanged: true,
  });
  console.log(JSON.stringify(report));
} catch (error) {
  report.status = "failed";
  report.error = error.message;
  throw error;
} finally {
  writeFileSync(
    resolve(".local/m2/upload-limit.json"),
    JSON.stringify(report, null, 2),
  );
  await browser.close();
}
