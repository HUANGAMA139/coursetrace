import assert from "node:assert/strict";
import {pathToFileURL,fileURLToPath} from "node:url";
import {join} from "node:path";
const {chromium}=await import(pathToFileURL(process.env.COURSETRACE_PLAYWRIGHT_MODULE||join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs")).href);
import {mkdirSync,writeFileSync} from "node:fs";
const base="http://127.0.0.1:5173",out=fileURLToPath(new URL("../.local/study-layout/",import.meta.url));
mkdirSync(out,{recursive:true});
const browser=await chromium.launch({channel:"msedge",headless:true}),context=await browser.newContext({viewport:{width:1440,height:1000},locale:"zh-CN",reducedMotion:"reduce"});
const report={checks:[],pageErrors:[],unexpectedPosts:[],observedAt:new Date().toISOString()};
context.on("page",p=>p.on("pageerror",e=>report.pageErrors.push(e.message)));
const save=()=>writeFileSync(out+"/verification.json",JSON.stringify(report,null,2));
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,ms=12000){const end=Date.now()+ms;while(Date.now()<end){if(await fn())return;await sleep(100);}throw Error("condition timed out");}
const login=await context.newPage();await login.goto(base);await login.getByLabel("密码",{exact:true}).fill("CourseTrace@123");await login.getByRole("button",{name:"进入学习空间"}).click();await login.getByRole("heading",{name:/^我的课程/}).waitFor();
async function data(path){const r=await context.request.get(base+path);assert.equal(r.status(),200);return(await r.json()).data;}
const course=(await data("/api/courses?size=50")).items.find(c=>c.title==="M5 算法与数据存储验证课程");assert.ok(course);
const video=(await data("/api/courses/"+course.id+"/videos?size=50")).items.find(v=>v.title.includes("算法与数据存储"));
const history=await data("/api/videos/"+video.id+"/questions"),turn=history.items.find(t=>t.status==="SUCCEEDED");
const url=base+"/#/courses/"+course.id+"?video="+video.id;
const qa=p=>p.getByRole("tab",{name:/^课程问答/});
const directory=p=>p.getByRole("tab",{name:"课程目录",exact:true});
async function open(p){await p.goto(url);await p.locator("video").waitFor();await p.locator(".subtitle-line").first().waitFor();}
async function showQa(p){await p.locator(".study-tools").waitFor({state:"attached"});if(p.viewportSize().width>=1280 && p.viewportSize().height>=720){await qa(p).waitFor();await qa(p).click();}else await p.getByRole("button",{name:/^课程问答/}).click();await p.locator(".qa-turn").first().waitFor();}
async function check(name,run){
 if(process.env.COURSETRACE_CHECK && !name.includes(process.env.COURSETRACE_CHECK))return;
 const p=await context.newPage();await p.route("**/api/**",r=>{if(r.request().method()==="POST"){report.unexpectedPosts.push(r.request().url());return r.abort();}return r.continue();});
 try{const evidence=await run(p);report.checks.push({name,status:"passed",evidence});console.log("PASS "+name);}
 catch(e){report.checks.push({name,status:"failed",message:e.message});console.log("FAIL "+name+": "+e.message);await p.screenshot({path:out+"/failure-"+report.checks.length+".png",fullPage:true}).catch(()=>{});}
 finally{await p.close();save();}
}
try{
await check("Desktop columns keep tools visible alongside long notes",async p=>{
 await open(p);await showQa(p);await until(()=>p.locator(".qa-compose").evaluate(e=>e.getBoundingClientRect().bottom<=innerHeight+1));await p.screenshot({path:out+"/desktop-initial.png"});await p.getByRole("tab",{name:/^笔记/}).click();await p.locator(".learning-note").first().waitFor();
 const first=await p.evaluate(()=>({left:document.querySelector("video").getBoundingClientRect().left,right:document.querySelector(".study-tools").getBoundingClientRect().left}));
 assert.ok(first.right>first.left+300);assert.equal(await p.locator(".course-viewing-column .course-qa,.course-viewing-column .video-list-panel").count(),0);
 await showQa(p);await until(()=>p.locator(".qa-compose").evaluate(e=>e.getBoundingClientRect().bottom<=innerHeight+1));
 await p.screenshot({path:out+"/desktop-top.png"});
 await p.evaluate(()=>scrollTo(0,1800));await sleep(250);
 const box=await p.locator(".study-tools").evaluate(e=>{const r=e.getBoundingClientRect();return{top:r.top,bottom:r.bottom,height:r.height};});
 assert.ok(box.top>=19&&box.top<=21);assert.ok(box.bottom<=1000);assert.ok(box.height>800);
 await p.screenshot({path:out+"/desktop-long-notes.png"});return box;
});
await check("Directory scroll and page survive tab switches without moving the main page",async p=>{
 const list={items:Array.from({length:20},(_,i)=>({...video,id:1000+i,title:"目录滚动模拟视频 "+(i+1)})),total:40,page:1,size:20};
 await p.route("**/api/courses/"+course.id+"/videos?*",r=>r.fulfill({status:200,contentType:"application/json",body:JSON.stringify({data:list})}));
 await p.route("**/processing-tasks?*",r=>r.fulfill({status:200,contentType:"application/json",body:JSON.stringify({data:[]})}));
 await open(p);await p.locator("#study-directory-panel").evaluate(e=>e.scrollTop=350);await sleep(150);
 const main=await p.evaluate(()=>scrollY);await qa(p).click();await p.locator(".qa-turn").first().waitFor();await directory(p).click();
 assert.equal(await p.evaluate(()=>scrollY),main);assert.ok(await p.locator("#study-directory-panel").evaluate(e=>e.scrollTop>=340));return{directoryScroll:await p.locator("#study-directory-panel").evaluate(e=>e.scrollTop)};
});
await check("Draft, history position and player instance survive tab changes",async p=>{
 await open(p);await showQa(p);await p.locator("#course-question").fill("保留这条未发送的草稿");await p.locator(".qa-history").evaluate(e=>e.scrollTop=200);await sleep(150);
 await p.evaluate(()=>window.savedPlayer=document.querySelector("video"));const main=await p.evaluate(()=>scrollY);
 await directory(p).click();await qa(p).click();await sleep(200);
 assert.equal(await p.locator("#course-question").inputValue(),"保留这条未发送的草稿");assert.ok(await p.locator(".qa-history").evaluate(e=>Math.abs(e.scrollTop-200)<3));
 assert.equal(await p.evaluate(()=>window.savedPlayer===document.querySelector("video")),true);assert.equal(await p.evaluate(()=>scrollY),main);
 await p.reload();await showQa(p);await until(()=>p.locator(".qa-history").evaluate(e=>Math.abs(e.scrollTop-200)<3));assert.equal(await p.locator("#course-question").inputValue(),"保留这条未发送的草稿");
});
await check("Question history scrolls independently and composer stays visible",async p=>{
 await open(p);await showQa(p);const before=await p.locator(".qa-compose").boundingBox();const main=await p.evaluate(()=>scrollY);
 await p.locator(".qa-history").evaluate(e=>e.scrollTop=250);await sleep(100);const after=await p.locator(".qa-compose").boundingBox();
 assert.ok(Math.abs(before.y-after.y)<2);assert.equal(await p.evaluate(()=>scrollY),main);assert.ok(after.y+after.height<=1001);
});
await check("References from the sidebar seek the video without resetting conversation",async p=>{
 await open(p);await showQa(p);await p.getByRole("tab",{name:/^笔记/}).click();await p.evaluate(()=>scrollTo(0,1800));await sleep(200);
 const citation=p.locator("[data-qa-source]").first(),ms=Number(await citation.getAttribute("data-begin-ms"));await citation.scrollIntoViewIfNeeded();const draft="回看时保留输入";await p.locator("#course-question").fill(draft);
 await citation.click();await until(()=>p.locator("video").evaluate((v,ms)=>{const r=v.getBoundingClientRect();return Math.abs(v.currentTime-ms/1000)<1.5&&r.top>=-1&&r.bottom<=innerHeight+1;},ms));await p.locator("video").evaluate(v=>v.pause());
 assert.equal(await p.locator("#course-question").inputValue(),draft);assert.equal(await qa(p).getAttribute("aria-selected"),"true");return{targetMs:ms};
});
await check("Pending request survives tab switches and reload, replay uses one ID",async p=>{
 const ids=[];await p.route("**/api/videos/"+video.id+"/questions",r=>{ids.push(r.request().postDataJSON().requestId);return r.fulfill({status:503,contentType:"application/json",body:JSON.stringify({message:"SIMULATED_LOST_RESPONSE"})});});
 await p.route("**/api/videos/"+video.id+"/questions/request/*",r=>r.fulfill({status:404,contentType:"application/json",body:JSON.stringify({message:"missing"})}));
 await open(p);await showQa(p);await p.locator("#course-question").fill("待确认请求保留");await p.getByRole("button",{name:"提问",exact:true}).click();await p.getByRole("button",{name:"确认原请求",exact:true}).waitFor();
 await directory(p).click();await qa(p).click();await p.getByRole("button",{name:"确认原请求",exact:true}).click();await p.getByRole("button",{name:"用原编号重新提交"}).waitFor();assert.equal(ids.length,1);
 await p.reload();await showQa(p);await p.getByRole("button",{name:"确认原请求",exact:true}).click();await p.getByRole("button",{name:"用原编号重新提交"}).click();await until(()=>ids.length===2);assert.equal(ids[0],ids[1]);return{sameId:true,posts:2};
});
await check("Switching tabs never aborts an in-flight question",async p=>{
 let release;const gate=new Promise(r=>release=r);let posts=0;await p.route("**/api/videos/"+video.id+"/questions",async r=>{posts++;const payload=r.request().postDataJSON();await gate;await r.fulfill({status:200,contentType:"application/json",body:JSON.stringify({data:{...turn,requestId:payload.requestId,question:payload.question}})}).catch(()=>{});});
 try{await open(p);await showQa(p);await p.locator("#course-question").fill("异步等待中切换页签");await p.getByRole("button",{name:"提问",exact:true}).click();await until(()=>posts===1);
 await directory(p).click();await qa(p).click();assert.equal(posts,1);assert.ok(await p.locator(".qa-pending").isVisible());release();await until(()=>p.locator("#course-question").inputValue().then(t=>t===""));assert.equal(posts,1);
 }finally{release();}
});
await check("Reading older answers is not interrupted when a new answer arrives",async p=>{
 let changed=false;const fresh={...turn,id:999001,requestId:"layout-new-answer-mocked",question:"模拟新增答案"};
 await p.route("**/api/videos/"+video.id+"/questions?*",r=>r.fulfill({status:200,contentType:"application/json",body:JSON.stringify({data:changed?{...history,items:[fresh,...history.items],total:history.total+1}:history})}));
 await open(p);await showQa(p);await p.locator(".qa-history").evaluate(e=>e.scrollTop=200);await sleep(200);changed=true;
 await p.getByRole("button",{name:"刷新问答记录"}).click();await p.locator(".qa-new-answer").waitFor();assert.ok(await p.locator(".qa-history").evaluate(e=>Math.abs(e.scrollTop-200)<3));
 await p.locator(".qa-new-answer").click();await until(()=>p.locator(".qa-history").evaluate(e=>e.scrollHeight-e.clientHeight-e.scrollTop<3));
});
await check("Per-video drafts do not leak when switching lessons",async p=>{
 await p.goto(base+"/#/courses/8?video=28");await p.locator(".subtitle-line").first().waitFor();await showQa(p);await p.locator("#course-question").fill("标准视频草稿");
 await directory(p).click();await p.getByRole("button",{name:"播放视频：二分查找讲解（延迟音轨）",exact:true}).click();await qa(p).click();await p.locator("#course-question").waitFor();
 assert.equal(await p.locator("#course-question").inputValue(),"");assert.ok((await p.locator(".study-current").innerText()).includes("延迟音轨"));
 await directory(p).click();await p.getByRole("button",{name:"播放视频：二分查找讲解（标准音轨）",exact:true}).click();await qa(p).click();await p.locator("#course-question").waitFor();assert.equal(await p.locator("#course-question").inputValue(),"标准视频草稿");
});
await check("Mobile provides four entries, native modal focus and citation return",async p=>{
 await p.setViewportSize({width:390,height:844});await open(p);await p.evaluate(()=>scrollTo(0,0));assert.ok(await p.locator("video").evaluate(v=>v.getBoundingClientRect().top<innerHeight));
 await p.getByRole("button",{name:"笔记",exact:true}).click();assert.equal(await p.getByRole("tab",{name:/^笔记/}).getAttribute("aria-selected"),"true");
 await p.getByRole("button",{name:/^课程问答/}).click();await p.locator(".qa-turn").first().waitFor();assert.equal(await p.locator(".study-tools-panel").evaluate(e=>e.matches(":modal")),true);
 await until(()=>p.locator(".qa-compose").evaluate(e=>e.getBoundingClientRect().bottom<=innerHeight+1));await p.screenshot({path:out+"/mobile-questions.png"});
 const citation=p.locator("[data-qa-source]").first(),ms=Number(await citation.getAttribute("data-begin-ms"));await citation.click();await until(()=>p.locator(".study-tools-panel").evaluate(e=>!e.open));await until(()=>p.locator("video").evaluate((v,ms)=>Math.abs(v.currentTime-ms/1000)<1.5,ms));await p.locator("video").evaluate(v=>v.pause());
 await p.getByRole("button",{name:"课程目录",exact:true}).click();await directory(p).waitFor();await p.keyboard.press("Escape");await until(()=>p.locator(".study-tools-panel").evaluate(e=>!e.open));assert.equal(await p.getByRole("button",{name:"课程目录",exact:true}).evaluate(e=>e===document.activeElement),true);
 await p.evaluate(()=>scrollTo(0,0));await p.screenshot({path:out+"/mobile-top.png"});
});
await check("Desktop to tablet resize preserves the same question component",async p=>{
 await open(p);await showQa(p);await p.locator("#course-question").fill("跨断点保留草稿");await p.evaluate(()=>window.savedTextarea=document.querySelector("#course-question"));
 await p.setViewportSize({width:1024,height:900});await p.getByRole("button",{name:/^课程问答/}).click();await p.locator("#course-question").waitFor();assert.equal(await p.locator("#course-question").inputValue(),"跨断点保留草稿");
 await p.setViewportSize({width:1440,height:1000});await p.locator("#course-question").waitFor();assert.equal(await p.evaluate(()=>window.savedTextarea===document.querySelector("#course-question")),true);
 assert.equal(await p.locator(".study-tools-panel").evaluate(e=>e.matches(":modal")),false);
});
await check("Upload dialog preserves selection and protects cancel, navigation and refresh",async p=>{
 let release;const gate=new Promise(r=>release=r);let posts=0;await p.route("**/api/courses/"+course.id+"/videos",async r=>{if(r.request().method()!=="POST")return r.fallback();posts++;await gate;await r.fulfill({status:201,contentType:"application/json",body:JSON.stringify({data:video})}).catch(()=>{});});
 try{await open(p);await p.getByRole("button",{name:"添加课程视频",exact:true}).click();await p.getByLabel("选择课程视频").setInputFiles({name:"layout-review.mp4",mimeType:"video/mp4",buffer:Buffer.from("mock upload")});
 await p.getByRole("button",{name:"关闭视频管理"}).click();await p.getByRole("button",{name:"添加课程视频",exact:true}).click();assert.equal(await p.locator("#video-title").inputValue(),"layout-review");await p.getByRole("button",{name:"上传视频",exact:true}).click();await p.locator(".upload-progress").waitFor();
 await p.keyboard.press("Escape");assert.equal(await p.locator("#course-upload-tools").evaluate(e=>e.open),true);assert.equal(await p.getByRole("button",{name:"关闭视频管理"}).isDisabled(),true);assert.equal(await p.locator(".logout-button").isDisabled(),true);
 await p.evaluate(()=>location.hash="/courses/8?video=28");await sleep(250);assert.equal(p.url(),url);
 const dialogs=[];p.on("dialog",async d=>{dialogs.push(d.type());await d.dismiss();});await p.reload({timeout:5000}).catch(()=>{});assert.ok(dialogs.includes("beforeunload"));release();await p.locator(".upload-progress").waitFor({state:"hidden"});if(await p.locator("#course-upload-tools").evaluate(e=>e.open))await p.getByRole("button",{name:"关闭视频管理"}).click();await p.getByRole("link",{name:"CourseTrace 首页"}).click();await p.getByRole("heading",{name:/^我的课程/}).waitFor();assert.equal(posts,1);
 }finally{release();}
});
await check("All responsive widths avoid overflow and keep an accessible tool entry",async p=>{
 await open(p);for(const width of[1920,1440,1280,1180,1024,768,390]){
 await p.setViewportSize({width,height:900});await sleep(120);assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,"width "+width);
 if(width>=1280)assert.equal(await directory(p).isVisible(),true);else assert.equal(await p.getByRole("button",{name:"课程目录",exact:true}).isVisible(),true);
 }return{widths:[1920,1440,1280,1180,1024,768,390]};
});

await check("Short desktop windows use a readable drawer instead of clipping the composer",async p=>{
 await p.setViewportSize({width:1440,height:600});await open(p);await showQa(p);
 await until(()=>p.locator(".study-tools-panel").evaluate(e=>e.matches(":modal")));
 const results=[];
 for(const mode of ["RAG","AGENT"]){
  await p.locator("#qa-mode").selectOption(mode);
  const sizes=await p.evaluate(()=>{const rect=s=>document.querySelector(s).getBoundingClientRect();return{composerBottom:rect(".qa-compose").bottom,submitBottom:rect(".qa-form button").bottom,historyHeight:rect(".qa-history").height,viewport:innerHeight};});
  assert.ok(sizes.composerBottom<=601);assert.ok(sizes.submitBottom<=601);assert.ok(sizes.historyHeight>50);
  results.push({mode,...sizes});
 }
 return results;
});

assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.unexpectedPosts,[]);if(report.checks.some(c=>c.status!=="passed"))process.exitCode=1;
}finally{save();await browser.close();console.log("Saved "+out+"/verification.json");}
