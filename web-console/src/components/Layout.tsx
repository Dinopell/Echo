import { NavLink, Outlet } from 'react-router-dom';

const links = [
  { to: '/', label: '概览', icon: '◉' },
  { to: '/upload', label: '聆听上传', icon: '◎' },
  { to: '/conversations', label: '记忆对话', icon: '▤' },
  { to: '/persons', label: '人物图谱', icon: '◈' },
  { to: '/search', label: '智能搜索', icon: '⌕' },
  { to: '/sync', label: 'P2P 同步', icon: '⇄' },
  { to: '/settings', label: '设置', icon: '⚙' },
];

export default function Layout() {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <h1>Echo</h1>
          <p>本地隐私记忆控制台</p>
        </div>
        {links.map((l) => (
          <NavLink
            key={l.to}
            to={l.to}
            end={l.to === '/'}
            className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
          >
            <span>{l.icon}</span>
            {l.label}
          </NavLink>
        ))}
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
