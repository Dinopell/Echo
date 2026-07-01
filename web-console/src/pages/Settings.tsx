import { useEffect, useState } from 'react';
import { api, clearToken, getToken, setToken } from '../api/client';

export default function SettingsPage() {
  const [authEnabled, setAuthEnabled] = useState(false);
  const [apiKey, setApiKey] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    api.getAuthStatus().then((s) => setAuthEnabled(s.enabled)).catch(() => {});
  }, []);

  const saveToken = async () => {
    setError('');
    setMessage('');
    try {
      const res = await api.login(apiKey);
      if (res.accessToken) {
        setToken(res.accessToken);
        setMessage('JWT 已保存到本地');
      } else {
        setMessage('JWT 未启用，无需 Token');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '认证失败');
    }
  };

  return (
    <>
      <h2 className="page-title">设置</h2>
      <p className="page-desc">JWT 认证与本地连接配置</p>
      {error && <div className="error-banner">{error}</div>}
      {message && <div className="card" style={{ marginBottom: '1rem', borderColor: 'var(--accent-dim)' }}>{message}</div>}

      <div className="card">
        <h3>JWT 认证</h3>
        <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', margin: '0.5rem 0 1rem' }}>
          当前环境：{authEnabled ? '已启用，API 需要 Bearer Token' : '未启用，可直接调用 API'}
        </p>
        {authEnabled && (
          <>
            <div className="form-row">
              <label>ECHO_API_KEY</label>
              <input className="input" type="password" value={apiKey}
                onChange={(e) => setApiKey(e.target.value)} placeholder="与服务器 .env 中一致" />
            </div>
            <div style={{ display: 'flex', gap: '0.75rem' }}>
              <button type="button" className="btn btn-primary" onClick={saveToken}>换取并保存 Token</button>
              <button type="button" className="btn btn-ghost" onClick={() => { clearToken(); setMessage('已清除 Token'); }}>
                清除 Token
              </button>
            </div>
            <p style={{ marginTop: '0.75rem', fontSize: '0.8rem', color: 'var(--text-muted)' }}>
              已保存: {getToken() ? '是' : '否'}
            </p>
          </>
        )}
      </div>

      <div className="card" style={{ marginTop: '1rem' }}>
        <h3>开发说明</h3>
        <p style={{ fontSize: '0.9rem', color: 'var(--text-muted)', marginTop: '0.5rem', lineHeight: 1.7 }}>
          控制台通过 Nginx 反向代理访问控制面（/api）与数据面（/data-api）。
          本地开发可运行 <code>cd web-console && npm run dev</code>，端口 3000。
        </p>
      </div>
    </>
  );
}
