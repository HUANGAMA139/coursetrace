import assert from "node:assert/strict";
import {readFileSync,writeFileSync,existsSync} from "node:fs";
import {resolve,dirname,join} from "node:path";
import {fileURLToPath} from "node:url";
import {execFileSync} from "node:child_process";
import {createHash} from "node:crypto";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."),out=join(root,".local/m5");
const browser=JSON.parse(readFileSync(join(out,"browser-verification.json"),"utf8"));
assert.equal(browser.status,"passed","Complete browser fixture acceptance first");
const local=Object.fromEntries(readFileSync(join(root,".env.local"),"utf8").split(/\r?\n/).filter(s=>/^[A-Z0-9_]+=/.test(s)).map(s=>{const at=s.indexOf("=");return[s.slice(0,at),s.slice(at+1).replace(/^["']|["']$/g,"")];}));
const setting=(name,fallback)=>process.env[name]||local[name]||fallback;
const key=setting("DASHSCOPE_API_KEY",""),base=setting("QWEN_BASE_URL","https://dashscope.aliyuncs.com/compatible-mode/v1").replace(/\/$/,""),qdrant=setting("QDRANT_URL","http://127.0.0.1:6333");
assert.ok(key,"Model key must be configured");
const endpoint=new URL(base);assert.equal(endpoint.protocol,"https:");assert.ok(endpoint.hostname==="dashscope.aliyuncs.com"||endpoint.hostname.endsWith(".maas.aliyuncs.com"));
function sql(query){
 return execFileSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-ExecutionPolicy","Bypass","-File",join(root,"scripts/docker.ps1"),"exec","-T","mysql","sh","-c","echo "+Buffer.from(query).toString("base64")+" | base64 -d | MYSQL_PWD=$MYSQL_PASSWORD mysql --default-character-set=utf8mb4 -u$MYSQL_USER $MYSQL_DATABASE -NB"],{cwd:root,encoding:"utf8",windowsHide:true,timeout:30000}).trim();
}
const id=browser.lessonIndex.activeIndexId,video=browser.fixtures.lesson.id;
const row=sql("SELECT owner_id,course_id,video_id,transcript_artifact_id,config_json FROM video_search_index WHERE id="+id+" AND video_id="+video).split("\t");assert.equal(row.length,5);
const config=JSON.parse(row[4]),scope={owner_id:Number(row[0]),course_id:Number(row[1]),video_id:Number(row[2]),transcript_artifact_id:Number(row[3]),index_id:id};
const mapping=new Map(sql("SELECT c.id,GROUP_CONCAT(r.segment_id ORDER BY r.ordinal) FROM subtitle_chunk c JOIN subtitle_chunk_segment r ON c.id=r.chunk_id WHERE c.index_id="+id+" GROUP BY c.id").split(/\r?\n/).map(line=>{const[c,ids]=line.split("\t");return[Number(c),ids.split(",").map(Number)];}));
const cases=browser.evaluationCases;
const fingerprint=createHash("sha256").update(JSON.stringify([config.embeddingModel,config.dimensions,base,cases.map(c=>c.question)])).digest("hex"),cachePath=join(out,"retrieval-query-vectors.json");
let cache=existsSync(cachePath)?JSON.parse(readFileSync(cachePath,"utf8")):null;
if(!cache||cache.fingerprint!==fingerprint){
 cache={fingerprint,submissionIntentAt:new Date().toISOString()};writeFileSync(cachePath,JSON.stringify(cache));
 const response=await fetch(base+"/embeddings",{method:"POST",headers:{"Content-Type":"application/json",Authorization:"Bearer "+key},body:JSON.stringify({model:config.embeddingModel,dimensions:config.dimensions,input:cases.map(c=>c.question),encoding_format:"float"}),signal:AbortSignal.timeout(45000)});
 if(!response.ok)throw new Error("Embedding evaluation request failed with HTTP "+response.status()+"; inspect state before retrying");
 const result=await response.json();assert.equal(result.data.length,cases.length);const vectors=Array(cases.length);
 for(const item of result.data){assert.ok(Number.isInteger(item.index)&&item.index>=0&&item.index<cases.length&&!vectors[item.index]);assert.equal(item.embedding.length,1024);assert.ok(item.embedding.every(Number.isFinite));vectors[item.index]=item.embedding;}
 cache.vectors=vectors;cache.usage=result.usage;writeFileSync(cachePath,JSON.stringify(cache));
}else assert.ok(cache.vectors,"A prior evaluation request has uncertain outcome; do not automatically resubmit");
const anchors={
 binary:["二分查找要求数组","数组有序使"],stack:["栈遵循后进先出"],queue:["队列遵循先进先出"],
 bfs:["广度优先搜索使用队列","在无权图中"],hash:["冲突不代表两个键相等","不同的键可能落在同一个桶"],
 index:["通常称为覆盖索引"],transaction:["原子性要求","转账可以"],cache:["过期时间并不等于强一致性"]
};
const normalize=text=>text.replace(/[，。；、\s]/g,"");
const results=[];
for(let n=0;n<cases.length;n++){
 const item=cases[n],response=await fetch(qdrant+"/collections/"+config.collection+"/points/query",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({query:cache.vectors[n],filter:{must:Object.entries(scope).map(([key,value])=>({key,match:{value}}))},limit:3,with_payload:true,with_vector:false}),signal:AbortSignal.timeout(10000)});
 assert.ok(response.ok);const points=(await response.json()).result.points;
 for(const point of points)for(const[field,value]of Object.entries(scope))assert.equal(point.payload[field],value);
 const expected=item.unsupported?[]:browser.lessonSegments.filter(s=>anchors[item.code].some(a=>normalize(s.text).includes(normalize(a)))).map(s=>s.id);
 if(!item.unsupported)assert.ok(expected.length,item.code+" needs a human-labeled source");
 const hits=points.map(point=>({chunkId:point.payload.chunk_id,score:point.score,sourceIds:mapping.get(point.payload.chunk_id)||[]}));
 const top1Hit=hits.length>0&&hits[0].sourceIds.some(s=>expected.includes(s)),top3Hit=hits.some(h=>h.sourceIds.some(s=>expected.includes(s)));
 if(!item.unsupported)assert.equal(top3Hit,true,"No relevant source in top 3 for "+item.code);
 results.push({code:item.code,question:item.question,expectedSourceIds:expected,top1Hit:!item.unsupported?top1Hit:null,top3Hit:!item.unsupported?top3Hit:null,hits});
}
const report={observedAt:new Date().toISOString(),status:"passed",model:config.embeddingModel,dimensions:1024,videoId:video,indexId:id,chunkCount:mapping.size,
 positiveQuestions:results.filter(r=>r.expectedSourceIds.length).length,top1Hits:results.filter(r=>r.top1Hit).length,top3Hits:results.filter(r=>r.top3Hit).length,
 embeddingEvaluationRequests:1,usage:cache.usage,queryScope:Object.keys(scope),results,
 scope:"Eight human-labeled questions on one original synthetic lesson. Three chunks make top-3 coverage a functional check, not an accuracy benchmark. Unsupported questions still retrieve nearest points; evidence sufficiency is checked by grounded QA."};
writeFileSync(join(out,"retrieval-verification.json"),JSON.stringify(report,null,2));
console.log(JSON.stringify({status:report.status,positiveQuestions:report.positiveQuestions,top1Hits:report.top1Hits,top3Hits:report.top3Hits,chunkCount:report.chunkCount}));
