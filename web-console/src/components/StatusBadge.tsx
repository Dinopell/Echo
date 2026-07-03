import { statusLabel, sentimentLabel } from '../utils/format';

export function StatusBadge({ status }: { status: string }) {
  return <span className={`status-badge status-${status}`}>{statusLabel(status)}</span>;
}

export function SentimentBadge({ sentiment }: { sentiment?: string | null }) {
  if (!sentiment) return null;
  return <span className={`sentiment-badge sentiment-${sentiment}`}>{sentimentLabel(sentiment)}</span>;
}
