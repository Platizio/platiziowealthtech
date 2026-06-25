import React, { useEffect, useMemo, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  AlertCircle, CheckCircle2, ShieldCheck, Loader2, RefreshCw, Clock, XCircle,
} from 'lucide-react';
import {
  approveProfileChange,
  rejectProfileChange,
  requestProfileChangeOtp,
  type ProfileChangeApprovalSummary,
} from '../config/api';

const Spinner = () => (
  <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
  </svg>
);

const normalizeStatus = (value?: string) => String(value || '').trim().toUpperCase();

/** Pretty-prints the frozen pending-profile JSON as a read-only key/value list. */
function PendingProfileView({ value }: { value?: string }) {
  const entries = useMemo<Array<[string, string]>>(() => {
    if (!value) return [];
    try {
      const parsed = JSON.parse(value) as Record<string, unknown>;
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return Object.entries(parsed).map(([k, v]) => [
          k.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase()),
          v == null || v === '' ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v),
        ]);
      }
    } catch {
      /* fall through to raw */
    }
    return [];
  }, [value]);

  if (entries.length === 0) {
    return (
      <pre className="max-h-80 overflow-auto rounded-xl border border-slate-200 bg-slate-50 p-4 text-xs leading-relaxed text-slate-700 whitespace-pre-wrap break-words font-mono">
        {value || '—'}
      </pre>
    );
  }

  return (
    <dl className="divide-y divide-slate-100 overflow-hidden rounded-xl border border-slate-200 bg-slate-50">
      {entries.map(([label, val]) => (
        <div key={label} className="flex items-start justify-between gap-4 px-4 py-2.5">
          <dt className="text-xs font-medium text-slate-500">{label}</dt>
          <dd className="break-words text-right text-xs font-semibold text-slate-700">{val}</dd>
        </div>
      ))}
    </dl>
  );
}

interface ProfileChangeApprovalPanelProps {
  change: ProfileChangeApprovalSummary;
  /** Optional back affordance (return to the approvals list). */
  onBack?: () => void;
  /** Called when this change reaches a terminal state (approved/applied or rejected). */
  onResolved?: () => void;
}

type FlowStep = 'consent' | 'verify' | 'reject' | 'done';

/**
 * R9 distributor-filled profile-change approval (the "skip-form second loop").
 * The investor reviews the FROZEN pending profile + rendered consent text, accepts
 * an UNTICKED consent checkbox to unlock "Send code", enters a 6-box OTP, then:
 *
 *   POST /investor/profile-changes/{id}/request-otp    (PENDING → CHALLENGE_SENT)
 *   POST /investor/profile-changes/{id}/approve {consentAccepted:true, code}  (→ READY)
 *   POST /investor/profile-changes/{id}/reject  {reason}                      (→ INVESTOR_SKIPPED)
 *
 * Mirrors {@link TransactionApprovalPanel} exactly (OTP boxes, consent gate, navy
 * theme) and adds a Reject-with-reason path so the distributor can re-fill.
 */
