import type { ReactNode } from 'react';

type IconProps = { size?: number; className?: string };

function Icon({ size = 20, className, children }: IconProps & { children: ReactNode }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}
      stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
      {children}
    </svg>
  );
}

export function IconHome(p: IconProps) {
  return <Icon {...p}><path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" /></Icon>;
}
export function IconMic(p: IconProps) {
  return <Icon {...p}><path d="M12 14a3 3 0 0 0 3-3V6a3 3 0 1 0-6 0v5a3 3 0 0 0 3 3z" /><path d="M19 11a7 7 0 0 1-14 0M12 18v3" /></Icon>;
}
export function IconBook(p: IconProps) {
  return <Icon {...p}><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" /></Icon>;
}
export function IconUsers(p: IconProps) {
  return <Icon {...p}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></Icon>;
}
export function IconSearch(p: IconProps) {
  return <Icon {...p}><circle cx="11" cy="11" r="8" /><path d="m21 21-4.3-4.3" /></Icon>;
}
export function IconSettings(p: IconProps) {
  return <Icon {...p}><circle cx="12" cy="12" r="3" /><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" /></Icon>;
}
export function IconUpload(p: IconProps) {
  return <Icon {...p}><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" x2="12" y1="3" y2="15" /></Icon>;
}
export function IconWave(p: IconProps) {
  return <Icon {...p}><path d="M2 12h2l2-7 4 14 4-18 4 11h2" /></Icon>;
}
