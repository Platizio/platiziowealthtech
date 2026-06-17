import { API_BASE_URL } from '../config/api';
import { validateInvestorMinimumAge } from './kycActionLocks';

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
  /** POA PAN check returned code `aadhaar_not_linked`. */
  requiresPanAadhaarLink: boolean;
};

export type IdentityDocumentFetch = {
  identityDocumentId?: string;
  redirectUrl?: string;
  status?: string;
  reason?: string;
};

/** Verbatim Cybrilla/provider message from an API response field. */
export type CybrillaKycWarning = {
  id: string;
  field: string;
  label: string;
  code?: string;
  status?: string;
  reason: string;
};

const PRE_VERIFICATION_WARNING_FIELDS = [
  { key: 'readiness', label: 'Investor readiness' },
  { key: 'pan', label: 'PAN' },
  { key: 'name', label: 'Name' },
  { key: 'date_of_birth', label: 'Date of birth' },
] as const;

const cleanStatus = (value?: string | null) => String(value || '').trim().toLowerCase();

const asRecord = (value: unknown): Record<string, unknown> | null =>
  value && typeof value === 'object' ? (value as Record<string, unknown>) : null;

const readText = (value: unknown) => String(value ?? '').trim();

const pushCybrillaWarning = (
  warnings: CybrillaKycWarning[],
  seen: Set<string>,
  warning: Omit<CybrillaKycWarning, 'id'>,
) => {
  const reason = readText(warning.reason);
  if (!reason) return;
  const id = `${warning.field}:${warning.code || ''}:${reason}`;
  if (seen.has(id)) return;
  seen.add(id);
  warnings.push({ ...warning, reason, id });
};

const addPreVerificationHashWarning = (
  warnings: CybrillaKycWarning[],
  seen: Set<string>,
  field: string,
  label: string,
  hash?: PreVerificationHash | null,
) => {
  if (!hash) return;
  const status = cleanStatus(hash.status);
  const code = readText(hash.code);
  const reason = readText(hash.reason);
  if (status === 'verified' && !reason) return;
  if (status !== 'failed' && !reason) return;
  const message = reason || (status === 'failed' && code ? code : '');
  pushCybrillaWarning(warnings, seen, {
    field,
    label,
    code: code || undefined,
    status: hash.status || undefined,
    reason: message,
  });
};

const extractPreVerificationCybrillaWarnings = (
  external?: PreVerificationResponse | null,
): CybrillaKycWarning[] => {
  if (!external) return [];
  const warnings: CybrillaKycWarning[] = [];
  const seen = new Set<string>();

  PRE_VERIFICATION_WARNING_FIELDS.forEach(({ key, label }) => {
    addPreVerificationHashWarning(
      warnings,
      seen,
      key,
      label,
      external[key] as PreVerificationHash | undefined,
    );
  });

  if (Array.isArray(external.bank_accounts)) {
    external.bank_accounts.forEach((bank, index) => {
      addPreVerificationHashWarning(
        warnings,
        seen,
        `bank_accounts.${index}`,
        `Bank account ${index + 1}`,
        bank,
      );
    });
  }

  return warnings;
};

const extractComplianceCybrillaWarnings = (
  external: Record<string, unknown>,
): CybrillaKycWarning[] => {
  const warnings: CybrillaKycWarning[] = [];
  const seen = new Set<string>();
  const status = external.status;
  const failed = status === false || readText(status).toLowerCase() === 'false';
  const reason = readText(external.reason);
  const action = readText(external.action);

  if (reason) {
    pushCybrillaWarning(warnings, seen, {
      field: 'kyc_compliance',
      label: 'KYC compliance check',
      code: action || undefined,
      status: readText(status) || undefined,
      reason,
    });
  } else if (failed && action) {
    pushCybrillaWarning(warnings, seen, {
      field: 'kyc_compliance',
      label: 'KYC compliance check',
      code: action,
      status: readText(status) || undefined,
      reason: action,
    });
  }

  const constraints = external.constraints;
  if (Array.isArray(constraints)) {
    constraints.forEach((constraint, index) => {
      const text = typeof constraint === 'string'
        ? constraint
        : JSON.stringify(constraint);
      pushCybrillaWarning(warnings, seen, {
        field: `constraints.${index}`,
        label: 'Investment constraint',
        reason: text,
      });
    });
  }

  return warnings;
};

