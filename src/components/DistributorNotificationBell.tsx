import { useCallback, useEffect, useRef, useState } from 'react';
import { Bell, Loader2, AlertCircle, CheckCheck, Inbox } from 'lucide-react';
import { apiFetch } from '../config/api';

/**
 * Notifications bell for the distributor shell (investor.md M5 / R8).
 * Polls an unread-count badge every 60s, and on click opens a dropdown panel
 * that lists notifications (newest first), supports "mark all read", and marks
 * a single unread item read on click. Closes on outside click.
 */

interface DistributorNotification {
  id: string;
  type?: string;
  title?: string;
  body?: string;
  readAt?: string | null;
  createdAt?: string;
}

interface DistributorNotificationBellProps {
  distributorId: string;
}

const POLL_MS = 60_000;

const relativeTime = (iso?: string): string => {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (!Number.isFinite(then)) return '';
  const diff = Date.now() - then;
  if (diff < 0) return 'just now';
  const mins = Math.floor(diff / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 7) return `${days}d ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 5) return `${weeks}w ago`;
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
};

export default function DistributorNotificationBell({ distributorId }: DistributorNotificationBellProps) {
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<DistributorNotification[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [marking, setMarking] = useState(false);

  const wrapRef = useRef<HTMLDivElement | null>(null);

  const base = `/dashboard/distributor/${encodeURIComponent(distributorId)}/notifications`;

  const loadUnread = useCallback(async () => {
    if (!distributorId) return;
    try {
      const res = await apiFetch(`${base}/unread-count`, { skipAuthRedirect: true });
      const body = await res.json().catch(() => null);
      if (!res.ok) return;
      const n = (body as { unread?: number } | null)?.unread;
      if (typeof n === 'number' && Number.isFinite(n)) setUnread(n);
    } catch {
      /* badge polling is best-effort */
    }
  }, [base, distributorId]);

  const loadList = useCallback(async () => {
    if (!distributorId) return;
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch(base, { skipAuthRedirect: true });
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || `Unable to load notifications (${res.status}).`);
      }
      const list: DistributorNotification[] = Array.isArray(body)
        ? body
        : Array.isArray((body as { content?: DistributorNotification[] } | null)?.content)
          ? (body as { content: DistributorNotification[] }).content
          : [];
      const sorted = [...list].sort(
        (a, b) => new Date(b.createdAt ?? 0).getTime() - new Date(a.createdAt ?? 0).getTime(),
      );
      setItems(sorted);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load notifications.');
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [base, distributorId]);

  // Badge: on mount + every 60s.
  useEffect(() => {
    if (!distributorId) return;
    void loadUnread();
    const id = window.setInterval(() => void loadUnread(), POLL_MS);
    return () => window.clearInterval(id);
  }, [distributorId, loadUnread]);

  // Fetch the list whenever the panel opens.
  useEffect(() => {
    if (open) void loadList();
  }, [open, loadList]);

  // Close on outside click.
  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);

  const markAllRead = async () => {
    if (!distributorId) return;
    setMarking(true);
    try {
      const res = await apiFetch(`${base}/read-all`, { method: 'POST', skipAuthRedirect: true });
      if (!res.ok) throw new Error('Could not mark all read.');
      await Promise.all([loadList(), loadUnread()]);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not mark all read.');
    } finally {
      setMarking(false);
    }
  };

  const markOneRead = async (n: DistributorNotification) => {
    if (!distributorId || n.readAt) return;
    try {
      const res = await apiFetch(`${base}/${encodeURIComponent(n.id)}/read`, {
        method: 'POST',
        skipAuthRedirect: true,
      });
      if (!res.ok) throw new Error('Could not mark as read.');
      await Promise.all([loadList(), loadUnread()]);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not mark as read.');
    }
  };

  if (!distributorId) return null;

  const badge = unread > 99 ? '99+' : String(unread);
  const hasUnread = unread > 0;

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-label={hasUnread ? `Notifications, ${unread} unread` : 'Notifications'}
        aria-expanded={open}
        className="relative flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-white text-slate-600 transition-colors hover:bg-slate-50 hover:text-slate-800"
      >
        <Bell className="h-5 w-5" />
        {hasUnread && (
          <span className="absolute -right-1 -top-1 flex min-w-[18px] items-center justify-center rounded-full bg-blue-600 px-1 text-[10px] font-bold leading-none text-white ring-2 ring-white">
            {badge}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-50 mt-2 w-80 overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-2xl sm:w-96">
          <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
            <div>
              <p className="text-sm font-semibold text-slate-800">Notifications</p>
              <p className="text-[11px] text-slate-400">{hasUnread ? `${unread} unread` : 'All caught up'}</p>
            </div>
            <button
              type="button"
              onClick={markAllRead}
              disabled={marking || !hasUnread}
              className="flex items-center gap-1.5 rounded-lg px-2 py-1 text-xs font-semibold text-blue-600 transition-colors hover:bg-blue-50 disabled:cursor-not-allowed disabled:text-slate-300 disabled:hover:bg-transparent"
            >
              {marking ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCheck className="h-3.5 w-3.5" />}
              Mark all read
            </button>
          </div>

          <div className="max-h-96 overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="h-6 w-6 animate-spin text-blue-600" />
              </div>
            ) : error ? (
              <div className="flex items-start gap-2.5 px-4 py-6 text-sm text-red-700">
                <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
                <div>
                  <p className="font-semibold">Couldn't load notifications</p>
                  <p className="mt-1 text-xs">{error}</p>
                  <button onClick={() => void loadList()} className="mt-2 text-xs font-semibold text-red-600 hover:underline">
                    Try again
                  </button>
                </div>
              </div>
            ) : items.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-2 px-4 py-12 text-center">
                <Inbox className="h-7 w-7 text-slate-300" />
                <p className="text-sm font-medium text-slate-500">No notifications yet</p>
                <p className="text-xs text-slate-400">You'll see updates here as they arrive.</p>
              </div>
            ) : (
              <ul className="divide-y divide-slate-50">
                {items.map(n => {
                  const isUnread = !n.readAt;
                  return (
                    <li key={n.id}>
                      <button
                        type="button"
                        onClick={() => void markOneRead(n)}
                        disabled={!isUnread}
                        className={`flex w-full gap-3 px-4 py-3 text-left transition-colors ${
                          isUnread ? 'bg-blue-50/60 hover:bg-blue-50 cursor-pointer' : 'cursor-default hover:bg-slate-50'
                        }`}
                      >
                        <span
                          className={`mt-1.5 h-2 w-2 flex-shrink-0 rounded-full ${isUnread ? 'bg-blue-600' : 'bg-transparent'}`}
                          aria-hidden="true"
                        />
                        <span className="min-w-0 flex-1">
                          <span className="flex items-start justify-between gap-2">
                            <span className={`text-sm leading-snug ${isUnread ? 'font-semibold text-slate-800' : 'font-medium text-slate-600'}`}>
                              {n.title || n.type || 'Notification'}
                            </span>
                            <span className="flex-shrink-0 text-[10px] text-slate-400">{relativeTime(n.createdAt)}</span>
                          </span>
                          {n.body && <span className="mt-0.5 block text-xs leading-relaxed text-slate-500 line-clamp-3">{n.body}</span>}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
