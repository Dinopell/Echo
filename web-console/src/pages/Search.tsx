import { useState } from 'react';
import { api } from '../api/client';
import type { Conversation, SemanticSearchHit } from '../types';

export default function SearchPage() {
  const [mode, setMode] = useState<'semantic' | 'query'>('semantic');
  const [query, setQuery] = useState('');
  const [personName, setPersonName] = useState('');
  const [topic, setTopic] = useState('');
  const [semanticHits, setSemanticHits] = useState<SemanticSearchHit[]>([]);
  const [queryHits, setQueryHits] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const search = async () => {
    setLoading(true);
    setError('');
    try {
      if (mode === 'semantic') {
        if (!query.trim()) throw new Error('请输入搜索内容');
        setSemanticHits(await api.semanticSearch(query.trim()));
        setQueryHits([]);
      } else {
        const params: Record<string, string | number> = { limit: 20 };
        if (personName.trim()) params.personName = personName.trim();
        else if (topic.trim()) params.topic = topic.trim();
        setQueryHits(await api.queryGraph(params));
        setSemanticHits([]);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <h2 className="page-title">智能搜索</h2>
      <p className="page-desc">语义向量召回与自然语言图谱查询</p>
      {error && <div className="error-banner">{error}</div>}

      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1.25rem' }}>
        <button type="button" className={`btn ${mode === 'semantic' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => setMode('semantic')}>语义搜索</button>
        <button type="button" className={`btn ${mode === 'query' ? 'btn-primary' : 'btn-ghost'}`}
          onClick={() => setMode('query')}>条件查询</button>
      </div>

      {mode === 'semantic' ? (
        <div className="form-row">
          <label>自然语言查询（BGE 向量 + Neo4j）</label>
          <input className="input" value={query} onChange={(e) => setQuery(e.target.value)}
            placeholder="例如：上周和张三讨论的项目进度" onKeyDown={(e) => e.key === 'Enter' && search()} />
        </div>
      ) : (
        <>
          <div className="form-row">
            <label>人名（优先）</label>
            <input className="input" value={personName} onChange={(e) => setPersonName(e.target.value)} />
          </div>
          <div className="form-row">
            <label>话题关键词</label>
            <input className="input" value={topic} onChange={(e) => setTopic(e.target.value)} />
          </div>
        </>
      )}

      <button type="button" className="btn btn-primary" onClick={search} disabled={loading}>
        {loading ? '搜索中…' : '搜索'}
      </button>

      <div className="list" style={{ marginTop: '1.5rem' }}>
        {semanticHits.map((hit) => (
          <div key={hit.conversation.conversationId} className="list-item">
            <h4>{hit.conversation.summary || hit.conversation.transcript?.slice(0, 80) || '—'}</h4>
            <div className="meta">
              <span>相似度 {(hit.score * 100).toFixed(1)}%</span>
              <span className={`badge ${hit.conversation.status}`}>{hit.conversation.status}</span>
            </div>
            {hit.conversation.summary && <p style={{ marginTop: '0.5rem', fontSize: '0.9rem' }}>{hit.conversation.summary}</p>}
          </div>
        ))}
        {queryHits.map((c) => (
          <div key={c.conversationId} className="list-item">
            <h4>{c.summary || c.transcript?.slice(0, 80) || c.conversationId}</h4>
            <div className="meta">
              <span>{c.recordedAt ? new Date(c.recordedAt).toLocaleString('zh-CN') : ''}</span>
              <span className={`badge ${c.status}`}>{c.status}</span>
            </div>
          </div>
        ))}
        {!loading && semanticHits.length === 0 && queryHits.length === 0 && (
          <div className="empty">输入条件后搜索</div>
        )}
      </div>
    </>
  );
}
