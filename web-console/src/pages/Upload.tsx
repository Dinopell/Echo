import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { TaskStatus } from '../types';

export default function UploadPage() {
  const [file, setFile] = useState<File | null>(null);
  const [language, setLanguage] = useState('zh');
  const [uploading, setUploading] = useState(false);
  const [taskId, setTaskId] = useState('');
  const [conversationId, setConversationId] = useState('');
  const [taskStatus, setTaskStatus] = useState<TaskStatus | null>(null);
  const [error, setError] = useState('');
  const [drag, setDrag] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const pollRef = useRef<number>();

  const pollTask = useCallback((id: string) => {
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = window.setInterval(async () => {
      try {
        const s = await api.getTaskStatus(id);
        setTaskStatus(s);
        if (s.status === 'done' || s.status === 'failed') {
          if (pollRef.current) clearInterval(pollRef.current);
        }
      } catch {
        /* ignore poll errors */
      }
    }, 3000);
  }, []);

  useEffect(() => () => {
    if (pollRef.current) clearInterval(pollRef.current);
  }, []);

  const onUpload = async () => {
    if (!file) return;
    setUploading(true);
    setError('');
    setTaskStatus(null);
    try {
      const res = await api.uploadAudio(file, language);
      setTaskId(res.taskId);
      setConversationId(res.conversationId);
      pollTask(res.taskId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDrag(false);
    const f = e.dataTransfer.files[0];
    if (f) setFile(f);
  };

  return (
    <>
      <h2 className="page-title">聆听上传</h2>
      <p className="page-desc">上传音频后自动加密存储、转写、摘要并写入知识图谱</p>
      {error && <div className="error-banner">{error}</div>}

      <div
        className={`dropzone${drag ? ' dragover' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDrag(true); }}
        onDragLeave={() => setDrag(false)}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => e.key === 'Enter' && inputRef.current?.click()}
      >
        <input
          ref={inputRef}
          type="file"
          accept="audio/*,.wav,.mp3,.m4a"
          hidden
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
        {file ? (
          <p><strong>{file.name}</strong>（{(file.size / 1024).toFixed(1)} KB）</p>
        ) : (
          <p>拖拽或点击选择 wav / mp3 / m4a 音频</p>
        )}
      </div>

      <div className="form-row" style={{ maxWidth: 200, marginTop: '1rem' }}>
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

      {taskId && (
        <div className="card detail-panel" style={{ marginTop: '1.5rem' }}>
          <section>
            <h3>任务信息</h3>
            <p>Task ID: <code>{taskId}</code></p>
            <p>Conversation ID: <code>{conversationId}</code></p>
          </section>
          {taskStatus && (
            <section>
              <h3>处理状态</h3>
              <p>
                <span className={`badge ${taskStatus.status}`}>{taskStatus.status}</span>
                {taskStatus.transcript_length != null && ` · 转写长度 ${taskStatus.transcript_length}`}
                {taskStatus.sentiment && ` · 情感 ${taskStatus.sentiment}`}
              </p>
              {taskStatus.error && <p style={{ color: 'var(--danger)' }}>{taskStatus.error}</p>}
              {taskStatus.status === 'done' && (
                <p style={{ marginTop: '0.75rem' }}>
                  <a href="/conversations">→ 查看记忆对话</a>
                </p>
              )}
            </section>
          )}
        </div>
      )}
    </>
  );
}
