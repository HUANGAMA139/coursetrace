<script setup>
import Icon from "../Icon.vue";
import { fileSize } from "../format.js";
import { taskLabel } from "../processing.js";
defineProps({
  items: Array,
  tasks: { type: Object, default: () => ({}) },
  total: Number,
  page: Number,
  selectedId: Number,
  loading: Boolean,
  error: String,
});
defineEmits(["select", "page", "retry"]);
function duration(ms) {
  const seconds = Math.round(ms / 1000);
  return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
}
const date = (value) =>
  new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric" }).format(
    new Date(value),
  );
</script>
<template>
  <section class="video-list-panel" aria-labelledby="video-list-heading">
    <div class="video-list-heading">
      <div>
        <Icon name="video" :size="20" />
        <h2 id="video-list-heading">课程视频</h2>
        <span class="count-badge">{{ total }}</span>
      </div>
      <span>按上传时间排序</span>
    </div>
    <div v-if="error" class="error-banner" role="alert">
      <span>{{ error }}</span
      ><button @click="$emit('retry')">重试</button>
    </div>
    <div v-else-if="loading" class="video-list-loading" role="status">
      正在读取视频列表…
    </div>
    <div v-else-if="!items.length" class="video-list-empty">
      <span class="empty-video-icon"><Icon name="video" :size="32" /></span>
      <h3>第一段讲解，从这里开始。</h3>
      <p>上传一段课程视频，把值得回顾的内容留在这里。</p>
    </div>
    <div v-else class="video-items">
      <button
        v-for="(video, index) in items"
        :key="video.id"
        class="video-item"
        :class="{ selected: selectedId === video.id }"
        :aria-label="'播放视频：' + video.title"
        :aria-current="selectedId === video.id ? 'true' : undefined"
        @click="$emit('select', video)"
      >
        <span class="video-number">{{
          String((page - 1) * 20 + index + 1).padStart(2, "0")
        }}</span
        ><span class="video-thumbnail"
          ><Icon :name="selectedId === video.id ? 'play' : 'video'" :size="23"
        /></span>
        <span class="video-item-copy"
          ><strong>{{ video.title }}</strong
          ><span
            >{{ duration(video.durationMs) }} · {{ fileSize(video.sizeBytes)
            }}<span class="video-date">
              · {{ date(video.createdAt) }} 上传</span
            ></span
          ></span
        >
        <span class="video-item-state">{{
          tasks[video.id] ? taskLabel(tasks[video.id]) : selectedId === video.id ? "正在学习" : duration(video.durationMs)
        }}</span
        ><Icon name="chevron" :size="15" />
      </button>
    </div>
    <nav
      v-if="total > 20"
      class="pagination video-pagination"
      aria-label="视频分页"
    >
      <span>第 {{ page }} / {{ Math.ceil(total / 20) }} 页</span>
      <div>
        <button
          class="button secondary"
          :disabled="loading || page <= 1"
          @click="$emit('page', page - 1)"
        >
          上一页</button
        ><button
          class="button secondary"
          :disabled="loading || page * 20 >= total"
          @click="$emit('page', page + 1)"
        >
          下一页
        </button>
      </div>
    </nav>
  </section>
</template>
