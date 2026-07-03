import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, clearToken, getToken, setToken } from '../api/client';
import PageHeader from '../components/PageHeader';

type Tab = 'general' | 'system' | 'sync';

export default function SettingsPage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'general';

  const [authEnabled, setAuthEnabled] = useState(false);
  const [apiKey, setApiKey] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const [controlHealth, setControlHealth] = useState('—');
  const [dataHealth, setDataHealth] = useState('—');
  const [consumer, setConsumer] = useState('—');
  const [queues, setQueues] = useState<Record<string, number>>({});

  const [p2pStatus, setP2pStatus] = useState<Record<string, unknown>>({});
  const [discover, setDiscover] = useState<Record<string, unknown>>({});
  const [peer, setPeer] = useState('192.168.1.100:8080');
  const [syncResult, setSyncResult] = useState('');

  const setTab = (t: Tab) => setParams({ tab: t });

  const loadSystem = useCallback(async () => {
    try {
      const [ch, dh, q] = await Promise.all([
        api.getControlHealth(),
        api.getDataHealth(),
        api.getQueues(),
      ]);
      setControlHealth(ch.status);
      setDataHealth(dh.status);
      setConsumer(dh.consumer_running ? '运行中' : '已停止');
      setQueues(q);
    } catch { /* ignore */ }
  }, []);

  const loadSync = useCallback(async () => {
    try {
      const [s, d] = await Promise.all([api.getP2PStatus(), api.discoverPeers()]);
      setP2pStatus(s);
      setDiscover(d);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    api.getAuthStatus().then((s) => setAuthEnabled(s.enabled)).catch(() => {});
  }, []);

  useEffect(() => {
    if (tab === 'system') loadSystem();
    if (tab === 'sync') loadSync();
  }, [tab, loadSystem, loadSync]);

  const saveToken = async () => {
    setError('');
    setMessage('');
    try {
      const res = await api.login(apiKey);
      if (res.accessToken) {
        setToken(res.accessToken);
        setMessage('已保存登录凭证');
      } else {
        setMessage('当前环境无需登录');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '认证失败');
    }
  };

  const triggerSync = async () => {
    setSyncResult('');
    try {
      setSyncResult(await api.triggerSync(peer));
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步失败');
    }
  };

  return (
    <>
      <PageHeader title="设置" desc="账户、系统状态与设备同步。" />
      {error && <div className="error-banner">{error}</div>}
      {message && <div className="card" style={{ marginBottom: '1rem', borderColor: 'rgba(232,184,109,0.3)' }}>{message}</div>}

      <div className="settings-tabs">
        {(['general', 'system', 'sync'] as Tab[]).map((t) => (
          <button key={t} type="button" className={`settings-tab${tab === t ? ' active' : ''}`}
            onClick={() => setTab(t)}>
            {t === 'general' ? '账户' : t === 'system' ? '系统' : 'P2P 同步'}
          </button>
        ))}
      </div>

      {tab === 'general' && (
        <div className="card">
          <div className="card-title">登录与隐私</div>
          <p style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', marginBottom: '1rem' }}>
            {authEnabled
              ? '已启用 JWT，请使用 API Key 换取访问凭证。'
              : '开发模式下无需登录，所有数据仅在本地处理。'}
          </p>
          {authEnabled && (
            <>
              <div className="form-row">
                <label>API Key</label>
                <input className="input" type="password" value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)} />
              </div>
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button type="button" className="btn btn-primary" onClick={saveToken}>保存凭证</button>
                <button type="button" className="btn btn-ghost" onClick={() => { clearToken(); setMessage('已清除'); }}>
                  清除
                </button>
              </div>
              <p style={{ marginTop: '0.75rem', fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                凭证状态：{getToken() ? '已保存' : '未设置'}
              </p>
            </>
          )}
        </div>
      )}

      {tab === 'system' && (
        <>
          <div className="grid-3" style={{ marginBottom: '1rem' }}>
            <div className="card">
              <div className="card-title">控制面</div>
              <div className="stat-value" style={{ fontSize: '1.1rem' }}>{controlHealth}</div>
            </div>
            <div className="card">
              <div className="card-title">数据面</div>
              <div className="stat-value" style={{ fontSize: '1.1rem' }}>{dataHealth}</div>
            </div>
            <div className="card">
              <div className="card-title">任务消费者</div>
              <div className="stat-value" style={{ fontSize: '1.1rem' }}>{consumer}</div>
            </div>
          </div>
          <div className="card" style={{ marginBottom: '1rem' }}>
            <div className="card-title">任务队列</div>
            <pre className="json-block">{JSON.stringify(queues, null, 2)}</pre>
          </div>
          <div className="card">
            <div className="card-title">开发者工具</div>
            <div className="link-list" style={{ marginTop: '0.75rem' }}>
              <a href="http://localhost:7474" target="_blank" rel="noreferrer">Neo4j Browser <span>→</span></a>
              <a href="http://localhost:9001" target="_blank" rel="noreferrer">MinIO 控制台 <span>→</span></a>
              <a href="/data-api/docs" target="_blank" rel="noreferrer">数据面 API 文档 <span>→</span></a>
            </div>
          </div>
        </>
      )}

      {tab === 'sync' && (
        <>
          <div className="grid-2">
            <div className="card">
              <div className="card-title">同步状态</div>
              <pre className="json-block">{JSON.stringify(p2pStatus, null, 2)}</pre>
            </div>
            <div className="card">
              <div className="card-title">局域网设备</div>
              <pre className="json-block">{JSON.stringify(discover, null, 2)}</pre>
            </div>
          </div>
          <div className="card" style={{ marginTop: '1rem' }}>
            <div className="card-title">手动同步到对端</div>
            <div className="form-row">
              <label>对端地址</label>
              <input className="input" value={peer} onChange={(e) => setPeer(e.target.value)}
                placeholder="192.168.1.100:8080" />
            </div>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button type="button" className="btn btn-primary" onClick={triggerSync}>开始同步</button>
              <button type="button" className="btn btn-ghost" onClick={loadSync}>刷新</button>
            </div>
            {syncResult && <p style={{ marginTop: '1rem', color: 'var(--accent)' }}>{syncResult}</p>}
          </div>
        </>
      )}
    </>
  );
}
