import { useCallback, useEffect, useState } from 'react';
import {
  ShieldCheck, Loader2, AlertCircle, Clock, CheckCircle2, XCircle, ChevronRight,
} from 'lucide-react';
import EmptyState from '../components/EmptyState';
import TransactionApprovalPanel, {
  type ApprovalChallenge,
} from '../components/TransactionApprovalPanel';
import { apiFetch } from '../config/api';

const normalizeStatus = (value?: string) => String(value || '').trim().toUpperCase();

const STATUS_META: Record<string, { label: string; cls: string; icon: typeof Clock }> = {
  PENDING: { label: 'Awaiting your approval', cls: 'bg-amber-50 border-amber-200 text-amber-700', icon: Clock },
  CHALLENGE_SENT: { label: 'Code sent', cls: 'bg-blue-50 border-blue-200 text-blue-700', icon: Clock },
  APPROVED: { label: 'Approved', cls: 'bg-emerald-50 border-emerald-200 text-emerald-700', icon: CheckCircle2 },
  CONSUMED: { label: 'Submitted', cls: 'bg-emerald-50 border-emerald-200 text-emerald-700', icon: CheckCircle2 },
  SUBMITTED: { label: 'Submitted', cls: 'bg-emerald-50 border-emerald-200 text-emerald-700', icon: CheckCircle2 },
  EXPIRED: { label: 'Expired', cls: 'bg-slate-100 border-slate-200 text-slate-600', icon: XCircle },
  REJECTED: { label: 'Rejected', cls: 'bg-red-50 border-red-200 text-red-700', icon: XCircle },
  SUPERSEDED: { label: 'Superseded', cls: 'bg-slate-100 border-slate-200 text-slate-600', icon: XCircle },
};

const statusMeta = (status?: string) =>
  STATUS_META[normalizeStatus(status)] || { label: normalizeStatus(status) || 'Pending', cls: 'bg-slate-100 border-slate-200 text-slate-600', icon: Clock };

function StatusPill({ status }: { status?: string }) {
  const meta = statusMeta(status);
  const Icon = meta.icon;
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-semibold ${meta.cls}`}>
      <Icon className="h-3.5 w-3.5" /> {meta.label}
    </span>
  );
}

const typeLabel = (t?: string) => String(t || 'Transaction').replace(/_/g, ' ');

/**
 * F: investor Approval Center. Lists the investor's transaction-approval (2FA)
 * challenges via {@code GET /investor/approvals}; selecting one opens the
 * {@link TransactionApprovalPanel} (frozen snapshot + consent + 6-box OTP +
 * Approve). An EmptyState renders when there are no approvals. Mirrors the
 * Phase-1 onboarding-review look and the navy theme exactly.
 */
export default function InvestorApprovalCenter() {
  const [challenges, setChallenges] = useState<ApprovalChallenge[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ApprovalChallenge | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadList = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const res = await apiFetch('/investor/approvals');
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Unable to load your approvals.');
      }
      const list: ApprovalChallenge[] = Array.isArray(data)
        ? data
        : Array.isArray((data as { content?: ApprovalChallenge[] } | null)?.content)
          ? (data as { content: ApprovalChallenge[] }).content
          : [];
      setChallenges(list);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Unable to load your approvals.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadList();
  }, [loadList]);

  const openChallenge = useCallback(async (id: string) => {
    setSelectedId(id);
    setDetail(null);
    setDetailLoading(true);
    try {
      const res = await apiFetch(`/investor/approvals/${id}`);
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Unable to load this approval.');
      }
      setDetail(data as ApprovalChallenge);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Unable to load this approval.');
      setSelectedId(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const closeDetail = useCallback(() => {
    setSelectedId(null);
    setDetail(null);
    void loadList();
  }, [loadList]);

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading your approvals…</p>
        </div>
      </div>
    );
  }

  // Detail view
  if (selectedId) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-slate-800">Approve transaction</h1>
          <p className="mt-1 text-sm text-slate-500">
            Review the exact transaction and authorize it with a one-time passcode.
          </p>
        </div>
        {detailLoading || !detail ? (
          <div className="flex min-h-[40vh] items-center justify-center">
            <Loader2 className="h-7 w-7 animate-spin text-blue-600" />
          </div>
        ) : (
          <TransactionApprovalPanel
            challenge={detail}
            onBack={closeDetail}
            onChallengeUpdated={(c) => setDetail(c)}
            onApproved={() => void loadList()}
          />
        )}
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl p-8">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-slate-800">Approval center</h1>
        <p className="mt-1 text-sm text-slate-500">
          Securely approve transactions your distributor has submitted. Each approval needs a one-time passcode.
        </p>
      </div>

      {loadError && (
        <div className="mb-6 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
          <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
          <div>
            <p className="text-sm text-red-700">{loadError}</p>
            <button onClick={() => void loadList()} className="mt-2 text-xs font-semibold text-red-600 hover:underline">
              Try again
            </button>
          </div>
        </div>
      )}

      {challenges.length === 0 && !loadError ? (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <EmptyState
            icon={ShieldCheck}
            title="No approvals pending"
            subtitle="When your distributor submits a transaction for your approval, it will appear here for you to review and authorize."
          />
        </div>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
          {challenges.map((c, i) => {
            const meta = statusMeta(c.status);
            const live = ['PENDING', 'CHALLENGE_SENT', 'APPROVED'].includes(normalizeStatus(c.status));
            return (
              <button
                key={c.id}
                type="button"
                onClick={() => void openChallenge(c.id)}
                className={`flex w-full items-center justify-between gap-4 px-6 py-4 text-left transition-colors hover:bg-slate-50 ${i > 0 ? 'border-t border-slate-100' : ''}`}
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-slate-800">{typeLabel(c.transactionType)}</span>
                    <StatusPill status={c.status} />
                  </div>
                  {c.expiresAt && live && (
                    <p className="mt-1 flex items-center gap-1 text-[11px] text-slate-400">
                      <Clock className="h-3 w-3" /> Expires {new Date(c.expiresAt).toLocaleString('en-IN')}
                    </p>
                  )}
                </div>
                <ChevronRight className="h-4 w-4 flex-shrink-0 text-slate-400" />
                <span className="sr-only">{meta.label}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
