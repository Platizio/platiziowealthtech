import { useCallback, useEffect, useState, type ChangeEvent } from 'react';
import {
  Loader2, AlertCircle, Users, UserPlus, ShieldCheck, X, CheckCircle2, Ban, Plus,
  FileCheck2, Upload, Pencil,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import NomineeFields, { emptyNominee, type NomineeValue } from './NomineeFields';
import { uploadNomineeDocument, validateNomineeDocumentFile } from '../utils/investorDocuments';

/**
 * Shared investor nominee management (used by the standalone /investor/nominations
 * page AND the "Nominee details" step at the end of the onboarding review).
 *
 * Lists the investor's nominees (GET /investor/nominations — rich shape, one row
 * per nominee), lets them add a nominee (POST /investor/nominations) or complete
 * a partially-captured one (PUT /investor/nominations/{id}), upload the nominee's
 * ID document (PUT /investor/nominations/{id}/document), and offers a clearly-
 * separate "I choose not to nominate" opt-out (POST /investor/nominations/opt-out).
 *
 * Distributor-captured nominees arrive pre-filled from the same GET: when
 * completing one, every field that already has a value renders read-only
 * ("Already provided") and only the gaps stay editable.
 *
 * Compliance: NO pre-checked boxes and NO defaulted values — the relationship
 * select starts empty with a required validation, and nothing is opted in/out
 * until the investor acts.
 */

/** Mirrors the BE nomination row (GET /investor/nominations). All optional so a
 *  lagging backend degrades gracefully. */
export interface RichNominee {
  id?: string;
  investorId?: string;
  nomineeIndex?: number;
  fullName?: string | null;
  relationship?: string | null;
  dateOfBirth?: string | null;
  sharePercent?: number | null;
  mobileNumber?: string | null;
  email?: string | null;
  idType?: string | null;
  idNumber?: string | null;
  addressLine1?: string | null;
  addressLine2?: string | null;
  addressLine3?: string | null;
  city?: string | null;
  state?: string | null;
  postalCode?: string | null;
  country?: string | null;
  sameAsApplicant?: boolean | null;
  guardianName?: string | null;
  hasIdDocument?: boolean;
}

const dateOnly = (value?: string | null) => (value ? String(value).slice(0, 10) : '');

const toFormValue = (n?: RichNominee | null): NomineeValue => ({
  ...emptyNominee(),
  fullName: n?.fullName || '',
  dateOfBirth: dateOnly(n?.dateOfBirth),
  relationship: n?.relationship || '',
  sharePercent: n?.sharePercent != null ? String(n.sharePercent) : '',
  mobileNumber: n?.mobileNumber || '',
  email: n?.email || '',
  idType: n?.idType || '',
  idNumber: n?.idNumber || '',
  addressLine1: n?.addressLine1 || '',
  addressLine2: n?.addressLine2 || '',
  addressLine3: n?.addressLine3 || '',
  city: n?.city || '',
  state: n?.state || '',
  postalCode: n?.postalCode || '',
  country: n?.country || 'India',
  sameAsApplicant: Boolean(n?.sameAsApplicant),
  guardianName: n?.guardianName || '',
});

/** String-valued NomineeValue keys that can be pre-filled (and therefore locked). */
const PREFILLABLE_KEYS = [
  'fullName', 'dateOfBirth', 'relationship', 'sharePercent', 'mobileNumber', 'email',
  'idType', 'idNumber', 'addressLine1', 'addressLine2', 'addressLine3',
  'city', 'state', 'postalCode', 'country', 'guardianName',
] as const;

const lockedFieldsFor = (n: RichNominee): Array<keyof NomineeValue> => {
  const form = toFormValue(n);
  return PREFILLABLE_KEYS.filter(key => String(form[key] ?? '').trim() !== '');
};

const hasMissingFields = (n: RichNominee): boolean =>
  lockedFieldsFor(n).length < PREFILLABLE_KEYS.length;

/** A display row inside a nominee card (— when the value was never captured). */
function CardRow({ label, value }: { label: string; value?: string | null }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="flex-shrink-0">{label}</dt>
      <dd className={`truncate text-right ${value ? 'text-slate-700' : 'text-slate-300'}`}>{value || '—'}</dd>
    </div>
  );
}

