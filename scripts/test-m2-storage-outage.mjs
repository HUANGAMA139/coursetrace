import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
const { request } = await import(
  pathToFileURL(
    join(
      process.env.USERPROFILE,
      ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs",
    ),
  ).href
);
const api = await request.newContext({ baseURL: "http://127.0.0.1:8080" });
const report = { observedAt: new Date().toISOString() };
try {
  let token = (await (await api.get("/api/auth/csrf")).json()).data;
  const login = await api.post("/api/auth/login", {
    headers: { [token.headerName]: token.token },
    form: { username: "student", password: "CourseTrace@123" },
  });
  assert.equal(login.status(), 200);
  token = (await (await api.get("/api/auth/csrf")).json()).data;
  const course = (await (await api.get("/api/courses")).json()).data.items[0];
  const path = "/api/courses/" + course.id + "/videos";
  const before = (await (await api.get(path)).json()).data.total;
  const upload = await api.post(path, {
    headers: { [token.headerName]: token.token },
    multipart: {
      file: {
        name: "unavailable.mp4",
        mimeType: "video/mp4",
        buffer: readFileSync(resolve(".local/m2/sample-course.mp4")),
      },
    },
  });
  assert.equal(upload.status(), 503, await upload.text());
  assert.equal((await upload.json()).code, "STORAGE_UNAVAILABLE");
  const after = (await (await api.get(path)).json()).data.total;
  assert.equal(after, before);
  Object.assign(report, {
    status: "passed",
    loginDuringStorageOutage: true,
    uploadStatus: 503,
    successfulRowsBefore: before,
    successfulRowsAfter: after,
  });
  console.log(JSON.stringify(report));
} catch (error) {
  report.status = "failed";
  report.error = error.message;
  throw error;
} finally {
  writeFileSync(
    resolve(".local/m2/storage-outage.json"),
    JSON.stringify(report, null, 2),
  );
  await api.dispose();
}
