import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import type { Person } from '../types';
import { formatDate } from '../utils/format';

export default function PersonsPage() {
  const [persons, setPersons] = useState<Person[]>([]);
  const [selected, setSelected] = useState<Person | null>(null);
  const [network, setNetwork] = useState<Person[]>([]);
  const [name, setName] = useState('');
  const [relationship, setRelationship] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setError('');
    try {
      setPersons(await api.getPersons());
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const selectPerson = async (p: Person) => {
    setSelected(p);
    try {
      setNetwork(await api.getPersonNetwork(p.name));
    } catch {
      setNetwork([]);
    }
  };

  const addPerson = async () => {
    if (!name.trim()) return;
    setSaving(true);
    try {
      await api.upsertPerson(name.trim(), relationship.trim());
      setName('');
      setRelationship('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <PageHeader title="认识的人" desc="对话里提到的人会自动收录，你也可以手动添加。" />
      {error && <div className="error-banner">{error}</div>}

      <div className="card" style={{ marginBottom: '1.5rem' }}>
        <div className="card-title">添加联系人</div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: '0.75rem', alignItems: 'end' }}>
          <div className="form-row" style={{ margin: 0 }}>
            <label>姓名</label>
            <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="张三" />
          </div>
          <div className="form-row" style={{ margin: 0 }}>
            <label>关系</label>
            <input className="input" value={relationship} onChange={(e) => setRelationship(e.target.value)}
              placeholder="同事、朋友、家人…" />
          </div>
          <button type="button" className="btn btn-primary" onClick={addPerson} disabled={saving}>添加</button>
        </div>
      </div>

      <div className="grid-2">
        <div>
          {persons.length === 0 ? (
            <EmptyState title="还没有人物" hint="对话转写完成后，提及的人物会自动出现；你也可以手动添加。" />
          ) : (
            <div className="person-grid">
              {persons.map((p) => (
                <div
                  key={p.name}
                  className={`person-card${selected?.name === p.name ? ' selected' : ''}`}
                  onClick={() => selectPerson(p)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && selectPerson(p)}
                >
                  <div className="person-avatar">{p.name.charAt(0)}</div>
                  <h4>{p.name}</h4>
                  <p>{p.relationship || '未标注关系'}</p>
                  <p style={{ marginTop: '0.35rem' }}>提及 {p.mentionCount ?? 0} 次</p>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="card">
          {selected ? (
            <>
              <div className="card-title">{selected.name} 的关系网</div>
              {selected.lastSeen && (
                <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginBottom: '1rem' }}>
                  最近提及：{formatDate(selected.lastSeen)}
                </p>
              )}
              {network.length === 0 ? (
                <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>暂无关联人物</p>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  {network.map((n) => (
                    <div key={n.name} style={{
                      padding: '0.75rem', background: 'var(--bg)', borderRadius: 'var(--radius-sm)',
                      border: '1px solid var(--border)',
                    }}>
                      <strong>{n.name}</strong>
                      <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem', marginLeft: '0.5rem' }}>
                        {n.relationship || '关联'}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </>
          ) : (
            <EmptyState title="选择一个人物" hint="点击左侧卡片查看其社交关系网络。" />
          )}
        </div>
      </div>
    </>
  );
}