const extractKycRequestCybrillaWarnings = (
  request: Record<string, unknown>,
): CybrillaKycWarning[] => {
  const warnings: CybrillaKycWarning[] = [];
  const seen = new Set<string>();
  const status = cleanStatus(readText(request.status));
  const reason = readText(request.reason)
    || readText(request.rejection_reason)
    || readText(request.message);

  if (!reason && !['rejected', 'failed', 'expired'].includes(status)) {
    return warnings;
  }

  pushCybrillaWarning(warnings, seen, {
    field: 'kyc_request',
    label: 'KYC application',
    code: status || undefined,
    status: readText(request.status) || undefined,
    reason: reason || status,
  });

  return warnings;
};

const extractBackendKycDecisionWarnings = (
  kyc: Record<string, unknown>,
): CybrillaKycWarning[] => {
  const warnings: CybrillaKycWarning[] = [];
  const seen = new Set<string>();

  const readinessReason = readText(kyc.readinessReason);
  if (readinessReason) {
    pushCybrillaWarning(warnings, seen, {
      field: 'readiness',
      label: 'Investor readiness',
      code: readText(kyc.readinessCode) || undefined,
      status: readText(kyc.readinessStatus) || undefined,
      reason: readinessReason,
    });
  }

  const complianceReason = readText(kyc.complianceReason);
  if (complianceReason) {
    pushCybrillaWarning(warnings, seen, {
      field: 'kyc_compliance',
      label: 'KYC compliance check',
      code: readText(kyc.complianceAction) || undefined,
      reason: complianceReason,
    });
  }

  const constraints = kyc.constraints;
  if (typeof constraints === 'string' && constraints.trim()) {
    try {
      const parsed = JSON.parse(constraints);
      if (Array.isArray(parsed)) {
        parsed.forEach((constraint, index) => {
          pushCybrillaWarning(warnings, seen, {
            field: `constraints.${index}`,
            label: 'Investment constraint',
            reason: typeof constraint === 'string' ? constraint : JSON.stringify(constraint),
          });
        });
      }
    } catch {
      pushCybrillaWarning(warnings, seen, {
        field: 'constraints',
        label: 'Investment constraint',
        reason: constraints,
      });
    }
  }

  return warnings;
};

const extractInvestorCybrillaReasonWarnings = (
  investor: Record<string, unknown>,
): CybrillaKycWarning[] => {
  const warnings: CybrillaKycWarning[] = [];
  const seen = new Set<string>();

  const entries: Array<[string, string, string, string?, string?]> = [
    ['kycReadinessReason', 'readiness', 'Investor readiness', 'kycReadinessCode', 'kycReadinessStatus'],
    ['panVerificationReason', 'pan', 'PAN', 'panVerificationCode', 'panVerificationStatus'],
    ['panAadhaarLinkReason', 'pan_aadhaar', 'PAN–Aadhaar link', 'panAadhaarLinkStatus'],
    ['kycComplianceReason', 'kyc_compliance', 'KYC compliance check', 'kycComplianceAction'],
  ];

  entries.forEach(([reasonKey, field, label, codeKey, statusKey]) => {
    const reason = readText(investor[reasonKey]);
    const code = codeKey ? readText(investor[codeKey]) : '';
    const status = statusKey ? readText(investor[statusKey]) : '';
    if (!reason) {
      if (field === 'readiness' && cleanStatus(status) === 'failed' && code) {
        pushCybrillaWarning(warnings, seen, {
          field,
          label,
          code: code || undefined,
          status: status || undefined,
          reason: code,
        });
      }
      return;
    }
    pushCybrillaWarning(warnings, seen, {
      field,
      label,
      code: code || undefined,
      status: status || undefined,
      reason,
    });
  });

  return warnings;
};

const mergeCybrillaWarnings = (...groups: CybrillaKycWarning[][]) => {
  const warnings: CybrillaKycWarning[] = [];
  const seen = new Set<string>();
  groups.flat().forEach((warning) => pushCybrillaWarning(warnings, seen, warning));
  return warnings;
};

