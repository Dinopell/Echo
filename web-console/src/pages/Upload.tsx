import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import PageHeader from '../components/PageHeader';
import { StatusBadge } from '../components/StatusBadge';
import { IconUpload } from '../components/Icons';
import type { TaskStatus } from '../types';
import { statusLabel } from '../utils/format';

const STEPS = ['上传', '转写', '摘要', '存档'];

export default function UploadPage() {
  const [file, setFile] = useState<File | null>(null);
  const [language, setLanguage] = useState('zh');
  const [uploading, setUploading] = useState(false);
  const [taskId, setTaskId] = useState('');
  const [taskStatus, setTaskStatus] = useState<TaskStatus | null>(null);
  const [error, setError] = useState('');
  const [drag, setDrag] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const pollRef = useRef<number>();

  const stepIndex = !taskId ? -1
    : taskStatus?.status === 'pending' ? 0
    : taskStatus?.status === 'processing' ? 2
    : taskStatus?.status === 'done' ? 4
    : taskStatus?.status === 'failed' ? -2 : 0;

  const pollTask = useCallback((id: string) => {
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = window.setInterval(async () => {
      try {
        const s = await api.getTaskStatus(id);
        setTaskStatus(s);
        if (s.status === 'done' || s.status === 'failed') {
          if (pollRef.current) clearInterval(pollRef.current);
        }
      } catch { /* ignore */ }
    }, 2500);
  }, []);

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  const onUpload = async () => {
    if (!file) return;
    setUploading(true);
    setError('');
    setTaskStatus(null);
    try {
      const res = await api.uploadAudio(file, language);
      setTaskId(res.taskId);
      pollTask(res.taskId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '上传失败');
    } finally {
      setUploading(false);
    }
  };

  return (
    <>
      <PageHeader
        title="录音上传"
        desc="音频仅在本地加密存储与处理，不会上传到任何云端。"
      />
      {error && <div className="error-banner">{error}</div>}

      <div
        className={`upload-zone${drag ? ' dragover' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDrag(true); }}
        onDragLeave={() => setDrag(false)}
        onDrop={(e) => { e.preventDefault(); setDrag(false); if (e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]); }}
        onClick={() => inputRef.current?.click()}
        role="button"
        tabIndex={0}
      >
        <input ref={inputRef} type="file" accept="audio/*,.wav,.mp3,.m4a" hidden
          onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        <div className="upload-zone-icon"><IconUpload size={26} /></div>
        {file ? (
          <>
            <h3>{file.name}</h3>
            <p>{(file.size / 1024).toFixed(1)} KB · 点击可更换文件</p>
          </>
        ) : (
          <>
            <h3>拖入或选择音频文件</h3>
            <p>支持 wav、mp3、m4a</p>
          </>
        )}
      </div>

      <div style={{ display: 'flex', gap: '1rem', marginTop: '1.25rem', flexWrap: 'wrap', alignItems: 'end' }}>
        <div className="form-row" style={{ margin: 0, minWidth: 160, flex: '0 0 auto' }}>
          <label>语言</label>
          <select className="select" value={language} onChange={(e) => setLanguage(e.target.value)}>
            <option value="auto">自动检测</option>
            <option value="zh">中文</option>
            <option value="en">英文</option>
          </select>
        </div>
        <button type="button" className="btn btn-primary" disabled={!file || uploading} onClick={onUpload}>
          {uploading ? '上传中…' : '开始处理'}
        </button>
      </div>

      {taskId && (
        <div className="card" style={{ marginTop: '1.75rem' }}>
          <div className="card-title">处理进度</div>
          <div className="progress-steps">
            {STEPS.map((label, i) => (
              <div key={label}
                className={`progress-step${i < stepIndex ? ' done' : i === stepIndex ? ' active' : ''}`}>
                {label}
              </div>
            ))}
          </div>
          {taskStatus && (
            <div style={{ marginTop: '1.25rem' }}>
              <StatusBadge status={taskStatus.status} />
              {taskStatus.sentiment && (
                <span style={{ marginLeft: '0.75rem', color: 'var(--text-muted)', fontSize: '0.88rem' }}>
                  情感倾向：{taskStatus.sentiment}
                </span>
              )}
              {taskStatus.error && (
                <p style={{ color: 'var(--danger)', marginTop: '0.75rem', fontSize: '0.9rem' }}>{taskStatus.error}</p>
              )}
              {taskStatus.status === 'done' && (
                <div style={{ marginTop: '1rem' }}>
                  <Link to="/conversations" className="btn btn-ghost btn-sm">查看记忆详情 →</Link>
                </div>
              )}
              {taskStatus.status === 'failed' && (
                <p style={{ marginTop: '0.5rem', fontSize: '0.82rem', color: 'var(--text-muted)' }}>
                  状态：{statusLabel(taskStatus.status)}
                </p>
              )}
            </div>
          )}
        </div>
      )}
    </>
  );
}
