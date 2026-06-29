import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Loader2, AlertCircle, CheckCircle2, ShieldCheck, ShieldAlert, FileCheck2,
  CheckCheck, XCircle, KeyRound, RefreshCw, ClipboardCheck,
} from 'lucide-react';
import { apiFetch } from '../config/api';

/**
 * Investor approvals (investor.md M4 / R9).
 *
 * A distributor proposes a profile change; the investor reviews the exact snapshot
 * and approves it via OTP + explicit consent (or rejects it). Wires the
 * investor-session endpoints:
 *   GET  /investor/profile/pending-changes                  → the pending change (if any)
 *   POST /investor/profile/pending-changes/request-otp      → send OTP to maskedDestination
 *   POST /investor/profile/pending-changes/approve          → {otp, consentAccepted:true}
 *   POST /investor/profile/pending-changes/reject           → reject the proposal
 *
 * Mirrors the navy/Inter design system and the loading/error/empty discipline of
 * InvestorInvest / InvestorKyc. The snapshot is rendered exactly as the backend
 * returns it (snapshotJson is a raw JSON string we parse defensively).
 */

interface PendingChange {
  pending: boolean;
  id?: string;
  changeType?: string;
  status?: string;
  snapshotJson?: string;
  snapshotSha256?: string;
  consentText?: string;
  maskedDestination?: string;
}

type SnapshotValue = string | number | boolean | null | undefined;
type SnapshotObject = Record<string, unknown>;

const prettyKey = (k: string) =>
  k
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (c) => c.toUpperCase());

const prettyChangeType = (s?: string) =>
  s ? s.replace(/[_-]+/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase()) : 'Profile change';

const isPlainObject = (v: unknown): v is SnapshotObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const renderValue = (v: unknown): string => {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  if (Array.isArray(v)) return v.length ? v.map((x) => String(x)).join(', ') : '—';
  if (isPlainObject(v)) return JSON.stringify(v);
  return String(v as SnapshotValue);
};

/** Flatten a parsed snapshot into label/value rows, unwrapping a nested "profile" object. */
function toRows(parsed: unknown): { key: string; value: string }[] {
  if (!isPlainObject(parsed)) return [];
  const rows: { key: string; value: string }[] = [];
  for (const [k, val] of Object.entries(parsed)) {
    if (k === 'profile' && isPlainObject(val)) {
      for (const [pk, pv] of Object.entries(val)) rows.push({ key: prettyKey(pk), value: renderValue(pv) });
      continue;
    }
    rows.push({ key: prettyKey(k), value: renderValue(val) });
  }
  return rows;
}

