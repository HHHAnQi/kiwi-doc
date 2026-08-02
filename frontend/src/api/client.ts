// HTTP 薄封装: 全局带 Authorization header + 解析 ErrorResponse 抛 typed error
//
// API base 通过 VITE_API_BASE 注入:
// - dev: vite proxy 把 /api/* 路由到 chat-app 8080, VITE_API_BASE 留空 = 同源相对路径
// - prod: 由 nginx 同源托管时也留空; 跨域时填 https://api.example.com
// 因此本文件默认 '', 部署时通过 env override, 代码不改。

import type { ErrorResponse } from '../types/api';

const TOKEN_KEY = 'ragdoc.token';

// import.meta.env.VITE_API_BASE 由 vite 在 build/dev 时静态替换;
// 取不到时 fallback '' (相对路径, 走当前 origin/proxy)
const API_BASE =
  (import.meta.env.VITE_API_BASE as string | undefined)?.replace(/\/$/, '') ?? '';

export function apiURL(path: string): string {
  // path 必须以 / 开头; API_BASE 末尾斜杠已去
  return `${API_BASE}${path}`;
}

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
  const resp = await fetch(apiURL(path), {
    headers: {
      Authorization: `Bearer ${getToken()}`,
      Accept: 'application/json',
    },
  });
  if (!resp.ok) throw await parseError(resp);
  return (await resp.json()) as T;
}

export async function postJSON<T>(path: string, body: unknown): Promise<T> {
  const resp = await fetch(apiURL(path), {
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
  const resp = await fetch(apiURL(path), {
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
