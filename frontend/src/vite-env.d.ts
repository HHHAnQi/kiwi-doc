/// <reference types="vite/client" />

// Vite 环境变量类型声明。import.meta.env.VITE_* 在 build/dev 时静态替换。
interface ImportMetaEnv {
  // 后端 API base: dev 留空走 vite proxy 相对路径; prod 可填同源 ''
  // 或跨域 'https://api.example.com'。
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
