import type {
  ApiResponse,
  AuthStatus,
  Conversation,
  DataPlaneHealth,
  HealthStatus,
  Person,
  SemanticSearchHit,
  TaskStatus,
  UploadResult,
} from '../types';

const TOKEN_KEY = 'echo_jwt_token';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  const token = getToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init?.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(path, { ...init, headers });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }
  const json = (await res.json()) as ApiResponse<T>;
  if (json.code !== 200) throw new Error(json.message || '请求失败');
  return json.data;
}

export const api = {
  getAuthStatus: () => request<AuthStatus>('/api/v1/auth/status'),
  login: (apiKey: string) =>
    request<{ accessToken: string }>('/api/v1/auth/token', {
      method: 'POST',
      body: JSON.stringify({ apiKey }),
    }),

  getControlHealth: async () => {
    const res = await fetch('/actuator/health');
    return (await res.json()) as HealthStatus;
  },
  getDataHealth: async () => {
    const res = await fetch('/data-api/health');
    return (await res.json()) as DataPlaneHealth;
  },

  getQueues: () => request<Record<string, number>>('/api/v1/audio/queues'),

  uploadAudio: async (file: File, language: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('language', language);
    return request<UploadResult>('/api/v1/audio/upload', { method: 'POST', body: form });
  },

  getTaskStatus: (taskId: string) => request<TaskStatus>(`/api/v1/audio/task/${taskId}`),

  getConversations: (days = 30) =>
    request<Conversation[]>(`/api/v1/graph/conversations?days=${days}`),

  getPersons: () => request<Person[]>('/api/v1/graph/persons'),
  getImportantPersons: (limit = 20) =>
    request<Person[]>(`/api/v1/graph/persons/important?limit=${limit}`),
  getPersonNetwork: (name: string) =>
    request<Person[]>(`/api/v1/graph/persons/${encodeURIComponent(name)}/network`),
  upsertPerson: (name: string, relationship: string) =>
    request<Person>('/api/v1/graph/persons', {
      method: 'POST',
      body: JSON.stringify({ name, relationship }),
    }),

  queryGraph: (params: Record<string, string | number>) => {
    const q = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== '' && v !== undefined) q.set(k, String(v));
    });
    return request<Conversation[]>(`/api/v1/graph/query?${q}`);
  },

  semanticSearch: (query: string, limit = 10, minScore = 0.3) =>
    request<SemanticSearchHit[]>('/api/v1/graph/search/semantic', {
      method: 'POST',
      body: JSON.stringify({ query, limit, minScore }),
    }),

  getP2PStatus: () => request<Record<string, unknown>>('/api/v1/sync/status'),
  discoverPeers: () => request<Record<string, unknown>>('/api/v1/sync/discover'),
  triggerSync: (peerAddress: string) =>
    request<string>('/api/v1/sync/trigger', {
      method: 'POST',
      body: JSON.stringify({ peerAddress }),
    }),
};