/** Parse Cybrilla warnings verbatim from a Platizio KYC API payload or stored provider JSON. */
export const extractCybrillaWarningsFromPayload = (payload: unknown): CybrillaKycWarning[] => {
  if (!payload || typeof payload !== 'object') return [];
  const data = payload as Record<string, unknown>;
  const investor = asRecord(data.investor);
  const kyc = asRecord(data.kyc);
  const externalNode = asRecord(data.externalResponse)
    || extractPreVerification(data)
    || parseStoredPreVerification(data.externalKycPayloadJson)
    || parseStoredPreVerification(investor?.externalKycPayloadJson);

  const groups: CybrillaKycWarning[][] = [];

  if (externalNode?.object === 'pre_verification') {
    groups.push(extractPreVerificationCybrillaWarnings(externalNode as PreVerificationResponse));
  } else if (externalNode) {
    groups.push(extractComplianceCybrillaWarnings(externalNode));
  }

  const kycRequest = asRecord(data.kycRequest)
    || asRecord(data.kyc_request)
    || asRecord(asRecord(externalNode)?.kyc_request);
  if (kycRequest) {
    groups.push(extractKycRequestCybrillaWarnings(kycRequest));
  }

  if (kyc) {
    groups.push(extractBackendKycDecisionWarnings(kyc));
  }

  if (investor) {
    groups.push(extractInvestorCybrillaReasonWarnings(investor));
  }

  return mergeCybrillaWarnings(...groups);
};

/** @deprecated Use extractCybrillaWarningsFromPayload */
export const getCybrillaKycWarnings = (
  external?: PreVerificationResponse | null,
  investor?: Record<string, unknown> | null,
) => extractCybrillaWarningsFromPayload({ externalResponse: external, investor });

export const isKycRejectedState = (
  decision: PreVerificationDecision,
  investor?: Record<string, unknown> | null,
) => {
  if (decision.state === 'failed' || decision.state === 'retry') return true;
  const kycStatus = String(investor?.kycStatus || '').trim().toUpperCase();
  return ['FAILED', 'REJECTED', 'RETRY_REQUIRED'].includes(kycStatus);
};

type InvestorIdentity = {
  firstName?: string;
  lastName?: string;
  fullName?: string;
  pan?: string | null;
  dob?: string | null;
  dateOfBirth?: string | null;
  mobile?: string | null;
  email?: string | null;
  relationshipType?: string | null;
  guardianPan?: string | null;
};

type IdentityValidationOptions = {
  requireContact?: boolean;
};

/** Standard Indian PAN: AAAAA9999A (five letters, four digits, one letter). */
export const PAN_REGEX = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
/** Optional Cybrilla simulator pattern (XXXPXNNNNX) — for scripted test outcomes only. */
export const POA_SANDBOX_PAN_REGEX = /^[A-Z]{3}P[A-Z][0-9]{4}[A-Z]$/;
export const INDIAN_PAN_FORMAT_MESSAGE = 'Enter a valid PAN in AAAAA9999A format.';
export const POA_SANDBOX_PAN_HINT =
  'Optional Platizio simulator patterns (for testing specific outcomes only): '
  + 'XXXPX3751X = KYC-ready, XXXPX3753X = KYC unavailable, XXXPINNNNX = invalid, '
  + 'XXXPANNNNX = Aadhaar not linked. Any valid PAN (AAAAA9999A) works for normal tenant sandbox use.';
/** @deprecated Use POA_SANDBOX_PAN_REGEX */
export const CYBRILLA_SANDBOX_PAN_REGEX = POA_SANDBOX_PAN_REGEX;
/** @deprecated Use POA_SANDBOX_PAN_HINT */
export const CYBRILLA_SANDBOX_PAN_HINT = POA_SANDBOX_PAN_HINT;
export const MOBILE_REGEX = /^[6-9]\d{9}$/;
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const NAME_REGEX = /^[A-Za-z][A-Za-z .'-]{1,79}$/;
const DUMMY_NAME_REGEX = /\b(test|dummy|sample|asdf|qwerty|unknown|null|none)\b/i;
const LORD_VOLDEMORT_REGEX = /^lord\s+voldemort$/i;
const SANDBOX_DOB_MISMATCH = '2000-01-01';

export const isCybrillaSandboxMode = () => {
  if (import.meta.env.VITE_CYBRILLA_SANDBOX === 'false') return false;
  if (import.meta.env.VITE_CYBRILLA_SANDBOX === 'true') return true;
  return /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/|$)/.test(API_BASE_URL);
};

/** When true, enforce Cybrilla doc simulator PAN/name/DOB patterns client-side (QA only). */
export const isCybrillaStrictSimulatorMode = () =>
  import.meta.env.VITE_CYBRILLA_SANDBOX_STRICT_SIMULATOR === 'true';

