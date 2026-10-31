export const taskLabels = {
  PENDING: "等待处理",
  RUNNING: "正在检查视频",
  RETRY_WAIT: "等待自动重试",
  SUCCEEDED: "视频检查通过",
  FAILED: "视频检查失败",
};
export const taskLabel = (task) => task ? (task.pipelineVersion===2 ? ({PENDING:"等待生成",RUNNING:"生成学习资料",WAITING_EXTERNAL:"云端转录中",RETRY_WAIT:"等待重试",SUCCEEDED:"学习资料就绪",FAILED:"生成未完成"}[task.status]||"正在读取状态") : taskLabels[task.status] || "正在读取状态") : "尚未检查";
export const isActiveTask = (task) => ["PENDING", "RUNNING", "RETRY_WAIT", "WAITING_EXTERNAL"].includes(task?.status);
