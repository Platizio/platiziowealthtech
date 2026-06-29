import React, { useEffect, useState } from 'react';
import { Send, X, AlertCircle, ShieldCheck, Loader2, Building2, User } from 'lucide-react';
import {
  submitDistributorProfile,
  updateDistributorProfile,
  type DistributorProfileSubmitResponse,
} from '../config/api';
import PincodeCityFields from './PincodeCityFields';
import NomineeFields, { emptyNominee, type NomineeValue } from './NomineeFields';
import { validateInvestorMinimumAge } from '../utils/kycActionLocks';
import { fetchIfscDetails } from '../utils/referenceLookup';

/**
 * Distributor skip-form fill (investor.md R10 + IRIS P2).
 *
 * When an investor approved the persona link but skipped the onboarding profile
 * (linking_status INVESTOR_SKIPPED — or the distributor already started filling it,
 * DISTRIBUTOR_FILLING), the distributor completes the profile on the investor's behalf.
 *
 * P2: this now captures the FULL IRIS field set — exactly the same fields the investor
 * self-fill wizard (InvestorOnboarding) sends via `buildInvestorPayload`: mode-of-holding,
 * category, gender, country of birth/citizenship, source of wealth, PEP + Relative split,
 * annual income, occupation, bank (account type / IFSC / account number) and up to 3
 * nominees. The collected fields are JSON-stringified into `payloadJson` (with the SAME
 * field names as buildInvestorPayload so the BE freeze/apply-back machinery round-trips
 * them) and POSTed to /investors/{id}/profile/submit (first time) or PUT
 * /investors/{id}/profile (editing the still-pending fill). The acting distributor is
 * resolved server-side from the session — never sent in the body. On success the investor
 * must approve the frozen details via 2FA, so we surface the resulting challengeId.
 */

const IFSC_REGEX = /^[A-Z]{4}0[A-Z0-9]{6}$/;

// Option lists — mirror InvestorOnboarding's selects verbatim so the captured values
// are identical to the investor self-fill payload.
const GENDER_OPTIONS = ['Male', 'Female', 'Other', 'Prefer not to say'];
const OCCUPATION_OPTIONS = ['Salaried', 'Self-Employed', 'Business Owner', 'Retired', 'Student', 'Homemaker'];
const INCOME_OPTIONS = ['Below ₹1 L', '₹1–5 L', '₹5–10 L', '₹10–25 L', '₹25–50 L', 'Above ₹50 L'];
const COUNTRY_OPTIONS = ['India', 'USA', 'UK', 'Canada', 'Australia', 'UAE', 'Singapore', 'Other'];
const SOURCE_OF_WEALTH_OPTIONS = ['Salary', 'Business Income', 'Inheritance', 'Investments', 'Gift', 'Sale of Property', 'Other'];
const ACCOUNT_TYPE_OPTIONS = ['Savings', 'Current', 'NRE', 'NRO'];

interface ScalarValues {
  // Identity / contact (frozen into the BE snapshot alongside fullName + pan)
  dateOfBirth: string; // ISO yyyy-MM-dd for <input type="date">
  mobileNumber: string;
  email: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  // IRIS scalars
  holdingMode: string;
  category: string;
  gender: string;
  occupation: string;
  annualIncome: string;
  countryOfBirth: string;
  countryOfCitizenship: string;
  taxResidency: string; // drives taxResidentOtherCountry
  sourceOfWealth: string;
  pep: boolean;
  relativeOfPep: boolean;
  // Bank
  accountNumber: string;
  ifsc: string;
  accountType: string;
}

function dateInputValue(value: any): string {
  if (typeof value === 'string') {
    return value.length >= 10 ? value.substring(0, 10) : value;
  }
  if (Array.isArray(value) && value.length === 3) {
    const [y, m, d] = value;
    return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  }
  return '';
}

