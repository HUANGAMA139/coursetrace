<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = defineProps({ currentTitle: String, qaBusy: Boolean });
const emit = defineEmits(["visibility"]);
const container = ref(null), panel = ref(null), tab = ref("directory"), drawerOpen = ref(false);
const media = window.matchMedia("(min-width: 1280px) and (min-height: 720px)");
const wide = ref(media.matches);
const panelVisible = computed(() => wide.value || drawerOpen.value);
const qaVisible = computed(() => panelVisible.value && tab.value === "questions");
const directoryViewport=ref(null);
let directoryScroll=0;
function saveDirectoryScroll(){if(tab.value==="directory" && panelVisible.value)directoryScroll=directoryViewport.value.scrollTop;}
watch(panelVisible,async value=>{if(value){await nextTick();if(directoryViewport.value)directoryViewport.value.scrollTop=directoryScroll;}});
watch(tab,async value=>{if(value==="directory"){await nextTick();if(directoryViewport.value)directoryViewport.value.scrollTop=directoryScroll;}});
const viewportHeight = ref(window.visualViewport?.height || window.innerHeight);
const panelHeight = ref(window.innerHeight - 40);
let disposed = false, returnFocus, frame;
function updateViewport() {
  viewportHeight.value = window.visualViewport?.height || window.innerHeight;
  if(wide.value && container.value)panelHeight.value=Math.max(180,viewportHeight.value-Math.max(20,container.value.getBoundingClientRect().top)-20);
}
function scrolled(){if(!frame)frame=requestAnimationFrame(()=>{frame=0;updateViewport();});}
function closed() { drawerOpen.value = false; }
function closeDrawer() { if (!wide.value) panel.value?.close(); }
async function open(value = tab.value) {
  tab.value = value;
  await nextTick();
  if (disposed) return;
  if (!wide.value && !panel.value.open) {
    returnFocus = document.activeElement;
    panel.value.showModal();
    drawerOpen.value = true;
  }
}
function changeTab(value) { tab.value = value; }
function tabKey(event) {
  const tabs = ["directory", "questions"];
  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  changeTab(event.key === "Home" ? tabs[0] : event.key === "End" ? tabs[1] : tabs[1 - tabs.indexOf(tab.value)]);
  nextTick(() => panel.value.querySelector("#study-tab-" + tab.value)?.focus());
}
function backdrop(event) { if (event.target === panel.value && !wide.value) closeDrawer(); }
function resize() {
  const wasOpen = drawerOpen.value;
  if (panel.value?.open) panel.value.close();
  wide.value = media.matches;
  drawerOpen.value = false;
  if (wide.value) panel.value?.show();
  else if (wasOpen) returnFocus?.focus?.();
  nextTick(updateViewport);
}
watch(qaVisible, value => emit("visibility", value), { immediate: true });
onMounted(() => {
  if (wide.value) panel.value.show();
  nextTick(updateViewport);
  window.addEventListener("scroll", scrolled, {passive:true});
  media.addEventListener("change", resize);
  window.visualViewport?.addEventListener("resize", updateViewport);
  window.addEventListener("resize", updateViewport);
});
onBeforeUnmount(() => {
  disposed = true;
  cancelAnimationFrame(frame);
  window.removeEventListener("scroll", scrolled);
  media.removeEventListener("change", resize);
  window.visualViewport?.removeEventListener("resize", updateViewport);
  window.removeEventListener("resize", updateViewport);
  panel.value?.close();
});
defineExpose({ open, closeDrawer });
</script>

