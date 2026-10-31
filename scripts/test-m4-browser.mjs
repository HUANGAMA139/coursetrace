import assert from "node:assert/strict";
import {readFileSync,writeFileSync,mkdirSync} from "node:fs";
import {resolve,dirname,join} from "node:path";
import {pathToFileURL,fileURLToPath} from "node:url";
import {execFileSync} from "node:child_process";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."),out=join(root,".local/m4"),base="http://127.0.0.1:5173";
mkdirSync(out,{recursive:true});
const {chromium}=await import(pathToFileURL(join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs")).href);
const browser=await chromium.launch({channel:"msedge",headless:true}),context=await browser.newContext({viewport:{width:1440,height:1080},locale:"zh-CN"});
const page=await context.newPage();
const report={observedAt:new Date().toISOString(),checks:[],pageErrors:[],fixtures:{},observedStates:[]};
page.on("pageerror",e=>report.pageErrors.push(e.message));
function sql(query){
 const encoded=Buffer.from(query).toString("base64");
 return execFileSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-ExecutionPolicy","Bypass","-File",join(root,"scripts/docker.ps1"),"exec","-T","mysql","sh","-c","echo "+encoded+" | base64 -d | MYSQL_PWD=$MYSQL_PASSWORD mysql -u$MYSQL_USER $MYSQL_DATABASE -NB"],{cwd:root,encoding:"utf8",windowsHide:true,timeout:30000}).trim();
}
const save=()=>writeFileSync(join(out,"browser-verification.json"),JSON.stringify(report,null,2));
async function check(name,action){try{await action();report.checks.push({name,status:"passed"});console.log("PASS "+name);save();}catch(e){report.checks.push({name,status:"failed",message:e.message});save();throw e;}}
async function waitFor(action,timeout=180000){const end=Date.now()+timeout;while(Date.now()<end){const value=await action();if(value)return value;await new Promise(r=>setTimeout(r,500));}throw new Error("Expected learning state was not reached");}
async function csrf(target=context){return(await(await target.request.get(base+"/api/auth/csrf")).json()).data;}
async function post(path,data,target=context){const t=await csrf(target);return target.request.post(base+path,{headers:{[t.headerName]:t.token},...(data?{data}:{})});}
async function get(path,target=context){const r=await target.request.get(base+path);assert.equal(r.status(),200);return(await r.json()).data;}
async function login(target=page,name="student"){await target.goto(base);await target.getByLabel("账号",{exact:true}).fill(name);await target.getByLabel("密码",{exact:true}).fill("CourseTrace@123");await target.getByRole("button",{name:"进入学习空间"}).click();await target.getByRole("heading",{name:/^我的课程/}).waitFor();}
async function ensureVideo(title,file){
 const list=await get("/api/courses/"+report.fixtures.course.id+"/videos?size=50");
 const existing=list.items.find(v=>v.title===title);if(existing)return existing;
 const t=await csrf();const r=await context.request.post(base+"/api/courses/"+report.fixtures.course.id+"/videos",{headers:{[t.headerName]:t.token},multipart:{title,file:{name:"sample.mp4",mimeType:"video/mp4",buffer:readFileSync(file)}}});
 assert.equal(r.status(),201);return(await r.json()).data;
}
async function select(video){await page.goto(base+"/#/courses/"+report.fixtures.course.id+"?video="+video.id);await page.getByRole("heading",{name:"字幕与章节笔记",exact:true}).waitFor();}
async function processVideo(video){
 await select(video);
 let tasks=await get("/api/courses/"+report.fixtures.course.id+"/processing-tasks?pipelineVersion=2&videoIds="+video.id);
 if(!tasks.length){
  const response=page.waitForResponse(r=>r.url().endsWith("/api/videos/"+video.id+"/processing-tasks")&&r.request().method()==="POST");
  await page.getByRole("button",{name:"生成字幕与笔记",exact:true}).click();
  const accepted=await response;assert.equal(accepted.status(),202);tasks=[(await accepted.json()).data];
 }else if(tasks[0].status==="FAILED"&&tasks[0].canRetry){
  await page.getByRole("button",{name:"重试未完成步骤",exact:true}).click();
 }
 const id=tasks[0].id;
 await waitFor(async()=>{
  const current=await get("/api/processing-tasks/"+id);
  report.observedStates.push({videoId:video.id,stage:current.stage,status:current.status});
  if(current.status==="FAILED")throw new Error(current.errorCode+": "+current.errorMessage);
  return current.status==="SUCCEEDED";
 });
 await page.locator('.learning-state[data-learning-status="SUCCEEDED"]').waitFor({timeout:15000});
 return await get("/api/processing-tasks/"+id);
}
try{
 await login();
 await check("Isolated spoken-lesson fixtures and configured capabilities",async()=>{
  const cap=await get("/api/learning-capabilities");assert.equal(cap.configured,true);assert.equal(cap.maxDurationSeconds,3600);
  const courses=await get("/api/courses?size=50");
  report.fixtures.course=courses.items.find(c=>c.title==="M4 二分查找验证课程");
  if(!report.fixtures.course){const r=await post("/api/courses",{title:"M4 二分查找验证课程",description:"Windows 语音合成的明确课程内容，用于真实 ASR 和笔记验收"});assert.equal(r.status(),201);report.fixtures.course=(await r.json()).data;}
  report.fixtures.normal=await ensureVideo("二分查找讲解（标准音轨）",join(out,"sample-speech.mp4"));
  report.fixtures.delayed=await ensureVideo("二分查找讲解（延迟音轨）",join(out,"sample-delayed.mp4"));
  report.fixtures.silent=await ensureVideo("无音轨对照样片",join(root,".local/m2/sample-silent.mp4"));
 });
 await check("Real Kafka FFmpeg temporary upload ASR transcript and Qwen notes",async()=>{
  const task=await processVideo(report.fixtures.normal);report.fixtures.normalTaskId=task.id;
  const transcript=await get("/api/videos/"+report.fixtures.normal.id+"/transcript");
  const segments=await get("/api/videos/"+report.fixtures.normal.id+"/segments?size=100");
  const notes=await get("/api/videos/"+report.fixtures.normal.id+"/notes");
  assert.equal(transcript.ready,true);assert.ok(segments.items.length>=3);assert.equal(notes.complete,true);assert.ok(notes.items.length>0);
  assert.match(segments.items.map(s=>s.text).join(" "),/二分|binary/i);
  const ids=new Map(segments.items.map(s=>[s.id,s]));
  for(const note of notes.items){assert.ok(note.sources.length);for(const source of note.sources){assert.ok(ids.has(source.segmentId));assert.equal(source.beginMs,ids.get(source.segmentId).beginMs);}}
  assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+report.fixtures.normal.id),"1");
  assert.equal(sql("SELECT status FROM asr_job WHERE video_id="+report.fixtures.normal.id),"SUCCEEDED");
  report.normalSegments=segments.items;report.normalNotes=notes.items;report.normalTask=task;
  report.noteRequests=Number(sql("SELECT SUM(request_count) FROM processing_artifact WHERE kind='NOTE' AND video_id="+report.fixtures.normal.id));
 });
 await check("Authenticated WebVTT loads native cues and subtitle click seeks actual player",async()=>{
  const id=report.fixtures.normal.id,version=(await get("/api/videos/"+id+"/transcript")).version;
  const vtt=await context.request.get(base+"/api/videos/"+id+"/subtitles.vtt?version="+version);
  assert.equal(vtt.status(),200);assert.match(vtt.headers()["content-type"],/text\/vtt/);assert.ok((await vtt.text()).startsWith("WEBVTT"));
  await waitFor(()=>page.evaluate(()=>document.querySelector("video")?.textTracks?.[0]?.cues?.length>0),15000);
  const target=report.normalSegments[Math.min(3,report.normalSegments.length-1)];
  await page.locator('[data-segment-id="'+target.id+'"]').click();
  await waitFor(()=>page.evaluate(ms=>Math.abs(document.querySelector("video").currentTime-ms/1000)<1.5,target.beginMs),10000);
  await page.locator("video").evaluate(v=>v.pause());
 });
 await check("Notes render verified sources and jump back to the cited explanation",async()=>{
  await page.getByRole("tab",{name:/^笔记/}).click();
  await page.locator('.note-source-details summary').first().click();
  const source=report.normalNotes[0].sources[0];
  await page.locator('[data-source-segment="'+source.segmentId+'"]').first().click();
  await waitFor(()=>page.evaluate(ms=>Math.abs(document.querySelector("video").currentTime-ms/1000)<1.5,source.beginMs),10000);
  await page.locator("video").evaluate(v=>v.pause());
 });
 await check("Duplicate submissions and reload reuse remote task and stable segment IDs",async()=>{
  const id=report.fixtures.normal.id;
  const responses=await Promise.all(Array.from({length:4},()=>post("/api/videos/"+id+"/processing-tasks",{pipelineVersion:2})));
  for(const r of responses){assert.equal(r.status(),200);assert.equal((await r.json()).data.id,report.fixtures.normalTaskId);}
  await page.reload();await page.locator('.learning-state[data-learning-status="SUCCEEDED"]').waitFor();
  const again=await get("/api/videos/"+id+"/segments?size=100");
  assert.deepEqual(again.items.map(s=>s.id),report.normalSegments.map(s=>s.id));
  assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+id),"1");
  assert.equal(Number(sql("SELECT SUM(request_count) FROM processing_artifact WHERE kind='NOTE' AND video_id="+id)),report.noteRequests);
 });
 await check("Delayed audio is aligned to original video time",async()=>{
  const task=await processVideo(report.fixtures.delayed);report.fixtures.delayedTaskId=task.id;
  const delayed=await get("/api/videos/"+report.fixtures.delayed.id+"/segments?size=100");
  report.delayedSegments=delayed.items;
  const delta=delayed.items[0].beginMs-report.normalSegments[0].beginMs;report.firstSentenceDelayMs=delta;
  assert.ok(delta>=1500&&delta<=2800,"Expected approximately two seconds of preserved initial audio delay, got "+delta);
  const target=delayed.items[Math.min(3,delayed.items.length-1)];
  await page.locator('[data-segment-id="'+target.id+'"]').click();
  await waitFor(()=>page.evaluate(ms=>Math.abs(document.querySelector("video").currentTime-ms/1000)<1.5,target.beginMs),10000);
  await page.locator("video").evaluate(v=>v.pause());
 });
 await check("Silent video rejected before paid calls and ownership/CSRF protect all material endpoints",async()=>{
  const silent=await post("/api/videos/"+report.fixtures.silent.id+"/processing-tasks",{pipelineVersion:2});
  assert.equal(silent.status(),422);assert.equal((await silent.json()).code,"NO_AUDIO");
  assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+report.fixtures.silent.id),"0");
  const id=report.fixtures.normal.id,bob=await browser.newContext(),bp=await bob.newPage();await login(bp,"student2");
  for(const path of ["/transcript","/segments","/notes","/subtitles.vtt"]){
   assert.equal((await bob.request.get(base+"/api/videos/"+id+path)).status(),404);
   const anon=await browser.newContext();assert.equal((await anon.request.get(base+"/api/videos/"+id+path)).status(),401);await anon.close();
  }
  assert.equal((await post("/api/videos/"+id+"/processing-tasks",{pipelineVersion:2},bob)).status(),404);
  assert.equal((await context.request.post(base+"/api/videos/"+id+"/processing-tasks",{data:{pipelineVersion:2}})).status(),403);
  assert.equal((await post("/api/processing-tasks/"+report.fixtures.normalTaskId+"/retry",{expectedRunNo:1},bob)).status(),404);
  await bob.close();
 });
 await check("Desktop/mobile material layout and logout unmount player, tracks and learning panel",async()=>{
  await select(report.fixtures.normal);await page.getByRole("tab",{name:/^笔记/}).click();
  await page.locator(".learning-note").first().waitFor();
  await page.screenshot({path:join(out,"workspace-desktop.png"),fullPage:true});
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:join(out,"workspace-mobile.png"),fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await post("/api/auth/logout");await page.getByRole("button",{name:"刷新学习资料",exact:true}).click();
  await page.getByRole("heading",{name:"欢迎回来"}).waitFor();
  assert.equal(await page.locator(".learning-materials,video,track").count(),0);assert.deepEqual(report.pageErrors,[]);
 });
 report.status="passed";
}finally{report.finishedAt=new Date().toISOString();save();await browser.close();}
