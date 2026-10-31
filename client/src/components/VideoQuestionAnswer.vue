<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { api } from "../api.js";
import AgentRunTrace from "./AgentRunTrace.vue";
const mode=ref("RAG");
const props=defineProps({
  video:{type:Object,required:true},
  ownerId:{type:Number,required:true},
  transcriptVersion:{type:Number,default:0},
  visible:{type:Boolean,default:true}
});
const emit=defineEmits(["expired","seek","activity"]);
const state=ref(null),turns=ref([]),page=ref(1),total=ref(0),question=ref(""),
  error=ref(""),indexError=ref(""),historyError=ref(""),pendingError=ref(""),
  busy=ref(false),asking=ref(false),checking=ref(false),loading=ref(true),
  historyLoaded=ref(false),pending=ref(null);
const storageKey="coursetrace:pending-question:"+props.ownerId+":"+props.video.id;
try {
  const saved=JSON.parse(sessionStorage.getItem(storageKey)||"null");
  if(saved&&/^[a-zA-Z0-9-]{16,64}$/.test(saved.id)&&typeof saved.text==="string"&&saved.text.length<=1000) {
    pending.value={id:saved.id,text:saved.text,mode:saved.mode==="AGENT"?"AGENT":"RAG",missing:false};mode.value=pending.value.mode;
    question.value=saved.text;
  }
} catch { /* The in-memory request still works if browser storage is unavailable. */ }
const historyViewport=ref(null),newAnswers=ref(false);
const viewKey="coursetrace:question-view:"+props.ownerId+":"+props.video.id;
let savedScroll=0,restoreScroll=false,historyInitialized=false,wasNearBottom=true;
try {
  const saved=JSON.parse(sessionStorage.getItem(viewKey)||"null");
  if(saved){
    if(!pending.value&&typeof saved.draft==="string")question.value=saved.draft.slice(0,500);
    if(Number.isSafeInteger(saved.page)&&saved.page>0&&saved.page<=100000)page.value=saved.page;
    if(Number.isFinite(saved.scroll)&&saved.scroll>=0){savedScroll=saved.scroll;restoreScroll=true;}
  }
} catch { /* Optional view state never changes request identity. */ }
function saveView(){
  try{sessionStorage.setItem(viewKey,JSON.stringify({draft:question.value,page:page.value,scroll:savedScroll}));}catch{}
}
function historyScrolled(){
  if(!props.visible)return;
  const element=historyViewport.value;
  if(!element)return;
  savedScroll=element.scrollTop;
  wasNearBottom=element.scrollHeight-element.clientHeight-element.scrollTop<48;
  if(wasNearBottom)newAnswers.value=false;
  saveView();
}
async function applyHistory(history) {
  const prior=turns.value.map(turn=>turn.id+":"+turn.status).join(",");
  const changedPage=page.value!==history.page;
  const follow=!historyInitialized||changedPage||wasNearBottom;
  const oldScroll=savedScroll;
  turns.value=history.items;total.value=history.total;page.value=history.page;
  historyLoaded.value=true;historyError.value="";
  const changed=prior!==history.items.map(turn=>turn.id+":"+turn.status).join(",");
  await nextTick();
  if(disposed)return;
  const element=historyViewport.value;
  if(props.visible&&element){
    if(!historyInitialized&&restoreScroll&&!changedPage)element.scrollTop=oldScroll;
    else if(follow)element.scrollTop=element.scrollHeight;
    else{element.scrollTop=oldScroll;if(changed)newAnswers.value=true;}
    historyScrolled();
  }
  historyInitialized=true;
}
async function showNewest(){
  if(page.value!==1){restoreScroll=false;await refresh(1);}
  await nextTick();
  if(historyViewport.value)historyViewport.value.scrollTop=historyViewport.value.scrollHeight;
  newAnswers.value=false;historyScrolled();
}
watch(question,saveView,{flush:"sync"});
let disposed=false,timer,refreshController,actionController,askController,readFailures=0;
const active=computed(()=>turns.value.some(t=>t.status==="RUNNING"));
const canAsk=computed(()=>state.value?.ready&&historyLoaded.value&&!indexError.value&&!historyError.value&&!asking.value&&!active.value&&!pending.value);
const canRebuild=computed(()=>state.value?.indexId&&["SUCCEEDED","FAILED"].includes(state.value?.task?.status));
const ordered=computed(()=>[...turns.value].reverse());
const stageName=computed(()=>({CHUNK_BUILD:"整理字幕片段",EMBED_BATCH:"生成检索资料",INDEX_UPSERT:"保存检索资料",INDEX_PUBLISH:"校验问答资料"})[state.value?.task?.stage]||"准备问答资料");
const historyLabel=turn=>({RUNNING:"正在根据课程材料回答",UNKNOWN:"结果尚未确认",FAILED:"本次未能完成",INSUFFICIENT_EVIDENCE:"课程依据不足"})[turn.status]||"";
function time(ms){const seconds=Math.floor(ms/1000);return Math.floor(seconds/60)+":"+String(seconds%60).padStart(2,"0");}
function persistPending(value) {
  pending.value=value;
  try {
    if(value)sessionStorage.setItem(storageKey,JSON.stringify({id:value.id,text:value.text,mode:value.mode||"RAG"}));
    else sessionStorage.removeItem(storageKey);
  } catch { /* Never create a second request to compensate for a storage error. */ }
}
function handle(failure,field=error,timedOut=false){
  if(failure.status===401){emit("expired",failure);return;}
  if(failure.name!=="AbortError"||timedOut)field.value=timedOut?"读取超时，请刷新重试":failure.message;
}
function acceptResult(turn,request) {
  if(pending.value?.id!==request.id)return;
  if(turn.status==="RUNNING"){
    persistPending({...request,missing:false,turnId:turn.id});
    return;
  }
  turns.value=turns.value.map(existing=>existing.id===turn.id?turn:existing);
  if(question.value.trim()===request.text)question.value="";
  persistPending(null);pendingError.value="";error.value="";
}
function schedule(){
  clearTimeout(timer);
  if(disposed||document.hidden||!props.visible)return;
  const processing=state.value?.status==="BUILDING"||active.value||pending.value&&!pending.value.missing;
  if(processing)timer=setTimeout(()=>refresh(),2000);
  else if((indexError.value||historyError.value)&&readFailures<3)timer=setTimeout(()=>refresh(),5000);
}
async function lookup(request,controller,valid,timedOut){
  if(!request)return;
  try {
    const turn=await api.questionRequest(props.video.id,request.id,controller.signal);
    if(!valid()||pending.value?.id!==request.id)return;
    pendingError.value="";
    acceptResult(turn,request);
  }catch(failure){
    if(!valid()||pending.value?.id!==request.id)return;
    if(failure.status===404){
      persistPending({...request,missing:true});
      pendingError.value="暂未查到原请求。可再次确认，或用原编号重新提交。";
    }else handle(failure,pendingError,timedOut());
  }
}
async function refresh(target=page.value){
  clearTimeout(timer);refreshController?.abort();
  if(disposed||document.hidden||!props.visible)return;
  const controller=refreshController=new AbortController();
  const valid=()=>!disposed&&refreshController===controller;
  let timedOut=false;
  const timeout=setTimeout(()=>{timedOut=true;controller.abort();},15000);
  let failed=false;
  try {
    // Each read updates its own panel; an unavailable index never hides saved answers.
    await Promise.allSettled([
      (async()=>{
        try {
          const result=await api.searchIndex(props.video.id,controller.signal);
          if(valid()){state.value=result;indexError.value="";}
        }catch(failure){if(valid()){failed=true;handle(failure,indexError,timedOut);}}
      })(),
      (async()=>{
        try {
          const history=await api.questions(props.video.id,target,controller.signal);
          if(!valid())return;
          await applyHistory(history);
          if(!valid())return;
          const request=pending.value;
          const found=request&&history.items.find(t=>t.requestId===request.id);
          if(found)acceptResult(found,request);
        }catch(failure){if(valid()){failed=true;handle(failure,historyError,timedOut);}}
      })(),
      lookup(pending.value,controller,valid,()=>timedOut)
    ]);
  }finally{
    clearTimeout(timeout);
    if(valid()){loading.value=false;readFailures=failed?readFailures+1:0;schedule();}
  }
}
async function confirmPending(){
  if(checking.value||asking.value||!pending.value)return;
  checking.value=true;
  try{await refresh(1);}finally{if(!disposed)checking.value=false;}
}
async function indexAction(mode){
  if(busy.value)return;busy.value=true;error.value="";
  const controller=actionController=new AbortController();
  try{
    if(mode==="retry")await api.retryProcessing(state.value.task.id,state.value.task.runNo,controller.signal);
    else if(mode==="rebuild")await api.rebuildSearchIndex(props.video.id,{expectedIndexId:state.value.indexId,expectedRunNo:state.value.task.runNo,requestId:crypto.randomUUID()},controller.signal);
    else await api.createSearchIndex(props.video.id,controller.signal);
    if(!disposed)await refresh();
  }catch(failure){if(!disposed)handle(failure);}
  finally{if(!disposed)busy.value=false;}
}
async function sendQuestion(request){
  if(asking.value)return;
  asking.value=true;error.value="";pendingError.value="";page.value=1;
  // Persist before the POST; reloads and uncertain responses reuse exactly this ID.
  persistPending({...request,missing:false});
  const controller=askController=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),request.mode==="AGENT"?150000:100000);
  try{
    const result=await api.askQuestion(props.video.id,{requestId:request.id,question:request.text,mode:request.mode||"RAG"},controller.signal);
    if(disposed||askController!==controller)return;
    acceptResult(result,request);
    await refresh(1);
  }catch(failure){
    if(disposed||askController!==controller)return;
    if(failure.name==="AbortError")error.value="本次等待已结束，请确认原请求结果。";
    else{
      // A definite client rejection was not admitted; network/5xx results remain pending.
      if((failure.status>=400&&failure.status<500&&failure.status!==408)||failure.code==="RATE_LIMIT_UNAVAILABLE")persistPending(null);
      handle(failure);
    }
  }finally{
    clearTimeout(timeout);
    if(!disposed&&askController===controller){asking.value=false;schedule();}
  }
}
function ask(){
  if(!canAsk.value||!question.value.trim())return;
  return sendQuestion({id:crypto.randomUUID(),text:question.value.trim(),mode:mode.value});
}
function resubmitPending(){
  if(!pending.value?.missing||checking.value||asking.value)return;
  return sendQuestion(pending.value);
}
function seek(source){if(source.videoId===props.video.id)emit("seek",source.beginMs);}
function visibilityChanged(){
  clearTimeout(timer);
  if(document.hidden||!props.visible)refreshController?.abort();
  else refresh();
}
watch(()=>props.transcriptVersion,()=>{if(state.value)refresh();});
watch(()=>props.visible,async visible=>{
  clearTimeout(timer);
  if(!visible){
    // Capture before v-show removes the layout box; pending POSTs continue.
    // scroll events already captured the visible position before v-show hides it.
    saveView();refreshController?.abort();
  }else{
    await nextTick();
    if(disposed)return;
    if(historyInitialized && historyViewport.value){historyViewport.value.scrollTop=savedScroll;historyScrolled();}
    refresh();
  }
});
watch(()=>asking.value||!!pending.value||active.value,value=>emit("activity",value),{immediate:true});
document.addEventListener("visibilitychange",visibilityChanged);
refresh();
onBeforeUnmount(()=>{
  saveView();disposed=true;clearTimeout(timer);
  document.removeEventListener("visibilitychange",visibilityChanged);
  refreshController?.abort();actionController?.abort();askController?.abort();
});
</script>

