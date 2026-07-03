import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import type { Conversation, SemanticSearchHit } from '../types';
import { formatDateTime, previewText } from '../utils/format';

export default function SearchPage() {
  const [mode, setMode] = useState<'semantic' | 'query'>('semantic');
  const [query, setQuery] = useState('');
  const [personName, setPersonName] = useState('');
  const [topic, setTopic] = useState('');
  const [semanticHits, setSemanticHits] = useState<SemanticSearchHit[]>([]);
  const [queryHits, setQueryHits] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [searched, setSearched] = useState(false);

  const search = async () => {
    setLoading(true);
    setError('');
    setSearched(true);
    try {
      if (mode === 'semantic') {
        if (!query.trim()) throw new Error('请输入你想回忆的内容');
        setSemanticHits(await api.semanticSearch(query.trim()));
        setQueryHits([]);
      } else {
        const params: Record<string, string | number> = { limit: 20 };
        if (personName.trim()) params.personName = personName.trim();
        else if (topic.trim()) params.topic = topic.trim();
        else throw new Error('请输入人名或话题关键词');
        setQueryHits(await api.queryGraph(params));
        setSemanticHits([]);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败');
    } finally {
      setLoading(false);
    }
  };

  const hasResults = semanticHits.length > 0 || queryHits.length > 0;

  return (
    <>
      <PageHeader
        title="搜索记忆"
        desc="用自然语言回忆往事，或按人物、话题精确查找。"
      />
      {error && <div className="error-banner">{error}</div>}

      <div className="tab-bar">
        <button type="button" className={`tab${mode === 'semantic' ? ' active' : ''}`}
          onClick={() => setMode('semantic')}>想起什么</button>
        <button type="button" className={`tab${mode === 'query' ? ' active' : ''}`}
          onClick={() => setMode('query')}>按人 / 话题</button>
      </div>

      {mode === 'semantic' ? (
        <div className="search-bar">
          <input
            className="input"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="例如：上周和张三聊的项目进展…"
            onKeyDown={(e) => e.key === 'Enter' && search()}
          />
          <button type="button" className="btn btn-primary" onClick={search} disabled={loading}>
            {loading ? '搜索中…' : '回忆'}
          </button>
        </div>
      ) : (
        <>
          <div className="grid-2" style={{ marginBottom: '1rem' }}>
            <div className="form-row" style={{ margin: 0 }}>
              <label>人物姓名</label>
              <input className="input" value={personName} onChange={(e) => setPersonName(e.target.value)} />
            </div>
            <div className="form-row" style={{ margin: 0 }}>
              <label>话题关键词</label>
              <input className="input" value={topic} onChange={(e) => setTopic(e.target.value)} />
            </div>
          </div>
          <button type="button" className="btn btn-primary" onClick={search} disabled={loading}>
            {loading ? '搜索中…' : '筛选'}
          </button>
        </>
      )}

      <div style={{ marginTop: '1.75rem' }}>
        {searched && !loading && !hasResults && !error && (
          <EmptyState title="没有找到相关记忆" hint="试试换一种说法，或上传更多对话录音。" />
        )}
        {semanticHits.map((hit) => (
          <Link
            key={hit.conversation.conversationId}
            to={`/conversations?id=${hit.conversation.conversationId}`}
            className="memory-item search-hit"
          >
            <div className="memory-item-title">{previewText(hit.conversation)}</div>
            <div className="memory-item-meta">
              <span className="search-score">相关度 {(hit.score * 100).toFixed(0)}%</span>
              <span>{formatDateTime(hit.conversation.recordedAt)}</span>
            </div>
          </Link>
        ))}
        {queryHits.map((c) => (
          <Link
            key={c.conversationId}
            to={`/conversations?id=${c.conversationId}`}
            className="memory-item search-hit"
          >
            <div className="memory-item-title">{previewText(c)}</div>
            <div className="memory-item-meta">
              <span>{formatDateTime(c.recordedAt)}</span>
            </div>
          </Link>
        ))}
      </div>
    </>
  );
}
