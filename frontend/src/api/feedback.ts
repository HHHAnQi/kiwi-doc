import type { FeedbackRequest } from '../types/api';
import { postJSON } from './client';

interface FeedbackCreatedResponse {
  feedback_id: number;
}

export function submitFeedback(req: FeedbackRequest): Promise<FeedbackCreatedResponse> {
  return postJSON<FeedbackCreatedResponse>('/api/v1/feedback', req);
}
