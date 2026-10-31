<script setup>
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { api } from "../api.js";
import { useProcessingTasks } from "../useProcessingTasks.js";
const props=defineProps({video:Object,courseId:Number,videos:Array});
const emit=defineEmits(["expired","seek","transcript","tasks","refresh-subtitles"]);
const stages={SOURCE_CHECK:"检查视频",AUDIO_EXTRACT:"提取音频",ASR_SUBMIT:"提交转录",ASR_POLL:"云端转录",TRANSCRIPT_BUILD:"整理字幕",NOTE_BUILD:"生成笔记"};
const course=computed(()=>props.courseId),selected=computed(()=>props.video),visible=computed(()=>props.videos||[]),enabled=computed(()=>!!props.video);
const learning=useProcessingTasks(course,visible,selected,enabled,failure=>emit("expired",failure),2);
const task=learning.selectedTask;
const tab=ref("subtitles"),capability=ref(null),summary=ref(null),rows=ref([]),notes=ref(null),
  contentLoading=ref(false),contentError=ref(""),subtitleError=ref(""),noteError=ref(""),page=ref(1),total=ref(0);
let controller,disposed=false;const capabilityController=new AbortController();
const label=computed(()=>{
  if(!task.value)return "尚未生成学习资料";
  if(task.value.status==="SUCCEEDED")return "字幕与笔记已就绪";
  if(task.value.status==="FAILED")return "学习资料生成未完成";
  if(task.value.status==="RETRY_WAIT")return "等待自动重试";
  if(task.value.status==="PENDING")return "等待处理";
  return stages[task.value.stage]||"正在处理";
});
const step=computed(()=>task.value?.steps?.find(s=>s.stage===task.value.stage&&["RUNNING","WAITING_EXTERNAL","PENDING","RETRY_WAIT","FAILED"].includes(s.status)));
const readyNotes=computed(()=>notes.value?.items||[]);
const time=ms=>{const seconds=Math.max(0,Math.floor(ms/1000));return Math.floor(seconds/60)+":"+String(seconds%60).padStart(2,"0");};
function auth(error){if(error.status===401){emit("expired",error);return true;}return false;}
function sourcesFor(note) {
  return [...new Map(note.sources.map(source => [source.segmentId, source])).values()]
    .sort((a, b) => a.beginMs - b.beginMs || a.segmentId - b.segmentId);
}
async function loadContent(target=page.value,atMs) {
  controller?.abort();
  const current=controller=new AbortController();
  contentLoading.value=true;
  let timedOut=false;
  const timeout=setTimeout(()=>{timedOut=true;current.abort();},20000);
  const valid=()=>!disposed&&current===controller;
  const showError=(failure,field)=>{
    if(!valid()||auth(failure))return;
    if(failure.name!=="AbortError"||timedOut)
      field.value=timedOut?"读取超时，请刷新重试":failure.message;
  };
  try {
    const info=await api.transcript(props.video.id,current.signal);
    if(!valid())return;
    const changed=summary.value?.version!==info.version;
    summary.value=info;
    contentError.value="";
    if(changed){rows.value=[];notes.value=null;total.value=0;page.value=1;}
    // Captions depend on the transcript, not on successful note generation.
    emit("transcript",info.ready?info.version:0);
    if(!info.ready){rows.value=[];notes.value=null;total.value=0;return;}
    await Promise.allSettled([
      (async()=>{
        try {
          const result=await api.segments(props.video.id,changed?1:target,current.signal,atMs);
          if(!valid())return;
          rows.value=result.items;page.value=result.page;total.value=result.total;subtitleError.value="";
        }catch(failure){showError(failure,subtitleError);}
      })(),
      (async()=>{
        try {
          const result=await api.notes(props.video.id,current.signal);
          if(valid()){notes.value=result;noteError.value="";}
        }catch(failure){showError(failure,noteError);}
      })()
    ]);
  }catch(failure){showError(failure,contentError);}
  finally{clearTimeout(timeout);if(valid())contentLoading.value=false;}
}
async function refresh(){
  await Promise.allSettled([learning.refresh(),loadContent()]);
  if(!disposed&&summary.value?.ready)emit("refresh-subtitles");
}
async function resubmit(){
  if(window.confirm("上一次转录提交结果尚未确认。重新提交可能产生重复费用。请先核对已有任务，确认仍要重新提交吗？"))
    await learning.retryConfirmed();
}
watch(()=>task.value?JSON.stringify([task.value.runNo,task.value.status,task.value.stage,task.value.steps.filter(s=>s.status==="SUCCEEDED").length]):"",()=>loadContent());
watch(learning.tasks,value=>emit("tasks",value));
api.learningCapabilities(capabilityController.signal).then(value=>{if(!disposed)capability.value=value;}).catch(error=>{if(!disposed&&error.name!=="AbortError"&&!auth(error))contentError.value=error.message;});
loadContent();
onBeforeUnmount(()=>{disposed=true;controller?.abort();capabilityController.abort();});
function showTab(value){if(!["subtitles","notes"].includes(value))return;tab.value=value;document.querySelector(".learning-materials")?.scrollIntoView({block:"start"});}
defineExpose({showTab});
</script>

