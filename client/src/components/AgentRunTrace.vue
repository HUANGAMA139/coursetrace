<script setup>
import {onBeforeUnmount,ref,watch} from "vue";
import {api} from "../api.js";
const props=defineProps({videoId:Number,turnId:Number,summary:Object,visible:Boolean});
const emit=defineEmits(["seek","expired"]);
const open=ref(false),detail=ref(null),error=ref("");
let timer,controller,disposed=false;
const labels={SEARCH:"正在搜索资料",CONTEXT:"正在补读上下文",DECIDE:"正在选择下一步",FINAL:"正在整理回答",FINISHED:"处理已结束"};
const statuses={STARTED:"处理中",SUCCEEDED:"已完成",REJECTED:"参数未通过",FAILED:"未能完成",UNKNOWN:"结果未确认"};
const time=ms=>Math.floor(ms/60000)+":"+String(Math.floor(ms/1000)%60).padStart(2,"0");
const reasons={QDRANT_UNAVAILABLE:"课程检索暂不可用",AGENT_MODEL_UNKNOWN:"模型结果未能确认",RESULT_LIMIT:"达到材料输出上限",INPUT_LIMIT:"达到上下文上限",ANSWER_READY:"材料已收集完成",NO_EVIDENCE:"资料不足",ROUND_LIMIT:"达到收集轮数",TOOL_LIMIT:"达到工具次数",TIME_LIMIT:"达到时间限制",LOOP_DETECTED:"连续调用未增加材料",SEARCH_LIMIT:"达到搜索次数",CONTEXT_LIMIT:"达到补读次数",EXECUTION_EXPIRED:"执行中断或超时",INVALID_ANSWER:"回答引用未通过校验"};
async function load(){
 clearTimeout(timer);controller?.abort();
 if(disposed||!open.value||!props.visible)return;
 const current=controller=new AbortController();let timeout=setTimeout(()=>current.abort(),15000);
 try{
  const data=await api.agentRun(props.videoId,props.turnId,current.signal);
  if(disposed||controller!==current)return;
  detail.value=data;error.value="";
 }catch(e){if(e.status===401)emit("expired",e);else if(!disposed&&controller===current)error.value=e.name==="AbortError"?"读取过程超时，可收起后重试":e.message;}
 finally{clearTimeout(timeout);if(!disposed&&controller===current&&detail.value?.status==="RUNNING")timer=setTimeout(load,2000);}
}
watch([open,()=>props.visible,()=>props.summary?.status],load);
onBeforeUnmount(()=>{disposed=true;clearTimeout(timer);controller?.abort();});
</script>
<template>
 <div class="agent-trace" :data-agent-run="summary.id">
  <p class="qa-meta">深入问答 · {{ labels[summary.phase]||summary.status }} · 搜索 {{ summary.searchCalls }} 次 / 补读 {{ summary.contextCalls }} 次</p>
  <p v-if="summary.limited" class="qa-meta">依据已找到资料回答，处理已达上限。</p>
  <details @toggle="open=$event.target.open">
   <summary>查看处理过程</summary>
   <p v-if="error" class="form-error" role="alert">{{ error }}</p>
   <template v-if="detail">
    <p class="qa-meta">{{ reasons[detail.stopReason]||(detail.status==="RUNNING"?"处理中":"本次处理已停止") }} · 工具 {{ detail.toolCalls }}/{{ detail.config.budget.maxTools }} · 决策 {{ detail.rounds }}/{{ detail.config.budget.maxRounds }}</p>
    <ol>
     <li v-for="step in detail.steps" :key="step.seq" :data-agent-step="step.seq" :data-tool-name="step.kind==='TOOL'?step.name:undefined">
      <strong>{{ step.kind==='TOOL'?(step.name==='searchSegments'?'搜索课程':step.name==='readSurroundingContext'?'补读上下文':'拒绝工具请求'):(step.name==='finalAnswer'?'生成有依据的回答':'选择下一步操作') }}</strong>
      <span class="qa-meta"> · {{ statuses[step.status]||"处理结束" }} · {{ step.elapsedMs }}ms{{ step.cached?' · 复用本轮结果':'' }}</span>
      <p v-if="step.arguments?.query" class="qa-meta">查询：{{ step.arguments.query }}</p>
      <p v-if="step.errorCode" class="qa-meta">{{ step.result?.message||reasons[step.errorCode]||"本步骤未能完成，请查看本次问答结果" }}</p>
      <div v-if="step.kind==='TOOL'&&step.result?.sources" class="qa-citations">
       <button v-for="source in step.result.sources" :key="source.segmentId+':'+source.startOffset" type="button" :data-agent-source="source.segmentId" :data-begin-ms="source.beginMs" :title="source.text" @click="emit('seek',source.beginMs)">回看 {{ time(source.beginMs) }}<span>{{ source.text }}</span></button>
      </div>
     </li>
    </ol>
   </template>
   <p v-else-if="!error" class="qa-meta">正在读取实际执行步骤…</p>
  </details>
 </div>
</template>
<style scoped>
.agent-trace{margin:12px 0;padding:12px;border:1px solid var(--border,#d8dfd4);border-radius:12px;background:#f4f7f0;}
.qa-citations{display:flex;gap:7px;flex-direction:column;margin:8px 0 13px}
.qa-citations button{width:100%;text-align:left;cursor:pointer;border:1px solid #dfe8d9;background:#f6f8f2;border-radius:8px;color:#55764a;padding:7px 10px;font:inherit;font-size:11px}
.qa-citations button:focus-visible{outline:2px solid #86a078;outline-offset:2px}
.qa-citations span{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;margin-top:4px;color:#84917a;font-size:11px;line-height:1.7}
summary{cursor:pointer;color:#365841;font-size:.9rem;}
ol{padding-left:22px;}li{margin:12px 0;font-size:.88rem;}p{overflow-wrap:anywhere;}
</style>
