import { ref } from "vue";

// Shared by the course route and the application shell.
export const uploadInProgress = ref(false);
export const uploadLeaveNotice = ref("");
export function checkUploadLeave() {
  if (!uploadInProgress.value) return true;
  uploadLeaveNotice.value = "请等待当前视频上传完成后再离开课程。";
  return false;
}
export function setUploadInProgress(value) {
  uploadInProgress.value = value;
  if (!value) uploadLeaveNotice.value = "";
}
