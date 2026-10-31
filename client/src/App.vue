<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { api, refreshCsrf } from "./api.js";
import Icon from "./Icon.vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { checkUploadLeave, uploadInProgress } from "./uploadGuard.js";
const route = useRoute(),
  router = useRouter();

const ready = ref(false);
const user = ref(null);
const username = ref("student");
const password = ref("");
const loginBusy = ref(false);
const loginError = ref("");
const loggingOut = ref(false);
const courses = ref([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const listError = ref("");
const searchDraft = ref("");
const keyword = ref("");
const pages = computed(() => Math.max(1, Math.ceil(total.value / 9)));
const newCourseId = ref(null);
const toast = ref("");
const createDialog = ref(null);

const title = ref("");
const description = ref("");
const creating = ref(false);
const createError = ref("");
const fieldErrors = ref({});

let loadController;
let toastTimer;

const dateLabel = (value) =>
  new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric" }).format(
    new Date(value),
  );

function handleExpired(error) {
  if (error.status !== 401) return false;
  user.value = null;
  courses.value = [];

  createDialog.value?.close();
  loginError.value = "登录已过期，请重新登录";
  return true;
}

async function loadCourses(targetPage = page.value) {
  loadController?.abort();
  const controller = new AbortController();
  loadController = controller;
  loading.value = true;
  listError.value = "";
  try {
    const result = await api.courses(
      targetPage,
      keyword.value,
      controller.signal,
    );
    if (controller !== loadController) return;
    courses.value = result.items;
    total.value = result.total;
    page.value = result.page;
  } catch (error) {
    if (error.name === "AbortError" || controller !== loadController) return;
    if (!handleExpired(error)) listError.value = error.message;
  } finally {
    if (controller === loadController) loading.value = false;
  }
}

async function bootstrap() {
  try {
    await refreshCsrf();
    user.value = await api.me();
    await loadCourses(1);
  } catch (error) {
    if (error.status !== 401)
      loginError.value = "暂时无法连接服务，请稍后重新登录";
  } finally {
    ready.value = true;
  }
}

async function login() {
  loginBusy.value = true;
  loginError.value = "";
  try {
    user.value = await api.login(username.value.trim(), password.value);
    password.value = "";
    keyword.value = "";
    searchDraft.value = "";
    await loadCourses(1);
  } catch (error) {
    loginError.value = error.message;
  } finally {
    loginBusy.value = false;
  }
}

async function logout() {
  if (!checkUploadLeave()) return;
  loggingOut.value = true;
  try {
    await api.logout();
    loadController?.abort();
    user.value = null;
    router.replace("/");
    courses.value = [];
    total.value = 0;
    password.value = "";
    toast.value = "";
    loginError.value = "";
  } catch (error) {
    if (!handleExpired(error)) listError.value = error.message;
  } finally {
    loggingOut.value = false;
  }
}

function search() {
  keyword.value = searchDraft.value.trim();
  loadCourses(1);
}
function clearSearch() {
  if (!checkUploadLeave()) return;
  router.push("/");
  searchDraft.value = "";
  keyword.value = "";
  loadCourses(1);
}

async function showCreate() {
  title.value = "";
  description.value = "";
  createError.value = "";
  fieldErrors.value = {};
  createDialog.value.showModal();
  await nextTick();
  document.getElementById("course-title").focus();
}

async function createCourse() {
  if (creating.value) return;
  creating.value = true;
  createError.value = "";
  fieldErrors.value = {};
  try {
    const created = await api.createCourse({
      title: title.value.trim(),
      description: description.value.trim(),
    });
    createDialog.value.close();
    newCourseId.value = created.id;
    keyword.value = "";
    searchDraft.value = "";
    await loadCourses(1);
    toast.value = "课程已创建，新的学习从这里开始。";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toast.value = "";
    }, 4500);
  } catch (error) {
    if (!handleExpired(error)) {
      createError.value = error.message;
      fieldErrors.value = error.fieldErrors || {};
    }
  } finally {
    creating.value = false;
  }
}

function showDetail(course) {
  router.push({ name: "course", params: { courseId: course.id } });
}

function closeBackdrop(event) {
  if (event.target.tagName === "DIALOG" && !creating.value)
    event.target.close();
}
function cancelCreate(event) {
  if (creating.value) event.preventDefault();
}
onMounted(bootstrap);
onBeforeUnmount(() => {
  loadController?.abort();
  clearTimeout(toastTimer);
});
</script>

