import { useCallback, useEffect, useMemo, useState } from 'react';
import { motion } from 'motion/react';
import {
  AlertCircle, ArrowRight, CheckCircle2, Clock, Loader2, Mail,
  ShieldCheck, ThumbsDown, UserPlus, XCircle, ArrowLeft,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  reviewInvestorLink,
  approveInvestorLink,
  rejectInvestorLink,
  approveAndSkipInvestorLink,
  reviewInvestorLinkPublic,
  approveInvestorLinkPublic,
  rejectInvestorLinkPublic,
  type InvestorLinkReviewResponse,
  type InvestorLinkError,
} from '../config/api';
import { useAppSelector } from '../store/hooks';
import {
  selectInvestorUser,
  selectIsInvestorAuthLoading,
} from '../store/slices/investorAuthSlice';

type Phase = 'review' | 'approved' | 'skipped' | 'rejected';

const normalizeStatus = (value?: string | null) =>
  String(value || '').trim().toUpperCase();

/** True once the link has been acted on (or expired) — the review actions are then hidden. */
const isTerminalStatus = (status?: string | null) => {
  const s = normalizeStatus(status);
  return s === 'APPROVED' || s === 'REJECTED' || s === 'EXPIRED' || s === 'SUPERSEDED';
};

/** Maps the resource-level HTTP error from the link api fns to clear investor copy. */
const messageForError = (err: unknown): string => {
  const status = (err as InvestorLinkError | undefined)?.status;
  const raw = err instanceof Error ? err.message : '';
  if (status === 404) return 'This approval link is invalid or no longer exists. Please ask your distributor to send a new one.';
  if (status === 403) return 'This approval link does not belong to your account. Sign in with the email address the link was sent to.';
  if (status === 409 || status === 410) {
    return raw || 'This approval link has already been used or has expired. Please ask your distributor to send a new one.';
  }
  return raw || 'Something went wrong loading this approval link. Please try again.';
};

/** Turns a camelCase / snake_case key into a Title Case label. */
const labelize = (key: string) =>
  key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^./, (c) => c.toUpperCase());

/** Renders a scalar value for display (booleans → Yes/No, blanks → em dash). */
const renderScalar = (value: unknown): string => {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return String(value);
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === 'object' && !Array.isArray(value);

function DetailValue({ value }: { value: unknown }) {
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-sm text-slate-400">None provided.</span>;
    return (
      <div className="w-full space-y-2">
        {value.map((item, i) => (
          <div key={i} className="border-t border-slate-100 pt-2 first:border-t-0 first:pt-0">
            {isRecord(item) ? <ScalarRows obj={item} /> : <span className="text-sm font-medium text-slate-800">{renderScalar(item)}</span>}
          </div>
        ))}
      </div>
    );
  }
  if (isRecord(value)) return <ScalarRows obj={value} />;
  return <span className="text-right text-sm font-medium text-slate-800 break-words">{renderScalar(value)}</span>;
}

