<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from "vue";
import { api } from "../api.js";
import Icon from "../Icon.vue";
import { fileSize } from "../format.js";
const props=defineProps({video:{type:Object,required:true},subtitleVersion:{type:Number,default:0}});
const emit=defineEmits(["expired"]);
const player=ref(null),failure=ref(""),captionFailure=ref(""),attempt=ref(0),loaded=ref(false),captionAttempt=ref(0),seekNotice=ref("");
let disposed=false,pendingSeek=null,noticeTimer;
function stop(){
  const element=player.value;
  if(element){element.pause();element.removeAttribute("src");element.load();}
}
async function failed(){
  if(disposed)return;
  failure.value="视频暂时无法播放，请重试。若问题持续，请检查文件编码或存储服务。";
  try{await api.me();}catch(error){if(!disposed&&error.status===401)emit("expired",error);}
}
async function captionFailed(){
  if(disposed)return;captionFailure.value="字幕暂时无法加载，请重新加载字幕。";
  try{await api.me();}catch(error){if(!disposed&&error.status===401)emit("expired",error);}
}
function applySeek(){
  const element=player.value;
  if(pendingSeek==null||!element||!Number.isFinite(element.duration))return;
  element.currentTime=Math.max(0,Math.min(pendingSeek/1000,Math.max(0,element.duration-.05)));
  pendingSeek=null;element.play().catch(()=>{});
}
function seekTo(ms){
  if(!Number.isFinite(ms)||ms<0)return;
  pendingSeek=ms;
  if(loaded.value)applySeek();
  const seconds=Math.floor(ms/1000);
  seekNotice.value="已定位至 "+Math.floor(seconds/60)+":"+String(seconds%60).padStart(2,"0");
  clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>{seekNotice.value="";},5000);
  const element=player.value;
  if(element){
    const rect=element.getBoundingClientRect();
    if(rect.top<0||rect.bottom>window.innerHeight)
      element.scrollIntoView({block:"center",behavior:window.matchMedia("(prefers-reduced-motion: reduce)").matches?"instant":"smooth"});
  }
}
function reloadSubtitles(){captionFailure.value="";captionAttempt.value++;}

function metadata(){loaded.value=true;applySeek();}
async function retry(){stop();failure.value="";loaded.value=false;attempt.value++;await nextTick();player.value?.load();}
watch(()=>props.video.id,()=>{stop();failure.value="";captionFailure.value="";loaded.value=false;pendingSeek=null;});
watch(()=>props.subtitleVersion,()=>{captionFailure.value="";});
onBeforeUnmount(()=>{disposed=true;pendingSeek=null;clearTimeout(noticeTimer);stop();});
defineExpose({seekTo,reloadSubtitles});
</script>

<template>
  <section class="player-panel" aria-labelledby="playing-title">
    <div class="player-surface">
      <video :key="video.id+'-'+attempt" ref="player" :src="video.contentUrl" controls playsinline preload="metadata"
        :aria-label="'播放：'+video.title" @loadedmetadata="metadata" @error="failed">
        <track v-if="subtitleVersion" :key="video.id+'-'+subtitleVersion+'-'+captionAttempt" kind="subtitles" srclang="zh" label="课程字幕" default
          :src="'/api/videos/'+video.id+'/subtitles.vtt?version='+subtitleVersion+'&reload='+captionAttempt" @error="captionFailed" />
      </video>
      <div v-if="failure" class="player-failure" role="alert"><Icon name="alert" :size="26"/><p>{{ failure }}</p><button class="button secondary" @click="retry">重新播放</button></div>
    </div>
    <div class="player-caption">
      <div><span class="eyebrow">NOW LEARNING</span><h2 id="playing-title">{{ video.title }}</h2>
        <p>{{ video.width }} × {{ video.height }} · {{ fileSize(video.sizeBytes) }} · {{ video.audioCodec ? "含课程音频" : "无音轨" }}</p>
        <p v-if="captionFailure" class="form-error" role="alert">{{ captionFailure }} <button class="button secondary" @click="reloadSubtitles">重新加载字幕</button></p>
        <p v-if="seekNotice" class="seek-notice" role="status">{{ seekNotice }}</p>
      </div>
      <span class="player-private"><Icon name="lock" :size="14"/>个人课程</span>
    </div>
  </section>
</template>

<style scoped>
.seek-notice{font-size:12px;color:#55764a;margin:10px 0 0}.player-panel{scroll-margin-top:20px}
</style>
