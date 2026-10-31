import assert from "node:assert/strict";
import {readFileSync,existsSync} from "node:fs";
import {join} from "node:path";
import {createHash} from "node:crypto";
import {root,base,out,runId,read,save,wait,pause,playwright,sql,get,post,login,select,seek,checkWriter} from "./m7-support.mjs";
const manifest=read("manifest.json",{runId,createdAt:new Date().toISOString(),kind:"fresh-M7-flow",intents:{}});
const previous=read("flow-verification.json",null);
if(previous?.status==="passed"){
 console.log("Previously verified Flow retained at "+previous.observedAt+"; no new upload or paid calls. Use a new RunId for a fresh run.");process.exit(0);
}
const report={runId,observedAt:new Date().toISOString(),checks:[],questions:[],pageErrors:[],states:[],recordingType:"continuous browser recording, no narration; includes actual waits"};
const check=checkWriter(report,"flow-verification.json"),{chromium}=await playwright();
const browser=await chromium.launch({channel:"msedge",headless:true});
const context=await browser.newContext({viewport:{width:1440,height:1080},locale:"zh-CN",recordVideo:{dir:join(out,"flow-recording"),size:{width:1440,height:1080}}});
const page=await context.newPage(),recording=page.video();page.on("pageerror",e=>report.pageErrors.push(e.message));
const persist=()=>save("manifest.json",manifest);
async function taskDone(id){
 return wait(async()=>{const t=await get(context,"/api/processing-tasks/"+id);const stamp=[id,t.runNo,t.stage,t.status].join(":");
 if(report.states.at(-1)?.stamp!==stamp){report.states.push({stamp,taskId:id,stage:t.stage,status:t.status,at:new Date().toISOString()});save("flow-verification.json",report);}
 if(t.status==="FAILED")throw new Error(t.errorCode+": "+t.errorMessage);return t.status==="SUCCEEDED"?t:null;},360000);
}
async function ownedCourses(keyword){return(await get(context,"/api/courses?size=50&keyword="+encodeURIComponent(keyword))).items;}
async function safeQuestion(item){
 const id="m7-"+createHash("sha256").update(runId+":"+item.id+":"+item.question).digest("hex").slice(0,32);
 let r=await context.request.get(base+"/api/videos/"+manifest.video.id+"/questions/request/"+id);let fresh=false;
 if(r.status()===404){
  assert.ok(!manifest.intents[id],"Earlier POST is uncertain. Reconcile this request manually; no automatic paid resend: "+id);
  // Query the same owner's recent history instead of relaxing the production admission limit.
  for(;;){const recent=(await get(context,"/api/videos/"+manifest.video.id+"/questions")).items.filter(t=>Date.parse(t.createdAt)>Date.now()-61000);
   if(recent.length<6)break;await pause(Math.min(1000,Math.max(100,Date.parse(recent.at(-1).createdAt)+61200-Date.now())));}
  manifest.intents[id]={question:item.question,mode:item.mode,at:new Date().toISOString()};persist();
  r=await post(context,"/api/videos/"+manifest.video.id+"/questions",{requestId:id,question:item.question,mode:item.mode});fresh=true;
 }else assert.equal(r.status(),200);
 assert.ok([200,202].includes(r.status()),"Question "+item.id+": "+r.status()+" "+await r.text());
 let turn=(await r.json()).data;
 if(turn.status==="RUNNING")turn=await wait(async()=>{const t=await get(context,"/api/videos/"+manifest.video.id+"/questions/"+turn.id);return t.status!=="RUNNING"?t:null;},150000);
 const trace=item.mode==="AGENT"?await get(context,"/api/videos/"+manifest.video.id+"/questions/"+turn.id+"/agent-run"):null;
 const observation={case:item,fresh,turn,trace};report.questions.push(observation);save("flow-verification.json",report);
 assert.equal(turn.status,item.expected,"Question "+item.id+": "+JSON.stringify(turn));
 if(turn.status==="SUCCEEDED"){
  assert.equal(turn.answer.status,"ANSWERED");const canonical=new Map(manifest.segments.map(s=>[s.id,s]));
  for(const p of turn.answer.paragraphs){assert.ok(p.citations.length);for(const c of p.citations){
   const s=canonical.get(c.segmentId);assert.ok(s);assert.equal(c.videoId,manifest.video.id);assert.equal(c.transcriptVersion,manifest.transcript.version);assert.equal(c.beginMs,s.beginMs);assert.equal(c.endMs,s.endMs);assert.ok(s.text.substring(c.startOffset,c.endOffset).startsWith(c.quote));
  }}
  observation.anchorPresentInCitedText=item.anchors.some(a=>turn.answer.paragraphs.some(p=>p.citations.some(c=>canonical.get(c.segmentId).text.includes(a))));
 }
 if(trace){
  assert.ok(trace.rounds<=4&&trace.toolCalls<=4&&trace.searchCalls<=2&&trace.contextCalls<=2&&trace.steps.filter(s=>s.kind==="MODEL").length<=5);
  if(item.requireRead){assert.ok(trace.steps.some(s=>s.name==="searchSegments"&&s.status==="SUCCEEDED"));assert.ok(trace.steps.some(s=>s.name==="readSurroundingContext"&&s.status==="SUCCEEDED"));
   const seen=new Set();observation.newContextSources=[];
   for(const step of trace.steps.filter(s=>s.kind==="TOOL")){const ids=step.result?.sources?.map(s=>s.segmentId)||[];if(step.name==="readSurroundingContext")observation.newContextSources.push(ids.filter(id=>!seen.has(id)));ids.forEach(id=>seen.add(id));}
   assert.ok(observation.newContextSources.some(ids=>ids.length>0),"Explicit context reading must add actual sources");
  }
 }
 manifest.questions??={};manifest.questions[item.id]={id:turn.id,requestId:id,question:item.question,mode:item.mode,status:turn.status};persist();save("flow-verification.json",report);
 return observation;
}
try{
 await page.goto(base);await login(context);await page.reload();
 await check("Fresh owned course and actual MP4 upload with dynamic IDs",async()=>{
  const title="M7 完整流程验收 "+runId;
  let course=manifest.course||(await ownedCourses(title)).find(c=>c.title===title);
  if(!course){manifest.intents.course=true;persist();const r=await post(context,"/api/courses",{title,description:"自写文本与 Windows 合成语音；本次新视频完整链路验收"});assert.equal(r.status(),201);course=(await r.json()).data;}
  manifest.course=course;persist();await page.goto(base+"/#/courses/"+course.id);
  let video=manifest.video;
  if(!video)video=(await get(context,"/api/courses/"+course.id+"/videos?size=50")).items.find(v=>v.title==="有序查找与循环队列（合成语音）");
  if(!video){
   const sample=join(root,".local/m7/sample-course.mp4");assert.ok(existsSync(sample));const start=Date.now();
   manifest.intents.upload=true;persist();const token=await get(context,"/api/auth/csrf");
   const r=await context.request.post(base+"/api/courses/"+course.id+"/videos",{timeout:120000,headers:{[token.headerName]:token.token},multipart:{title:"有序查找与循环队列（合成语音）",file:{name:"course.mp4",mimeType:"video/mp4",buffer:readFileSync(sample)}}});
   assert.equal(r.status(),201);video=(await r.json()).data;manifest.uploadElapsedMs=Date.now()-start;manifest.uploadWasFresh=true;
  }
  manifest.video=video;manifest.sample=JSON.parse(readFileSync(join(root,".local/m7/sample.json"),"utf8"));persist();
  await select(page,course.id,video.id);await page.locator("video").evaluate(v=>v.play());
  await wait(()=>page.locator("video").evaluate(v=>v.currentTime>.5),10000);await page.locator("video").evaluate(v=>v.pause());
  if(!previous?.checks?.some(c=>c.name==="Fresh owned course and actual MP4 upload with dynamic IDs"&&c.status==="passed"))assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+video.id),"0","Fresh flow must not start from an old ASR result");
 });
 await check("New Kafka FFmpeg ASR transcript and timestamped notes",async()=>{
  const existing=await get(context,"/api/courses/"+manifest.course.id+"/processing-tasks?pipelineVersion=2&videoIds="+manifest.video.id);
  const start=Date.now();
  if(!existing.length){
   const pending=page.waitForResponse(r=>r.url().endsWith("/api/videos/"+manifest.video.id+"/processing-tasks")&&r.request().method()==="POST");
   await page.getByRole("button",{name:"生成字幕与笔记",exact:true}).click();const r=await pending;assert.equal(r.status(),202);manifest.learningTaskId=(await r.json()).data.id;manifest.taskSubmitElapsedMs=Date.now()-start;persist();
  }else manifest.learningTaskId=existing[0].id;
  report.learningTask=await taskDone(manifest.learningTaskId);manifest.learningElapsedMs=Date.now()-start;
  manifest.transcript=await get(context,"/api/videos/"+manifest.video.id+"/transcript");manifest.segments=(await get(context,"/api/videos/"+manifest.video.id+"/segments?size=100")).items;manifest.notes=await get(context,"/api/videos/"+manifest.video.id+"/notes");persist();
  assert.equal(manifest.transcript.ready,true);assert.equal(manifest.notes.complete,true);assert.ok(manifest.segments.length>=10);assert.ok(manifest.notes.items.length);
  for(const s of manifest.segments){assert.ok(s.beginMs>=0&&s.endMs<=manifest.video.durationMs+200&&s.endMs>s.beginMs);}
  const canonical=new Map(manifest.segments.map(s=>[s.id,s]));for(const n of manifest.notes.items)for(const c of n.sources){assert.ok(canonical.has(c.segmentId));assert.equal(c.beginMs,canonical.get(c.segmentId).beginMs);}
  assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+manifest.video.id),"1");assert.equal(sql("SELECT status FROM asr_job WHERE video_id="+manifest.video.id),"SUCCEEDED");
 });
 await check("Subtitle and note references seek the actual new video",async()=>{
  await page.reload();await page.locator('.learning-state[data-learning-status="SUCCEEDED"]').waitFor();
  await wait(()=>page.locator("video").evaluate(v=>v.textTracks?.[0]?.cues?.length>0),15000);
  const s=manifest.segments[3];await page.locator("video").evaluate(v=>{v.pause();window.__subtitleSeek=[];v.addEventListener("seeked",()=>window.__subtitleSeek.push(v.currentTime));});
  await page.locator('[data-segment-id="'+s.id+'"]').click();await wait(()=>page.evaluate(at=>window.__subtitleSeek.some(t=>Math.abs(t-at/1000)<.2),s.beginMs),10000);
  report.subtitleSeek={segmentId:s.id,ms:s.beginMs,events:await page.evaluate(()=>window.__subtitleSeek)};
  await page.locator("video").evaluate(v=>v.pause());await page.getByRole("tab",{name:/^笔记/}).click();await page.locator(".note-source-details summary").first().click();
  const c=manifest.notes.items[0].sources[0];await page.locator('[data-source-segment="'+c.segmentId+'"]').first().click();await wait(()=>page.locator("video").evaluate((v,at)=>Math.abs(v.currentTime-at/1000)<1,c.beginMs),10000);await page.locator("video").evaluate(v=>v.pause());
 });
 await check("New subtitle chunks Embedding and Qdrant index through Kafka",async()=>{
  await select(page,manifest.course.id,manifest.video.id,true);const start=Date.now();
  const r=await post(context,"/api/videos/"+manifest.video.id+"/search-index");assert.equal(r.status(),202);manifest.indexTaskId=(await r.json()).data.id;persist();
  report.indexTask=await taskDone(manifest.indexTaskId);manifest.indexElapsedMs=Date.now()-start;manifest.index=await get(context,"/api/videos/"+manifest.video.id+"/search-index");persist();assert.equal(manifest.index.ready,true);
  assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+manifest.video.id),"1");
 });
 const cases=JSON.parse(readFileSync(join(root,"docs/demo/questions.json"),"utf8"));
 for(const item of cases)await check("Question "+item.id+" ("+item.mode+", new or explicitly replayed request)",()=>safeQuestion(item));
 await check("Question and Agent process references, reload and mobile layout",async()=>{
  await page.reload();await page.getByRole("tab",{name:/^课程问答/}).click();
  const ordinary=manifest.questions.sorted,deep=manifest.questions.context;
  const turn=page.locator('[data-turn-id="'+ordinary.id+'"]');await turn.waitFor();report.answerSeek=await seek(page,turn.locator("[data-qa-source]").first());
  const agent=page.locator('[data-turn-id="'+deep.id+'"]');await agent.getByText("查看处理过程",{exact:true}).click();await agent.locator('[data-tool-name="readSurroundingContext"]').waitFor();
  report.agentSeek=await seek(page,agent.locator("[data-agent-source]").first());await page.screenshot({path:join(out,"flow-desktop.png")});
  await page.setViewportSize({width:390,height:844});await page.getByRole("button",{name:/^课程问答/}).click();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));await page.screenshot({path:join(out,"flow-mobile.png")});
 });
 await check("Ownership CSRF and request replay do not add paid calls",async()=>{
  const bob=await browser.newContext();await login(bob,"student2");const vid=manifest.video.id;
  for(const path of ["/api/videos/"+vid+"/content","/api/videos/"+vid+"/segments","/api/videos/"+vid+"/questions/"+manifest.questions.context.id+"/agent-run"])assert.equal((await bob.request.get(base+path)).status(),404);
  const anon=await browser.newContext();assert.equal((await anon.request.get(base+"/api/videos/"+vid+"/questions")).status(),401);
  assert.equal((await context.request.post(base+"/api/videos/"+vid+"/questions",{data:{requestId:"m7-no-csrf-request-000",question:"二分",mode:"RAG"}})).status(),403);
  const q=manifest.questions.context,before=sql("SELECT embedding_requests,chat_requests FROM chat_turn WHERE id="+q.id);
  const repeat=await post(context,"/api/videos/"+vid+"/questions",{requestId:q.requestId,question:q.question,mode:q.mode});assert.equal((await repeat.json()).data.id,q.id);
  assert.equal(sql("SELECT embedding_requests,chat_requests FROM chat_turn WHERE id="+q.id),before);await bob.close();await anon.close();
 });
 await check("Existing multi-topic course is discovered dynamically for the demonstration",async()=>{
  const c=(await ownedCourses("M5 算法与数据存储验证课程")).find(c=>c.title==="M5 算法与数据存储验证课程");assert.ok(c,"Prepare a multi-topic course before Demo");
  const v=(await get(context,"/api/courses/"+c.id+"/videos?size=50")).items.find(v=>v.title==="算法与数据存储基础（合成语音）");assert.ok(v);
  const index=await get(context,"/api/videos/"+v.id+"/search-index");assert.equal(index.ready,true);assert.ok(index.chunkCount>=3);
  const history=await get(context,"/api/videos/"+v.id+"/questions");manifest.preparedDemo={course:c,video:v,index,kind:"previously processed data, checked this run",agentTurn:history.items.find(t=>t.mode==="AGENT"&&t.status==="SUCCEEDED"),failureTurn:history.items.find(t=>t.mode==="AGENT"&&t.status==="FAILED")};persist();
 });
 assert.deepEqual(report.pageErrors,[]);report.status="passed";manifest.flowStatus="passed";manifest.finishedAt=new Date().toISOString();persist();
 report.counters=sql("SELECT COUNT(*),COALESCE(SUM(chat_requests),0),COALESCE(SUM(embedding_requests),0) FROM chat_turn WHERE video_id="+manifest.video.id);
 report.artifactCounters=sql("SELECT kind,COUNT(*),SUM(request_count) FROM processing_artifact WHERE video_id="+manifest.video.id+" GROUP BY kind");
 report.timingNote="First-run service timings are in task steps; total learning time includes external polling waits. Replays are labeled separately.";
}finally{
 await context.close();report.recordingPath=await recording.path();save("flow-verification.json",report);await browser.close();
}
