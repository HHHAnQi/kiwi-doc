import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Vite dev server 在 5173, /api/* 反向代理到 chat-app 8080 避免跨域。
// chat-app 不必开 CORS, 同源访问。dev 不依赖后端 CORS config(production 同源服务也不需要)。
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 流 (POST /api/v1/chat/sse) 需要 ws: false + 不 buffer。Vite 默认 proxy 不 buffer, 但保险加 forward。
      },
    },
  },
});
