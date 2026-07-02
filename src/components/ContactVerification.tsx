import { useState } from 'react';
import { apiFetch } from '../config/api';

type Channel = 'email' | 'mobile';
type Method = 'OTP' | 'SELF_DECLARED' | null;

interface ContactStatus {
  emailVerified?: boolean;
  emailVerificationMethod?: string | null;
  emailBelongsTo?: string | null;
  mobileVerified?: boolean;
  mobileVerificationMethod?: string | null;
  mobileBelongsTo?: string | null;
  otpEnabled?: boolean;
}

/**
 * `distributor` (default) calls the distributor endpoints under
 * `/investors/{investorId}/...`. `investor-self` calls the investor-session
 * endpoints under `/investor/contact/...` and ignores `investorId` entirely.
 */
type Mode = 'distributor' | 'investor-self';

interface ContactVerificationProps {
  /** Required for `distributor` mode; ignored in `investor-self` mode. */
  investorId: string | null;
  channel: Channel;
  /** The email / mobile being verified (for empty-state messaging). */
  value: string;
  verified?: boolean;
  method?: Method;
  belongsTo?: string | null;
  onVerified?: (status: ContactStatus) => void;
  /** Which API surface to call. Defaults to the existing distributor flow. */
  mode?: Mode;
  /**
   * Render the channel as disabled with an explanatory hint instead of the
   * verification controls (e.g. mobile when SMS OTP is switched off).
   */
  disabled?: boolean;
  /** Hint shown when `disabled` is true. */
  disabledHint?: string;
}

const RELATIONSHIPS = [
  { value: 'self', label: 'Self' },
  { value: 'spouse', label: 'Spouse' },
  { value: 'dependent_child', label: 'Dependent child' },
  { value: 'dependent_parent', label: 'Dependent parent' },
  { value: 'guardian', label: 'Guardian' },
];

/**
 * Per-channel investor contact verification (Tier 2). Two ways to satisfy a
 * channel: an OTP round-trip (sent + verified server-side via Supabase Auth) or
 * a distributor self-declaration with the relationship (belongs_to). All calls
 * go through the Platizio backend (`/investors/{id}/...`), never to Supabase
 * directly.
 */
