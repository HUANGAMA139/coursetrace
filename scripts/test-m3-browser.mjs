import assert from "node:assert/strict";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { resolve, dirname, join } from "node:path";
import { pathToFileURL, fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."), output=join(root,".local/m3");
mkdirSync(output,{recursive:true});
const { chromium }=await import(pathToFileURL(process.env.COURSETRACE_PLAYWRIGHT_MODULE || join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs")).href);
const base=process.env.COURSETRACE_WEB_URL || "http://127.0.0.1:5173";
const browser=await chromium.launch({channel:"msedge",headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1080},locale:"zh-CN"});
const page=await context.newPage();
const report={observedAt:new Date().toISOString(),checks:[],pageErrors:[],states:[],actualServices:["MySQL","Kafka","MinIO"],fixtures:{}};
page.on("pageerror",error=>report.pageErrors.push(error.message));
let kafkaStopped=false,minioStopped=false;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function docker(...args) {
  return execFileSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-ExecutionPolicy","Bypass","-File",join(root,"scripts/docker.ps1"),...args],{cwd:root,encoding:"utf8",windowsHide:true,timeout:120000,stdio:["pipe","pipe","pipe"]}).trim();
}
function sql(query) {
  const encoded=Buffer.from(query).toString("base64");
  return docker("exec","-T","mysql","sh","-c","echo "+encoded+" | base64 -d | MYSQL_PWD=$MYSQL_PASSWORD mysql -u$MYSQL_USER $MYSQL_DATABASE -NB");
}
function publish(payload,topic="course.processing") {
  const encoded=Buffer.from(payload+"\n").toString("base64");
  docker("exec","-T","kafka","sh","-c","echo "+encoded+" | base64 -d | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "+topic);
}
function save() { writeFileSync(join(output,"browser-verification.json"),JSON.stringify(report,null,2)); }
async function check(name,action) {
  try { await action(); report.checks.push({name,status:"passed"}); console.log("PASS "+name); save(); }
  catch(error) { report.checks.push({name,status:"failed",message:error.message}); save(); throw error; }
}
async function waitFor(action,timeout=45000) {
  const deadline=Date.now()+timeout;
  while(Date.now()<deadline) { const value=await action(); if(value) return value; await pause(250); }
  throw new Error("Timed out waiting for expected state");
}
async function token(target=context) { return (await (await target.request.get(base+"/api/auth/csrf")).json()).data; }
async function post(path,data,target=context) {
  const csrf=await token(target);
  return target.request.post(base+path,{headers:{[csrf.headerName]:csrf.token},...(data?{data}:{})});
}
async function task(id) {
  const response=await context.request.get(base+"/api/processing-tasks/"+id);
  assert.equal(response.status(),200); return (await response.json()).data;
}
async function login(target=page,name="student") {
  await target.goto(base);
  await target.getByLabel("账号",{exact:true}).fill(name);
  await target.getByLabel("密码",{exact:true}).fill("CourseTrace@123");
  await target.getByRole("button",{name:"进入学习空间"}).click();
  await target.getByRole("heading",{name:/^我的课程/}).waitFor();
}
async function select(video) {
  if(await page.locator("#course-upload-tools[open]").count())await page.getByRole("button",{name:"关闭视频管理",exact:true}).click();
  await page.goto(base+"/#/courses/"+report.fixtures.course.id+"?video="+video.id);
  await page.getByRole("button",{name:"添加课程视频",exact:true}).click();
  await page.getByRole("heading",{name:"视频资源检查",exact:true}).waitFor();
  await waitFor(()=>page.getByRole("button",{name:"刷新状态",exact:true}).isEnabled());
}
async function upload(title) {
  const csrf=await token();
  const response=await context.request.post(base+"/api/courses/"+report.fixtures.course.id+"/videos",{
    headers:{[csrf.headerName]:csrf.token},
    multipart:{title,file:{name:"sample.mp4",mimeType:"video/mp4",buffer:readFileSync(join(root,".local/m2/sample-silent.mp4"))}},
  });
  assert.equal(response.status(),201); return (await response.json()).data;
}

try {
  await login();
  await check("Create isolated real-video fixtures",async()=>{
    const response=await post("/api/courses",{title:"异步任务验证课程 "+new Date().toISOString().slice(0,19),description:"M3 自动验收生成样片"});
    assert.equal(response.status(),201); report.fixtures.course=(await response.json()).data;
    for(const [key,title] of Object.entries({normal:"正常检查样片",concurrent:"并发提交样片",queued:"消息恢复样片",storage:"存储恢复样片",interrupted:"中断恢复样片"}))
      report.fixtures[key]=await upload(title);
  });
  await check("UI submit, real Kafka consumption and persisted MinIO result",async()=>{
    await select(report.fixtures.normal);
    await page.getByRole("button",{name:"检查视频",exact:true}).click();
    await page.locator('.processing-status[data-status="SUCCEEDED"]').waitFor({timeout:45000});
    const snapshot=(await (await context.request.get(base+"/api/courses/"+report.fixtures.course.id+"/processing-tasks?videoIds="+report.fixtures.normal.id)).json()).data[0];
    report.fixtures.normalTask=snapshot;
    assert.equal(snapshot.steps[0].result.actualSizeBytes,report.fixtures.normal.sizeBytes);
    assert.equal(snapshot.steps[0].attemptCount,1);
    await waitFor(()=>sql("SELECT status FROM outbox_event WHERE task_id="+snapshot.id)==="SENT");
    assert.equal(sql("SELECT COUNT(*) FROM task_step WHERE task_id="+snapshot.id+" AND status='SUCCEEDED'"),"1");
  });
  await check("Concurrent HTTP submissions create one task and one Outbox",async()=>{
    const csrf=await token();
    const responses=await Promise.all(Array.from({length:5},()=>context.request.post(base+"/api/videos/"+report.fixtures.concurrent.id+"/processing-tasks",{headers:{[csrf.headerName]:csrf.token}})));
    assert.equal(responses.filter(r=>r.status()===202).length,1);
    assert.equal(responses.filter(r=>r.status()===200).length,4);
    const ids=await Promise.all(responses.map(async r=>(await r.json()).data.id));
    assert.equal(new Set(ids).size,1);
    await waitFor(async()=>(await task(ids[0])).status==="SUCCEEDED");
    assert.equal(sql("SELECT COUNT(*) FROM outbox_event WHERE task_id="+ids[0]),"1");
    report.fixtures.concurrentTaskId=ids[0];
  });
  await check("Kafka outage keeps HTTP usable and Outbox pending; restore completes task",async()=>{
    await select(report.fixtures.queued);
    docker("stop","-t","2","kafka"); kafkaStopped=true;
    const started=Date.now();
    const response=await post("/api/videos/"+report.fixtures.queued.id+"/processing-tasks");
    report.submitDuringKafkaOutageMs=Date.now()-started;
    assert.equal(response.status(),202);
    const queued=(await response.json()).data; report.fixtures.queuedTaskId=queued.id;
    await page.getByRole("button",{name:"刷新状态",exact:true}).click();
    await page.locator('.processing-status[data-status="PENDING"]').waitFor();
    assert.equal((await context.request.get(base+"/api/auth/me")).status(),200);
    await waitFor(()=>Number(sql("SELECT publish_attempts FROM outbox_event WHERE task_id="+queued.id))>=1,30000);
    assert.equal(sql("SELECT status FROM outbox_event WHERE task_id="+queued.id),"PENDING");
    let inFlight=0,maxInFlight=0;
    await page.route("**/processing-tasks?videoIds=*",async route=>{
      inFlight++; maxInFlight=Math.max(maxInFlight,inFlight);
      try { await pause(2800); await route.continue(); } finally { inFlight--; }
    });
    await pause(7500);
    await page.unrouteAll({behavior:"wait"});
    assert.equal(maxInFlight,1);
    docker("up","-d","--wait","kafka"); kafkaStopped=false;
    await waitFor(async()=>(await task(queued.id)).status==="SUCCEEDED",90000);
    await page.getByRole("button",{name:"刷新状态",exact:true}).click();
    await page.locator('.processing-status[data-status="SUCCEEDED"]').waitFor();
    const faultPage=await context.newPage();
    try {
      await faultPage.goto(base+"/#/courses/"+report.fixtures.course.id+"?video="+report.fixtures.queued.id);
      await faultPage.getByRole("button",{name:"添加课程视频",exact:true}).click();
      await faultPage.locator('.processing-status[data-status="SUCCEEDED"]').waitFor();
      await faultPage.route("**/processing-tasks?videoIds=*", route=>route.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"状态查询故障验收"})}));
      await faultPage.getByRole("button",{name:"刷新状态",exact:true}).click();
      await faultPage.getByText(/获取状态失败/).waitFor();
      assert.equal((await task(queued.id)).status,"SUCCEEDED");
    } finally { await faultPage.close(); }
  });
  await check("MinIO outage triggers two retry topics, DLT and manual run recovery",async()=>{
    await select(report.fixtures.storage);
    docker("stop","-t","2","minio"); minioStopped=true;
    const response=await post("/api/videos/"+report.fixtures.storage.id+"/processing-tasks");
    assert.equal(response.status(),202); const first=(await response.json()).data;
    report.fixtures.storageTaskId=first.id;
    await page.getByRole("button",{name:"刷新状态",exact:true}).click();
    await waitFor(async()=>{
      const current=await task(first.id);
      report.states.push({status:current.status,attempts:current.steps[0].attemptCount});
      return current.status==="FAILED";
    },60000);
    const failed=await task(first.id);
    assert.equal(failed.steps[0].attemptCount,3);
    assert.equal(failed.errorCode,"STORAGE_UNAVAILABLE");
    assert.ok(report.states.some(s=>s.status==="RETRY_WAIT"));
    assert.equal(sql("SELECT status FROM task_step WHERE task_id="+first.id+" AND run_no=1"),"FAILED");
    await page.getByRole("button",{name:"重新检查",exact:true}).waitFor({timeout:15000});
    docker("up","-d","--wait","minio"); minioStopped=false;
    await waitFor(async()=>{ try { return (await fetch("http://127.0.0.1:9000/minio/health/ready")).ok; } catch{return false;} });
    await page.getByRole("button",{name:"重新检查",exact:true}).click();
    await page.locator('.processing-status[data-status="SUCCEEDED"]').waitFor({timeout:45000});
    assert.equal((await task(first.id)).runNo,2);
    assert.equal(sql("SELECT COUNT(*) FROM task_step WHERE task_id="+first.id),"2");
  });
  await check("Replay successful and stale messages; invalid messages cannot mutate task",async()=>{
    const id=report.fixtures.normalTask.id;
    const before=await task(id);
    const payload=sql("SELECT payload_json FROM outbox_event WHERE task_id="+id);
    publish(payload); publish(payload);
    const old=sql("SELECT payload_json FROM outbox_event WHERE task_id="+report.fixtures.storageTaskId+" AND run_no=1");
    publish(old); publish(old,"course.processing.dlt");
    publish("{bad");
    const unrelated={...JSON.parse(payload),eventId:"00000000-0000-0000-0000-000000000000"};
    publish(JSON.stringify(unrelated));
    await pause(2500);
    const after=await task(id);
    assert.equal(after.status,"SUCCEEDED");
    assert.equal(after.steps[0].attemptCount,1);
    assert.equal(after.steps[0].result.checkedAt,before.steps[0].result.checkedAt);
    const recovered=await task(report.fixtures.storageTaskId);
    assert.equal(recovered.runNo,2); assert.equal(recovered.status,"SUCCEEDED");
  });
  await check("Real Session/CSRF and cross-account task isolation",async()=>{
    const bob=await browser.newContext();
    const bp=await bob.newPage(); await login(bp,"student2");
    const id=report.fixtures.normalTask.id, vid=report.fixtures.normal.id;
    assert.equal((await bob.request.get(base+"/api/processing-tasks/"+id)).status(),404);
    assert.equal((await post("/api/videos/"+vid+"/processing-tasks",null,bob)).status(),404);
    assert.equal((await post("/api/processing-tasks/"+id+"/retry",{expectedRunNo:1},bob)).status(),404);
    assert.equal((await bob.request.get(base+"/api/courses/"+report.fixtures.course.id+"/processing-tasks?videoIds="+vid)).status(),404);
    assert.equal((await context.request.post(base+"/api/videos/"+vid+"/processing-tasks")).status(),403);
    assert.equal((await context.request.post(base+"/api/processing-tasks/"+id+"/retry",{data:{expectedRunNo:1}})).status(),403);
    const anon=await browser.newContext();
    assert.equal((await anon.request.get(base+"/api/processing-tasks/"+id)).status(),401);
    assert.equal((await post("/api/videos/"+vid+"/processing-tasks",null,anon)).status(),401);
    assert.equal((await post("/api/processing-tasks/"+id+"/retry",{expectedRunNo:1},anon)).status(),401);
    await anon.close(); await bob.close();
  });
  await check("Reload recovery, desktop/mobile layout and logout cleanup",async()=>{
    await select(report.fixtures.normal);
    await page.reload();
    await page.getByRole("button",{name:"添加课程视频",exact:true}).click();
    await page.locator('.processing-status[data-status="SUCCEEDED"]').waitFor();
    await page.screenshot({path:join(output,"workspace-desktop.png"),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    await page.screenshot({path:join(output,"workspace-mobile.png"),fullPage:true});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
    await post("/api/auth/logout");
    await page.getByRole("button",{name:"刷新状态",exact:true}).click();
    await page.getByRole("heading",{name:"欢迎回来"}).waitFor();
    assert.equal(await page.locator(".processing-panel").count(),0);
    assert.equal(await page.locator("video").count(),0);
    assert.deepEqual(report.pageErrors,[]);
  });
  report.status="passed";
} finally {
  if(kafkaStopped) { try { docker("up","-d","--wait","kafka"); } catch(error) { report.restoreKafkaError=error.message; } }
  if(minioStopped) { try { docker("up","-d","--wait","minio"); } catch(error) { report.restoreMinioError=error.message; } }
  report.finishedAt=new Date().toISOString(); save(); await browser.close();
}