<template>
  <aside ref="container" class="study-tools" :style="{height:wide?panelHeight+'px':undefined}" aria-label="课程学习工具">
    <dialog ref="panel" class="study-tools-panel" :style="{ '--tool-viewport': viewportHeight + 'px' }"
      :aria-modal="!wide && drawerOpen ? 'true' : undefined" aria-label="课程目录与问答"
      @close="closed" @click="backdrop">
      <div class="study-tools-top">
        <span>学习工具</span>
        <button v-if="!wide" class="tool-close" type="button" @click="closeDrawer" aria-label="关闭学习工具">关闭</button>
      </div>
      <div class="study-tool-tabs" role="tablist" aria-label="课程工具页签" @keydown="tabKey">
        <button id="study-tab-directory" role="tab" :tabindex="tab === 'directory' ? 0 : -1"
          :aria-selected="tab === 'directory'" aria-controls="study-directory-panel" @click="changeTab('directory')">课程目录</button>
        <button id="study-tab-questions" role="tab" :tabindex="tab === 'questions' ? 0 : -1"
          :aria-selected="tab === 'questions'" aria-controls="study-questions-panel" @click="changeTab('questions')">课程问答<span v-if="qaBusy" class="tool-busy">处理中</span></button>
      </div>
      <p class="study-current" :title="currentTitle">当前视频：{{ currentTitle || "尚未选择" }}</p>
      <!-- Keep both panels mounted so tabs do not reset the request, draft or scroll position. -->
      <div ref="directoryViewport" @scroll="saveDirectoryScroll" v-show="tab === 'directory'" id="study-directory-panel" class="study-directory-panel"
        role="tabpanel" aria-labelledby="study-tab-directory" tabindex="0">
        <slot name="directory" :close="closeDrawer" />
      </div>
      <div v-show="tab === 'questions'" id="study-questions-panel" class="study-questions-panel"
        role="tabpanel" aria-labelledby="study-tab-questions">
        <slot name="questions" :visible="qaVisible" />
      </div>
    </dialog>
  </aside>
</template>

<style scoped>
.study-tools{position:sticky;top:20px;height:calc(100dvh - 40px);min-height:0;align-self:start}
.study-tools-panel{position:static;margin:0;padding:0;width:100%;max-width:none;max-height:none;height:100%;border:1px solid #e2e8de;border-radius:16px;background:#fff;color:#36523e;overflow:hidden}
.study-tools-panel[open]{display:flex;flex-direction:column}
.study-tools-top{display:flex;justify-content:space-between;align-items:center;padding:16px 20px 8px;color:#879780;font-size:11px;flex-shrink:0}
.study-tool-tabs{display:flex;gap:8px;padding:0 16px;border-bottom:1px solid #e8ece5;flex-shrink:0}
.study-tool-tabs>button{flex:1;border:0;border-bottom:2px solid transparent;background:transparent;padding:12px 4px;color:#84907d;cursor:pointer;font-size:14px}
.study-tool-tabs>button[aria-selected="true"]{color:#365c46;border-bottom-color:#507448;font-weight:600}
.tool-busy{font-size:10px;color:#738c5f;margin-left:5px}
.study-current{padding:10px 20px;margin:0;border-bottom:1px solid #edf0e8;font-size:11px;color:#839078;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex-shrink:0}
.study-directory-panel{flex:1;min-height:0;overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable}
.study-questions-panel{flex:1;min-height:0;overflow:hidden}
.tool-close{border:0;background:#f0f4ec;border-radius:6px;padding:6px 12px;font-size:12px;color:#55764a;cursor:pointer}
.study-tools-panel::backdrop{background:rgb(20 36 26 / .38)}
.study-directory-panel :deep(.video-list-panel){margin:0;border:0;border-radius:0}
.study-directory-panel :deep(.video-list-heading){padding:16px;gap:8px}
.study-directory-panel :deep(.video-list-heading>span),.study-directory-panel :deep(.video-date),.study-directory-panel :deep(.video-number){display:none}
.study-directory-panel :deep(.video-items){padding:4px 8px}
.study-directory-panel :deep(.video-item){gap:10px;padding:13px 8px;flex-wrap:wrap}
.study-directory-panel :deep(.video-thumbnail){width:35px;height:35px}
.study-directory-panel :deep(.video-item-copy){flex:1;min-width:0}
.study-directory-panel :deep(.video-item-copy strong){font-size:12px;overflow-wrap:anywhere}
.study-directory-panel :deep(.video-item-state){font-size:10px;max-width:92px;white-space:normal}
.study-directory-panel :deep(.video-pagination){padding:14px 10px;flex-wrap:wrap;font-size:11px}
.study-directory-panel :deep(.video-pagination .button){padding:6px 10px;font-size:11px}
@media(max-width:1279px), (max-height:719px){
  .study-tools{position:static;height:0}
  .study-tools-panel{position:fixed;inset:0;width:min(460px,calc(100% - 24px));height:calc(var(--tool-viewport) - 32px);max-height:820px;margin:auto;box-shadow:0 16px 70px #19351d33}
}
@media(max-width:600px){
  .study-tools-panel{inset:auto 0 0;width:100%;height:calc(var(--tool-viewport) - 20px);max-height:88dvh;margin:0;border-radius:18px 18px 0 0;padding-bottom:env(safe-area-inset-bottom)}
}
</style>
