import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Loader2, AlertCircle, CheckCircle2, ArrowRight, User, ShieldAlert, MapPin, Briefcase, Wallet,
} from 'lucide-react';
import { apiFetch } from '../config/api';

/**
 * Investor profile self-authoring (investor.md M3). After KYC the investor fills
 * in their personal details and either submits them to their distributor
 * (POST /investor/profile/submit-to-distributor) or skips for now
 * (POST /investor/profile/skip). A prefill (GET /investor/profile/prefill)
 * surfaces the read-only identity already on record.
 */

interface Prefill {
  fullName?: string;
  pan?: string;
  email?: string;
  mobileNumber?: string;
  dateOfBirth?: string;
  linkingStatus?: string;
}

interface PersonalDetails {
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  occupation: string;
  incomeRange: string;
}

const INCOME_RANGES = ['Below 1L', '1-5L', '5-10L', '10-25L', 'Above 25L'];

const EMPTY: PersonalDetails = {
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  postalCode: '',
  occupation: '',
  incomeRange: '',
};

type Done = 'submitted' | 'skipped' | null;

export default function InvestorProfileForm() {
  const navigate = useNavigate();

  const [prefill, setPrefill] = useState<Prefill | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [form, setForm] = useState<PersonalDetails>(EMPTY);
  const [submitting, setSubmitting] = useState(false);
  const [skipping, setSkipping] = useState(false);
  const [actionError, setActionError] = useState('');
  const [done, setDone] = useState<Done>(null);

  const loadPrefill = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/profile/prefill');
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || `Unable to load your profile (${res.status}).`);
      }
      setPrefill((body as Prefill) ?? {});
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load your profile.');
      setPrefill(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadPrefill(); }, [loadPrefill]);

  const update = (k: keyof PersonalDetails) => (v: string) => {
    setForm(f => ({ ...f, [k]: v }));
    setActionError('');
  };

  const submitToDistributor = async () => {
    setSubmitting(true);
    setActionError('');
    try {
      const res = await apiFetch('/investor/profile/submit-to-distributor', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || 'Could not submit your profile.');
      }
      setDone('submitted');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not submit your profile.');
    } finally {
      setSubmitting(false);
    }
  };

  const skip = async () => {
    setSkipping(true);
    setActionError('');
    try {
      const res = await apiFetch('/investor/profile/skip', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || 'Could not skip for now.');
      }
      setDone('skipped');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not skip for now.');
    } finally {
      setSkipping(false);
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm text-slate-600">Loading your profile…</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <div className="flex items-start gap-2.5">
            <AlertCircle className="mt-0.5 h-5 w-5 text-red-500" />
            <div>
              <p className="font-semibold">Couldn't load your profile</p>
              <p className="mt-1 text-sm">{error}</p>
              <button onClick={() => loadPrefill()} className="mt-3 text-sm font-semibold text-red-600 hover:underline">Try again</button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (done) {
    const submitted = done === 'submitted';
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="rounded-2xl border border-slate-100 bg-white p-8 text-center shadow-sm">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100">
            <CheckCircle2 className="h-8 w-8 text-emerald-600" />
          </div>
          <h2 className="text-lg font-semibold text-slate-800">
            {submitted ? 'Profile submitted' : 'Saved for later'}
          </h2>
          <p className="mx-auto mt-1.5 max-w-md text-sm text-slate-500">
            {submitted
              ? 'Profile submitted — your distributor has been notified.'
              : 'You can complete this later or your distributor can fill it.'}
          </p>
          <button
            onClick={() => navigate('/investor/dashboard')}
            className="mx-auto mt-6 flex items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3 text-sm font-semibold text-white hover:bg-[#1A3066] transition-colors"
          >
            Go to dashboard <ArrowRight className="h-4 w-4" />
          </button>
        </div>
      </div>
    );
  }

  const inputCls =
    'w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100';
  const labelCls = 'mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500';

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-8">
      <div>
        <h1 className="text-2xl font-semibold text-slate-800">Complete your profile</h1>
        <p className="mt-1 text-sm text-slate-500">
          Add your personal details so your distributor can serve you better. All fields are optional, but completing them speeds things up.
        </p>
      </div>

      {/* Identity (read-only, from KYC) */}
      <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
        <div className="flex items-center gap-2 text-[10px] font-bold uppercase tracking-wider text-slate-400">
          <User className="h-3.5 w-3.5" /> Identity on record
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <div>
            <p className="text-[11px] font-medium uppercase tracking-wide text-slate-400">Name</p>
            <p className="mt-0.5 text-sm font-semibold text-slate-800">{prefill?.fullName || '—'}</p>
          </div>
          <div>
            <p className="text-[11px] font-medium uppercase tracking-wide text-slate-400">PAN</p>
            <p className="mt-0.5 font-mono text-sm font-semibold text-slate-800">{prefill?.pan || '—'}</p>
          </div>
          <div>
            <p className="text-[11px] font-medium uppercase tracking-wide text-slate-400">Email</p>
            <p className="mt-0.5 text-sm font-semibold text-slate-800 break-all">{prefill?.email || '—'}</p>
          </div>
        </div>
      </div>

      {/* Personal details form */}
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

      {actionError && (
        <div className="flex items-start gap-2 rounded-xl border border-amber-100 bg-amber-50 px-3.5 py-2.5">
          <ShieldAlert className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600" />
          <p className="text-xs text-amber-800">{actionError}</p>
        </div>
      )}

      {/* Terminal actions */}
      <div className="flex flex-col-reverse gap-3 sm:flex-row sm:items-center sm:justify-end">
        <button
          onClick={skip}
          disabled={skipping || submitting}
          className="flex items-center justify-center gap-2 rounded-xl border border-slate-200 px-5 py-3 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-60"
        >
          {skipping ? (<><Loader2 className="h-4 w-4 animate-spin" /> Skipping…</>) : 'Skip for now'}
        </button>
        <button
          onClick={submitToDistributor}
          disabled={submitting || skipping}
          className="flex items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-5 py-3 text-sm font-semibold text-white hover:bg-[#1A3066] transition-colors disabled:opacity-60"
        >
          {submitting ? (<><Loader2 className="h-4 w-4 animate-spin" /> Submitting…</>) : (<>Send to distributor <ArrowRight className="h-4 w-4" /></>)}
        </button>
      </div>
    </div>
  );
}
