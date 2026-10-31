import assert from "node:assert/strict";
import {readFileSync,writeFileSync,mkdirSync,existsSync} from "node:fs";
import {resolve,dirname,join} from "node:path";
import {fileURLToPath,pathToFileURL} from "node:url";
import {execFileSync} from "node:child_process";
export const root=resolve(dirname(fileURLToPath(import.meta.url)),".."),base=process.env.COURSETRACE_WEB_URL||"http://127.0.0.1:5173";
export const runId=process.env.COURSETRACE_M7_RUN_ID;
assert.ok(runId&&/^[a-zA-Z0-9-]{1,48}$/.test(runId),"Set a safe COURSETRACE_M7_RUN_ID");
export const out=join(root,".local/m7",runId);mkdirSync(out,{recursive:true});
export const read=(name,fallback)=>existsSync(join(out,name))?JSON.parse(readFileSync(join(out,name),"utf8")):fallback;
export function save(name,value){writeFileSync(join(out,name),JSON.stringify(value,null,2));}
export const pause=ms=>new Promise(r=>setTimeout(r,ms));
export async function wait(action,timeout=240000){const until=Date.now()+timeout;while(Date.now()<until){const value=await action();if(value)return value;await pause(800);}throw new Error("M7 condition timed out");}
export async function playwright(){const module=process.env.COURSETRACE_PLAYWRIGHT_MODULE||join(process.env.USERPROFILE,".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs");return import(pathToFileURL(module).href);}
export function ps(script,...args){return execFileSync("C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",["-NoProfile","-ExecutionPolicy","Bypass","-File",join(root,"scripts",script),...args],{cwd:root,encoding:"utf8",windowsHide:true,timeout:120000,stdio:script.includes("idea-start")?["ignore","ignore","ignore"]:["pipe","pipe","pipe"]});}
export function sql(query){return ps("docker.ps1","exec","-T","mysql","sh","-c","echo "+Buffer.from(query).toString("base64")+" | base64 -d | MYSQL_PWD=$MYSQL_PASSWORD mysql --default-character-set=utf8mb4 -u$MYSQL_USER $MYSQL_DATABASE -NB").trim();}
export async function get(context,path){const r=await context.request.get(base+path,{timeout:20000});assert.equal(r.status(),200,"GET "+path+": "+r.status());return(await r.json()).data;}
export async function post(context,path,data,timeout=155000){const token=await get(context,"/api/auth/csrf");return context.request.post(base+path,{timeout,headers:{[token.headerName]:token.token},...(data===undefined?{}:{data})});}
export async function login(context,name="student"){const token=await get(context,"/api/auth/csrf");const r=await context.request.post(base+"/api/auth/login",{headers:{[token.headerName]:token.token},form:{username:name,password:"CourseTrace@123"}});assert.equal(r.status(),200);return(await r.json()).data;}
export async function select(page,course,video,questions=false){await page.goto(base+"/#/courses/"+course+"?video="+video);await page.locator("video").waitFor();if(questions){if(page.viewportSize().width>=1280&&page.viewportSize().height>=720)await page.getByRole("tab",{name:/^课程问答/}).click();else await page.getByRole("button",{name:/^课程问答/}).click();await page.getByRole("heading",{name:"课程问答",exact:true}).waitFor();}}
export async function seek(page,locator){
 await page.locator("video").evaluate(v=>{v.pause();window.__m7Seek=[];for(const type of ["seeking","seeked"])v.addEventListener(type,()=>window.__m7Seek.push({type,time:v.currentTime}));});
 const ms=Number(await locator.getAttribute("data-begin-ms"));await locator.click();
 await page.waitForFunction(at=>window.__m7Seek?.some(e=>e.type==="seeked"&&Math.abs(e.time-at/1000)<.2),ms,{timeout:15000});
 await page.locator("video").evaluate(v=>v.pause());return{ms,events:await page.evaluate(()=>window.__m7Seek)};
}
export function checkWriter(report,name){return async(label,action)=>{const start=Date.now();try{await action();report.checks.push({name:label,status:"passed",elapsedMs:Date.now()-start,observedAt:new Date().toISOString()});console.log("PASS "+label);save(name,report);}catch(e){report.checks.push({name:label,status:"failed",message:e.message,elapsedMs:Date.now()-start});save(name,report);throw e;}};}
