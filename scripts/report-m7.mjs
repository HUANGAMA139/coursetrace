import assert from "node:assert/strict";
import {readFileSync,existsSync,readdirSync,statSync} from "node:fs";
import {join,relative} from "node:path";
import {createHash} from "node:crypto";
import {execFileSync} from "node:child_process";
import {runId,root,out,read,save,ps,sql} from "./m7-support.mjs";
const requireReport=name=>{const value=read(name);assert.ok(value,"Missing report "+name);assert.ok(value.checks?.length||value.status==="passed","Empty report "+name);if(value.checks)assert.ok(value.checks.every(c=>c.status==="passed"),"Failed check in "+name);if(value.status)assert.equal(value.status,"passed",name);return value;};
const build=requireReport("build-verification.json"),flow=requireReport("flow-verification.json"),fault=requireReport("recovery-verification.json"),refine=requireReport("refinement-verification.json"),demo=requireReport("demo-verification.json"),recordings=requireReport("recordings.json"),idea=requireReport("idea-start-verification.json"),manifest=read("manifest.json");
const media=requireReport("media-verification.json");
assert.equal(build.backend.failures+build.backend.errors,0);assert.equal(flow.questions.length,10);assert.equal(flow.questions.filter(q=>q.fresh).length,10);
const regressions=[];
for(const milestone of [1,2,3,4,5,6]){const r=requireReport("regression-m"+milestone+".json");regressions.push({milestone,groups:r.checks.length,observedAt:r.observedAt,evidence:"executed this M7 run; legacy fixtures and stored AI turns may be reused"});}
for(const name of ["frontend-review","study-layout"]){const r=requireReport(name+".json");regressions.push({milestone:name,groups:r.checks.length,observedAt:r.observedAt,evidence:"browser checks; controlled injection cases are simulations"});}
const probe=join(root,".tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe");
function inspect(path){assert.ok(existsSync(path),path);const metadata=JSON.parse(execFileSync(probe,["-v","error","-show_entries","format=duration:stream=codec_name,codec_type,width,height","-of","json",path],{encoding:"utf8",windowsHide:true}));assert.ok(metadata.streams.some(s=>s.codec_type==="video"));return{path:relative(root,path).replaceAll("\\","/"),bytes:statSync(path).size,...metadata};}
const main=inspect(recordings.demo),raw=inspect(recordings.fullFlow);
assert.ok(Number(main.format.duration)>=360&&Number(main.format.duration)<500);assert.ok(main.streams.some(s=>s.codec_type==="audio"));assert.ok(Number(raw.format.duration)>30);
const hash=path=>createHash("sha256").update(readFileSync(path)).digest("hex");
for(const name of ["coursetrace-demo.mp4","coursetrace-full-flow.mp4"])assert.equal(hash(join(out,name)),media.sha256[name],"Decoded media hash mismatch");
const jar="server/target/coursetrace-server-0.0.1-SNAPSHOT.jar",runtime=".local/m1/server-runtime.jar";
assert.equal(hash(join(root,jar)),hash(join(root,runtime)),"Runtime JAR must match the final build");
const health=await(await fetch("http://127.0.0.1:8080/actuator/health")).json();assert.equal(health.status,"UP");
assert.equal((await fetch("http://127.0.0.1:5173")).status,200);assert.equal((await fetch("http://127.0.0.1:6333/readyz")).status,200);assert.equal((await fetch("http://127.0.0.1:9000/minio/health/ready")).status,200);
const privateData=sql("SELECT COUNT(*),SUM(chat_requests),SUM(embedding_requests) FROM chat_turn WHERE video_id="+manifest.video.id);
const asr=sql("SELECT COUNT(*),SUM(request_count) FROM processing_artifact WHERE kind='ASR_JSON' AND video_id="+manifest.video.id);
const containerJson=ps("docker.ps1","ps","--format","json").trim();const containers=containerJson.startsWith("[")?JSON.parse(containerJson):containerJson.split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line));const rows=Array.isArray(containers)?containers:[containers];
for(const service of ["mysql","minio","kafka","qdrant"]){const row=rows.find(r=>r.Service===service);assert.ok(row&&row.State==="running",service+" must be restored");}
const report={
 runId,observedAt:new Date().toISOString(),status:"passed",
 backend:{...build.backend,passed:build.backend.tests-build.backend.skipped},
 frontend:{status:"passed",log:"frontend-build.log"},
 flow:{groups:flow.checks.length,questions:flow.questions.length,freshQuestions:10,courseId:manifest.course.id,videoId:manifest.video.id,durationMs:manifest.video.durationMs,learningTaskId:manifest.learningTaskId,indexTaskId:manifest.indexTaskId,indexId:manifest.index.activeIndexId,uploadMs:manifest.uploadElapsedMs,submitMs:manifest.taskSubmitElapsedMs,learningMs:manifest.learningElapsedMs,indexMs:manifest.indexElapsedMs,states:flow.states,answerSeek:flow.answerSeek,agentSeek:flow.agentSeek},
 recovery:{groups:fault.checks.length,type:fault.execution,failureTurnId:fault.failureTurn.id,stopReason:fault.failureTrace.stopReason,restartCounters:fault.restartCounters},
 refinements:{groups:refine.checks.length,promptRevision:refine.promptRevision,counts:refine.questions.map(q=>({case:q.case.id,originalChars:q.originalChars,newChars:q.newChars,turnId:q.turn.id}))},
 regressions,idea,mediaVerification:media,manualReviewDocument:"docs/m7-acceptance.md",recordings:{demo:main,fullFlow:raw,narration:recordings.narration,paidCallsDuringDemo:demo.newQuestionPosts.length,chapters:demo.chapters},
 runtime:{backend:health.status,web:200,qdrant:200,jarMatchesBuild:true,sha256:hash(join(root,jar)),containers:rows.filter(r=>["mysql","minio","kafka","qdrant"].includes(r.Service)).map(r=>({service:r.Service,state:r.State,health:r.Health}))},
 callAttempts:{videoOnly:privateData,asrArtifactCountAndReservedAttempts:asr,asrJobs:sql("SELECT COUNT(*),MAX(submission_no),SUM(poll_count) FROM asr_job WHERE video_id="+manifest.video.id),note:"Includes ten initial cases, one real fault and two targeted rechecks; attempts/received usage are not a billing statement."},
 evidenceBoundaries:["Fresh Flow and ten initial questions were executed once this M7 run; two affected answers were rechecked after the final-answer prompt patch.","Legacy AI responses in compatibility regressions may be stored replays.","External-API unknown/forged-citation/budget edge cases use isolated tests or browser injection, not induced paid retries.","Synthetic speech and small sample data do not establish human-lecture accuracy, load capacity or universal prompt-injection resistance."]
};
save("final-verification.json",report);console.log(JSON.stringify({status:report.status,backend:report.backend,flow:report.flow.groups,regressions:regressions.map(r=>[r.milestone,r.groups]),demoSeconds:main.format.duration},null,2));