export default function ProfileChangeApprovalPanel({
  change,
  onBack,
  onResolved,
}: ProfileChangeApprovalPanelProps) {
  const initialStatus = normalizeStatus(change.status);

  const [consentAccepted, setConsentAccepted] = useState(false);
  const [otpDigits, setOtpDigits] = useState<string[]>(Array(6).fill(''));
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const [approving, setApproving] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [devCode, setDevCode] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const [localStep, setLocalStep] = useState<FlowStep | null>(
    initialStatus === 'CHALLENGE_SENT' ? 'verify' : null,
  );
  const [done, setDone] = useState<'approved' | 'rejected' | null>(null);
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  const step: FlowStep = done ? 'done' : localStep ?? 'consent';

  useEffect(() => {
    setConsentAccepted(false);
    setOtpDigits(Array(6).fill(''));
    setError('');
    setCountdown(0);
    setDevCode('');
    setRejectReason('');
    setDone(null);
    setLocalStep(normalizeStatus(change.status) === 'CHALLENGE_SENT' ? 'verify' : null);
  }, [change]);

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

  const sendCode = async (isResend: boolean) => {
    if (!isResend && (!consentAccepted || sending)) return;
    setSending(true);
    setError('');
    try {
      const data = await requestProfileChangeOtp(change.challengeId);
      setOtpDigits(Array(6).fill(''));
      setDevCode(typeof data.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      setLocalStep('verify');
      if (isResend) setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not send the verification code. Please try again.');
    } finally {
      setSending(false);
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

  const handleApprove = async () => {
    const entered = otpDigits.join('');
    if (entered.length < 6) {
      setError('Please enter the complete 6-digit code.');
      return;
    }
    setApproving(true);
    setError('');
    try {
      await approveProfileChange(change.challengeId, entered, true);
      setDone('approved');
      onResolved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Incorrect code. Please check and try again.');
      setOtpDigits(Array(6).fill(''));
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } finally {
      setApproving(false);
    }
  };

  const handleReject = async () => {
    setRejecting(true);
    setError('');
    try {
      await rejectProfileChange(change.challengeId, rejectReason.trim() || undefined);
      setDone('rejected');
      onResolved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not reject this change. Please try again.');
    } finally {
      setRejecting(false);
    }
  };

  return (
    <div className="space-y-6">
      {onBack && step !== 'done' && (
        <button
          type="button"
          onClick={onBack}
          className="text-sm font-medium text-blue-600 hover:underline"
        >
          ← Back to approvals
        </button>
      )}

      {/* Frozen pending profile — read-only */}
      <div className="rounded-2xl border border-slate-200 bg-white p-6">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-slate-800">
            Profile changes to approve
            <span className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Filled by your distributor
            </span>
          </h2>
          {change.expiresAt && step !== 'done' && (
            <span className="inline-flex items-center gap-1 text-[11px] text-slate-400">
              <Clock className="h-3 w-3" /> Expires {new Date(change.expiresAt).toLocaleString('en-IN')}
            </span>
          )}
        </div>
        <PendingProfileView value={change.pendingProfileJson} />
        {change.profileChangeSha256 && (
          <p className="mt-3 break-all font-mono text-[11px] text-slate-400">
            Change hash: {change.profileChangeSha256}
          </p>
        )}
      </div>

      {/* Rendered consent text — exact evidence copy */}
      {change.consentRenderedText && (
        <div className="rounded-2xl border border-slate-200 bg-white p-6">
          <h2 className="mb-2 text-sm font-semibold text-slate-800">Consent</h2>
          <div className="max-h-56 overflow-auto whitespace-pre-wrap rounded-xl border border-slate-100 bg-slate-50 p-4 text-xs leading-relaxed text-slate-600">
            {change.consentRenderedText}
          </div>
          {change.consentTemplateVersion && (
            <p className="mt-2 text-[11px] text-slate-400">
              Consent version: {change.consentTemplateVersion}
            </p>
          )}
        </div>
      )}

      {/* Terminal success states */}
      {step === 'done' ? (
        <motion.div
          initial={{ opacity: 0, scale: 0.98 }}
          animate={{ opacity: 1, scale: 1 }}
          className={`rounded-2xl border p-6 text-center ${
            done === 'approved' ? 'border-emerald-200 bg-emerald-50' : 'border-slate-200 bg-slate-50'
          }`}
        >
          <div
            className={`mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full ${
              done === 'approved' ? 'bg-emerald-100' : 'bg-slate-200'
            }`}
          >
            {done === 'approved' ? (
              <ShieldCheck className="h-6 w-6 text-emerald-600" />
            ) : (
              <XCircle className="h-6 w-6 text-slate-500" />
            )}
          </div>
          <p className="text-sm font-semibold text-slate-700">
            {done === 'approved' ? 'Profile changes approved and applied' : 'Profile changes rejected'}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            {done === 'approved'
              ? 'You authorized these exact changes with a one-time passcode. Your profile is now up to date.'
              : 'These changes were declined. Your distributor can review and re-submit a corrected profile.'}
          </p>
          {onBack && (
            <button
              type="button"
              onClick={onBack}
              className="mt-4 inline-flex items-center gap-2 rounded-xl bg-[#0B1B3E] px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
            >
              Back to approvals
            </button>
          )}
        </motion.div>
      ) : (
        /* Active flow: consent → send code → 6-box OTP → approve (or reject-with-reason) */
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
                  I have reviewed the profile changes above and accept the consent. Send me a one-time passcode to authorize them.
                </span>
              </label>
              <button
                type="button"
                onClick={() => void sendCode(false)}
                disabled={!consentAccepted || sending}
                className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {sending ? (<><Spinner /> Sending code…</>) : (<><ShieldCheck className="h-4 w-4" /> Send code</>)}
              </button>
              <p className="mt-3 text-center text-xs text-slate-400">
                A 6-digit code will be sent to your verified
                {' '}{(change.channel || 'EMAIL').toLowerCase()}
                {change.maskedDestination ? ` (${change.maskedDestination})` : ''}.
              </p>
              <div className="mt-4 border-t border-slate-100 pt-4 text-center">
                <button
                  type="button"
                  onClick={() => { setLocalStep('reject'); setError(''); }}
                  className="text-xs font-medium text-slate-500 hover:text-red-600"
                >
                  These details are wrong — reject this change
                </button>
              </div>
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
                    Code sent to your {(change.channel || 'email').toLowerCase()}
                  </p>
                  {change.maskedDestination && (
                    <p className="truncate text-[11px] text-slate-500">{change.maskedDestination}</p>
                  )}
                </div>
              </div>

              {devCode && (
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
                    onClick={() => void sendCode(true)}
                    className="flex items-center gap-1.5 text-xs font-medium text-blue-500 transition-colors hover:text-blue-700"
                  >
                    <RefreshCw className="h-3 w-3" /> Resend code
                  </button>
                )}
              </div>

              <button
                type="button"
                onClick={() => void handleApprove()}
                disabled={otpDigits.some((d) => !d) || approving}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
              >
                {approving ? (<><Loader2 className="h-4 w-4 animate-spin" /> Approving…</>) : (<><ShieldCheck className="h-4 w-4" /> Approve changes</>)}
              </button>
            </motion.div>
          )}

          {step === 'reject' && (
            <motion.div key="reject" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
              <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-slate-500">
                Reason (optional)
              </label>
              <textarea
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                rows={3}
                placeholder="Tell your distributor what to correct (e.g. wrong address or date of birth)."
                className="w-full resize-none rounded-xl border-2 border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-700 outline-none transition-all focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-100"
              />
              <div className="mt-5 flex gap-3">
                <button
                  type="button"
                  onClick={() => { setLocalStep(null); setError(''); }}
                  disabled={rejecting}
                  className="flex-1 rounded-xl border border-slate-200 px-5 py-3 text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50"
                >
                  Back
                </button>
                <button
                  type="button"
                  onClick={() => void handleReject()}
                  disabled={rejecting}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-red-600 px-5 py-3 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {rejecting ? (<><Loader2 className="h-4 w-4 animate-spin" /> Rejecting…</>) : (<><XCircle className="h-4 w-4" /> Reject change</>)}
                </button>
              </div>
            </motion.div>
          )}
        </div>
      )}
    </div>
  );
}
