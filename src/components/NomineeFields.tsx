import React, { useState } from 'react';
import { FileCheck2, Loader2, Trash2, Upload, User } from 'lucide-react';
import PincodeCityFields from './PincodeCityFields';
import { validateNomineeDocumentFile } from '../utils/investorDocuments';

// ── Shared luxe styling (matches InvestorOnboarding inp/sel) ───────────────────
const inp =
  'w-full px-3.5 py-2.5 text-sm bg-white border border-slate-200 rounded-xl focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';
const sel = inp + ' cursor-pointer';
const labelCls = 'block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5';
const lockedCls = ' bg-slate-100 cursor-not-allowed text-slate-500';

// ── Nominee model (field names match the BE investor_nominees columns) ─────────
export interface NomineeValue {
  fullName: string;
  dateOfBirth: string;
  relationship: string;
  sharePercent: string;
  mobileNumber: string;
  email: string;
  idType: string;
  idNumber: string;
  addressLine1: string;
  addressLine2: string;
  addressLine3: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
  sameAsApplicant: boolean;
  /** Required only when the nominee is a minor. */
  guardianName: string;
}

export interface ApplicantAddress {
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  country?: string;
}

export const emptyNominee = (): NomineeValue => ({
  fullName: '',
  dateOfBirth: '',
  relationship: '',
  sharePercent: '',
  mobileNumber: '',
  email: '',
  idType: '',
  idNumber: '',
  addressLine1: '',
  addressLine2: '',
  addressLine3: '',
  city: '',
  state: '',
  postalCode: '',
  country: 'India',
  sameAsApplicant: false,
  guardianName: '',
});

const RELATIONSHIP_OPTIONS = ['Spouse', 'Son', 'Daughter', 'Father', 'Mother', 'Brother', 'Sister', 'Other'];
const ID_TYPE_OPTIONS = ['PAN', 'Aadhaar', 'Passport', 'Voter ID', 'Driving Licence'];

interface Props {
  value: NomineeValue;
  index: number;
  onChange: (next: NomineeValue) => void;
  onRemove?: () => void;
  /** When omitted the "Same as applicant address" shortcut is hidden. */
  applicantAddress?: ApplicantAddress;
  /**
   * Fields already captured elsewhere (e.g. by the distributor) render read-only
   * with an "Already provided" hint; everything else stays editable.
   */
  readOnlyFields?: Array<keyof NomineeValue>;
  /**
   * When provided, renders an ID-document upload slot (client-side ≤5MB
   * pdf/jpg/png check). The promise should perform the actual upload.
   */
  onUploadDocument?: (file: File) => Promise<void>;
  /** Shows the "ID document uploaded" badge in the upload slot. */
  hasIdDocument?: boolean;
  /** Disables the upload input (e.g. while the nominee is unsaved). */
  uploadDisabled?: boolean;
}

