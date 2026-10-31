import assert from "node:assert/strict";

import {readFileSync,writeFileSync,mkdirSync} from "node:fs";
import {fileURLToPath,pathToFileURL} from "node:url";
import {dirname,resolve,join} from "node:path";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."),out=join(root,".local/frontend-review"),base="http://127.0.0.1:5173";
mkdirSync(out,{recursive:true});
const {chromium}=await import(pathToFileURL(process.env.COURSETRACE_PLAYWRIGHT_MODULE||join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs")).href);
const report={observedAt:new Date().toISOString(),checks:[],pageErrors:[],unexpectedPosts:[]};
const browser=await chromium.launch({channel:"msedge",headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},locale:"zh-CN",reducedMotion:"reduce"});
context.on("page",p=>p.on("pageerror",e=>report.pageErrors.push(e.message)));
const save=()=>writeFileSync(join(out,"verification.json"),JSON.stringify(report,null,2));
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const api=async(path)=>{const r=await context.request.get(base+path);assert.equal(r.status(),200,path);return(await r.json()).data;};
async function until(f,ms=10000){const end=Date.now()+ms;while(Date.now()<end){if(await f())return;await sleep(100);}throw Error("Condition timed out");}
const login=await context.newPage();await login.goto(base);await login.getByLabel("密码",{exact:true}).fill("CourseTrace@123");await login.getByRole("button",{name:"进入学习空间"}).click();await login.getByRole("heading",{name:/^我的课程/}).waitFor();
const course=(await api("/api/courses?size=50")).items.find(c=>c.title==="M5 算法与数据存储验证课程");assert.ok(course,"Requires existing M5 fixture");
const video=(await api("/api/courses/"+course.id+"/videos?size=50")).items.find(v=>v.title.includes("算法与数据存储"));
const url=base+"/#/courses/"+course.id+"?video="+video.id,history=await api("/api/videos/"+video.id+"/questions"),notes=await api("/api/videos/"+video.id+"/notes");
report.fixture={course:course.id,video:video.id,historyTotal:history.total,notes:notes.items.length};
async function open(p){await p.goto(url);await p.locator("video").waitFor();await p.locator(".subtitle-line").first().waitFor();if(p.viewportSize().width>=1280)await p.getByRole("tab",{name:/^课程问答/}).click();}
async function ready(p){await p.locator('.qa-index[data-index-status="READY"]').waitFor();await p.locator(".qa-turn").first().waitFor();}
async function captionReady(p){await until(()=>p.locator("video track").evaluate(t=>t.readyState===2),20000);}
async function check(name,run){
 const p=await context.newPage();await p.route("**/api/**",async r=>{
  if(r.request().method()==="POST"){report.unexpectedPosts.push(r.request().url());await r.abort();}
  else await r.continue();
 });
 try{const evidence=await run(p);report.checks.push({name,status:"passed",evidence});console.log("PASS "+name);}
 catch(e){report.checks.push({name,status:"failed",message:e.message});console.log("FAIL "+name+": "+e.message);await p.screenshot({path:join(out,"failure-"+report.checks.length+".png"),fullPage:true}).catch(()=>{});}
 finally{await p.close();save();}
}
try{
await check("Notes unavailable: real subtitles and native captions remain usable",async p=>{
 await p.route("**/api/videos/"+video.id+"/notes",r=>r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_NOTE_FAILURE"})}));
 await open(p);await captionReady(p);assert.equal(await p.locator(".subtitle-line").count(),50);
 await p.getByRole("tab",{name:/^笔记/}).click();await p.getByText("笔记：SIMULATED_NOTE_FAILURE",{exact:true}).waitFor();
 return{visibleSubtitles:50,nativeCaptionReady:true};
});
await check("Subtitles unavailable: independently loaded notes remain readable",async p=>{
 await p.route("**/api/videos/"+video.id+"/segments?*",r=>r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_SUBTITLE_FAILURE"})}));
 await p.goto(url);await p.getByRole("tab",{name:/^笔记/}).click();await until(async()=>await p.locator(".learning-note").count()===notes.items.length);await captionReady(p);
 return{notes:await p.locator(".learning-note").count()};
});
await check("Index unavailable: real saved answers and citations remain readable",async p=>{
 await p.route("**/api/videos/"+video.id+"/search-index",r=>r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_INDEX_FAILURE"})}));
 await open(p);await p.getByText("问答资料：SIMULATED_INDEX_FAILURE",{exact:true}).waitFor();await until(async()=>await p.locator(".qa-turn").count()===history.items.length);
 assert.ok(await p.locator("[data-qa-source]").count()>0);assert.equal(await p.locator(".qa-empty").count(),0);assert.equal(await p.getByRole("button",{name:"提问",exact:true}).isEnabled(),false);
 return{renderedAnswers:await p.locator(".qa-turn").count()};
});
await check("History failure is distinguished from a genuinely empty history",async p=>{
 await p.route("**/api/videos/"+video.id+"/questions?*",r=>r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_HISTORY_FAILURE"})}));
 await open(p);await p.getByText("问答记录：SIMULATED_HISTORY_FAILURE",{exact:true}).waitFor();assert.equal(await p.getByText("把不确定的地方问清楚，让理解留下依据。",{exact:true}).count(),0);
 assert.equal(await p.getByRole("button",{name:"提问",exact:true}).isEnabled(),false);
});
await check("Uncertain POST blocks new questions; explicit replay preserves original ID across reload",async p=>{
 const ids=[];let reads=0;await p.route("**/api/videos/"+video.id+"/questions",r=>{
  ids.push(r.request().postDataJSON().requestId);return r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_UNCERTAIN_POST"})});
 });
 await p.route("**/api/videos/"+video.id+"/questions/request/*",r=>{reads++;return r.fulfill({status:404,contentType:"application/json",body:JSON.stringify({message:"not found"})});});
 await open(p);await ready(p);await p.locator("#course-question").fill("原请求编号回归验证");await p.getByRole("button",{name:"提问",exact:true}).click();await p.getByRole("button",{name:"确认原请求",exact:true}).waitFor();
 assert.equal(await p.getByRole("button",{name:"提问",exact:true}).isEnabled(),false);
 await p.getByRole("button",{name:"确认原请求",exact:true}).click();await p.getByRole("button",{name:"用原编号重新提交"}).waitFor();assert.equal(ids.length,1);
 await p.getByRole("button",{name:"用原编号重新提交"}).click();await until(()=>ids.length===2);assert.equal(ids[0],ids[1]);
 await p.reload();await p.getByRole("tab",{name:/^课程问答/}).click();await p.getByRole("button",{name:"确认原请求",exact:true}).waitFor();assert.equal(await p.locator("#course-question").inputValue(),"原请求编号回归验证");assert.equal(ids.length,2);
 await p.getByRole("button",{name:"确认原请求",exact:true}).click();assert.equal(ids.length,2);
 return{posts:ids.length,sameRequestId:ids[0]===ids[1],readOnlyChecks:reads,reloadPreserved:true};
});
await check("Read-only reconciliation finds completion and never posts again",async p=>{
 let id,posts=0;const existing=history.items.find(t=>t.status==="SUCCEEDED");assert.ok(existing);
 await p.route("**/api/videos/"+video.id+"/questions",r=>{posts++;id=r.request().postDataJSON().requestId;return r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_LOST_RESPONSE"})});});
 await p.route("**/api/videos/"+video.id+"/questions/request/*",r=>r.fulfill({status:200,contentType:"application/json",body:JSON.stringify({data:{...existing,requestId:id,question:"已完成结果确认"}})}));
 await open(p);await ready(p);await p.locator("#course-question").fill("已完成结果确认");await p.getByRole("button",{name:"提问",exact:true}).click();await p.getByRole("button",{name:"确认原请求",exact:true}).waitFor();await p.getByRole("button",{name:"确认原请求",exact:true}).click();
 await p.locator(".qa-pending-check").waitFor({state:"hidden"});assert.equal(posts,1);assert.equal(await p.locator("#course-question").inputValue(),"");
 return{modelSubmissionReplays:0};
});
await check("Only active work polls; hidden tabs pause reads and successful refresh clears errors",async p=>{
 let fail=false,hits=0;await p.route("**/api/videos/"+video.id+"/search-index",r=>{hits++;return fail?r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_TRANSIENT_ERROR"})}):r.fallback();});
 await open(p);await ready(p);await sleep(500);const idle=hits;await sleep(8300);assert.equal(hits,idle,"idle panel must not poll");
 fail=true;await p.getByRole("button",{name:"刷新问答记录"}).click();await p.getByText("问答资料：SIMULATED_TRANSIENT_ERROR",{exact:true}).waitFor();
 await p.evaluate(()=>{Object.defineProperty(document,"hidden",{configurable:true,get:()=>true});document.dispatchEvent(new Event("visibilitychange"));});
 const hidden=hits;await sleep(5400);assert.equal(hits,hidden,"hidden panel must not poll");
 fail=false;await p.evaluate(()=>{Object.defineProperty(document,"hidden",{configurable:true,get:()=>false});document.dispatchEvent(new Event("visibilitychange"));});
 await p.getByText("问答资料：SIMULATED_TRANSIENT_ERROR",{exact:true}).waitFor({state:"hidden"});return{idleRequests:0,hiddenRequests:0};
});
await check("Notes collapse all verified sources by default and expand real text",async p=>{
 await open(p);await p.getByRole("tab",{name:/^笔记/}).click();await p.locator(".learning-note").first().waitFor();
 const first=p.locator(".learning-note").first();assert.equal(await first.locator(".note-sources button:visible").count(),0);assert.equal(await first.locator(".note-replay").count(),1);
 await first.locator(".note-source-details summary").click();assert.equal(await first.locator(".note-sources button:visible").count(),notes.items[0].sources.length);assert.ok((await first.locator(".note-source-quote").first().innerText()).length>0);
 await p.screenshot({path:join(out,"notes-desktop.png")});return{allSourcesPreserved:notes.items[0].sources.length};
});
await check("Question citation seeks and brings the real player into view",async p=>{
 await open(p);await ready(p);const source=p.locator("[data-qa-source]").first();const ms=Number(await source.getAttribute("data-begin-ms"));
 await p.locator("video").evaluate(v=>v.pause());await source.click();await until(()=>p.locator("video").evaluate((v,at)=>{const r=v.getBoundingClientRect();return Math.abs(v.currentTime-at/1000)<1.5&&r.top>=-1&&r.bottom<=innerHeight+1;},ms));
 await p.locator(".seek-notice").waitFor();await p.locator("video").evaluate(v=>v.pause());return{targetMs:ms,playerVisible:true};
});
await check("Chapter replay and expanded source both seek to the original video",async p=>{
 await open(p);await p.getByRole("tab",{name:/^笔记/}).click();const note=p.locator(".learning-note").first();await note.locator(".note-replay").click();
 await until(()=>p.locator("video").evaluate((v,ms)=>Math.abs(v.currentTime-ms/1000)<1.5,notes.items[0].beginMs));await p.locator("video").evaluate(v=>v.pause());
 await note.locator(".note-source-details summary").click();const source=notes.items[0].sources.at(-1);
 await note.locator('[data-source-segment="'+source.segmentId+'"]').click();await until(()=>p.locator("video").evaluate((v,ms)=>Math.abs(v.currentTime-ms/1000)<1.5,source.beginMs));await p.locator("video").evaluate(v=>v.pause());
});
await check("Caption retry reloads the same transcript without resetting playback",async p=>{
 let fail=true,hits=0;await p.route("**/api/videos/"+video.id+"/subtitles.vtt?*",r=>{hits++;return fail?r.fulfill({status:503,body:"mock failure"}):r.fallback();});
 await open(p);await p.getByRole("button",{name:"重新加载字幕",exact:true}).waitFor();await p.locator("video").evaluate(v=>{v.pause();v.currentTime=12;});
 fail=false;const before=hits;await p.getByRole("button",{name:"重新加载字幕",exact:true}).click();await captionReady(p);assert.ok(hits>before);assert.equal(await p.locator("video").evaluate(v=>Math.abs(v.currentTime-12)<0.5),true);
 const after=hits;await p.getByRole("button",{name:"刷新学习资料"}).click();await until(()=>hits>after);return{sameVersionReload:true,playbackPreserved:true};
});
await check("Upload protects logo, navigation, other course, logout and page refresh",async p=>{
 let release;const gate=new Promise(r=>release=r);let intercepted=false;
 await p.route("**/api/courses/"+course.id+"/videos",async r=>{if(r.request().method()!=="POST")return r.fallback();intercepted=true;await gate;await r.fulfill({status:201,contentType:"application/json",body:JSON.stringify({data:video})}).catch(()=>{});});
 try{
  await open(p);await p.getByRole("button",{name:"添加课程视频",exact:true}).click();await p.getByLabel("选择课程视频").setInputFiles({name:"guard-review.mp4",mimeType:"video/mp4",buffer:Buffer.from("browser mock only")});await p.getByRole("button",{name:"上传视频",exact:true}).click();await p.locator(".upload-progress").waitFor();
  await p.keyboard.press("Escape");assert.equal(await p.locator("#course-upload-tools").evaluate(e=>e.open),true);
  assert.equal(await p.locator(".logout-button").isDisabled(),true);
  await p.evaluate(()=>{location.hash="/courses/8?video=28";});await sleep(300);assert.equal(p.url(),url);
  const dialogs=[];p.on("dialog",async d=>{dialogs.push(d.type());await d.dismiss();});await p.reload({timeout:5000}).catch(()=>{});assert.ok(dialogs.includes("beforeunload"));assert.equal(p.url(),url);
  release();await p.locator(".upload-progress").waitFor({state:"hidden"});await p.getByRole("link",{name:"CourseTrace 首页"}).click();await p.getByRole("heading",{name:/^我的课程/}).waitFor();
  return{intercepted,guardRemovedAfterCompletion:true,refreshDialog:dialogs[0]};
 }finally{release();}
});
await check("Mobile starts with the player, collapsed tools and a working upload entry",async p=>{
 await p.setViewportSize({width:390,height:844});await open(p);await p.evaluate(()=>scrollTo(0,0));
 const layout=await p.locator("video").evaluate(v=>({top:v.getBoundingClientRect().top,bottom:v.getBoundingClientRect().bottom,viewport:innerHeight,overflow:document.documentElement.scrollWidth>innerWidth}));
 assert.ok(layout.top<layout.viewport);assert.equal(layout.overflow,false);assert.equal(await p.locator("#course-upload-tools").getAttribute("open"),null);
 await p.screenshot({path:join(out,"mobile-after.png")});await p.getByRole("button",{name:"添加课程视频",exact:true}).click();await until(async()=>await p.locator("#course-upload-tools").getAttribute("open")!==null);await p.getByRole("button",{name:"上传视频",exact:true}).waitFor();
 await p.screenshot({path:join(out,"mobile-upload.png")});return layout;
});
await check("Video switching removes stale source, track and request state",async p=>{
 await open(p);await ready(p);const old=await p.locator("video track").getAttribute("src");await p.goto(base+"/#/courses/8?video=28");await p.locator("video").waitFor();await captionReady(p);
 assert.notEqual(await p.locator("video track").getAttribute("src"),old);assert.ok((await p.locator("video track").getAttribute("src")).includes("/videos/28/"));assert.equal(await p.locator(".qa-pending-check").count(),0);
 await p.getByRole("button",{name:"我的课程",exact:true}).click();await p.getByRole("heading",{name:/^我的课程/}).waitFor();assert.equal(await p.locator("video").count(),0);
});
assert.equal(report.unexpectedPosts.length,0,"No actual model/index/upload submission is allowed in these fault tests");
assert.equal(report.pageErrors.length,0);
if(report.checks.some(c=>c.status==="failed"))process.exitCode=1;
}finally{save();await browser.close();console.log("Saved "+join(out,"verification.json"));}