/** Read-only key/value rows for the scalar fields of an object. */
function ScalarRows({ obj }: { obj: Record<string, unknown> }) {
  const rows = Object.entries(obj);
  if (rows.length === 0) return null;
  return (
    <div className="overflow-hidden rounded-xl border border-slate-200">
      {rows.map(([k, v], i) => {
        const complex = typeof v === 'object' && v !== null;
        return (
          <div
            key={k}
            className={`px-4 py-3 ${i > 0 ? 'border-t border-slate-100' : ''} ${i % 2 === 1 ? 'bg-slate-50/60' : 'bg-white'}`}
          >
            {complex ? (
              <>
                <span className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">{labelize(k)}</span>
                <DetailValue value={v} />
              </>
            ) : (
              <div className="flex items-start justify-between gap-4">
                <span className="text-xs font-bold uppercase tracking-wider text-slate-500">{labelize(k)}</span>
                <DetailValue value={v} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Pretty-prints the frozen distributor-entered payload as a read-only review. Scalars render
 * as key/value rows; ARRAY values (e.g. `nominees`) render each element as a labeled sub-card
 * so the investor sees the full rich diff (nominees, tax, bank) before approving — instead of
 * the value being dropped.
 */
function ProfileDetails({ json }: { json?: string | null }) {
  const parsed = useMemo<Record<string, unknown> | null>(() => {
    if (!json) return null;
    try {
      const obj = JSON.parse(json);
      if (obj && typeof obj === 'object' && !Array.isArray(obj)) return obj as Record<string, unknown>;
    } catch {
      /* fall through to raw */
    }
    return null;
  }, [json]);

  if (!parsed) {
    return (
      <pre className="max-h-80 overflow-auto rounded-xl border border-slate-200 bg-slate-50 p-4 text-xs leading-relaxed text-slate-700 whitespace-pre-wrap break-words font-mono">
        {json || 'No details were provided.'}
      </pre>
    );
  }

  return (
    <div className="space-y-4">
      <ScalarRows obj={parsed} />
    </div>
  );
}

/** Centered branded shell mirroring the investor login left-panel deep-navy treatment. */
function ApprovalShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-[#F1F5F9] text-slate-900 font-sans">
      <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-6 py-3.5">
          <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-[#0B1B3E] text-sm font-bold text-white shadow-sm">P</div>
          <span className="text-base font-bold tracking-tight text-[#0B1B3E]">Platizio</span>
          <span className="rounded-full bg-blue-50 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-blue-600">Investor</span>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-6 py-10">{children}</main>
    </div>
  );
}

/**
 * OWED-3 / R3 / R7: the page the distributor approval email links to.
 *
 * The opaque token (`?token=…`) is the resource address. If the investor is not
 * signed in we send them through the existing email + PAN login flow with a
 * return-to back here. Once authenticated we GET /investor/link/{token}, render
 * the distributor name + the frozen Step-1 details + status/expiry in a luxe
 * card, and offer Approve (link by PAN), Approve & complete later (approve-and-skip,
 * distributor fills the form) and Reject. Expired / already-acted / not-owner
 * errors are surfaced with clear copy.
 */
export default function InvestorLinkApproval() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const token = (searchParams.get('token') || '').trim();

  const investor = useAppSelector(selectInvestorUser);
  const authLoading = useAppSelector(selectIsInvestorAuthLoading);
  const authenticated = Boolean(investor);

  const [review, setReview] = useState<InvestorLinkReviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [phase, setPhase] = useState<Phase>('review');
  const [acting, setActing] = useState<'approve' | 'skip' | 'reject' | null>(null);
  const [actionError, setActionError] = useState('');
  const [confirmReject, setConfirmReject] = useState(false);
  const [approvedPrefill, setApprovedPrefill] = useState<{ email?: string | null; pan?: string | null } | null>(null);

  /** Absolute return-to for login, so the investor lands back on this exact link. */
  const returnTo = useMemo(
    () => `${window.location.pathname}${window.location.search}`,
    [],
  );

  const goSignIn = (
    path: '/investor/login' | '/investor/signup',
    options?: { autoApprove?: boolean },
  ) => {
    // For the signup → approve re-sequence (P3) we append autoApprove=1 to the return-to
    // itself, so after the new session is set InvestorSignup reads the token from the
    // return-to URL and approves this exact link before navigating to onboarding.
    const dest = options?.autoApprove
      ? `${returnTo}${returnTo.includes('?') ? '&' : '?'}autoApprove=1`
      : returnTo;
    navigate(`${path}?returnTo=${encodeURIComponent(dest)}`);
  };

  const loadReview = useCallback(async () => {
    if (!token) {
      setLoading(false);
      setLoadError('This approval link is missing its token. Please open the link from your email again.');
      return;
    }
    setLoading(true);
    setLoadError('');
    try {
      const data = authenticated
        ? await reviewInvestorLink(token)
        : await reviewInvestorLinkPublic(token);
      setReview(data);
      const status = normalizeStatus(data.status);
      if (status === 'REJECTED') setPhase('rejected');
      if (status === 'APPROVED') {
        setApprovedPrefill({
          email: (data as InvestorLinkReviewResponse & { email?: string | null }).email,
          pan: (data as InvestorLinkReviewResponse & { pan?: string | null }).pan,
        });
        setPhase('approved');
      }
    } catch (err) {
      setLoadError(messageForError(err));
    } finally {
      setLoading(false);
    }
  }, [authenticated, token]);

  useEffect(() => {
    if (authLoading) return;
    void loadReview();
  }, [authenticated, authLoading, loadReview]);

  const distributorName = review?.distributorDisplayName || 'your distributor';

  const handleApprove = async () => {
    if (!token) return;
    setActing('approve');
    setActionError('');
    try {
      await approveInvestorLink(token);
      setPhase('approved');
    } catch (err) {
      setActionError(messageForError(err));
    } finally {
      setActing(null);
    }
  };

  const handleApproveAndSkip = async () => {
    if (!token) return;
    setActing('skip');
    setActionError('');
    try {
      await approveAndSkipInvestorLink(token);
      setPhase('skipped');
    } catch (err) {
      setActionError(messageForError(err));
    } finally {
      setActing(null);
    }
  };

  const handleReject = async () => {
    if (!token) return;
    setActing('reject');
    setActionError('');
    try {
      await rejectInvestorLink(token);
      setPhase('rejected');
    } catch (err) {
      setActionError(messageForError(err));
    } finally {
      setActing(null);
      setConfirmReject(false);
    }
  };

  // ── 1. Waiting on session restore ────────────────────────────────────────
  const handlePublicApprove = async () => {
    if (!token) return;
    setActing('approve');
    setActionError('');
    try {
      const data = await approveInvestorLinkPublic(token);
      setApprovedPrefill({
        email: data.email ?? (review as (InvestorLinkReviewResponse & { email?: string | null }) | null)?.email,
        pan: data.pan ?? (review as (InvestorLinkReviewResponse & { pan?: string | null }) | null)?.pan,
      });
      setPhase('approved');
    } catch (err) {
      setActionError(messageForError(err));
    } finally {
      setActing(null);
    }
  };

  const handlePublicReject = async () => {
    if (!token) return;
    setActing('reject');
    setActionError('');
    try {
      await rejectInvestorLinkPublic(token);
      setPhase('rejected');
    } catch (err) {
      setActionError(messageForError(err));
    } finally {
      setActing(null);
      setConfirmReject(false);
    }
  };

  const signupAfterApprovalPath = () => {
    const params = new URLSearchParams({ invited: '1', returnTo: '/investor/kyc' });
    if (approvedPrefill?.email) params.set('email', approvedPrefill.email);
    if (approvedPrefill?.pan) params.set('pan', approvedPrefill.pan);
    return `/investor/signup?${params.toString()}`;
  };

  const loginAfterApprovalPath = () =>
    `/investor/login?returnTo=${encodeURIComponent('/investor/kyc')}`;

  if (authLoading) {
    return (
      <ApprovalShell>
        <div className="flex min-h-[40vh] items-center justify-center">
          <Loader2 className="h-7 w-7 animate-spin text-[#0B1B3E]" />
        </div>
      </ApprovalShell>
    );
  }

  // ── 2. Not signed in → route through investor login, return-to here ─
  if (loading) {
    return (
      <ApprovalShell>
        <div className="flex min-h-[40vh] flex-col items-center justify-center">
          <Loader2 className="h-7 w-7 animate-spin text-[#0B1B3E]" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading the approval request...</p>
        </div>
      </ApprovalShell>
    );
  }

  if (loadError) {
    return (
      <ApprovalShell>
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          className="mx-auto max-w-md rounded-2xl border border-red-100 bg-white p-8 text-center shadow-sm"
        >
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-50">
            <XCircle className="h-6 w-6 text-red-500" />
          </div>
          <h1 className="text-lg font-semibold text-slate-800">We couldn't open this link</h1>
          <p className="mt-2 text-sm text-slate-500">{loadError}</p>
          <button
            type="button"
            onClick={() => void loadReview()}
            className="mt-6 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
          >
            Try again
          </button>
        </motion.div>
      </ApprovalShell>
    );
  }

  if (!authenticated) {
    if (phase === 'approved') {
      return (
        <ApprovalShell>
          <motion.div
            initial={{ opacity: 0, scale: 0.98 }}
            animate={{ opacity: 1, scale: 1 }}
            className="mx-auto max-w-md rounded-2xl border border-emerald-200 bg-emerald-50 p-8 text-center"
          >
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100">
              <CheckCircle2 className="h-7 w-7 text-emerald-600" />
            </div>
            <h1 className="text-lg font-semibold text-slate-800">Onboarding approved</h1>
            <p className="mt-2 text-sm text-slate-600">
              You're linked to {distributorName}. Create your investor account next, then complete KYC.
            </p>
            <button
              type="button"
              onClick={() => navigate(signupAfterApprovalPath())}
              className="mt-6 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white shadow-lg transition-colors hover:bg-[#1A3066]"
            >
              <UserPlus className="h-4 w-4" /> Create investor account
            </button>
            <button
              type="button"
              onClick={() => navigate(loginAfterApprovalPath())}
              className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl border border-emerald-200 bg-white/70 px-6 py-3 text-sm font-semibold text-slate-700 transition-colors hover:bg-white"
            >
              <Mail className="h-4 w-4" /> I already have an account
            </button>
          </motion.div>
        </ApprovalShell>
      );
    }

    if (phase === 'rejected') {
      return (
        <ApprovalShell>
          <motion.div
            initial={{ opacity: 0, scale: 0.98 }}
            animate={{ opacity: 1, scale: 1 }}
            className="mx-auto max-w-md rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm"
          >
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-slate-100">
              <XCircle className="h-7 w-7 text-slate-500" />
            </div>
            <h1 className="text-lg font-semibold text-slate-800">Link request declined</h1>
            <p className="mt-2 text-sm text-slate-500">
              You declined the onboarding request from {distributorName}. They will not be linked to your account.
            </p>
          </motion.div>
        </ApprovalShell>
      );
    }

    return (
      <ApprovalShell>
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          className="mx-auto max-w-2xl"
        >
          <div className="mb-6">
            <h1 className="text-2xl font-semibold text-slate-800">Approve your onboarding</h1>
            <p className="mt-1 text-sm text-slate-500">
              {distributorName} entered these details for your account. Review them before approving.
            </p>
          </div>

          <div className="mb-6 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h2 className="mb-3 text-sm font-semibold text-slate-800">Details entered by {distributorName}</h2>
            <ProfileDetails json={review?.profileDetailsJson} />
            {review?.contentSha256 && (
              <p className="mt-3 break-all font-mono text-[11px] text-slate-400">
                Revision hash: {review.contentSha256}
              </p>
            )}
          </div>

          {actionError && (
            <div className="mb-5 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
              <p className="text-sm text-red-700">{actionError}</p>
            </div>
          )}

          <button
            type="button"
            onClick={() => void handlePublicApprove()}
            disabled={acting !== null}
            className="mt-6 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white shadow-lg transition-all duration-150 hover:-translate-y-0.5 hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:translate-y-0"
          >
            {acting === 'approve'
              ? <><Loader2 className="h-4 w-4 animate-spin" /> Approving...</>
              : <><ShieldCheck className="h-4 w-4" /> Approve onboarding</>}
          </button>
          <p className="mt-2 text-center text-[11px] text-slate-400">
            After approval you'll create your investor account and complete KYC.
          </p>

          <button
            type="button"
            onClick={() => goSignIn('/investor/login')}
            className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 px-6 py-3 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50"
          >
            <Mail className="h-4 w-4" /> Sign in with email + PAN
          </button>
          {!confirmReject ? (
            <button
              type="button"
              onClick={() => { setConfirmReject(true); setActionError(''); }}
              disabled={acting !== null}
              className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl px-6 py-2.5 text-sm font-semibold text-red-600 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              <ThumbsDown className="h-4 w-4" /> Reject this request
            </button>
          ) : (
            <div className="mt-4 rounded-xl border border-red-100 bg-red-50 p-4">
              <p className="text-sm font-medium text-red-800">Decline this onboarding request?</p>
              <div className="mt-3 flex gap-2">
                <button
                  type="button"
                  onClick={() => void handlePublicReject()}
                  disabled={acting !== null}
                  className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-red-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {acting === 'reject' ? <><Loader2 className="h-4 w-4 animate-spin" /> Rejecting...</> : 'Yes, reject'}
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmReject(false)}
                  disabled={acting !== null}
                  className="flex-1 rounded-lg border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </motion.div>
      </ApprovalShell>
    );
  }

  // ── 3. Authenticated: loading the review ─────────────────────────────────
  if (loading) {
    return (
      <ApprovalShell>
        <div className="flex min-h-[40vh] flex-col items-center justify-center">
          <Loader2 className="h-7 w-7 animate-spin text-[#0B1B3E]" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading the approval request…</p>
        </div>
      </ApprovalShell>
    );
  }

  // ── 4. Load failed (bad/expired/not-mine token) ──────────────────────────
  if (loadError) {
    return (
      <ApprovalShell>
        <motion.div
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          className="mx-auto max-w-md rounded-2xl border border-red-100 bg-white p-8 text-center shadow-sm"
        >
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-50">
            <XCircle className="h-6 w-6 text-red-500" />
          </div>
          <h1 className="text-lg font-semibold text-slate-800">We couldn't open this link</h1>
          <p className="mt-2 text-sm text-slate-500">{loadError}</p>
          <div className="mt-6 flex flex-col gap-2">
            <button
              type="button"
              onClick={() => void loadReview()}
              className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
            >
              Try again
            </button>
            <button
              type="button"
              onClick={() => navigate('/investor/dashboard')}
              className="flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 px-6 py-3 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50"
            >
              <ArrowLeft className="h-4 w-4" /> Go to my dashboard
            </button>
          </div>
        </motion.div>
      </ApprovalShell>
    );
  }

  // ── 5. Success states ────────────────────────────────────────────────────
  if (phase === 'approved' || phase === 'skipped') {
    const skipped = phase === 'skipped';
    return (
      <ApprovalShell>
        <motion.div
          initial={{ opacity: 0, scale: 0.98 }}
          animate={{ opacity: 1, scale: 1 }}
          className="mx-auto max-w-md rounded-2xl border border-emerald-200 bg-emerald-50 p-8 text-center"
        >
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100">
            <CheckCircle2 className="h-7 w-7 text-emerald-600" />
          </div>
          <h1 className="text-lg font-semibold text-slate-800">
            You're now linked to {distributorName}
          </h1>
          <p className="mt-2 text-sm text-slate-600">
            {skipped
              ? `Thanks for approving. You've asked ${distributorName} to complete your profile details — they'll fill them in and you'll be notified to approve the final details.`
              : 'Thanks for approving. Complete KYC next to finish setting up your account.'}
          </p>
          <button
            type="button"
            onClick={() => navigate(skipped ? '/investor/dashboard' : '/investor/kyc')}
            className="mt-6 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white shadow-lg transition-all duration-150 hover:-translate-y-0.5 hover:bg-[#1A3066]"
          >
            {skipped ? 'Go to my dashboard' : 'Continue to KYC'} <ArrowRight className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={() => navigate('/investor/dashboard')}
            className="mt-3 text-xs font-semibold text-slate-500 hover:underline"
          >
            Go to my dashboard
          </button>
        </motion.div>
      </ApprovalShell>
    );
  }

  if (phase === 'rejected') {
    return (
      <ApprovalShell>
        <motion.div
          initial={{ opacity: 0, scale: 0.98 }}
          animate={{ opacity: 1, scale: 1 }}
          className="mx-auto max-w-md rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm"
        >
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-slate-100">
            <ThumbsDown className="h-7 w-7 text-slate-500" />
          </div>
          <h1 className="text-lg font-semibold text-slate-800">Link request declined</h1>
          <p className="mt-2 text-sm text-slate-500">
            You've declined the request from {distributorName}. They will not be linked to your account.
            If this was a mistake, ask your distributor to send a new request.
          </p>
          <button
            type="button"
            onClick={() => navigate('/investor/dashboard')}
            className="mt-6 flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 px-6 py-3 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50"
          >
            Go to my dashboard
          </button>
        </motion.div>
      </ApprovalShell>
    );
  }

  // ── 6. Review + actions ──────────────────────────────────────────────────
  const status = normalizeStatus(review?.status);
  const expired = status === 'EXPIRED';
  const alreadyActed = isTerminalStatus(review?.status);
  const expiresAt = review?.expiresAt ? new Date(review.expiresAt) : null;
  const expiresLabel =
    expiresAt && !Number.isNaN(expiresAt.getTime())
      ? expiresAt.toLocaleString(undefined, {
          dateStyle: 'medium',
          timeStyle: 'short',
        })
      : null;

  return (
    <ApprovalShell>
      <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }}>
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-slate-800">Review your distributor link</h1>
          <p className="mt-1 text-sm text-slate-500">
            <span className="font-semibold text-slate-700">{distributorName}</span> has asked to link your
            investor account. Review the details they entered, then approve or decline.
          </p>
        </div>

        {/* Status / expiry pill */}
        <div className="mb-6 flex flex-wrap items-center gap-2">
          {expired ? (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-red-200 bg-red-50 px-3 py-1 text-xs font-semibold text-red-700">
              <Clock className="h-3.5 w-3.5" /> Link expired
            </span>
          ) : alreadyActed ? (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-600">
              <Clock className="h-3.5 w-3.5" /> {status === 'APPROVED' ? 'Already approved' : status}
            </span>
          ) : (
            <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-200 bg-amber-50 px-3 py-1 text-xs font-semibold text-amber-700">
              <Clock className="h-3.5 w-3.5" /> Awaiting your approval
            </span>
          )}
          {expiresLabel && !alreadyActed && (
            <span className="text-xs text-slate-400">Expires {expiresLabel}</span>
          )}
          {typeof review?.revisionNo === 'number' && (
            <span className="text-xs text-slate-400">Revision #{review.revisionNo}</span>
          )}
        </div>

        {/* Distributor-entered details */}
        <div className="mb-6 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
          <h2 className="mb-3 text-sm font-semibold text-slate-800">Details entered by {distributorName}</h2>
          <ProfileDetails json={review?.profileDetailsJson} />
          {review?.contentSha256 && (
            <p className="mt-3 break-all font-mono text-[11px] text-slate-400">
              Revision hash: {review.contentSha256}
            </p>
          )}
        </div>

        {/* Action error */}
        {actionError && (
          <div className="mb-5 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
            <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
            <p className="text-sm text-red-700">{actionError}</p>
          </div>
        )}

        {/* Expired / already-acted: no actions, just guidance */}
        {expired || alreadyActed ? (
          <div className="rounded-2xl border border-slate-200 bg-white p-6 text-center shadow-sm">
            <p className="text-sm text-slate-600">
              {expired
                ? 'This approval link has expired. Please ask your distributor to send a new one.'
                : 'This request has already been actioned. No further action is needed.'}
            </p>
            <button
              type="button"
              onClick={() => navigate('/investor/dashboard')}
              className="mt-4 inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 px-6 py-2.5 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50"
            >
              Go to my dashboard
            </button>
          </div>
        ) : (
          <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <button
              type="button"
              onClick={() => void handleApprove()}
              disabled={acting !== null}
              className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white shadow-lg transition-all duration-150 hover:-translate-y-0.5 hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:translate-y-0"
            >
              {acting === 'approve' ? (
                <><Loader2 className="h-4 w-4 animate-spin" /> Approving…</>
              ) : (
                <><ShieldCheck className="h-4 w-4" /> Approve &amp; link {distributorName}</>
              )}
            </button>

            <button
              type="button"
              onClick={() => void handleApproveAndSkip()}
              disabled={acting !== null}
              className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 px-6 py-3 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {acting === 'skip' ? (
                <><Loader2 className="h-4 w-4 animate-spin" /> Approving…</>
              ) : (
                <>Approve &amp; complete later</>
              )}
            </button>
            <p className="mt-2 text-center text-[11px] text-slate-400">
              Approve now and ask {distributorName} to fill in your profile details. You'll review the final
              details before they're submitted.
            </p>

            <div className="my-5 border-t border-slate-100" />

            {!confirmReject ? (
              <button
                type="button"
                onClick={() => { setConfirmReject(true); setActionError(''); }}
                disabled={acting !== null}
                className="flex w-full items-center justify-center gap-2 rounded-xl px-6 py-2.5 text-sm font-semibold text-red-600 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <ThumbsDown className="h-4 w-4" /> Reject this request
              </button>
            ) : (
              <div className="rounded-xl border border-red-100 bg-red-50 p-4">
                <p className="text-sm font-medium text-red-800">
                  Decline the link from {distributorName}?
                </p>
                <p className="mt-1 text-xs text-red-600">
                  They will not be linked to your account. You can ask them to send a new request later.
                </p>
                <div className="mt-3 flex gap-2">
                  <button
                    type="button"
                    onClick={() => void handleReject()}
                    disabled={acting !== null}
                    className="flex flex-1 items-center justify-center gap-2 rounded-lg bg-red-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {acting === 'reject' ? (
                      <><Loader2 className="h-4 w-4 animate-spin" /> Rejecting…</>
                    ) : (
                      <>Yes, reject</>
                    )}
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmReject(false)}
                    disabled={acting !== null}
                    className="flex-1 rounded-lg border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    Cancel
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </motion.div>
    </ApprovalShell>
  );
}