// ═══════════════════════════════════════════════════════════════════════════════
// Renders a single nominee's fields. Field names are kept identical to the
// backend payload so the parent can serialise `nominees: [...]` directly.
export default function NomineeFields({
  value,
  index,
  onChange,
  onRemove,
  applicantAddress,
  readOnlyFields,
  onUploadDocument,
  hasIdDocument,
  uploadDisabled,
}: Props) {
  const [uploadBusy, setUploadBusy] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const [uploadedLocally, setUploadedLocally] = useState(false);

  const set = <K extends keyof NomineeValue>(key: K, next: NomineeValue[K]) =>
    onChange({ ...value, [key]: next });

  const locked = (key: keyof NomineeValue) => Boolean(readOnlyFields?.includes(key));
  const lockHint = (key: keyof NomineeValue) =>
    locked(key) ? <p className="mt-1 text-[10px] font-medium text-slate-400">Already provided</p> : null;

  const handleSameAsApplicant = (checked: boolean) => {
    if (checked && applicantAddress) {
      onChange({
        ...value,
        sameAsApplicant: true,
        addressLine1: applicantAddress.addressLine1 || '',
        addressLine2: applicantAddress.addressLine2 || '',
        addressLine3: '',
        city: applicantAddress.city || '',
        state: applicantAddress.state || '',
        postalCode: applicantAddress.postalCode || '',
        country: applicantAddress.country || 'India',
      });
    } else {
      onChange({ ...value, sameAsApplicant: false });
    }
  };

  // Only lock the address behind the checkbox when an applicant address is around
  // to copy from; the "already provided" per-field locks are handled separately.
  const addressLocked = value.sameAsApplicant && Boolean(applicantAddress);
  // Pincode/city/state lock together (single control group) — the pincode drives them.
  const pinGroupLocked = addressLocked || locked('postalCode');

  const handleUploadFile = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !onUploadDocument) return;
    const validationError = validateNomineeDocumentFile(file);
    if (validationError) {
      setUploadError(validationError);
      return;
    }
    setUploadBusy(true);
    setUploadError('');
    try {
      await onUploadDocument(file);
      setUploadedLocally(true);
    } catch (e) {
      setUploadError(e instanceof Error ? e.message : 'Could not upload the document. Please try again.');
    } finally {
      setUploadBusy(false);
    }
  };

  const documentUploaded = Boolean(hasIdDocument) || uploadedLocally;

  return (
    <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
      <div className="mb-4 flex items-center justify-between">
        <span className="flex items-center gap-2 text-sm font-semibold text-slate-700">
          <User className="h-4 w-4 text-slate-400" />
          Nominee {index + 1}
        </span>
        {onRemove && (
          <button
            type="button"
            onClick={onRemove}
            className="inline-flex items-center gap-1.5 rounded-lg border border-red-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-red-600 transition-colors hover:bg-red-50"
          >
            <Trash2 className="h-3.5 w-3.5" />
            Remove
          </button>
        )}
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className={labelCls}>Full Name</label>
          <input
            value={value.fullName}
            onChange={e => set('fullName', e.target.value)}
            placeholder="Full legal name"
            disabled={locked('fullName')}
            className={inp + (locked('fullName') ? lockedCls : '')}
          />
          {lockHint('fullName')}
        </div>
        <div>
          <label className={labelCls}>Date of Birth</label>
          <input
            type="date"
            value={value.dateOfBirth}
            onChange={e => set('dateOfBirth', e.target.value)}
            disabled={locked('dateOfBirth')}
            className={inp + (locked('dateOfBirth') ? lockedCls : '')}
          />
          {lockHint('dateOfBirth')}
        </div>
        <div>
          <label className={labelCls}>Relationship</label>
          <select
            value={value.relationship}
            onChange={e => set('relationship', e.target.value)}
            disabled={locked('relationship')}
            className={sel + (locked('relationship') ? lockedCls : '')}
          >
            <option value="">Select</option>
            {RELATIONSHIP_OPTIONS.map(r => (
              <option key={r} value={r}>{r}</option>
            ))}
          </select>
          {lockHint('relationship')}
        </div>
        <div>
          <label className={labelCls}>Share %</label>
          <input
            value={value.sharePercent}
            onChange={e => set('sharePercent', e.target.value.replace(/[^\d.]/g, '').slice(0, 6))}
            placeholder="e.g. 50"
            inputMode="decimal"
            disabled={locked('sharePercent')}
            className={inp + (locked('sharePercent') ? lockedCls : '')}
          />
          {lockHint('sharePercent')}
        </div>
        <div>
          <label className={labelCls}>Mobile Number</label>
          <input
            value={value.mobileNumber}
            onChange={e => set('mobileNumber', e.target.value.replace(/\D/g, '').slice(0, 13))}
            placeholder="9876543210"
            disabled={locked('mobileNumber')}
            className={inp + (locked('mobileNumber') ? lockedCls : '')}
          />
          {lockHint('mobileNumber')}
        </div>
        <div>
          <label className={labelCls}>Email</label>
          <input
            type="email"
            value={value.email}
            onChange={e => set('email', e.target.value)}
            placeholder="nominee@email.com"
            disabled={locked('email')}
            className={inp + (locked('email') ? lockedCls : '')}
          />
          {lockHint('email')}
        </div>
        <div>
          <label className={labelCls}>ID Type</label>
          <select
            value={value.idType}
            onChange={e => set('idType', e.target.value)}
            disabled={locked('idType')}
            className={sel + (locked('idType') ? lockedCls : '')}
          >
            <option value="">Select ID type</option>
            {ID_TYPE_OPTIONS.map(t => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
          {lockHint('idType')}
        </div>
        <div>
          <label className={labelCls}>ID Number</label>
          <input
            value={value.idNumber}
            onChange={e => set('idNumber', e.target.value.toUpperCase())}
            placeholder="ID document number"
            disabled={locked('idNumber')}
            className={inp + ' font-mono' + (locked('idNumber') ? lockedCls : '')}
          />
          {lockHint('idNumber')}
        </div>
        <div className="col-span-2">
          <label className={labelCls}>Guardian Name (if minor)</label>
          <input
            value={value.guardianName}
            onChange={e => set('guardianName', e.target.value)}
            placeholder="Required only when the nominee is a minor"
            disabled={locked('guardianName')}
            className={inp + (locked('guardianName') ? lockedCls : '')}
          />
          {lockHint('guardianName')}
        </div>
      </div>

      {/* Address */}
      <div className="mt-4 border-t border-slate-200 pt-4">
        {applicantAddress && (
          <label className="flex cursor-pointer items-center gap-2.5">
            <input
              type="checkbox"
              checked={value.sameAsApplicant}
              onChange={e => handleSameAsApplicant(e.target.checked)}
              className="h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
            />
            <span className="text-sm font-medium text-slate-700">Same as applicant address</span>
          </label>
        )}

        <div className="mt-4 grid grid-cols-2 gap-4">
          <div>
            <label className={labelCls}>Address Line 1</label>
            <input
              value={value.addressLine1}
              onChange={e => set('addressLine1', e.target.value)}
              placeholder="House / flat, street"
              disabled={addressLocked || locked('addressLine1')}
              className={inp + (addressLocked || locked('addressLine1') ? lockedCls : '')}
            />
            {lockHint('addressLine1')}
          </div>
          <div>
            <label className={labelCls}>Address Line 2</label>
            <input
              value={value.addressLine2}
              onChange={e => set('addressLine2', e.target.value)}
              placeholder="Area, landmark (optional)"
              disabled={addressLocked || locked('addressLine2')}
              className={inp + (addressLocked || locked('addressLine2') ? lockedCls : '')}
            />
            {lockHint('addressLine2')}
          </div>
          <div>
            <label className={labelCls}>Address Line 3</label>
            <input
              value={value.addressLine3}
              onChange={e => set('addressLine3', e.target.value)}
              placeholder="Optional"
              disabled={addressLocked || locked('addressLine3')}
              className={inp + (addressLocked || locked('addressLine3') ? lockedCls : '')}
            />
            {lockHint('addressLine3')}
          </div>
          <div>
            <label className={labelCls}>Country</label>
            <input
              value={value.country}
              onChange={e => set('country', e.target.value)}
              placeholder="India"
              disabled={addressLocked || locked('country')}
              className={inp + (addressLocked || locked('country') ? lockedCls : '')}
            />
            {lockHint('country')}
          </div>
          <PincodeCityFields
            postalCode={value.postalCode}
            city={value.city}
            state={value.state}
            onPostalCodeChange={v => set('postalCode', v)}
            onLocationResolved={({ city, state }) => onChange({ ...value, city, state })}
            onCityChange={v => set('city', v)}
            onStateChange={v => set('state', v)}
            inputClassName={inp}
            selectClassName={sel}
            disabled={pinGroupLocked}
          />
        </div>
      </div>

      {/* Optional ID-document upload slot (rendered only when a handler is wired) */}
      {onUploadDocument && (
        <div className="mt-4 border-t border-slate-200 pt-4">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-xs font-bold uppercase tracking-wider text-slate-500">Nominee ID Document</span>
            {documentUploaded && (
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 px-2.5 py-1 text-[11px] font-semibold text-emerald-700">
                <FileCheck2 className="h-3.5 w-3.5" /> ID document uploaded
              </span>
            )}
          </div>
          <label
            className={`mt-2 inline-flex cursor-pointer items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 ${
              uploadDisabled || uploadBusy ? 'cursor-not-allowed opacity-50' : ''
            }`}
          >
            {uploadBusy ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Upload className="h-3.5 w-3.5" />}
            {uploadBusy ? 'Uploading…' : documentUploaded ? 'Replace document' : 'Upload document'}
            <input
              type="file"
              accept=".pdf,.jpg,.jpeg,.png"
              className="hidden"
              disabled={uploadDisabled || uploadBusy}
              onChange={e => void handleUploadFile(e)}
            />
          </label>
          <p className="mt-1.5 text-[11px] text-slate-400">PDF, JPG or PNG up to 5 MB.</p>
          {uploadError && <p className="mt-1 text-[11px] text-red-600">{uploadError}</p>}
        </div>
      )}
    </div>
  );
}
