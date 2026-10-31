import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {runId,read,save,pause,playwright,get,post,login,sql} from "./m7-support.mjs";
const manifest=read("manifest.json");assert.equal(manifest?.flowStatus,"passed");
const {chromium}=await playwright(),browser=await chromium.launch({channel:"msedge",headless:true}),context=await browser.newContext();
const report={runId,observedAt:new Date().toISOString(),promptRevision:"course-qa-v1-m7-grounded",checks:[],questions:[],purpose:"Targeted real recheck after an observed grounding/verbosity issue; original ten-case results are retained"};
try{
 await login(context);
 for(const key of ["count","context"]){
  const original=read("flow-verification.json").questions.find(q=>q.case.id===key),item=original.case;
  const requestId="m7-fix-"+createHash("sha256").update(runId+":"+key+":"+report.promptRevision).digest("hex").slice(0,28);
  const prior=await context.request.get("http://127.0.0.1:5173/api/videos/"+manifest.video.id+"/questions/request/"+requestId);
  let turn;
  if(prior.status()===200)turn=(await prior.json()).data;
  else{
   assert.equal(prior.status(),404);
   for(;;){const recent=(await get(context,"/api/videos/"+manifest.video.id+"/questions")).items.filter(t=>Date.parse(t.createdAt)>Date.now()-61000);if(recent.length<6)break;await pause(1000);}
   const result=await post(context,"/api/videos/"+manifest.video.id+"/questions",{requestId,question:item.question,mode:item.mode});assert.equal(result.status(),200);turn=(await result.json()).data;
  }
  assert.equal(turn.status,"SUCCEEDED",JSON.stringify(turn));const texts=turn.answer.paragraphs.map(p=>p.text),length=texts.join("").length;
  assert.ok(texts.length<=2&&length<=440,"Concise answer expected");assert.ok(!/指针.{0,5}重合|阻止.*出队|覆盖未读取/.test(texts.join("")));
  const raw=sql("SELECT response_json FROM chat_turn WHERE id="+turn.id);assert.equal(JSON.parse(raw)._promptRevision,report.promptRevision);
  const trace=item.mode==="AGENT"?await get(context,"/api/videos/"+manifest.video.id+"/questions/"+turn.id+"/agent-run"):null;
  const observation={fresh:prior.status()===404,case:item,originalTurnId:original.turn.id,originalChars:original.turn.answer.paragraphs.map(p=>p.text).join("").length,newChars:length,turn,trace};
  report.questions.push(observation);report.checks.push({name:key+" answer is concise and limited to provided facts",status:"passed"});
  manifest.refinements??={};manifest.refinements[key]={id:turn.id,requestId,mode:item.mode,question:item.question};save("manifest.json",manifest);console.log("PASS "+key+" "+length+" chars");
 }
 assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id="+manifest.video.id),"1");
 report.status="passed";
}finally{save("refinement-verification.json",report);await browser.close();}
