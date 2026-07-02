import { useCallback, useEffect, useState } from 'react';
import {
  ShieldCheck, Loader2, AlertCircle, Clock, CheckCircle2, XCircle, ChevronRight, UserCog,
} from 'lucide-react';
import EmptyState from '../components/EmptyState';
import TransactionApprovalPanel, {
  type ApprovalChallenge,
} from '../components/TransactionApprovalPanel';
import ProfileChangeApprovalPanel from '../components/ProfileChangeApprovalPanel';
import {
  apiFetch,
  listProfileChanges,
  type ProfileChangeApprovalSummary,
} from '../config/api';

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
 * The list (GET /investor/approvals) and detail (GET /investor/approvals/{challengeId})
 * endpoints key the challenge id as `challengeId` (see ApprovalSummaryResponse /
 * ApprovalDetailResponse) — there is no `id` field. The UI and TransactionApprovalPanel
 * read `id`, so normalize it here. Without this, `c.id` is undefined → duplicate React
 * keys and a GET /investor/approvals/undefined (HTTP 400) the moment a row is clicked.
 */
const toChallenge = (raw: any): ApprovalChallenge =>
  ({ ...raw, id: raw?.id ?? raw?.challengeId }) as ApprovalChallenge;

type TabKey = 'transactions' | 'profile';

/**
 * F: investor Approval Center. Two tabbed sections, both on the navy theme:
 *
 *   • Transactions — transaction-approval (2FA) challenges via {@code GET /investor/approvals};
 *     selecting one opens {@link TransactionApprovalPanel} (frozen snapshot + consent + OTP).
 *   • Profile changes (R9, the skip-form second loop) — distributor-filled profile changes via
 *     {@code GET /investor/profile-changes}; selecting one opens {@link ProfileChangeApprovalPanel}
 *     (frozen pending profile + consent + OTP → Approve, or Reject-with-reason).
 *
 * Each tab shows a count badge and its own EmptyState; resolving an item clears it from the list.
 */
export default function InvestorApprovalCenter() {
  const [tab, setTab] = useState<TabKey>('transactions');

  // ── Transaction approvals (existing) ──────────────────────────────────────
  const [challenges, setChallenges] = useState<ApprovalChallenge[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ApprovalChallenge | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // ── Profile-change approvals (R9) ─────────────────────────────────────────
  const [changes, setChanges] = useState<ProfileChangeApprovalSummary[]>([]);
  const [changesLoading, setChangesLoading] = useState(true);
  const [changesError, setChangesError] = useState('');
  const [selectedChange, setSelectedChange] = useState<ProfileChangeApprovalSummary | null>(null);

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
      setChallenges(list.map(toChallenge));
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Unable to load your approvals.');
    } finally {
      setLoading(false);
    }
  }, []);

  const loadChanges = useCallback(async () => {
    setChangesLoading(true);
    setChangesError('');
    try {
      const list = await listProfileChanges();
      setChanges(Array.isArray(list) ? list : []);
    } catch (e) {
      setChangesError(e instanceof Error ? e.message : 'Unable to load your profile changes.');
    } finally {
      setChangesLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadList();
    void loadChanges();
  }, [loadList, loadChanges]);

  const openChallenge = useCallback(async (id?: string) => {
    if (!id) {
      setLoadError('This approval is missing its identifier and cannot be opened. Please refresh and try again.');
      return;
    }
    setSelectedId(id);
    setDetail(null);
    setDetailLoading(true);
    try {
      const res = await apiFetch(`/investor/approvals/${id}`);
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Unable to load this approval.');
      }
      setDetail(toChallenge(data));
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

  const closeChange = useCallback(() => {
    setSelectedChange(null);
    void loadChanges();
  }, [loadChanges]);

  if (loading && changesLoading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading your approvals…</p>
        </div>
      </div>
    );
  }

  // ── Transaction detail view ───────────────────────────────────────────────
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

  // ── Profile-change detail view ────────────────────────────────────────────
  if (selectedChange) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-slate-800">Approve profile changes</h1>
          <p className="mt-1 text-sm text-slate-500">
            Your distributor filled in these profile details. Review them and authorize with a one-time passcode, or reject them.
          </p>
        </div>
        <ProfileChangeApprovalPanel
          change={selectedChange}
          onBack={closeChange}
          onResolved={() => void loadChanges()}
        />
      </div>
    );
  }

  const tabBtn = (key: TabKey, label: string, count: number) => (
    <button
      type="button"
      onClick={() => setTab(key)}
      className={`flex items-center gap-2 border-b-2 px-1 pb-3 text-sm font-semibold transition-colors ${
        tab === key
          ? 'border-[#0B1B3E] text-[#0B1B3E]'
          : 'border-transparent text-slate-400 hover:text-slate-600'
      }`}
    >
      {label}
      {count > 0 && (
        <span
          className={`inline-flex h-5 min-w-5 items-center justify-center rounded-full px-1.5 text-[11px] font-bold ${
            tab === key ? 'bg-[#0B1B3E] text-white' : 'bg-slate-200 text-slate-600'
          }`}
        >
          {count}
        </span>
      )}
    </button>
  );

  return (
    <div className="mx-auto max-w-2xl p-8">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-slate-800">Approval center</h1>
        <p className="mt-1 text-sm text-slate-500">
          Securely review and authorize what your distributor has submitted. Each approval needs a one-time passcode.
        </p>
      </div>

      <div className="mb-6 flex items-center gap-6 border-b border-slate-200">
        {tabBtn('transactions', 'Transactions', challenges.length)}
        {tabBtn('profile', 'Profile changes', changes.length)}
      </div>

      {tab === 'transactions' ? (
        <>
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
                title="No transactions pending"
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
                    key={c.id ?? i}
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
        </>
      ) : (
        <>
          {changesError && (
            <div className="mb-6 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
              <div>
                <p className="text-sm text-red-700">{changesError}</p>
                <button onClick={() => void loadChanges()} className="mt-2 text-xs font-semibold text-red-600 hover:underline">
                  Try again
                </button>
              </div>
            </div>
          )}

          {changes.length === 0 && !changesError ? (
            <div className="rounded-2xl border border-slate-200 bg-white">
              <EmptyState
                icon={UserCog}
                title="No profile changes pending"
                subtitle="When your distributor fills in or updates your profile details, the change will appear here for you to review and authorize."
              />
            </div>
          ) : (
            <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
              {changes.map((c, i) => {
                const meta = statusMeta(c.status);
                return (
                  <button
                    key={c.challengeId}
                    type="button"
                    onClick={() => setSelectedChange(c)}
                    className={`flex w-full items-center justify-between gap-4 px-6 py-4 text-left transition-colors hover:bg-slate-50 ${i > 0 ? 'border-t border-slate-100' : ''}`}
                  >
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold text-slate-800">Profile update</span>
                        <StatusPill status={c.status} />
                      </div>
                      <p className="mt-0.5 text-[11px] text-slate-400">Filled by your distributor</p>
                      {c.expiresAt && (
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
        </>
      )}
    </div>
  );
}
