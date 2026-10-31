import { computed, onBeforeUnmount, ref, watch } from "vue";
import { api } from "./api.js";
import { isActiveTask } from "./processing.js";

export const POLL_INTERVAL_MS = 2000;

export function useProcessingTasks(courseId, videos, selected, enabled, expired, pipelineVersion=1) {
  const tasks = ref({}), loading = ref(false), error = ref(""), actionError = ref(""),
    busy = ref(false), updatedAt = ref(null);
  let timer, controller, actionController, generation = 0, disposed = false;
  const selectedTask = computed(() => tasks.value[selected.value?.id] || null);
  const ids = computed(() => [...new Set([
    ...videos.value.map(video => video.id), ...(selected.value ? [selected.value.id] : []),
  ])]);
  const signature = computed(() => courseId.value + ":" + ids.value.join(",") + ":" + enabled.value);
  function stopRead() {
    clearTimeout(timer);
    generation++;
    controller?.abort();
    controller = undefined;
    loading.value = false;
  }
  async function refresh() {
    stopRead();
    if (disposed || !enabled.value || document.hidden || !ids.value.length) return;
    const version = generation, current = controller = new AbortController();
    let timedOut = false;
    const deadline = setTimeout(() => { timedOut = true; current.abort(); }, 10000);
    loading.value = true;
    try {
      const results = await api.processingTasks(courseId.value, ids.value, current.signal, pipelineVersion);
      if (disposed || version !== generation) return;
      tasks.value = Object.fromEntries(results.map(task => [task.videoId, task]));
      error.value = "";
      updatedAt.value = new Date();
    } catch (failure) {
      if (disposed || version !== generation) return;
      if (failure.status === 401) {
        stopRead();
        expired(failure);
        return;
      }
      if (failure.name !== "AbortError" || timedOut)
        error.value = "获取状态失败：" + (timedOut ? "请求超时，请刷新重试" : failure.message);
    } finally {
      clearTimeout(deadline);
      if (!disposed && version === generation) {
        loading.value = false;
        controller = undefined;
        if (!document.hidden && (error.value || Object.values(tasks.value).some(isActiveTask)))
          timer = setTimeout(refresh, error.value ? 5000 : POLL_INTERVAL_MS);
      }
    }
  }
  async function act(retry = false, confirm = false) {
    if (busy.value || !selected.value) return;
    const videoId = selected.value.id, task = selectedTask.value;
    if (retry && !(task?.canRetry || confirm && task?.canResubmitUncertain)) return;
    const current = actionController = new AbortController();
    busy.value = true;
    actionError.value = "";
    try {
      const result = retry
        ? await api.retryProcessing(task.id, task.runNo, current.signal, confirm)
        : await api.submitProcessing(videoId, current.signal, pipelineVersion);
      if (disposed || current !== actionController) return;
      tasks.value = { ...tasks.value, [videoId]: result };
      await refresh();
    } catch (failure) {
      if (disposed || current !== actionController || failure.name === "AbortError") return;
      if (failure.status === 401) { expired(failure); return; }
      actionError.value = failure.message + (failure.status === 0 ? "；可刷新状态确认是否已提交" : "");
      await refresh();
    } finally {
      if (current === actionController) { busy.value = false; actionController = undefined; }
    }
  }
  watch(signature, () => {
    stopRead();
    actionController?.abort();
    actionController = undefined;
    busy.value = false;
    actionError.value = "";
    error.value = "";
    updatedAt.value = null;
    const allowed = new Set(ids.value);
    tasks.value = Object.fromEntries(Object.entries(tasks.value).filter(([id]) => allowed.has(Number(id))));
    void refresh();
  }, { immediate: true });
  function visibilityChanged() { if (document.hidden) stopRead(); else void refresh(); }
  document.addEventListener("visibilitychange", visibilityChanged);
  onBeforeUnmount(() => {
    disposed = true;
    stopRead();
    actionController?.abort();
    document.removeEventListener("visibilitychange", visibilityChanged);
  });
  return { tasks, selectedTask, loading, error, actionError, busy, updatedAt, refresh,
    submit: () => act(false), retry: () => act(true), retryConfirmed: () => act(true,true) };
}
