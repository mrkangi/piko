import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const backendTarget = process.env.VITE_API_TARGET || 'http://127.0.0.1:7788'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/healthz': {
        target: backendTarget,
        changeOrigin: true,
      },
      '/readyz': {
        target: backendTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: '../internal/web/dist',
    emptyOutDir: true,
  },
})
