import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { Person } from '../types';

export default function PersonsPage() {
  const [persons, setPersons] = useState<Person[]>([]);
  const [network, setNetwork] = useState<Person[]>([]);
  const [selected, setSelected] = useState<Person | null>(null);
  const [name, setName] = useState('');
  const [relationship, setRelationship] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setError('');
    try {
      const data = await api.getPersons();
      setPersons(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const selectPerson = async (p: Person) => {
    setSelected(p);
    try {
      const net = await api.getPersonNetwork(p.name);
      setNetwork(net);
    } catch {
      setNetwork([]);
    }
  };

  const addPerson = async () => {
    if (!name.trim()) return;
    setLoading(true);
    setError('');
    try {
      await api.upsertPerson(name.trim(), relationship.trim());
      setName('');
      setRelationship('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <h2 className="page-title">人物图谱</h2>
      <p className="page-desc">社交关系网络与人物提及统计</p>
      {error && <div className="error-banner">{error}</div>}

      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <h3 style={{ marginBottom: '1rem' }}>添加人物</h3>
        <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
          <div className="form-row" style={{ flex: 1, minWidth: 140, margin: 0 }}>
            <label>姓名</label>
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="form-row" style={{ flex: 1, minWidth: 140, margin: 0 }}>
            <label>关系</label>
            <input className="input" value={relationship} onChange={(e) => setRelationship(e.target.value)}
              placeholder="同事 / 朋友 / 家人" />
          </div>
          <button type="button" className="btn btn-primary" style={{ alignSelf: 'end' }}
            onClick={addPerson} disabled={loading}>保存</button>
        </div>
      </div>

      <div className="split">
        <div className="list">
          {persons.map((p) => (
            <div
              key={p.name}
              className={`list-item${selected?.name === p.name ? ' selected' : ''}`}
              onClick={() => selectPerson(p)}
            >
              <h4>{p.name}</h4>
              <div className="meta">
                <span>{p.relationship || '未标注关系'}</span>
                <span>提及 {p.mentionCount ?? 0} 次</span>
                {p.lastSeen && <span>{new Date(p.lastSeen).toLocaleDateString('zh-CN')}</span>}
              </div>
            </div>
          ))}
          {persons.length === 0 && <div className="empty">暂无人物节点</div>}
        </div>

        <div>
          {selected ? (
            <div className="card detail-panel">
              <section>
                <h3>{selected.name}</h3>
                <p>关系: {selected.relationship || '—'}</p>
                <p>提及次数: {selected.mentionCount ?? 0}</p>
              </section>
              <section>
                <h3>关系网络</h3>
                {network.length === 0 ? (
                  <p className="empty">暂无关联人物</p>
                ) : (
                  <ul style={{ listStyle: 'none', display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    {network.map((n) => (
                      <li key={n.name} style={{ padding: '0.5rem', background: 'var(--bg)', borderRadius: 8 }}>
                        {n.name} — {n.relationship || '关联'}（提及 {n.mentionCount ?? 0}）
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </div>
          ) : (
            <div className="empty">选择人物查看关系网络</div>
          )}
        </div>
      </div>
    </>
  );
}