export const normalizePan = (value?: string | null) =>
  String(value || '').trim().toUpperCase();

export const validatePanFormat = (
  value?: string | null,
  options: { required?: boolean } = {},
): string | null => {
  const required = options.required ?? true;
  const pan = normalizePan(value);
  if (!pan) {
    return required ? 'PAN is required.' : null;
  }
  if (!PAN_REGEX.test(pan)) {
    return INDIAN_PAN_FORMAT_MESSAGE;
  }
  return null;
};

/** Validate PAN before POA pre-verification. Tenants accept any AAAAA9999A PAN; simulator patterns are optional. */
export const validatePanBeforePoaApi = (
  value?: string | null,
  options: { required?: boolean } = {},
): string | null => {
  const structuralError = validatePanFormat(value, options);
  if (structuralError) {
    return structuralError;
  }
  const pan = normalizePan(value);
  const requiresSimulatorPan = isCybrillaSandboxMode() || isCybrillaStrictSimulatorMode();
  if (requiresSimulatorPan && pan && !POA_SANDBOX_PAN_REGEX.test(pan)) {
    if (isCybrillaStrictSimulatorMode()) {
      return 'Strict simulator mode: use a XXXPXNNNNX pattern PAN (4th character P). '
        + 'Set VITE_CYBRILLA_SANDBOX_STRICT_SIMULATOR=false to allow any valid PAN.';
    }
    return `Platizio sandbox requires a simulator PAN (pattern XXXPXNNNNX, 4th character P). ${POA_SANDBOX_PAN_HINT}`;
  }
  return null;
};

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
  } else if (
    !NAME_REGEX.test(fullName)
    || DUMMY_NAME_REGEX.test(fullName)
    || (isCybrillaStrictSimulatorMode() && LORD_VOLDEMORT_REGEX.test(fullName))
  ) {
    errors.fullName = 'Enter the investor legal name as per PAN records.';
    if (identity.firstName !== undefined) errors.firstName = errors.fullName;
    if (identity.lastName !== undefined) errors.lastName = errors.fullName;
  }

  const panError = validatePanFormat(pan);
  if (panError) {
    errors.pan = panError;
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
    } else if (isCybrillaStrictSimulatorMode() && dob === SANDBOX_DOB_MISMATCH) {
      errors.dob = 'Strict simulator mode: Platizio treats 2000-01-01 as a DOB mismatch test case.';
    } else {
      const minimumAgeError = validateInvestorMinimumAge(dob, {
        relationshipType: identity.relationshipType,
      });
      if (minimumAgeError) {
        errors.dob = minimumAgeError;
      }
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

  if (identity.relationshipType === 'MINOR') {
    const guardianPanError = validatePanFormat(identity.guardianPan);
    if (guardianPanError) {
      errors.guardianPan = guardianPanError === 'PAN is required.'
        ? 'Guardian PAN is required for minor folios.'
        : guardianPanError;
    }
  }

  return errors;
};

/** Validate identity immediately before a POA / KYC Cybrilla call. */
export const validateIdentityBeforeCybrillaPoa = (
  identity: InvestorIdentity,
  options: IdentityValidationOptions = {},
) => {
  const errors = validateInvestorIdentityForKyc(identity, options);
  const poaPanError = validatePanBeforePoaApi(identity.pan);
  if (poaPanError) {
    errors.pan = poaPanError;
  }
  if (identity.relationshipType === 'MINOR') {
    const guardianPoaError = validatePanBeforePoaApi(identity.guardianPan);
    if (guardianPoaError) {
      errors.guardianPan = guardianPoaError === 'PAN is required.'
        ? 'Guardian PAN is required for minor folios.'
        : guardianPoaError;
    }
  }
  return errors;
};

