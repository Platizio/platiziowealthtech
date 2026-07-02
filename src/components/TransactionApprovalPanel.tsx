import React, { useEffect, useMemo, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  AlertCircle, CheckCircle2, ShieldCheck, Loader2, RefreshCw,
  Clock, XCircle, ExternalLink, ChevronDown, ChevronRight,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import { prettySipFrequency } from '../utils/sipDisplay';

/**
 * The 2FA approval state machine, mirrored from the backend
 * {@code TransactionApprovalStatus} lifecycle. PENDING / CHALLENGE_SENT /
 * APPROVED are "live"; CONSUMED is a terminal success; EXPIRED / REJECTED /
 * SUPERSEDED are terminal-fail (only a brand-new challenge re-authorizes).
 */
export type ChallengeStatus =
  | 'PENDING'
  | 'CHALLENGE_SENT'
  | 'APPROVED'
  | 'CONSUMED'
  | 'SUBMITTED'
  | 'EXPIRED'
  | 'REJECTED'
  | 'SUPERSEDED';

/** Where the investor should go after an approved+consumed challenge. */
export interface NextAction {
  /** e.g. 'REDIRECT' | 'SUBMITTED' | 'PAYMENT' | 'MANDATE' — informational only. */
  type?: string;
  /** A payment / mandate URL the investor must open to finish. */
  url?: string;
  label?: string;
  message?: string;
}

/**
 * A frozen, hashed transaction-approval challenge as returned by
 * {@code GET /investor/approvals/{id}}. {@code snapshotJson} is the immutable
 * snapshot the investor is approving; {@code consentRenderedText} is the exact
 * consent copy stored as evidence on the challenge.
 */
export interface ApprovalChallenge {
  id: string;
  transactionId?: string;
  transactionType?: string;     // PURCHASE | SIP | REDEMPTION
  status: ChallengeStatus | string;
  snapshotJson?: unknown;
  snapshotSha256?: string;
  consentTemplateVersion?: string;
  consentRenderedText?: string;
  channel?: string;             // EMAIL | MOBILE
  maskedDestination?: string;
  expiresAt?: string;
  deliveryAttempts?: number;
  nextAction?: NextAction | null;
}

const Spinner = () => (
  <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
  </svg>
);

const normalizeStatus = (value?: string) => String(value || '').trim().toUpperCase();

const isSuccess = (status?: string) => {
  const s = normalizeStatus(status);
  return s === 'CONSUMED' || s === 'SUBMITTED' || s === 'APPROVED';
};

const isStale = (status?: string) => {
  const s = normalizeStatus(status);
  return s === 'EXPIRED' || s === 'SUPERSEDED' || s === 'REJECTED';
};

/** Pretty-prints the frozen snapshot as a read-only JSON tree (mirrors review page). */
function ReadOnlySnapshot({ value }: { value: unknown }) {
  const text = useMemo(() => {
    try {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value;
      return JSON.stringify(parsed, null, 2);
    } catch {
      return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    }
  }, [value]);

  return (
    <pre className="max-h-80 overflow-auto rounded-xl border border-slate-200 bg-slate-50 p-4 text-xs leading-relaxed text-slate-700 whitespace-pre-wrap break-words font-mono">
      {text}
    </pre>
  );
}

interface TransactionApprovalPanelProps {
  challenge: ApprovalChallenge;
  /** Called when the challenge reaches a success state (CONSUMED/SUBMITTED). */
  onApproved?: (challenge: ApprovalChallenge) => void;
  /** Called to refresh the parent's copy after any state change. */
  onChallengeUpdated?: (challenge: ApprovalChallenge) => void;
  /** Optional back affordance (e.g. return to the approval list). */
  onBack?: () => void;
}

type FlowStep = 'consent' | 'verify' | 'done';

/**
 * Phase-2 transaction approval (2FA). Renders the FROZEN snapshot read-only +
 * the rendered consent text, an UNTICKED consent checkbox that gates a disabled
 * "Send code", then a 6-box OTP and "Approve":
 *
 *   POST /investor/approvals/{id}/otp/request    (PENDING → CHALLENGE_SENT)
 *   POST /investor/approvals/{id}/approve {consentAccepted:true, code}
 *
 * On approve success the backend marks the challenge CONSUMED and returns a
 * {@code nextAction}; if it carries a payment / mandate url we redirect there,
 * otherwise we show "Submitted". Terminal-fail states (expired / superseded /
 * rejected) offer a fresh re-request. The OTP handlers mirror InvestorLoginPage
 * exactly; the consent checkbox mirrors InvestorOnboardingReview.
 */
export default function TransactionApprovalPanel({
  challenge: initialChallenge,
  onApproved,
  onChallengeUpdated,
  onBack,
}: TransactionApprovalPanelProps) {
  const [challenge, setChallenge] = useState<ApprovalChallenge>(initialChallenge);

  const [consentAccepted, setConsentAccepted] = useState(false);
  // Compliance: a SEPARATE, explicit consent captured AT THE TIME OF OTP ENTRY.
  // Starts UNCHECKED and gates the Approve/verify button alongside a complete OTP.
  const [verifyConsent, setVerifyConsent] = useState(false);
  const [otpDigits, setOtpDigits] = useState<string[]>(Array(6).fill(''));
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const [approving, setApproving] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [devCode, setDevCode] = useState('');
  // The structured detail card is primary; the raw JSON stays available but collapsed.
  const [showRawSnapshot, setShowRawSnapshot] = useState(false);
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  const status = normalizeStatus(challenge.status);
  const success = isSuccess(status);
  const stale = isStale(status);
  const challengeSent = status === 'CHALLENGE_SENT';

  // Derive the visible step from status + local flow. A live challenge that has
  // already been sent jumps straight to OTP entry on (re)load.
  const step: FlowStep = success
    ? 'done'
    : challengeSent
      ? 'verify'
      : 'consent';

  useEffect(() => {
    setChallenge(initialChallenge);
    setConsentAccepted(false);
    setVerifyConsent(false);
    setOtpDigits(Array(6).fill(''));
    setError('');
    setCountdown(0);
    setDevCode('');
  }, [initialChallenge]);

  useEffect(() => {
    if (step === 'verify') {
      setTimeout(() => otpRefs.current[0]?.focus(), 120);
    }
  }, [step]);

  useEffect(() => {
    if (countdown <= 0) return;
    const t = setTimeout(() => setCountdown((c) => c - 1), 1000);
    return () => clearTimeout(t);
  }, [countdown]);

  const patchChallenge = (next: ApprovalChallenge) => {
    setChallenge(next);
    onChallengeUpdated?.(next);
  };

  const handleSendCode = async () => {
    if (!consentAccepted || sending) return;
    setSending(true);
    setError('');
    try {
      const res = await apiFetch(`/investor/approvals/${challenge.id}/otp/request`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error(data?.message || 'Could not send the verification code. Please try again.');
      }
      setOtpDigits(Array(6).fill(''));
      setDevCode(typeof data?.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data?.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      patchChallenge({ ...challenge, status: 'CHALLENGE_SENT', ...(data?.challenge || {}) });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not send the verification code. Please try again.');
    } finally {
      setSending(false);
    }
  };

  const handleResend = async () => {
    setError('');
    try {
      const res = await apiFetch(`/investor/approvals/${challenge.id}/otp/request`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error(data?.message || 'Could not resend the code. Please try again.');
      }
      setOtpDigits(Array(6).fill(''));
      setDevCode(typeof data?.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data?.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not resend the code. Please try again.');
    }
  };

  const handleDigitChange = (i: number, val: string) => {
    if (!/^\d*$/.test(val)) return;
    const next = [...otpDigits];
    next[i] = val.slice(-1);
    setOtpDigits(next);
    setError('');
    if (val && i < 5) otpRefs.current[i + 1]?.focus();
  };

  const handleDigitKeyDown = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !otpDigits[i] && i > 0) otpRefs.current[i - 1]?.focus();
    if (e.key === 'Enter' && otpDigits.every((d) => d)) void handleApprove();
  };

  const handleDigitPaste = (e: React.ClipboardEvent) => {
    const paste = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (paste.length === 6) {
      setOtpDigits(paste.split(''));
      setTimeout(() => otpRefs.current[5]?.focus(), 0);
    }
    e.preventDefault();
  };

  const followNextAction = (next: ApprovalChallenge) => {
    const action = next.nextAction;
    if (action?.url) {
      // Real Cybrilla payment / mandate redirect once 2FA + consent passed.
      window.location.assign(action.url);
    }
  };

  const handleApprove = async () => {
    const entered = otpDigits.join('');
    if (entered.length < 6) {
      setError('Please enter the complete 6-digit code.');
      return;
    }
    // Compliance: the explicit consent must be ticked AT OTP ENTRY before approving.
    if (!verifyConsent) {
      setError('Please tick the authorisation consent to approve this transaction.');
      return;
    }
    setApproving(true);
    setError('');
    try {
      const res = await apiFetch(`/investor/approvals/${challenge.id}/approve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Only send consentAccepted:true because the user ticked it at OTP entry.
        body: JSON.stringify({ consentAccepted: verifyConsent, code: entered }),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error(data?.message || 'Incorrect or expired code. Please try again.');
      }
      const next: ApprovalChallenge = {
        ...challenge,
        status: 'CONSUMED',
        ...(data && typeof data === 'object' ? data : {}),
      };
      patchChallenge(next);
      onApproved?.(next);
      followNextAction(next);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Incorrect code. Please check and try again.');
      setOtpDigits(Array(6).fill(''));
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } finally {
      setApproving(false);
    }
  };

  const reRequest = () => {
    // A terminal-fail challenge can only be re-authorized by a brand-new one.
    // Reset to the consent step so the investor restarts cleanly; the parent
    // re-fetches the live challenge when this one is superseded.
    setConsentAccepted(false);
    setVerifyConsent(false);
    setOtpDigits(Array(6).fill(''));
    setError('');
    setCountdown(0);
    setDevCode('');
    patchChallenge({ ...challenge, status: 'PENDING' });
  };

  const typeLabel = String(challenge.transactionType || 'transaction').replace(/_/g, ' ');

  // Pull the frozen snapshot apart into a structured, labelled detail so the
  // consent is INFORMED. Works for PURCHASE (lump sum), SIP and REDEMPTION
  // snapshots (best-effort, read-only) — rows with absent values are omitted.
  const snapshotSummary = useMemo(() => {
    let snap: Record<string, unknown> | null = null;
    try {
      const parsed =
        typeof challenge.snapshotJson === 'string'
          ? JSON.parse(challenge.snapshotJson)
          : challenge.snapshotJson;
      if (parsed && typeof parsed === 'object') snap = parsed as Record<string, unknown>;
    } catch {
      snap = null;
    }
    if (!snap) return null;
    const pick = (...keys: string[]) => {
      for (const k of keys) {
        const v = snap?.[k];
        if (v !== undefined && v !== null && v !== '') return v;
      }
      return undefined;
    };
    const asMoney = (raw: unknown) => {
      const num = typeof raw === 'number' ? raw : Number(raw);
      return Number.isFinite(num) && num > 0 ? `₹${num.toLocaleString('en-IN')}` : undefined;
    };
    const asDate = (raw: unknown) => {
      if (raw == null || raw === '') return undefined;
      const d = new Date(String(raw));
      return Number.isNaN(d.getTime())
        ? String(raw)
        : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
    };

    const amount = asMoney(pick('amount', 'amountInRupees', 'orderAmount', 'investmentAmount'));
    const scheme = pick('productSchemeName', 'schemeName', 'fundName', 'productName', 'scheme');
    const amc = pick('productSchemeAmcName', 'amcName');
    const units = pick('units', 'unitsToRedeem', 'quantity');

    // Transaction type: transactionKind LUMPSUM → lump sum purchase; otherwise
    // fall back to the challenge's transactionType (PURCHASE | SIP | REDEMPTION).
    const kind = String(pick('transactionKind') ?? '').trim().toUpperCase();
    const type = normalizeStatus(String(pick('transactionType', 'type') ?? challenge.transactionType ?? ''));
    const transactionTypeLabel =
      kind === 'LUMPSUM' ? 'Lump sum purchase'
        : type === 'SIP' ? 'SIP (recurring)'
          : type === 'REDEMPTION' ? 'Redemption / payment exit'
            : kind || type
              ? (kind || type).replace(/_/g, ' ')
              : undefined;

    const orderDateTimeRaw = pick('orderDateTime');
    const orderDateTime = (() => {
      if (orderDateTimeRaw == null) return undefined;
      const d = new Date(String(orderDateTimeRaw));
      return Number.isNaN(d.getTime()) ? String(orderDateTimeRaw) : d.toLocaleString('en-IN');
    })();

    const instalmentsRaw = pick('sipInstalments', 'instalments');

    const rows: Array<{ label: string; value: string }> = [];
    const addRow = (label: string, value: unknown) => {
      if (value === undefined || value === null || value === '') return;
      rows.push({ label, value: String(value) });
    };
    addRow('Transaction type', transactionTypeLabel);
    addRow('Scheme', scheme != null ? `${String(scheme)}${amc ? ` · ${String(amc)}` : ''}` : undefined);
    addRow('ISIN', pick('productSchemeIsin', 'isin'));
    addRow('Order date/time', orderDateTime);
    addRow('Amount', amount);
    addRow('Units', units);
    addRow('Folio number', pick('folioNumber', 'folio'));
    addRow('NAV date', asDate(pick('allotmentDate')));
    addRow('NAV amount', asMoney(pick('allotmentNav')));
    addRow('SIP name', pick('sipName'));
    addRow('SIP number', pick('sipNumber'));
    addRow('SIP frequency', prettySipFrequency(pick('sipFrequency') as string | undefined));
    addRow('SIP start date', asDate(pick('sipStartDate')));
    addRow('Instalments', instalmentsRaw);
    addRow('Payment mode', pick('paymentMode', 'paymentMethod'));
    addRow('Mandate mode', pick('mandateMode'));

    return {
      rows,
      amount,
      scheme: scheme != null ? String(scheme) : undefined,
      units: units != null ? String(units) : undefined,
    };
  }, [challenge.snapshotJson, challenge.transactionType]);

  return (
    <div className="space-y-6">
      {onBack && (
        <button
          type="button"
          onClick={onBack}
          className="text-sm font-medium text-blue-600 hover:underline"
        >
          ← Back to approvals
        </button>
      )}

      {/* Frozen snapshot — read-only */}
      <div className="rounded-2xl border border-slate-200 bg-white p-6">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-slate-800">
            Transaction to approve
            <span className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              {typeLabel}
            </span>
          </h2>
          {challenge.expiresAt && !success && !stale && (
            <span className="inline-flex items-center gap-1 text-[11px] text-slate-400">
              <Clock className="h-3 w-3" /> Expires {new Date(challenge.expiresAt).toLocaleString('en-IN')}
            </span>
          )}
        </div>
        {/* Structured, labelled detail (primary). Falls back to the raw dump when
            the snapshot can't be parsed into any known field. */}
        {snapshotSummary && snapshotSummary.rows.length > 0 ? (
          <dl className="divide-y divide-slate-100 overflow-hidden rounded-xl border border-slate-200 bg-slate-50/60">
            {snapshotSummary.rows.map((row) => (
              <div key={row.label} className="flex items-start justify-between gap-4 px-4 py-2.5">
                <dt className="pt-0.5 text-xs font-medium text-slate-400">{row.label}</dt>
                <dd className="text-right text-sm font-medium text-slate-800 break-words">{row.value}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <ReadOnlySnapshot value={challenge.snapshotJson} />
        )}

        {/* Raw frozen snapshot stays available for full transparency, collapsed by default. */}
        {snapshotSummary && snapshotSummary.rows.length > 0 && (
          <div className="mt-3">
            <button
              type="button"
              onClick={() => setShowRawSnapshot((v) => !v)}
              className="flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700"
            >
              {showRawSnapshot ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
              {showRawSnapshot ? 'Hide raw snapshot' : 'View raw snapshot'}
            </button>
            {showRawSnapshot && (
              <div className="mt-2">
                <ReadOnlySnapshot value={challenge.snapshotJson} />
              </div>
            )}
          </div>
        )}

        {challenge.snapshotSha256 && (
          <p className="mt-3 break-all font-mono text-[11px] text-slate-400">
            Snapshot hash: {challenge.snapshotSha256}
          </p>
        )}
      </div>

      {/* Rendered consent text — exact evidence copy */}
      {challenge.consentRenderedText && (
        <div className="rounded-2xl border border-slate-200 bg-white p-6">
          <h2 className="mb-2 text-sm font-semibold text-slate-800">Consent</h2>
          <div className="max-h-56 overflow-auto whitespace-pre-wrap rounded-xl border border-slate-100 bg-slate-50 p-4 text-xs leading-relaxed text-slate-600">
            {challenge.consentRenderedText}
          </div>
          {challenge.consentTemplateVersion && (
            <p className="mt-2 text-[11px] text-slate-400">
              Consent version: {challenge.consentTemplateVersion}
            </p>
          )}
        </div>
      )}

      {/* Success state */}
      {success ? (
        <motion.div
          initial={{ opacity: 0, scale: 0.98 }}
          animate={{ opacity: 1, scale: 1 }}
          className="rounded-2xl border border-emerald-200 bg-emerald-50 p-6 text-center"
        >
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-100">
            <ShieldCheck className="h-6 w-6 text-emerald-600" />
          </div>
          <p className="text-sm font-semibold text-slate-700">
            {challenge.nextAction?.message || 'Approved and submitted'}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            You've approved this exact transaction with a one-time passcode. It has been submitted for processing.
          </p>
          {challenge.nextAction?.url && (
            <a
              href={challenge.nextAction.url}
              className="mt-4 inline-flex items-center gap-2 rounded-xl bg-[#0B1B3E] px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
            >
              <ExternalLink className="h-4 w-4" />
              {challenge.nextAction.label || 'Continue'}
            </a>
          )}
        </motion.div>
      ) : stale ? (
        /* Terminal-fail — must re-request a fresh challenge */
        <div className="rounded-2xl border border-amber-200 bg-amber-50 p-6">
          <div className="flex items-start gap-3">
            <XCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-600" />
            <div className="flex-1">
              <p className="text-sm font-semibold text-amber-900">
                {status === 'SUPERSEDED'
                  ? 'This request was superseded'
                  : status === 'REJECTED'
                    ? 'This request was rejected'
                    : 'This request has expired'}
              </p>
              <p className="mt-1 text-xs text-amber-800">
                {status === 'SUPERSEDED'
                  ? 'The transaction was edited, so this approval no longer applies. Request a fresh code to approve the updated transaction.'
                  : 'For your security this approval is no longer valid. Request a fresh code to try again.'}
              </p>
              <button
                type="button"
                onClick={reRequest}
                className="mt-4 inline-flex items-center gap-2 rounded-xl bg-[#0B1B3E] px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
              >
                <RefreshCw className="h-4 w-4" /> Start a fresh approval
              </button>
            </div>
          </div>
        </div>
      ) : (
        /* Active flow: consent → send code → 6-box OTP → approve */
        <div className="rounded-2xl border border-slate-200 bg-white p-6">
          <AnimatePresence>
            {error && (
              <motion.div
                initial={{ opacity: 0, y: -8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                className="mb-5 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3"
              >
                <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
                <p className="text-sm text-red-700">{error}</p>
              </motion.div>
            )}
          </AnimatePresence>

          {step === 'consent' && (
            <motion.div key="consent" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <label className="flex cursor-pointer select-none items-start gap-3">
                <input
                  type="checkbox"
                  checked={consentAccepted}
                  onChange={(e) => { setConsentAccepted(e.target.checked); setError(''); }}
                  className="mt-0.5 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                />
                <span className="text-sm leading-relaxed text-slate-700">
                  I have reviewed the transaction details above and accept the consent. Send me a one-time passcode to authorize it.
                </span>
              </label>
              <button
                type="button"
                onClick={() => void handleSendCode()}
                disabled={!consentAccepted || sending}
                className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {sending ? (<><Spinner /> Sending code…</>) : (<><ShieldCheck className="h-4 w-4" /> Send code</>)}
              </button>
              <p className="mt-3 text-center text-xs text-slate-400">
                A 6-digit code will be sent to your verified
                {' '}{(challenge.channel || 'EMAIL').toLowerCase()}
                {challenge.maskedDestination ? ` (${challenge.maskedDestination})` : ''}.
              </p>
            </motion.div>
          )}

          {step === 'verify' && (
            <motion.div key="verify" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }}>
              <div className="mb-6 flex items-center gap-3 rounded-xl border border-green-200 bg-green-50 px-4 py-3">
                <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-green-100">
                  <CheckCircle2 className="h-4 w-4 text-green-600" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-semibold text-slate-700">
                    Code sent to your {(challenge.channel || 'email').toLowerCase()}
                  </p>
                  {challenge.maskedDestination && (
                    <p className="truncate text-[11px] text-slate-500">{challenge.maskedDestination}</p>
                  )}
                </div>
              </div>

              {import.meta.env.DEV && devCode && (
                <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-left">
                  <p className="mb-1 text-[11px] font-semibold text-amber-900">Dev OTP code</p>
                  <p className="font-mono text-base tracking-widest text-amber-800">{devCode}</p>
                </div>
              )}

              <label className="mb-3 block text-xs font-bold uppercase tracking-wider text-slate-500">
                Enter 6-digit code
              </label>
              <div className="flex justify-between gap-2" onPaste={handleDigitPaste}>
                {otpDigits.map((digit, i) => (
                  <input
                    key={i}
                    ref={(el) => { otpRefs.current[i] = el; }}
                    type="text"
                    inputMode="numeric"
                    maxLength={1}
                    value={digit}
                    onChange={(e) => handleDigitChange(i, e.target.value)}
                    onKeyDown={(e) => handleDigitKeyDown(i, e)}
                    className={`h-14 w-12 rounded-xl border-2 text-center text-2xl font-bold outline-none transition-all
                      ${digit ? 'border-blue-500 bg-blue-50 text-blue-700' : 'border-slate-200 bg-slate-50 text-slate-700'}
                      focus:border-blue-500 focus:bg-blue-50 focus:ring-2 focus:ring-blue-100`}
                  />
                ))}
              </div>

              <div className="mb-6 mt-2.5 flex items-center justify-end">
                {countdown > 0 ? (
                  <p className="text-xs text-slate-400">
                    Resend code in <span className="font-semibold text-slate-600">{countdown}s</span>
                  </p>
                ) : (
                  <button
                    type="button"
                    onClick={() => void handleResend()}
                    className="flex items-center gap-1.5 text-xs font-medium text-blue-500 transition-colors hover:text-blue-700"
                  >
                    <RefreshCw className="h-3 w-3" /> Resend code
                  </button>
                )}
              </div>

              {/* Compliance: clear, informed consent captured AT OTP ENTRY.
                  Applies for PURCHASE, REDEMPTION and SIP challenge types. */}
              <div className="mb-5 rounded-xl border border-slate-200 bg-slate-50 p-4">
                {(snapshotSummary?.amount || snapshotSummary?.scheme || snapshotSummary?.units) && (
                  <div className="mb-3 space-y-1 text-xs text-slate-600">
                    {snapshotSummary?.scheme && (
                      <p className="flex justify-between gap-3">
                        <span className="text-slate-400">Scheme</span>
                        <span className="text-right font-medium text-slate-700">{snapshotSummary.scheme}</span>
                      </p>
                    )}
                    {snapshotSummary?.amount && (
                      <p className="flex justify-between gap-3">
                        <span className="text-slate-400">Amount</span>
                        <span className="text-right font-semibold text-slate-800">{snapshotSummary.amount}</span>
                      </p>
                    )}
                    {snapshotSummary?.units && (
                      <p className="flex justify-between gap-3">
                        <span className="text-slate-400">Units</span>
                        <span className="text-right font-medium text-slate-700">{snapshotSummary.units}</span>
                      </p>
                    )}
                  </div>
                )}
                <label className="flex cursor-pointer select-none items-start gap-3">
                  <input
                    type="checkbox"
                    checked={verifyConsent}
                    onChange={(e) => { setVerifyConsent(e.target.checked); setError(''); }}
                    className="mt-0.5 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                  />
                  <span className="text-sm leading-relaxed text-slate-700">
                    I authorise this {typeLabel} and confirm the details above are correct.
                  </span>
                </label>
              </div>

              <button
                type="button"
                onClick={() => void handleApprove()}
                disabled={otpDigits.some((d) => !d) || !verifyConsent || approving}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {approving ? (<><Loader2 className="h-4 w-4 animate-spin" /> Approving…</>) : (<><ShieldCheck className="h-4 w-4" /> Approve</>)}
              </button>
            </motion.div>
          )}
        </div>
      )}
    </div>
  );
}
