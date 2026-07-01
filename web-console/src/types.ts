export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface Conversation {
  id?: number;
  conversationId: string;
  audioObjectKey?: string;
  transcript?: string | null;
  summary?: string | null;
  sentiment?: string | null;
  durationSeconds?: number | null;
  recordedAt?: string;
  status: string;
  taskId?: string;
  cardObjectKey?: string | null;
}

export interface Person {
  id?: number;
  name: string;
  relationship?: string;
  mentionCount?: number;
  lastSeen?: string;
  summary?: string;
}

export interface SemanticSearchHit {
  conversation: Conversation;
  score: number;
}

export interface UploadResult {
  conversationId: string;
  taskId: string;
  objectKey: string;
  status: string;
  message?: string;
}

export interface TaskStatus {
  taskId?: string;
  task_id?: string;
  status: string;
  transcript_length?: string | number;
  sentiment?: string;
  sentiment_score?: string;
  error?: string;
  updatedAt?: string;
}

export interface AuthStatus {
  enabled: boolean;
  expiresInSeconds?: number;
}

export interface HealthStatus {
  status: string;
  components?: Record<string, { status: string }>;
}

export interface DataPlaneHealth {
  status: string;
  service: string;
  version: string;
  consumer_running?: boolean;
}
