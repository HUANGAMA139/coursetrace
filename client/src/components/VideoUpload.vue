<script setup>
import {computed,onBeforeUnmount,ref} from "vue";
import Icon from "../Icon.vue";
import {fileSize} from "../format.js";
import {fileHash,initUpload,uploadStatus,uploadMissing,completeUpload,cancelUpload,delay} from "../resumableUpload.js";
const props=defineProps({courseId:{type:Number,required:true},ownerId:{type:Number,required:true},maxBytes:{type:Number,required:true}});
const emit=defineEmits(["uploaded","expired","busy"]);
const input=ref(null),file=ref(null),title=ref(""),busy=ref(false),percent=ref(0),phase=ref(""),error=ref(""),notice=ref(""),dragging=ref(false);
const cacheKey="coursetrace:upload:"+props.ownerId+":"+props.courseId;
let cached=null;try{cached=JSON.parse(localStorage.getItem(cacheKey)||"null");}catch{}
const saved=ref(cached),maxLabel=computed(()=>fileSize(props.maxBytes));let controller,disposed=false;
function store(value){saved.value=value;try{if(value)localStorage.setItem(cacheKey,JSON.stringify(value));else localStorage.removeItem(cacheKey);}catch{}}
function choose(candidate){error.value="";notice.value="";file.value=null;if(!candidate)return;
 if(!candidate.name.toLowerCase().endsWith(".mp4")){error.value="请选择 MP4 视频文件";return;}
 if(!candidate.size||candidate.size>props.maxBytes){error.value=candidate.size?"文件超过 "+maxLabel.value+" 上传上限":"文件为空，请重新选择";return;}
 file.value=candidate;title.value=saved.value?.filename===candidate.name?saved.value.title:candidate.name.replace(/\.mp4$/i,"").slice(0,120);
}
function dropped(event){dragging.value=false;if(!busy.value)choose(event.dataTransfer.files[0]);}
function setBusy(value){busy.value=value;emit("busy",value);}
function handle(failure){if(failure.name==="AbortError")notice.value=phase.value==="合并校验中"?"已停止等待，服务器可能仍在保存，请确认结果。":"已暂停。已确认的分片保留，可继续上传。";else if(failure.status===401)emit("expired",failure);else error.value=failure.message;}
async function accept(session){if(session.status!=="COMPLETED")return false;store(null);file.value=null;title.value="";if(input.value)input.value.value="";setBusy(false);emit("uploaded",session.video);return true;}
async function waitCompletion(session,signal){
 phase.value="合并校验中";let current=session;const until=Date.now()+120000;
 while(current.status==="FINALIZING"&&Date.now()<until){await delay(1000,signal);current=await uploadStatus(current.id,signal);}
 if(await accept(current))return;
 if(current.status==="FINALIZING")throw new Error("服务器仍在合并校验，请稍后点击确认保存结果。");
 throw new Error(current.message||"合并未完成，请继续上传或重试确认。");
}
async function upload(){if(busy.value||!file.value||!title.value.trim())return;error.value="";notice.value="";percent.value=0;setBusy(true);controller=new AbortController();const signal=controller.signal;
 try{
  phase.value="校验原文件";const sha=await fileHash(file.value,signal,p=>percent.value=p);if(signal.aborted)return;
  const old=saved.value;const same=old&&old.sha256===sha&&old.sizeBytes===file.value.size;
  let metadata=same?{...old}:{requestId:crypto.randomUUID(),filename:file.value.name,title:title.value.trim(),sizeBytes:file.value.size,sha256:sha};
  store(metadata);let session=await initUpload(props.courseId,metadata,signal);
  if(["CANCELLED","EXPIRED"].includes(session.status)){metadata={...metadata,requestId:crypto.randomUUID()};store(metadata);session=await initUpload(props.courseId,metadata,signal);}
  store({...metadata,id:session.id,title:session.title});if(await accept(session))return;
  if(session.status==="FINALIZING"&&!session.canComplete){await waitCompletion(session,signal);return;}
  phase.value="上传分片";await uploadMissing(file.value,session,signal,p=>percent.value=p);
  phase.value="合并校验中";
  const timer=setTimeout(()=>controller.abort(),120000);
  try{session=await completeUpload(session.id,signal);await waitCompletion(session,signal);}finally{clearTimeout(timer);}
 }catch(failure){if(!disposed)handle(failure);}finally{if(!disposed)setBusy(false);}
}
function pause(){controller?.abort();}
async function confirm(){if(busy.value||!saved.value?.id)return;error.value="";notice.value="";controller=new AbortController();setBusy(true);
 try{const session=await uploadStatus(saved.value.id,controller.signal);if(await accept(session))return;if(session.status==="FINALIZING"){await waitCompletion(session,controller.signal);return;}notice.value=session.message||"上传尚未保存，请选择原文件继续；过期会话可取消后重新上传。";}
 catch(failure){handle(failure);}finally{if(!disposed)setBusy(false);}
}
async function cancel(){if(busy.value)return;error.value="";
 try{if(saved.value?.id){const session=await cancelUpload(saved.value.id);if(await accept(session))return;}store(null);percent.value=0;notice.value="已取消上传，可以重新选择视频。";}catch(failure){handle(failure);}}
