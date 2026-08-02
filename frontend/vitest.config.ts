import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Vitest 独立配置 (不污染 vite.config.ts, 否则 build 会被 test 配置拖慢)。
//
// environment=jsdom: 让 store/react 测试能跑(无真实浏览器但有 DOM/window)。
// globals=true: 测试文件可直接用 describe/it/expect 不必每处 import。
// coverage 暂不开, 重点是先把"已有 pure-logic 有覆盖"做起来。
export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
});
