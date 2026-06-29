import React from 'react';
import { Trash2, User } from 'lucide-react';
import PincodeCityFields from './PincodeCityFields';

// ── Shared luxe styling (matches InvestorOnboarding inp/sel) ───────────────────
const inp =
  'w-full px-3.5 py-2.5 text-sm bg-white border border-slate-200 rounded-xl focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';
const sel = inp + ' cursor-pointer';
const labelCls = 'block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5';

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
});

const RELATIONSHIP_OPTIONS = ['Spouse', 'Son', 'Daughter', 'Father', 'Mother', 'Brother', 'Sister', 'Other'];
const ID_TYPE_OPTIONS = ['PAN', 'Aadhaar', 'Passport', 'Voter ID', 'Driving Licence'];

interface Props {
  value: NomineeValue;
  index: number;
  onChange: (next: NomineeValue) => void;
  onRemove?: () => void;
  applicantAddress: ApplicantAddress;
}

// ═══════════════════════════════════════════════════════════════════════════════
// Renders a single nominee's fields. Field names are kept identical to the
// backend payload so the parent can serialise `nominees: [...]` directly.
export default function NomineeFields({ value, index, onChange, onRemove, applicantAddress }: Props) {
  const set = <K extends keyof NomineeValue>(key: K, next: NomineeValue[K]) =>
    onChange({ ...value, [key]: next });

  const handleSameAsApplicant = (checked: boolean) => {
    if (checked) {
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

  const addressLocked = value.sameAsApplicant;

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
            className={inp}
          />
        </div>
        <div>
          <label className={labelCls}>Date of Birth</label>
          <input
            type="date"
            value={value.dateOfBirth}
            onChange={e => set('dateOfBirth', e.target.value)}
            className={inp}
          />
        </div>
        <div>
          <label className={labelCls}>Relationship</label>
          <select value={value.relationship} onChange={e => set('relationship', e.target.value)} className={sel}>
            <option value="">Select</option>
            {RELATIONSHIP_OPTIONS.map(r => (
              <option key={r} value={r}>{r}</option>
            ))}
          </select>
        </div>
        <div>
          <label className={labelCls}>Share %</label>
          <input
            value={value.sharePercent}
            onChange={e => set('sharePercent', e.target.value.replace(/[^\d.]/g, '').slice(0, 6))}
            placeholder="e.g. 50"
            inputMode="decimal"
            className={inp}
          />
        </div>
        <div>
          <label className={labelCls}>Mobile Number</label>
          <input
            value={value.mobileNumber}
            onChange={e => set('mobileNumber', e.target.value.replace(/\D/g, '').slice(0, 13))}
            placeholder="9876543210"
            className={inp}
          />
        </div>
        <div>
          <label className={labelCls}>Email</label>
          <input
            type="email"
            value={value.email}
            onChange={e => set('email', e.target.value)}
            placeholder="nominee@email.com"
            className={inp}
          />
        </div>
        <div>
          <label className={labelCls}>ID Type</label>
          <select value={value.idType} onChange={e => set('idType', e.target.value)} className={sel}>
            <option value="">Select ID type</option>
            {ID_TYPE_OPTIONS.map(t => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>
        <div>
          <label className={labelCls}>ID Number</label>
          <input
            value={value.idNumber}
            onChange={e => set('idNumber', e.target.value.toUpperCase())}
            placeholder="ID document number"
            className={inp + ' font-mono'}
          />
        </div>
      </div>

      {/* Address */}
      <div className="mt-4 border-t border-slate-200 pt-4">
        <label className="flex cursor-pointer items-center gap-2.5">
          <input
            type="checkbox"
            checked={value.sameAsApplicant}
            onChange={e => handleSameAsApplicant(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
          />
          <span className="text-sm font-medium text-slate-700">Same as applicant address</span>
        </label>

        <div className="mt-4 grid grid-cols-2 gap-4">
          <div>
            <label className={labelCls}>Address Line 1</label>
            <input
              value={value.addressLine1}
              onChange={e => set('addressLine1', e.target.value)}
              placeholder="House / flat, street"
              disabled={addressLocked}
              className={inp + (addressLocked ? ' bg-slate-100 cursor-not-allowed' : '')}
            />
          </div>
          <div>
            <label className={labelCls}>Address Line 2</label>
            <input
              value={value.addressLine2}
              onChange={e => set('addressLine2', e.target.value)}
              placeholder="Area, landmark (optional)"
              disabled={addressLocked}
              className={inp + (addressLocked ? ' bg-slate-100 cursor-not-allowed' : '')}
            />
          </div>
          <div>
            <label className={labelCls}>Address Line 3</label>
            <input
              value={value.addressLine3}
              onChange={e => set('addressLine3', e.target.value)}
              placeholder="Optional"
              disabled={addressLocked}
              className={inp + (addressLocked ? ' bg-slate-100 cursor-not-allowed' : '')}
            />
          </div>
          <div>
            <label className={labelCls}>Country</label>
            <input
              value={value.country}
              onChange={e => set('country', e.target.value)}
              placeholder="India"
              disabled={addressLocked}
              className={inp + (addressLocked ? ' bg-slate-100 cursor-not-allowed' : '')}
            />
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
            disabled={addressLocked}
          />
        </div>
      </div>
    </div>
  );
}
