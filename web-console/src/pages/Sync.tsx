import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';

export default function SyncPage() {
  const [status, setStatus] = useState<Record<string, unknown>>({});
  const [discover, setDiscover] = useState<Record<string, unknown>>({});
  const [peer, setPeer] = useState('192.168.1.100:8080');
  const [result, setResult] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      const [s, d] = await Promise.all([api.getP2PStatus(), api.discoverPeers()]);
      setStatus(s);
      setDiscover(d);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const trigger = async () => {
    setError('');
    setResult('');
    try {
      const msg = await api.triggerSync(peer);
      setResult(msg);
    } catch (e) {
      setError(e instanceof Error ? e.message : '同步失败');
    }
  };

  return (
    <>
      <h2 className="page-title">P2P 同步</h2>
      <p className="page-desc">局域网设备间加密快照同步（需启用 P2P_ENABLED）</p>
      {error && <div className="error-banner">{error}</div>}

      <div className="grid-2">
        <div className="card">
          <h3>同步状态</h3>
          <pre style={{ fontSize: '0.82rem', marginTop: '0.75rem', overflow: 'auto' }}>
            {JSON.stringify(status, null, 2)}
          </pre>
        </div>
        <div className="card">
          <h3>设备发现</h3>
          <pre style={{ fontSize: '0.82rem', marginTop: '0.75rem', overflow: 'auto' }}>
            {JSON.stringify(discover, null, 2)}
          </pre>
        </div>
      </div>

      <div className="card" style={{ marginTop: '1rem' }}>
        <h3 style={{ marginBottom: '1rem' }}>手动触发同步</h3>
        <div className="form-row">
          <label>对端地址（IP:端口）</label>
          <input className="input" value={peer} onChange={(e) => setPeer(e.target.value)} />
        </div>
        <div style={{ display: 'flex', gap: '0.75rem' }}>
          <button type="button" className="btn btn-primary" onClick={trigger}>触发同步</button>
          <button type="button" className="btn btn-ghost" onClick={load}>刷新状态</button>
        </div>
        {result && <p style={{ marginTop: '1rem', color: 'var(--accent)' }}>{result}</p>}
      </div>
    </>
  );
}