onBeforeUnmount(()=>{disposed=true;controller?.abort();});
</script>
<template>
 <section class="upload-panel" aria-labelledby="upload-heading">
  <div class="panel-heading"><span class="panel-icon"><Icon name="upload" :size="26"/></span><div><h2 id="upload-heading">添加课程视频</h2><p>分片上传，网络中断后可继续。</p></div></div>
  <p v-if="saved" class="resume-notice" role="status">检测到上传记录：{{ saved.filename }}。刷新后请重新选择同一原文件继续；也可以先确认保存结果。有效期以内的已确认分片会保留。</p>
  <form @submit.prevent="upload">
   <label class="upload-dropzone" :class="{dragging,'has-file':file,disabled:busy}" @dragover.prevent="dragging=!busy" @dragleave.prevent="dragging=false" @drop.prevent="dropped">
    <input ref="input" type="file" accept=".mp4,video/mp4" aria-label="选择课程视频" :disabled="busy||!maxBytes" @change="choose($event.target.files[0])"/>
    <span class="upload-file-icon"><Icon :name="file?'video':'upload'" :size="26"/></span><strong>{{ file?file.name:'点击选择，或将视频拖到这里' }}</strong>
    <span>{{ file?fileSize(file.size)+' · 点击可重新选择':'MP4 · H.264 / AAC · 单个文件最大 '+maxLabel }}</span>
   </label>
   <div v-if="file" class="upload-title"><label for="video-title">视频标题</label><input id="video-title" v-model="title" maxlength="120" required :disabled="busy||!!saved"/></div>
   <div v-if="busy||percent>0" class="upload-progress" role="status" aria-live="polite"><div><span>{{ busy?phase:'已暂停或等待继续' }}</span><strong>{{ percent }}%</strong></div><progress max="100" :value="percent" aria-label="视频上传进度"/><p>分片上传完成后仍需合并与媒体校验；保存成功才会进入视频列表。</p></div>
   <p v-if="notice" class="resume-notice" role="status">{{ notice }}</p><p v-if="error" class="form-error upload-error" role="alert">{{ error }}</p>
   <div class="upload-actions"><span><Icon name="lock" :size="14"/>仅当前课程所属账号可访问</span><button type="submit" class="button primary" :disabled="busy||!file||!title.trim()||!maxBytes">{{ busy?'处理中…':saved?'继续上传':'上传视频' }}</button></div>
  </form>
  <div class="resume-actions"><button v-if="busy" type="button" class="button secondary" @click="pause">{{ phase==='合并校验中'?'停止等待':'暂停上传' }}</button><template v-else-if="saved"><button type="button" class="button secondary" :disabled="!saved.id" @click="confirm">确认保存结果</button><button type="button" class="button secondary" @click="cancel">取消本次上传</button></template></div>
 </section>
</template>
<style scoped>
.resume-notice{font-size:12px;line-height:1.9;color:#587150;background:#f0f5ed;border-radius:10px;padding:10px 12px}.resume-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:12px}.resume-actions .button{font-size:12px}
</style>
