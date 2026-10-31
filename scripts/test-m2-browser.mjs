import assert from "node:assert/strict";
import { readFileSync, mkdirSync, writeFileSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { dirname, resolve, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = join(root, ".local", "m2");
mkdirSync(output, { recursive: true });
const samplePath = join(output, "sample-course.mp4"),
  silentPath = join(output, "sample-silent.mp4");
if (!existsSync(samplePath) || !existsSync(silentPath))
  throw new Error("Run scripts/prepare-m2-samples.ps1 first.");
const source = readFileSync(samplePath);
const { chromium } = await import(
  pathToFileURL(
    process.env.COURSETRACE_PLAYWRIGHT_MODULE ||
      join(
        process.env.USERPROFILE,
        ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs",
      ),
  ).href
);
const base = process.env.COURSETRACE_WEB_URL || "http://127.0.0.1:5173";
const browser = await chromium.launch({ channel: "msedge", headless: true });
const report = {
  observedAt: new Date().toISOString(),
  base,
  checks: [],
  createdVideos: [],
  pageErrors: [],
  rangeResponses: [],
};
const alice = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  locale: "zh-CN",
});
const page = await alice.newPage();
page.on("pageerror", (error) => report.pageErrors.push(error.message));
page.on("response", (response) => {
  if (response.url().includes("/content") && response.status() === 206)
    report.rangeResponses.push(response.status());
});

