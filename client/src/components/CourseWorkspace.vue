<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from "vue-router";
import { api } from "../api.js";
import Icon from "../Icon.vue";
import VideoUpload from "./VideoUpload.vue";
import VideoList from "./VideoList.vue";
import VideoPlayer from "./VideoPlayer.vue";
import ProcessingTaskPanel from "./ProcessingTaskPanel.vue";
import LearningMaterials from "./LearningMaterials.vue";
import VideoQuestionAnswer from "./VideoQuestionAnswer.vue";
import CourseStudyTools from "./CourseStudyTools.vue";
import { useProcessingTasks } from "../useProcessingTasks.js";
import { checkUploadLeave, setUploadInProgress, uploadLeaveNotice } from "../uploadGuard.js";
const props = defineProps({ ownerId: { type: Number, required: true } });
const emit = defineEmits(["expired"]);
const route = useRoute(),
  router = useRouter();
const activePlayer=ref(null), subtitleVersion=ref(0), learningTasks=ref({});
const studyTools=ref(null), uploadDialog=ref(null), materials=ref(null), qaBusy=ref(false);
const courseId = computed(() => Number(route.params.courseId));
const course = ref(null),
  loading = ref(true),
  error = ref(""),
  videos = ref([]),
  total = ref(0),
  page = ref(1),
  listLoading = ref(false),
  listError = ref(""),
  selected = ref(null),
  selectedError = ref(""),
  selecting = ref(false),
  maxBytes = ref(0),
  success = ref(""),
  busy = ref(false),
  leaveNotice = uploadLeaveNotice;
function beforeUnload(event) {
  if (!busy.value) return;
  event.preventDefault();
  event.returnValue = "";
}
watch(busy, value => {
  setUploadInProgress(value);
  if (value) window.addEventListener("beforeunload", beforeUnload);
  else window.removeEventListener("beforeunload", beforeUnload);
});
function showUpload() {
  if (!uploadDialog.value?.open) uploadDialog.value?.showModal();
}
function closeUpload() { if (!busy.value) uploadDialog.value?.close(); }
function cancelUpload(event) { if (busy.value) event.preventDefault(); }
async function seek(ms) {
  studyTools.value?.closeDrawer();
  await nextTick();
  activePlayer.value?.seekTo(ms);
}
function selectMaterial(tab) { materials.value?.showTab(tab); }
function directorySelect(video) { studyTools.value?.closeDrawer(); select(video); }

const taskEnabled = computed(() => !loading.value && !error.value && course.value?.id === courseId.value);
const processing = useProcessingTasks(courseId, videos, selected, taskEnabled, failure => emit("expired", failure));
let bootController,
  listController,
  selectedController,
  disposed = false;
