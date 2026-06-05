import React, { useEffect } from 'react';
import { useForm, type SubmitHandler } from 'react-hook-form';
import { Save, X, AlertCircle } from 'lucide-react';
import { apiFetch } from '../config/api';
import { normalizePan, PAN_REGEX } from '../utils/kycPreVerification';

/**
 * F-10: edit form for an existing investor.
 *
 * Field set matches the backend InvestorUpdateRequest DTO. PAN is editable
 * only while KYC is not verified — after that it stays locked.
 *
 * On save: calls PUT /investors/{id} via apiFetch (which prefixes /api/v1
 * and forwards auth cookies). On HTTP 200, `onSaved(updatedInvestor)` is
 * called with the server response so the parent can refresh the cached
 * investor object. On HTTP 400 we parse the comma-joined "field: message"
 * format that GlobalExceptionHandler emits for MethodArgumentNotValidException
 * and surface those via react-hook-form's setError so the offending field
 * displays an inline error.
 */

interface FormValues {
  fullName: string;
  pan: string;
  mobileNumber: string;
  email: string;
  dateOfBirth: string; // ISO yyyy-MM-dd for <input type="date">
  anniversaryDate: string;
  goalMaturityDate: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  state: string;
  postalCode: string;
  onboardingNotes: string;
}

// Map an Investor record (snake/camel/whatever the API returns) to flat form
// defaults. dateOfBirth may arrive as either an ISO string or a [y,m,d] tuple
// (Spring + Jackson can serialise LocalDate either way depending on config) —
// handle both. Nulls become "" so the inputs stay controlled.
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
    fullName:        investor?.fullName        ?? '',
    pan:             normalizePan(investor?.pan),
    mobileNumber:    investor?.mobileNumber    ?? '',
    email:           investor?.email           ?? '',
    dateOfBirth:     dateInputValue(investor?.dateOfBirth),
    anniversaryDate: dateInputValue(investor?.anniversaryDate),
    goalMaturityDate: dateInputValue(investor?.goalMaturityDate),
    addressLine1:    investor?.addressLine1    ?? '',
    addressLine2:    investor?.addressLine2    ?? '',
    city:            investor?.city            ?? '',
    state:           investor?.state           ?? '',
    postalCode:      investor?.postalCode      ?? '',
    onboardingNotes: investor?.onboardingNotes ?? '',
  };
}

// GlobalExceptionHandler.handleValidationExceptions returns its message as
// "field: msg, field: msg, ..." (joined with ", "). Split that back into
// per-field errors so react-hook-form can highlight the offending inputs.
function parseFieldErrors(message: string): Array<{ field: string; message: string }> {
  if (!message) return [];
  return message
    .split(',')
    .map(part => part.trim())
    .filter(Boolean)
    .map(part => {
      const colon = part.indexOf(':');
      if (colon <= 0) return null;
      return {
        field: part.substring(0, colon).trim(),
        message: part.substring(colon + 1).trim(),
      };
    })
    .filter((x): x is { field: string; message: string } => x !== null);
}

const LABEL_CLS = 'block text-[10px] font-bold text-slate-500 uppercase tracking-wider mb-1.5';
const INPUT_CLS =
  'w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm ' +
  'focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';
const ERR_INPUT_CLS = 'border-red-300 bg-red-50 focus:bg-red-50';
const ERR_TEXT_CLS = 'text-xs text-red-500 mt-1 flex items-center gap-1';

interface Props {
  investor: any;
  onSaved: (updatedInvestor: any) => void;
  onCancel: () => void;
}

const isKycVerified = (investor: any) => {
  const status = String(investor?.kycStatus || '').toUpperCase();
  return status === 'COMPLETED' || status === 'VERIFIED';
};