export const buildPreVerificationFromBackendState = (
  investor?: Record<string, unknown> | null,
  kyc?: Record<string, unknown> | null,
): PreVerificationResponse | null => {
  if (!investor && !kyc) return null;

  const readinessStatus = readText(investor?.kycReadinessStatus ?? kyc?.readinessStatus);
  const readinessCode = readText(investor?.kycReadinessCode ?? kyc?.readinessCode);
  const readinessReason = readText(investor?.kycReadinessReason ?? kyc?.readinessReason);
  const panStatus = readText(investor?.panVerificationStatus);
  const panCode = readText(investor?.panVerificationCode);
  const panReason = readText(investor?.panVerificationReason);
  const hasReadiness = Boolean(readinessStatus || readinessCode || readinessReason);
  const hasPan = Boolean(panStatus || panCode || panReason);
  const kycState = readText(kyc?.state);

  if (!hasReadiness && !hasPan) {
    if (kycState === 'FRESH_KYC_REQUIRED') {
      return {
        object: 'pre_verification',
        status: 'completed',
        readiness: {
          status: 'failed',
          code: 'kyc_unavailable',
          reason: readText(kyc?.message) || 'No reusable KYC record was found for this investor.',
        },
      };
    }
    if (kycState === 'VERIFIED' || kycState === 'VERIFIED_WITH_CONSTRAINTS') {
      return {
        object: 'pre_verification',
        status: 'completed',
        readiness: { status: 'verified' },
      };
    }
    return null;
  }

  const result: PreVerificationResponse = {
    object: 'pre_verification',
    status: 'completed',
  };
  if (hasReadiness) {
    result.readiness = {
      status: readinessStatus || undefined,
      code: readinessCode || undefined,
      reason: readinessReason || undefined,
    };
  }
  if (hasPan) {
    result.pan = {
      status: panStatus || undefined,
      code: panCode || undefined,
      reason: panReason || undefined,
    };
  }
  return result;
};

/**
 * Resolve POA pre-verification from a Platizio KYC API payload.
 * Backend may return a kyc_request in externalResponse after pre-verification
 * completes and a fresh KYC application is started — that is not an error.
 */
export const resolvePreVerificationFromPayload = (payload: unknown): PreVerificationResponse | null => {
  if (!payload || typeof payload !== 'object') return null;
  const data = payload as Record<string, unknown>;
  const externalNode = asRecord(data.externalResponse)
    || (readText(data.object) === 'pre_verification' ? data : null);

  if (externalNode?.object === 'pre_verification') {
    return externalNode as PreVerificationResponse;
  }

  const investor = asRecord(data.investor);
  const stored = parseStoredPreVerification(investor?.externalKycPayloadJson);
  if (stored) return stored;

  const fromState = buildPreVerificationFromBackendState(investor, asRecord(data.kyc));
  if (fromState) return fromState;

  if (externalNode?.object === 'kyc_request') {
    const readinessReason = readText(investor?.kycReadinessReason);
    const readinessCode = readText(investor?.kycReadinessCode) || 'kyc_unavailable';
    return {
      object: 'pre_verification',
      status: 'completed',
      readiness: {
        status: readText(investor?.kycReadinessStatus) || 'failed',
        code: readinessCode,
        reason: readinessReason || undefined,
      },
    };
  }

  return null;
};

export const extractPreVerification = (payload: any): PreVerificationResponse | null =>
  resolvePreVerificationFromPayload(payload);

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

export const isPanAadhaarNotLinked = (
  external?: PreVerificationResponse | null,
  investor?: Record<string, unknown> | null,
) => {
  const panCode = cleanStatus(external?.pan?.code);
  if (panCode === 'aadhaar_not_linked') return true;
  return cleanStatus(String(investor?.panAadhaarLinkStatus || '')) === 'not_linked';
};

export const extractIdentityDocumentFetch = (payload: unknown): IdentityDocumentFetch | null => {
  if (!payload || typeof payload !== 'object') return null;
  const data = payload as Record<string, unknown>;
  const external = asRecord(data.externalResponse)
    || (readText(data.object) === 'identity_document' ? data : null);
  if (!external) return null;
  const fetch = asRecord(external.fetch);
  return {
    identityDocumentId: readText(external.id) || undefined,
    redirectUrl: readText(fetch?.redirect_url) || undefined,
    status: readText(fetch?.status) || undefined,
    reason: readText(fetch?.reason) || undefined,
  };
};

export const isIdentityDocumentFetchComplete = (status?: string | null) => {
  const normalized = cleanStatus(status);
  return normalized === 'successful' || normalized === 'completed' || normalized === 'verified';
};

/** Platizio backend view of Cybrilla FP /v2/identity_documents Aadhaar fetch. */
export type AadhaarVerificationView = {
  identityDocumentId?: string;
  redirectUrl?: string;
  fetchStatus?: string;
  fetchReason?: string;
  fetchComplete: boolean;
  proofsAttachedToKycRequest: boolean;
  investor?: Record<string, unknown> | null;
};

