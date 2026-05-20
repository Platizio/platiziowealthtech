import React from 'react';
import type { LucideIcon } from 'lucide-react';

/**
 * F-26: shared empty-state for list views (investors, transactions, leads,
 * products, etc.). Render this when `data.length === 0 && !loading && !error`.
 *
 * Two common framings are both supported:
 *
 *   (a) "Nothing exists yet" — pass an `action` (e.g. an "Add investor"
 *       button) so the user has an obvious next step:
 *
 *         <EmptyState
 *           icon={Users}
 *           title="No investors yet"
 *           subtitle="Add your first investor to get started"
 *           action={<button onClick={openAddModal}>Add Investor</button>}
 *         />
 *
 *   (b) "Filter excluded everything" — omit `action`, point users at the
 *       filters instead:
 *
 *         <EmptyState
 *           icon={SearchX}
 *           title="No investors found"
 *           subtitle="Try adjusting your filters"
 *         />
 *
 * `icon` is the lucide-react component itself (e.g. `Users`), not a rendered
 * element — the component renders it with consistent sizing/colour.
 */
export interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  subtitle?: string;
  action?: React.ReactNode;
  /** Tailwind classes appended to the outer container (e.g. spacing tweaks). */
  className?: string;
}

export default function EmptyState({
  icon: Icon,
  title,
  subtitle,
  action,
  className = '',
}: EmptyStateProps) {
  return (
    <div
      className={`flex flex-col items-center justify-center text-center py-16 px-6 ${className}`}
    >
      <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mb-4">
        <Icon className="w-6 h-6 text-slate-400" />
      </div>
      <p className="text-sm font-semibold text-slate-700">{title}</p>
      {subtitle && (
        <p className="text-xs text-slate-400 mt-1 max-w-sm">{subtitle}</p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
