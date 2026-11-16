import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard is served by the TV app itself, from a flat directory, so assets
// are referenced relatively and land straight in the APK's asset folder.
export default defineConfig({
  base: "/",
  plugins: [react()],
  build: {
    outDir: "../android/app/src/main/assets/dashboard",
    emptyOutDir: true,
    target: "es2020",
    assetsDir: "assets",
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    host: true,
  },
});
