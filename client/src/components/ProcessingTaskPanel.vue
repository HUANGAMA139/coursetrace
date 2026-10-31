<script setup>
import { computed } from "vue";
import { taskLabel } from "../processing.js";
import { fileSize } from "../format.js";
const props = defineProps({
  video: Object, task: Object, loading: Boolean, busy: Boolean,
  error: String, actionError: String, updatedAt: Date,
});
defineEmits(["submit", "retry", "refresh"]);
const step = computed(() => props.task?.steps?.[0]);
const result = computed(() => step.value?.result);
const date = value => value ? new Intl.DateTimeFormat("zh-CN", {
  month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit",
}).format(new Date(value)) : "";
</script>

<template>
  <section class="processing-panel" aria-labelledby="processing-heading">
    <div class="processing-heading">
      <div><p class="eyebrow">VIDEO CHECK</p><h2 id="processing-heading">视频资源检查</h2></div>
      <button class="processing-refresh" :disabled="loading || busy || !video" @click="$emit('refresh')">刷新状态</button>
    </div>
    <p v-if="!video" class="processing-hint">选择一段视频，检查课程文件是否可用。</p>
    <template v-else>
      <p class="processing-video">{{ video.title }}</p>
      <p class="processing-status" :data-status="task?.status || 'NONE'" role="status">
        <span class="processing-dot"></span>{{ loading && !task ? "正在读取检查状态…" : taskLabel(task) }}
      </p>
      <p class="processing-hint">确认课程文件存在，并与上传记录一致。</p>
      <dl v-if="task" class="processing-details">
        <div><dt>当前步骤</dt><dd>视频资源检查</dd></div>
        <div><dt>本轮尝试</dt><dd>{{ step?.attemptCount || 0 }} / {{ step?.maxAttempts || 3 }}</dd></div>
        <div><dt>执行轮次</dt><dd>第 {{ task.runNo }} 轮</dd></div>
      </dl>
      <p v-if="result" class="processing-result">已确认 {{ fileSize(result.actualSizeBytes) }} 的视频文件<br /><span>检查于 {{ date(result.checkedAt) }}</span></p>
      <p v-if="task?.errorMessage" class="processing-message" :role="task.status === 'FAILED' ? 'alert' : undefined">{{ task.errorMessage }}</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
      <p v-if="updatedAt" class="processing-updated">状态更新于 {{ date(updatedAt) }}</p>
      <button v-if="!task" class="button primary processing-submit" :disabled="loading || busy || !!error" @click="$emit('submit')">{{ busy ? "正在提交…" : "检查视频" }}</button>
      <button v-else-if="task.canRetry" class="button primary processing-submit" :disabled="loading || busy || !!error" @click="$emit('retry')">{{ busy ? "正在提交…" : "重新检查" }}</button>
    </template>
  </section>
</template>

<style scoped>
.processing-panel { padding: 25px; border: 1px solid #e4e8e0; border-radius: 18px; background: #fff; min-width: 0; }
.processing-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.processing-heading .eyebrow { margin: 0 0 8px; }
.processing-heading h2 { margin: 0; font-size: 17px; }
.processing-refresh { border: 0; background: transparent; color: #587150; font-size: 12px; padding: 7px 0; cursor: pointer; white-space: nowrap; }
.processing-refresh:disabled { opacity: .5; cursor: default; }
.processing-video { font-size: 13px; line-height: 1.6; margin: 21px 0 13px; overflow-wrap: anywhere; }
.processing-status { display: flex; align-items: center; gap: 9px; font-size: 14px; font-weight: 600; color: #60715b; }
.processing-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; flex-shrink: 0; }
.processing-status[data-status="FAILED"] { color: #ac4940; }
.processing-status[data-status="RETRY_WAIT"] { color: #97733a; }
.processing-hint, .processing-message { font-size: 12px; line-height: 1.8; color: #7c8177; overflow-wrap: anywhere; }
.processing-details { margin: 18px 0; font-size: 12px; }
.processing-details div { display: flex; justify-content: space-between; gap: 15px; margin: 10px 0; }
.processing-details dt { color: #83887d; }
.processing-details dd { margin: 0; }
.processing-result { border-radius: 10px; background: #f0f5ed; padding: 13px; color: #4c6644; font-size: 12px; line-height: 1.8; }
.processing-result span, .processing-updated { font-size: 11px; color: #83887d; }
.processing-submit { width: 100%; margin-top: 9px; }
@media (max-width: 600px) { .processing-panel { padding: 21px 17px; } }
</style>