<template>
 <section class="course-qa" aria-labelledby="qa-heading">
  <header class="qa-header"><div><p class="eyebrow">ASK YOUR COURSE</p><h2 id="qa-heading">课程问答</h2></div><button class="qa-refresh" @click="error='';refresh()">刷新问答记录</button></header>
  <p class="qa-scope">仅依据当前视频回答，来源可以回看。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <p v-if="indexError" class="form-error" role="alert">问答资料：{{ indexError }}</p>
  <div class="qa-index" :data-index-status="state?.status||'LOADING'">
   <p v-if="loading">正在读取问答资料…</p>
   <template v-else-if="state && !indexError">
    <p class="qa-index-message">{{ state.message }}</p>
    <p v-if="state.task&&!['SUCCEEDED','FAILED'].includes(state.task.status)" class="qa-meta">{{ stageName }} · 已保存 {{ state.completedBatches }} / {{ state.batchCount||'—' }} 批</p>
    <details v-if="state.ready && !state.task?.canRetry" class="qa-index-options"><summary>资料设置</summary><div class="qa-actions">
     <button v-if="!state.task" class="button secondary" :disabled="busy||state.status==='NO_TRANSCRIPT'||!state.configured" @click="indexAction('create')">{{ busy?'正在提交…':'建立问答索引' }}</button>
     <button v-if="state.task?.canRetry" class="button secondary" :disabled="busy" @click="indexAction('retry')">恢复索引任务</button>
     <button v-if="canRebuild" class="qa-refresh" :disabled="busy" @click="indexAction('rebuild')">{{ busy?'正在提交…':'重新建立索引' }}</button>
    </div></details>
    <div v-else class="qa-actions">
     <button v-if="!state.task" class="button secondary" :disabled="busy||state.status==='NO_TRANSCRIPT'||!state.configured" @click="indexAction('create')">建立问答索引</button>
     <button v-if="state.task?.canRetry" class="button secondary" :disabled="busy" @click="indexAction('retry')">恢复索引任务</button>
     <button v-if="canRebuild" class="qa-refresh" :disabled="busy" @click="indexAction('rebuild')">重新建立索引</button>
    </div>
   </template>
  </div>
  <div ref="historyViewport" class="qa-history" role="region" aria-label="当前视频问答记录" tabindex="0" @scroll="historyScrolled">
   <p v-if="historyError" class="form-error" role="alert">问答记录：{{ historyError }}</p>
   <p v-if="!historyLoaded && !historyError" class="qa-empty">正在读取问答记录…</p>
   <p v-else-if="historyLoaded && !turns.length" class="qa-empty">把不确定的地方问清楚，让理解留下依据。</p>
   <article v-for="turn in ordered" :key="turn.id" class="qa-turn" :data-turn-id="turn.id" :data-answer-status="turn.status">
    <h3>{{ turn.question }}</h3>
    <AgentRunTrace v-if="turn.agentRun" :video-id="props.video.id" :turn-id="turn.id" :summary="turn.agentRun" :visible="props.visible" @seek="emit('seek',$event)" @expired="emit('expired',$event)" />
    <span v-if="state?.activeIndexId&&turn.indexId!==state.activeIndexId" class="qa-meta">来自此前版本的课程资料</span>
    <template v-if="turn.answer?.status==='ANSWERED'">
     <div v-for="(paragraph,index) in turn.answer.paragraphs" :key="index" class="qa-paragraph">
      <p>{{ paragraph.text }}</p>
      <div class="qa-citations"><button v-for="source in paragraph.citations" :key="source.segmentId" type="button" :data-qa-source="source.segmentId" :data-begin-ms="source.beginMs" :title="source.quote" @click="seek(source)">回看 {{ time(source.beginMs) }}<span>{{ source.quote }}</span></button></div>
     </div>
    </template>
    <p v-else-if="turn.answer?.status==='INSUFFICIENT_EVIDENCE'" class="qa-insufficient">{{ turn.answer.message }}</p>
    <p v-else class="qa-meta">{{ turn.message||historyLabel(turn) }}</p>
   </article>
  </div>
  <nav v-if="total>20" class="qa-pagination" aria-label="问答历史分页"><button class="button secondary" :disabled="page<=1" @click="refresh(page-1)">较新记录</button><span>{{ page }} / {{ Math.ceil(total/20) }}</span><button class="button secondary" :disabled="page*20>=total" @click="refresh(page+1)">更早记录</button></nav>
  <button v-if="newAnswers" class="qa-new-answer" type="button" @click="showNewest">有新回答，查看最新 ↓</button>
  <div class="qa-compose">
  <form class="qa-form" @submit.prevent="ask">
   <div class="qa-mode-choice"><label for="qa-mode">回答方式</label>
   <select id="qa-mode" v-model="mode" :disabled="asking||!!pending">
    <option value="RAG">普通问答</option><option value="AGENT">深入问答</option>
   </select></div>
   <p v-if="mode==='AGENT'" class="qa-meta">会搜索课程并按需补读上下文，处理时间和模型调用可能增加。</p>
   <label for="course-question">想弄懂哪一个知识点？</label>
   <textarea id="course-question" v-model="question" rows="3" maxlength="500" :disabled="asking || !!pending" placeholder="例如：为什么二分查找要求数组有序？" />
   <div class="qa-form-footer"><span>{{ question.length }} / 500</span><button class="button primary" :disabled="!canAsk||!question.trim()" type="submit">{{ asking||active?'正在回答…':'提问' }}</button></div>
  </form>
  <p v-if="pending&&asking" class="qa-pending" role="status">正在检索课程原文并整理回答…</p>
  <div v-else-if="pending" class="qa-pending-check" role="status">
    <p>上次请求的结果尚未确认，确认后再发起新问题。</p>
    <p v-if="pendingError">{{ pendingError }}</p>
    <button class="button secondary" type="button" :disabled="checking" @click="confirmPending">{{ checking?'正在确认…':'确认原请求' }}</button>
    <button v-if="pending.missing" class="qa-refresh" type="button" :disabled="checking" @click="resubmitPending">用原编号重新提交</button>
  </div>
  </div>
 </section>
