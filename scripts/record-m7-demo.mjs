import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {join} from "node:path";
import {runId,root,base,out,read,save,pause,playwright,get,select,seek,sql} from "./m7-support.mjs";
const manifest=read("manifest.json"),recovery=read("recovery-verification.json"),refined=read("refinement-verification.json");
assert.equal(manifest?.flowStatus,"passed");assert.equal(recovery?.status,"passed");assert.equal(refined?.status,"passed");
const chapters=JSON.parse(readFileSync(join(root,"docs/demo/narration.json"),"utf8"));
const {chromium}=await playwright(),browser=await chromium.launch({channel:"msedge",headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1000},locale:"zh-CN",recordVideo:{dir:join(out,"demo-recording"),size:{width:1440,height:1000}}});
const page=await context.newPage(),video=page.video(),report={runId,observedAt:new Date().toISOString(),chapters:[],newQuestionPosts:[],pageErrors:[],kind:"actual browser recording; previously processed data is explicitly labeled"};
page.on("pageerror",e=>report.pageErrors.push(e.message));
page.on("request",r=>{if(r.method()==="POST"&&/\/questions$|\/processing-tasks$|\/search-index$/.test(new URL(r.url()).pathname))report.newQuestionPosts.push(r.url());});
const before=sql("SELECT COUNT(*),SUM(chat_requests),SUM(embedding_requests) FROM chat_turn WHERE video_id="+manifest.video.id);
await page.addInitScript(()=>{
 const install=()=>{if(document.getElementById("m7-recording-caption"))return;const note=document.createElement("div");note.id="m7-recording-caption";
 note.style.cssText="position:fixed;z-index:2147483647;bottom:12px;left:24px;max-width:720px;padding:12px 18px;background:rgba(24,56,42,.94);color:white;border-radius:12px;font:16px/1.7 sans-serif;pointer-events:none;box-shadow:0 3px 16px #0003";
 note.textContent="CourseTrace · 实际浏览器演示 · 数据与合成语音均已标注";document.body.append(note);};
 if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",install);else install();
});
async function caption(text){await page.evaluate(text=>{document.getElementById("m7-recording-caption").textContent=text;},text);}
async function chapter(index,action){
 const config=chapters[index],start=Date.now();await caption((index+1)+"/7 "+config.title+"｜"+config.text);
 report.currentChapter=index+1;save("demo-progress.json",{...report,status:"recording"});
 await action();await caption((index+1)+"/7 "+config.title+"｜"+config.text);
 while(Date.now()-start<config.seconds*1000)await pause(1000);
 report.chapters.push({index:index+1,title:config.title,plannedSeconds:config.seconds,actualSeconds:(Date.now()-start)/1000});save("demo-progress.json",{...report,status:"recording"});
 console.log("RECORDED "+config.title);
}
try{
 await page.goto(base);
 await chapter(0,async()=>{await page.getByLabel("账号",{exact:true}).fill("student");await page.getByLabel("密码",{exact:true}).fill("CourseTrace@123");});
 await chapter(1,async()=>{
  await page.getByRole("button",{name:"进入学习空间"}).click();await page.getByRole("heading",{name:/^我的课程/}).waitFor();
  const search=page.getByPlaceholder(/搜索/);if(await search.count())await search.fill(manifest.course.title);
  await pause(3000);await select(page,manifest.course.id,manifest.video.id);
 });
 await chapter(2,async()=>{
  await page.getByRole("button",{name:"资源检查与处理详情",exact:true}).click();
  await page.getByRole("heading",{name:"视频资源检查",exact:true}).waitFor();await pause(12000);
  await page.getByRole("button",{name:"关闭视频管理",exact:true}).click();
  await page.getByRole("list",{name:"学习资料处理阶段"}).scrollIntoViewIfNeeded();await pause(5000);await page.locator("video").scrollIntoViewIfNeeded();
  await page.locator("video").evaluate(v=>v.play());await pause(12000);await page.locator("video").evaluate(v=>v.pause());
 });
 await chapter(3,async()=>{
  await page.getByRole("tab",{name:/^字幕/}).click();const s=manifest.segments[3];await page.locator('[data-segment-id="'+s.id+'"]').click();await pause(4000);await page.locator("video").evaluate(v=>v.pause());
  await page.getByRole("tab",{name:/^笔记/}).click();await page.locator(".note-source-details summary").first().click();await pause(7000);
  await select(page,manifest.preparedDemo.course.id,manifest.preparedDemo.video.id);await page.locator("video").evaluate(v=>v.play());await pause(6000);await page.locator("video").evaluate(v=>v.pause());
 });
 await chapter(4,async()=>{
  await select(page,manifest.course.id,manifest.video.id,true);const id=manifest.refinements.count.id;
  const turn=page.locator('[data-turn-id="'+id+'"]');await turn.waitFor();await turn.scrollIntoViewIfNeeded();await pause(10000);
  report.answerSeek=await seek(page,turn.locator("[data-qa-source]").first());await pause(10000);await select(page,manifest.course.id,manifest.video.id,true);
  await page.locator('[data-turn-id="'+id+'"]').scrollIntoViewIfNeeded();
 });
 await chapter(5,async()=>{
  const turn=page.locator('[data-turn-id="'+manifest.refinements.context.id+'"]');await turn.getByText("查看处理过程",{exact:true}).click();await turn.locator('[data-tool-name="readSurroundingContext"]').waitFor();
  await turn.locator(".agent-trace").scrollIntoViewIfNeeded();await pause(12000);report.agentSeek=await seek(page,turn.locator("[data-agent-source]").first());
  await select(page,manifest.course.id,manifest.video.id,true);const trace=page.locator('[data-turn-id="'+manifest.refinements.context.id+'"] .agent-trace');await trace.scrollIntoViewIfNeeded();
  await page.screenshot({path:join(out,"demo-agent.png")});
 });
 await chapter(6,async()=>{
  const failure=page.locator('[data-turn-id="'+recovery.failureTurn.id+'"]');await failure.getByText("查看处理过程",{exact:true}).click();await failure.locator('[data-tool-name="searchSegments"]').waitFor();await failure.scrollIntoViewIfNeeded();await pause(12000);
  await page.reload();await page.getByRole("tab",{name:/^课程问答/}).click();await page.locator('[data-turn-id="'+manifest.refinements.context.id+'"]').waitFor();
  await page.screenshot({path:join(out,"demo-final.png")});
 });
 assert.deepEqual(report.newQuestionPosts,[]);assert.deepEqual(report.pageErrors,[]);
 assert.equal(sql("SELECT COUNT(*),SUM(chat_requests),SUM(embedding_requests) FROM chat_turn WHERE video_id="+manifest.video.id),before);
 report.counterBefore=before;report.counterAfter=before;report.status="passed";
}finally{
 await context.close();report.webm=await video.path();save("demo-verification.json",report);save("demo-progress.json",report);await browser.close();
}
