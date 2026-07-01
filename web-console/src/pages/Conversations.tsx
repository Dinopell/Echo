import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Conversation } from '../types';

function ConversationDetail({ c }: { c: Conversation }) {
  return (
    <div className="detail-panel card">
      <section>
        <h3>元数据</h3>
        <p>ID: {c.conversationId}</p>
        <p>状态: <span className={`badge ${c.status}`}>{c.status}</span></p>
        <p>录制: {c.recordedAt ? new Date(c.recordedAt).toLocaleString('zh-CN') : '—'}</p>
        {c.sentiment && <p>情感: {c.sentiment}</p>}
        {c.durationSeconds != null && <p>时长: {c.durationSeconds}s</p>}
      </section>
      {c.summary && (
        <section>
          <h3>AI 摘要</h3>
          <p>{c.summary}</p>
        </section>
      )}
      {c.transcript && (
        <section>
          <h3>转写全文</h3>
          <pre>{c.transcript}</pre>
        </section>
      )}
      {!c.transcript && !c.summary && (
        <p className="empty">处理中或尚无结果，请稍后刷新</p>
      )}
    </div>
  );
}

export default function ConversationsPage() {
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
      setSelected((prev) => prev ?? data[0] ?? null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [days]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <>
      <h2 className="page-title">记忆对话</h2>
      <p className="page-desc">查看转写、摘要与情感标签</p>
      {error && <div className="error-banner">{error}</div>}

      <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem', alignItems: 'end' }}>
        <div className="form-row" style={{ margin: 0, maxWidth: 140 }}>
          <label>最近天数</label>
          <input className="input" type="number" min={1} max={365} value={days}
            onChange={(e) => setDays(Number(e.target.value))} />
        </div>
        <button type="button" className="btn btn-ghost" onClick={load} disabled={loading}>
          {loading ? '加载中…' : '刷新'}
        </button>
      </div>

      <div className="split">
        <div className="list">
          {items.map((c) => (
            <div
              key={c.conversationId}
              className={`list-item${selected?.conversationId === c.conversationId ? ' selected' : ''}`}
              onClick={() => setSelected(c)}
            >
              <h4>{c.summary?.slice(0, 60) || c.transcript?.slice(0, 60) || '（等待处理）'}</h4>
              <div className="meta">
                <span className={`badge ${c.status}`}>{c.status}</span>
                <span>{c.recordedAt ? new Date(c.recordedAt).toLocaleString('zh-CN') : ''}</span>
                {c.sentiment && <span>{c.sentiment}</span>}
              </div>
            </div>
          ))}
          {!loading && items.length === 0 && <div className="empty">暂无对话，请先上传音频</div>}
        </div>
        <div>{selected ? <ConversationDetail c={selected} /> : null}</div>
      </div>
    </>
  );
}