function toScalarDefaults(investor: any): ScalarValues {
  return {
    dateOfBirth: dateInputValue(investor?.dateOfBirth),
    mobileNumber: investor?.mobileNumber ?? '',
    email: investor?.email ?? '',
    addressLine1: investor?.addressLine1 ?? '',
    addressLine2: investor?.addressLine2 ?? '',
    city: investor?.city ?? '',
    state: investor?.state ?? '',
    postalCode: investor?.postalCode ?? '',
    holdingMode: investor?.holdingMode ?? '',
    category: investor?.category ?? '',
    gender: investor?.gender ?? '',
    occupation: investor?.occupation ?? '',
    annualIncome: investor?.annualIncome ?? '',
    countryOfBirth: investor?.countryOfBirth ?? '',
    countryOfCitizenship: investor?.countryOfCitizenship ?? '',
    taxResidency: investor?.taxResidentOtherCountry ? '' : 'India',
    sourceOfWealth: investor?.sourceOfWealth ?? '',
    pep: investor?.pep ?? false,
    relativeOfPep: investor?.relativeOfPep ?? false,
    accountNumber: '',
    ifsc: '',
    accountType: 'Savings',
  };
}

function toNomineeDefaults(investor: any): NomineeValue[] {
  return Array.isArray(investor?.nominees) && investor.nominees.length > 0
    ? investor.nominees.map((n: any) => ({ ...emptyNominee(), ...n }))
    : [];
}

const LABEL_CLS = 'block text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-1.5';
const INPUT_CLS =
  'w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm ' +
  'focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';
const SELECT_CLS = INPUT_CLS + ' cursor-pointer';
const ERR_INPUT_CLS = 'border-red-300 bg-red-50 focus:bg-red-50';
const ERR_TEXT_CLS = 'text-xs text-red-500 mt-1 flex items-center gap-1';
const SECTION_TITLE_CLS = 'flex items-center gap-2 text-sm font-semibold text-slate-700 mb-3';

interface Props {
  investor: any;
  /** Use PUT (edit the pending fill) when the investor is already DISTRIBUTOR_FILLING. */
  isResubmit?: boolean;
  onSubmitted: (result: DistributorProfileSubmitResponse) => void;
  onCancel: () => void;
}