</template>

<style scoped>
.course-qa{display:flex;flex-direction:column;height:100%;min-height:0;min-width:0;background:#fff}
.qa-header{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:14px 18px 0;flex-shrink:0}
.qa-header .eyebrow{display:none}.qa-header h2{font-size:14px;margin:0}
.qa-refresh{border:0;background:transparent;color:#587150;font-size:11px;cursor:pointer;padding:6px 0}.qa-refresh:disabled{opacity:.5}
.qa-scope,.qa-meta,.qa-empty{font-size:11px;line-height:1.8;color:#81867a}
.qa-scope{margin:6px 18px 10px;flex-shrink:0}
.course-qa>.form-error{margin:0 18px 8px;font-size:11px;flex-shrink:0}
.qa-index{flex-shrink:0;max-height:120px;overflow-y:auto;padding:9px 12px;margin:0 14px 8px;border-radius:9px;background:#f5f7f2;font-size:11px;color:#456446}
.qa-index-message{margin:0}.qa-index[data-index-status="FAILED"],.qa-index[data-index-status="DEGRADED"]{background:#fbf3ed;color:#9b5945}
.qa-index-options>summary{cursor:pointer;font-size:10px;color:#839078;margin-top:4px}
.qa-actions{display:flex;flex-wrap:wrap;gap:9px;align-items:center}.qa-actions:not(:empty){margin-top:6px}.qa-actions .button{font-size:11px;padding:6px 10px}
.qa-history{flex:1;min-height:0;overflow-y:auto;overscroll-behavior:contain;padding:0 16px;scrollbar-gutter:stable;overflow-wrap:anywhere}
.qa-empty{text-align:center;padding:20px 4px}.qa-turn{border-top:1px solid #edf0e8;padding:16px 0 4px;margin-top:10px}.qa-turn h3{font-size:13px;line-height:1.8;margin:0 0 10px;color:#344e35}
.qa-paragraph>p,.qa-insufficient{font-size:12px;color:#66715c;line-height:1.9;white-space:pre-wrap}
.qa-citations{display:flex;gap:7px;flex-direction:column;margin:8px 0 13px}.qa-citations button{width:100%;text-align:left;cursor:pointer;border:1px solid #dfe8d9;background:#f6f8f2;border-radius:8px;color:#55764a;padding:7px 10px;font-size:11px}
.qa-citations span{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;margin-top:4px;color:#84917a;font-size:11px;line-height:1.7}
.qa-pagination{display:flex;justify-content:space-between;align-items:center;gap:8px;font-size:11px;padding:5px 14px;flex-shrink:0}.qa-pagination .button{padding:5px 8px;font-size:11px}
.qa-compose{flex-shrink:0;padding:12px 16px;background:#fff;border-top:1px solid #e8ece5;max-height:55%;overflow-y:auto}
.qa-form label{display:block;font-size:12px;color:#43583e;margin-bottom:7px}
.qa-mode-choice{display:flex;align-items:center;gap:12px;margin-bottom:10px}.qa-mode-choice label{white-space:nowrap;margin:0}.qa-mode-choice select{flex:1;min-width:0;width:auto;margin:0}
.qa-form select{width:100%;box-sizing:border-box;border:1px solid #dfe5d8;border-radius:8px;padding:7px 10px;background:#fbfcf9;font:inherit;font-size:12px;color:#43583e;margin-bottom:0;outline-color:#86a078}
.qa-form textarea{box-sizing:border-box;width:100%;resize:vertical;min-height:64px;max-height:120px;border:1px solid #dfe5d8;border-radius:9px;background:#fbfcf9;padding:8px 10px;font:inherit;font-size:12px;color:#43583e;line-height:1.7;outline-color:#86a078}
.qa-form-footer{display:flex;justify-content:space-between;align-items:center;margin-top:7px;gap:10px}.qa-form-footer span{font-size:10px;color:#959c8c}.qa-form-footer .button{font-size:12px;padding:7px 18px}
.qa-pending{font-size:11px;color:#5a794e;line-height:1.8;margin:8px 0 0}.qa-pending-check{padding:8px 10px;background:#f6f8f2;border-radius:8px;font-size:11px;color:#66715c;line-height:1.7;margin-top:8px}
.qa-pending-check p{margin:3px 0}.qa-pending-check .button{padding:5px 8px;font-size:11px}.qa-pending-check .qa-refresh{margin-left:8px}
.qa-new-answer{border:0;background:#ecf3e5;color:#507448;cursor:pointer;font-size:11px;padding:7px;flex-shrink:0}
@media(max-height:719px){
 .qa-header{padding-top:8px}.qa-scope{margin-top:3px;margin-bottom:6px}
 .qa-index{max-height:66px;padding:6px 10px;margin-bottom:6px}
 .qa-compose{padding:8px 12px}.qa-mode-choice{margin-bottom:6px}
 .qa-form label{margin-bottom:4px}.qa-mode-choice label{margin:0}
 .qa-form textarea{height:56px;min-height:48px;max-height:72px}
 .qa-form>.qa-meta{margin:3px 0;font-size:10px}
 .qa-form-footer{margin-top:4px}
}
</style>
