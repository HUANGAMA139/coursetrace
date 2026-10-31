<script setup>
defineProps({ name: String, size: { type: [Number, String], default: 20 } });
const paths = {
  upload: ["M12 16V3", "m7 8 5-5 5 5", "M4 15v6h16v-6"],
  video: ["M3 5h18v14H3z", "m10 9 5 3-5 3Z"],
  play: ["m9 5 11 7-11 7Z"],
  "arrow-left": ["M20 12H4", "m10 6-6 6 6 6"],
  book: [
    "M12 7c-2-2-6-3-9-2v15c3-1 7 0 9 2 2-2 6-3 9-2V5c-3-1-7 0-9 2Z",
    "M12 7v15",
  ],
  grid: ["M3 3h7v7H3z", "M14 3h7v7h-7z", "M3 14h7v7H3z", "M14 14h7v7h-7z"],
  plus: ["M12 5v14", "M5 12h14"],
  search: ["M21 21l-5-5", "M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0"],
  arrow: ["M5 12h14", "m13 6 6 6-6 6"],
  close: ["m6 6 12 12", "M6 18 18 6"],
  logout: ["M9 4H4v16h5", "M9 12h12", "m17 8 4 4-4 4"],
  chevron: ["m9 5 7 7-7 7"],
  check: ["m5 12 4 4L19 6"],
  spark: ["m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z"],
  calendar: ["M5 5h14v16H5z", "M8 3v4", "M16 3v4", "M5 10h14"],
  lock: ["M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4"],
  alert: ["M12 8v5", "M12 17h.01", "M12 3 2 21h20Z"],
};
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(d, i) in paths[name] || paths.book" :key="i" :d="d" />
  </svg>
</template>