<template>
  <section class="learning-materials" aria-labelledby="learning-title">
    <header class="learning-materials-header">
      <div><p class="eyebrow">UNDERSTAND & REVISIT</p><h2 id="learning-title">字幕与章节笔记</h2></div>
      <button class="learning-refresh" :disabled="learning.loading.value || contentLoading" @click="refresh">刷新学习资料</button>
    </header>
    <div class="learning-progress">
      <p class="learning-state" :data-learning-status="task?.status || 'NONE'" role="status">{{ label }}</p>
      <p v-if="step?.stage==='ASR_POLL'" class="learning-hint">云端正在处理音频，已查询 {{ step.pollCount }} 次。完成后会继续整理字幕。</p>
      <p v-else-if="!task" class="learning-hint">将这段讲解整理成可回看的字幕和带来源的笔记。</p>
      <ol v-if="task" class="learning-stages" aria-label="学习资料处理阶段">
        <li v-for="(name,key) in stages" :key="key" :class="{done:task.steps.some(s=>s.stage===key&&s.status==='SUCCEEDED'),current:task.stage===key}">{{ name }}</li>
      </ol>
      <p v-if="task?.errorMessage" class="form-error" role="alert">{{ task.errorMessage }}</p>
      <p v-if="learning.error.value || learning.actionError.value" class="form-error" role="alert">{{ learning.error.value || learning.actionError.value }}</p>
      <p v-if="!video.audioCodec" class="learning-hint">这段视频没有音轨，可以继续播放，暂时无法生成字幕。</p>
      <p v-else-if="capability && !capability.configured" class="learning-hint">{{ capability.message }}</p>
      <button v-if="!task" class="button primary" :disabled="!capability?.configured || !video.audioCodec || learning.busy.value || learning.loading.value || !!learning.error.value" @click="learning.submit">{{ learning.busy.value ? "正在提交…" : "生成字幕与笔记" }}</button>
      <button v-else-if="task.canRetry" class="button primary" :disabled="learning.busy.value || learning.loading.value" @click="learning.retry">重试未完成步骤</button>
      <button v-else-if="task.canResubmitUncertain" class="button secondary" :disabled="learning.busy.value" @click="resubmit">核对后重新提交转录</button>
    </div>
    <div class="learning-tabs" role="tablist" aria-label="课程学习资料">
      <button role="tab" :aria-selected="tab==='subtitles'" aria-controls="subtitle-panel" @click="tab='subtitles'">字幕<span v-if="summary?.ready">{{ summary.segmentCount }}</span></button>
      <button role="tab" :aria-selected="tab==='notes'" aria-controls="note-panel" @click="tab='notes'">笔记<span v-if="readyNotes.length">{{ readyNotes.length }}</span></button>
    </div>
    <p v-if="contentError" class="form-error" role="alert">{{ contentError }}</p>
    <div v-if="tab==='subtitles'" id="subtitle-panel" role="tabpanel" aria-label="课程字幕" class="learning-tab-panel">
      <p v-if="subtitleError" class="form-error" role="alert">字幕：{{ subtitleError }}</p>
      <p v-if="!summary?.ready" class="learning-empty">{{ contentLoading ? "正在读取字幕…" : "字幕生成后，可以点击句子回到原视频。" }}</p>
      <div v-else>
        <button v-for="segment in rows" :key="segment.id" class="subtitle-line" :data-segment-id="segment.id" :aria-label="'播放字幕：'+segment.text" @click="emit('seek',segment.beginMs)">
          <span class="source-time">{{ time(segment.beginMs) }}</span><span>{{ segment.text }}</span>
        </button>
        <nav v-if="total>50" class="learning-pagination" aria-label="字幕分页">
          <button class="button secondary" :disabled="contentLoading||page<=1" @click="loadContent(page-1)">上一页字幕</button>
          <span>{{ page }} / {{ Math.ceil(total/50) }}</span>
          <button class="button secondary" :disabled="contentLoading||page*50>=total" @click="loadContent(page+1)">下一页字幕</button>
        </nav>
      </div>
    </div>
    <div v-else id="note-panel" role="tabpanel" aria-label="章节笔记" class="learning-tab-panel">
      <p v-if="noteError" class="form-error" role="alert">笔记：{{ noteError }}</p>
      <p v-if="!readyNotes.length && !noteError" class="learning-empty">章节笔记生成后，会在这里列出知识要点和讲解来源。</p>
      <template v-if="readyNotes.length">
        <p v-if="!notes.complete" class="learning-hint">已完成 {{ notes.completedWindows }} / {{ notes.totalWindows }} 个笔记窗口，可先阅读已有内容。</p>
        <article v-for="note in readyNotes" :key="note.id" class="learning-note" :data-note-id="note.id">
          <span class="learning-note-range">{{ time(note.beginMs) }} — {{ time(note.endMs) }}</span>
          <h3>{{ note.title }}</h3><p>{{ note.summary }}</p>
          <ul><li v-for="(point,index) in note.points" :key="index">{{ point }}</li></ul>
          <div class="note-review">
            <button class="note-replay" @click="emit('seek',note.beginMs)">▶ 回看本节 {{ time(note.beginMs) }}</button>
            <details class="note-source-details">
              <summary>查看来源（{{ sourcesFor(note).length }}）</summary>
              <div class="note-sources">
                <button v-for="source in sourcesFor(note)" :key="source.segmentId" :data-source-segment="source.segmentId" @click="emit('seek',source.beginMs)">
                  <span>回看 {{ time(source.beginMs) }}</span><span v-if="source.quote" class="note-source-quote">{{ source.quote }}</span>
                </button>
              </div>
            </details>
          </div>
        </article>
      </template>
    </div>
  </section>
