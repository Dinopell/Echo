import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import {
  IconBook, IconHome, IconMic, IconSearch, IconSettings, IconUsers,
} from './Icons';

const mainNav = [
  { to: '/', label: '首页', Icon: IconHome },
  { to: '/upload', label: '录音', Icon: IconMic },
  { to: '/conversations', label: '记忆', Icon: IconBook },
  { to: '/persons', label: '人脉', Icon: IconUsers },
  { to: '/search', label: '搜索', Icon: IconSearch },
  { to: '/settings', label: '设置', Icon: IconSettings },
];

export default function Layout() {
  const [online, setOnline] = useState(true);
  const location = useLocation();

  useEffect(() => {
    api.getControlHealth()
      .then((h) => setOnline(h.status === 'UP'))
      .catch(() => setOnline(false));
  }, []);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <div className="brand-icon">E</div>
            <div>
              <h1>Echo</h1>
              <p>你的本地记忆伴侣</p>
            </div>
          </div>
        </div>

        <nav className="nav-section">
          {mainNav.map(({ to, label, Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
            >
              <Icon size={18} />
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-footer">
          <span className={`health-dot${online ? '' : ' off'}`} />
          {online ? '已连接' : '未连接'}
        </div>
      </aside>

      <div className="content-column">
        <main className="main">
          <Outlet />
        </main>
      </div>

      <nav className="mobile-nav" aria-label="主导航">
        {mainNav.slice(0, 5).map(({ to, label, Icon }) => {
          const active = to === '/'
            ? location.pathname === '/'
            : location.pathname.startsWith(to);
          return (
            <NavLink key={to} to={to} end={to === '/'} className={`mobile-nav-item${active ? ' active' : ''}`}>
              <Icon size={20} />
              <span>{label}</span>
            </NavLink>
          );
        })}
      </nav>
    </div>
  );
}
