export type PreVerificationHash = {
  status?: string | null;
  code?: string | null;
  reason?: string | null;
  value?: unknown;
};

export type PreVerificationResponse = {
  object?: string;
  id?: string;
  status?: string;
  investor_identifier?: string;
  readiness?: PreVerificationHash;
  pan?: PreVerificationHash;
  name?: PreVerificationHash;
  date_of_birth?: PreVerificationHash;
  bank_accounts?: Array<PreVerificationHash>;
  created_at?: string;
  completed_at?: string;
  updated_at?: string;
};

export type PreVerificationDecision = {
  state: 'idle' | 'accepted' | 'verified' | 'failed' | 'retry' | 'pending';
  title: string;
  message: string;
  canProceed: boolean;
  requiresFreshKyc: boolean;
};

type InvestorIdentity = {
  firstName?: string;
  lastName?: string;
  fullName?: string;
  pan?: string | null;
  dob?: string | null;
  mobile?: string | null;
  email?: string | null;
  relationshipType?: string | null;
  guardianPan?: string | null;
};

type IdentityValidationOptions = {
  requireContact?: boolean;
};

export const PAN_REGEX = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
export const MOBILE_REGEX = /^[6-9]\d{9}$/;
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const NAME_REGEX = /^[A-Za-z][A-Za-z .'-]{1,79}$/;
const DUMMY_NAME_REGEX = /\b(test|dummy|sample|asdf|qwerty|unknown|null|none)\b/i;
const LORD_VOLDEMORT_REGEX = /^lord\s+voldemort$/i;

export const normalizePan = (value?: string | null) =>
  String(value || '').trim().toUpperCase();

export const normalizeMobile = (value?: string | null) => {
  let digits = String(value || '').replace(/\D/g, '');
  if (digits.startsWith('91') && digits.length > 10) {
    digits = digits.slice(2);
  }
  return digits;
};

export const getInvestorFullName = (identity: InvestorIdentity) =>
  (identity.fullName || `${identity.firstName || ''} ${identity.lastName || ''}`)
    .replace(/\s+/g, ' ')
    .trim();

export const validateInvestorIdentityForKyc = (
  identity: InvestorIdentity,
  options: IdentityValidationOptions = {},
) => {
  const errors: Record<string, string> = {};
  const requireContact = options.requireContact ?? true;
  const fullName = getInvestorFullName(identity);
  const pan = normalizePan(identity.pan);
  const dob = String(identity.dob || '').trim();

  if (identity.firstName !== undefined && !String(identity.firstName).trim()) {
    errors.firstName = 'First name is required.';
  }
  if (identity.lastName !== undefined && !String(identity.lastName).trim()) {
    errors.lastName = 'Last name is required.';
  }
  if (!fullName) {
    errors.fullName = 'Investor name is required.';
  } else if (!NAME_REGEX.test(fullName) || DUMMY_NAME_REGEX.test(fullName) || LORD_VOLDEMORT_REGEX.test(fullName)) {
    errors.fullName = 'Enter the investor legal name as per PAN records.';
    if (identity.firstName !== undefined) errors.firstName = errors.fullName;
    if (identity.lastName !== undefined) errors.lastName = errors.fullName;
  }

  if (!PAN_REGEX.test(pan)) {
    errors.pan = 'Enter a valid PAN in AAAAA9999A format.';
  }

  if (!dob) {
    errors.dob = 'Date of birth is required for PAN validation.';
  } else {
    const parsedDate = new Date(`${dob}T00:00:00`);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    if (Number.isNaN(parsedDate.getTime())) {
      errors.dob = 'Enter a valid date of birth.';
    } else if (parsedDate >= today) {
      errors.dob = 'Date of birth must be in the past.';
    } else if (parsedDate.getFullYear() < 1900) {
      errors.dob = 'Enter a realistic date of birth.';
    }
  }

  if (requireContact) {
    const mobile = normalizeMobile(identity.mobile);
    if (!MOBILE_REGEX.test(mobile)) {
      errors.mobile = 'Enter a valid 10-digit Indian mobile number.';
    }
    const email = String(identity.email || '').trim();
    if (!EMAIL_REGEX.test(email)) {
      errors.email = 'Enter a valid email address.';
    }
  }

  if (identity.relationshipType === 'MINOR' && !PAN_REGEX.test(normalizePan(identity.guardianPan))) {
    errors.guardianPan = 'Enter a valid guardian PAN in AAAAA9999A format.';
  }

  return errors;
};

export const extractPreVerification = (payload: any): PreVerificationResponse | null => {
  const external = payload?.externalResponse || payload;
  return external?.object === 'pre_verification' ? external : null;
};

export const parseStoredPreVerification = (payload?: unknown): PreVerificationResponse | null => {
  if (!payload) return null;
  if (typeof payload === 'string') {
    try {
      return extractPreVerification(JSON.parse(payload));
    } catch {
      return null;
    }
  }
  return extractPreVerification(payload);
};

const cleanStatus = (value?: string | null) => String(value || '').trim().toLowerCase();

export const getPreVerificationDecision = (
  external?: PreVerificationResponse | null,
): PreVerificationDecision => {
  if (!external) {
    return {
      state: 'idle',
      title: 'Pre-verification not started',
      message: 'Run POA pre-verification to validate PAN, name, date of birth, and investor readiness.',
      canProceed: false,
      requiresFreshKyc: false,
    };
  }

  const status = cleanStatus(external.status);
  if (status && status !== 'completed') {
    return {
      state: status === 'accepted' ? 'accepted' : 'pending',
      title: 'Pre-verification in progress',
      message: 'Cybrilla accepted the request. Refresh the status after a short interval.',
      canProceed: false,
      requiresFreshKyc: false,
    };
  }

  const readinessStatus = cleanStatus(external.readiness?.status);
  const readinessCode = cleanStatus(external.readiness?.code);

  if (readinessStatus === 'verified') {
    return {
      state: 'verified',
      title: 'Investor is ready to invest',
      message: 'PAN, KYC readiness, and demographic checks passed.',
      canProceed: true,
      requiresFreshKyc: false,
    };
  }

  if (readinessStatus === 'failed') {
    if (readinessCode === 'kyc_unavailable' || readinessCode === 'unavailable') {
      return {
        state: 'failed',
        title: 'Fresh KYC required',
        message: external.readiness?.reason || 'No reusable KYC record was found for this investor.',
        canProceed: false,
        requiresFreshKyc: true,
      };
    }

    if (readinessCode === 'upstream_error') {
      return {
        state: 'retry',
        title: 'Verification needs retry',
        message: external.readiness?.reason || 'Cybrilla could not reach the upstream KYC source. Retry the pre-verification.',
        canProceed: false,
        requiresFreshKyc: false,
      };
    }

    return {
      state: readinessCode === 'kyc_incomplete' || readinessCode === 'unknown' ? 'retry' : 'failed',
      title: 'Investor is not ready yet',
      message: external.readiness?.reason || `Readiness failed${readinessCode ? ` (${readinessCode})` : ''}.`,
      canProceed: false,
      requiresFreshKyc: readinessCode === 'kyc_incomplete',
    };
  }

  const fieldNames: Array<keyof PreVerificationResponse> = ['pan', 'name', 'date_of_birth'];
  const failedField = fieldNames.find(field => cleanStatus((external[field] as PreVerificationHash | undefined)?.status) === 'failed');
  if (failedField) {
    const result = external[failedField] as PreVerificationHash | undefined;
    return {
      state: cleanStatus(result?.code) === 'upstream_error' ? 'retry' : 'failed',
      title: 'PAN details did not match',
      message: result?.reason || `${preVerificationFieldLabel(String(failedField))} failed${result?.code ? ` (${result.code})` : ''}.`,
      canProceed: false,
      requiresFreshKyc: false,
    };
  }

  const allDemographicFieldsVerified = fieldNames.every(
    field => cleanStatus((external[field] as PreVerificationHash | undefined)?.status) === 'verified',
  );
  if (allDemographicFieldsVerified) {
    return {
      state: 'verified',
      title: 'PAN details verified',
      message: 'PAN, name, and date of birth matched successfully.',
      canProceed: true,
      requiresFreshKyc: false,
    };
  }

  return {
    state: 'pending',
    title: 'Pre-verification pending',
    message: 'The response does not yet contain a final verification result. Refresh before continuing.',
    canProceed: false,
    requiresFreshKyc: false,
  };
};

export const preVerificationFieldLabel = (field: string) => {
  switch (field) {
    case 'readiness': return 'Investor readiness';
    case 'pan': return 'PAN';
    case 'name': return 'Name';
    case 'date_of_birth': return 'Date of birth';
    default: return field.replace(/_/g, ' ');
  }
};

export const getPreVerificationRows = (external?: PreVerificationResponse | null) => {
  if (!external) return [];
  return (['readiness', 'pan', 'name', 'date_of_birth'] as const).map(field => {
    const result = external[field] as PreVerificationHash | undefined;
    return {
      field,
      label: preVerificationFieldLabel(field),
      status: result?.status || 'pending',
      code: result?.code || '',
      reason: result?.reason || '',
      value: typeof result?.value === 'string' ? result.value : '',
    };
  });
};

export const preVerificationStatusClasses = (status?: string | null) => {
  switch (cleanStatus(status)) {
    case 'verified':
      return 'bg-green-50 text-green-700 border-green-200';
    case 'failed':
      return 'bg-red-50 text-red-700 border-red-200';
    case 'pending':
    case '':
      return 'bg-slate-50 text-slate-600 border-slate-200';
    default:
      return 'bg-blue-50 text-blue-700 border-blue-200';
  }
};
