import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import { StatusBadge, SentimentBadge } from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import type { Conversation } from '../types';
import { formatDateTime, previewText } from '../utils/format';

function MemoryDetail({ c }: { c: Conversation }) {
  return (
    <article className="memory-detail">
      <h2>{c.summary || previewText(c)}</h2>
      <div className="memory-detail-meta">
        <StatusBadge status={c.status} />
        <SentimentBadge sentiment={c.sentiment} />
        <span style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>
          {formatDateTime(c.recordedAt)}
        </span>
        {c.durationSeconds != null && (
          <span style={{ fontSize: '0.82rem', color: 'var(--text-muted)' }}>
            {c.durationSeconds} 秒
          </span>
        )}
      </div>

      {c.summary && (
        <div className="detail-block">
          <h3>摘要</h3>
          <p>{c.summary}</p>
        </div>
      )}
      {c.transcript && (
        <div className="detail-block">
          <h3>转写全文</h3>
          <pre>{c.transcript}</pre>
        </div>
      )}
      {!c.transcript && !c.summary && (
        <EmptyState title="仍在处理中" hint="转写和摘要完成后会自动出现在这里，请稍后刷新。" />
      )}
      <details style={{ marginTop: '1rem' }}>
        <summary style={{ cursor: 'pointer', color: 'var(--text-muted)', fontSize: '0.82rem' }}>技术信息</summary>
        <p className="tech-id" style={{ marginTop: '0.5rem' }}>ID: {c.conversationId}</p>
        {c.taskId && <p className="tech-id">任务: {c.taskId}</p>}
      </details>
    </article>
  );
}

export default function ConversationsPage() {
  const [searchParams] = useSearchParams();
  const initialId = searchParams.get('id');
  const [days, setDays] = useState(30);
  const [items, setItems] = useState<Conversation[]>([]);
  const [selected, setSelected] = useState<Conversation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await api.getConversations(days);
      setItems(data);
      setSelected((prev) => {
        const targetId = prev?.conversationId ?? initialId;
        if (targetId) {
          return data.find((c) => c.conversationId === targetId) ?? data[0] ?? null;
        }
        return data[0] ?? null;
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [days, initialId]);

  useEffect(() => { load(); }, [load]);

  return (
    <>
      <PageHeader
        title="记忆库"
        desc="你的对话转写、摘要与情感印记。"
        action={
          <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
            <select className="select" style={{ width: 'auto' }} value={days}
              onChange={(e) => setDays(Number(e.target.value))}>
              <option value={7}>近 7 天</option>
              <option value={30}>近 30 天</option>
              <option value={90}>近 90 天</option>
              <option value={365}>近一年</option>
            </select>
            <button type="button" className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
              {loading ? '…' : '刷新'}
            </button>
          </div>
        }
      />
      {error && <div className="error-banner">{error}</div>}

      {items.length === 0 && !loading ? (
        <EmptyState title="记忆库是空的" hint="上传录音后，转写和摘要会出现在这里。" actionLabel="上传录音" actionTo="/upload" />
      ) : (
        <div className="memory-layout">
          <div className="memory-list">
            {items.map((c) => (
              <button
                key={c.conversationId}
                type="button"
                className={`memory-item${selected?.conversationId === c.conversationId ? ' active' : ''}`}
                onClick={() => setSelected(c)}
              >
                <div className="memory-item-title">{previewText(c)}</div>
                <div className="memory-item-meta">
                  <StatusBadge status={c.status} />
                  <SentimentBadge sentiment={c.sentiment} />
                  <span>{formatDateTime(c.recordedAt)}</span>
                </div>
              </button>
            ))}
          </div>
          {selected ? <MemoryDetail c={selected} /> : (
            <div className="memory-detail" style={{ display: 'grid', placeItems: 'center' }}>
              <p style={{ color: 'var(--text-muted)' }}>选择一条记忆查看详情</p>
            </div>
          )}
        </div>
      )}
    </>
  );
}
