import assert from "node:assert/strict";
import {readFileSync,writeFileSync} from "node:fs";
import {resolve,dirname,join} from "node:path";
import {fileURLToPath,pathToFileURL} from "node:url";
import {execFileSync} from "node:child_process";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."), output=join(root,".local/m3");
const fixture=JSON.parse(readFileSync(join(output,"browser-verification.json"),"utf8")).fixtures;
const mode=process.argv[2], file=join(output,"interruption-verification.json");
const {chromium}=await import(pathToFileURL(join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs")).href);
const browser=await chromium.launch({channel:"msedge",headless:true});
const context=await browser.newContext();
const base="http://127.0.0.1:5173";
function project(script,...args) {
  return execFileSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-ExecutionPolicy","Bypass","-File",join(root,"scripts",script),...args],{cwd:root,encoding:"utf8",windowsHide:true,timeout:120000}).trim();
}
function sql(query) {
  return project("docker.ps1","exec","-T","mysql","sh","-c","echo "+Buffer.from(query).toString("base64")+" | base64 -d | MYSQL_PWD=$MYSQL_PASSWORD mysql -u$MYSQL_USER $MYSQL_DATABASE -NB");
}
async function token() { return (await (await context.request.get(base+"/api/auth/csrf")).json()).data; }
async function post(path,data) {
  const t=await token(); return context.request.post(base+path,{headers:{[t.headerName]:t.token},...(data?{data}:{})});
}
async function login() {
  const t=await token();
  const r=await context.request.post(base+"/api/auth/login",{headers:{[t.headerName]:t.token},form:{username:"student",password:"CourseTrace@123"}});
  assert.equal(r.status(),200);
}
async function task(id) { const r=await context.request.get(base+"/api/processing-tasks/"+id); assert.equal(r.status(),200); return (await r.json()).data; }
async function waitFor(action) { for(let i=0;i<160;i++){const value=await action();if(value)return value;await new Promise(r=>setTimeout(r,250));} throw new Error("Expected task state was not observed"); }
let paused=false;
try {
  await login();
  if(mode==="prepare") {
    project("docker.ps1","pause","minio"); paused=true;
    const response=await post("/api/videos/"+fixture.interrupted.id+"/processing-tasks");
    assert.equal(response.status(),202); const submitted=(await response.json()).data;
    await waitFor(async()=>(await task(submitted.id)).status==="RUNNING");
    const before=await task(submitted.id);
    writeFileSync(file,JSON.stringify({status:"prepared",observedAt:new Date().toISOString(),taskId:submitted.id,videoId:fixture.interrupted.id,courseId:fixture.course.id,before,realProcessInterruption:true},null,2));
    project("stop-dev.ps1");
    console.log("PASS actual development process stopped while task was RUNNING");
  } else if(mode==="resume") {
    const report=JSON.parse(readFileSync(file,"utf8"));
    const after=await task(report.taskId);
    assert.equal(after.status,"RUNNING");
    assert.equal(after.runNo,1); assert.equal(after.canRetry,false);
    report.afterRestart=after;
    // Only the isolated fixture is backdated; the process interruption and persistence checks above are real.
    sql("UPDATE processing_task SET started_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 11 MINUTE) WHERE id="+report.taskId+" AND status='RUNNING' AND run_no=1");
    report.staleThresholdTest="Controlled backdating of this test task by 11 minutes; no claim of waiting in real time";
    const page=await context.newPage();
    await page.goto(base+"/#/courses/"+report.courseId+"?video="+report.videoId);
    await page.getByRole("button",{name:"重新检查",exact:true}).waitFor();
    await page.getByRole("button",{name:"重新检查",exact:true}).click();
    await page.locator('.processing-status[data-status="SUCCEEDED"]').waitFor({timeout:45000});
    const done=await task(report.taskId); assert.equal(done.runNo,2); assert.equal(done.status,"SUCCEEDED");
    assert.equal(sql("SELECT COUNT(*) FROM task_step WHERE task_id="+report.taskId),"2");
    report.recovered=done; report.status="passed"; report.finishedAt=new Date().toISOString();
    writeFileSync(file,JSON.stringify(report,null,2));
    console.log("PASS task survived real process interruption and restart; manual recovery succeeded with a new run");
  } else throw new Error("Use prepare, restart with IDEA Start, then resume");
} finally {
  if(paused) project("docker.ps1","unpause","minio");
  await browser.close();
}