function handle(error) {
  if (error.status === 401) {
    emit("expired", error);
    return true;
  }
  return false;
}
async function loadSelected() {
  selectedController?.abort();
  selected.value = null; subtitleVersion.value=0; qaBusy.value=false;
  selectedError.value = "";
  const id = Number(route.query.video);
  if (!route.query.video) {
    selecting.value = false;
    if (videos.value.length)
      await router.replace({ query: { video: String(videos.value[0].id) } });
    return;
  }
  if (!Number.isSafeInteger(id) || id < 1) {
    selecting.value = false;
    selectedError.value = "视频地址不正确，请从列表重新选择";
    return;
  }
  const controller = (selectedController = new AbortController());
  selecting.value = true;
  try {
    const result = await api.video(id, controller.signal);
    if (disposed || controller !== selectedController) return;
    if (result.courseId !== courseId.value)
      throw new Error("该视频不属于当前课程，请从列表重新选择");
    selected.value = result;
  } catch (failure) {
    if (
      failure.name !== "AbortError" &&
      controller === selectedController &&
      !disposed &&
      !handle(failure)
    )
      selectedError.value = failure.message;
  } finally {
    if (controller === selectedController && !disposed) selecting.value = false;
  }
}
async function boot() {
  learningTasks.value={};
  bootController?.abort();
  listController?.abort();
  selectedController?.abort();
  const controller = (bootController = new AbortController());
  loading.value = true;
  error.value = "";
  selected.value = null; subtitleVersion.value=0;
  success.value = "";
  try {
    const [details, list, policy] = await Promise.all([
      api.course(courseId.value, controller.signal),
      api.videos(courseId.value, 1, controller.signal),
      api.uploadPolicy(controller.signal),
    ]);
    if (disposed || controller !== bootController) return;
    course.value = details;
    videos.value = list.items;
    total.value = list.total;
    page.value = list.page;
    maxBytes.value = policy.maxBytes;
    loading.value = false;
    await loadSelected();
  } catch (failure) {
    if (
      failure.name !== "AbortError" &&
      controller === bootController &&
      !disposed &&
      !handle(failure)
    )
      error.value = failure.message;
  } finally {
    if (controller === bootController && !disposed) loading.value = false;
  }
}
async function loadVideos(target = page.value) {
  listController?.abort();
  const controller = (listController = new AbortController());
  listLoading.value = true;
  listError.value = "";
  try {
    const list = await api.videos(courseId.value, target, controller.signal);
    if (disposed || controller !== listController) return;
    videos.value = list.items;
    total.value = list.total;
    page.value = list.page;
  } catch (failure) {
    if (
      failure.name !== "AbortError" &&
      controller === listController &&
      !disposed &&
      !handle(failure)
    )
      listError.value = failure.message;
  } finally {
    if (controller === listController && !disposed) listLoading.value = false;
  }
}
function select(video) {
  router.push({ query: { video: String(video.id) } });
}
async function uploaded(video) {
  success.value = "视频已上传，可以开始回顾课程了。";
  await loadVideos(1);
  select(video);
  closeUpload();
}
watch(() => route.params.courseId, boot, { immediate: true });
watch(
  () => route.query.video,
  () => {
    if (!loading.value && !error.value) loadSelected();
  },
);
onBeforeRouteLeave(checkUploadLeave);
onBeforeRouteUpdate((to, from) => {
  if (to.params.courseId !== from.params.courseId) return checkUploadLeave();
});
onBeforeUnmount(() => {
  disposed = true;
  setUploadInProgress(false);
  window.removeEventListener("beforeunload", beforeUnload);
  uploadDialog.value?.close();
  bootController?.abort();
  listController?.abort();
  selectedController?.abort();
});
</script>
<template>
  <div class="content course-workspace">
    <button class="back-to-courses" :disabled="busy" @click="router.push('/')"><Icon name="arrow-left" :size="16" />返回我的课程</button>
    <div v-if="loading" class="workspace-loading" role="status">正在打开课程…</div>
    <div v-else-if="error" class="error-banner" role="alert"><span>{{ error }}</span><button @click="boot">重试</button></div>
    <template v-else-if="course">
      <div class="course-learning-grid">
        <div class="course-viewing-column">
      <header class="course-page-intro">
        <div class="course-page-badge"><Icon name="book" :size="24" /></div>
        <div class="course-heading-copy">
          <p class="eyebrow">YOUR COURSE, YOUR PACE</p><h1>{{ course.title }}</h1>
          <p>{{ course.description || "跟随自己的节奏，把每一课学得更清楚。" }}</p>
        </div>
        <div class="course-heading-actions">
          <span class="course-only-you"><Icon name="lock" :size="14" />仅你可见</span>
          <button class="button secondary" @click="showUpload">添加课程视频</button>
          <button class="course-details-entry" @click="showUpload">资源检查与处理详情</button>
        </div>
      </header>
      <div v-if="success" class="upload-success" role="status"><Icon name="check" :size="18" />{{ success }}
        <button class="icon-button" aria-label="关闭上传成功提示" @click="success=''"><Icon name="close" :size="15" /></button>
      </div>
      <p v-if="leaveNotice && busy" class="form-error" role="alert">{{ leaveNotice }}</p>

          <VideoPlayer v-if="selected" :key="selected.id" ref="activePlayer" :video="selected" :subtitle-version="subtitleVersion" @expired="emit('expired',$event)" />
          <div v-else class="player-placeholder">
            <span class="player-placeholder-icon"><Icon :name="selectedError?'alert':'play'" :size="38" /></span>
            <h2>{{ selecting ? "正在准备课程视频…" : selectedError ? "暂时无法打开视频" : "让每一次回看，都有新的理解。" }}</h2>
            <p :role="selectedError?'alert':undefined">{{ selectedError || "上传或选择一段视频，开始你的课程回顾。" }}</p>
            <button v-if="!total" class="button primary" @click="showUpload">添加第一段视频</button>
          </div>
          <LearningMaterials v-if="selected" :key="selected.id" ref="materials" :video="selected" :course-id="courseId" :videos="videos"
            @tasks="learningTasks=$event" @seek="seek" @transcript="subtitleVersion=$event" @refresh-subtitles="activePlayer?.reloadSubtitles()" @expired="emit('expired',$event)" />
        </div>
        <CourseStudyTools ref="studyTools" :current-title="selected?.title" :qa-busy="qaBusy">
          <template #directory>
            <VideoList :items="videos" :tasks="{...processing.tasks.value,...learningTasks}" :total="total" :page="page"
              :selected-id="selected?.id" :loading="listLoading" :error="listError" @select="directorySelect" @page="loadVideos" @retry="loadVideos()" />
          </template>
          <template #questions="{visible}">
            <VideoQuestionAnswer v-if="selected" :key="selected.id" :visible="visible" :video="selected" :owner-id="props.ownerId"
              :transcript-version="subtitleVersion" @activity="qaBusy=$event" @seek="seek" @expired="emit('expired',$event)" />
            <p v-else class="qa-select-first">先从课程目录选择视频，再开始提问。</p>
          </template>
        </CourseStudyTools>
      </div>
      <nav class="mobile-study-nav" aria-label="移动端学习工具">
        <button :disabled="!selected" @click="selectMaterial('subtitles')">字幕</button>
        <button :disabled="!selected" @click="selectMaterial('notes')">笔记</button>
        <button @click="studyTools?.open('directory')">课程目录</button>
        <button @click="studyTools?.open('questions')">课程问答<span v-if="qaBusy"> · 处理中</span></button>
      </nav>
      <!-- Management stays mounted while its dialog is closed; uploads never reset on a tool-tab change. -->
      <dialog id="course-upload-tools" ref="uploadDialog" class="course-upload-dialog" aria-label="添加视频与资源检查"
        @cancel="cancelUpload" @click="$event.target===uploadDialog && closeUpload()">
        <header class="upload-dialog-heading"><h2>视频与处理详情</h2><button class="icon-button" :disabled="busy" @click="closeUpload" aria-label="关闭视频管理"><Icon name="close" :size="18" /></button></header>
        <p v-if="busy" class="learning-hint">视频正在上传，请等待完成后关闭。</p>
        <VideoUpload :key="courseId" :course-id="courseId" :owner-id="props.ownerId" :max-bytes="maxBytes" @uploaded="uploaded" @expired="emit('expired',$event)" @busy="busy=$event" />
        <ProcessingTaskPanel :video="selected" :task="processing.selectedTask.value" :loading="processing.loading.value"
          :busy="processing.busy.value" :error="processing.error.value" :action-error="processing.actionError.value"
          :updated-at="processing.updatedAt.value" @submit="processing.submit" @retry="processing.retry" @refresh="processing.refresh" />
      </dialog>
    </template>
    <footer class="workspace-footer"><span>一点一滴，留下理解的轨迹。</span><span>COURSETRACE</span></footer>
  </div>
