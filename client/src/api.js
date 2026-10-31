export class ApiError extends Error {
  constructor(message, status = 0, code = "NETWORK_ERROR", fieldErrors = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

let csrf;

export async function refreshCsrf() {
  const response = await fetch("/api/auth/csrf", {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  const json = await response.json();
  if (!response.ok || !json.data?.token)
    throw new ApiError("暂时无法连接服务，请稍后重试", response.status);
  csrf = json.data;
  return csrf;
}

export async function request(path, options = {}, retried = false) {
  const method = options.method || "GET";
  const writes = !["GET", "HEAD"].includes(method);
  if (writes && !csrf) await refreshCsrf();
  let response;
  try {
    response = await fetch(path, {
      ...options,
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        ...options.headers,
        ...(writes ? { [csrf.headerName]: csrf.token } : {}),
      },
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new ApiError("暂时无法连接服务，请检查连接后重试");
  }
  const json = await response.json().catch(() => ({}));
  // A CSRF rejection happens before business execution, so retrying it once cannot duplicate a creation.
  if (response.status === 403 && json.code === "CSRF_INVALID" && !retried) {
    await refreshCsrf();
    return request(path, options, true);
  }
  if (!response.ok) {
    const retry=Math.max(0,Number(response.headers.get("Retry-After"))||0);
    const failure=new ApiError(
      json.message || "服务暂时不可用，请稍后重试",
      response.status,
      json.code,
      json.fieldErrors,
    );
    failure.retryAfterSeconds=retry;
    if(retry)failure.message+="（约 "+retry+" 秒后可重试）";
    throw failure;
  }
  return json.data;
}

async function uploadVideo(
  courseId,
  file,
  title,
  options = {},
  retried = false,
) {
  if (!csrf) await refreshCsrf();
  if (options.signal?.aborted)
    throw new DOMException("Upload aborted", "AbortError");
  const result = await new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const finish = () => options.signal?.removeEventListener("abort", abort);
    xhr.open("POST", "/api/courses/" + courseId + "/videos");
    xhr.withCredentials = true;
    xhr.timeout = 300000;
    xhr.setRequestHeader("Accept", "application/json");
    xhr.setRequestHeader(csrf.headerName, csrf.token);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable)
        options.onProgress?.(
          Math.min(100, Math.round((event.loaded / event.total) * 100)),
        );
    };
    xhr.onload = () => {
      finish();
      let json = {};
      try {
        json = JSON.parse(xhr.responseText);
      } catch {}
      resolve({ status: xhr.status, json });
    };
    xhr.onerror = () => {
      finish();
      reject(
        new ApiError(
          "连接中断，上传结果尚未确认。请先查看视频列表，再决定是否重试",
        ),
      );
    };
    xhr.ontimeout = () => {
      finish();
      reject(new ApiError("上传超时，请先查看视频列表，确认结果后再重试"));
    };
    xhr.onabort = () => {
      finish();
      reject(new DOMException("Upload aborted", "AbortError"));
    };
    options.signal?.addEventListener("abort", abort, { once: true });
    const form = new FormData();
    form.append("file", file);
    form.append("title", title);
    options.onProgress?.(0);
    xhr.send(form);
  });
  if (
    result.status === 403 &&
    result.json.code === "CSRF_INVALID" &&
    !retried
  ) {
    await refreshCsrf();
    return uploadVideo(courseId, file, title, options, true);
  }
  if (result.status !== 201)
    throw new ApiError(
      result.json.message || "上传失败，请稍后重试",
      result.status,
      result.json.code,
    );
  return result.json.data;
}

export const api = {
  agentRun: (videoId, turnId, signal) => request(`/api/videos/${videoId}/questions/${turnId}/agent-run`, {signal}),
  searchIndex: (id,signal) => request("/api/videos/"+id+"/search-index",{signal}),
  createSearchIndex: (id,signal) => request("/api/videos/"+id+"/search-index",{method:"POST",signal}),
  rebuildSearchIndex: (id,data,signal) => request("/api/videos/"+id+"/search-index/rebuild",{method:"POST",signal,headers:{"Content-Type":"application/json"},body:JSON.stringify(data)}),
  questionRequest: (id,requestId,signal) => request("/api/videos/"+id+"/questions/request/"+encodeURIComponent(requestId),{signal}),
  questions: (id,page=1,signal) => request("/api/videos/"+id+"/questions?page="+page+"&size=20",{signal}),
  askQuestion: (id,data,signal) => request("/api/videos/"+id+"/questions",{method:"POST",signal,headers:{"Content-Type":"application/json"},body:JSON.stringify(data)}),
  processingTasks: (courseId, ids, signal, pipelineVersion=1) => request("/api/courses/" + courseId + "/processing-tasks?videoIds=" + ids.join(",") + "&pipelineVersion=" + pipelineVersion, { signal }),
  processingTask: (id, signal) => request("/api/processing-tasks/" + id, { signal }),
  submitProcessing: (id, signal, pipelineVersion=1) => request("/api/videos/" + id + "/processing-tasks", { method: "POST", signal, ...(pipelineVersion===2 ? {headers:{"Content-Type":"application/json"},body:JSON.stringify({pipelineVersion})} : {}) }),
  retryProcessing: (id, expectedRunNo, signal, confirmResubmitUncertain=false) => request("/api/processing-tasks/" + id + "/retry", {
    method: "POST", signal, headers: { "Content-Type": "application/json" }, body: JSON.stringify({ expectedRunNo, confirmResubmitUncertain }),
  }),
  learningCapabilities: (signal) => request("/api/learning-capabilities",{signal}),
  transcript: (id,signal) => request("/api/videos/"+id+"/transcript",{signal}),
  segments: (id,page,signal,atMs) => request("/api/videos/"+id+"/segments?page="+page+"&size=50"+(atMs==null?"":"&atMs="+atMs),{signal}),
  notes: (id,signal) => request("/api/videos/"+id+"/notes",{signal}),
  uploadVideo,
  uploadPolicy: (signal) => request("/api/video-upload-policy", { signal }),
  videos: (courseId, page = 1, signal) =>
    request("/api/courses/" + courseId + "/videos?page=" + page + "&size=20", {
      signal,
    }),
  video: (id, signal) => request("/api/videos/" + id, { signal }),
  me: () => request("/api/auth/me"),
  async login(username, password) {
    const user = await request("/api/auth/login", {
      method: "POST",
      body: new URLSearchParams({ username, password }),
    });
    // Successful authentication changes the session and clears its previous CSRF token.
    await refreshCsrf();
    return user;
  },
  async logout() {
    await request("/api/auth/logout", { method: "POST" });
    csrf = undefined;
  },
  courses(page = 1, keyword = "", signal) {
    const query = new URLSearchParams({
      page: String(page),
      size: "9",
      keyword,
    });
    return request(`/api/courses?${query}`, { signal });
  },
  course: (id, signal) => request(`/api/courses/${id}`, { signal }),
  createCourse: (data) =>
    request("/api/courses", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    }),
};
