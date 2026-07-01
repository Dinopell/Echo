import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';

export default function Dashboard() {
  const [controlHealth, setControlHealth] = useState<string>('—');
  const [dataHealth, setDataHealth] = useState<string>('—');
  const [consumer, setConsumer] = useState<string>('—');
  const [auth, setAuth] = useState<string>('—');
  const [queues, setQueues] = useState<Record<string, number>>({});
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    setError('');
    try {
      const [ch, dh, as, q] = await Promise.all([
        api.getControlHealth(),
        api.getDataHealth(),
        api.getAuthStatus(),
        api.getQueues(),
      ]);
      setControlHealth(ch.status);
      setDataHealth(dh.status);
      setConsumer(dh.consumer_running ? '运行中' : '已停止');
      setAuth(as.enabled ? '已启用 JWT' : '未启用');
      setQueues(q);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 15000);
    return () => clearInterval(t);
  }, [refresh]);

  return (
    <>
      <h2 className="page-title">系统概览</h2>
      <p className="page-desc">Echo 全栈本地服务状态与任务队列监控</p>
      {error && <div className="error-banner">{error}</div>}

      <div className="grid-3">
        <div className="card">
          <h3>控制面</h3>
          <div className="value">
            <span className={`badge ${controlHealth === 'UP' ? 'up' : 'down'}`}>{controlHealth}</span>
          </div>
        </div>
        <div className="card">
          <h3>数据面</h3>
          <div className="value">
            <span className={`badge ${dataHealth === 'healthy' ? 'up' : 'down'}`}>{dataHealth}</span>
          </div>
        </div>
        <div className="card">
          <h3>队列消费者</h3>
          <div className="value" style={{ fontSize: '1.1rem' }}>{consumer}</div>
        </div>
        <div className="card">
          <h3>认证</h3>
          <div className="value" style={{ fontSize: '1rem' }}>{auth}</div>
        </div>
      </div>

      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h3 style={{ margin: 0 }}>Redis 任务队列</h3>
          <button type="button" className="btn btn-ghost" onClick={refresh}>刷新</button>
        </div>
        <div className="grid-3">
          {Object.entries(queues).map(([name, count]) => (
            <div key={name}>
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>{name}</div>
              <div style={{ fontSize: '1.4rem', fontWeight: 700 }}>{count}</div>
            </div>
          ))}
          {Object.keys(queues).length === 0 && <div className="empty">暂无队列数据</div>}
        </div>
      </div>

      <div className="card" style={{ marginTop: '1rem' }}>
        <h3>外部工具</h3>
        <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', marginTop: '0.5rem' }}>
          <a href="http://localhost:7474" target="_blank" rel="noreferrer">Neo4j Browser</a>
          {' · '}
          <a href="http://localhost:9001" target="_blank" rel="noreferrer">MinIO 控制台</a>
          {' · '}
          <a href="/data-api/docs" target="_blank" rel="noreferrer">数据面 API 文档</a>
        </p>
      </div>
    </>
  );
}
