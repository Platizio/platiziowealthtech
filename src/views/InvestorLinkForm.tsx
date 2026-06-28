import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Loader2, AlertCircle, CheckCircle2, XCircle, ShieldCheck, ShieldAlert,
  User, MapPin, Briefcase, Wallet, Landmark, FileCheck2,
} from 'lucide-react';
import { apiFetch } from '../config/api';

/**
 * Public token-only investor onboarding form (the no-auth page the distributor's
 * link opens). Reached at `/investor/link-form?token=…` with NO session — the
 * opaque token is the only credential.
 *
 * Flow:
 *  1. GET /investor/link/review?token=…   → identity the distributor entered (read-only)
 *                                            + `profile` for prefilling the editable form.
 *  2. POST /investor/link/submit-profile  → links the distributor, applies the profile,
 *                                            sets linking_status=READY, notifies distributor.
 *  3. POST /investor/link/reject          → declines the link.
 *
 * All calls use { skipAuthRedirect: true } so a 401/410 never bounces to a login
 * page — this surface must work with no login at all.
 */

interface ReviewProfile {
  // personal details / address
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  state?: string;
  postalCode?: string;
  occupation?: string;
  incomeRange?: string;
  // bank account
  bankAccountHolderName?: string;
  bankAccountNumber?: string;
  bankIfscCode?: string;
  bankName?: string;
  bankAccountType?: string;
  // FATCA declaration
  placeOfBirth?: string;
  countryOfBirth?: string;
  taxResidencyCountry?: string;
  taxIdentificationNumber?: string;
}

interface ReviewResponse {
  status?: string;
  fullName?: string;
  pan?: string;
  maskedEmail?: string;
  email?: string;
  mobileNumber?: string;
  dateOfBirth?: string | null;
  relationshipType?: string;
  distributorName?: string;
  profile?: ReviewProfile | null;
}

interface ProfileForm {
  fullName: string;
  pan: string;
  email: string;
  mobileNumber: string;
  dateOfBirth: string;
  relationshipType: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  occupation: string;
  incomeRange: string;
  bankAccountHolderName: string;
  bankAccountNumber: string;
  bankIfscCode: string;
  bankName: string;
  bankAccountType: string;
  placeOfBirth: string;
  countryOfBirth: string;
  taxResidencyCountry: string;
  taxIdentificationNumber: string;
}

const INCOME_RANGES = ['Below 1L', '1-5L', '5-10L', '10-25L', 'Above 25L'];
const ACCOUNT_TYPES = ['Savings', 'Current', 'NRE', 'NRO'];

const EMPTY: ProfileForm = {
  fullName: '',
  pan: '',
  email: '',
  mobileNumber: '',
  dateOfBirth: '',
  relationshipType: '',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  postalCode: '',
  occupation: '',
  incomeRange: '',
  bankAccountHolderName: '',
  bankAccountNumber: '',
  bankIfscCode: '',
  bankName: '',
  bankAccountType: '',
  placeOfBirth: '',
  countryOfBirth: '',
  taxResidencyCountry: '',
  taxIdentificationNumber: '',
};

const fmtDate = (v?: string | null) => {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime())
    ? v
    : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
};

const maskPan = (pan?: string) => {
  if (!pan) return '—';
  const p = pan.trim().toUpperCase();
  if (p.length < 5) return p;
  return `${p.slice(0, 2)}XXX${p.slice(5)}`;
};

type Done = 'submitted' | 'declined' | null;

