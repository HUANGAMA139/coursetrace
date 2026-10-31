import assert from "node:assert/strict";
import {writeFileSync,mkdirSync,readFileSync} from "node:fs";
import {resolve,dirname,join} from "node:path";
import {pathToFileURL,fileURLToPath} from "node:url";
import {execFileSync} from "node:child_process";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."),out=join(root,".local/m6"),base="http://127.0.0.1:5173";
mkdirSync(out,{recursive:true});
const {chromium}=await import(pathToFileURL(join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs")).href);
const browser=await chromium.launch({channel:"msedge",headless:true});const context=await browser.newContext();
const report={observedAt:new Date().toISOString(),checks:[]};let stopped=false;
function ps(script,...args){return execFileSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-ExecutionPolicy","Bypass","-File",join(root,"scripts",script),...args],{cwd:root,encoding:"utf8",windowsHide:true,stdio:script==="test-m6-idea-start.ps1"?"ignore":["pipe","pipe","pipe"],timeout:100000});}
function sql(query){return ps("docker.ps1","exec","-T","mysql","sh","-c","echo "+Buffer.from(query).toString("base64")+" | base64 -d | MYSQL_PWD=$MYSQL_PASSWORD mysql --default-character-set=utf8mb4 -u$MYSQL_USER $MYSQL_DATABASE -NB").trim();}
async function get(path){const r=await context.request.get(base+path,{timeout:15000});assert.equal(r.status(),200);return(await r.json()).data;}
async function post(path,data){const token=await get("/api/auth/csrf");return context.request.post(base+path,{timeout:150000,headers:{[token.headerName]:token.token},data});}
async function login(){const token=await get("/api/auth/csrf");const r=await context.request.post(base+"/api/auth/login",{headers:{[token.headerName]:token.token},form:{username:"student",password:"CourseTrace@123"}});assert.equal(r.status(),200);}
async function wait(action,ms=15000){const until=Date.now()+ms;while(Date.now()<until){const v=await action();if(v)return v;await new Promise(r=>setTimeout(r,100));}throw new Error("Recovery condition timeout");}
function save(){writeFileSync(join(out,"recovery-verification.json"),JSON.stringify(report,null,2));}
try{
 await login();const asrBefore=sql("SELECT COUNT(*) FROM asr_job WHERE video_id=36");
 const id="m6-real-outage-v1-20261030",question="请在课程中查找数据库索引有哪些维护成本。";
 const old=await context.request.get(base+"/api/videos/36/questions/request/"+id);
 let result;
 if(old.status()===200){result=(await old.json()).data;report.reusedExistingFailure=true;}
 else{
  const pending=post("/api/videos/36/questions",{requestId:id,question,mode:"AGENT"});
  await wait(()=>sql("SELECT COUNT(*) FROM agent_step s JOIN agent_run r ON r.id=s.run_id JOIN chat_turn t ON t.id=r.turn_id WHERE t.request_id='"+id+"' AND s.kind='MODEL' AND s.status='STARTED'")==="1");
  ps("docker.ps1","stop","qdrant");stopped=true;
  try{const response=await pending;assert.equal(response.status(),200);result=(await response.json()).data;}
  finally{ps("docker.ps1","up","-d","--wait","qdrant");stopped=false;}
 }
 report.failureTurn=result;report.failureTrace=await get("/api/videos/36/questions/"+result.id+"/agent-run");
 assert.equal(result.status,"FAILED",JSON.stringify(result));assert.equal(report.failureTrace.stopReason,"QDRANT_UNAVAILABLE");
 assert.ok(report.failureTrace.steps.some(s=>s.kind==="TOOL"&&s.status==="FAILED"));
 report.checks.push({name:"Real Qdrant outage during an admitted Agent tool produces a durable failure",status:"passed"});
 const before=sql("SELECT COUNT(*),COALESCE(SUM(chat_requests),0),COALESCE(SUM(embedding_requests),0) FROM chat_turn WHERE mode='AGENT'");
 const schema=sql("SELECT version,success FROM flyway_schema_history WHERE version='6'");assert.equal(schema,"6\t1");report.migration=schema;
 ps("test-m6-idea-start.ps1");await login();
 const original=JSON.parse(readFileSync(join(out,"browser-verification.json"),"utf8")).questions[0].turn;
 const restored=await get("/api/videos/36/questions/"+original.id),trace=await get("/api/videos/36/questions/"+original.id+"/agent-run");
 assert.equal(restored.status,"SUCCEEDED");assert.ok(trace.steps.some(s=>s.name==="readSurroundingContext"));
 assert.equal(sql("SELECT COUNT(*),COALESCE(SUM(chat_requests),0),COALESCE(SUM(embedding_requests),0) FROM chat_turn WHERE mode='AGENT'"),before);
 assert.equal(sql("SELECT COUNT(*) FROM asr_job WHERE video_id=36"),asrBefore);
 report.checks.push({name:"IDEA restart preserves Agent answers and steps without additional paid calls or ASR",status:"passed"});
 report.status="passed";save();console.log(JSON.stringify({status:report.status,checks:report.checks}));
}finally{if(stopped)ps("docker.ps1","up","-d","--wait","qdrant");save();await browser.close();}