export default function DistributorFillProfileForm({
  investor,
  isResubmit,
  onSubmitted,
  onCancel,
}: Props) {
  const [s, setS] = useState<ScalarValues>(() => toScalarDefaults(investor));
  const [nominees, setNominees] = useState<NomineeValue[]>(() => toNomineeDefaults(investor));
  const [displayNominees, setDisplayNominees] = useState<boolean>(investor?.displayNominees ?? false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // IFSC → bank/branch lookup (mirrors InvestorOnboarding behaviour).
  const [bankName, setBankName] = useState('');
  const [ifscBranchName, setIfscBranchName] = useState('');
  const [ifscLookupLoading, setIfscLookupLoading] = useState(false);

  useEffect(() => {
    setS(toScalarDefaults(investor));
    setNominees(toNomineeDefaults(investor));
    setDisplayNominees(investor?.displayNominees ?? false);
    setFieldErrors({});
    setFormError(null);
  }, [investor?.id]);

  useEffect(() => {
    const normalized = s.ifsc.trim().toUpperCase();
    if (!IFSC_REGEX.test(normalized)) {
      setBankName('');
      setIfscBranchName('');
      return;
    }
    let cancelled = false;
    setIfscLookupLoading(true);
    fetchIfscDetails(normalized)
      .then(result => {
        if (cancelled) return;
        setBankName(result.bankName || '');
        setIfscBranchName(result.branchName || '');
      })
      .catch(() => {
        if (cancelled) return;
        setBankName('');
        setIfscBranchName('');
      })
      .finally(() => {
        if (!cancelled) setIfscLookupLoading(false);
      });
    return () => { cancelled = true; };
  }, [s.ifsc]);

  const set = <K extends keyof ScalarValues>(key: K, value: ScalarValues[K]) => {
    setS(prev => ({ ...prev, [key]: value }));
    setFieldErrors(prev => {
      if (!prev[key as string]) return prev;
      const next = { ...prev };
      delete next[key as string];
      return next;
    });
  };

  const applicantAddress = {
    addressLine1: s.addressLine1,
    addressLine2: s.addressLine2,
    city: s.city,
    state: s.state,
    postalCode: s.postalCode,
    country: 'India',
  };
  const addNominee = () => setNominees(prev => (prev.length >= 3 ? prev : [...prev, emptyNominee()]));
  const removeNominee = (index: number) => setNominees(prev => prev.filter((_, i) => i !== index));
  const updateNominee = (index: number, next: NomineeValue) =>
    setNominees(prev => prev.map((n, i) => (i === index ? next : n)));
  const nomineeShareTotal = nominees.reduce((sum, n) => sum + (Number(n.sharePercent) || 0), 0);

  const validate = (): boolean => {
    const errs: Record<string, string> = {};
    if (!s.dateOfBirth) errs.dateOfBirth = 'Date of birth is required';
    if (!s.mobileNumber.trim()) errs.mobileNumber = 'Mobile is required';
    if (!s.email.trim()) errs.email = 'Email is required';
    if (!s.addressLine1.trim()) errs.addressLine1 = 'Address line 1 is required';
    if (!/^\d{6}$/.test(s.postalCode.trim())) errs.postalCode = 'Enter a 6-digit PIN';
    if (!s.city.trim()) errs.city = 'City is required';
    if (!s.state.trim()) errs.state = 'State is required';
    setFieldErrors(errs);
    return Object.keys(errs).length === 0;
  };

  // Builds the SAME payload field names as InvestorOnboarding#buildInvestorPayload so the
  // BE freeze (onboardingSnapshotJson) + applyApprovedProfileFields round-trips them.
  const buildPayload = () => ({
    fullName: investor?.fullName ?? '',
    pan: investor?.pan ?? '',
    dateOfBirth: s.dateOfBirth || null,
    mobileNumber: s.mobileNumber.trim(),
    email: s.email.trim(),
    addressLine1: s.addressLine1.trim() || null,
    addressLine2: s.addressLine2.trim() || null,
    city: s.city.trim() || null,
    state: s.state.trim() || null,
    postalCode: s.postalCode.trim() || null,
    // ── IRIS scalars (identical names to buildInvestorPayload) ──
    holdingMode: s.holdingMode || null,
    category: s.category || null,
    gender: s.gender || null,
    occupation: s.occupation || null,
    annualIncome: s.annualIncome || null,
    countryOfBirth: s.countryOfBirth || null,
    countryOfCitizenship: s.countryOfCitizenship || null,
    taxResidentOtherCountry: Boolean(s.taxResidency && s.taxResidency !== 'India'),
    sourceOfWealth: s.sourceOfWealth || null,
    pep: Boolean(s.pep),
    relativeOfPep: Boolean(s.relativeOfPep),
    // Bank (account type / IFSC / account number)
    accountNumber: s.accountNumber.trim() || null,
    ifsc: s.ifsc.trim().toUpperCase() || null,
    accountType: s.accountType || null,
    displayNominees: Boolean(displayNominees),
    nominees: nominees.map(n => ({
      fullName: n.fullName.trim(),
      dateOfBirth: n.dateOfBirth || null,
      relationship: n.relationship || null,
      sharePercent: n.sharePercent ? Number(n.sharePercent) : null,
      mobileNumber: n.mobileNumber ? n.mobileNumber.trim() : null,
      email: n.email.trim() || null,
      idType: n.idType || null,
      idNumber: n.idNumber.trim() || null,
      addressLine1: n.addressLine1.trim() || null,
      addressLine2: n.addressLine2.trim() || null,
      addressLine3: n.addressLine3.trim() || null,
      city: n.city.trim() || null,
      state: n.state.trim() || null,
      postalCode: n.postalCode.trim() || null,
      country: n.country.trim() || null,
      sameAsApplicant: Boolean(n.sameAsApplicant),
    })),
  });

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    if (!validate()) {
      setFormError('Please fix the highlighted fields before submitting.');
      return;
    }

    const minimumAgeError = validateInvestorMinimumAge(s.dateOfBirth);
    if (minimumAgeError) {
      setFormError(minimumAgeError);
      return;
    }

    setSubmitting(true);
    try {
      const json = JSON.stringify(buildPayload());
      const result = isResubmit
        ? await updateDistributorProfile(investor.id, json)
        : await submitDistributorProfile(investor.id, json);
      onSubmitted(result);
    } catch (err) {
      const status = (err as { status?: number })?.status;
      if (status === 403) {
        setFormError('You are not the distributor linked to this investor, so you cannot fill their profile.');
      } else if (status === 400) {
        setFormError(
          (err as Error)?.message
            || 'This investor is not waiting for a distributor-filled profile. Refresh and try again.',
        );
      } else {
        setFormError(err instanceof Error ? err.message : 'Could not submit the profile. Please try again.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={onSubmit} className="space-y-6">
      <div className="flex items-start gap-2 rounded-xl border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-700">
        <ShieldCheck className="mt-0.5 h-4 w-4 flex-shrink-0" />
        <span>
          This investor approved the link but skipped the profile form. Complete the details
          below — the investor will be asked to approve them with a one-time code before they
          take effect.
        </span>
      </div>

      {formError && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded-xl px-4 py-3 text-sm flex items-start gap-2">
          <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
          <span>{formError}</span>
        </div>
      )}

      {/* ── Identity & contact ───────────────────────────────────────── */}
      <section>
        <div className={SECTION_TITLE_CLS}><User className="h-4 w-4 text-slate-400" /> Identity &amp; contact</div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className={LABEL_CLS}>Date of birth</label>
            <input
              type="date"
              value={s.dateOfBirth}
              onChange={e => set('dateOfBirth', e.target.value)}
              className={`${INPUT_CLS} ${fieldErrors.dateOfBirth ? ERR_INPUT_CLS : ''}`}
            />
            {fieldErrors.dateOfBirth && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{fieldErrors.dateOfBirth}</p>}
          </div>
          <div>
            <label className={LABEL_CLS}>Mobile</label>
            <input
              value={s.mobileNumber}
              onChange={e => set('mobileNumber', e.target.value.replace(/\D/g, '').slice(0, 13))}
              className={`${INPUT_CLS} ${fieldErrors.mobileNumber ? ERR_INPUT_CLS : ''}`}
            />
            {fieldErrors.mobileNumber && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{fieldErrors.mobileNumber}</p>}
          </div>
          <div className="md:col-span-2">
            <label className={LABEL_CLS}>Email</label>
            <input
              type="email"
              value={s.email}
              onChange={e => set('email', e.target.value)}
              className={`${INPUT_CLS} ${fieldErrors.email ? ERR_INPUT_CLS : ''}`}
            />
            {fieldErrors.email && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{fieldErrors.email}</p>}
          </div>
          <div className="md:col-span-2">
            <label className={LABEL_CLS}>Address line 1</label>
            <input
              value={s.addressLine1}
              onChange={e => set('addressLine1', e.target.value)}
              className={`${INPUT_CLS} ${fieldErrors.addressLine1 ? ERR_INPUT_CLS : ''}`}
            />
            {fieldErrors.addressLine1 && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{fieldErrors.addressLine1}</p>}
          </div>
          <div className="md:col-span-2">
            <label className={LABEL_CLS}>Address line 2</label>
            <input
              value={s.addressLine2}
              onChange={e => set('addressLine2', e.target.value)}
              className={INPUT_CLS}
            />
          </div>
          <div className="md:col-span-2 grid grid-cols-1 md:grid-cols-2 gap-4">
            <PincodeCityFields
              postalCode={s.postalCode}
              city={s.city}
              state={s.state}
              onPostalCodeChange={value => set('postalCode', value)}
              onLocationResolved={({ city, state }) => setS(prev => ({ ...prev, city, state }))}
              onCityChange={value => set('city', value)}
              onStateChange={value => set('state', value)}
              inputClassName={INPUT_CLS}
              selectClassName={SELECT_CLS}
              postalCodeError={fieldErrors.postalCode}
              cityError={fieldErrors.city}
              stateError={fieldErrors.state}
            />
          </div>
        </div>
      </section>

      {/* ── Account profile (IRIS) ───────────────────────────────────── */}
      <section>
        <div className={SECTION_TITLE_CLS}><ShieldCheck className="h-4 w-4 text-slate-400" /> Account profile</div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className={LABEL_CLS}>Mode of holding</label>
            <select value={s.holdingMode} onChange={e => set('holdingMode', e.target.value)} className={SELECT_CLS}>
              <option value="">Select mode</option>
              <option value="SINGLE">Single</option>
              <option value="ANYONE_OR_SURVIVOR">Anyone or Survivor</option>
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Category</label>
            <select value={s.category} onChange={e => set('category', e.target.value)} className={SELECT_CLS}>
              <option value="">Select category</option>
              <option value="RESIDENT">Resident</option>
              <option value="NRI">NRI</option>
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Gender</label>
            <select value={s.gender} onChange={e => set('gender', e.target.value)} className={SELECT_CLS}>
              <option value="">Select gender</option>
              {GENDER_OPTIONS.map(g => <option key={g} value={g}>{g}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Occupation</label>
            <select value={s.occupation} onChange={e => set('occupation', e.target.value)} className={SELECT_CLS}>
              <option value="">Select occupation</option>
              {OCCUPATION_OPTIONS.map(o => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Annual income range</label>
            <select value={s.annualIncome} onChange={e => set('annualIncome', e.target.value)} className={SELECT_CLS}>
              <option value="">Select range</option>
              {INCOME_OPTIONS.map(r => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Source of wealth</label>
            <select value={s.sourceOfWealth} onChange={e => set('sourceOfWealth', e.target.value)} className={SELECT_CLS}>
              <option value="">Select source</option>
              {SOURCE_OF_WEALTH_OPTIONS.map(w => <option key={w} value={w}>{w}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Country of birth</label>
            <select value={s.countryOfBirth} onChange={e => set('countryOfBirth', e.target.value)} className={SELECT_CLS}>
              <option value="">Select country</option>
              {COUNTRY_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Country of citizenship</label>
            <select value={s.countryOfCitizenship} onChange={e => set('countryOfCitizenship', e.target.value)} className={SELECT_CLS}>
              <option value="">Select country</option>
              {COUNTRY_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Country of tax residency</label>
            <select value={s.taxResidency} onChange={e => set('taxResidency', e.target.value)} className={SELECT_CLS}>
              <option value="">Select country</option>
              {COUNTRY_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Politically exposed person (PEP)</label>
            <select value={s.pep ? 'Yes' : 'No'} onChange={e => set('pep', e.target.value === 'Yes')} className={SELECT_CLS}>
              <option value="No">No</option>
              <option value="Yes">Yes</option>
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Relative of a PEP</label>
            <select value={s.relativeOfPep ? 'Yes' : 'No'} onChange={e => set('relativeOfPep', e.target.value === 'Yes')} className={SELECT_CLS}>
              <option value="No">No</option>
              <option value="Yes">Yes</option>
            </select>
          </div>
        </div>
      </section>

      {/* ── Bank ─────────────────────────────────────────────────────── */}
      <section>
        <div className={SECTION_TITLE_CLS}><Building2 className="h-4 w-4 text-slate-400" /> Bank account</div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className={LABEL_CLS}>Account number</label>
            <input
              value={s.accountNumber}
              onChange={e => set('accountNumber', e.target.value.replace(/\D/g, '').slice(0, 18))}
              placeholder="12345678901234"
              className={INPUT_CLS + ' font-mono'}
            />
          </div>
          <div>
            <label className={LABEL_CLS}>Account type</label>
            <select value={s.accountType} onChange={e => set('accountType', e.target.value)} className={SELECT_CLS}>
              {ACCOUNT_TYPE_OPTIONS.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div className="md:col-span-2">
            <label className={LABEL_CLS}>IFSC code</label>
            <div className="flex flex-wrap items-center gap-3">
              <input
                value={s.ifsc}
                onChange={e => set('ifsc', e.target.value.toUpperCase().slice(0, 11))}
                placeholder="HDFC0001234"
                className={INPUT_CLS + ' font-mono w-44'}
              />
              {(ifscLookupLoading || bankName) && (
                <span className="flex items-center gap-2 rounded-lg border border-green-200 bg-green-50 px-3 py-2 text-xs font-semibold text-green-700">
                  {ifscLookupLoading
                    ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    : <Building2 className="h-3.5 w-3.5" />}
                  {ifscLookupLoading ? 'Looking up IFSC…' : `${bankName}${ifscBranchName ? ` · ${ifscBranchName}` : ''}`}
                </span>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* ── Nominees (up to 3) ───────────────────────────────────────── */}
      <section>
        <div className="mb-3 flex items-center justify-between">
          <span className="flex items-center gap-2 text-sm font-semibold text-slate-700">
            <User className="h-4 w-4 text-slate-400" />
            Nominee details
            <span className="text-xs font-normal text-slate-400">(optional, up to 3)</span>
          </span>
          {nominees.length < 3 && (
            <button
              type="button"
              onClick={addNominee}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
            >
              + Add nominee
            </button>
          )}
        </div>

        {nominees.length === 0 ? (
          <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 px-4 py-6 text-center text-xs text-slate-400">
            No nominees added. Click "Add nominee" to register up to 3 nominees.
          </div>
        ) : (
          <div className="space-y-4">
            {nominees.map((nominee, index) => (
              <NomineeFields
                key={index}
                index={index}
                value={nominee}
                onChange={next => updateNominee(index, next)}
                onRemove={() => removeNominee(index)}
                applicantAddress={applicantAddress}
              />
            ))}
          </div>
        )}

        {nominees.length > 0 && (
          <p className={`mt-3 text-xs font-medium ${nomineeShareTotal === 100 ? 'text-green-600' : 'text-amber-600'}`}>
            Total nominee share: {nomineeShareTotal}% {nomineeShareTotal === 100 ? '✓' : '(should total 100%)'}
          </p>
        )}

        <label className="mt-4 flex cursor-pointer items-center gap-3">
          <input
            type="checkbox"
            checked={displayNominees}
            onChange={e => setDisplayNominees(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
          />
          <span className="text-sm text-slate-700">Display nominee details on statements and reports</span>
        </label>
      </section>

      <div className="flex items-center justify-end gap-3 pt-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={submitting}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
        >
          <X className="w-4 h-4" /> Cancel
        </button>
        <button
          type="submit"
          disabled={submitting}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors disabled:opacity-50"
        >
          {submitting ? (
            <>
              <div className="w-3.5 h-3.5 border-2 border-white/40 border-t-white rounded-full animate-spin" />
              Submitting…
            </>
          ) : (
            <>
              <Send className="w-4 h-4" /> {isResubmit ? 'Update & resend for approval' : 'Submit for investor approval'}
            </>
          )}
        </button>
      </div>
    </form>
  );
}