export default function InvestorLinkForm() {
  const [params] = useSearchParams();
  const token = params.get('token') || '';

  const [review, setReview] = useState<ReviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [form, setForm] = useState<ProfileForm>(EMPTY);
  const [consent, setConsent] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [declining, setDeclining] = useState(false);
  const [actionError, setActionError] = useState('');
  const [done, setDone] = useState<Done>(null);

  const load = useCallback(async () => {
    if (!token) {
      setLoadError('This link is missing its token. Please use the full link from your distributor.');
      setLoading(false);
      return;
    }
    setLoading(true);
    setLoadError('');
    try {
      const res = await apiFetch(`/investor/link/review?token=${encodeURIComponent(token)}`, {
        skipAuthRedirect: true,
      });
      const body = (await res.json().catch(() => null)) as ReviewResponse | { message?: string } | null;
      if (!res.ok) {
        const msg = (body as { message?: string } | null)?.message;
        if (res.status === 410) {
          throw new Error(msg || 'This link has expired or has already been used.');
        }
        if (res.status === 400) {
          throw new Error(msg || 'This link is no longer valid.');
        }
        throw new Error(msg || 'This link is invalid or has expired.');
      }
      const data = (body as ReviewResponse) ?? {};
      setReview(data);
      // Prefill the editable form from whatever the distributor already entered.
      // NO defaulted/pre-checked values — only mirror real prefill data.
      const p = data.profile ?? {};
      setForm({
        fullName: data.fullName ?? '',
        pan: data.pan ?? '',
        email: data.email ?? '',
        mobileNumber: data.mobileNumber ?? '',
        dateOfBirth: data.dateOfBirth ?? '',
        relationshipType: data.relationshipType ?? '',
        addressLine1: p.addressLine1 ?? '',
        addressLine2: p.addressLine2 ?? '',
        city: p.city ?? '',
        state: p.state ?? '',
        postalCode: p.postalCode ?? '',
        occupation: p.occupation ?? '',
        incomeRange: p.incomeRange ?? '',
        bankAccountHolderName: p.bankAccountHolderName ?? '',
        bankAccountNumber: p.bankAccountNumber ?? '',
        bankIfscCode: p.bankIfscCode ?? '',
        bankName: p.bankName ?? '',
        bankAccountType: p.bankAccountType ?? '',
        placeOfBirth: p.placeOfBirth ?? '',
        countryOfBirth: p.countryOfBirth ?? '',
        taxResidencyCountry: p.taxResidencyCountry ?? '',
        taxIdentificationNumber: p.taxIdentificationNumber ?? '',
      });
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'This link is invalid or has expired.');
      setReview(null);
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { void load(); }, [load]);

  const update = (k: keyof ProfileForm) => (v: string) => {
    setForm(f => ({ ...f, [k]: v }));
    setActionError('');
  };

  const submitProfile = async () => {
    if (!consent) return;
    setSubmitting(true);
    setActionError('');
    try {
      const res = await apiFetch('/investor/link/submit-profile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, consentAccepted: true, profile: form }),
        skipAuthRedirect: true,
      });
      const body = (await res.json().catch(() => null)) as { status?: string; message?: string } | null;
      if (!res.ok) {
        if (res.status === 410) {
          throw new Error(body?.message || 'This link has expired or has already been used.');
        }
        if (res.status === 400) {
          throw new Error(body?.message || 'This link is no longer valid. Please request a new one.');
        }
        throw new Error(body?.message || 'Could not submit your details. Please try again.');
      }
      setDone('submitted');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not submit your details.');
    } finally {
      setSubmitting(false);
    }
  };

  const decline = async () => {
    setDeclining(true);
    setActionError('');
    try {
      const res = await apiFetch('/investor/link/reject', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
        skipAuthRedirect: true,
      });
      const body = (await res.json().catch(() => null)) as { message?: string } | null;
      if (!res.ok && res.status !== 410) {
        throw new Error(body?.message || 'Could not decline this link.');
      }
      setDone('declined');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not decline this link.');
    } finally {
      setDeclining(false);
    }
  };

  const inputCls =
    'w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100';
  const labelCls = 'mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500';

  const Page = ({ children }: { children: ReactNode }) => (
    <div className="min-h-screen bg-slate-50">
      <div className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-2xl items-center gap-2.5 px-8 py-4">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-[#0B1B3E] text-base font-bold text-white">P</div>
          <span className="text-lg font-bold tracking-tight text-slate-800">Platizio</span>
        </div>
      </div>
      {children}
    </div>
  );

  if (loading) {
    return (
      <Page>
        <div className="flex min-h-[60vh] items-center justify-center">
          <div className="text-center">
            <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
            <p className="mt-3 text-sm text-slate-600">Loading your onboarding details…</p>
          </div>
        </div>
      </Page>
    );
  }

  if (loadError) {
    return (
      <Page>
        <div className="mx-auto max-w-2xl p-8">
          <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
            <div className="flex items-start gap-2.5">
              <AlertCircle className="mt-0.5 h-5 w-5 text-red-500" />
              <div>
                <p className="font-semibold">Link unavailable</p>
                <p className="mt-1 text-sm">{loadError}</p>
                {token && (
                  <button onClick={() => void load()} className="mt-3 text-sm font-semibold text-red-600 hover:underline">
                    Try again
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      </Page>
    );
  }

  if (done === 'submitted') {
    return (
      <Page>
        <div className="mx-auto max-w-2xl p-8">
          <div className="rounded-2xl border border-slate-100 bg-white p-8 text-center shadow-sm">
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100">
              <CheckCircle2 className="h-8 w-8 text-emerald-600" />
            </div>
            <h2 className="text-lg font-semibold text-slate-800">✓ Submitted to your distributor</h2>
            <p className="mx-auto mt-1.5 max-w-md text-sm text-slate-500">
              Thank you. Your details have been submitted and your distributor
              {review?.distributorName ? ` (${review.distributorName})` : ''} has been notified.
              You can now close this page.
            </p>
          </div>
        </div>
      </Page>
    );
  }

  if (done === 'declined') {
    return (
      <Page>
        <div className="mx-auto max-w-2xl p-8">
          <div className="rounded-2xl border border-slate-100 bg-white p-8 text-center shadow-sm">
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-slate-100">
              <XCircle className="h-8 w-8 text-slate-500" />
            </div>
            <h2 className="text-lg font-semibold text-slate-800">Declined</h2>
            <p className="mx-auto mt-1.5 max-w-md text-sm text-slate-500">
              You've declined this onboarding link
              {review?.distributorName ? ` from ${review.distributorName}` : ''}.
              Your distributor has been notified. You can now close this page.
            </p>
          </div>
        </div>
      </Page>
    );
  }

  return (
    <Page>
      <div className="mx-auto max-w-2xl space-y-6 p-8">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Complete your onboarding</h1>
          <p className="mt-1 text-sm text-slate-500">
            {review?.distributorName ? `Invited by ${review.distributorName}. ` : ''}
            Check and edit any details your distributor entered, complete the rest, and submit to finish.
          </p>
        </div>

        {/* Your details — entered by the distributor; the investor can edit anything that's wrong. */}
        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-2 text-[10px] font-bold uppercase tracking-wider text-slate-400">
            <User className="h-3.5 w-3.5" /> Your details {review?.distributorName ? `(from ${review.distributorName} — edit if needed)` : '(edit if needed)'}
          </div>
          <div className="mt-3 space-y-3">
            <div>
              <label className={labelCls}>Full name</label>
              <input value={form.fullName} onChange={e => update('fullName')(e.target.value)} placeholder="Your full name" className={inputCls} />
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className={labelCls}>PAN</label>
                <input value={form.pan} onChange={e => update('pan')(e.target.value.toUpperCase())} maxLength={10} placeholder="ABCDE1234F" className={`${inputCls} font-mono uppercase`} />
              </div>
              <div>
                <label className={labelCls}>Date of birth</label>
                <input type="date" value={form.dateOfBirth} onChange={e => update('dateOfBirth')(e.target.value)} className={inputCls} />
              </div>
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className={labelCls}>Email</label>
                <input type="email" value={form.email} onChange={e => update('email')(e.target.value)} placeholder="you@example.com" className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>Mobile</label>
                <input value={form.mobileNumber} onChange={e => update('mobileNumber')(e.target.value)} placeholder="10-digit mobile" className={inputCls} />
              </div>
            </div>
            <div>
              <label className={labelCls}>Relationship</label>
              <select
                value={form.relationshipType}
                onChange={e => update('relationshipType')(e.target.value)}
                className={`${inputCls} ${form.relationshipType ? 'text-slate-800' : 'text-slate-400'}`}
              >
                <option value="">Select…</option>
                {['SELF', 'SPOUSE', 'MINOR', 'HUF'].map(r => (
                  <option key={r} value={r} className="text-slate-800">{r}</option>
                ))}
              </select>
            </div>
          </div>
        </div>

        {/* Editable profile — Address & financial */}
        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-2 text-[10px] font-bold uppercase tracking-wider text-slate-400">
            <MapPin className="h-3.5 w-3.5" /> Address
          </div>
          <div className="mt-3 space-y-3">
            <div>
              <label className={labelCls}>Address line 1</label>
              <input value={form.addressLine1} onChange={e => update('addressLine1')(e.target.value)} placeholder="House / flat, building, street" className={inputCls} />
            </div>
            <div>
              <label className={labelCls}>Address line 2</label>
              <input value={form.addressLine2} onChange={e => update('addressLine2')(e.target.value)} placeholder="Area, landmark (optional)" className={inputCls} />
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div>
                <label className={labelCls}>City</label>
                <input value={form.city} onChange={e => update('city')(e.target.value)} placeholder="e.g. Mumbai" className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>State</label>
                <input value={form.state} onChange={e => update('state')(e.target.value)} placeholder="e.g. Maharashtra" className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>Postal code</label>
                <input value={form.postalCode} onChange={e => update('postalCode')(e.target.value)} placeholder="e.g. 400001" className={inputCls} />
              </div>
            </div>
          </div>

          <div className="mt-5 flex items-center gap-2 text-[10px] font-bold uppercase tracking-wider text-slate-400">
            <Briefcase className="h-3.5 w-3.5" /> Financial profile
          </div>
          <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className={labelCls}>Occupation</label>
              <input value={form.occupation} onChange={e => update('occupation')(e.target.value)} placeholder="e.g. Salaried, Business" className={inputCls} />
            </div>
            <div>
              <label className={labelCls}>Annual income range</label>
              <div className="relative">
                <Wallet className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <select
                  value={form.incomeRange}
                  onChange={e => update('incomeRange')(e.target.value)}
                  className={`${inputCls} pl-10 ${form.incomeRange ? 'text-slate-800' : 'text-slate-400'}`}
                >
                  <option value="">Select a range…</option>
                  {INCOME_RANGES.map(r => (
                    <option key={r} value={r} className="text-slate-800">{r}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>
        </div>

        {/* Bank account */}
        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-2 text-[10px] font-bold uppercase tracking-wider text-slate-400">
            <Landmark className="h-3.5 w-3.5" /> Bank account
          </div>
          <div className="mt-3 space-y-3">
            <div>
              <label className={labelCls}>Account holder name</label>
              <input value={form.bankAccountHolderName} onChange={e => update('bankAccountHolderName')(e.target.value)} placeholder="As per bank records" className={inputCls} />
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className={labelCls}>Account number</label>
                <input value={form.bankAccountNumber} onChange={e => update('bankAccountNumber')(e.target.value)} placeholder="Bank account number" className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>IFSC code</label>
                <input value={form.bankIfscCode} onChange={e => update('bankIfscCode')(e.target.value.toUpperCase())} placeholder="e.g. HDFC0001234" className={`${inputCls} font-mono uppercase`} />
              </div>
            </div>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className={labelCls}>Bank name</label>
                <input value={form.bankName} onChange={e => update('bankName')(e.target.value)} placeholder="e.g. HDFC Bank" className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>Account type</label>
                <select
                  value={form.bankAccountType}
                  onChange={e => update('bankAccountType')(e.target.value)}
                  className={`${inputCls} ${form.bankAccountType ? 'text-slate-800' : 'text-slate-400'}`}
                >
                  <option value="">Select a type…</option>
                  {ACCOUNT_TYPES.map(t => (
                    <option key={t} value={t} className="text-slate-800">{t}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>
        </div>

        {/* FATCA declaration */}
        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
          <div className="flex items-center gap-2 text-[10px] font-bold uppercase tracking-wider text-slate-400">
            <FileCheck2 className="h-3.5 w-3.5" /> FATCA declaration
          </div>
          <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <label className={labelCls}>Place of birth</label>
              <input value={form.placeOfBirth} onChange={e => update('placeOfBirth')(e.target.value)} placeholder="e.g. Mumbai" className={inputCls} />
            </div>
            <div>
              <label className={labelCls}>Country of birth</label>
              <input value={form.countryOfBirth} onChange={e => update('countryOfBirth')(e.target.value)} placeholder="e.g. India" className={inputCls} />
            </div>
            <div>
              <label className={labelCls}>Country of tax residency</label>
              <input value={form.taxResidencyCountry} onChange={e => update('taxResidencyCountry')(e.target.value)} placeholder="e.g. India" className={inputCls} />
            </div>
            <div>
              <label className={labelCls}>Tax identification number</label>
              <input value={form.taxIdentificationNumber} onChange={e => update('taxIdentificationNumber')(e.target.value)} placeholder="TIN (if non-resident)" className={inputCls} />
            </div>
          </div>
        </div>

        {actionError && (
          <div className="flex items-start gap-2 rounded-xl border border-amber-100 bg-amber-50 px-3.5 py-2.5">
            <ShieldAlert className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600" />
            <p className="text-xs text-amber-800">{actionError}</p>
          </div>
        )}

        {/* Consent */}
        <label className="flex cursor-pointer items-start gap-2.5 rounded-xl border border-slate-200 bg-white px-4 py-3 shadow-sm">
          <input
            type="checkbox"
            checked={consent}
            onChange={e => { setConsent(e.target.checked); setActionError(''); }}
            className="mt-0.5 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-200"
          />
          <span className="text-xs leading-relaxed text-slate-600">
            I confirm these details are correct and authorise Platizio to onboard me.
          </span>
        </label>

        {/* Terminal actions */}
        <div className="flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-end">
          <button
            onClick={() => void decline()}
            disabled={declining || submitting}
            className="flex items-center justify-center gap-2 rounded-xl border border-slate-200 px-5 py-3 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-60"
          >
            {declining ? (<><Loader2 className="h-4 w-4 animate-spin" /> Declining…</>) : 'Decline'}
          </button>
          <button
            onClick={() => void submitProfile()}
            disabled={submitting || declining || !consent}
            className="flex items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-5 py-3 text-sm font-semibold text-white hover:bg-[#1A3066] transition-colors disabled:cursor-not-allowed disabled:opacity-60"
          >
            {submitting ? (<><Loader2 className="h-4 w-4 animate-spin" /> Submitting…</>) : (<><ShieldCheck className="h-4 w-4" /> Approve &amp; Submit</>)}
          </button>
        </div>
      </div>
    </Page>
  );
}
