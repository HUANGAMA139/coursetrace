import {readFileSync,writeFileSync,existsSync,mkdirSync} from "node:fs";
import {resolve,dirname,join} from "node:path";
import {fileURLToPath} from "node:url";
import {createHash} from "node:crypto";
const root=resolve(dirname(fileURLToPath(import.meta.url)),".."), out=join(root,".local/m4");
mkdirSync(out,{recursive:true});
if(existsSync(join(root,".env.local"))) for(const line of readFileSync(join(root,".env.local"),"utf8").split(/\r?\n/)){
 const m=line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/); if(m && m[2] && !process.env[m[1]]) process.env[m[1]]=m[2].replace(/^["']|["']$/g,"");
}
const key=process.env.DASHSCOPE_API_KEY, model=process.env.QWEN_ASR_MODEL||"qwen-audio-3.1-asr-flash-filetrans";
if(!key) throw new Error("DASHSCOPE_API_KEY is not configured");
const base=(process.env.DASHSCOPE_HTTP_BASE_URL||"https://dashscope.aliyuncs.com/api/v1").replace(/\/$/,"");
function valid(url){const u=new URL(url);if(u.protocol!=="https:"||u.username||u.password||!(u.hostname.endsWith(".aliyuncs.com")||u.hostname.endsWith(".maas.aliyuncs.com"))) throw new Error("Non-official URL");}
async function api(url,body,headers={}) {
 valid(url);
 const r=await fetch(url,{method:body?"POST":"GET",headers:{Authorization:"Bearer "+key,...(body?{"Content-Type":"application/json"}:{}),...headers},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(30000),redirect:"error"});
 const j=await r.json(); if(!r.ok) throw new Error("API "+r.status()+" "+String(j.code||j.error?.code||"FAILED").slice(0,80)); return j;
}
const audio=readFileSync(join(out,"speech.flac")), hash=createHash("sha256").update(audio).digest("hex"), path=join(out,"bridge-state.json");
let state=existsSync(path)?JSON.parse(readFileSync(path,"utf8")):{audioHash:hash};
if(state.audioHash!==hash) throw new Error("Fixture changed; inspect the previous submitted task before replacing bridge state");
const save=()=>writeFileSync(path,JSON.stringify(state,null,2));
if(!state.asrTaskId && state.submitIntentAt) throw new Error("Previous ASR submission is uncertain; inspect it before any resubmission");
if(!state.asrTaskId) {
 const policy=(await api(base+"/uploads?action=getPolicy&model="+encodeURIComponent(model))).data;
 valid(policy.upload_host);
 const form=new FormData(), filename="speech-"+hash.slice(0,12)+".flac", objectKey=policy.upload_dir+"/"+filename;
 for(const [k,v] of Object.entries({OSSAccessKeyId:policy.oss_access_key_id,Signature:policy.signature,policy:policy.policy,"x-oss-object-acl":policy.x_oss_object_acl,"x-oss-forbid-overwrite":policy.x_oss_forbid_overwrite,key:objectKey,success_action_status:"200"})) form.append(k,v);
 form.append("file",new Blob([audio],{type:"audio/flac"}),filename);
 const uploaded=await fetch(policy.upload_host,{method:"POST",body:form,signal:AbortSignal.timeout(60000),redirect:"error"});
 if(!uploaded.ok) throw new Error("Temporary upload HTTP "+uploaded.status);
 state.localUpload="passed";state.submitIntentAt=new Date().toISOString();save();
 const result=await api(base+"/services/audio/asr/transcription",{model,input:{file_urls:["oss://"+objectKey]},parameters:{channel_id:[0],language_hints:["zh","en"]}},{"X-DashScope-Async":"enable","X-DashScope-OssResourceResolve":"enable"});
 state.asrTaskId=result.output?.task_id;if(!state.asrTaskId)throw new Error("No ASR task id");state.model=model;save();
}
if(!state.transcript) {
 for(let i=0;i<80;i++){
  const result=await api(base+"/tasks/"+state.asrTaskId);
  if(result.output?.task_status==="SUCCEEDED"){
   const item=result.output.results?.find(r=>r.transcription_url);if(!item)throw new Error("ASR completed without transcript");
   valid(item.transcription_url);
   const response=await fetch(item.transcription_url,{signal:AbortSignal.timeout(30000),redirect:"error"});
   if(!response.ok)throw new Error("Result download failed");
   const raw=await response.json();state.transcript=raw;state.status="passed";save();break;
  }
  if(["FAILED","CANCELED","UNKNOWN"].includes(result.output?.task_status))throw new Error("ASR "+result.output.task_status);
  await new Promise(r=>setTimeout(r,3000));
 }
}
if(!state.transcript)throw new Error("ASR not finished; task id persisted, rerun queries it without resubmission");
console.log(JSON.stringify({localUpload:state.localUpload,asr:state.status,model,taskId:state.asrTaskId,sentenceCount:state.transcript.transcripts?.flatMap(t=>t.sentences||[]).length}));
