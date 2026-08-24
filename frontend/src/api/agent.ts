// Agent run 只读审计 API(GET /api/v1/agent/runs/{id})
import { getJSON } from './client';
import type { AgentRunDetail } from '../types/api';

export function fetchAgentRun(runId: string): Promise<AgentRunDetail> {
  return getJSON<AgentRunDetail>(`/api/v1/agent/runs/${runId}`);
}