export const normalizeAadhaarVerificationResponse = (payload: unknown): AadhaarVerificationView | null => {
  if (!payload || typeof payload !== 'object') return null;
  const data = payload as Record<string, unknown>;
  const investor = asRecord(data.investor);
  const fromApi: AadhaarVerificationView = {
    identityDocumentId: readText(data.identityDocumentId) || undefined,
    redirectUrl: readText(data.redirectUrl) || undefined,
    fetchStatus: readText(data.fetchStatus) || undefined,
    fetchReason: readText(data.fetchReason) || undefined,
    fetchComplete: Boolean(data.fetchComplete),
    proofsAttachedToKycRequest: Boolean(data.proofsAttachedToKycRequest),
    investor,
  };
  const fromExternal = extractIdentityDocumentFetch(payload);
  if (fromExternal) {
    return {
      identityDocumentId: fromApi.identityDocumentId || fromExternal.identityDocumentId,
      redirectUrl: fromApi.redirectUrl || fromExternal.redirectUrl,
      fetchStatus: fromApi.fetchStatus || fromExternal.status,
      fetchReason: fromApi.fetchReason || fromExternal.reason,
      fetchComplete: fromApi.fetchComplete || isIdentityDocumentFetchComplete(fromApi.fetchStatus || fromExternal.status),
      proofsAttachedToKycRequest: fromApi.proofsAttachedToKycRequest,
      investor: fromApi.investor || investor,
    };
  }
  if (fromApi.identityDocumentId || fromApi.fetchStatus || fromApi.redirectUrl) {
    return {
      ...fromApi,
      fetchComplete: fromApi.fetchComplete || isIdentityDocumentFetchComplete(fromApi.fetchStatus),
    };
  }
  return null;
};

export const readAadhaarVerificationFromInvestor = (
  investor?: Record<string, unknown> | null,
): AadhaarVerificationView | null => {
  if (!investor) return null;
  const identityDocumentId = readText(investor.externalIdentityDocumentId);
  const fetchStatus = readText(investor.aadhaarFetchStatus);
  if (!identityDocumentId && !fetchStatus) return null;
  return {
    identityDocumentId: identityDocumentId || undefined,
    fetchStatus: fetchStatus || undefined,
    fetchReason: readText(investor.aadhaarFetchReason) || undefined,
    fetchComplete: isIdentityDocumentFetchComplete(fetchStatus),
    proofsAttachedToKycRequest: false,
    investor,
  };
};

export type PreVerificationDecisionContext = {
  investor?: Record<string, unknown> | null;
  kyc?: Record<string, unknown> | null;
};

/**
 * Cybrilla POA only allows a fresh {@code /v2/kyc_requests} when readiness failed with
 * {@code kyc_unavailable}. Other readiness failures must fix identity or retry pre-verification first.
 */
export const canCreateFreshKycRequest = (
  preVerification?: PreVerificationResponse | null,
  investor?: Record<string, unknown> | null,
): boolean => {
  const demographicFields = ['pan', 'name', 'date_of_birth'] as const;
  for (const field of demographicFields) {
    const fromPreVerification = cleanStatus(preVerification?.[field]?.status);
    if (fromPreVerification === 'failed') return false;
    if (field === 'pan' && cleanStatus(String(investor?.panVerificationStatus || '')) === 'failed') {
      return false;
    }
  }

  const readinessStatus = cleanStatus(preVerification?.readiness?.status)
    || cleanStatus(String(investor?.kycReadinessStatus || ''));
  const readinessCode = cleanStatus(preVerification?.readiness?.code)
    || cleanStatus(String(investor?.kycReadinessCode || ''));

  if (readinessStatus === 'verified') return false;
  if (readinessStatus === 'failed') {
    return readinessCode === 'kyc_unavailable' || readinessCode === 'unavailable';
  }
  if (!readinessStatus
      && cleanStatus(String(investor?.panVerificationStatus || preVerification?.pan?.status || '')) === 'verified') {
    return true;
  }
  return false;
};

