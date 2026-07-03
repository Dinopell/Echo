export function formatDateTime(iso?: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatDate(iso?: string | null) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
}

const STATUS_MAP: Record<string, string> = {
  pending: '等待中',
  processing: '处理中',
  done: '已完成',
  failed: '失败',
  UP: '正常',
  healthy: '正常',
};

const SENTIMENT_MAP: Record<string, string> = {
  positive: '积极',
  negative: '消极',
  neutral: '平和',
};

export function statusLabel(s?: string) {
  if (!s) return '未知';
  return STATUS_MAP[s] ?? s;
}

export function sentimentLabel(s?: string | null) {
  if (!s) return '';
  return SENTIMENT_MAP[s] ?? s;
}

export function previewText(c: { summary?: string | null; transcript?: string | null }) {
  const t = c.summary || c.transcript;
  if (!t) return '正在聆听与理解中…';
  return t.length > 72 ? `${t.slice(0, 72)}…` : t;
}
