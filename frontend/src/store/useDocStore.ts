// 文档列表 store: 加载 / 上传 / 状态轮询
import { create } from 'zustand';
import type { DocumentSummary } from '../types/api';
import {
  listDocuments,
  uploadDocument,
  deleteDocument,
  retryDocument,
  type UploadOptions,
} from '../api/documents';
import { ApiError } from '../api/client';
import { useToastStore } from './useToastStore';

interface DocState {
  docs: DocumentSummary[];
  // 后端总文档数(listForSummary 返回的 page.total), 用于驱动 "加载更多" 按钮
  total: number;
  loading: boolean;
  error: string | null;

  // 上传中状态 (按文件名做 key, 上传完自动清)
  uploading: Record<string, boolean>;

  load: () => Promise<void>;
  loadMore: () => Promise<void>;
  upload: (file: File, opts?: UploadOptions) => Promise<void>;
  remove: (docId: number) => Promise<void>;
  retry: (docId: number) => Promise<void>;
}

// 后台 status 轮询 (PARSING/UPLOADED → READY) 用 setInterval 在 App.tsx 里启, 这里只暴露 load。

// 单页 size: 100 起步, 配合 loadMore 直到 total.
// 不做无限滚动 (滚动事件 + IntersectionObserver 工作量大 + 体验未必更好),
// 显式 "加载更多" 按钮体感更可控。
const PAGE_SIZE = 100;

export const useDocStore = create<DocState>((set, get) => ({
  docs: [],
  total: 0,
  loading: false,
  error: null,
  uploading: {},

  load: async () => {
    // 注意: 进 loading 不立即清 error, 让上次错误在请求期间仍可见;
    // 成功后由下面 set 清除。若仍在 loading 时清 error, 会让红框闪烁。
    set({ loading: true });
    try {
      const page = await listDocuments({ size: PAGE_SIZE, page: 1 });
      set({
        docs: page.items,
        total: page.total,
        loading: false,
        error: null,
      });
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message;
      set({ loading: false, error: msg });
    }
  },

  // DEV-B3: loadMore 追加下一页, 不替换已有 docs。
  // 用于 knowledge base > PAGE_SIZE 时让用户能看完整列表。
  loadMore: async () => {
    const cur = get().docs;
    // 已加载页数 + 1 = 下一页号
    const nextPage = Math.floor(cur.length / PAGE_SIZE) + 1;
    set({ loading: true });
    try {
      const page = await listDocuments({ size: PAGE_SIZE, page: nextPage });
      // 后端按 createdAt DESC; 由于 docs 在前页已按时间倒序排, append 即可保持序一致。
      // 严格点应去重(doc_id), 防 windows.confirm 间的并发刷新导致重叠。
      const existingIds = new Set(cur.map((d) => d.doc_id));
      const fresh = page.items.filter((d) => !existingIds.has(d.doc_id));
      set({
        docs: [...cur, ...fresh],
        total: page.total,
        loading: false,
        error: null,
      });
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message;
      set({ loading: false, error: msg });
    }
  },

  upload: async (file: File, opts?: UploadOptions) => {
    set((s) => ({ uploading: { ...s.uploading, [file.name]: true } }));
    try {
      const result = await uploadDocument(file, opts);
      // 刷新文档列表(后端会返回新 doc), 上传事件 done
      await get().load();
      // 友好提示: 幂等命中 (同 hash 重传) 高价值信息, 不再只 console
      if (result.idempotent_hit) {
        useToastStore.getState().show(
          'info',
          `文件已存在 (hash 命中), 复用 doc #${result.doc_id}`,
        );
      }
    } catch (e) {
      // upload 失败要写入 error 让 App.tsx effect 推 toast, 不让 reject 上抛.
      //UploadDropzone 的 reduce 队列可继续消费下一个文件.
      const msg = e instanceof ApiError ? e.message : (e as Error).message;
      set({ error: `上传 ${file.name} 失败: ${msg}` });
    } finally {
      set((s) => {
        const next = { ...s.uploading };
        delete next[file.name];
        return { uploading: next };
      });
    }
  },

  // 软删: 后端 DELETE /documents/{id} 返回 204, 列表里这条变 DELETED(或被过滤)
  // 乐观 UI 不做, 因后端是软删 + listDocuments 仍可能返回 DELETED 条目, 直接重 load 最稳。
  remove: async (docId: number) => {
    try {
      await deleteDocument(docId);
      await get().load();
      // 与 error 对称: 成功路径也告诉用户"操作生效了", 不只是列表默默消失
      useToastStore.getState().show('success', '文档已删除');
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message;
      set({ error: msg });
    }
  },

  // 重试: 仅 FAILED 状态可调, 后端 202 Accepted, 实际解析是异步的(parser-service 消费 MQ)
  // 触发后状态变 PARSING/RUNNING, App.tsx 轮询会自动追到 READY
  retry: async (docId: number) => {
    try {
      await retryDocument(docId);
      await get().load();
      useToastStore.getState().show('success', '已提交重新解析');
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : (e as Error).message;
      set({ error: msg });
    }
  },
}));
