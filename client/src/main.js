import { createApp } from "vue";
import App from "./App.vue";
import "./style.css";
import { router } from "./router.js";

const app = createApp(App).use(router);
router.isReady().then(() => app.mount("#app"));