async function check(name, action) {
  try {
    await action();
    report.checks.push({ name, status: "passed" });
    console.log("PASS " + name);
  } catch (error) {
    report.checks.push({ name, status: "failed", message: error.message });
    throw error;
  }
}
async function login(target, username) {
  await target.goto(base);
  await target.getByRole("heading", { name: "欢迎回来" }).waitFor();
  await target.getByLabel("账号", { exact: true }).fill(username);
  await target.getByLabel("密码", { exact: true }).fill("CourseTrace@123");
  await target.getByRole("button", { name: "进入学习空间" }).click();
  await target.getByRole("heading", { name: /^我的课程/ }).waitFor();
  await target
    .locator('[aria-label="正在加载课程"]')
    .waitFor({ state: "hidden" });
}
async function csrf(context) {
  const res = await context.request.get(base + "/api/auth/csrf");
  return (await res.json()).data;
}
async function postFile(context, path, file, title, withCsrf = true) {
  const token = withCsrf ? await csrf(context) : null;
  return context.request.post(base + path, {
    headers: token ? { [token.headerName]: token.token } : {},
    multipart: { file, ...(title ? { title } : {}) },
  });
}
let course, audio, silent;
async function ensureVideo(title, path) {
  const list = (
    await (
      await alice.request.get(
        base + "/api/courses/" + course.id + "/videos?size=50",
      )
    ).json()
  ).data;
  const existing = list.items.find((item) => item.title === title);
  if (existing) {
    await page
      .getByRole("button", { name: "播放视频：" + title, exact: true })
      .click();
    await page.waitForFunction(
      (id) =>
        document.querySelector("video")?.getAttribute("src") ===
        "/api/videos/" + id + "/content",
      existing.id,
    );
    return existing;
  }
  await page.getByRole("button",{name:"添加课程视频",exact:true}).click();
  await page.getByLabel("选择课程视频", { exact: true }).setInputFiles(path);
  await page.getByLabel("视频标题", { exact: true }).fill(title);
  const [response] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith("/api/courses/" + course.id + "/videos") &&
        r.request().method() === "POST",
    ),
    page.getByRole("button", { name: "上传视频", exact: true }).click(),
  ]);
  if (response.status() !== 201)
    throw new Error(
      "Upload HTTP " + response.status() + ": " + (await response.text()),
    );
  const video = (await response.json()).data;
  report.createdVideos.push({ id: video.id, title: video.title });
  await page
    .getByRole("button", { name: "播放视频：" + title, exact: true })
    .waitFor();
  await page.waitForFunction(
    (id) =>
      document.querySelector("video")?.getAttribute("src") ===
      "/api/videos/" + id + "/content",
    video.id,
  );
  return video;
}
try {
  await check("M1 login and course navigation remain usable", async () => {
    await login(page, "student");
    const list = (
      await (await alice.request.get(base + "/api/courses?size=50")).json()
    ).data.items;
    course = list.find((item) => item.title === "数据结构与算法") || list[0];
    assert.ok(course, "A course from M1 is required");
    report.courseId = course.id;
    await page.getByLabel("搜索课程名称").fill(course.title);
    await page.getByLabel("搜索课程名称").press("Enter");
    await page.getByRole("button", { name: course.title, exact: true }).click();
    await page.getByRole("button",{name:"添加课程视频",exact:true}).click();
    await page
      .getByRole("heading", { name: "添加课程视频", exact: true })
      .waitFor({ state: "attached" });
    await page
      .getByRole("heading", { name: course.title, exact: true })
      .waitFor({state:"attached"});
    await page.getByRole("button",{name:"关闭视频管理"}).click();
  });
  await check("Actual MP4 upload writes safe metadata through UI", async () => {
    audio = await ensureVideo("播放验证样片（含音轨）", samplePath);
    assert.equal(audio.sizeBytes, source.length);
    assert.ok(audio.durationMs >= 19900 && audio.durationMs <= 20100);
    assert.equal(audio.videoCodec, "h264");
    assert.equal(audio.audioCodec, "aac");
    assert.ok(!("objectKey" in audio) && !("bucketName" in audio));
    report.audioVideoId = audio.id;
  });
  await check("Browser decodes and advances the video", async () => {
    await page.waitForFunction(
      () => document.querySelector("video")?.readyState >= 2,
    );
    await page.locator("video").evaluate(async (video) => {
      video.muted = true;
      await video.play();
    });
    await page.waitForFunction(
      () => document.querySelector("video")?.currentTime > 0.5,
    );
    report.browserDurationSeconds = await page
      .locator("video")
      .evaluate((video) => video.duration);
  });
  await check("Seeking resumes playback from a later time", async () => {
    await page.locator("video").evaluate((video) => {
      video.currentTime = 16;
    });
    await page.waitForFunction(() => {
      const v = document.querySelector("video");
      return v && !v.seeking && v.currentTime > 16.3;
    });
    await page.locator("video").evaluate((video) => video.pause());
  });
  await check(
    "MinIO bytes match the source; Range and HEAD are correct",
    async () => {
      const url = base + audio.contentUrl;
      const full = await alice.request.get(url);
      assert.equal(full.status(), 200);
      assert.equal(
        createHash("sha256")
          .update(await full.body())
          .digest("hex"),
        createHash("sha256").update(source).digest("hex"),
      );
      for (const [range, start, end] of [
        ["bytes=0-63", 0, 64],
        ["bytes=-64", source.length - 64, source.length],
        [
          "bytes=" + (source.length - 30) + "-",
          source.length - 30,
          source.length,
        ],
      ]) {
        const response = await alice.request.get(url, {
          headers: { Range: range },
        });
        assert.equal(response.status(), 206);
        assert.deepEqual(await response.body(), source.subarray(start, end));
        assert.equal(
          response.headers()["content-range"],
          "bytes " + start + "-" + (end - 1) + "/" + source.length,
        );
      }
      const head = await alice.request.head(url);
      assert.equal(head.status(), 200);
      assert.equal((await head.body()).length, 0);
      assert.equal(head.headers()["content-length"], String(source.length));
      const invalid = await alice.request.get(url, {
        headers: { Range: "bytes=" + source.length + "-" },
      });
      assert.equal(invalid.status(), 416);
      assert.equal(
        invalid.headers()["content-range"],
        "bytes */" + source.length,
      );
      const direct = await browser.newContext();
      assert.equal(
        (
          await direct.request.get("http://127.0.0.1:9000/coursetrace-videos/")
        ).status(),
        403,
      );
      await direct.close();
    },
  );
  await check(
    "Silent MP4 uploads and switching replaces the old player",
    async () => {
      silent = await ensureVideo("播放验证样片（无音轨）", silentPath);
      assert.equal(silent.audioCodec ?? null, null);
      await page.waitForFunction(
        () =>
          Math.abs((document.querySelector("video")?.duration || 0) - 12) < 0.2,
      );
      await page
        .getByRole("button", { name: "播放视频：" + audio.title, exact: true })
        .click();
      await page.waitForFunction(
        () =>
          Math.abs((document.querySelector("video")?.duration || 0) - 20) < 0.2,
      );
      assert.equal(await page.locator("video").count(), 1);
    },
  );
  await check(
    "Deep URL, session and selected video survive reload",
    async () => {
      assert.ok(page.url().includes("video=" + audio.id));
      await page.reload();
      await page
        .getByRole("button", { name: "添加课程视频", exact: true })
        .waitFor();
      await page.waitForFunction(
        (id) =>
          document.querySelector("video")?.getAttribute("src") ===
          "/api/videos/" + id + "/content",
        audio.id,
      );
      await page.waitForFunction(
        () => document.querySelector("video")?.readyState >= 2,
      );
    },
  );
  await check(
    "Anonymous requests cannot list, upload or read video",
    async () => {
      const anonymous = await browser.newContext();
      for (const path of [
        "/api/courses/" + course.id + "/videos",
        "/api/videos/" + audio.id,
        audio.contentUrl,
      ]) {
        assert.equal((await anonymous.request.get(base + path)).status(), 401);
      }
      assert.equal(
        (await anonymous.request.head(base + audio.contentUrl)).status(),
        401,
      );
      const response = await postFile(
        anonymous,
        "/api/courses/" + course.id + "/videos",
        { name: "sample.mp4", mimeType: "video/mp4", buffer: source },
      );
      assert.equal(response.status(), 401);
      await anonymous.close();
    },
  );
  await check(
    "student2 cannot upload into or read student video, including Range/HEAD",
    async () => {
      const bob = await browser.newContext();
      const bobPage = await bob.newPage();
      await login(bobPage, "student2");
      for (const path of [
        "/api/courses/" + course.id + "/videos",
        "/api/videos/" + audio.id,
        audio.contentUrl,
      ]) {
        assert.equal(
          (
            await bob.request.get(base + path, {
              headers: { Range: "bytes=0-10" },
            })
          ).status(),
          404,
        );
      }
      assert.equal(
        (await bob.request.head(base + audio.contentUrl)).status(),
        404,
      );
      const response = await postFile(
        bob,
        "/api/courses/" + course.id + "/videos",
        { name: "sample.mp4", mimeType: "video/mp4", buffer: source },
      );
      assert.equal(response.status(), 404);
      await bob.close();
    },
  );
  await check(
    "CSRF, empty, forged and unsupported files fail without success rows",
    async () => {
      const path = "/api/courses/" + course.id + "/videos";
      const before = (await (await alice.request.get(base + path)).json()).data
        .total;
      const noCsrf = await postFile(
        alice,
        path,
        { name: "sample.mp4", mimeType: "video/mp4", buffer: source },
        undefined,
        false,
      );
      assert.equal(noCsrf.status(), 403);
      const cases = [
        [
          { name: "empty.mp4", mimeType: "video/mp4", buffer: Buffer.alloc(0) },
          400,
        ],
        [
          {
            name: "fake.mp4",
            mimeType: "video/mp4",
            buffer: Buffer.from("This is not an MP4 file"),
          },
          415,
        ],
        [{ name: "bad.txt", mimeType: "video/mp4", buffer: source }, 415],
        [
          {
            name: "unsupported.mp4",
            mimeType: "video/mp4",
            buffer: readFileSync(join(output, "unsupported-codec.mp4")),
          },
          415,
        ],
      ];
      for (const [file, status] of cases) {
        const response = await postFile(alice, path, file);
        assert.equal(response.status(), status, await response.text());
      }
      assert.equal(
        (await (await alice.request.get(base + path)).json()).data.total,
        before,
      );
      await page.getByRole("button",{name:"添加课程视频",exact:true}).click();
      await page
        .getByLabel("选择课程视频", { exact: true })
        .setInputFiles({
          name: "bad.txt",
          mimeType: "text/plain",
          buffer: Buffer.from("not video"),
        });
      await page
        .getByRole("alert")
        .filter({ hasText: "请选择 MP4 视频文件" })
        .waitFor();
    },
  );
  await check(
    "Desktop/mobile layout and loaded player have no horizontal overflow",
    async () => {
      await page.reload();
      await page.waitForFunction(
        () => document.querySelector("video")?.readyState >= 3,
      );
      await page.locator("video").evaluate((video) => {
        video.currentTime = 5;
      });
      await page.waitForFunction(() => {
        const video = document.querySelector("video");
        return video && !video.seeking && video.readyState >= 3;
      });
      await page.screenshot({
        path: join(output, "courses-desktop.png"),
        fullPage: true,
      });
      await page.setViewportSize({ width: 390, height: 844 });
      await page.screenshot({
        path: join(output, "courses-mobile.png"),
        fullPage: true,
      });
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      );
      await page.setViewportSize({ width: 1440, height: 1000 });
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      );
    },
  );
  await check(
    "Logout unmounts player and media API rejects the old session",
    async () => {
      await page.locator(".sidebar .logout-button").click();
      await page.getByRole("heading", { name: "欢迎回来" }).waitFor();
      assert.equal(await page.locator("video").count(), 0);
      assert.equal(
        (await alice.request.get(base + audio.contentUrl)).status(),
        401,
      );
    },
  );
  assert.equal(report.pageErrors.length, 0, report.pageErrors.join("\n"));
  assert.ok(
    report.rangeResponses.length > 0,
    "Browser must observe actual 206 responses",
  );
  report.status = "passed";
} catch (error) {
  report.status = "failed";
  report.error = error.stack;
  await page
    .screenshot({ path: join(output, "failure.png"), fullPage: true })
    .catch(() => {});
  console.error(error);
  process.exitCode = 1;
} finally {
  writeFileSync(
    join(output, "browser-verification.json"),
    JSON.stringify(report, null, 2),
  );
  await browser.close();
  console.log(
    "M2 browser report: " +
      report.status +
      "; " +
      report.checks.filter((c) => c.status === "passed").length +
      " groups passed.",
  );
}
