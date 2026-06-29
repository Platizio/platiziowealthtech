import { useCallback, useEffect, useState } from 'react';
import {
  Loader2, AlertCircle, Users, UserPlus, ShieldCheck, X, CheckCircle2, Ban, Plus,
} from 'lucide-react';
import { apiFetch } from '../config/api';

/**
 * Investor "Nominees" page (REQUIREMENT #4). Lists the investor's nominees
 * (GET /investor/nominations), lets them add a nominee (POST /investor/nominations),
 * and offers a clearly-separate "I choose not to nominate" opt-out
 * (POST /investor/nominations/opt-out) behind a confirmation.
 *
 * Compliance: NO pre-checked boxes and NO defaulted values — the relationship
 * select starts empty with a required validation, and nothing is opted in/out
 * until the investor acts. Styled to match InvestorInvest.tsx.
 */

interface Nominee {
  id?: string;
  fullName?: string;
  relationship?: string;
  dateOfBirth?: string | null;
  allocationPercentage?: number | null;
  addressLine?: string | null;
  guardianName?: string | null;
}

interface NominationsResponse {
  nominees?: Nominee[];
  optedOut?: boolean;
}

// Empty first option forces an explicit choice (no defaulted relationship).
const RELATIONSHIP_OPTIONS = [
  'Spouse', 'Son', 'Daughter', 'Father', 'Mother', 'Brother', 'Sister', 'Other',
];

const emptyForm = {
  fullName: '',
  relationship: '',
  dateOfBirth: '',
  allocationPercentage: '',
  addressLine: '',
  guardianName: '',
};

