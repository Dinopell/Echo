import { Link } from 'react-router-dom';

export default function EmptyState({
  title,
  hint,
  actionLabel,
  actionTo,
}: {
  title: string;
  hint?: string;
  actionLabel?: string;
  actionTo?: string;
}) {
  return (
    <div className="empty-state">
      <div className="empty-state-icon">◇</div>
      <h3>{title}</h3>
      {hint && <p>{hint}</p>}
      {actionLabel && actionTo && (
        <Link to={actionTo} className="btn btn-primary">{actionLabel}</Link>
      )}
    </div>
  );
}