</template>

<style scoped>
.learning-materials{margin-top:24px;padding:25px;border:1px solid #e4e8e0;border-radius:18px;background:#fff;min-width:0}
.learning-materials-header{display:flex;align-items:center;justify-content:space-between;gap:16px}
.learning-materials-header .eyebrow{margin:0 0 8px}.learning-materials h2{font-size:18px;margin:0}
.learning-refresh{border:0;background:transparent;color:#587150;font-size:12px;cursor:pointer;white-space:nowrap}.learning-refresh:disabled{opacity:.5}
.learning-progress{margin-top:22px;padding:18px;border-radius:12px;background:#f5f7f2}
.learning-state{font-size:14px;font-weight:600;margin:0 0 9px;color:#365c46}.learning-state[data-learning-status="FAILED"]{color:#a54e42}
.learning-hint,.learning-empty{font-size:12px;line-height:1.9;color:#81867a}.learning-progress .button{margin-top:8px}
.learning-stages{display:flex;flex-wrap:wrap;gap:9px;list-style:none;padding:0;margin:15px 0}
.learning-stages li{font-size:10px;padding:5px 9px;border-radius:6px;color:#92978b;background:#eaede5}
.learning-stages li.done{color:#537349;background:#e5efdf}.learning-stages li.current{box-shadow:0 0 0 1px #8ba67f inset}
.learning-tabs{display:flex;gap:22px;margin-top:23px;border-bottom:1px solid #e8ece5}
.learning-tabs button{border:0;border-bottom:2px solid transparent;background:transparent;padding:12px 2px;font-size:14px;cursor:pointer;color:#969b8e}
.learning-tabs button[aria-selected="true"]{color:#365c46;border-bottom-color:#507448}
.learning-tabs span{margin-left:7px;font-size:10px;background:#edf2e7;padding:2px 5px;border-radius:4px}
.learning-tab-panel{padding-top:15px;min-height:100px}.learning-empty{text-align:center;padding:18px 7px}
.subtitle-line{display:flex;align-items:flex-start;gap:17px;width:100%;text-align:left;border:0;border-radius:8px;background:transparent;padding:13px 9px;color:#495743;font-size:13px;line-height:1.9;cursor:pointer}
.subtitle-line:hover{background:#f1f5eb}.subtitle-line>span:last-child{overflow-wrap:anywhere;min-width:0}
.source-time{font-size:11px;color:#78906b;flex-shrink:0;font-variant-numeric:tabular-nums;min-width:36px}
.learning-pagination{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:15px;font-size:12px}
.learning-note{padding:20px 3px;border-bottom:1px solid #edf0e8}.learning-note:last-child{border-bottom:0}
.learning-note-range{font-size:11px;color:#849879}.learning-note h3{font-size:16px;margin:9px 0 12px}
.learning-note p,.learning-note li{font-size:13px;line-height:1.9;color:#66715c;overflow-wrap:anywhere}
.learning-note ul{padding-left:20px}.note-sources{display:flex;flex-wrap:wrap;gap:9px;align-items:center;margin-top:15px;font-size:11px;color:#979c8e}
.note-sources button{border:1px solid #dfe8d9;background:#f6f8f2;border-radius:6px;padding:5px 9px;color:#55764a;font-size:11px;cursor:pointer}
@media(max-width:600px){.learning-materials{padding:21px 16px;margin-top:19px}.learning-materials-header{gap:10px}.learning-materials h2{font-size:16px}.learning-progress{padding:15px}.subtitle-line{gap:10px;padding:11px 0;font-size:12px}}

.note-review{display:flex;flex-wrap:wrap;align-items:flex-start;gap:12px;margin-top:15px}
.note-replay{border:1px solid #dfe8d9;background:#f6f8f2;border-radius:7px;padding:7px 11px;color:#55764a;cursor:pointer;font-size:12px}
.note-source-details{flex:1;min-width:150px}
.note-source-details>summary{cursor:pointer;padding:7px 0;color:#788971;font-size:12px}
.note-sources{display:flex;flex-direction:column;align-items:stretch;margin-top:8px;gap:7px}
.note-sources button{text-align:left;line-height:1.7}
.note-source-quote{display:block;color:#7b8673;margin-top:4px;overflow-wrap:anywhere}
</style>
