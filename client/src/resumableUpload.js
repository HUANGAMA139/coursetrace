import {ApiError,request,refreshCsrf} from "./api.js";
const abortError=()=>new DOMException("Paused","AbortError");
export function fileHash(file,signal,onProgress){
 return new Promise((resolve,reject)=>{
  const worker=new Worker(new URL("./uploadHash.worker.js",import.meta.url),{type:"module"});
  const stop=()=>{worker.terminate();signal?.removeEventListener("abort",abort);};
  const abort=()=>{stop();reject(abortError());};
  if(signal?.aborted){abort();return;}signal?.addEventListener("abort",abort,{once:true});
  worker.onmessage=({data})=>{if(data.progress!=null)onProgress(data.progress);if(data.sha256){stop();resolve(data.sha256);}if(data.error){stop();reject(new Error("文件校验失败："+data.error));}};
  worker.onerror=()=>{stop();reject(new Error("无法校验文件，请重新选择后重试"));};worker.postMessage(file);
 });
}
export const uploadStatus=(id,signal)=>request("/api/uploads/"+id,{signal});
export const cancelUpload=(id,signal)=>request("/api/uploads/"+id,{method:"DELETE",signal});
export const completeUpload=(id,signal)=>request("/api/uploads/"+id+"/complete",{method:"POST",signal});
export const initUpload=(course,data,signal)=>request("/api/courses/"+course+"/uploads",{method:"POST",signal,headers:{"Content-Type":"application/json"},body:JSON.stringify(data)});
async function sendPart(id,number,sha,blob,signal,onProgress,retried=false){
 const csrf=await refreshCsrf();if(signal.aborted)throw abortError();
 const result=await new Promise((resolve,reject)=>{
  const xhr=new XMLHttpRequest(),abort=()=>xhr.abort(),done=()=>signal.removeEventListener("abort",abort);
  xhr.open("PUT","/api/uploads/"+id+"/parts/"+number+"?sha256="+sha);xhr.withCredentials=true;xhr.timeout=120000;xhr.setRequestHeader(csrf.headerName,csrf.token);
  xhr.upload.onprogress=e=>{if(e.lengthComputable)onProgress(Math.min(blob.size,e.loaded));};
  xhr.onload=()=>{done();let body={};try{body=JSON.parse(xhr.responseText);}catch{}resolve({status:xhr.status,body});};
  xhr.onerror=xhr.ontimeout=()=>{done();reject(new ApiError("分片结果未确认，请继续上传",0));};
  xhr.onabort=()=>{done();reject(abortError());};signal.addEventListener("abort",abort,{once:true});
  const form=new FormData();form.append("file",blob,"part.bin");xhr.send(form);
 });
 if(result.status===403&&result.body.code==="CSRF_INVALID"&&!retried)return sendPart(id,number,sha,blob,signal,onProgress,true);
 if(result.status<200||result.status>=300)throw new ApiError(result.body.message||"分片保存失败",result.status,result.body.code);
 return result.body.data;
}
export function delay(ms,signal){return new Promise((resolve,reject)=>{if(signal.aborted){reject(abortError());return;}const timer=setTimeout(()=>{signal.removeEventListener("abort",abort);resolve();},ms);const abort=()=>{clearTimeout(timer);signal.removeEventListener("abort",abort);reject(abortError());};signal.addEventListener("abort",abort,{once:true});});}
export async function uploadMissing(file,session,signal,onProgress){
 const confirmed=new Map(session.parts.map(p=>[p.partNo,p.sizeBytes])),live=new Map();
 const progress=()=>onProgress(Math.min(100,Math.round(([...confirmed.values()].reduce((a,b)=>a+b,0)+[...live.values()].reduce((a,b)=>a+b,0))/file.size*100)));
 const missing=Array.from({length:session.chunkCount},(_,i)=>i).filter(n=>!confirmed.has(n));progress();
 async function worker(){while(missing.length){if(signal.aborted)throw abortError();const n=missing.shift(),blob=file.slice(n*session.chunkSize,Math.min(file.size,(n+1)*session.chunkSize));
  const bytes=await blob.arrayBuffer();const sha=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",bytes)),b=>b.toString(16).padStart(2,"0")).join("");
  for(let attempt=0;attempt<3;attempt++){
   try{await sendPart(session.id,n,sha,blob,signal,loaded=>{live.set(n,loaded);progress();});confirmed.set(n,blob.size);live.delete(n);progress();break;}
   catch(error){live.delete(n);progress();if(signal.aborted||error.name==="AbortError")throw error;
    if(error.status&&error.status<500&&error.status!==429&&error.status!==408)throw error;
    try{const current=await uploadStatus(session.id,signal);if(current.parts.some(p=>p.partNo===n&&p.sha256===sha)){confirmed.set(n,blob.size);progress();break;}}catch(check){if(signal.aborted)throw check;}
    if(attempt===2)throw error;await delay(500*2**attempt,signal);
   }
  }
 }}
 // Wait for both workers before returning: a failed worker must not leave invisible uploads running.
 const results=await Promise.allSettled([worker(),worker()]);const failed=results.find(r=>r.status==="rejected");if(failed)throw failed.reason;
}
