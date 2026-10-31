import assert from "node:assert/strict";
import {readFileSync,writeFileSync,mkdirSync,existsSync} from "node:fs";
import {resolve,dirname,join} from "node:path";
import {fileURLToPath,pathToFileURL} from "node:url";
import {createHash,randomUUID} from "node:crypto";
import {execFileSync} from "node:child_process";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."),base="http://127.0.0.1:5173";
const run=process.env.COURSETRACE_UPGRADE_RUN_ID||new Date().toISOString().replace(/[^0-9]/g,"").slice(0,14);
assert.match(run,/^[a-zA-Z0-9-]{1,40}$/);const out=join(root,".local/upgrade",run);mkdirSync(out,{recursive:true});
const read=(name,fallback)=>existsSync(join(out,name))?JSON.parse(readFileSync(join(out,name),"utf8")):fallback;
const manifest=read("manifest.json",{run}),report=read("verification.json",{run,checks:[],pageErrors:[],kind:"actual MinIO/Redis/Edge integration"});
const save=()=>{writeFileSync(join(out,"manifest.json"),JSON.stringify(manifest,null,2));writeFileSync(join(out,"verification.json"),JSON.stringify(report,null,2));};
const pause=ms=>new Promise(r=>setTimeout(r,ms));
async function wait(action,timeout=30000){const until=Date.now()+timeout;while(Date.now()<until){const result=await action();if(result)return result;await pause(300);}throw Error("Integration condition timed out");}
async function check(name,action){if(report.checks.some(c=>c.name===name&&c.status==="passed")){console.log("REUSE "+name);return;}try{const start=Date.now();await action();report.checks.push({name,status:"passed",elapsedMs:Date.now()-start,at:new Date().toISOString()});if(report.failure?.name===name)delete report.failure;console.log("PASS "+name);save();}catch(e){report.failure={name,message:e.message};save();throw e;}}
function ps(script,...args){return execFileSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-ExecutionPolicy","Bypass","-File",join(root,"scripts",script),...args],{cwd:root,encoding:"utf8",windowsHide:true,timeout:180000,maxBuffer:4*1024*1024,stdio:script.includes("idea-start")?["ignore","ignore","ignore"]:["ignore","pipe","pipe"]});}
const redis=(...args)=>ps("docker.ps1","exec","-T","redis","redis-cli","--raw",...args).trim();
function sql(query){return ps("docker.ps1","exec","-T","mysql","sh","-c","echo "+Buffer.from(query).toString("base64")+" | base64 -d | MYSQL_PWD=$MYSQL_PASSWORD mysql --default-character-set=utf8mb4 -u$MYSQL_USER $MYSQL_DATABASE -NB").trim();}
const module=process.env.COURSETRACE_PLAYWRIGHT_MODULE||join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs");
const {chromium}=await import(pathToFileURL(module).href),browser=await chromium.launch({channel:"msedge",headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1080},locale:"zh-CN"}),page=await context.newPage();page.on("pageerror",e=>report.pageErrors.push(e.message));
async function get(path,ctx=context){const r=await ctx.request.get(base+path);assert.equal(r.status(),200,"GET "+path);return(await r.json()).data;}
async function post(path,data,ctx=context,method="POST"){const csrf=await get("/api/auth/csrf",ctx);return ctx.request.fetch(base+path,{method,timeout:155000,headers:{[csrf.headerName]:csrf.token},...(data===undefined?{}:{data})});}
async function login(ctx=context,user="student"){const csrf=await get("/api/auth/csrf",ctx);const r=await ctx.request.post(base+"/api/auth/login",{headers:{[csrf.headerName]:csrf.token},form:{username:user,password:"CourseTrace@123"}});assert.equal(r.status(),200);return(await r.json()).data;}
async function coursePage(){await page.goto(base+"/#/courses/"+manifest.course.id+(manifest.video?"?video="+manifest.video.id:""));await page.getByRole("button",{name:"资源检查与处理详情",exact:true}).waitFor();}
async function taskDone(id){return wait(async()=>{const task=await get("/api/processing-tasks/"+id);if(task.status==="FAILED")throw Error(task.errorCode+": "+task.errorMessage);return task.status==="SUCCEEDED"?task:false;},360000);}
async function seek(locator){const ms=Number(await locator.getAttribute("data-begin-ms"));await page.locator("video").evaluate(v=>{v.pause();window.__upgradeSeeks=[];v.addEventListener("seeked",()=>window.__upgradeSeeks.push(v.currentTime));});await locator.click();await page.waitForFunction(at=>window.__upgradeSeeks.some(t=>Math.abs(t-at/1000)<.6),ms);await page.locator("video").evaluate(v=>v.pause());return{expectedMs:ms,actualSeconds:await page.locator("video").evaluate(v=>v.currentTime)};}
try{
 manifest.user=await login();if(!manifest.course){const r=await post("/api/courses",{title:"数据结构 · 续传与额度验收 "+run,description:"自写合成语音课程；本地集成验收"});assert.equal(r.status(),201);manifest.course=(await r.json()).data;save();}
 const source=join(root,".local/m7/sample-course.mp4");assert.ok(existsSync(source),"Prepare the original synthetic M7 lesson first");const original=readFileSync(source),padded=Buffer.concat([original,Buffer.alloc(18*1024*1024-original.length)]),sample=join(out,"resume-course.mp4");writeFileSync(sample,padded);const sha=createHash("sha256").update(padded).digest("hex");
 await check("Actual chunks survive pause, reload and IDEA restart; only missing parts resume",async()=>{
  if(manifest.uploadId){const previous=await get("/api/uploads/"+manifest.uploadId);assert.notEqual(previous.status,"COMPLETED","Use a new RunId when an unverified transfer already completed");const cancelled=await post("/api/uploads/"+manifest.uploadId,undefined,context,"DELETE");assert.equal(cancelled.status(),200);}
  await coursePage();await page.getByRole("button",{name:"添加课程视频",exact:true}).click();await page.getByLabel("选择课程视频").setInputFiles(sample);await page.getByLabel("视频标题",{exact:true}).fill("有序查找与循环队列（合成语音）");
  let release;const gate=new Promise(r=>release=r);let first=true,resuming=false;const resumedParts=[];
  await page.route("**/api/uploads/*/parts/*",async route=>{const n=Number(new URL(route.request().url()).pathname.split("/").at(-1));if(resuming)resumedParts.push(n);if(first&&n===1){await gate;await route.abort().catch(()=>{});}else await route.continue();});
  const initialized=page.waitForResponse(r=>/\/api\/courses\/\d+\/uploads$/.test(new URL(r.url()).pathname)&&r.request().method()==="POST");
  await page.getByRole("button",{name:"上传视频",exact:true}).click();const initResponse=await initialized;assert.equal(initResponse.request().postDataJSON().sha256,sha);manifest.uploadId=(await initResponse.json()).data.id;save();
  await wait(async()=>{const s=await get("/api/uploads/"+manifest.uploadId);return s.parts.some(p=>p.partNo===0)&&!s.parts.some(p=>p.partNo===1);});
  await page.getByRole("button",{name:"暂停上传",exact:true}).click();first=false;release();await page.getByRole("button",{name:"继续上传",exact:true}).waitFor();await page.unroute("**/api/uploads/*/parts/*");
  const confirmed=(await get("/api/uploads/"+manifest.uploadId)).parts.map(p=>p.partNo);assert.ok(confirmed.includes(0));report.confirmedBeforeRestart=confirmed;
  await page.screenshot({path:join(out,"upload-paused.png")});
  console.log("CHECKPOINT paused parts="+confirmed.join(",")+"; restart IDEA launcher");ps("idea-start.ps1","-NoBrowser");console.log("CHECKPOINT launcher ready; reselect original file");manifest.user=await login();await page.reload();await page.getByRole("button",{name:"添加课程视频",exact:true}).click();assert.equal(await page.getByLabel("选择课程视频").evaluate(el=>el.files.length),0);await page.getByText(/检测到上传记录/).waitFor();
  await page.getByLabel("选择课程视频").setInputFiles(sample);resuming=true;
  await page.route("**/api/uploads/*/parts/*",async route=>{resumedParts.push(Number(new URL(route.request().url()).pathname.split("/").at(-1)));await route.continue();});
  let lost=false,completionError;await page.route("**/api/uploads/*/complete",async route=>{try{if(!lost){lost=true;const response=await route.fetch();const body=await response.json();if(response.status()!==200){completionError=JSON.stringify(body);await route.fulfill({response});return;}manifest.video=body.data.video;save();await route.abort();}else await route.continue();}catch(e){completionError=e.message;await route.abort().catch(()=>{});}});
  await page.getByRole("button",{name:"继续上传",exact:true}).click();await page.getByRole("button",{name:"确认保存结果",exact:true}).waitFor({timeout:90000});assert.equal(completionError,undefined);await wait(()=>!!manifest.video,90000);await page.getByRole("button",{name:"确认保存结果",exact:true}).click();await page.locator("video").waitFor();
  assert.ok(resumedParts.length);assert.ok(resumedParts.every(n=>!confirmed.includes(n)));report.resumedParts=resumedParts;await page.unroute("**/api/uploads/*/parts/*");await page.unroute("**/api/uploads/*/complete");
 });
 await check("Merged MinIO bytes, Range playback and idempotent completion",async()=>{
  const session=await get("/api/uploads/"+manifest.uploadId);assert.equal(session.status,"COMPLETED");manifest.video=session.video;save();
  const r=await context.request.get(base+manifest.video.contentUrl,{timeout:60000});assert.equal(r.status(),200);assert.equal(createHash("sha256").update(await r.body()).digest("hex"),sha);
  const range=await context.request.get(base+manifest.video.contentUrl,{headers:{Range:"bytes=0-1023"}});assert.equal(range.status(),206);assert.equal((await range.body()).length,1024);
  for(let n=0;n<2;n++){const complete=await post("/api/uploads/"+manifest.uploadId+"/complete");assert.equal(complete.status(),200);assert.equal((await complete.json()).data.videoId,manifest.video.id);}
  await coursePage();await page.locator("video").evaluate(v=>{v.muted=true;return v.play();});await wait(()=>page.locator("video").evaluate(v=>v.currentTime>.2));await page.locator("video").evaluate(v=>v.pause());
 });
 await check("Cross-user upload isolation and existing Kafka resource pipeline",async()=>{
  const other=await browser.newContext();try{await login(other,"student2");assert.equal((await other.request.get(base+"/api/uploads/"+manifest.uploadId)).status(),404);}finally{await other.close();}
  const r=await post("/api/videos/"+manifest.video.id+"/processing-tasks");assert.ok([200,202].includes(r.status()));const task=(await r.json()).data;await taskDone(task.id);
 });
 const live=process.env.COURSETRACE_UPGRADE_LIVE_AI==="true";
 if(live){
  await check("New resumed video ASR notes and Qdrant index reuse existing Kafka chain",async()=>{
   let r=await post("/api/videos/"+manifest.video.id+"/processing-tasks",{pipelineVersion:2});assert.ok([200,202].includes(r.status()));manifest.learningTask=(await r.json()).data.id;save();await taskDone(manifest.learningTask);
   assert.ok((await get("/api/videos/"+manifest.video.id+"/segments?page=1&size=50")).items.length);
   r=await post("/api/videos/"+manifest.video.id+"/search-index");assert.equal(r.status(),202);manifest.indexTask=(await r.json()).data.id;save();await taskDone(manifest.indexTask);assert.equal((await get("/api/videos/"+manifest.video.id+"/search-index")).ready,true);
  });
  for(const [mode,question] of [["RAG","记录元素数量的方法怎样判断循环队列为空或已满？"],["AGENT","请先搜索循环队列的保留一个空位置，再补读前后内容，解释这种约定指什么。"]])await check("Actual "+mode+" answer and citation seek",async()=>{
   const key=mode.toLowerCase();manifest[key]??={requestId:"upgrade-"+createHash("sha256").update(run+mode).digest("hex").slice(0,40),question,mode};save();const r=await post("/api/videos/"+manifest.video.id+"/questions",manifest[key]);assert.ok([200,202].includes(r.status()));const turn=(await r.json()).data;assert.equal(turn.status,"SUCCEEDED",JSON.stringify(turn));manifest[key].turn=turn;save();
   await coursePage();await page.getByRole("tab",{name:/^课程问答/}).click();await page.getByRole("button",{name:"刷新问答记录",exact:true}).click();const item=page.locator('[data-turn-id="'+turn.id+'"]');await item.waitFor();report[key+"Seek"]=await seek(item.locator("[data-qa-source]").first());
   const replay=await post("/api/videos/"+manifest.video.id+"/questions",manifest[key]);assert.equal((await replay.json()).data.id,turn.id);
  });
 }else report.liveAi="not requested; no new ASR or question calls";
 await check("Redis denial preserves draft and replays do not charge; Redis outage fails closed",async()=>{
  const video=manifest.rag?.turn?manifest.video.id:58;const existing=manifest.rag?.turn||{requestId:"m7-fix-aaadd997927dc5d4d78071a3630a",question:"记录元素数量的方法怎样判断循环队列为空或已满？",mode:"RAG"};
  const owner=manifest.user.id,prefix="coursetrace:limit:{admission}:qa:",keys=[prefix+"user:"+owner,prefix+"global"],backup=keys.map(key=>({key,fields:redis("HGETALL",key).split(/\r?\n/).filter(Boolean),ttl:Number(redis("PTTL",key)),at:Date.now()}));
  const before=sql("SELECT COUNT(*),COALESCE(SUM(chat_requests),0),COALESCE(SUM(embedding_requests),0) FROM chat_turn WHERE video_id="+video);
  try{
   for(const key of keys){redis("HSET",key,"tokens","0","at",String(Date.now()));redis("PEXPIRE",key,"60000");}
   let r=await post("/api/videos/"+video+"/questions",{requestId:randomUUID(),question:"解释循环队列",mode:"RAG"});assert.equal(r.status(),429);assert.ok(Number(r.headers()["retry-after"])>=1);
   r=await post("/api/videos/"+video+"/questions",existing);assert.equal(r.status(),200);assert.equal((await r.json()).data.status,"SUCCEEDED");
   await page.goto(base+"/#/courses/"+(manifest.rag?.turn?manifest.course.id:15)+"?video="+video);await page.getByRole("tab",{name:/^课程问答/}).click();const draft="请解释循环队列为什么要区分空和满";await page.getByLabel("想弄懂哪一个知识点？").fill(draft);await page.getByRole("button",{name:"提问",exact:true}).click();await page.getByText(/提交较频繁/).waitFor();assert.equal(await page.getByLabel("想弄懂哪一个知识点？").inputValue(),draft);await page.screenshot({path:join(out,"rate-limit.png")});
   ps("docker.ps1","stop","redis");
   r=await post("/api/videos/"+video+"/questions",{requestId:randomUUID(),question:"解释循环队列",mode:"RAG"});assert.equal(r.status(),503);assert.equal((await r.json()).code,"RATE_LIMIT_UNAVAILABLE");
   assert.equal((await context.request.get(base+manifest.video.contentUrl,{headers:{Range:"bytes=0-1023"}})).status(),206);assert.equal((await get("/api/uploads/"+manifest.uploadId)).status,"COMPLETED");
   assert.equal(sql("SELECT COUNT(*),COALESCE(SUM(chat_requests),0),COALESCE(SUM(embedding_requests),0) FROM chat_turn WHERE video_id="+video),before);
  }finally{
   ps("docker.ps1","up","-d","--wait","redis");
   try{for(const key of keys){redis("HSET",key,"tokens","0","at",String(Date.now()));redis("PEXPIRE",key,"60000");}const recovered=await post("/api/videos/"+video+"/questions",{requestId:randomUUID(),question:"解释循环队列",mode:"RAG"});assert.equal(recovered.status(),429);report.redisAdmissionRecovered=true;}
   finally{for(const b of backup){redis("DEL",b.key);if(b.fields.length&&(b.ttl===-1||b.ttl>Date.now()-b.at)){redis("HSET",b.key,...b.fields);if(b.ttl>0)redis("PEXPIRE",b.key,String(Math.max(1,b.ttl-(Date.now()-b.at))));}}}
  }
  report.redisRestored=redis("PING")==="PONG";
 });
 assert.deepEqual(report.pageErrors,[]);report.status="passed";report.observedAt=new Date().toISOString();save();console.log(JSON.stringify({status:report.status,checks:report.checks.length,videoId:manifest.video.id,liveAi:live,report:join(out,"verification.json")}));
}finally{save();await browser.close();}
