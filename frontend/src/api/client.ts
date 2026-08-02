// HTTP 薄封装: 全局带 Authorization header + 解析 ErrorResponse 抛 typed error
//
// dev mode 端口说明: vite proxy 已把 /api/* 路由到 chat-app 8080, 前端只发相对路径请求。
// prod mode 由 nginx/同源 static-serving 提供, 也是相对路径。
// 因此本文件不写绝对域名, 用相对 /api 即可。

import type { ErrorResponse } from '../types/api';

const TOKEN_KEY = 'ragdoc.token';

// dev-token-change-me 是后端默认 dev 凭据, localStorage 持久化让用户改一次
export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || 'dev-token-change-me';
}

export function setToken(t: string): void {
  localStorage.setItem(TOKEN_KEY, t);
}

export class ApiError extends Error {
  code: string;
  trace_id?: string;
  status: number;

  constructor(resp: ErrorResponse, status: number) {
    super(resp.message || `HTTP ${status}`);
    this.code = resp.code || 'UNKNOWN';
    this.trace_id = resp.trace_id;
    this.status = status;
  }
}

async function parseError(resp: Response): Promise<ApiError> {
  let body: ErrorResponse | null = null;
  try {
    body = (await resp.json()) as ErrorResponse;
  } catch {
    // 非 JSON 错误 (网络层 / 5xx with HTML), 降级
  }
  return new ApiError(
    body ?? { code: 'NETWORK', message: `HTTP ${resp.status} ${resp.statusText}` },
    resp.status,
  );
}

export async function getJSON<T>(path: string): Promise<T> {
  const resp = await fetch(path, {
    headers: {
      Authorization: `Bearer ${getToken()}`,
      Accept: 'application/json',
    },
  });
  if (!resp.ok) throw await parseError(resp);
  return (await resp.json()) as T;
}

export async function postJSON<T>(path: string, body: unknown): Promise<T> {
  const resp = await fetch(path, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${getToken()}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(body),
  });
  if (!resp.ok) throw await parseError(resp);
  return (await resp.json()) as T;
}

export async function postForm<T>(path: string, formData: FormData): Promise<T> {
  // multipart 不显式 set Content-Type, 浏览器自动加 boundary
  const resp = await fetch(path, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${getToken()}`,
      Accept: 'application/json',
    },
    body: formData,
  });
  if (!resp.ok) throw await parseError(resp);
  return (await resp.json()) as T;
}