export default function InvestorApprovals() {
  const navigate = useNavigate();
  const [data, setData] = useState<PendingChange | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // OTP / consent flow
  const [otpSent, setOtpSent] = useState(false);
  const [sendingOtp, setSendingOtp] = useState(false);
  const [otp, setOtp] = useState('');
  const [consent, setConsent] = useState(false);
  const [approving, setApproving] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [actionError, setActionError] = useState('');
  const [approved, setApproved] = useState(false);
  const [rejected, setRejected] = useState(false);

  const resetFlow = () => {
    setOtpSent(false);
    setOtp('');
    setConsent(false);
    setActionError('');
    setApproved(false);
    setRejected(false);
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/profile/pending-changes');
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || `Unable to load pending approvals (${res.status}).`);
      }
      setData((body as PendingChange) || { pending: false });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load pending approvals.');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const requestOtp = async () => {
    setSendingOtp(true);
    setActionError('');
    try {
      const res = await apiFetch('/investor/profile/pending-changes/request-otp', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not send the OTP.');
      setOtpSent(true);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not send the OTP.');
    } finally {
      setSendingOtp(false);
    }
  };

  const approve = async () => {
    if (otp.trim().length < 6 || !consent) return;
    setApproving(true);
    setActionError('');
    try {
      const res = await apiFetch('/investor/profile/pending-changes/approve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ otp: otp.trim(), consentAccepted: true }),
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not approve the change.');
      setApproved(true);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not approve the change.');
    } finally {
      setApproving(false);
    }
  };

  const reject = async () => {
    setRejecting(true);
    setActionError('');
    try {
      const res = await apiFetch('/investor/profile/pending-changes/reject', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not reject the change.');
      setRejected(true);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not reject the change.');
    } finally {
      setRejecting(false);
    }
  };

  // Parse snapshotJson defensively — it is a RAW JSON STRING.
  const { rows, parseFailed } = useMemo(() => {
    const raw = data?.snapshotJson;
    if (!raw) return { rows: [] as { key: string; value: string }[], parseFailed: false };
    try {
      return { rows: toRows(JSON.parse(raw)), parseFailed: false };
    } catch {
      return { rows: [] as { key: string; value: string }[], parseFailed: true };
    }
  }, [data?.snapshotJson]);

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading pending approvals…</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <div className="flex items-start gap-2.5">
            <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
            <div>
              <p className="font-semibold">Approvals unavailable</p>
              <p className="mt-1 text-sm">{error}</p>
              <button onClick={() => void load()} className="mt-4 text-sm font-semibold text-red-600 hover:underline">
                Try again
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const pending = data?.pending === true;

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-8">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Approvals</h1>
          <p className="mt-1 text-sm text-slate-500">Review and approve profile changes proposed on your behalf.</p>
        </div>
        <button
          onClick={() => void load()}
          className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" /> Refresh
        </button>
      </div>

      {/* Empty state */}
      {!pending && !approved && !rejected && (
        <div className="rounded-2xl border border-slate-100 bg-white p-10 text-center shadow-sm">
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-100">
            <CheckCheck className="h-7 w-7 text-emerald-600" />
          </div>
          <p className="text-sm font-semibold text-slate-800">No pending approvals</p>
          <p className="mt-1 text-sm text-slate-500">You're all caught up.</p>
        </div>
      )}

      {/* Just approved / rejected (pending is now false after reload) */}
      {!pending && (approved || rejected) && (
        <div className="rounded-2xl border border-slate-100 bg-white p-10 text-center shadow-sm">
          <div className={`mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full ${approved ? 'bg-emerald-100' : 'bg-slate-100'}`}>
            {approved ? <CheckCircle2 className="h-7 w-7 text-emerald-600" /> : <XCircle className="h-7 w-7 text-slate-500" />}
          </div>
          <p className="text-sm font-semibold text-slate-800">
            {approved ? 'Change approved' : 'Change rejected'}
          </p>
          <p className="mt-1 text-sm text-slate-500">
            {approved
              ? 'Your approval has been recorded and the change has been applied.'
              : 'The proposed change has been declined. No changes were made.'}
          </p>
          <button
            onClick={() => navigate('/investor/dashboard')}
            className="mt-5 inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50"
          >
            Back to dashboard
          </button>
        </div>
      )}

      {/* Pending change */}
      {pending && (
        <>
          {/* Proposed change card */}
          <div className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm">
            <div className="mb-4 flex items-center gap-2">
              <ClipboardCheck className="h-4 w-4 text-slate-500" />
              <h2 className="text-sm font-semibold text-slate-700">Proposed change</h2>
              {data?.status && (
                <span className="ml-auto inline-flex items-center rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-semibold text-amber-700 ring-1 ring-amber-200">
                  {prettyChangeType(data.status)}
                </span>
              )}
            </div>

            <div className="mb-5 flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-400">Change type</span>
              <span className="text-sm font-semibold text-slate-700">{prettyChangeType(data?.changeType)}</span>
            </div>

            {parseFailed ? (
              <div className="flex items-start gap-2.5 rounded-xl border border-amber-100 bg-amber-50 px-4 py-3">
                <ShieldAlert className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600" />
                <p className="text-sm text-amber-800">We couldn't read the change details. Please contact your distributor before approving.</p>
              </div>
            ) : rows.length > 0 ? (
              <dl className="divide-y divide-slate-100 overflow-hidden rounded-xl border border-slate-100">
                {rows.map((r) => (
                  <div key={r.key} className="flex items-start justify-between gap-4 px-4 py-3">
                    <dt className="text-xs font-bold uppercase tracking-wider text-slate-400">{r.key}</dt>
                    <dd className="text-right text-sm font-medium text-slate-700 break-words">{r.value}</dd>
                  </div>
                ))}
              </dl>
            ) : (
              <p className="rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-500">No change details provided.</p>
            )}

            {data?.consentText && (
              <div className="mt-5 flex items-start gap-2.5 rounded-xl border border-blue-100 bg-blue-50/60 px-4 py-3">
                <FileCheck2 className="mt-0.5 h-4 w-4 flex-shrink-0 text-blue-600" />
                <p className="text-sm text-blue-900">{data.consentText}</p>
              </div>
            )}

            {data?.snapshotSha256 && (
              <p className="mt-3 font-mono text-[10px] text-slate-400 break-all">SHA-256: {data.snapshotSha256}</p>
            )}
          </div>

          {actionError && (
            <div className="flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
              <p className="text-sm text-red-700">{actionError}</p>
            </div>
          )}

          {/* Approve / reject */}
          <div className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm">
            <div className="mb-4 flex items-center gap-2">
              <ShieldCheck className="h-4 w-4 text-slate-500" />
              <h2 className="text-sm font-semibold text-slate-700">Verify & approve</h2>
            </div>

            {!otpSent ? (
              <>
                <p className="mb-4 text-sm text-slate-600">
                  We'll send a one-time passcode to{' '}
                  <span className="font-semibold text-slate-800">{data?.maskedDestination || 'your registered contact'}</span>{' '}
                  to confirm it's you.
                </p>
                <button
                  onClick={() => void requestOtp()}
                  disabled={sendingOtp}
                  className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {sendingOtp ? (<><Loader2 className="h-4 w-4 animate-spin" /> Sending OTP…</>) : (<><KeyRound className="h-4 w-4" /> Send OTP to {data?.maskedDestination || 'me'}</>)}
                </button>
              </>
            ) : (
              <div className="space-y-4">
                <div>
                  <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">
                    Enter OTP
                  </label>
                  <input
                    value={otp}
                    onChange={(e) => { setOtp(e.target.value.replace(/\s/g, '').slice(0, 6)); setActionError(''); }}
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={6}
                    placeholder="6-character code"
                    className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-center font-mono text-lg tracking-[0.4em] outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                  />
                  <button
                    onClick={() => void requestOtp()}
                    disabled={sendingOtp}
                    className="mt-2 text-xs font-semibold text-blue-600 hover:underline disabled:opacity-60"
                  >
                    {sendingOtp ? 'Resending…' : 'Resend OTP'}
                  </button>
                </div>

                <label className="flex cursor-pointer items-start gap-2.5 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
                  <input
                    type="checkbox"
                    checked={consent}
                    onChange={(e) => { setConsent(e.target.checked); setActionError(''); }}
                    className="mt-0.5 h-4 w-4 flex-shrink-0 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                  />
                  <span className="text-sm text-slate-700">I have reviewed and approve this change.</span>
                </label>

                <div className="flex flex-col gap-3 sm:flex-row">
                  <button
                    onClick={() => void approve()}
                    disabled={approving || rejecting || otp.trim().length < 6 || !consent}
                    className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {approving ? (<><Loader2 className="h-4 w-4 animate-spin" /> Approving…</>) : (<><CheckCircle2 className="h-4 w-4" /> Approve change</>)}
                  </button>
                  <button
                    onClick={() => void reject()}
                    disabled={approving || rejecting}
                    className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-red-200 py-3 text-sm font-semibold text-red-600 transition-all hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {rejecting ? (<><Loader2 className="h-4 w-4 animate-spin" /> Rejecting…</>) : (<><XCircle className="h-4 w-4" /> Reject</>)}
                  </button>
                </div>

                <p className="text-center text-[11px] text-slate-400">Dev environment: the master code 000000 works.</p>
              </div>
            )}

            {!otpSent && (
              <button
                onClick={() => void reject()}
                disabled={rejecting}
                className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl border border-red-200 py-3 text-sm font-semibold text-red-600 transition-all hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {rejecting ? (<><Loader2 className="h-4 w-4 animate-spin" /> Rejecting…</>) : (<><XCircle className="h-4 w-4" /> Reject this change</>)}
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
