import React, { useEffect } from 'react';
import { useForm, type SubmitHandler } from 'react-hook-form';
import { Send, X, AlertCircle, ShieldCheck } from 'lucide-react';
import {
  submitDistributorProfile,
  updateDistributorProfile,
  type DistributorProfileSubmitResponse,
} from '../config/api';
import PincodeCityFields from './PincodeCityFields';
import { validateInvestorMinimumAge } from '../utils/kycActionLocks';

/**
 * Distributor skip-form fill (investor.md R10).
 *
 * When an investor approved the persona link but skipped the onboarding profile
 * (linking_status INVESTOR_SKIPPED — or the distributor already started filling it,
 * DISTRIBUTOR_FILLING), the distributor completes the profile on the investor's behalf.
 *
 * Field set mirrors the onboarding profile fields the BE freezes into the 2FA challenge
 * (see InvestorController#onboardingSnapshotJson: dateOfBirth, address lines, city/state/
 * postalCode, mobile, email). The collected fields are JSON-stringified into `payloadJson`
 * and POSTed to /investors/{id}/profile/submit (first time) or PUT /investors/{id}/profile
 * (editing the still-pending fill). The acting distributor is resolved server-side from the
 * session — never sent in the body. On success the investor must approve the frozen details
 * via 2FA, so we surface the resulting challengeId to the caller.
 */

interface FormValues {
  dateOfBirth: string; // ISO yyyy-MM-dd for <input type="date">
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  mobileNumber: string;
  email: string;
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

function toDefaults(investor: any): FormValues {
  return {
    dateOfBirth: dateInputValue(investor?.dateOfBirth),
    addressLine1: investor?.addressLine1 ?? '',
    addressLine2: investor?.addressLine2 ?? '',
    city: investor?.city ?? '',
    state: investor?.state ?? '',
    postalCode: investor?.postalCode ?? '',
    mobileNumber: investor?.mobileNumber ?? '',
    email: investor?.email ?? '',
  };
}

const LABEL_CLS = 'block text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-1.5';
const INPUT_CLS =
  'w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm ' +
  'focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';
const ERR_INPUT_CLS = 'border-red-300 bg-red-50 focus:bg-red-50';
const ERR_TEXT_CLS = 'text-xs text-red-500 mt-1 flex items-center gap-1';

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
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ defaultValues: toDefaults(investor) });

  const postalCode = watch('postalCode');
  const city = watch('city');
  const state = watch('state');

  useEffect(() => {
    reset(toDefaults(investor));
  }, [investor?.id, reset]);

  const [formError, setFormError] = React.useState<string | null>(null);

  const onSubmit: SubmitHandler<FormValues> = async values => {
    setFormError(null);

    const minimumAgeError = validateInvestorMinimumAge(values.dateOfBirth);
    if (minimumAgeError) {
      setFormError(minimumAgeError);
      return;
    }

    // The BE snapshot hashes fullName + pan alongside the editable profile fields, so we
    // carry the investor's identity through verbatim and add the distributor-entered details.
    const profile = {
      fullName: investor?.fullName ?? '',
      pan: investor?.pan ?? '',
      dateOfBirth: values.dateOfBirth || null,
      addressLine1: values.addressLine1.trim() || null,
      addressLine2: values.addressLine2.trim() || null,
      city: values.city.trim() || null,
      state: values.state.trim() || null,
      postalCode: values.postalCode.trim() || null,
      mobileNumber: values.mobileNumber.trim(),
      email: values.email.trim(),
    };

    try {
      const result = isResubmit
        ? await updateDistributorProfile(investor.id, JSON.stringify(profile))
        : await submitDistributorProfile(investor.id, JSON.stringify(profile));
      onSubmitted(result);
    } catch (err) {
      const status = (err as { status?: number })?.status;
      if (status === 403) {
        setFormError('You are not the distributor linked to this investor, so you cannot fill their profile.');
        return;
      }
      if (status === 400) {
        setFormError(
          (err as Error)?.message
            || 'This investor is not waiting for a distributor-filled profile. Refresh and try again.',
        );
        return;
      }
      setFormError(err instanceof Error ? err.message : 'Could not submit the profile. Please try again.');
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
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

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div>
          <label className={LABEL_CLS}>Date of birth</label>
          <input
            type="date"
            {...register('dateOfBirth', { required: 'Date of birth is required' })}
            className={`${INPUT_CLS} ${errors.dateOfBirth ? ERR_INPUT_CLS : ''}`}
          />
          {errors.dateOfBirth && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.dateOfBirth.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>Mobile</label>
          <input
            {...register('mobileNumber', { required: 'Mobile is required' })}
            className={`${INPUT_CLS} ${errors.mobileNumber ? ERR_INPUT_CLS : ''}`}
          />
          {errors.mobileNumber && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.mobileNumber.message}</p>}
        </div>

        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Email</label>
          <input
            type="email"
            {...register('email', { required: 'Email is required' })}
            className={`${INPUT_CLS} ${errors.email ? ERR_INPUT_CLS : ''}`}
          />
          {errors.email && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.email.message}</p>}
        </div>

        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Address line 1</label>
          <input
            {...register('addressLine1', { required: 'Address line 1 is required' })}
            className={`${INPUT_CLS} ${errors.addressLine1 ? ERR_INPUT_CLS : ''}`}
          />
          {errors.addressLine1 && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.addressLine1.message}</p>}
        </div>

        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Address line 2</label>
          <input
            {...register('addressLine2')}
            className={`${INPUT_CLS} ${errors.addressLine2 ? ERR_INPUT_CLS : ''}`}
          />
        </div>

        <div className="md:col-span-2 grid grid-cols-1 md:grid-cols-2 gap-4">
          <PincodeCityFields
            postalCode={postalCode}
            city={city}
            state={state}
            onPostalCodeChange={value => setValue('postalCode', value, { shouldDirty: true })}
            onLocationResolved={({ city: resolvedCity, state: resolvedState }) => {
              setValue('city', resolvedCity, { shouldDirty: true });
              setValue('state', resolvedState, { shouldDirty: true });
            }}
            onCityChange={value => setValue('city', value, { shouldDirty: true })}
            onStateChange={value => setValue('state', value, { shouldDirty: true })}
            inputClassName={INPUT_CLS}
            selectClassName={INPUT_CLS}
            postalCodeError={errors.postalCode?.message}
            cityError={errors.city?.message}
            stateError={errors.state?.message}
          />
        </div>
      </div>

      <div className="flex items-center justify-end gap-3 pt-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={isSubmitting}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
        >
          <X className="w-4 h-4" /> Cancel
        </button>
        <button
          type="submit"
          disabled={isSubmitting}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors disabled:opacity-50"
        >
          {isSubmitting ? (
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