export default function InvestorNomineeManager() {
  const [nominees, setNominees] = useState<RichNominee[]>([]);
  const [optedOut, setOptedOut] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Add / complete editor
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingNominee, setEditingNominee] = useState<RichNominee | null>(null);
  const [form, setForm] = useState<NomineeValue>(emptyNominee());
  const [readOnlyFields, setReadOnlyFields] = useState<Array<keyof NomineeValue>>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');

  // Opt-out
  const [showOptOut, setShowOptOut] = useState(false);
  const [optingOut, setOptingOut] = useState(false);
  const [optOutError, setOptOutError] = useState('');

  // Per-card ID-document uploads
  const [uploadingId, setUploadingId] = useState('');
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadErrors, setUploadErrors] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/nominations');
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || `Unable to load nominees (${res.status}).`);
      // New wire shape is a plain array; tolerate the old {nominees, optedOut} envelope.
      const list: RichNominee[] = Array.isArray(body)
        ? body
        : Array.isArray((body as { nominees?: RichNominee[] } | null)?.nominees)
          ? ((body as { nominees?: RichNominee[] }).nominees as RichNominee[])
          : [];
      setNominees(list);
      if (!Array.isArray(body) && (body as { optedOut?: boolean } | null)?.optedOut !== undefined) {
        setOptedOut((body as { optedOut?: boolean }).optedOut === true);
      } else if (list.length > 0) {
        setOptedOut(false);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load nominees.');
      setNominees([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const allocated = nominees.reduce((sum, n) => sum + (Number(n.sharePercent) || 0), 0);
  const remaining = Math.max(0, 100 - allocated);

  const openAdd = () => {
    setEditingNominee(null);
    setForm(emptyNominee());
    setReadOnlyFields([]);
    setFormError('');
    setEditorOpen(true);
  };

  const openComplete = (n: RichNominee) => {
    setEditingNominee(n);
    setForm(toFormValue(n));
    setReadOnlyFields(lockedFieldsFor(n));
    setFormError('');
    setEditorOpen(true);
  };

  const closeEditor = () => {
    setEditorOpen(false);
    setEditingNominee(null);
    setFormError('');
  };

  const saveNominee = async () => {
    if (!form.fullName.trim()) { setFormError('Enter the nominee’s full name.'); return; }
    if (!form.relationship) { setFormError('Select the relationship.'); return; }
    const pct = Number(form.sharePercent);
    if (!Number.isFinite(pct) || pct < 1 || pct > 100) { setFormError('Enter a share between 1 and 100.'); return; }
    // Exclude the nominee being completed from the total it is validated against.
    const othersAllocated = nominees
      .filter(n => !editingNominee || n.id !== editingNominee.id)
      .reduce((sum, n) => sum + (Number(n.sharePercent) || 0), 0);
    if (othersAllocated + pct > 100) {
      setFormError(`Share exceeds 100%. Only ${Math.max(0, 100 - othersAllocated)}% remains.`);
      return;
    }

    setSaving(true);
    setFormError('');
    try {
      // POST creates a brand-new nominee only; completing/updating an EXISTING
      // one must PUT /investor/nominations/{id} with the merged full object —
      // re-POSTing an existing nominee would create a duplicate row.
      const url = editingNominee?.id
        ? `/investor/nominations/${encodeURIComponent(editingNominee.id)}`
        : '/investor/nominations';
      const res = await apiFetch(url, {
        method: editingNominee?.id ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fullName: form.fullName.trim(),
          relationship: form.relationship,
          sharePercent: pct,
          dateOfBirth: form.dateOfBirth || null,
          mobileNumber: form.mobileNumber.trim() || null,
          email: form.email.trim() || null,
          idType: form.idType || null,
          idNumber: form.idNumber.trim() || null,
          addressLine1: form.addressLine1.trim() || null,
          addressLine2: form.addressLine2.trim() || null,
          addressLine3: form.addressLine3.trim() || null,
          city: form.city.trim() || null,
          state: form.state.trim() || null,
          postalCode: form.postalCode.trim() || null,
          country: form.country.trim() || null,
          sameAsApplicant: Boolean(form.sameAsApplicant),
          guardianName: form.guardianName.trim() || null,
        }),
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not save the nominee.');
      closeEditor();
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Could not save the nominee.');
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
      setOptedOut(true);
      await load();
    } catch (e) {
      setOptOutError(e instanceof Error ? e.message : 'Could not record your choice.');
    } finally {
      setOptingOut(false);
    }
  };

  const uploadDocumentFor = async (nomineeId: string, file: File) => {
    setUploadingId(nomineeId);
    setUploadProgress(0);
    setUploadErrors(prev => ({ ...prev, [nomineeId]: '' }));
    try {
      await uploadNomineeDocument(nomineeId, file, setUploadProgress);
      setNominees(prev => prev.map(n => (n.id === nomineeId ? { ...n, hasIdDocument: true } : n)));
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Could not upload the document. Please try again.';
      setUploadErrors(prev => ({ ...prev, [nomineeId]: message }));
      throw e instanceof Error ? e : new Error(message);
    } finally {
      setUploadingId('');
    }
  };

  const handleCardUpload = (n: RichNominee, event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !n.id) return;
    const validationError = validateNomineeDocumentFile(file);
    if (validationError) {
      setUploadErrors(prev => ({ ...prev, [n.id as string]: validationError }));
      return;
    }
    void uploadDocumentFor(n.id, file).catch(() => { /* surfaced via uploadErrors */ });
  };

  const addressText = (n: RichNominee) =>
    [n.addressLine1, n.addressLine2, n.addressLine3, n.city, n.state, n.postalCode, n.country]
      .map(part => String(part || '').trim())
      .filter(Boolean)
      .join(', ');

  return (
    <div className="space-y-5">
      {/* Allocation summary */}
      {!optedOut && nominees.length > 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-blue-100 bg-blue-50/60 px-4 py-3">
          <ShieldCheck className="h-4 w-4 flex-shrink-0 text-blue-600" />
          <p className="min-w-[200px] flex-1 text-sm text-blue-900">
            Allocated <span className="font-semibold">{allocated}%</span>
            {remaining > 0 && <> · <span className="font-semibold">{remaining}%</span> still available</>}
          </p>
          {nominees.length < 3 && (
            <button
              onClick={openAdd}
              className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-lg border border-blue-200 bg-white px-3 py-1.5 text-xs font-semibold text-blue-700 hover:bg-blue-50"
            >
              <UserPlus className="h-3.5 w-3.5" /> Add nominee
            </button>
          )}
        </div>
      )}

      {loading ? (
        <div className="flex min-h-[20vh] items-center justify-center">
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
      ) : optedOut && nominees.length === 0 ? (
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
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
          {nominees.map((n, i) => {
            const nomineeId = n.id || '';
            const uploading = uploadingId !== '' && uploadingId === nomineeId;
            const uploadError = nomineeId ? uploadErrors[nomineeId] : '';
            return (
              <div key={nomineeId || i} className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-slate-800">{n.fullName || 'Nominee'}</p>
                    <p className="mt-0.5 text-xs text-slate-500">{n.relationship || '—'}</p>
                  </div>
                  <span className="flex-shrink-0 rounded-lg bg-blue-50 px-2.5 py-1 text-xs font-bold text-blue-700">
                    {Number.isFinite(Number(n.sharePercent)) && n.sharePercent != null ? `${n.sharePercent}%` : '—'}
                  </span>
                </div>

                <dl className="mt-3 space-y-1 text-xs text-slate-500">
                  <CardRow label="Date of birth" value={dateOnly(n.dateOfBirth)} />
                  <CardRow label="Mobile" value={n.mobileNumber} />
                  <CardRow label="Email" value={n.email} />
                  <CardRow label="ID" value={n.idType ? `${n.idType}${n.idNumber ? ` · ${n.idNumber}` : ''}` : n.idNumber} />
                  <CardRow label="Address" value={addressText(n)} />
                  <CardRow label="Guardian" value={n.guardianName} />
                </dl>

                <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
                  {n.hasIdDocument && (
                    <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700">
                      <FileCheck2 className="h-3.5 w-3.5" /> ID document uploaded
                    </span>
                  )}
                  {nomineeId && (
                    <label
                      className={`inline-flex cursor-pointer items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition-colors hover:bg-slate-50 ${uploading ? 'cursor-not-allowed opacity-50' : ''}`}
                    >
                      {uploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
                      {uploading ? `Uploading… ${uploadProgress}%` : n.hasIdDocument ? 'Replace ID document' : 'Upload ID document'}
                      <input
                        type="file"
                        accept=".pdf,.jpg,.jpeg,.png"
                        className="hidden"
                        disabled={uploading}
                        onChange={e => handleCardUpload(n, e)}
                      />
                    </label>
                  )}
                  {hasMissingFields(n) && (
                    <button
                      onClick={() => openComplete(n)}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-blue-200 bg-blue-50 px-2.5 py-1.5 text-[11px] font-semibold text-blue-700 transition-colors hover:bg-blue-100"
                    >
                      <Pencil className="h-3.5 w-3.5" /> Complete details
                    </button>
                  )}
                </div>
                {uploadError && <p className="mt-2 text-[11px] text-red-600">{uploadError}</p>}
              </div>
            );
          })}
        </div>
      )}

      {/* Add / complete editor */}
      {editorOpen && (
        <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="mb-4 flex items-start justify-between gap-3">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Nominee</p>
              <p className="text-sm font-semibold text-slate-800">
                {editingNominee ? `Complete details for ${editingNominee.fullName || 'this nominee'}` : 'Add a nominee'}
              </p>
              {editingNominee && (
                <p className="mt-0.5 text-xs text-slate-500">
                  Fields already captured are shown read-only — fill in only what’s missing.
                </p>
              )}
            </div>
            <button onClick={closeEditor} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100"><X className="h-4 w-4" /></button>
          </div>

          <NomineeFields
            value={form}
            index={editingNominee ? nominees.findIndex(n => n.id === editingNominee.id) : nominees.length}
            onChange={setForm}
            readOnlyFields={readOnlyFields}
            onUploadDocument={editingNominee?.id ? file => uploadDocumentFor(editingNominee.id as string, file) : undefined}
            hasIdDocument={editingNominee ? Boolean(nominees.find(n => n.id === editingNominee.id)?.hasIdDocument) : false}
          />

          {formError && <p className="mt-3 text-xs text-red-600">{formError}</p>}

          <div className="mt-4 flex gap-3">
            <button
              onClick={closeEditor}
              disabled={saving}
              className="flex-1 rounded-xl border border-slate-200 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              onClick={() => void saveNominee()}
              disabled={saving}
              className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066] disabled:opacity-60"
            >
              {saving
                ? (<><Loader2 className="h-4 w-4 animate-spin" /> Saving…</>)
                : (<><UserPlus className="h-4 w-4" /> {editingNominee ? 'Save details' : 'Add nominee'}</>)}
            </button>
          </div>
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
                onClick={() => void confirmOptOut()}
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
