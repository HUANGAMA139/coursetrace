import { createRouter, createWebHashHistory } from "vue-router";
import CourseWorkspace from "./components/CourseWorkspace.vue";

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", name: "courses", component: { render: () => null } },
    {
      path: "/courses/:courseId(\\d+)",
      name: "course",
      component: CourseWorkspace,
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
  scrollBehavior: () => ({ top: 0 }),
});
