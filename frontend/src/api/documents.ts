import type {
  DocumentSummary,
  DocumentUploadResponse,
  PageResponse,
} from '../types/api';
import { getJSON, postForm, del, postNoBody } from './client';

export function listDocuments(params?: {
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<PageResponse<DocumentSummary>> {
  const qs = new URLSearchParams();
  if (params?.status) qs.set('status', params.status);
  if (params?.keyword) qs.set('keyword', params.keyword);
  qs.set('page', String(params?.page ?? 1));
  qs.set('size', String(params?.size ?? 50));
  return getJSON<PageResponse<DocumentSummary>>(`/api/v1/documents?${qs.toString()}`);
}

export interface UploadOptions {
  source?: string;
  version?: string;
  language?: string;
  docType?: string;
}

export function uploadDocument(
  file: File,
  opts: UploadOptions = {},
): Promise<DocumentUploadResponse> {
  const fd = new FormData();
  fd.append('file', file);
  if (opts.source) fd.append('source', opts.source);
  if (opts.version) fd.append('version', opts.version);
  if (opts.language) fd.append('language', opts.language);
  if (opts.docType) fd.append('doc_type', opts.docType);
  return postForm<DocumentUploadResponse>('/api/v1/documents', fd);
}

// 后端契约:
//   DELETE /api/v1/documents/{id}  -> 204 软删
//   POST   /api/v1/documents/{id}/retry -> 202 重试(仅 FAILED 可调用一次)
// 见 DocumentController.java:114,121
export function deleteDocument(docId: number): Promise<void> {
  return del(`/api/v1/documents/${docId}`);
}

export function retryDocument(docId: number): Promise<void> {
  return postNoBody(`/api/v1/documents/${docId}/retry`);
}