<template>
  <div v-if="!ready" class="initial-loading" role="status">
    <span class="brand-mark"><Icon name="book" :size="25" /></span
    ><span>正在打开你的学习空间…</span>
  </div>

  <div v-else-if="!user" class="login-layout">
    <section class="login-story" aria-label="CourseTrace 介绍">
      <RouterLink class="brand brand-light" to="/" aria-label="CourseTrace 首页"
        ><span class="brand-mark"><Icon name="book" :size="24" /></span
        >CourseTrace<span class="brand-dot">.</span></RouterLink
      >
      <div class="story-copy">
        <p class="eyebrow">A LITTLE MORE UNDERSTANDING, EVERY DAY</p>
        <h1>把每一课，<br />学得更清楚<span>。</span></h1>
        <p class="story-description">
          给课程一个安静的归处，<br />也给每一次理解，留下清晰的轨迹。
        </p>
      </div>
      <div class="notebook-visual" aria-hidden="true">
        <div class="notebook-back"></div>
        <div class="notebook-front">
          <div class="notebook-top">
            <span>MY LEARNING NOTES</span><Icon name="spark" :size="18" />
          </div>
          <div class="notebook-title">从好奇，走向理解。</div>
          <div class="note-line wide"></div>
          <div class="note-line"></div>
          <div class="note-line short"></div>
          <div class="notebook-divider"></div>
          <div class="note-tag"><span></span>每一次回顾，都向前一步</div>
        </div>
        <div class="floating-note">
          <Icon name="check" :size="18" />让知识慢慢连起来
        </div>
      </div>
      <p class="story-footer">课程学习 · 知识回顾 · 理解与积累</p>
    </section>
    <main class="login-main">
      <div class="mobile-login-brand brand">
        <span class="brand-mark"><Icon name="book" /></span>CourseTrace.
      </div>
      <div class="login-form-wrap">
        <span class="welcome-pill"
          ><span></span> YOUR PERSONAL LEARNING SPACE</span
        >
        <h2>欢迎回来</h2>
        <p class="muted login-subtitle">登录，继续你的学习旅程。</p>
        <form @submit.prevent="login" class="login-form">
          <label for="username">账号</label
          ><input
            id="username"
            v-model="username"
            name="username"
            autocomplete="username"
            maxlength="64"
            placeholder="请输入账号"
            required
            :disabled="loginBusy"
          />
          <label for="password">密码</label
          ><input
            id="password"
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            required
            :disabled="loginBusy"
          />
          <p v-if="loginError" class="form-error" role="alert">
            <Icon name="alert" :size="16" />{{ loginError }}
          </p>
          <button
            class="button primary login-button"
            type="submit"
            :disabled="loginBusy"
          >
            <span>{{ loginBusy ? "正在登录…" : "进入学习空间" }}</span
            ><Icon name="arrow" :size="18" />
          </button>
        </form>
        <div class="demo-account">
          <span class="demo-label">本地体验账号</span>
          <p>
            <code>student</code><span>密码</span><code>CourseTrace@123</code>
          </p>
        </div>
        <p class="login-footnote">
          <Icon name="lock" :size="14" />你的课程，只属于你的学习空间
        </p>
      </div>
      <footer class="login-copyright">
        CourseTrace · 为每一次认真学习而设计
      </footer>
    </main>
  </div>

  <div v-else class="workspace">
    <aside class="sidebar">
      <RouterLink class="brand" to="/" aria-label="CourseTrace 首页"
        ><span class="brand-mark"><Icon name="book" :size="24" /></span
        >CourseTrace<span class="brand-dot">.</span></RouterLink
      >
      <div class="nav-heading">学习空间</div>
      <nav aria-label="主导航">
        <button class="nav-item active" @click="clearSearch">
          <Icon name="grid" :size="19" />我的课程<span
            class="nav-indicator"
          ></span>
        </button>
      </nav>
      <div class="sidebar-thought">
        <span class="thought-symbol">“</span>
        <p>学习的意义，<br />藏在每一次理解里。</p>
        <span>MAKE ROOM FOR KNOWLEDGE</span>
      </div>
      <div class="sidebar-account">
        <span class="avatar">{{ user.displayName.slice(0, 1) }}</span>
        <div class="account-copy">
          <strong>{{ user.displayName }}</strong
          ><span>个人学习空间</span>
        </div>
        <button
          class="icon-button logout-button"
          aria-label="退出登录"
          title="退出登录"
          @click="logout"
          :disabled="loggingOut || uploadInProgress"
        >
          <Icon name="logout" :size="18" />
        </button>
      </div>
    </aside>
    <main class="workspace-main">
      <header class="topbar">
        <div class="breadcrumb">
          <span class="desktop-breadcrumb">学习空间</span
          ><Icon
            class="desktop-breadcrumb"
            name="chevron"
            :size="12"
          /><strong>{{
            route.name === "course" ? "课程学习" : "我的课程"
          }}</strong>
        </div>
        <div class="topbar-right">
          <span class="personal-label"><span></span>个人学习空间</span
          ><button
            class="icon-button mobile-logout"
            aria-label="退出登录"
            @click="logout"
            :disabled="loggingOut || uploadInProgress"
          >
            <Icon name="logout" :size="18" />
          </button>
        </div>
      </header>
      <RouterView v-if="route.name === 'course'" v-slot="{ Component }"
        ><component
          :is="Component"
          :key="route.params.courseId"
          :owner-id="user.id"
          @expired="handleExpired"
      /></RouterView>
      <div v-else class="content">
        <section class="page-intro">
          <div>
            <p class="eyebrow">YOUR LEARNING, IN ONE PLACE</p>
            <h1>我的课程<span class="title-dot">.</span></h1>
            <p class="muted">把课程收好，让每一次学习都有迹可循。</p>
          </div>
          <button class="button primary create-top" @click="showCreate">
            <Icon name="plus" :size="18" />新建课程
          </button>
        </section>
        <section class="learning-banner">
          <div class="banner-icon"><Icon name="book" :size="28" /></div>
          <div>
            <h2>每一门课，都是一个新的起点。</h2>
            <p>为正在学习的课程建一个专属空间，从整理开始，慢慢积累。</p>
          </div>
          <div class="banner-orbits" aria-hidden="true">
            <span></span><span></span><Icon name="spark" :size="28" />
          </div>
        </section>
        <section class="course-section" aria-labelledby="course-section-title">
          <div class="course-toolbar">
            <div class="section-label">
              <h2 id="course-section-title">
                {{ keyword ? "搜索结果" : "全部课程" }}
              </h2>
              <span class="count-badge">{{ total }}</span>
            </div>
            <form class="search-box" role="search" @submit.prevent="search">
              <Icon name="search" :size="17" /><label
                for="course-search"
                class="visually-hidden"
                >搜索课程名称</label
              ><input
                id="course-search"
                v-model="searchDraft"
                placeholder="搜索课程名称"
                maxlength="120"
              /><button
                v-if="searchDraft"
                type="button"
                class="icon-button clear-search"
                aria-label="清空搜索"
                @click="clearSearch"
              >
                <Icon name="close" :size="14" /></button
              ><button class="visually-hidden" type="submit">搜索</button>
            </form>
          </div>
          <div v-if="keyword" class="search-caption">
            关于“{{ keyword }}”的 {{ total }} 门课程<button
              @click="clearSearch"
            >
              查看全部
            </button>
          </div>
          <div v-if="listError" class="error-banner" role="alert">
            <Icon name="alert" :size="18" /><span>{{ listError }}</span
            ><button @click="loadCourses()">重试</button>
          </div>
          <div
            v-else-if="loading"
            class="course-grid"
            aria-busy="true"
            aria-label="正在加载课程"
          >
            <div v-for="i in 3" :key="i" class="course-skeleton">
              <span></span><span></span><span></span>
            </div>
          </div>
          <div v-else-if="courses.length" class="course-grid">
            <article
              v-for="course in courses"
              :key="course.id"
              class="course-card"
              data-testid="course-card"
              :class="{ 'just-created': newCourseId === course.id }"
            >
              <div class="card-top">
                <span class="course-icon" :class="`palette-${course.id % 4}`"
                  ><Icon name="book" :size="24" /></span
                ><span v-if="newCourseId === course.id" class="new-badge"
                  >刚刚创建</span
                ><span v-else class="course-kind">个人课程</span>
              </div>
              <h3>
                <button @click="showDetail(course)" class="course-title-button">
                  {{ course.title }}
                </button>
              </h3>
              <p class="course-description">
                {{
                  course.description ||
                  "还没有课程简介，先给新的学习留一点空间。"
                }}
              </p>
              <div class="card-divider"></div>
              <footer class="card-footer">
                <span
                  ><Icon name="calendar" :size="14" /><time
                    :datetime="course.createdAt"
                    >{{ dateLabel(course.createdAt) }}创建</time
                  ></span
                ><button
                  @click="showDetail(course)"
                  class="card-link"
                  :aria-label="`查看课程：${course.title}`"
                >
                  查看课程<Icon name="arrow" :size="15" />
                </button>
              </footer>
            </article>
          </div>
          <div v-else class="empty-state">
            <div class="empty-book"><Icon name="book" :size="36" /></div>
            <span class="eyebrow">{{
              keyword ? "KEEP EXPLORING" : "A FRESH START"
            }}</span>
            <h3>{{ keyword ? "还没有找到这门课程" : "从第一门课程开始吧" }}</h3>
            <p>
              {{
                keyword
                  ? "试试更短的名称，或回到全部课程。"
                  : "把正在学习的课程放进来，给知识一个专属的归处。"
              }}
            </p>
            <button
              class="button"
              :class="keyword ? 'secondary' : 'primary'"
              @click="keyword ? clearSearch() : showCreate()"
            >
              <Icon :name="keyword ? 'arrow' : 'plus'" :size="17" />{{
                keyword ? "查看全部课程" : "创建第一门课程"
              }}
            </button>
          </div>
          <nav
            v-if="pages > 1 && !loading"
            class="pagination"
            aria-label="课程分页"
          >
            <span>第 {{ page }} / {{ pages }} 页，共 {{ total }} 门课程</span>
            <div>
              <button
                class="button secondary"
                :disabled="page <= 1"
                @click="loadCourses(page - 1)"
              >
                上一页</button
              ><button
                class="button secondary"
                :disabled="page >= pages"
                @click="loadCourses(page + 1)"
              >
                下一页
              </button>
            </div>
          </nav>
        </section>
        <footer class="workspace-footer">
          <span>一点一滴，留下理解的轨迹。</span><span>COURSETRACE</span>
        </footer>
      </div>
    </main>
    <div v-if="toast" class="toast" role="status">
      <Icon name="check" :size="18" />{{ toast }}
    </div>

    <dialog
      ref="createDialog"
      class="modal create-modal"
      aria-labelledby="create-heading"
      @click="closeBackdrop"
      @cancel="cancelCreate"
    >
      <div class="modal-heading">
        <span class="dialog-icon"><Icon name="plus" :size="23" /></span
        ><button
          class="icon-button"
          aria-label="关闭新建课程"
          :disabled="creating"
          @click="createDialog.close()"
        >
          <Icon name="close" />
        </button>
      </div>
      <h2 id="create-heading">为新知识，留个位置。</h2>
      <p class="muted modal-description">给课程起个名字，开启一段新的学习。</p>
      <form @submit.prevent="createCourse">
        <label for="course-title"
          >课程名称 <span class="required">*</span></label
        ><input
          id="course-title"
          v-model="title"
          placeholder="例如：数据结构与算法"
          maxlength="120"
          required
          :disabled="creating"
          :aria-invalid="Boolean(fieldErrors.title)"
          aria-describedby="title-error"
        />
        <p v-if="fieldErrors.title" id="title-error" class="field-error">
          {{ fieldErrors.title }}
        </p>
        <div class="label-row">
          <label for="course-description"
            >课程简介 <span class="optional">选填</span></label
          ><span>{{ description.length }} / 1000</span>
        </div>
        <textarea
          id="course-description"
          v-model="description"
          placeholder="这门课讲些什么？写下你想学习的内容。"
          maxlength="1000"
          rows="4"
          :disabled="creating"
          :aria-invalid="Boolean(fieldErrors.description)"
        ></textarea>
        <p v-if="fieldErrors.description" class="field-error">
          {{ fieldErrors.description }}
        </p>
        <p v-if="createError" role="alert" class="form-error">
          {{ createError }}
        </p>
        <div class="modal-actions">
          <button
            type="button"
            class="button secondary"
            :disabled="creating"
            @click="createDialog.close()"
          >
            取消</button
          ><button
            type="submit"
            class="button primary"
            :disabled="creating || !title.trim()"
          >
            <Icon v-if="!creating" name="plus" :size="17" />{{
              creating ? "正在创建…" : "创建课程"
            }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>
