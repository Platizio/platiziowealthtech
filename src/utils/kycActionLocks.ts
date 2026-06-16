export const MIN_INVESTOR_AGE_YEARS = 18;

/** True while Cybrilla POA pre-verification is actively in flight (not used to lock Run before first click). */
export const isKycPhaseInProgress = (phase?: string | null) =>
  phase === 'checking' || phase === 'accepted' || phase === 'pending';

export const calculateInvestorAgeYears = (dob?: string | null): number | null => {
  const value = String(dob || '').trim();
  if (!value) return null;
  const parsed = new Date(`${value}T00:00:00`);
  if (Number.isNaN(parsed.getTime())) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  let age = today.getFullYear() - parsed.getFullYear();
  const monthDelta = today.getMonth() - parsed.getMonth();
  if (monthDelta < 0 || (monthDelta === 0 && today.getDate() < parsed.getDate())) {
    age -= 1;
  }
  return age;
};

export const latestAllowedDobForMinimumAge = (minAge = MIN_INVESTOR_AGE_YEARS): string => {
  const cutoff = new Date();
  cutoff.setHours(0, 0, 0, 0);
  cutoff.setFullYear(cutoff.getFullYear() - minAge);
  return cutoff.toISOString().slice(0, 10);
};

export const validateInvestorMinimumAge = (
  dob?: string | null,
  options: { relationshipType?: string } = {},
): string | null => {
  if (options.relationshipType === 'MINOR') return null;
  const age = calculateInvestorAgeYears(dob);
  if (age === null) return null;
  if (age < MIN_INVESTOR_AGE_YEARS) {
    return `Investor must be at least ${MIN_INVESTOR_AGE_YEARS} years old. Enter a date of birth on or before ${latestAllowedDobForMinimumAge()}.`;
  }
  return null;
};

/**
 * Lock "Run pre-verification" only while a user-initiated check is in flight,
 * or after POA pre-verification has finished with a terminal success outcome.
 * Do not lock on accepted/pending resume or background polling before the user runs.
 */
export const shouldLockPoaRunButton = (args: {
  anyBusy: boolean;
  kycPhase?: string | null;
  preVerificationComplete?: boolean;
}) => {
  if (args.kycPhase === 'checking') return true;
  if (args.preVerificationComplete) return true;
  if (args.anyBusy) return true;
  return false;
};

export const isPoaPreVerificationComplete = (decision?: {
  state?: string | null;
  canProceed?: boolean;
  requiresFreshKyc?: boolean;
} | null) => {
  if (!decision) return false;
  if (decision.state === 'verified' || decision.canProceed) return true;
  if (decision.requiresFreshKyc) return true;
  return false;
};

export const shouldLockAadhaarStart = (args: {
  anyBusy: boolean;
  documentId?: string | null;
  redirectUrl?: string | null;
  externalDocumentId?: string | null;
}) =>
  args.anyBusy
  || Boolean(args.documentId || args.redirectUrl || args.externalDocumentId);

export const shouldLockKycRequestCreate = (args: {
  anyBusy: boolean;
  creating?: boolean;
  kycRequestId?: string | null;
  /** When true, Cybrilla readiness failed (non-kyc_unavailable) — do not start a KYC request. */
  readinessBlocksCreate?: boolean;
}) =>
  args.anyBusy
  || Boolean(args.creating)
  || Boolean(args.kycRequestId)
  || Boolean(args.readinessBlocksCreate);

export const shouldLockEsignStart = (args: {
  anyBusy: boolean;
  esignId?: string | null;
  redirectUrl?: string | null;
}) => args.anyBusy || Boolean(args.esignId || args.redirectUrl);
