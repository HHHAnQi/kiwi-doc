# rag-doc-platform frontend

RAG 文档中台的浏览器 SPA：上传文档 → 问答 → 查看引用 → 反馈，全部对接
`platform-bootstrap`（chat-app，端口 8080）开放的 REST + SSE 接口。

>condezpole 架构定位见 `../docs/v3/frontend-spa-spec.md`（如未存在见根 README）。
>本目录只提交源码；`dist/` 与 `node_modules/` 由 `.gitignore` 排除。

## 技术栈

| 关注点 | 选型 | 备注 |
| --- | --- | --- |
| 构建 | Vite 8 | dev server 默认 5173，被占用时自动落到 5174 |
| UI | React 19 + TypeScript 6 | 函数组件 + hooks |
| 样式 | Tailwind v4（`@tailwindcss/vite` 插件） | 主题色在 `src/index.css` 的 `@theme` |
| 状态 | Zustand 5 | `useDocStore` / `useChatStore` |
| Markdown | react-markdown 9 + remark-gfm | 渲染 LLM 回答 |
| SSE | 原生 `fetch` + `ReadableStream` | 不用 `EventSource`（不支持 POST body）|

不引入路由（单页两栏布局）、不引入 axios（用原生 fetch）、不引入 UI kit（Tailwind utility 足够）。

## 目录结构

```
src/
  api/            # 与后端契约的封装：client / documents / chat(SSE) / feedback
  components/     # 纯展示/交互组件
  lib/            # cn / format 等小工具
  store/          # zustand store
  types/api.ts    # 与后端 DTO 对齐的 TS 类型（手写）
  App.tsx         # Header + Sidebar + ChatWindow 三栏布局，定时拉文档状态
  main.tsx
  index.css       # @import "tailwindcss" + @theme brand tokens
```

## 开发

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173（占用则自动 +1）
```

dev 模式下 `vite.config.ts` 把 `/api/*` 反向代理到 `http://localhost:8080`，
因此**无需在后端开 CORS**，也无需额外配置 token origin。前置条件：本地
`./gradlew :platform-bootstrap:bootRun` 已起。

## 构建

```bash
npm run build   # 产物到 dist/，体积约 365KB JS / 113KB gzip
npm run preview # 本地预览生产构建（不再走 dev proxy，需另行配置反代）
```

生产部署时，建议用 nginx 把 `/api/*` 反代到 chat-app，静态资源指向 `dist/`。

## 与后端的接口契约

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/documents?page=&size=` | 文档列表（snake_case）|
| POST | `/api/v1/documents`（multipart） | 上传文档 |
| POST | `/api/v1/chat/sse` | SSE 问答：`citations → delta* → done\|error` |
| POST | `/api/v1/feedback` | 提交 👍/👎 与纠错 |

字段命名兼容：REST 走 Jackson SNAKE_CASE；SSE 路径透传 Java record 原名
（camelCase）。前端 `api/chat.ts` 的 `parseSSEFrame` 同时兜住两种命名，因此
`Citation`、`done` 等事件在两种序列化下都能正确解析。

4 级降级状态（`state_hint`）由 `components/StateBanner.tsx` 友好提示：
`OK` / `EMPTY_KB` / `NO_RECALL` / `LLM_DEGRADED`，每种都带 trace_id 便于排查。

## 鉴权

dev/pre-prod 通过 `Authorization: Bearer <dev-token>` 头注入；token 暂存在
`localStorage`，由 `api/client.ts` 的 `getToken/setToken` 管理。生产应替换为
正式 登录态/SSO cookie，`client.ts` 是唯一改动点。
