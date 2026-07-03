import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import type { Conversation } from '../types';
import { formatDateTime, previewText } from '../utils/format';

function greeting() {
  const h = new Date().getHours();
  if (h < 6) return '夜深了';
  if (h < 12) return '早上好';
  if (h < 18) return '下午好';
  return '晚上好';
}

export default function HomePage() {
  const [recent, setRecent] = useState<Conversation[]>([]);
  const [memoryCount, setMemoryCount] = useState(0);
  const [personCount, setPersonCount] = useState(0);
  const [processing, setProcessing] = useState(0);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const [conv, persons, queues] = await Promise.all([
        api.getConversations(7),
        api.getPersons(),
        api.getQueues(),
      ]);
      setMemoryCount(conv.length);
      setRecent(conv.slice(0, 5));
      setPersonCount(persons.length);
      setProcessing(queues['echo:queue:transcribe'] ?? 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  return (
    <>
      <header className="welcome-header">
        <p className="welcome-eyebrow">{greeting()}</p>
        <h1 className="welcome-title">欢迎回来</h1>
        <p className="welcome-desc">把对话录下来，Echo 会帮你整理成可搜索的记忆。</p>
      </header>

      <div className="hero-card">
        <div>
          <h2>上传一段录音</h2>
          <p>支持 wav、mp3、m4a，全程在本地处理，数据不会离开你的设备。</p>
        </div>
        <Link to="/upload" className="btn btn-primary">开始上传</Link>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {processing > 0 && (
        <div className="notice-banner">
          有 {processing} 段录音正在处理，完成后会自动出现在记忆库里。
        </div>
      )}

      <div className="grid-2 home-stats">
        <div className="card stat-card">
          <div className="card-title">近 7 天记忆</div>
          <div className="stat-value">{memoryCount}</div>
        </div>
        <div className="card stat-card">
          <div className="card-title">认识的人</div>
          <div className="stat-value">{personCount}</div>
        </div>
      </div>

      <div className="section-header">
        <h2 className="section-title">最近记忆</h2>
        {recent.length > 0 && (
          <Link to="/conversations" className="btn btn-ghost btn-sm">查看全部</Link>
        )}
      </div>

      {recent.length === 0 ? (
        <EmptyState
          title="还没有记忆"
          hint="上传你的第一段录音，转写和摘要完成后会出现在这里。"
          actionLabel="去上传"
          actionTo="/upload"
        />
      ) : (
        <div className="memory-feed">
          {recent.map((c) => (
            <Link
              key={c.conversationId}
              to={`/conversations?id=${c.conversationId}`}
              className="memory-item"
            >
              <div className="memory-item-title">{previewText(c)}</div>
              <div className="memory-item-meta">
                {c.status && c.status !== 'done' && <StatusBadge status={c.status} />}
                <span>{formatDateTime(c.recordedAt)}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </>
  );
}
