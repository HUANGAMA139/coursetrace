import assert from "node:assert/strict";
import {base,runId,read,save,wait,pause,playwright,ps,sql,get,post,login,checkWriter} from "./m7-support.mjs";
const manifest=read("manifest.json");assert.equal(manifest?.flowStatus,"passed","Finish Flow before Fault");
const report={runId,observedAt:new Date().toISOString(),checks:[],type:"new real service fault and restart"},check=checkWriter(report,"recovery-verification.json");
const {chromium}=await playwright(),browser=await chromium.launch({channel:"msedge",headless:true}),context=await browser.newContext();
let stopped=false;
const counters=()=>sql("SELECT COUNT(*),COALESCE(SUM(chat_requests),0),COALESCE(SUM(embedding_requests),0) FROM chat_turn WHERE video_id="+manifest.video.id);
try{
 await login(context);
 await check("New admitted Agent tool records a real Qdrant outage and restores the dependency",async()=>{
  const id="m7-fault-"+runId;const old=await context.request.get(base+"/api/videos/"+manifest.video.id+"/questions/request/"+id);
  if(old.status()===200){report.execution="replay of previously admitted failure";report.failureTurn=(await old.json()).data;}
  else{
   assert.equal(old.status(),404);
   for(;;){const recent=(await get(context,"/api/videos/"+manifest.video.id+"/questions")).items.filter(t=>Date.parse(t.createdAt)>Date.now()-61000);if(recent.length<6)break;await pause(1000);}
   const pending=post(context,"/api/videos/"+manifest.video.id+"/questions",{requestId:id,question:"请搜索课程中的二分查找有序条件，并根据课程解释为什么成立。",mode:"AGENT"});
   await wait(()=>sql("SELECT COUNT(*) FROM agent_step s JOIN agent_run r ON r.id=s.run_id JOIN chat_turn t ON t.id=r.turn_id WHERE t.request_id='"+id+"' AND s.kind='MODEL' AND s.status='STARTED'")==="1",15000);
   report.stoppedAt=new Date().toISOString();ps("docker.ps1","stop","qdrant");stopped=true;
   try{const r=await pending;assert.equal(r.status(),200);report.failureTurn=(await r.json()).data;}
   finally{ps("docker.ps1","up","-d","--wait","qdrant");stopped=false;report.restoredAt=new Date().toISOString();}
   report.execution="fresh admitted call; actual container stopped";
  }
  const turn=report.failureTurn;report.failureTrace=await get(context,"/api/videos/"+manifest.video.id+"/questions/"+turn.id+"/agent-run");
  assert.equal(turn.status,"FAILED",JSON.stringify(turn));assert.equal(report.failureTrace.stopReason,"QDRANT_UNAVAILABLE");assert.ok(report.failureTrace.steps.some(s=>s.kind==="TOOL"&&s.status==="FAILED"));
  assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+manifest.video.id),"1");
 });
 await check("IDEA same launcher Stop/Start preserves new-flow results without replaying AI",async()=>{
  const before=counters();const beforeIds=manifest.segments.map(s=>s.id);
  ps("stop-dev.ps1");ps("test-m7-idea-start.ps1","-RunId",runId);
  await login(context);const vid=manifest.video.id;
  const transcript=await get(context,"/api/videos/"+vid+"/transcript"),segments=(await get(context,"/api/videos/"+vid+"/segments?size=100")).items;
  assert.equal(transcript.version,manifest.transcript.version);assert.deepEqual(segments.map(s=>s.id),beforeIds);
  assert.equal((await get(context,"/api/videos/"+vid+"/notes")).complete,true);
  assert.equal((await get(context,"/api/videos/"+vid+"/search-index")).ready,true);
  const q=manifest.questions.context;assert.equal((await get(context,"/api/videos/"+vid+"/questions/"+q.id)).status,"SUCCEEDED");
  assert.ok((await get(context,"/api/videos/"+vid+"/questions/"+q.id+"/agent-run")).steps.some(s=>s.name==="readSurroundingContext"));
  assert.equal(counters(),before);assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+vid),"1");
  report.restartCounters={before,after:counters()};report.schema=sql("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank");
 });
 report.status="passed";save("recovery-verification.json",report);
}finally{if(stopped)ps("docker.ps1","up","-d","--wait","qdrant");save("recovery-verification.json",report);await browser.close();}