export default function ContactVerification({
  investorId,
  channel,
  value,
  verified: initialVerified,
  method: initialMethod,
  belongsTo: initialBelongsTo,
  onVerified,
  mode = 'distributor',
  disabled = false,
  disabledHint,
}: ContactVerificationProps) {
  const [verified, setVerified] = useState(!!initialVerified);
  const [method, setMethod] = useState<Method>(initialMethod ?? null);
  const [belongsTo, setBelongsTo] = useState(initialBelongsTo || 'self');
  const [sent, setSent] = useState(false);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');

  const label = channel === 'email' ? 'email' : 'mobile';
  const isSelf = mode === 'investor-self';

  // MSG91 STUB: mobile OTP is simulated server-side; swap to real MSG91-backed
  // delivery when integrated. The email channel is fully live.
  // investor-self has no per-investor id in the URL; distributor needs one.
  const otpRequestUrl = isSelf
    ? `/investor/contact/${channel}/otp/request`
    : `/investors/${investorId}/${channel}/otp/request`;
  const otpVerifyUrl = isSelf
    ? `/investor/contact/${channel}/otp/verify`
    : `/investors/${investorId}/${channel}/otp/verify`;
  const declareUrl = isSelf
    ? '/investor/contact/declare'
    : `/investors/${investorId}/contact/declare`;

  const applyStatus = (status: ContactStatus | null) => {
    if (!status) return;
    const v = channel === 'email' ? status.emailVerified : status.mobileVerified;
    const m = (channel === 'email' ? status.emailVerificationMethod : status.mobileVerificationMethod) as Method;
    const b = channel === 'email' ? status.emailBelongsTo : status.mobileBelongsTo;
    if (v) {
      setVerified(true);
      setMethod(m ?? null);
      if (b) setBelongsTo(b);
      onVerified?.(status);
    }
    return status;
  };

  const sendCode = async () => {
    if (!isSelf && !investorId) return;
    setBusy(true); setError(''); setInfo('');
    try {
      const res = await apiFetch(otpRequestUrl, { method: 'POST' });
      const data = (await res.json().catch(() => null)) as ContactStatus | null;
      if (!res.ok) throw new Error((data as unknown as { message?: string })?.message || `Could not send the ${label} code.`);
      setSent(true);
      setInfo(
        data?.otpEnabled === false
          ? 'Supabase is not configured (dev mode) — verify with code 000000.'
          : `A code was sent to the investor's ${label}.`,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : `Could not send the ${label} code.`);
    } finally {
      setBusy(false);
    }
  };

  const verifyCode = async () => {
    if ((!isSelf && !investorId) || !code.trim()) return;
    setBusy(true); setError('');
    try {
      const res = await apiFetch(otpVerifyUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: code.trim() }),
      });
      const data = (await res.json().catch(() => null)) as ContactStatus | null;
      if (!res.ok) throw new Error((data as unknown as { message?: string })?.message || 'Incorrect or expired code.');
      applyStatus(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Verification failed.');
    } finally {
      setBusy(false);
    }
  };

  const declare = async () => {
    if (!isSelf && !investorId) return;
    setBusy(true); setError('');
    try {
      const res = await apiFetch(declareUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ channel: channel.toUpperCase(), belongsTo }),
      });
      const data = (await res.json().catch(() => null)) as ContactStatus | null;
      if (!res.ok) throw new Error((data as unknown as { message?: string })?.message || 'Could not record the declaration.');
      applyStatus(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Declaration failed.');
    } finally {
      setBusy(false);
    }
  };

  if (verified) {
    const detail = method === 'SELF_DECLARED' ? `declared · ${belongsTo}` : 'OTP verified';
    return (
      <div className="mt-1.5 flex items-center gap-1.5 text-xs font-medium text-emerald-600">
        <span aria-hidden>✓</span>
        <span>Verified ({detail})</span>
      </div>
    );
  }

  if (disabled) {
    return (
      <p className="mt-1.5 text-[11px] text-slate-400">
        {disabledHint || `${label === 'email' ? 'Email' : 'Mobile'} verification is currently unavailable.`}
      </p>
    );
  }

  if (!isSelf && !investorId) {
    return <p className="mt-1.5 text-[11px] text-slate-400">Save the investor to verify their {label}.</p>;
  }
  if (!value) {
    return <p className="mt-1.5 text-[11px] text-slate-400">Enter a {label} to enable verification.</p>;
  }

  return (
    <div className="mt-2 rounded-lg border border-slate-200 bg-slate-50/60 p-2.5 space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        {!sent ? (
          <button
            type="button" disabled={busy} onClick={sendCode}
            className="text-xs font-semibold text-blue-600 hover:text-blue-700 disabled:opacity-50"
          >
            {busy ? 'Sending…' : `Send ${label} OTP`}
          </button>
        ) : (
          <div className="flex items-center gap-2">
            <input
              value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              placeholder="6-digit code" inputMode="numeric"
              className="w-28 rounded-md border border-slate-300 px-2 py-1 text-xs tracking-widest"
            />
            <button
              type="button" disabled={busy || code.trim().length < 4} onClick={verifyCode}
              className="rounded-md bg-blue-600 px-2.5 py-1 text-xs font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
            >
              {busy ? 'Verifying…' : 'Verify'}
            </button>
            <button type="button" onClick={sendCode} disabled={busy} className="text-[11px] text-slate-500 hover:underline">
              Resend
            </button>
          </div>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-slate-200 pt-2">
        <span className="text-[11px] text-slate-500">or self-declare —</span>
        <select
          value={belongsTo} onChange={e => setBelongsTo(e.target.value)}
          className="rounded-md border border-slate-300 px-2 py-1 text-xs"
        >
          {RELATIONSHIPS.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
        </select>
        <button
          type="button" disabled={busy} onClick={declare}
          className="rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-semibold text-slate-700 hover:bg-slate-100 disabled:opacity-50"
        >
          Confirm ownership
        </button>
      </div>

      {info && <p className="text-[11px] text-slate-500">{info}</p>}
      {error && <p className="text-[11px] text-red-600">{error}</p>}
    </div>
  );
}
