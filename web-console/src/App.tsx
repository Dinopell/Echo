import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import UploadPage from './pages/Upload';
import ConversationsPage from './pages/Conversations';
import PersonsPage from './pages/Persons';
import SearchPage from './pages/Search';
import SyncPage from './pages/Sync';
import SettingsPage from './pages/Settings';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="upload" element={<UploadPage />} />
          <Route path="conversations" element={<ConversationsPage />} />
          <Route path="persons" element={<PersonsPage />} />
          <Route path="search" element={<SearchPage />} />
          <Route path="sync" element={<SyncPage />} />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