/** True when Cybrilla investor readiness failed in a way that blocks fresh KYC request creation. */
export const isReadinessFailureBlockingKycRequest = (
  preVerification?: PreVerificationResponse | null,
  investor?: Record<string, unknown> | null,
): boolean => {
  const readinessStatus = cleanStatus(preVerification?.readiness?.status)
    || cleanStatus(String(investor?.kycReadinessStatus || ''));
  if (readinessStatus !== 'failed') return false;
  return !canCreateFreshKycRequest(preVerification, investor);
};

/** Verbatim Cybrilla readiness failure/success reason from POA JSON or persisted investor fields. */
export const readCybrillaReadinessReason = (
  external?: PreVerificationResponse | null,
  context?: PreVerificationDecisionContext,
): string => {
  const fromExternal = readText(external?.readiness?.reason);
  if (fromExternal) return fromExternal;
  const investor = context?.investor;
  const kyc = context?.kyc;
  return readText(investor?.kycReadinessReason) || readText(kyc?.readinessReason);
};

export const getPreVerificationDecision = (
  external?: PreVerificationResponse | null,
  context?: PreVerificationDecisionContext,
): PreVerificationDecision => {
  if (!external) {
    return {
      state: 'idle',
      title: 'Pre-verification not started',
      message: 'Run POA pre-verification to validate PAN, name, date of birth, and investor readiness.',
      canProceed: false,
      requiresFreshKyc: false,
      requiresPanAadhaarLink: false,
    };
  }

  const status = cleanStatus(external.status);
  if (status && status !== 'completed') {
    return {
      state: status === 'accepted' ? 'accepted' : 'pending',
      title: 'Pre-verification in progress',
      message: 'Platizio accepted the request. Refresh the status after a short interval.',
      canProceed: false,
      requiresFreshKyc: false,
      requiresPanAadhaarLink: false,
    };
  }

  const readinessStatus = cleanStatus(external.readiness?.status)
    || cleanStatus(String(context?.investor?.kycReadinessStatus || context?.kyc?.readinessStatus || ''));
  const readinessCode = cleanStatus(external.readiness?.code)
    || cleanStatus(String(context?.investor?.kycReadinessCode || context?.kyc?.readinessCode || ''));
  const readinessReason = readCybrillaReadinessReason(external, context);

  if (readinessStatus === 'verified') {
    return {
      state: 'verified',
      title: 'Investor is ready to invest',
      message: 'PAN, KYC readiness, and demographic checks passed.',
      canProceed: true,
      requiresFreshKyc: false,
      requiresPanAadhaarLink: false,
    };
  }

  if (readinessStatus === 'failed') {
    if (readinessCode === 'kyc_unavailable' || readinessCode === 'unavailable') {
      return {
        state: 'failed',
        title: 'Fresh KYC required',
        message: readinessReason
          || 'No existing KYC was found for this PAN — this is expected for a new investor and is not an error. '
          + 'Click "Create KYC request" to start a fresh KYC application, or re-run pre-verification.',
        canProceed: false,
        requiresFreshKyc: true,
        requiresPanAadhaarLink: false,
      };
    }

    if (readinessCode === 'upstream_error') {
      return {
        state: 'retry',
        title: 'Verification needs retry',
        message: readinessReason || 'Platizio could not reach the upstream KYC source. Retry the pre-verification.',
        canProceed: false,
        requiresFreshKyc: false,
        requiresPanAadhaarLink: false,
      };
    }

    return {
      state: readinessCode === 'kyc_incomplete' || readinessCode === 'unknown' ? 'retry' : 'failed',
      title: 'Investor is not ready yet',
      message: readinessReason || `Readiness failed${readinessCode ? ` (${readinessCode})` : ''}.`,
      canProceed: false,
      requiresFreshKyc: false,
      requiresPanAadhaarLink: false,
    };
  }

  const fieldNames: Array<keyof PreVerificationResponse> = ['pan', 'name', 'date_of_birth'];
  const failedField = fieldNames.find(field => cleanStatus((external[field] as PreVerificationHash | undefined)?.status) === 'failed');
  if (failedField) {
    const result = external[failedField] as PreVerificationHash | undefined;
    const fieldCode = cleanStatus(result?.code);
    if (failedField === 'pan' && fieldCode === 'aadhaar_not_linked') {
      return {
        state: 'failed',
        title: 'PAN–Aadhaar link required',
        message: result?.reason || 'PAN is not linked with Aadhaar.',
        canProceed: false,
        requiresFreshKyc: false,
        requiresPanAadhaarLink: true,
      };
    }
    return {
      state: fieldCode === 'upstream_error' ? 'retry' : 'failed',
      title: 'PAN details did not match',
      message: result?.reason || `${preVerificationFieldLabel(String(failedField))} failed${result?.code ? ` (${result.code})` : ''}.`,
      canProceed: false,
      requiresFreshKyc: false,
      requiresPanAadhaarLink: false,
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
      requiresPanAadhaarLink: false,
    };
  }

  return {
    state: 'pending',
    title: 'Pre-verification pending',
    message: 'The response does not yet contain a final verification result. Refresh before continuing.',
    canProceed: false,
    requiresFreshKyc: false,
    requiresPanAadhaarLink: false,
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

export type KycReasonDialogInput = {
  decision?: PreVerificationDecision | null;
  warnings?: CybrillaKycWarning[];
  preVerification?: PreVerificationResponse | null;
  errorMessage?: string;
  flowMessage?: string;
};

/** Aggregate Cybrilla reasoning for a modal popup shown to the distributor/investor. */
export const buildKycReasonDialogContent = (
  input: KycReasonDialogInput,
): {
  title: string;
  variant: 'error' | 'warning' | 'success' | 'info';
  summary?: string;
  warnings: CybrillaKycWarning[];
  fieldRows: ReturnType<typeof getPreVerificationRows>;
} | null => {
  const warnings = input.warnings ?? [];
  const fieldRows = getPreVerificationRows(input.preVerification);
  const decision = input.decision;
  const errorMessage = readText(input.errorMessage);
  const flowMessage = readText(input.flowMessage);
  const hasFieldDetail = fieldRows.some(
    row => readText(row.reason) || readText(row.code) || cleanStatus(row.status) === 'failed',
  );

  if (errorMessage) {
    return {
      title: 'Platizio KYC error',
      variant: 'error',
      summary: errorMessage,
      warnings,
      fieldRows,
    };
  }

  if (!decision && warnings.length === 0 && !hasFieldDetail && !flowMessage) {
    return null;
  }

  const variant: 'error' | 'warning' | 'success' | 'info' = decision?.canProceed
    ? 'success'
    : decision?.state === 'retry'
      ? 'warning'
      : decision?.state === 'verified'
        ? 'success'
        : decision?.state === 'failed' || warnings.length > 0
          ? 'error'
          : 'info';

  const title = decision?.title
    || (warnings.length > 0 ? 'Platizio KYC details' : 'KYC status update');
  const summary = decision?.message || flowMessage || undefined;

  const shouldShow = Boolean(
    summary
    || warnings.length > 0
    || hasFieldDetail
    || decision?.state === 'failed'
    || decision?.state === 'retry'
    || decision?.canProceed,
  );

  if (!shouldShow) return null;

  return {
    title,
    variant,
    summary,
    warnings,
    fieldRows,
  };
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

/** Stable fingerprint for PAN/name/DOB/mobile used to detect identity edits between KYC attempts. */
export const computeIdentityFingerprint = (identity: InvestorIdentity & { dateOfBirth?: string | null }) => {
  const dob = String(identity.dob || identity.dateOfBirth || '').trim();
  return JSON.stringify({
    fullName: getInvestorFullName(identity).toUpperCase(),
    pan: normalizePan(identity.pan),
    dob,
    mobile: normalizeMobile(identity.mobile),
    email: String(identity.email || '').trim().toLowerCase(),
    relationshipType: identity.relationshipType || 'SELF',
    guardianPan: normalizePan(identity.guardianPan),
  });
};

/** True when the investor edited identity since the last saved KYC attempt. */
export const shouldForceNewKycCheck = (
  lastKycFingerprint: string,
  currentFingerprint: string,
  savedInvestor?: { pan?: string | null; fullName?: string | null; dateOfBirth?: string | null } | null,
) => {
  if (lastKycFingerprint && lastKycFingerprint !== currentFingerprint) {
    return true;
  }
  if (!savedInvestor) {
    return false;
  }
  const savedFingerprint = computeIdentityFingerprint({
    fullName: savedInvestor.fullName || '',
    pan: savedInvestor.pan,
    dob: typeof savedInvestor.dateOfBirth === 'string'
      ? savedInvestor.dateOfBirth.substring(0, 10)
      : '',
  });
  return Boolean(savedFingerprint && savedFingerprint !== currentFingerprint);
};
