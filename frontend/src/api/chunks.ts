// chunks 接口客户端: 单条 + 相邻 prev/next。
//
// 用途: CitationCard 展开时显示文档名 + 上下文相邻 chunk, 让用户能"看一眼原文出处",
// 不再只看到孤立的 "文档 #N"。
//
// 后端契约 (ChunkController.java):
//   GET /api/v1/chunks/{id}                 → ChunkDetailResponse
//   GET /api/v1/chunks/{id}/neighbors       → ChunkNeighborsResponse { prev, next }
//
// 容错: neighbors 接口若失败 (网络 / 罕见 5xx) 由调用方 catch, 静默降级;
// 不影响 CitationCard 主信息显示。
import type { ChunkDetail, ChunkNeighbors } from '../types/api';
import { getJSON } from './client';

export function getChunkDetail(chunkId: number): Promise<ChunkDetail> {
  return getJSON<ChunkDetail>(`/api/v1/chunks/${chunkId}`);
}

export function getChunkNeighbors(
  chunkId: number,
  direction: 'prev' | 'next' | 'both' = 'both',
): Promise<ChunkNeighbors> {
  return getJSON<ChunkNeighbors>(
    `/api/v1/chunks/${chunkId}/neighbors?direction=${direction}`,
  );
}