</template>

<style scoped>
.course-learning-grid{grid-template-columns:minmax(0,1fr) clamp(360px,28vw,420px);gap:24px;align-items:start}
.course-heading-copy{flex:1;min-width:0}.course-heading-copy h1{overflow-wrap:anywhere}
.course-heading-actions{display:flex;flex-direction:column;align-items:flex-end;gap:8px;flex-shrink:0}
.course-heading-actions .course-only-you{padding:0}.course-heading-actions .button{font-size:12px;padding:9px 13px}
.course-details-entry{border:0;background:transparent;font-size:11px;color:#839078;cursor:pointer}
.mobile-study-nav{display:none}
.qa-select-first{padding:24px;font-size:13px;color:#839078}
.course-upload-dialog{width:min(650px,calc(100% - 28px));max-height:90dvh;padding:20px;border:1px solid #e2e8de;border-radius:16px;background:#f9fbf7;color:#36523e;overflow-y:auto}
.course-upload-dialog::backdrop{background:rgb(20 36 26 / .38)}
.upload-dialog-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:15px}
.upload-dialog-heading h2{font-size:17px}.course-upload-dialog :deep(.processing-panel){margin-top:16px}
@media(max-width:1439px) and (min-width:1280px){.course-workspace{padding-left:24px;padding-right:24px}.course-learning-grid{gap:18px}}
@media(max-width:1279px), (max-height:719px){
  .course-learning-grid{grid-template-columns:minmax(0,1fr);gap:0}
  .course-workspace{padding-bottom:70px}
  .mobile-study-nav{display:flex;position:fixed;bottom:0;left:233px;right:0;z-index:20;background:#fff;border-top:1px solid #dfe7d9;padding:8px 12px calc(8px + env(safe-area-inset-bottom));box-shadow:0 -4px 20px #19351d08}
  .mobile-study-nav button{flex:1;border:0;background:transparent;color:#507448;font-size:12px;cursor:pointer;padding:9px 4px}
  .mobile-study-nav button:disabled{opacity:.45}
}
@media(max-width:1150px){.mobile-study-nav{left:208px}}
@media(max-width:780px){.mobile-study-nav{left:0}}
@media(max-width:600px){
  .course-page-intro{gap:12px}.course-heading-actions{width:100%;flex-direction:row;align-items:center;justify-content:flex-end}
  .course-upload-dialog{padding:12px}.course-heading-actions .course-only-you{display:none}
}
</style>