export default function InvestorNominees() {
  const [nominees, setNominees] = useState<Nominee[]>([]);
  const [optedOut, setOptedOut] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [showAdd, setShowAdd] = useState(false);
  const [form, setForm] = useState({ ...emptyForm });
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');

  const [showOptOut, setShowOptOut] = useState(false);
  const [optingOut, setOptingOut] = useState(false);
  const [optOutError, setOptOutError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/nominations');
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || `Unable to load nominees (${res.status}).`);
      const data = (body as NominationsResponse | null) ?? {};
      setNominees(Array.isArray(data.nominees) ? data.nominees : []);
      setOptedOut(data.optedOut === true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load nominees.');
      setNominees([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const allocated = nominees.reduce((sum, n) => sum + (Number(n.allocationPercentage) || 0), 0);
  const remaining = Math.max(0, 100 - allocated);

  const openAdd = () => { setForm({ ...emptyForm }); setFormError(''); setShowAdd(true); };

  const addNominee = async () => {
    if (!form.fullName.trim()) { setFormError('Enter the nominee’s full name.'); return; }
    if (!form.relationship) { setFormError('Select the relationship.'); return; }
    const pct = Number(form.allocationPercentage);
    if (!Number.isFinite(pct) || pct < 1 || pct > 100) { setFormError('Enter an allocation between 1 and 100.'); return; }
    if (allocated + pct > 100) { setFormError(`Allocation exceeds 100%. Only ${remaining}% remains.`); return; }

    setSaving(true);
    setFormError('');
    try {
      const res = await apiFetch('/investor/nominations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fullName: form.fullName.trim(),
          relationship: form.relationship,
          dateOfBirth: form.dateOfBirth || null,
          allocationPercentage: pct,
          addressLine: form.addressLine.trim() || null,
          guardianName: form.guardianName.trim() || null,
        }),
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not add the nominee.');
      setShowAdd(false);
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Could not add the nominee.');
    } finally {
      setSaving(false);
    }
  };

  const confirmOptOut = async () => {
    setOptingOut(true);
    setOptOutError('');
    try {
      const res = await apiFetch('/investor/nominations/opt-out', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not record your choice.');
      setShowOptOut(false);
      await load();
    } catch (e) {
      setOptOutError(e instanceof Error ? e.message : 'Could not record your choice.');
    } finally {
      setOptingOut(false);
    }
  };

  return (
    <div className="mx-auto max-w-5xl space-y-6 p-8">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Nominees</h1>
          <p className="mt-1 text-sm text-slate-500">
            Nominate who should receive your investments. You can add one or more nominees
            (allocations totalling 100%), or choose not to nominate.
          </p>
        </div>
        <button
          onClick={openAdd}
          className="flex flex-shrink-0 items-center gap-1.5 rounded-xl bg-[#0B1B3E] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066]"
        >
          <UserPlus className="h-4 w-4" /> Add nominee
        </button>
      </div>

      {/* Allocation summary */}
      {!optedOut && nominees.length > 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-blue-100 bg-blue-50/60 px-4 py-3">
          <ShieldCheck className="h-4 w-4 flex-shrink-0 text-blue-600" />
          <p className="min-w-[200px] flex-1 text-sm text-blue-900">
            Allocated <span className="font-semibold">{allocated}%</span>
            {remaining > 0 && <> · <span className="font-semibold">{remaining}%</span> still available</>}
          </p>
        </div>
      )}

      {loading ? (
        <div className="flex min-h-[40vh] items-center justify-center">
          <div className="text-center">
            <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
            <p className="mt-3 text-sm text-slate-600">Loading nominees…</p>
          </div>
        </div>
      ) : error ? (
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <div className="flex items-start gap-2.5">
            <AlertCircle className="mt-0.5 h-5 w-5 text-red-500" />
            <div>
              <p className="font-semibold">Couldn’t load nominees</p>
              <p className="mt-1 text-sm">{error}</p>
              <button onClick={() => void load()} className="mt-3 text-sm font-semibold text-red-600 hover:underline">Try again</button>
            </div>
          </div>
        </div>
      ) : optedOut ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm">
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-slate-100">
            <Ban className="h-6 w-6 text-slate-500" />
          </div>
          <p className="text-sm font-semibold text-slate-800">You chose not to nominate</p>
          <p className="mx-auto mt-1 max-w-md text-xs text-slate-500">
            You opted out of nomination. You can change your mind any time by adding a nominee below.
          </p>
          <button onClick={openAdd} className="mt-5 inline-flex items-center gap-1.5 rounded-xl bg-[#0B1B3E] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066]">
            <Plus className="h-4 w-4" /> Add a nominee instead
          </button>
        </div>
      ) : nominees.length === 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm">
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-blue-50">
            <Users className="h-6 w-6 text-blue-600" />
          </div>
          <p className="text-sm font-semibold text-slate-800">No nominees added yet</p>
          <p className="mx-auto mt-1 max-w-md text-xs text-slate-500">
            Add the people who should receive your investments, or let us know you’d rather not nominate.
          </p>
          <div className="mt-5 flex flex-wrap items-center justify-center gap-3">
            <button onClick={openAdd} className="inline-flex items-center gap-1.5 rounded-xl bg-[#0B1B3E] px-4 py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066]">
              <UserPlus className="h-4 w-4" /> Add nominee
            </button>
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {nominees.map((n, i) => (
            <div key={n.id || i} className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-slate-800">{n.fullName || 'Nominee'}</p>
                  <p className="mt-0.5 text-xs text-slate-500">{n.relationship || '—'}</p>
                </div>
                <span className="flex-shrink-0 rounded-lg bg-blue-50 px-2.5 py-1 text-xs font-bold text-blue-700">
                  {Number.isFinite(Number(n.allocationPercentage)) ? `${n.allocationPercentage}%` : '—'}
                </span>
              </div>
              <dl className="mt-3 space-y-1 text-xs text-slate-500">
                {n.dateOfBirth && <div className="flex justify-between gap-3"><dt>Date of birth</dt><dd className="text-slate-700">{n.dateOfBirth}</dd></div>}
                {n.guardianName && <div className="flex justify-between gap-3"><dt>Guardian</dt><dd className="text-slate-700">{n.guardianName}</dd></div>}
                {n.addressLine && <div className="flex justify-between gap-3"><dt>Address</dt><dd className="truncate text-slate-700">{n.addressLine}</dd></div>}
              </dl>
            </div>
          ))}
        </div>
      )}

      {/* Clearly-separate opt-out action */}
      {!loading && !error && !optedOut && (
        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
          <Ban className="h-4 w-4 flex-shrink-0 text-slate-400" />
          <p className="min-w-[200px] flex-1 text-sm text-slate-600">
            Prefer not to nominate anyone right now?
          </p>
          <button
            onClick={() => { setOptOutError(''); setShowOptOut(true); }}
            disabled={nominees.length > 0}
            title={nominees.length > 0 ? 'Remove your nominees first to opt out.' : undefined}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-50"
          >
            I choose not to nominate
          </button>
        </div>
      )}

      {/* Add nominee modal */}
      {showAdd && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Nominee</p>
                <p className="text-sm font-semibold text-slate-800">Add a nominee</p>
              </div>
              <button onClick={() => setShowAdd(false)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100"><X className="h-4 w-4" /></button>
            </div>

            <div className="mt-5 space-y-3">
              <div>
                <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">Full name</label>
                <input
                  value={form.fullName}
                  onChange={e => { setForm(f => ({ ...f, fullName: e.target.value })); setFormError(''); }}
                  placeholder="Full legal name"
                  className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                />
              </div>

              <div>
                <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">Relationship</label>
                <select
                  value={form.relationship}
                  onChange={e => { setForm(f => ({ ...f, relationship: e.target.value })); setFormError(''); }}
                  className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                >
                  <option value="" disabled>Select relationship…</option>
                  {RELATIONSHIP_OPTIONS.map(r => <option key={r} value={r}>{r}</option>)}
                </select>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">Date of birth</label>
                  <input
                    type="date"
                    value={form.dateOfBirth}
                    onChange={e => setForm(f => ({ ...f, dateOfBirth: e.target.value }))}
                    className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">Allocation %</label>
                  <input
                    type="number"
                    min={1}
                    max={100}
                    value={form.allocationPercentage}
                    onChange={e => { setForm(f => ({ ...f, allocationPercentage: e.target.value })); setFormError(''); }}
                    placeholder={remaining ? `up to ${remaining}` : 'e.g. 50'}
                    className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                  />
                </div>
              </div>

              <div>
                <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">Address (optional)</label>
                <input
                  value={form.addressLine}
                  onChange={e => setForm(f => ({ ...f, addressLine: e.target.value }))}
                  placeholder="Nominee’s address"
                  className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                />
              </div>

              <div>
                <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">Guardian name (if minor, optional)</label>
                <input
                  value={form.guardianName}
                  onChange={e => setForm(f => ({ ...f, guardianName: e.target.value }))}
                  placeholder="Required only when the nominee is a minor"
                  className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                />
              </div>

              {formError && <p className="text-xs text-red-600">{formError}</p>}

              <button
                onClick={addNominee}
                disabled={saving}
                className="mt-1 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white hover:bg-[#1A3066] disabled:opacity-60"
              >
                {saving ? (<><Loader2 className="h-4 w-4 animate-spin" /> Adding…</>) : (<><UserPlus className="h-4 w-4" /> Add nominee</>)}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Opt-out confirmation modal */}
      {showOptOut && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Confirm</p>
                <p className="text-sm font-semibold text-slate-800">Choose not to nominate</p>
              </div>
              <button onClick={() => setShowOptOut(false)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100"><X className="h-4 w-4" /></button>
            </div>
            <div className="mt-4 flex items-start gap-2.5 rounded-xl border border-amber-100 bg-amber-50 px-3 py-3">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600" />
              <p className="text-xs text-amber-800">
                I choose not to nominate anyone for my investment account at this time. I understand I can add a nominee later.
              </p>
            </div>
            {optOutError && <p className="mt-3 text-xs text-red-600">{optOutError}</p>}
            <div className="mt-5 flex gap-3">
              <button
                onClick={() => setShowOptOut(false)}
                className="flex-1 rounded-xl border border-slate-200 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                onClick={confirmOptOut}
                disabled={optingOut}
                className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066] disabled:opacity-60"
              >
                {optingOut ? (<><Loader2 className="h-4 w-4 animate-spin" /> Saving…</>) : (<><CheckCircle2 className="h-4 w-4" /> Confirm</>)}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