export default function InvestorEditForm({ investor, onSaved, onCancel }: Props) {
  const identityLocked = isKycVerified(investor);
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ defaultValues: toDefaults(investor) });

  // Re-seed defaults if the parent swaps to a different investor without
  // unmounting (defensive — current Investors.tsx unmounts on selection
  // change, but keeping this makes the component safe to reuse).
  useEffect(() => {
    reset(toDefaults(investor));
  }, [investor?.id, reset]);

  const [formError, setFormError] = React.useState<string | null>(null);

  const onSubmit: SubmitHandler<FormValues> = async values => {
    setFormError(null);
    // Strip empty optional strings so the backend's `if (request.foo() != null)`
    // guards do not overwrite stored values with "". Required fields (name,
    // mobile, email) always send through.
    const payload: Record<string, unknown> = {
      fullName:        values.fullName.trim(),
      mobileNumber:    values.mobileNumber.trim(),
      email:           values.email.trim(),
      dateOfBirth:     values.dateOfBirth || null,
      anniversaryDate: values.anniversaryDate || null,
      goalMaturityDate: values.goalMaturityDate || null,
      addressLine1:    values.addressLine1.trim() || null,
      addressLine2:    values.addressLine2.trim() || null,
      city:            values.city.trim() || null,
      state:           values.state.trim() || null,
      postalCode:      values.postalCode.trim() || null,
      onboardingNotes: values.onboardingNotes.trim() || null,
    };
    if (!identityLocked) {
      payload.pan = normalizePan(values.pan);
    }

    let response: Response;
    try {
      response = await apiFetch(`/investors/${investor.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
    } catch {
      setFormError("Couldn't reach the server. Please check your connection and try again.");
      return;
    }

    if (response.ok) {
      const updated = await response.json().catch(() => investor);
      onSaved(updated);
      return;
    }

    // Try to extract field errors from the API's ApiErrorResponse shape.
    let body: { message?: string; error?: string } = {};
    try { body = await response.json(); } catch { /* non-JSON body */ }

    if (response.status === 400) {
      const fieldErrors = parseFieldErrors(body.message ?? '');
      if (fieldErrors.length > 0) {
        for (const fe of fieldErrors) {
          // Only wire errors for fields the form actually owns — avoids
          // setError on unknown names which RHF would still record but
          // never display.
          if (fe.field in (toDefaults(investor) as object)) {
            setError(fe.field as keyof FormValues, { type: 'server', message: fe.message });
          }
        }
        setFormError('Please correct the highlighted fields and try again.');
        return;
      }
    }

    setFormError(body.message || `Save failed (HTTP ${response.status}).`);
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-5">
      {formError && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded-xl px-4 py-3 text-sm flex items-start gap-2">
          <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
          <span>{formError}</span>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Full name</label>
          <input
            {...register('fullName', { required: 'Full name is required' })}
            className={`${INPUT_CLS} ${errors.fullName ? ERR_INPUT_CLS : ''}`}
          />
          {errors.fullName && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.fullName.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>PAN</label>
          <input
            {...register('pan', {
              required: identityLocked ? false : 'PAN is required',
              validate: value => identityLocked || PAN_REGEX.test(normalizePan(value)) || 'Invalid PAN format',
            })}
            readOnly={identityLocked}
            maxLength={10}
            className={`${INPUT_CLS} font-mono tracking-widest uppercase ${errors.pan ? ERR_INPUT_CLS : ''} ${identityLocked ? 'opacity-60 cursor-not-allowed' : ''}`}
          />
          {errors.pan && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.pan.message}</p>}
          {!identityLocked && (
            <p className="mt-1 text-[11px] text-slate-400">Changing PAN clears the previous KYC attempt — run pre-check again after saving.</p>
          )}
        </div>

        <div>
          <label className={LABEL_CLS}>Email</label>
          <input
            type="email"
            {...register('email', { required: 'Email is required' })}
            className={`${INPUT_CLS} ${errors.email ? ERR_INPUT_CLS : ''}`}
          />
          {errors.email && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.email.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>Mobile</label>
          <input
            {...register('mobileNumber', { required: 'Mobile is required' })}
            className={`${INPUT_CLS} ${errors.mobileNumber ? ERR_INPUT_CLS : ''}`}
          />
          {errors.mobileNumber && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.mobileNumber.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>Date of birth</label>
          <input
            type="date"
            {...register('dateOfBirth')}
            className={`${INPUT_CLS} ${errors.dateOfBirth ? ERR_INPUT_CLS : ''}`}
          />
          {errors.dateOfBirth && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.dateOfBirth.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>Anniversary date</label>
          <input
            type="date"
            {...register('anniversaryDate')}
            className={`${INPUT_CLS} ${errors.anniversaryDate ? ERR_INPUT_CLS : ''}`}
          />
          {errors.anniversaryDate && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.anniversaryDate.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>Goal maturity date</label>
          <input
            type="date"
            {...register('goalMaturityDate')}
            className={`${INPUT_CLS} ${errors.goalMaturityDate ? ERR_INPUT_CLS : ''}`}
          />
          {errors.goalMaturityDate && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.goalMaturityDate.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>Postal code</label>
          <input
            {...register('postalCode')}
            className={`${INPUT_CLS} ${errors.postalCode ? ERR_INPUT_CLS : ''}`}
          />
          {errors.postalCode && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.postalCode.message}</p>}
        </div>

        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Address line 1</label>
          <input
            {...register('addressLine1')}
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

        <div>
          <label className={LABEL_CLS}>City</label>
          <input
            {...register('city')}
            className={`${INPUT_CLS} ${errors.city ? ERR_INPUT_CLS : ''}`}
          />
          {errors.city && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.city.message}</p>}
        </div>

        <div>
          <label className={LABEL_CLS}>State</label>
          <input
            {...register('state')}
            className={`${INPUT_CLS} ${errors.state ? ERR_INPUT_CLS : ''}`}
          />
          {errors.state && <p className={ERR_TEXT_CLS}><AlertCircle className="w-3 h-3" />{errors.state.message}</p>}
        </div>

        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Onboarding notes</label>
          <textarea
            rows={3}
            {...register('onboardingNotes')}
            className={`${INPUT_CLS} resize-y ${errors.onboardingNotes ? ERR_INPUT_CLS : ''}`}
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
              Saving…
            </>
          ) : (
            <>
              <Save className="w-4 h-4" /> Save changes
            </>
          )}
        </button>
      </div>
    </form>
  );
}
