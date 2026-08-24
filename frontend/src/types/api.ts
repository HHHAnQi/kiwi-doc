// 对接后端 DTO 的 TypeScript 类型, 手写不靠 codegen 维护更可控。
// snake_case 与后端 Jackson PropertyNamingStrategies.SNAKE_CASE 对齐。

export type DocumentStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'READY'
  | 'FAILED'
  | 'DELETED';

export interface DocumentSummary {
  doc_id: number;
  original_filename: string;
  status: DocumentStatus;
  size_bytes: number;
  chunk_count: number;
  created_at: string;
  updated_at: string;
  source: string | null;
  version: string | null;
  language: string | null;
  doc_type: string | null;
}

export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface DocumentUploadResponse {
  doc_id: number;
  status: DocumentStatus;
  original_filename: string;
  idempotent_hit: boolean;
  received_at: string;
}

export type StateHint = 'OK' | 'EMPTY_KB' | 'NO_RECALL' | 'LLM_DEGRADED';

export interface Citation {
  chunk_id: number;
  doc_id: number;
  page: number;
  snippet: string;
  llm_context?: string; // 评测用, 前端可忽略
  section_path: string[];
}

export interface ChatResponse {
  answer: string;
  citations: Citation[];
  state_hint: StateHint;
  trace_id: string;
}

// 同步 chat request 在 SSE 路径复用同样字段
export interface ChatRequest {
  query: string;
  doc_id?: number | null;
  top_k?: number;
  source?: string | null;
  version?: string | null;
  language?: string | null;
  // P0 修复(多轮贯通): 后端 SSE 链路已支持 conversationId → query 改写/history;
  // 不传则走 stateless 单轮路径
  conversation_id?: string | null;
}

// ===== Agent 执行过程(GET /api/v1/agent/runs/{id}) =====
export interface AgentStepView {
  step_id: string;
  sequence: number;
  tool_name: string;
  status: string;
  result_count: number;
  latency_ms: number | null;
  error_code: string;
}
export interface AgentRunDetail {
  run_id: string;
  status: string;
  plan_id: string;
  evidence_count: number;
  step_count: number;
  steps: AgentStepView[];
}

// ===== SSE event 客户端类型 (sealed record ChatStreamEvent 对应)=====

export type SSEEvent =
  | { type: 'citations'; citations: Citation[] }
  | { type: 'delta'; delta: string }
  | { type: 'done'; trace_id: string; state_hint: StateHint }
  | { type: 'error'; trace_id: string; message: string };

// ===== feedback =====

// 与后端 domain.feedback.Rating 枚举的 dbValue 对齐: like / dislike
// (后端只接受小写, 发 GOOD/BAD 会被拒为 SYS_INVALID_ARGUMENT)
export type Rating = 'like' | 'dislike';

export interface FeedbackRequest {
  trace_id: string;
  rating: Rating;
  corrected_answer?: string | null;
  comment?: string | null;
}

export interface ErrorResponse {
  code: string;
  message: string;
  trace_id?: string;
  timestamp?: string;
}

// ===== chunks 详情 (引用卡片展开看原文出处) =====
// 对应后端 ChunkDetailResponse + ChunkNeighborsResponse
export interface ChunkDetail {
  id: number;
  document_id: number;
  seq: number;
  chunk_type: string; // CHILD / PARENT / TEXT / FIGURE
  content: string;
  page: number;
  bbox: unknown | null;
  parent_chunk_id: number | null;
  content_hash: string;
  document_filename: string;
  section_path: string[];
}

export interface ChunkNeighbors {
  prev: ChunkDetail | null;
  next: ChunkDetail | null;
}
