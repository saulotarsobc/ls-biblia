import react from "@vitejs/plugin-react";
import { defineConfig, externalizeDepsPlugin } from "electron-vite";
import { resolve } from "node:path";

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    // Main e preload saem em CJS: o modulo `electron` e CJS e importa-lo de um
    // main em ESM quebra no link ("Cannot read properties of undefined").
    build: { rollupOptions: { input: resolve("src/main/index.ts") } },
    resolve: { alias: { "@shared": resolve("src/shared") } },
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    build: { rollupOptions: { input: resolve("src/preload/index.ts") } },
    resolve: { alias: { "@shared": resolve("src/shared") } },
  },
  renderer: {
    root: resolve("src/renderer"),
    plugins: [react()],
    build: { rollupOptions: { input: resolve("src/renderer/index.html") } },
    resolve: { alias: { "@shared": resolve("src/shared") } },
  },
});
