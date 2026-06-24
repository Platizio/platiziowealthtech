import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom';
import { motion, AnimatePresence } from 'motion/react';
import {
  ArrowLeft, ArrowRight, CheckCircle2, Loader2, Check,
  ShieldCheck, Upload, Building2, User, AlertTriangle, X, RefreshCw, Fingerprint, Clock,
} from 'lucide-react';
import CybrillaKycWarnings from '../components/CybrillaKycWarnings';
import CybrillaKycReasonDialog, { type CybrillaKycReasonDialogContent } from '../components/CybrillaKycReasonDialog';
import KycFlowPanel from '../components/KycFlowPanel';
import KycProviderLink from '../components/KycProviderLink';
import PincodeCityFields from '../components/PincodeCityFields';
import SandboxDemoGuide from '../components/SandboxDemoGuide';
import ContactVerification from '../components/ContactVerification';
import { fetchIfscDetails } from '../utils/referenceLookup';
import {
  listInvestorDocuments,
  savedDocumentsByKey,
  uploadInvestorDocument,
  type InvestorDocumentKey,
  type SavedInvestorDocument,
} from '../utils/investorDocuments';
import {
  shouldLockAadhaarStart,
  shouldLockEsignStart,
  shouldLockKycRequestCreate,
  shouldLockPoaRunButton,
  isPoaPreVerificationComplete,
  validateInvestorMinimumAge,
} from '../utils/kycActionLocks';
import type { SandboxDemoScenario } from '../utils/sandboxDemoData';
import { apiFetch } from '../config/api';
import { buildValidationSummary, mapServerErrorsToState, parseServerValidation } from '../utils/serverValidation';
import {
  extractPreVerification,
  resolvePreVerificationFromPayload,
  buildPreVerificationFromBackendState,
  readCybrillaReadinessReason,
  canCreateFreshKycRequest,
  isReadinessFailureBlockingKycRequest,
  getPreVerificationDecision,
  getPreVerificationRows,
  normalizeMobile,
  normalizePan,
  parseStoredPreVerification,
  preVerificationStatusClasses,
  computeIdentityFingerprint,
  shouldForceNewKycCheck,
  isCybrillaSandboxMode,
  validateInvestorIdentityForKyc,
  validateIdentityBeforeCybrillaPoa,
  extractCybrillaWarningsFromPayload,
  buildKycReasonDialogContent,
  type KycReasonDialogInput,
  isIdentityDocumentFetchComplete,
  isPanAadhaarNotLinked,
  normalizeAadhaarVerificationResponse,
  readAadhaarVerificationFromInvestor,
  type CybrillaKycWarning,
} from '../utils/kycPreVerification';
import {
  normalizeEsignVerificationResponse,
  normalizeKycFlowStatus,
  buildKycPostbackUrl,
  type KycFlowNextAction,
  type KycFlowStatusView,
} from '../utils/kycFlow';
import {
  canProceedFromKycStep,
  mapBackendNextStepToWizardStep,
} from '../utils/onboardingResume';

// ── Step metadata ────────────────────────────────────────────────────────────
const STEPS = [
  { id: 1, label: 'Basic Info' },
  { id: 2, label: 'Consent' },
  { id: 3, label: 'Personal' },
  { id: 4, label: 'KYC' },
  { id: 5, label: 'Bank' },
  { id: 6, label: 'FATCA' },
  { id: 7, label: 'Documents' },
];

// ── Props ────────────────────────────────────────────────────────────────────
interface Props {
  prospect?: {
    firstName?: string;
    lastName?: string;
    mobile?: string;
    email?: string;
    pan?: string;
    dob?: string;
  };
  userData?: {
    id?: string;
    distributorId?: string;
    email?: string;
    role?: string;
  } | null;
  resumeInvestor?: any | null;
  resumeInvestorId?: string | null;
  resumeStep?: number | string | null;
  onComplete: () => void;
  onBack: () => void;
}

// ── Shared field wrapper ──────────────────────────────────────────────────────
function Field({
  label, required, children, error,
}: { label: string; required?: boolean; children: React.ReactNode; error?: string }) {
  return (
    <div>
      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
        {label}
        {required && <span className="text-red-400 ml-0.5">*</span>}
      </label>
      {children}
      {error && <p className="mt-1.5 text-xs font-medium text-red-600">{error}</p>}
    </div>
  );
}

const inp = 'w-full px-3.5 py-2.5 text-sm bg-white border border-slate-200 rounded-xl focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';
const sel = inp + ' cursor-pointer';
const MAX_DOCUMENT_SIZE = 5 * 1024 * 1024;
const ONBOARDING_DRAFT_STORAGE_PREFIX = 'platizio:onboardingDraft:';

const onboardingDraftStorageKey = (distributorId: string, pan: string) =>
  `${ONBOARDING_DRAFT_STORAGE_PREFIX}${distributorId}:${normalizePan(pan)}`;
const ALLOWED_DOCUMENT_TYPES = new Set(['application/pdf', 'image/jpeg', 'image/png']);
const ALLOWED_DOCUMENT_EXTENSIONS = new Set(['pdf', 'jpg', 'jpeg', 'png']);
const IFSC_REGEX = /^[A-Z]{4}0[A-Z0-9]{6}$/;
const ACCOUNT_NUMBER_REGEX = /^\d{9,18}$/;

const normalizeStatus = (value?: string) => String(value || '').trim().toUpperCase();

const splitFullName = (value?: string) => {
  const parts = String(value || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return { firstName: '', lastName: '' };
  return {
    firstName: parts[0],
    lastName: parts.slice(1).join(' '),
  };
};

const noteValue = (notes: string | undefined, key: string) => {
  const entry = String(notes || '')
    .split(';')
    .map(item => item.trim())
    .find(item => item.toLowerCase().startsWith(`${key.toLowerCase()}=`));
  return entry ? entry.slice(entry.indexOf('=') + 1).trim() : '';
};

const clampResumeStep = (value: number | string | null | undefined) => {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? Math.min(Math.max(Math.round(numeric), 1), STEPS.length) : null;
};

const deriveResumeStep = (
  investor: any,
  requestedStep?: number | string | null,
  options?: { nextStep?: string; documentsComplete?: boolean },
) => {
  const fromBackend = mapBackendNextStepToWizardStep(options?.nextStep, options?.documentsComplete);
  if (fromBackend != null) return fromBackend;

  const explicitStep = clampResumeStep(requestedStep);
  if (explicitStep) return explicitStep;

  const kycStatus = normalizeStatus(investor?.kycStatus);
  const bankStatus = normalizeStatus(investor?.bankVerificationStatus);
  if (kycStatus !== 'COMPLETED' && kycStatus !== 'VERIFIED') return 4;
  if (!bankStatus || ['NOT_CAPTURED', 'VERIFICATION_PENDING', 'PENDING', 'FAILED'].includes(bankStatus)) return 5;
  if (options?.documentsComplete === false) return 7;
  return 6;
};

const REQUIRED_DOCUMENT_KEYS: InvestorDocumentKey[] = ['pan', 'address', 'signature'];

const identitySnapshot = (identity: {
  firstName?: string;
  lastName?: string;
  pan?: string;
  dob?: string;
  mobile?: string;
  email?: string;
  relationshipType?: string;
  guardianPan?: string;
}) => {
  const fullName = `${identity.firstName || ''} ${identity.lastName || ''}`.replace(/\s+/g, ' ').trim();
  return JSON.stringify({
    fullName: fullName.toUpperCase(),
    pan: normalizePan(identity.pan || ''),
    dob: identity.dob || '',
    mobile: normalizeMobile(identity.mobile || ''),
    email: String(identity.email || '').trim().toLowerCase(),
    relationshipType: identity.relationshipType || 'SELF',
    guardianPan: normalizePan(identity.guardianPan || ''),
  });
};

const kycPhaseFromInvestor = (investor: any, fallback: 'idle' | 'checking' | 'accepted' | 'verified' | 'failed' | 'retry' | 'pending' = 'idle') => {
  const status = normalizeStatus(investor?.kycStatus);
  if (status === 'COMPLETED' || status === 'VERIFIED') return 'verified';
  if (status === 'IN_PROGRESS' || status === 'PENDING') return 'pending';
  if (status === 'FAILED' || status === 'REJECTED') return 'failed';
  if (status === 'RETRY_REQUIRED') return 'retry';
  return fallback;
};

const validateDocumentFile = (file: File) => {
  const extension = file.name.split('.').pop()?.toLowerCase() || '';
  if (!ALLOWED_DOCUMENT_TYPES.has(file.type) && !ALLOWED_DOCUMENT_EXTENSIONS.has(extension)) {
    return 'Only PDF, JPG, and PNG files are allowed.';
  }
  if (file.size > MAX_DOCUMENT_SIZE) {
    return 'File size must be 5 MB or less.';
  }
  return '';
};

// ═══════════════════════════════════════════════════════════════════════════════
export default function InvestorOnboarding({ prospect, userData, resumeInvestor, resumeInvestorId, resumeStep, onComplete, onBack }: Props) {
  const isResumeMode = Boolean(resumeInvestor?.id || resumeInvestorId);
  const [step, setStep] = useState(() => clampResumeStep(resumeStep) || 1);
  const [submitted, setSubmitted] = useState(false);
  const [externalSyncPending, setExternalSyncPending] = useState(false);
  const [externalSyncMessage, setExternalSyncMessage] = useState('');
  const [showExternalSyncNotice, setShowExternalSyncNotice] = useState(false);
  const [bankVerificationResult, setBankVerificationResult] = useState<any | null>(null);
  const [bankVerificationMessage, setBankVerificationMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [resumeLoading, setResumeLoading] = useState(Boolean(resumeInvestorId && !resumeInvestor?.id));
  const [resumeError, setResumeError] = useState('');
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [refNum] = useState(() => 'APX' + Date.now().toString().slice(-8));

  // ── F4: investor-approval gate ──────────────────────────────────────────────
  // The distributor freezes a snapshot for the investor to approve. Finalize is
  // blocked until GET /investors/{id}/onboarding/approval-status returns ATTESTED.
  const [approvalStatus, setApprovalStatus] = useState<string>('NONE');
  const [approvalLoading, setApprovalLoading] = useState(false);
  const [approvalSubmitting, setApprovalSubmitting] = useState(false);
  const [approvalError, setApprovalError] = useState('');

  // ── Step 1 — Basic Identity ─────────────────────────────────────────────────
  const initialResumeName = splitFullName(resumeInvestor?.fullName || resumeInvestor?.name);
  const [s1, setS1] = useState({
    firstName: initialResumeName.firstName || prospect?.firstName || '',
    lastName: initialResumeName.lastName || prospect?.lastName || '',
    pan: resumeInvestor?.pan || prospect?.pan || '',
    dob: resumeInvestor?.dateOfBirth || resumeInvestor?.dob || prospect?.dob || '',
    mobile: resumeInvestor?.mobileNumber || resumeInvestor?.mobile || prospect?.mobile || '',
    email: resumeInvestor?.email || prospect?.email || '',
    relationshipType: resumeInvestor?.relationshipType || '', // DF-09: no auto-populated default; distributor must choose
    householdName: resumeInvestor?.householdName || '',
    guardianPan: resumeInvestor?.guardianPan || '',
  });

  // ── Step 2 — Consent (Aadhaar OTP happens on Cybrilla Digilocker in step 3) ─
  const [consentDataProcessing, setConsentDataProcessing] = useState(false);
  const [consentCybrillaKyc, setConsentCybrillaKyc] = useState(false);
  const kycCheckInFlightRef = useRef(false);
  const kycPollAttemptsRef = useRef(0);

  // ── Step 3 — KYC ────────────────────────────────────────────────────────────
  const [kycPhase, setKycPhase] = useState<'idle' | 'checking' | 'accepted' | 'verified' | 'failed' | 'retry' | 'pending'>('idle');
  const [draftInvestor, setDraftInvestor] = useState<any | null>(null);
  const [draftIdentityFingerprint, setDraftIdentityFingerprint] = useState('');
  const [lastKycIdentityFingerprint, setLastKycIdentityFingerprint] = useState('');
  const [kycPreVerification, setKycPreVerification] = useState<any | null>(null);
  const [kycValidationErrors, setKycValidationErrors] = useState<Record<string, string>>({});
  const [kycActionError, setKycActionError] = useState('');
  const [kycActionMessage, setKycActionMessage] = useState('');
  const [creatingKycRequest, setCreatingKycRequest] = useState(false);
  const [autoRunKycOnStep4, setAutoRunKycOnStep4] = useState(false);
  const [cybrillaApiWarnings, setCybrillaApiWarnings] = useState<CybrillaKycWarning[]>([]);
  const [aadhaarRedirectUrl, setAadhaarRedirectUrl] = useState('');
  const [aadhaarFetchStatus, setAadhaarFetchStatus] = useState('');
  const [aadhaarDocumentId, setAadhaarDocumentId] = useState('');
  const [aadhaarProofsAttached, setAadhaarProofsAttached] = useState(false);
  const [aadhaarActionLoading, setAadhaarActionLoading] = useState('');
  const [kycFlowStatus, setKycFlowStatus] = useState<KycFlowStatusView | null>(null);
  const [kycFlowLoading, setKycFlowLoading] = useState(false);
  const [kycFlowError, setKycFlowError] = useState('');
  const [kycFlowActionLoading, setKycFlowActionLoading] = useState('');
  const [kycReasonDialogOpen, setKycReasonDialogOpen] = useState(false);
  const [kycReasonDialogContent, setKycReasonDialogContent] = useState<CybrillaKycReasonDialogContent | null>(null);
  const kycReasonDialogSigRef = useRef('');
  const [esignRedirectUrl, setEsignRedirectUrl] = useState('');
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const postbackHandledRef = useRef(false);

  // ── Step 4 — Personal ───────────────────────────────────────────────────────
  const [s4, setS4] = useState({
    gender: noteValue(resumeInvestor?.onboardingNotes, 'gender'),
    occupation: noteValue(resumeInvestor?.onboardingNotes, 'occupation'),
    income: noteValue(resumeInvestor?.onboardingNotes, 'income'),
    contactOwner: noteValue(resumeInvestor?.onboardingNotes, 'contact_owner') || '', // DF-09: no default
    addressLine1: resumeInvestor?.addressLine1 || '',
    addressLine2: resumeInvestor?.addressLine2 || '',
    city: resumeInvestor?.city || '',
    state: resumeInvestor?.state || '',
    postalCode: resumeInvestor?.postalCode || '',
  });
  const [showNominee, setShowNominee] = useState(false);
  const [nominee, setNominee] = useState({ name: '', relation: '' });

  // ── Step 5 — Bank ────────────────────────────────────────────────────────────
  const [s5, setS5] = useState({ accNumber: '', ifsc: '', accType: 'Savings', primary: true });
  const [bankName, setBankName] = useState('');
  const [ifscBranchName, setIfscBranchName] = useState('');
  const [ifscLookupLoading, setIfscLookupLoading] = useState(false);
  const [existingBanks, setExistingBanks] = useState<any[]>([]);
  const [bankAccountsLoading, setBankAccountsLoading] = useState(false);
  const [bankEditMode, setBankEditMode] = useState(false);
  const [bankStepBusy, setBankStepBusy] = useState(false);
  const [bankStepError, setBankStepError] = useState('');
  const [bankStepMessage, setBankStepMessage] = useState('');

  // ── Step 6 — FATCA ───────────────────────────────────────────────────────────
  const [s6, setS6] = useState({
    taxResidency: noteValue(resumeInvestor?.onboardingNotes, 'tax_residency') || '', // DF-09: no default
    taxCountry: '',
    incomeSlab: '',
    politicalExp: noteValue(resumeInvestor?.onboardingNotes, 'pep') || '', // DF-09: no default
    declared: false,
    termsAccepted: false, // Task 4 / DF-10: T&C acceptance, separate from FATCA declaration
  });

  // ── Step 7 — Documents ───────────────────────────────────────────────────────
  const [docs, setDocs] = useState<{ pan: File | null; address: File | null; signature: File | null }>({
    pan: null,
    address: null,
    signature: null,
  });
  const [docErrors, setDocErrors] = useState<Record<string, string>>({});
  const [docProgress, setDocProgress] = useState<Record<string, number>>({});
  const [savedDocuments, setSavedDocuments] = useState<Partial<Record<InvestorDocumentKey, SavedInvestorDocument>>>({});
  const [docUploading, setDocUploading] = useState<Partial<Record<InvestorDocumentKey, boolean>>>({});
  const [docsLoading, setDocsLoading] = useState(false);

  const distributorId = userData?.id || userData?.distributorId;
  const fullName = `${s1.firstName} ${s1.lastName}`.replace(/\s+/g, ' ').trim();
  const identityFingerprint = computeIdentityFingerprint({
    firstName: s1.firstName,
    lastName: s1.lastName,
    pan: s1.pan,
    dob: s1.dob,
    mobile: s1.mobile,
    email: s1.email,
    relationshipType: s1.relationshipType,
    guardianPan: s1.guardianPan,
  });

  useEffect(() => {
    if (isResumeMode || draftInvestor?.id || !distributorId) return;
    const pan = normalizePan(s1.pan);
    if (!pan) return;
    try {
      const raw = sessionStorage.getItem(onboardingDraftStorageKey(distributorId, pan));
      if (!raw) return;
      const stored = JSON.parse(raw) as { investor?: { id?: string }; fingerprint?: string };
      if (!stored.investor?.id) return;
      setDraftInvestor(stored.investor);
      if (stored.fingerprint) {
        setDraftIdentityFingerprint(stored.fingerprint);
      }
    } catch {
      // Ignore corrupt session storage entries.
    }
  }, [isResumeMode, draftInvestor?.id, distributorId, s1.pan]);

  useEffect(() => {
    if (!draftInvestor?.id || !distributorId) return;
    const pan = normalizePan(s1.pan);
    if (!pan) return;
    try {
      sessionStorage.setItem(onboardingDraftStorageKey(distributorId, pan), JSON.stringify({
        investor: draftInvestor,
        fingerprint: draftIdentityFingerprint || identityFingerprint,
      }));
    } catch {
      // Ignore quota or privacy mode storage failures.
    }
  }, [draftInvestor, draftIdentityFingerprint, distributorId, identityFingerprint, s1.pan]);
  const activeInvestor = draftInvestor || resumeInvestor;
  const kycDecision = getPreVerificationDecision(kycPreVerification, { investor: activeInvestor as Record<string, unknown> | null });
  const poaInvestorId = draftInvestor?.id || resumeInvestor?.id;
  const poaKycCheckId = kycPreVerification?.id
    || draftInvestor?.externalKycCheckId
    || resumeInvestor?.externalKycCheckId;
  const showPoaRefreshButton = Boolean(poaKycCheckId) && !kycDecision.canProceed;
  const kycRequestId = activeInvestor?.externalKycRequestId || '';
  const panAadhaarNotLinked = kycDecision.requiresPanAadhaarLink
    || isPanAadhaarNotLinked(kycPreVerification, activeInvestor as Record<string, unknown> | null);
  const aadhaarFetchComplete = isIdentityDocumentFetchComplete(aadhaarFetchStatus) || aadhaarProofsAttached;
  const esignId = activeInvestor?.externalEsignId || '';
  const anyKycApiBusy = Boolean(
    kycFlowActionLoading
    || aadhaarActionLoading
    || creatingKycRequest
    || kycPhase === 'checking',
  );
  const poaPreVerificationComplete = isPoaPreVerificationComplete(kycDecision);
  // When the user goes back and edits identity (PAN / name / DOB / mobile / etc.)
  // after a prior pre-verification, the saved result is stale: it was computed for
  // the OLD identity. Treat the prior completion as invalid so "Run pre-verification"
  // is enabled again and a fresh check can run against the new data.
  const identityChangedSinceKyc = Boolean(
    lastKycIdentityFingerprint && lastKycIdentityFingerprint !== identityFingerprint,
  );
  const poaRunLocked = shouldLockPoaRunButton({
    anyBusy: anyKycApiBusy && kycPhase !== 'checking',
    kycPhase,
    // `kyc_unavailable` (requiresFreshKyc) is NOT a terminal success — per Cybrilla docs it
    // means "start a fresh KYC application". Keep "Run pre-verification" enabled so the investor
    // can re-run it and is never permanently blocked; the "Create KYC request" path stays available too.
    preVerificationComplete: poaPreVerificationComplete && !identityChangedSinceKyc && !kycDecision.requiresFreshKyc,
  });
  const aadhaarStartLocked = shouldLockAadhaarStart({
    anyBusy: anyKycApiBusy,
    documentId: aadhaarDocumentId,
    redirectUrl: aadhaarRedirectUrl,
    externalDocumentId: activeInvestor?.externalIdentityDocumentId,
  });
  const freshKycRequestAllowed = canCreateFreshKycRequest(
    kycPreVerification,
    activeInvestor as Record<string, unknown> | null,
  );
  const kycRequestCreateLocked = shouldLockKycRequestCreate({
    anyBusy: anyKycApiBusy,
    creating: creatingKycRequest,
    kycRequestId,
    readinessBlocksCreate: isReadinessFailureBlockingKycRequest(
      kycPreVerification,
      activeInvestor as Record<string, unknown> | null,
    ),
  });
  const esignStartLocked = shouldLockEsignStart({
    anyBusy: anyKycApiBusy,
    esignId,
    redirectUrl: esignRedirectUrl,
  });
  const aadhaarLinkLocked = anyKycApiBusy || aadhaarFetchComplete || aadhaarStartLocked;
  const esignLinkLocked = anyKycApiBusy || esignStartLocked;
  const dobAgeError = useMemo(
    () => validateInvestorMinimumAge(s1.dob, { relationshipType: s1.relationshipType }),
    [s1.dob, s1.relationshipType],
  );

  const presentKycReasonDialog = (
    input: KycReasonDialogInput,
    options?: { force?: boolean },
  ) => {
    const content = buildKycReasonDialogContent(input);
    if (!content) return;
    const signature = JSON.stringify(content);
    if (!options?.force && signature === kycReasonDialogSigRef.current) return;
    kycReasonDialogSigRef.current = signature;
    setKycReasonDialogContent(content);
    setKycReasonDialogOpen(true);
  };

  const reportKycIssue = (errorMessage: string, extra?: Partial<KycReasonDialogInput>) => {
    setKycActionError(errorMessage);
    presentKycReasonDialog({
      errorMessage,
      warnings: cybrillaKycWarnings,
      preVerification: kycPreVerification,
      decision: kycDecision,
      flowMessage: kycFlowStatus?.message,
      ...extra,
    }, { force: true });
  };

  const applyAadhaarVerificationState = (view: ReturnType<typeof normalizeAadhaarVerificationResponse>) => {
    if (!view) return;
    if (view.identityDocumentId) setAadhaarDocumentId(view.identityDocumentId);
    if (view.redirectUrl) setAadhaarRedirectUrl(view.redirectUrl);
    if (view.fetchStatus) setAadhaarFetchStatus(view.fetchStatus);
    if (view.proofsAttachedToKycRequest) setAadhaarProofsAttached(true);
    if (view.investor) setDraftInvestor(view.investor);
  };

  useEffect(() => {
    const stored = readAadhaarVerificationFromInvestor(activeInvestor as Record<string, unknown> | null);
    if (stored) applyAadhaarVerificationState(stored);
  }, [activeInvestor?.id, activeInvestor?.externalIdentityDocumentId, activeInvestor?.aadhaarFetchStatus]);

  const cybrillaKycWarnings = useMemo(() => {
    if (cybrillaApiWarnings.length > 0) return cybrillaApiWarnings;
    const investorRecord = (draftInvestor || resumeInvestor) as Record<string, unknown> | null;
    return extractCybrillaWarningsFromPayload({
      externalResponse: kycPreVerification,
      investor: investorRecord,
    });
  }, [kycPreVerification, draftInvestor, resumeInvestor, cybrillaApiWarnings]);
  const currentKycStatus = normalizeStatus(draftInvestor?.kycStatus || resumeInvestor?.kycStatus);
  const isExistingKycVerified = currentKycStatus === 'COMPLETED' || currentKycStatus === 'VERIFIED';
  const isKycFailed =
    kycPhase === 'failed'
    || kycPhase === 'retry'
    || kycDecision.state === 'failed'
    || kycDecision.state === 'retry'
    || ['FAILED', 'REJECTED', 'RETRY_REQUIRED'].includes(currentKycStatus);

  // Once an investor's KYC is verified (persisted status), identity/consent/KYC
  // steps must stay locked so the verified PAN/name/DOB cannot be altered. The
  // earliest reachable step is the first post-KYC stage (Bank = step 5).
  const kycLocked = isExistingKycVerified;
  const minStep = kycLocked ? 5 : 1;

  // Consent is a one-time gate. After acknowledgment (or when resuming past step 2), going
  // forward from Basic Info jumps straight to KYC — never back through Consent.
  const [consentCompleted, setConsentCompleted] = useState(() => {
    const explicit = clampResumeStep(resumeStep);
    if (explicit != null && explicit >= 3) return true;
    if (resumeInvestor?.id) {
      const kyc = normalizeStatus(resumeInvestor.kycStatus);
      return kyc !== 'NOT_STARTED' || Boolean(resumeInvestor.externalKycPayloadJson);
    }
    return false;
  });
  const [editingForKycRetry, setEditingForKycRetry] = useState(false);

  // Verified bank detection for the read-only bank summary (step 5).
  const bankIsVerified = (bank: any) => {
    const status = normalizeStatus(bank?.verificationStatus || bank?.cybrillaBankVerificationStatus);
    return status === 'VERIFIED' || status === 'COMPLETED';
  };
  const verifiedBank = existingBanks.find(bankIsVerified) || null;
  const showBankReadOnly = Boolean(verifiedBank) && !bankEditMode;

  const applyResumeInvestorToForm = React.useCallback((investor: any) => {
    if (!investor?.id) return;
    const name = splitFullName(investor.fullName || investor.name);
    const external = resolvePreVerificationFromPayload({ investor });
    const status = normalizeStatus(investor.kycStatus);
    const decision = getPreVerificationDecision(external, { investor });

    setS1({
      firstName: name.firstName,
      lastName: name.lastName,
      pan: investor.pan || '',
      dob: investor.dateOfBirth || investor.dob || '',
      mobile: investor.mobileNumber || investor.mobile || '',
      email: investor.email || '',
      relationshipType: investor.relationshipType || '', // DF-09: reflect saved value, no fabricated default
      householdName: investor.householdName || '',
      guardianPan: investor.guardianPan || '',
    });
    setS4({
      gender: noteValue(investor.onboardingNotes, 'gender'),
      occupation: noteValue(investor.onboardingNotes, 'occupation'),
      income: noteValue(investor.onboardingNotes, 'income'),
      contactOwner: noteValue(investor.onboardingNotes, 'contact_owner') || '', // DF-09
      addressLine1: investor.addressLine1 || '',
      addressLine2: investor.addressLine2 || '',
      city: investor.city || '',
      state: investor.state || '',
      postalCode: investor.postalCode || '',
    });
    const incomeFromNotes = noteValue(investor.onboardingNotes, 'income');
    setS6({
      taxResidency: noteValue(investor.onboardingNotes, 'tax_residency') || '', // DF-09
      taxCountry: '',
      incomeSlab: incomeFromNotes || noteValue(investor.onboardingNotes, 'income_slab') || '',
      politicalExp: noteValue(investor.onboardingNotes, 'pep') || '', // DF-09
      declared: false,
      termsAccepted: false, // re-confirmed below from GET /terms on resume
    });
    setDraftInvestor(investor);
    setDraftIdentityFingerprint('');
    setLastKycIdentityFingerprint(
      external
        ? computeIdentityFingerprint({
          fullName: investor.fullName || investor.name || '',
          pan: investor.pan,
          dateOfBirth: investor.dateOfBirth || investor.dob,
          mobile: investor.mobileNumber || investor.mobile,
          email: investor.email,
          relationshipType: investor.relationshipType,
          guardianPan: investor.guardianPan,
        })
        : '',
    );
    setKycPreVerification(external);
    setCybrillaApiWarnings(extractCybrillaWarningsFromPayload({ externalResponse: external, investor }));
    setKycPhase(
      status === 'COMPLETED' || status === 'VERIFIED'
        ? 'verified'
        : status === 'IN_PROGRESS' || status === 'PENDING'
          ? 'pending'
          : status === 'FAILED' || status === 'RETRY_REQUIRED' || status === 'REJECTED'
            ? 'retry'
            : decision.state,
    );
    setKycActionError('');
    setKycActionMessage(
      status === 'COMPLETED' || status === 'VERIFIED'
        ? 'KYC is already verified. Continue with the remaining onboarding stages.'
        : decision.message,
    );
    presentKycReasonDialog({
      decision,
      warnings: extractCybrillaWarningsFromPayload({ externalResponse: external, investor }),
      preVerification: external,
    });
    if (status !== 'NOT_STARTED' || external) {
      setConsentCompleted(true);
    }
    setEditingForKycRetry(false);
    setStep(deriveResumeStep(investor, resumeStep, {
      nextStep: investor.onboardingResumeNextStep,
      documentsComplete: investor.onboardingDocumentsComplete,
    }));
  }, [resumeStep]);

  const buildInvestorPayload = () => ({
    distributorId,
    fullName,
    mobileNumber: normalizeMobile(s1.mobile),
    email: s1.email.trim(),
    pan: normalizePan(s1.pan),
    dateOfBirth: s1.dob || null,
    relationshipType: s1.relationshipType,
    householdName: s1.householdName.trim() || null,
    guardianPan: s1.relationshipType === 'MINOR' ? normalizePan(s1.guardianPan) : null,
    addressLine1: s4.addressLine1.trim(),
    addressLine2: s4.addressLine2.trim() || null,
    city: s4.city.trim(),
    state: s4.state.trim(),
    postalCode: s4.postalCode.trim(),
    onboardingNotes: [
      `frontend_reference=${refNum}`,
      `gender=${s4.gender}`,
      `occupation=${s4.occupation}`,
      `income=${s4.income}`,
      `contact_owner=${s4.contactOwner}`,
      `tax_residency=${s6.taxResidency}`,
      `income_slab=${s6.incomeSlab}`,
      `pep=${s6.politicalExp}`,
    ].join('; '),
  });

  const buildInvestorUpdatePayload = () => {
    const payload = buildInvestorPayload();
    const { distributorId: _distributorId, ...updatablePayload } = payload;
    return updatablePayload;
  };

  // ── Effects ─────────────────────────────────────────────────────────────────

  useEffect(() => {
    if (!resumeInvestor?.id) return;
    applyResumeInvestorToForm(resumeInvestor);
    setResumeLoading(false);
    setResumeError('');
  }, [resumeInvestor, applyResumeInvestorToForm]);

  useEffect(() => {
    if (!resumeInvestorId || resumeInvestor?.id) return;
    let cancelled = false;

    const loadInvestorForResume = async () => {
      setResumeLoading(true);
      setResumeError('');
      try {
        const response = await apiFetch(`/investors/${resumeInvestorId}/onboarding/resume`);
        const data = await response.json().catch(() => null);
        if (!response.ok) {
          throw new Error(data?.message || `Investor resume failed with HTTP ${response.status}.`);
        }
        if (!cancelled) {
          const investor = {
            ...(data?.investor || {}),
            onboardingResumeNextStep: data?.nextStep,
            onboardingDocumentsComplete: data?.documentsComplete,
          };
          applyResumeInvestorToForm(investor);
        }
      } catch (err) {
        console.error('Investor resume load failed:', err);
        if (!cancelled) setResumeError(err instanceof Error ? err.message : 'Investor could not be loaded for onboarding.');
      } finally {
        if (!cancelled) setResumeLoading(false);
      }
    };

    loadInvestorForResume();
    return () => { cancelled = true; };
  }, [resumeInvestorId, resumeInvestor?.id, applyResumeInvestorToForm]);

  // IFSC lookup via Platizio backend → Cybrilla FP /api/onb/ifsc_codes/{ifsc}
  useEffect(() => {
    const normalized = s5.ifsc.trim().toUpperCase();
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
  }, [s5.ifsc]);

  // Load existing bank accounts so a verified bank can be shown read-only.
  const investorIdForBank = draftInvestor?.id || resumeInvestor?.id || resumeInvestorId || null;
  useEffect(() => {
    if (!investorIdForBank) return;
    let cancelled = false;
    setBankAccountsLoading(true);
    apiFetch(`/investors/${investorIdForBank}/bank-accounts`)
      .then(res => (res.ok ? res.json() : null))
      .then(data => {
        if (cancelled) return;
        setExistingBanks(Array.isArray(data) ? data : []);
      })
      .catch(err => {
        console.error('Failed to load investor bank accounts for onboarding', err);
        if (!cancelled) setExistingBanks([]);
      })
      .finally(() => {
        if (!cancelled) setBankAccountsLoading(false);
      });
    return () => { cancelled = true; };
  }, [investorIdForBank]);

  // Task 4 / DF-10: on resume, pre-check the T&C box if the investor already accepted.
  useEffect(() => {
    if (!investorIdForBank) return;
    let cancelled = false;
    apiFetch(`/investors/${investorIdForBank}/terms`)
      .then(res => (res.ok ? res.json() : []))
      .then((rows: any[]) => {
        if (cancelled) return;
        if (Array.isArray(rows) && rows.some(r => r?.documentKey === 'investor_tnc')) {
          setS6(prev => ({ ...prev, termsAccepted: true }));
        }
      })
      .catch(() => { /* non-fatal: leave the box unchecked */ });
    return () => { cancelled = true; };
  }, [investorIdForBank]);

  const startBankEdit = () => {
    if (verifiedBank) {
      const rawAccount = String(verifiedBank.accountNumber || '');
      setS5({
        accNumber: /^\d{9,18}$/.test(rawAccount) ? rawAccount : '',
        ifsc: verifiedBank.ifscCode || '',
        accType: verifiedBank.accountType || 'Savings',
        primary: true,
      });
    }
    setBankEditMode(true);
  };

  // Reset the POA pre-verification result if identity fields change — including after
  // going BACK and editing. Keyed off BOTH the draft fingerprint and the last-KYC
  // fingerprint: on a resumed/loaded investor `draftIdentityFingerprint` is empty, so
  // without the lastKyc check an edit-after-resume would never clear the stale result.
  // `lastKycIdentityFingerprint` is intentionally NOT cleared here so the button stays
  // unlocked (see identityChangedSinceKyc) until a fresh pre-verification actually runs.
  useEffect(() => {
    const draftChanged = Boolean(draftIdentityFingerprint) && draftIdentityFingerprint !== identityFingerprint;
    const kycChanged = Boolean(lastKycIdentityFingerprint) && lastKycIdentityFingerprint !== identityFingerprint;
    if (!draftChanged && !kycChanged) return;
    if (draftIdentityFingerprint) setDraftIdentityFingerprint('');
    setKycPreVerification(null);
    setKycPhase('idle');
    setKycActionError('');
    setKycActionMessage('Identity details changed. Run POA pre-verification again.');
  }, [draftIdentityFingerprint, lastKycIdentityFingerprint, identityFingerprint]);

  const consentAcknowledged = consentDataProcessing && consentCybrillaKyc;

  const allRequiredDocumentsSaved = REQUIRED_DOCUMENT_KEYS.every(
    key => Boolean(savedDocuments[key]?.id) || Boolean(docs[key]),
  );

  // ── Can proceed guard ─────────────────────────────────────────────────────────
  const canNext: boolean = (() => {
    switch (step) {
      case 1: return Object.keys(validateInvestorIdentityForKyc({
        firstName: s1.firstName,
        lastName: s1.lastName,
        pan: s1.pan,
        dob: s1.dob,
        mobile: s1.mobile,
        email: s1.email,
        relationshipType: s1.relationshipType,
        guardianPan: s1.guardianPan,
      })).length === 0 && !!s1.relationshipType; // DF-09: relationship must be chosen
      case 2: return consentAcknowledged;
      case 3: return !!(
        s4.gender
        && s4.occupation
        && s4.income
        && s4.contactOwner          // DF-09: must choose, no default
        && s4.addressLine1.trim()
        && s4.city.trim()
        && s4.state.trim()
        && /^\d{6}$/.test(s4.postalCode.trim())
      );
      case 4:
        return canProceedFromKycStep({
          isExistingKycVerified,
          kycPhase,
          kycFlowStatus,
          kycDecisionCanProceed: kycDecision.canProceed,
          kycDecisionRequiresFreshKyc: kycDecision.requiresFreshKyc,
          aadhaarFetchComplete,
          kycRequestId,
          esignStatus: String(
            (activeInvestor as Record<string, unknown> | null)?.esignStatus
            || kycFlowStatus?.investor?.esignStatus
            || '',
          ),
        });
      case 5:
        if (bankStepBusy) return false;
        if (showBankReadOnly) return true;
        return ACCOUNT_NUMBER_REGEX.test(s5.accNumber)
          && IFSC_REGEX.test(s5.ifsc)
          && Boolean(bankName)
          && !ifscLookupLoading;
      case 6: return !!(s6.taxResidency && s6.politicalExp && s6.incomeSlab && s6.declared && s6.termsAccepted);
      case 7: return allRequiredDocumentsSaved;
      default: return true;
    }
  })();

  const readJsonSafely = async (response: Response) => {
    const text = await response.text();
    if (!text) return null;
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  };

  const maskAccountNumber = (value: string) => {
    if (!value) return '';
    return value.length <= 4 ? '****' : `${'*'.repeat(Math.max(value.length - 4, 4))}${value.slice(-4)}`;
  };

  const isKycComplete = (investor: any) => {
    const status = normalizeStatus(investor?.kycStatus);
    return status === 'COMPLETED' || status === 'VERIFIED';
  };

  const bankVerificationStatusText = (bank: any) => {
    const status = normalizeStatus(bank?.verificationStatus || bank?.cybrillaBankVerificationStatus);
    if (status === 'VERIFIED' || status === 'COMPLETED') return 'Bank account verified by Platizio.';
    if (status === 'VERIFICATION_FAILED' || status === 'FAILED') return 'Bank verification failed. Collect a corrected bank account or retry.';
    if (bank?.cybrillaBankVerificationId) return 'Platizio bank verification is in progress.';
    if (bank?.externalSyncPending) return bank.externalSyncMessage || 'Bank details were saved locally and will be retried.';
    return 'Bank details saved. Verification will start after KYC is completed.';
  };

  const investorIdForDocuments = draftInvestor?.id || resumeInvestor?.id || resumeInvestorId || null;

  useEffect(() => {
    if (!investorIdForDocuments) return;
    let cancelled = false;
    setDocsLoading(true);
    listInvestorDocuments(investorIdForDocuments)
      .then(documents => {
        if (cancelled) return;
        const byKey = savedDocumentsByKey(documents);
        setSavedDocuments(byKey);
        const activeInvestor = draftInvestor || resumeInvestor;
        if (activeInvestor && isResumeMode) {
          const kycStatus = normalizeStatus(activeInvestor.kycStatus);
          const bankStatus = normalizeStatus(activeInvestor.bankVerificationStatus);
          const kycOk = kycStatus === 'COMPLETED' || kycStatus === 'VERIFIED';
          const bankOk = bankStatus === 'VERIFIED';
          const allDocsSaved = REQUIRED_DOCUMENT_KEYS.every(key => Boolean(byKey[key]?.id));
          if (kycOk && bankOk && !allDocsSaved) {
            setStep(7);
          }
        }
      })
      .catch(err => {
        console.error('Failed to load saved investor documents', err);
      })
      .finally(() => {
        if (!cancelled) setDocsLoading(false);
      });
    return () => { cancelled = true; };
  }, [investorIdForDocuments]);

  const persistDocumentToPlatizio = async (investorId: string, key: InvestorDocumentKey, file: File) => {
    setDocUploading(prev => ({ ...prev, [key]: true }));
    setDocProgress(prev => ({ ...prev, [key]: 0 }));
    setDocErrors(prev => ({ ...prev, [key]: '' }));
    try {
      const saved = await uploadInvestorDocument(investorId, key, file, percent => {
        setDocProgress(prev => ({ ...prev, [key]: percent }));
      });
      setSavedDocuments(prev => ({ ...prev, [key]: saved }));
      return saved;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Document upload failed';
      setDocErrors(prev => ({ ...prev, [key]: message }));
      throw err;
    } finally {
      setDocUploading(prev => ({ ...prev, [key]: false }));
    }
  };

  const handleDocumentSelect = (key: InvestorDocumentKey, file?: File) => {
    if (!file) return;

    const validationError = validateDocumentFile(file);
    setDocErrors(prev => ({ ...prev, [key]: validationError }));
    setDocProgress(prev => ({ ...prev, [key]: 0 }));

    if (validationError) {
      setDocs(prev => ({ ...prev, [key]: null }));
      return;
    }

    setDocs(prev => ({ ...prev, [key]: file }));

    if (!investorIdForDocuments) {
      setDocErrors(prev => ({
        ...prev,
        [key]: 'Complete earlier steps first so the investor draft exists, then upload again.',
      }));
      return;
    }

    void persistDocumentToPlatizio(investorIdForDocuments, key, file);
  };

  const appendExternalSyncWarning = (message: string) => {
    setExternalSyncPending(true);
    setExternalSyncMessage(prev => prev ? `${prev}\n${message}` : message);
    setShowExternalSyncNotice(true);
  };

  const clearFieldWarnings = (...fields: string[]) => {
    const keys = new Set(fields);
    setServerErrors(prev => {
      const next = { ...prev };
      keys.forEach(key => delete next[key]);
      return next;
    });
    setKycValidationErrors(prev => {
      const next = { ...prev };
      keys.forEach(key => delete next[key]);
      return next;
    });
    setKycActionError('');
  };

  const setIdentityField = (field: keyof typeof s1, value: string, warningFields?: string[]) => {
    setS1(prev => ({ ...prev, [field]: value }));
    clearFieldWarnings(...(warningFields || [String(field)]));
  };

  const setBankField = (field: keyof typeof s5, value: string | boolean, warningFields?: string[]) => {
    setS5(prev => ({ ...prev, [field]: value }));
    clearFieldWarnings(...(warningFields || [String(field)]));
  };

  const applySandboxScenario = (scenario: SandboxDemoScenario) => {
    setS1(prev => ({
      ...prev,
      firstName: scenario.firstName,
      lastName: scenario.lastName,
      pan: scenario.pan,
      dob: scenario.dob,
      mobile: scenario.mobile,
      email: scenario.email,
    }));
    setS4(prev => ({
      ...prev,
      gender: prev.gender || 'Male',
      occupation: prev.occupation || 'Salaried',
      income: prev.income || '1-5 L',
      addressLine1: scenario.addressLine1,
      city: scenario.city,
      state: scenario.state,
      postalCode: scenario.postalCode,
    }));
    setS5(prev => ({
      ...prev,
      accNumber: scenario.bankAccount,
      ifsc: scenario.ifsc,
      accType: 'Savings',
    }));
    setServerErrors({});
    setKycValidationErrors({});
    setKycActionError('');
    setKycActionMessage(`Loaded sandbox scenario: ${scenario.label}. Complete Personal step, then run KYC pre-verification.`);
  };

  const validateIdentityBeforePreVerification = () => {
    const errors = validateIdentityBeforeCybrillaPoa({
      firstName: s1.firstName,
      lastName: s1.lastName,
      pan: s1.pan,
      dob: s1.dob,
      mobile: s1.mobile,
      email: s1.email,
      relationshipType: s1.relationshipType,
      guardianPan: s1.guardianPan,
    });
    setKycValidationErrors(errors);

    if (Object.keys(errors).length > 0) {
      setServerErrors(prev => ({ ...prev, ...errors }));
      setKycActionError('Fix the highlighted identity fields before running POA pre-verification.');
      setKycActionMessage('');
      return false;
    }

    setServerErrors(prev => {
      const next = { ...prev };
      ['firstName', 'lastName', 'fullName', 'pan', 'dob', 'mobile', 'email', 'guardianPan'].forEach(key => delete next[key]);
      return next;
    });
    setKycActionError('');
    return true;
  };

  const logKycApiCall = (action: 'create' | 'refresh', details: Record<string, unknown>) => {
    console.log(`kyc_api_call action=${action}`, {
      timestamp: new Date().toISOString(),
      identityFingerprint,
      lastKycIdentityFingerprint,
      ...details,
    });
  };

  const ensureInvestorDraftForKyc = async () => {
    if (draftInvestor?.id && draftIdentityFingerprint === identityFingerprint) {
      return draftInvestor;
    }
    if (draftInvestor?.id) {
      const updatePayload = buildInvestorUpdatePayload();
      console.log('kyc_investor_update_request=', {
        endpoint: `PUT /api/v1/investors/${draftInvestor.id}`,
        pan: normalizePan(s1.pan),
        fullName,
      });
      const response = await apiFetch(`/investors/${draftInvestor.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(updatePayload),
      });
      const result = await readJsonSafely(response);
      if (!response.ok) {
        if (response.status === 400) {
          const validation = parseServerValidation(result);
          setServerErrors(mapServerErrorsToState(validation.fieldErrors, {
            fullName: 'firstName',
            mobileNumber: 'mobile',
            dateOfBirth: 'dob',
          }));
          throw new Error(buildValidationSummary(validation));
        }
        if (response.status === 409) {
          const message = typeof result === 'string' ? result : result?.message || 'Investor already exists';
          if (message.toLowerCase().includes('pan')) {
            const panHint = isCybrillaSandboxMode()
              ? ' In sandbox, each simulator PAN can only be used once. Try CCCPC3753C or DDDPD3753D (same kyc_unavailable demo), or open the existing investor from Investors.'
              : '';
            setServerErrors(prev => ({ ...prev, pan: message + panHint }));
            throw new Error(message + panHint);
          }
          throw new Error(message);
        }
        throw new Error(typeof result === 'string' ? result : result?.message || 'Investor update failed');
      }
      setDraftInvestor(result);
      setDraftIdentityFingerprint(identityFingerprint);
      return result;
    }
    if (!distributorId) {
      throw new Error('Distributor session was not found. Please log in again and retry.');
    }

    const payload = buildInvestorPayload();
    console.log('poa_pre_verification_draft_investor_request=', payload);
    const response = await apiFetch('/investors', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const result = await readJsonSafely(response);
    console.log('poa_pre_verification_draft_investor_response=', {
      status: response.status,
      ok: response.ok,
      body: result,
    });

    if (!response.ok) {
      if (response.status === 400) {
        const validation = parseServerValidation(result);
        setServerErrors(mapServerErrorsToState(validation.fieldErrors, {
          fullName: 'firstName',
          mobileNumber: 'mobile',
          dateOfBirth: 'dob',
        }));
        throw new Error(buildValidationSummary(validation));
      }
      if (response.status === 409) {
        const message = typeof result === 'string' ? result : result?.message || 'Investor already exists';
        if (message.toLowerCase().includes('pan')) {
          setServerErrors(prev => ({ ...prev, pan: message }));
        }
        throw new Error(message);
      }
      throw new Error(typeof result === 'string' ? result : result?.message || 'Investor draft creation failed');
    }

    setDraftInvestor(result);
    setDraftIdentityFingerprint(identityFingerprint);
    return result;
  };

  const applyPreVerificationResponse = (result: any) => {
    const external = resolvePreVerificationFromPayload(result);
    if (!external) {
      throw new Error('POA pre-verification response was not returned.');
    }
    const decision = getPreVerificationDecision(external, {
      investor: result?.investor,
      kyc: result?.kyc,
    });
    setKycPreVerification(external);
    setKycPhase(decision.state);
    setKycActionMessage(decision.message);
    const warnings = extractCybrillaWarningsFromPayload(result);
    setCybrillaApiWarnings(warnings);
    presentKycReasonDialog({
      decision,
      warnings,
      preVerification: external,
    });
    setLastKycIdentityFingerprint(identityFingerprint);
    if (result?.investor) {
      setDraftInvestor(result.investor);
      setDraftIdentityFingerprint(identityFingerprint);
    }
    return decision;
  };

  const runPoaPreVerification = async () => {
    if (!validateIdentityBeforePreVerification()) return;
    if (poaRunLocked) {
      console.log('kyc_api_call action=skipped reason=poa_run_locked');
      return;
    }
    if (kycCheckInFlightRef.current) {
      console.log('kyc_api_call action=skipped reason=in_flight');
      return;
    }

    kycCheckInFlightRef.current = true;
    setKycPhase('checking');
    setKycActionError('');
    setKycActionMessage('');
    setKycValidationErrors({});
    setCybrillaApiWarnings([]);

    try {
      const investor = await ensureInvestorDraftForKyc();
      const shouldForceNew = shouldForceNewKycCheck(
        lastKycIdentityFingerprint,
        identityFingerprint,
        investor,
      );

      logKycApiCall('create', {
        endpoint: `POST /api/v1/investors/${investor.id}/kyc-checks`,
        localInvestorId: investor.id,
        pan: normalizePan(s1.pan),
        name: fullName,
        dateOfBirth: s1.dob,
        forceNewCheck: shouldForceNew,
      });

      const response = await apiFetch(`/investors/${investor.id}/kyc-checks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          dateOfBirth: s1.dob || null,
          forceNewCheck: shouldForceNew,
        }),
      });
      const result = await readJsonSafely(response);
      console.log('kyc_api_call action=create_response', {
        status: response.status,
        ok: response.ok,
        forceNewCheck: shouldForceNew,
        body: result,
      });
      if (!response.ok) {
        const errorMessage = typeof result === 'string'
          ? result
          : result?.message || `POA pre-verification failed with HTTP ${response.status}`;
        console.error('kyc_api_call action=create_failed', {
          status: response.status,
          error: typeof result === 'string' ? result : result?.error,
          message: errorMessage,
          path: typeof result === 'string' ? undefined : result?.path,
          body: result,
        });
        if (response.status === 400 && /pan|sandbox/i.test(errorMessage)) {
          setKycValidationErrors(prev => ({ ...prev, pan: errorMessage }));
          setServerErrors(prev => ({ ...prev, pan: errorMessage }));
        }
        if (result && typeof result === 'object') {
          setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
        }
        throw new Error(errorMessage);
      }
      applyPreVerificationResponse(result);
      await loadKycFlowStatus(investor.id);
    } catch (err) {
      console.error('kyc_api_call action=create_error', err);
      setKycPhase('failed');
      const errorMessage = err instanceof Error ? err.message : 'POA pre-verification failed.';
      setKycActionError(errorMessage);
      presentKycReasonDialog({
        errorMessage,
        warnings: cybrillaApiWarnings,
        preVerification: kycPreVerification,
        decision: kycDecision,
      }, { force: true });
    } finally {
      kycCheckInFlightRef.current = false;
    }
  };

  // After editing identity post-KYC failure, auto-run one fresh check on return to step 4.
  useEffect(() => {
    if (step !== 4 || !autoRunKycOnStep4) return;
    setAutoRunKycOnStep4(false);
    void runPoaPreVerification();
  }, [step, autoRunKycOnStep4]);

  // Poll Cybrilla while pre-verification is still accepted/pending (backend also polls on create).
  useEffect(() => {
    if (step !== 4) {
      kycPollAttemptsRef.current = 0;
      return;
    }
    if (kycDecision.canProceed || kycPhase === 'verified' || kycPhase === 'failed') {
      return;
    }
    if (kycPhase !== 'accepted' && kycPhase !== 'pending') {
      return;
    }
    const investorId = poaInvestorId;
    const kycCheckId = poaKycCheckId;
    if (!investorId || !kycCheckId) return;

    const timer = window.setInterval(() => {
      if (kycPollAttemptsRef.current >= 12 || kycCheckInFlightRef.current) {
        return;
      }
      kycPollAttemptsRef.current += 1;
      void refreshPoaPreVerification({ silent: true });
    }, 2500);

    return () => window.clearInterval(timer);
  }, [step, kycPhase, kycDecision.canProceed, poaInvestorId, poaKycCheckId]);

  const refreshPoaPreVerification = async (options?: { silent?: boolean }) => {
    const kycCheckId = poaKycCheckId;
    const investorId = poaInvestorId;
    if (!kycCheckId) {
      if (!options?.silent) {
        setKycActionError('Run POA pre-verification before refreshing the status.');
      }
      return;
    }
    if (!investorId) {
      if (!options?.silent) {
        setKycActionError('Create the investor draft by running POA pre-verification first.');
      }
      return;
    }
    if (kycCheckInFlightRef.current) {
      console.log('kyc_api_call action=skipped reason=in_flight');
      return;
    }

    kycCheckInFlightRef.current = true;
    if (!options?.silent) {
      setKycPhase('checking');
      setKycActionError('');
      setKycActionMessage('');
    }

    try {
      logKycApiCall('refresh', {
        endpoint: `GET /api/v1/investors/${investorId}/kyc-checks/${kycCheckId}`,
        localInvestorId: investorId,
        kycCheckId,
      });
      const response = await apiFetch(`/investors/${investorId}/kyc-checks/${kycCheckId}`);
      const result = await readJsonSafely(response);
      console.log('kyc_api_call action=refresh_response', {
        status: response.status,
        ok: response.ok,
        body: result,
      });
      if (!response.ok) {
        if (result && typeof result === 'object') {
          setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
        }
        throw new Error(typeof result === 'string' ? result : result?.message || `POA pre-verification refresh failed with HTTP ${response.status}`);
      }
      applyPreVerificationResponse(result);
      await loadKycFlowStatus(investorId);
    } catch (err) {
      console.error('kyc_api_call action=refresh_error', err);
      setKycPhase('retry');
      const errorMessage = err instanceof Error ? err.message : 'POA pre-verification refresh failed.';
      setKycActionError(errorMessage);
      presentKycReasonDialog({
        errorMessage,
        warnings: cybrillaApiWarnings,
        preVerification: kycPreVerification,
        decision: kycDecision,
      }, { force: true });
    } finally {
      kycCheckInFlightRef.current = false;
    }
  };

  const applyIdentityDocumentResponse = (result: any) => {
    applyAadhaarVerificationState(normalizeAadhaarVerificationResponse(result));
    setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
  };

  const createAadhaarIdentityDocument = async () => {
    if (aadhaarStartLocked) {
      setKycActionMessage('Aadhaar fetch is already started. Use Refresh status after the investor completes Digilocker.');
      return;
    }
    let investor = draftInvestor || resumeInvestor;
    if (!investor?.id) {
      try {
        investor = await ensureInvestorDraftForKyc();
      } catch {
        setKycActionError('Create the investor draft by running POA pre-verification first.');
        return;
      }
    }
    if (!kycRequestId && !investor.externalKycRequestId) {
      setKycActionError('Create a KYC request before starting Aadhaar verification.');
      return;
    }

    setAadhaarActionLoading('create');
    setKycActionError('');
    setKycActionMessage('');

    try {
      const response = await apiFetch(`/investors/${investor.id}/identity-documents`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          type: 'aadhaar',
          postbackUrl: buildKycPostbackUrl(investor.id, 'aadhaar'),
        }),
      });
      const result = await readJsonSafely(response);
      if (!response.ok) {
        if (result && typeof result === 'object') {
          setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
        }
        throw new Error(typeof result === 'string' ? result : result?.message || `Aadhaar verification failed with HTTP ${response.status}`);
      }
      applyIdentityDocumentResponse(result);
      setKycActionMessage('Aadhaar Digilocker flow created. Open the link for the investor to complete OTP verification.');
      await loadKycFlowStatus(investor.id);
    } catch (err) {
      reportKycIssue(err instanceof Error ? err.message : 'Unable to start Aadhaar verification.');
    } finally {
      setAadhaarActionLoading('');
    }
  };

  const refreshAadhaarIdentityDocument = async () => {
    const investor = draftInvestor || resumeInvestor;
    if (!investor?.id) {
      setKycActionError('Create the investor draft before refreshing Aadhaar status.');
      return;
    }

    setAadhaarActionLoading('refresh');
    setKycActionError('');

    try {
      const response = await apiFetch(`/investors/${investor.id}/identity-documents/refresh`, {
        method: 'POST',
      });
      const result = await readJsonSafely(response);
      if (!response.ok) {
        if (result && typeof result === 'object') {
          setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
        }
        throw new Error(typeof result === 'string' ? result : result?.message || `Aadhaar status refresh failed with HTTP ${response.status}`);
      }
      applyIdentityDocumentResponse(result);
      await loadKycFlowStatus(investor.id);
      const view = normalizeAadhaarVerificationResponse(result);
      if (view?.fetchComplete && view.proofsAttachedToKycRequest) {
        setKycActionMessage('Aadhaar fetched from Digilocker and attached to the KYC request.');
      } else if (view?.fetchComplete) {
        setKycActionMessage('Aadhaar fetched successfully from Digilocker.');
      } else {
        setKycActionMessage('Digilocker fetch is still in progress. Ask the investor to complete OTP verification.');
      }
    } catch (err) {
      reportKycIssue(err instanceof Error ? err.message : 'Unable to refresh Aadhaar verification status.');
    } finally {
      setAadhaarActionLoading('');
    }
  };

  const loadKycFlowStatus = async (investorId = poaInvestorId) => {
    if (!investorId) {
      setKycFlowStatus(null);
      setKycFlowError('');
      return null;
    }
    setKycFlowLoading(true);
    setKycFlowError('');
    try {
      const response = await apiFetch(`/investors/${investorId}/kyc-flow/status`);
      const result = await readJsonSafely(response);
      if (!response.ok) {
        throw new Error(typeof result === 'string' ? result : result?.message || `KYC flow status failed with HTTP ${response.status}`);
      }
      const flow = normalizeKycFlowStatus(result);
      setKycFlowStatus(flow);
      if (flow?.aadhaarRedirectUrl) setAadhaarRedirectUrl(flow.aadhaarRedirectUrl);
      if (flow?.esignRedirectUrl) setEsignRedirectUrl(flow.esignRedirectUrl);
      if (flow?.investor) {
        setDraftInvestor(flow.investor);
        const storedAadhaar = readAadhaarVerificationFromInvestor(flow.investor);
        if (storedAadhaar) applyAadhaarVerificationState(storedAadhaar);
        const rebuiltPreVerification = buildPreVerificationFromBackendState(flow.investor);
        // Don't resurrect a pre-verification built from the backend's (now stale) identity
        // once the user has edited identity fields — that would re-lock the Run button and
        // re-show the old readiness result. The fresh check the user is about to run wins.
        if (rebuiltPreVerification && !identityChangedSinceKyc) {
          setKycPreVerification(prev => prev || rebuiltPreVerification);
          const readinessReason = readCybrillaReadinessReason(rebuiltPreVerification, { investor: flow.investor });
          const flowDecision = getPreVerificationDecision(rebuiltPreVerification, { investor: flow.investor });
          if (readinessReason) {
            setKycActionMessage(readinessReason);
            if (flowDecision.state === 'failed' || flowDecision.state === 'retry') {
              setKycPhase(flowDecision.state);
            }
          }
          presentKycReasonDialog({
            decision: flowDecision,
            warnings: extractCybrillaWarningsFromPayload({ investor: flow.investor }),
            preVerification: rebuiltPreVerification,
            flowMessage: flow.message,
          });
        }
      }
      return flow;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to load KYC flow status.';
      console.warn('kyc_flow_status_error=', err);
      setKycFlowError(message);
      reportKycIssue(message);
      return null;
    } finally {
      setKycFlowLoading(false);
    }
  };

  const startEsign = async () => {
    const investor = draftInvestor || resumeInvestor;
    if (!investor?.id) return;
    if (esignStartLocked) {
      setKycActionMessage('eSign is already started. Use Refresh eSign after the investor completes signing.');
      return;
    }
    setKycFlowActionLoading('START_ESIGN');
    setKycActionError('');
    try {
      const response = await apiFetch(`/investors/${investor.id}/esign/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          postbackUrl: buildKycPostbackUrl(investor.id, 'esign'),
        }),
      });
      const result = await readJsonSafely(response);
      if (!response.ok) {
        throw new Error(typeof result === 'string' ? result : result?.message || `eSign start failed with HTTP ${response.status}`);
      }
      const esign = normalizeEsignVerificationResponse(result);
      if (esign?.redirectUrl) setEsignRedirectUrl(esign.redirectUrl);
      if (esign?.investor) setDraftInvestor(esign.investor);
      await loadKycFlowStatus(investor.id);
      setKycActionMessage('eSign started. Open the link for the investor to sign the KYC application.');
    } catch (err) {
      reportKycIssue(err instanceof Error ? err.message : 'Unable to start eSign.');
    } finally {
      setKycFlowActionLoading('');
    }
  };

  const refreshEsign = async () => {
    const investor = draftInvestor || resumeInvestor;
    if (!investor?.id) return;
    setKycFlowActionLoading('REFRESH_ESIGN');
    setKycActionError('');
    try {
      const response = await apiFetch(`/investors/${investor.id}/esign/refresh`, { method: 'POST' });
      const result = await readJsonSafely(response);
      if (!response.ok) {
        throw new Error(typeof result === 'string' ? result : result?.message || `eSign refresh failed with HTTP ${response.status}`);
      }
      const esign = normalizeEsignVerificationResponse(result);
      if (esign?.redirectUrl) setEsignRedirectUrl(esign.redirectUrl);
      if (esign?.investor) setDraftInvestor(esign.investor);
      await loadKycFlowStatus(investor.id);
      setKycActionMessage(
        esign?.completed
          ? 'eSign completed. Platizio KYC application has been submitted.'
          : 'eSign is still pending. Ask the investor to complete signing.',
      );
    } catch (err) {
      reportKycIssue(err instanceof Error ? err.message : 'Unable to refresh eSign status.');
    } finally {
      setKycFlowActionLoading('');
    }
  };

  const runKycFlowNextAction = async (requestedAction: KycFlowNextAction) => {
    if (!requestedAction || requestedAction === 'WAIT' || requestedAction === 'COMPLETE') return;
    if (kycFlowActionLoading) return;

    if (requestedAction === 'RUN_PRE_VERIFICATION') {
      if (!validateIdentityBeforePreVerification()) return;
      if (poaRunLocked) return;
    }
    if (requestedAction === 'START_AADHAAR' && aadhaarStartLocked) return;
    if (requestedAction === 'CREATE_KYC_REQUEST' && kycRequestCreateLocked) return;
    if (requestedAction === 'START_ESIGN' && esignStartLocked) return;

    setKycFlowActionLoading(requestedAction);
    setKycActionError('');
    try {
      switch (requestedAction) {
        case 'RUN_PRE_VERIFICATION':
          await runPoaPreVerification();
          break;
        case 'CREATE_KYC_REQUEST':
          await createFreshKycRequestFromPreVerification();
          break;
        case 'START_AADHAAR':
          await createAadhaarIdentityDocument();
          break;
        case 'REFRESH_AADHAAR':
          await refreshAadhaarIdentityDocument();
          break;
        case 'START_ESIGN':
          await startEsign();
          break;
        case 'REFRESH_ESIGN':
          await refreshEsign();
          break;
        default:
          break;
      }
      const investorId = draftInvestor?.id || resumeInvestor?.id || poaInvestorId;
      if (investorId) {
        await loadKycFlowStatus(investorId);
      }
    } finally {
      setKycFlowActionLoading('');
    }
  };

  useEffect(() => {
    if (step !== 4) return;
    if (poaInvestorId) {
      void loadKycFlowStatus(poaInvestorId);
    } else {
      setKycFlowStatus(null);
      setKycFlowError('');
    }
  }, [step, poaInvestorId]);

  useEffect(() => {
    const investorId = searchParams.get('investorId');
    const kycReturn = searchParams.get('kycReturn');
    if (!investorId || !kycReturn || postbackHandledRef.current) return;
    postbackHandledRef.current = true;

    (async () => {
      setStep(4);
      setKycActionMessage(`Returned from ${kycReturn === 'esign' ? 'eSign' : 'Digilocker'}. Refreshing status…`);
      try {
        const investorResponse = await apiFetch(`/investors/${investorId}`);
        const investorResult = await readJsonSafely(investorResponse);
        if (investorResponse.ok && investorResult?.id) {
          applyResumeInvestorToForm(investorResult);
        }

        if (kycReturn === 'aadhaar') {
          const response = await apiFetch(`/investors/${investorId}/identity-documents/refresh`, { method: 'POST' });
          const result = await readJsonSafely(response);
          if (response.ok) {
            applyIdentityDocumentResponse(result);
          }
        } else if (kycReturn === 'esign') {
          const response = await apiFetch(`/investors/${investorId}/esign/refresh`, { method: 'POST' });
          const result = await readJsonSafely(response);
          if (response.ok) {
            const esign = normalizeEsignVerificationResponse(result);
            if (esign?.redirectUrl) setEsignRedirectUrl(esign.redirectUrl);
            if (esign?.investor) setDraftInvestor(esign.investor);
          }
        }

        await loadKycFlowStatus(investorId);
        setKycActionMessage(`Returned from ${kycReturn === 'esign' ? 'eSign' : 'Digilocker'}. Status refreshed.`);
      } catch (err) {
        setKycActionError(err instanceof Error ? err.message : 'Unable to refresh KYC status after redirect.');
      } finally {
        navigate('/distributor/investor-onboarding', { replace: true, state: location.state });
      }
    })();
  }, [searchParams, navigate, location.state]);

  const createFreshKycRequestFromPreVerification = async () => {
    if (kycRequestCreateLocked || !freshKycRequestAllowed) return;
    let investor = draftInvestor || resumeInvestor;
    if (!investor?.id) {
      try {
        investor = await ensureInvestorDraftForKyc();
      } catch (err) {
        setKycActionError(err instanceof Error ? err.message : 'Create the investor draft by running POA pre-verification first.');
        return;
      }
    }

    setCreatingKycRequest(true);
    setKycActionError('');
    setKycActionMessage('');

    try {
      console.log('fresh_kyc_request_create_request=', {
        endpoint: `POST /api/v1/investors/${investor.id}/kyc-requests`,
      });
      const response = await apiFetch(`/investors/${investor.id}/kyc-requests`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: fullName,
          pan: normalizePan(s1.pan),
          email: s1.email.trim(),
          mobile: normalizeMobile(s1.mobile),
          dateOfBirth: s1.dob || null,
          fields: {},
        }),
      });
      const result = await readJsonSafely(response);
      console.log('fresh_kyc_request_create_response=', {
        status: response.status,
        ok: response.ok,
        body: result,
      });
      if (!response.ok) {
        if (result && typeof result === 'object') {
          setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
        }
        throw new Error(typeof result === 'string' ? result : result?.message || `Fresh KYC request failed with HTTP ${response.status}`);
      }
      if (result?.investor) {
        setDraftInvestor(result.investor);
      }
      setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
      setKycActionMessage('Fresh KYC request was created. Fetch Aadhaar from Digilocker to attach identity and address proofs.');
      await loadKycFlowStatus(investor.id);
    } catch (err) {
      console.error('fresh_kyc_request_create_error=', err);
      reportKycIssue(err instanceof Error ? err.message : 'Fresh KYC request failed.');
    } finally {
      setCreatingKycRequest(false);
    }
  };

  // Sandbox-only: the Cybrilla sandbox cannot complete a real Digilocker/eSign session, so the
  // onboarding Aadhaar step would otherwise dead-end. Drive the existing sandbox-gated
  // POST /kyc-requests/{id}/simulate endpoint to "successful" so the demo KYC completes and the
  // flow can advance to bank verification. Never shown outside sandbox mode.
  const simulateKycApprovalSandbox = async () => {
    const investor = draftInvestor || resumeInvestor;
    if (!investor?.id || !kycRequestId) {
      setKycActionError('Create a KYC request before simulating sandbox approval.');
      return;
    }
    setAadhaarActionLoading('simulate-kyc');
    setKycActionError('');
    setKycActionMessage('');
    try {
      const response = await apiFetch(`/investors/${investor.id}/kyc-requests/${kycRequestId}/simulate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'successful' }),
      });
      const result = await readJsonSafely(response);
      if (!response.ok) {
        if (result && typeof result === 'object') {
          setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(result));
        }
        throw new Error(typeof result === 'string'
          ? result
          : result?.message || `Sandbox KYC simulation failed with HTTP ${response.status}`);
      }
      if (result?.investor) setDraftInvestor(result.investor);
      setKycActionMessage('Sandbox: KYC request marked successful — KYC is complete. Click Continue to move to Bank verification.');
      await loadKycFlowStatus(investor.id);
    } catch (err) {
      reportKycIssue(err instanceof Error ? err.message : 'Sandbox KYC simulation failed.');
    } finally {
      setAadhaarActionLoading('');
    }
  };

  const runInitialKycApis = async (createdInvestor: any) => {
    if (!createdInvestor?.id) return createdInvestor;
    try {
      console.log('step_1a_apply_kyc_request=', {
        endpoint: `POST /api/v1/investors/${createdInvestor.id}/kyc/apply`,
        pan: createdInvestor.pan,
      });
      const response = await apiFetch(`/investors/${createdInvestor.id}/kyc/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dateOfBirth: createdInvestor.dateOfBirth || s1.dob || null }),
      });
      const result = await readJsonSafely(response);
      console.log('step_1a_apply_kyc_response=', {
        status: response.status,
        ok: response.ok,
        body: result,
      });

      if (!response.ok) {
        throw new Error(typeof result === 'string' ? result : result?.message || 'KYC workflow failed');
      }
      return result?.investor || createdInvestor;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to apply KYC workflow';
      console.warn('step_1a_apply_kyc_external_sync=', message);
      appendExternalSyncWarning(`Unable to apply KYC workflow through Platizio: ${message}`);
      return createdInvestor;
    }
  };

  const syncInvestorProfileForStep = async () => {
    if (!draftInvestor?.id) {
      return ensureInvestorDraftForKyc();
    }

    const updatePayload = buildInvestorUpdatePayload();
    const response = await apiFetch(`/investors/${draftInvestor.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(updatePayload),
    });
    const result = await readJsonSafely(response);
    if (!response.ok) {
      if (response.status === 400) {
        const validation = parseServerValidation(result);
        setServerErrors(mapServerErrorsToState(validation.fieldErrors, {
          fullName: 'firstName',
          mobileNumber: 'mobile',
          dateOfBirth: 'dob',
        }));
        throw new Error(buildValidationSummary(validation));
      }
      if (response.status === 409) {
        const message = typeof result === 'string' ? result : result?.message || 'Investor already exists';
        throw new Error(message);
      }
      throw new Error(typeof result === 'string' ? result : result?.message || 'Investor update failed');
    }
    setDraftInvestor(result);
    if (draftIdentityFingerprint !== identityFingerprint) {
      setDraftIdentityFingerprint(identityFingerprint);
    }
    return result;
  };

  const refreshBankVerificationIfStarted = async (investorId: string, bank: any) => {
    if (!investorId || !bank?.id || !bank?.cybrillaBankVerificationId) {
      return bank;
    }

    try {
      console.log('step_2b_refresh_bank_verification_request=', {
        endpoint: `PATCH /api/v1/investors/${investorId}/bank-accounts/${bank.id}/verification`,
        localBankId: bank.id,
        cybrillaBankVerificationId: bank.cybrillaBankVerificationId,
      });
      const response = await apiFetch(`/investors/${investorId}/bank-accounts/${bank.id}/verification`, {
        method: 'PATCH',
      });
      const result = await readJsonSafely(response);
      console.log('step_2b_refresh_bank_verification_response=', {
        status: response.status,
        ok: response.ok,
        body: result,
      });
      if (!response.ok) {
        throw new Error(typeof result === 'string' ? result : result?.message || `Bank verification refresh failed with HTTP ${response.status}`);
      }
      return result || bank;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to refresh bank verification';
      console.warn('step_2b_refresh_bank_verification_error=', message);
      appendExternalSyncWarning(`Bank verification was started but the latest Platizio status could not be fetched: ${message}`);
      return bank;
    }
  };

  const pollBankVerification = async (investorId: string, bank: any, maxAttempts = 12) => {
    let current = bank;
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      if (bankIsVerified(current)) return current;
      const status = normalizeStatus(current?.verificationStatus || current?.cybrillaBankVerificationStatus);
      if (status === 'VERIFICATION_FAILED' || status === 'FAILED') return current;
      if (!current?.cybrillaBankVerificationId) return current;
      await new Promise(resolve => setTimeout(resolve, 2500));
      current = await refreshBankVerificationIfStarted(investorId, current);
    }
    return current;
  };

  const saveBankAccountAndVerify = async () => {
    setBankStepError('');
    setBankStepMessage('');

    if (showBankReadOnly && verifiedBank) {
      return verifiedBank;
    }

    const investor = await syncInvestorProfileForStep();
    const investorId = investor?.id;
    if (!investorId) {
      throw new Error('Investor draft was not found. Go back and complete earlier steps.');
    }

    const bankPayload = {
      accountHolderName: fullName,
      accountNumber: s5.accNumber,
      ifscCode: s5.ifsc,
      bankName: bankName || undefined,
      branchName: ifscBranchName || '',
    };

    const bankResponse = await apiFetch(`/investors/${investorId}/bank-accounts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bankPayload),
    });
    const bankResult = await readJsonSafely(bankResponse);
    if (!bankResponse.ok) {
      if (bankResponse.status === 400) {
        const validation = parseServerValidation(bankResult);
        setServerErrors(mapServerErrorsToState(validation.fieldErrors, {
          accountNumber: 'accNumber',
          ifscCode: 'ifsc',
        }));
        throw new Error(buildValidationSummary(validation));
      }
      throw new Error(typeof bankResult === 'string' ? bankResult : bankResult?.message || 'Bank account creation failed');
    }

    let finalBank = bankResult;
    setExistingBanks(prev => {
      const withoutDup = prev.filter(bank => bank.id !== bankResult?.id);
      return [...withoutDup, bankResult];
    });

    const kycDone = isKycComplete(investor);
    if (kycDone && bankResult?.cybrillaBankVerificationId) {
      setBankStepMessage('Verifying bank account with Platizio…');
      finalBank = await pollBankVerification(investorId, bankResult);
      setExistingBanks(prev => {
        const withoutDup = prev.filter(bank => bank.id !== finalBank?.id);
        return [...withoutDup, finalBank];
      });
      if (!bankIsVerified(finalBank)) {
        const status = normalizeStatus(finalBank?.verificationStatus || finalBank?.cybrillaBankVerificationStatus);
        if (status === 'VERIFICATION_FAILED' || status === 'FAILED') {
          throw new Error('Bank verification failed. In sandbox, use an account number ending in 1193 to pass or 1515 to fail.');
        }
        throw new Error('Bank verification is still in progress. Wait a moment and click Continue again.');
      }
      setBankEditMode(false);
      setBankStepMessage('Bank account verified by Platizio.');
    } else if (kycDone) {
      setBankStepMessage(bankVerificationStatusText(bankResult));
    } else {
      setBankStepMessage('Bank details saved. Platizio verification will start automatically after KYC completes.');
    }

    return finalBank;
  };

  const refreshPendingBankStatus = async () => {
    const investorId = draftInvestor?.id || resumeInvestor?.id || resumeInvestorId;
    const pendingBank = existingBanks.find(bank => !bankIsVerified(bank));
    if (!investorId || !pendingBank?.id) return;

    setBankStepBusy(true);
    setBankStepError('');
    try {
      const updated = await refreshBankVerificationIfStarted(investorId, pendingBank);
      setExistingBanks(prev => prev.map(bank => (bank.id === updated?.id ? updated : bank)));
      setBankStepMessage(bankVerificationStatusText(updated));
      if (bankIsVerified(updated)) {
        setBankEditMode(false);
      }
    } catch (err) {
      setBankStepError(err instanceof Error ? err.message : 'Unable to refresh bank verification status.');
    } finally {
      setBankStepBusy(false);
    }
  };

  // ── F4: investor-approval gate helpers ──────────────────────────────────────
  const gateInvestorId = draftInvestor?.id || resumeInvestor?.id || resumeInvestorId || null;
  const approvalAttested = normalizeStatus(approvalStatus) === 'ATTESTED';
  const approvalAwaiting = ['SUBMITTED', 'AWAITING_APPROVAL', 'PENDING', 'AWAITING'].includes(
    normalizeStatus(approvalStatus),
  );

  const loadApprovalStatus = useCallback(async (investorId: string) => {
    setApprovalLoading(true);
    try {
      const res = await apiFetch(`/investors/${investorId}/onboarding/approval-status`);
      const data = await res.json().catch(() => null);
      if (res.ok && data) {
        setApprovalStatus(normalizeStatus(data.status) || 'NONE');
      }
    } catch {
      // Non-fatal: leave prior status; the banner just won't update this cycle.
    } finally {
      setApprovalLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!gateInvestorId) return;
    void loadApprovalStatus(gateInvestorId);
  }, [gateInvestorId, loadApprovalStatus]);

  const submitForInvestorReview = async () => {
    if (!gateInvestorId) {
      setApprovalError('Save the investor before submitting for approval.');
      return;
    }
    setApprovalSubmitting(true);
    setApprovalError('');
    try {
      const res = await apiFetch(`/investors/${gateInvestorId}/onboarding/submit-for-review`, {
        method: 'POST',
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Could not submit for investor approval.');
      }
      setApprovalStatus(normalizeStatus((data as { status?: string } | null)?.status) || 'SUBMITTED');
    } catch (e) {
      setApprovalError(e instanceof Error ? e.message : 'Could not submit for investor approval.');
    } finally {
      setApprovalSubmitting(false);
    }
  };

  const submitInvestorToBackend = async () => {
    if (!approvalAttested) {
      setSubmitError('Awaiting investor approval — you cannot finalize until the investor approves this exact submission.');
      return;
    }
    if (!distributorId) {
      setSubmitError('Distributor session was not found. Please log in again and retry.');
      console.error('[Cybrilla Workflow] Missing distributor id in user session', userData);
      return;
    }

    const missingDocumentLabels = REQUIRED_DOCUMENT_KEYS
      .filter(key => !savedDocuments[key]?.id && !docs[key])
      .map(key => (key === 'pan' ? 'PAN card' : key === 'address' ? 'Address proof' : 'Signature'));
    if (missingDocumentLabels.length > 0) {
      setSubmitError(`Upload all required documents before submitting: ${missingDocumentLabels.join(', ')}.`);
      return;
    }

    const investorPayload = buildInvestorPayload();

    const bankPayload = {
      accountHolderName: fullName,
      accountNumber: s5.accNumber,
      ifscCode: s5.ifsc,
      bankName: bankName || undefined,
      branchName: ifscBranchName || '',
    };

    setSubmitting(true);
    setSubmitError('');
    setExternalSyncPending(false);
    setExternalSyncMessage('');
    setShowExternalSyncNotice(false);
    setBankVerificationResult(null);
    setBankVerificationMessage('');
    setServerErrors({});
    console.groupCollapsed('[Cybrilla Workflow] Investor onboarding submit');
    console.log('frontend_route=', '/distributor/investor-onboarding');
    console.log('frontend_note=', 'Browser calls Platizio backend only. Backend then calls FP/Cybrilla using server-side bearer tokens.');
    console.log('step_1_create_investor_request=', investorPayload);
    console.log('step_1_expected_backend_work=', [
      'POST /api/v1/investors',
      'InvestorService saves local investor copy',
      'Backend creates FP/Cybrilla investor profile, email, and phone',
      'Backend stores cybrillaInvestorId on the local investor',
    ]);

    try {
      let investorResult: any = null;
      const shouldReuseDraft = Boolean(draftInvestor?.id && (isResumeMode || draftIdentityFingerprint === identityFingerprint));

      if (shouldReuseDraft) {
        const updatePayload = buildInvestorUpdatePayload();
        console.log('step_1_update_preverified_investor_request=', {
          endpoint: `PUT /api/v1/investors/${draftInvestor.id}`,
          body: updatePayload,
        });
        const investorResponse = await apiFetch(`/investors/${draftInvestor.id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(updatePayload),
        });
        investorResult = await readJsonSafely(investorResponse);
        console.log('step_1_update_preverified_investor_response=', {
          status: investorResponse.status,
          ok: investorResponse.ok,
          body: investorResult,
          cybrillaInvestorId: investorResult?.cybrillaInvestorId,
        });

        if (!investorResponse.ok) {
          if (investorResponse.status === 400) {
            const validation = parseServerValidation(investorResult);
            setServerErrors(mapServerErrorsToState(validation.fieldErrors, {
              fullName: 'firstName',
              mobileNumber: 'mobile',
              dateOfBirth: 'dob',
            }));
            throw new Error(buildValidationSummary(validation));
          }
          throw new Error(typeof investorResult === 'string' ? investorResult : investorResult?.message || 'Investor update failed');
        }
      } else {
        const investorResponse = await apiFetch('/investors', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(investorPayload),
        });
        investorResult = await readJsonSafely(investorResponse);
        console.log('step_1_create_investor_response=', {
          status: investorResponse.status,
          ok: investorResponse.ok,
          body: investorResult,
          cybrillaInvestorId: investorResult?.cybrillaInvestorId,
        });

        if (!investorResponse.ok) {
          if (investorResponse.status === 400) {
            const validation = parseServerValidation(investorResult);
            setServerErrors(mapServerErrorsToState(validation.fieldErrors, {
              fullName: 'firstName',
              mobileNumber: 'mobile',
              dateOfBirth: 'dob',
            }));
            throw new Error(buildValidationSummary(validation));
          }
          if (investorResponse.status === 409) {
            const message = typeof investorResult === 'string'
              ? investorResult
              : investorResult?.message || 'Investor already exists';
            if (message.toLowerCase().includes('pan')) {
              setServerErrors(prev => ({ ...prev, pan: message }));
            }
            throw new Error(message);
          }
          throw new Error(typeof investorResult === 'string' ? investorResult : investorResult?.message || 'Investor creation failed');
        }
      }

      const pendingExternalProfile = Boolean(investorResult?.externalSyncPending) || !investorResult?.cybrillaInvestorId;
      setExternalSyncPending(pendingExternalProfile);
      if (pendingExternalProfile) {
        const message = investorResult?.externalSyncMessage
          || 'Unable to post investor data to Platizio. The investor was saved locally with KYC PENDING.';
        setExternalSyncMessage(message);
        setShowExternalSyncNotice(true);
        console.warn('step_1_create_investor_external_sync=', message);
      }

      const step3KycAlreadyRan = Boolean(
        kycPreVerification?.id
        || draftInvestor?.externalKycCheckId
        || kycDecision.canProceed
        || isKycComplete(draftInvestor),
      );
      if (!isKycComplete(investorResult) && !step3KycAlreadyRan) {
        investorResult = await runInitialKycApis(investorResult);
      }
      setDraftInvestor(investorResult);
      // Task 4 / DF-10: persist the T&C acceptance now that the investor exists.
      if (s6.termsAccepted && investorResult?.id) {
        try {
          await apiFetch(`/investors/${investorResult.id}/terms/accept`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ documentKey: 'investor_tnc', version: 'v1.0' }),
          });
        } catch (termsError) {
          console.warn('terms_accept_failed=', termsError);
        }
      }
      if (!isKycComplete(investorResult)) {
        appendExternalSyncWarning('KYC is not complete yet. Bank verification will start automatically after Platizio marks KYC as completed.');
      }

      console.log('step_1b_upload_documents_request=', {
        endpoint: `PUT /api/v1/investors/${investorResult.id}/documents`,
        documents: (Object.entries(docs) as [keyof typeof docs, File | null][]).map(([key, file]) => ({
          documentType: String(key).toUpperCase(),
          fileName: file?.name,
          fileSize: file?.size,
        })),
      });

      for (const [key, file] of Object.entries(docs) as [InvestorDocumentKey, File | null][]) {
        if (!file) continue;
        const saved = savedDocuments[key];
        if (saved && saved.fileName === file.name && saved.sizeBytes === file.size) continue;
        await persistDocumentToPlatizio(investorResult.id, key, file);
      }

      let finalBankResult: any;
      if (showBankReadOnly && verifiedBank) {
        // Verified bank already on file and the user did not choose to edit it —
        // reuse it instead of creating a duplicate bank account.
        console.log('step_2_skip_bank_create=', 'Verified bank already on file; skipping bank creation.');
        finalBankResult = verifiedBank;
      } else {
        console.log('step_2_add_bank_request=', {
          ...bankPayload,
          accountNumber: maskAccountNumber(bankPayload.accountNumber),
        });
        console.log('step_2_expected_backend_work=', [
          `POST /api/v1/investors/${investorResult.id}/bank-accounts`,
          'InvestorService saves local bank copy',
          'If KYC is complete, backend creates the FP bank account using cybrillaInvestorId',
          'Backend starts Cybrilla POA bank pre-verification and stores the verification id',
          'If KYC is not complete, backend saves the bank locally and starts verification when KYC completes',
        ]);

        const bankResponse = await apiFetch(`/investors/${investorResult.id}/bank-accounts`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(bankPayload),
        });
        const bankResult = await readJsonSafely(bankResponse);
        console.log('step_2_add_bank_response=', {
          status: bankResponse.status,
          ok: bankResponse.ok,
          body: bankResult,
          cybrillaBankId: bankResult?.cybrillaBankId,
        });

        if (!bankResponse.ok) {
          if (bankResponse.status === 400) {
            const validation = parseServerValidation(bankResult);
            setServerErrors(mapServerErrorsToState(validation.fieldErrors, {
              accountNumber: 'accNumber',
              ifscCode: 'ifsc',
            }));
            throw new Error(buildValidationSummary(validation));
          }
          throw new Error(typeof bankResult === 'string' ? bankResult : bankResult?.message || 'Bank account creation failed');
        }
        finalBankResult = bankResult;
        if (isKycComplete(investorResult) && bankResult?.cybrillaBankVerificationId) {
          finalBankResult = await refreshBankVerificationIfStarted(investorResult.id, bankResult);
        }
      }
      setBankVerificationResult(finalBankResult);
      setBankVerificationMessage(bankVerificationStatusText(finalBankResult));

      if (Boolean(finalBankResult?.externalSyncPending) || (isKycComplete(investorResult) && !finalBankResult?.cybrillaBankId)) {
        const message = finalBankResult?.externalSyncMessage
          || 'Unable to post bank data to Platizio. Bank details were saved locally for retry.';
        setExternalSyncPending(true);
        setExternalSyncMessage(prev => prev ? `${prev}\n${message}` : message);
        setShowExternalSyncNotice(true);
      }

      console.log('workflow_status=', 'completed');
      setSubmitted(true);
    } catch (error) {
      console.error('workflow_status=', 'failed');
      console.error('workflow_error=', error);
      setSubmitError(error instanceof Error ? error.message : 'Investor onboarding failed');
    } finally {
      console.groupEnd();
      setSubmitting(false);
    }
  };

  // ── Navigation ────────────────────────────────────────────────────────────────
  const goNext = () => {
    if (step === 7) {
      submitInvestorToBackend();
      return;
    }
    if (step === 1) {
      const returningToKycAfterEdit = consentCompleted && isKycFailed;
      setEditingForKycRetry(false);
      if (returningToKycAfterEdit) {
        setAutoRunKycOnStep4(true);
      }
      setStep(consentCompleted ? 3 : 2);
      return;
    }
    if (step === 2) {
      setConsentCompleted(true);
      setStep(3);
      return;
    }
    if (step === 3) {
      void (async () => {
        try {
          await syncInvestorProfileForStep();
          setStep(4);
        } catch (err) {
          setKycActionError(err instanceof Error ? err.message : 'Unable to save investor profile before KYC.');
        }
      })();
      return;
    }
    if (step === 5) {
      void (async () => {
        try {
          setBankStepBusy(true);
          setBankStepError('');
          await saveBankAccountAndVerify();
          setStep(6);
        } catch (err) {
          setBankStepError(err instanceof Error ? err.message : 'Unable to save and verify bank account.');
        } finally {
          setBankStepBusy(false);
        }
      })();
      return;
    }
    setStep(s => s + 1);
  };

  const goBack = () => {
    if (step <= minStep) {
      onBack();
      return;
    }
    // On KYC: no back unless pre-verification failed — then edit identity only (skip Consent).
    if (step === 4) {
      if (!isKycFailed) return;
      setEditingForKycRetry(true);
      setStep(1);
      setKycActionMessage('Update identity details if needed, then continue to run POA pre-verification again.');
      return;
    }
    if (step === 2) {
      setStep(1);
      return;
    }
    setStep(s => s - 1);
  };

  // ═══════════════════════════════════════════════════════════════════════════
  // ── Submitted screen ──────────────────────────────────────────────────────
  if (submitted) {
    const normalizedBankStatus = normalizeStatus(
      bankVerificationResult?.verificationStatus || bankVerificationResult?.cybrillaBankVerificationStatus,
    );
    const bankVerificationDone = normalizedBankStatus === 'VERIFIED' || normalizedBankStatus === 'COMPLETED';
    const bankVerificationFailed = normalizedBankStatus === 'VERIFICATION_FAILED' || normalizedBankStatus === 'FAILED';
    const bankVerificationSub = bankVerificationMessage
      || (bankVerificationResult?.cybrillaBankVerificationId
        ? 'Platizio bank verification is in progress'
        : 'Bank verification will start after KYC completes');
    const submittedKycComplete = isKycComplete(draftInvestor);
    const statusRows = externalSyncPending
      ? [
          { label: 'Investor Saved', sub: 'Local profile created successfully', color: 'green', done: true },
          { label: 'KYC Status', sub: submittedKycComplete ? 'KYC completed through Platizio' : 'PAN/external verification will be retried later', color: submittedKycComplete ? 'green' : 'amber', done: submittedKycComplete },
          { label: 'Bank Verification', sub: bankVerificationSub, color: bankVerificationFailed ? 'red' : 'amber', done: bankVerificationDone },
          { label: 'Compliance Review', sub: 'FATCA & PMLA check in queue', color: 'blue', done: false },
        ]
      : [
          { label: 'Identity Verified', sub: 'PAN captured and investor consent recorded', color: 'green', done: true },
          { label: 'KYC Processed', sub: 'POA pre-verification completed', color: 'green', done: true },
          { label: 'Bank Verification', sub: bankVerificationSub, color: bankVerificationDone ? 'green' : bankVerificationFailed ? 'red' : 'amber', done: bankVerificationDone },
          { label: 'Compliance Review', sub: 'FATCA & PMLA check in queue', color: 'blue', done: false },
        ];

    return (
      <motion.div
        initial={{ opacity: 0, scale: 0.95 }}
        animate={{ opacity: 1, scale: 1 }}
        className="flex items-center justify-center p-10 min-h-full"
      >
        <div className="max-w-md w-full text-center">
          <motion.div
            initial={{ scale: 0 }}
            animate={{ scale: 1 }}
            transition={{ type: 'spring', delay: 0.1 }}
            className="w-24 h-24 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-6"
          >
            <CheckCircle2 className="w-12 h-12 text-green-600" />
          </motion.div>

          <h1 className="text-2xl font-bold text-slate-800 mb-2">
            {externalSyncPending
              ? submittedKycComplete ? 'Investor created, bank verification pending' : 'Investor created, KYC incomplete'
              : 'Application Submitted!'}
          </h1>
          <p className="text-slate-500 text-sm mb-6 leading-relaxed">
            {externalSyncPending
              ? (externalSyncMessage || (submittedKycComplete
                ? `${s1.firstName} ${s1.lastName}'s KYC is complete. Bank verification will be retried once the platform is available.`
                : `${s1.firstName} ${s1.lastName}'s profile has been saved with KYC pending. External verification will be retried once the platform is available.`))
              : `${s1.firstName} ${s1.lastName}'s onboarding application has been submitted and is under review.`}
          </p>

          <div className="bg-slate-50 border border-slate-200 rounded-2xl p-5 mb-6 text-left">
            <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400 mb-1">Reference Number</p>
            <p className="text-xl font-bold font-mono text-[#0B1B3E]">{refNum}</p>
            <p className="text-xs text-slate-500 mt-1.5">Estimated review time: 2–3 business days</p>
          </div>

          <div className="space-y-3 mb-8 text-left">
            {statusRows.map(it => (
              <div key={it.label} className={`flex items-center gap-3 rounded-xl p-3.5 bg-${it.color}-50`}>
                {it.done
                  ? <CheckCircle2 className={`w-5 h-5 text-${it.color}-600 flex-shrink-0`} />
                  : <Loader2 className={`w-5 h-5 text-${it.color}-500 flex-shrink-0 animate-spin`} />
                }
                <div>
                  <p className="text-sm font-semibold text-slate-800">{it.label}</p>
                  <p className="text-xs text-slate-500">{it.sub}</p>
                </div>
              </div>
            ))}
          </div>

          <button
            onClick={onComplete}
            className="w-full py-3 bg-[#0B1B3E] text-white font-semibold rounded-xl hover:bg-[#1A3066] transition-colors"
          >
            Back to Dashboard
          </button>

          <AnimatePresence>
            {externalSyncPending && showExternalSyncNotice && (
              <motion.div
                className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm"
                role="dialog"
                aria-modal="true"
                aria-labelledby="investor-onboarding-external-sync-title"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
              >
                <motion.div
                  className="w-full max-w-md rounded-2xl border border-amber-100 bg-white p-6 text-left shadow-2xl"
                  initial={{ opacity: 0, y: 18, scale: 0.97 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: 12, scale: 0.98 }}
                  transition={{ duration: 0.18 }}
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex items-start gap-3">
                      <div className="mt-0.5 flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-amber-50 text-amber-600">
                        <AlertTriangle className="h-5 w-5" />
                      </div>
                      <div>
                        <h2 id="investor-onboarding-external-sync-title" className="text-base font-semibold text-slate-900">
                          Investor created, KYC incomplete
                        </h2>
                        <div className="mt-1 space-y-2 text-sm leading-6 text-slate-600">
                          {(externalSyncMessage || 'Unable to post data to Platizio. The record was saved locally for retry.')
                            .split('\n')
                            .map((line, index) => <p key={`${line}-${index}`}>{line}</p>)}
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => setShowExternalSyncNotice(false)}
                      className="rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
                      aria-label="Close external sync warning"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </div>
                  <div className="mt-5 flex justify-end">
                    <button
                      type="button"
                      onClick={() => setShowExternalSyncNotice(false)}
                      className="rounded-xl bg-[#0B1B3E] px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
                    >
                      Got it
                    </button>
                  </div>
                </motion.div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </motion.div>
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ── Main layout ───────────────────────────────────────────────────────────
  if (resumeLoading) {
    return (
      <div className="flex min-h-full items-center justify-center p-10">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading investor onboarding...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="p-8 max-w-4xl">

      {/* Breadcrumb */}
      <button
        type="button"
        onClick={onBack}
        className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors mb-6"
      >
        <ArrowLeft className="w-4 h-4" /> Back
      </button>

      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-slate-800">Investor Onboarding</h1>
        <p className="text-slate-500 text-sm mt-1">
          {s1.firstName ? `${s1.firstName} ${s1.lastName} — ` : ''}Step {step} of {STEPS.length}
        </p>
      </div>

      {/* ── Progress stepper ──────────────────────────────────────────────── */}
      {isResumeMode && !resumeError && (
        <div className="mb-6 rounded-xl border border-emerald-100 bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-700">
          Continuing saved onboarding for this investor. Changes will update the existing local and Platizio-linked record.
        </div>
      )}
      {resumeError && (
        <div className="mb-6 rounded-xl border border-red-100 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">
          {resumeError}
        </div>
      )}
      {kycLocked && (
        <div className="mb-6 flex items-center gap-2 rounded-xl border border-blue-100 bg-blue-50 px-4 py-3 text-sm font-medium text-blue-700">
          <ShieldCheck className="h-4 w-4 flex-shrink-0" />
          KYC is verified for this investor. Identity, consent, and KYC steps are locked and can no longer be edited.
        </div>
      )}

      {/* ── F4: investor-approval gate banner ─────────────────────────────── */}
      {gateInvestorId && (approvalAwaiting || approvalAttested || approvalError || approvalStatus === 'NONE') && (
        approvalAttested ? (
          <div className="mb-6 flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-medium text-emerald-700">
            <CheckCircle2 className="h-4 w-4 flex-shrink-0" />
            Investor approved this submission. You can finalize now. Any edit will require re-approval.
          </div>
        ) : approvalAwaiting ? (
          <div className="mb-6 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
            <div className="flex items-start gap-2 text-sm font-medium text-amber-800">
              <Clock className="mt-0.5 h-4 w-4 flex-shrink-0" />
              <span>
                Awaiting investor approval — you cannot finalize until the investor approves this exact
                submission; any edit requires re-approval.
              </span>
            </div>
            <button
              type="button"
              onClick={() => void loadApprovalStatus(gateInvestorId)}
              disabled={approvalLoading}
              className="mt-2 inline-flex items-center gap-1.5 text-xs font-semibold text-amber-700 hover:underline disabled:opacity-50"
            >
              <RefreshCw className={`h-3 w-3 ${approvalLoading ? 'animate-spin' : ''}`} /> Refresh approval status
            </button>
          </div>
        ) : (
          <div className="mb-6 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
            <div className="flex items-start gap-2 text-sm text-slate-600">
              <ShieldCheck className="mt-0.5 h-4 w-4 flex-shrink-0 text-slate-400" />
              <span>
                Before finalizing, submit this onboarding for the investor to approve. Finalize stays
                locked until the investor approves this exact submission.
              </span>
            </div>
            {approvalError && <p className="mt-2 text-xs font-medium text-red-600">{approvalError}</p>}
            <button
              type="button"
              onClick={() => void submitForInvestorReview()}
              disabled={approvalSubmitting}
              className="mt-3 inline-flex items-center gap-2 rounded-xl bg-[#0B1B3E] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:opacity-50"
            >
              {approvalSubmitting ? (<><Loader2 className="h-3.5 w-3.5 animate-spin" /> Submitting…</>) : 'Submit for investor approval'}
            </button>
          </div>
        )
      )}

      <div className="flex items-start mb-8 overflow-x-auto pb-2">
        {STEPS.map((s, i) => {
          const done = s.id < step;
          const current = s.id === step;
          return (
            <div key={s.id} className="flex items-center flex-1 min-w-0">
              <div className="flex flex-col items-center gap-1 flex-shrink-0">
                <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold border-2 transition-all ${done ? 'bg-green-500 border-green-500 text-white' :
                    current ? 'bg-[#0B1B3E] border-[#0B1B3E] text-white' :
                      'bg-white border-slate-200 text-slate-400'
                  }`}>
                  {done ? <Check className="w-3.5 h-3.5" /> : s.id}
                </div>
                <span className={`text-[9px] font-semibold whitespace-nowrap ${current ? 'text-[#0B1B3E]' : done ? 'text-green-600' : 'text-slate-400'
                  }`}>
                  {s.label}
                </span>
              </div>
              {i < STEPS.length - 1 && (
                <div className={`h-0.5 flex-1 mx-1 mb-4 rounded transition-colors ${done ? 'bg-green-400' : 'bg-slate-200'}`} />
              )}
            </div>
          );
        })}
      </div>

      {/* ── Step card ─────────────────────────────────────────────────────── */}
      <AnimatePresence mode="wait">
        <motion.div
          key={step}
          initial={{ opacity: 0, x: 24 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: -24 }}
          transition={{ duration: 0.18 }}
          className="bg-white rounded-2xl border border-slate-200 p-8"
        >

          {/* ── Step 1 — Basic Identity ──────────────────────────────── */}
          {step === 1 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Basic Identity</h2>
              <p className="text-sm text-slate-500 mb-6">Investor's core identification details</p>
              {isCybrillaSandboxMode() && (
                <div className="mb-6">
                  <SandboxDemoGuide onApplyScenario={applySandboxScenario} defaultExpanded />
                </div>
              )}
              {editingForKycRetry && (
                <div className="mb-5 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                  <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                  <span>
                    KYC pre-verification did not pass. Update PAN, name, or date of birth if needed, then continue — you will return directly to the KYC step (consent is not required again).
                  </span>
                </div>
              )}
              <div className="grid grid-cols-2 gap-5">
                <Field label="First Name" required error={serverErrors.firstName}>
                  <input value={s1.firstName} onChange={e => setIdentityField('firstName', e.target.value, ['firstName', 'fullName'])} placeholder="Rahul" className={inp} />
                </Field>
                <Field label="Last Name" required error={serverErrors.lastName}>
                  <input value={s1.lastName} onChange={e => setIdentityField('lastName', e.target.value, ['lastName', 'fullName'])} placeholder="Verma" className={inp} />
                </Field>
                <Field label="PAN Number" required error={serverErrors.pan}>
                  <input
                    value={s1.pan}
                    onChange={e => setIdentityField('pan', e.target.value.toUpperCase(), ['pan'])}
                    placeholder="ABCDE1234F"
                    maxLength={10}
                    className={inp + ' font-mono tracking-widest'}
                  />
                </Field>
                <Field label="Date of Birth" required error={serverErrors.dob || dobAgeError || undefined}>
                  <input type="date" value={s1.dob} onChange={e => setIdentityField('dob', e.target.value, ['dob', 'dateOfBirth'])} className={inp} />
                </Field>
                <Field label="Relationship Type">
                  <select
                    value={s1.relationshipType}
                    onChange={e => setIdentityField('relationshipType', e.target.value, ['relationshipType', 'guardianPan'])}
                    className={sel}
                  >
                    <option value="" disabled>Select relationship</option>
                    <option value="SELF">Self / Primary</option>
                    <option value="SPOUSE">Spouse / Joint holder</option>
                    <option value="MINOR">Minor folio</option>
                    <option value="HUF">HUF</option>
                  </select>
                </Field>
                <Field label="Household Name">
                  <input
                    value={s1.householdName}
                    onChange={e => setIdentityField('householdName', e.target.value, ['householdName'])}
                    placeholder="Sharma Family"
                    className={inp}
                  />
                </Field>
                {s1.relationshipType === 'MINOR' && (
                  <Field label="Guardian PAN" required error={serverErrors.guardianPan}>
                    <input
                      value={s1.guardianPan}
                      onChange={e => setIdentityField('guardianPan', e.target.value.toUpperCase(), ['guardianPan'])}
                      placeholder="ABCDE1234F"
                      maxLength={10}
                      className={inp + ' font-mono tracking-widest'}
                    />
                  </Field>
                )}
                <Field label="Mobile Number" required error={serverErrors.mobile}>
                  <input value={s1.mobile} onChange={e => setIdentityField('mobile', e.target.value.replace(/\D/g, ''), ['mobile', 'mobileNumber'])} placeholder="9876543210" maxLength={13} className={inp} />
                  <ContactVerification
                    investorId={draftInvestor?.id || resumeInvestor?.id || resumeInvestorId || null}
                    channel="mobile"
                    value={s1.mobile}
                    verified={draftInvestor?.mobileVerified ?? resumeInvestor?.mobileVerified}
                    method={draftInvestor?.mobileVerificationMethod ?? resumeInvestor?.mobileVerificationMethod}
                    belongsTo={draftInvestor?.mobileBelongsTo ?? resumeInvestor?.mobileBelongsTo}
                  />
                </Field>
                <Field label="Email Address" required error={serverErrors.email}>
                  <input type="email" value={s1.email} onChange={e => setIdentityField('email', e.target.value, ['email'])} placeholder="investor@email.com" className={inp} />
                  <ContactVerification
                    investorId={draftInvestor?.id || resumeInvestor?.id || resumeInvestorId || null}
                    channel="email"
                    value={s1.email}
                    verified={draftInvestor?.emailVerified ?? resumeInvestor?.emailVerified}
                    method={draftInvestor?.emailVerificationMethod ?? resumeInvestor?.emailVerificationMethod}
                    belongsTo={draftInvestor?.emailBelongsTo ?? resumeInvestor?.emailBelongsTo}
                  />
                </Field>
              </div>
            </div>
          )}

          {/* ── Step 2 — Consent (real Aadhaar OTP is on Cybrilla Digilocker in step 3) ─ */}
          {step === 2 && (
            <div className="max-w-lg">
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Investor Consent</h2>
              <p className="text-sm text-slate-500 mb-5">
                Record consent before Platizio KYC. Aadhaar OTP and biometric verification happen on the official
                Platizio Digilocker redirect in the next step — not on this screen.
              </p>

              <div className="mb-5 rounded-xl border border-indigo-200 bg-indigo-50 px-4 py-3 text-sm text-indigo-900">
                <div className="flex items-start gap-2">
                  <Fingerprint className="mt-0.5 h-4 w-4 shrink-0" />
                  <p>
                    When pre-verification returns <span className="font-mono">kyc_unavailable</span>, Platizio calls
                    <span className="font-mono">POST /v2/identity_documents</span> and opens the
                    <span className="font-semibold"> Digilocker redirect URL</span> for the investor. That is the real
                    Aadhaar authentication step.
                  </p>
                </div>
              </div>

              <div className="space-y-4">
                <label className="flex items-start gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={consentDataProcessing}
                    onChange={e => setConsentDataProcessing(e.target.checked)}
                    className="mt-1 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                  />
                  <span className="text-sm text-slate-700">
                    The investor consents to collection and processing of PAN, bank, and KYC data for mutual fund
                    onboarding through Platizio.
                  </span>
                </label>

                <label className="flex items-start gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={consentCybrillaKyc}
                    onChange={e => setConsentCybrillaKyc(e.target.checked)}
                    className="mt-1 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                  />
                  <span className="text-sm text-slate-700">
                    The investor agrees to complete Platizio KYC when required, including Aadhaar fetch via Digilocker
                    and eSign on Platizio-hosted pages.
                  </span>
                </label>
              </div>

              {consentAcknowledged && (
                <motion.p
                  initial={{ opacity: 0, y: 4 }}
                  animate={{ opacity: 1, y: 0 }}
                  className="mt-4 flex items-center gap-2 text-sm font-semibold text-green-600"
                >
                  <CheckCircle2 className="h-4 w-4" /> Consent recorded — continue to Platizio KYC
                </motion.p>
              )}
            </div>
          )}

          {/* ── Step 4 — KYC Processing ───────────────────────────────── */}
          {step === 4 && (
            <div className="py-6">
              <div className="text-center">
                <h2 className="text-lg font-semibold text-slate-800 mb-1">Platizio KYC</h2>
                <p className="text-sm text-slate-500 mb-6">
                  Pre-verification, Aadhaar Digilocker, and eSign — all through Platizio backend APIs.
                </p>
              </div>

              {isCybrillaSandboxMode() && (
                <div className="mx-auto mb-4 max-w-2xl">
                  <SandboxDemoGuide onApplyScenario={applySandboxScenario} compact />
                </div>
              )}

                {cybrillaKycWarnings.length > 0 && (
                  <CybrillaKycWarnings
                    warnings={cybrillaKycWarnings}
                    title={kycDecision.requiresFreshKyc || kycDecision.state === 'failed' || kycDecision.state === 'retry'
                      ? 'Platizio investor readiness'
                      : 'Warnings from Platizio'}
                    className="mx-auto mb-4 max-w-2xl"
                  />
                )}

                <div className="mx-auto mb-4 max-w-2xl rounded-xl border border-indigo-200 bg-indigo-50 px-4 py-3 text-left">
                  <p className="text-xs font-semibold text-indigo-900">Guided Platizio KYC flow</p>
                  <p className="mt-1 text-xs leading-5 text-indigo-800">
                    Use the checklist below — each step calls Platizio APIs (pre-verification → KYC request → Aadhaar → eSign).
                    When the flow shows complete, click <span className="font-semibold">Continue</span> to move to Bank verification.
                  </p>
                </div>

                <div className="mx-auto max-w-2xl">
                <KycFlowPanel
                  flow={kycFlowStatus}
                  loading={kycFlowLoading}
                  actionLoading={kycFlowActionLoading || aadhaarActionLoading}
                  aadhaarRedirectUrl={aadhaarRedirectUrl}
                  esignRedirectUrl={esignRedirectUrl}
                  aadhaarLinkDisabled={aadhaarLinkLocked}
                  esignLinkDisabled={esignLinkLocked}
                  primaryActionDisabled={
                    (kycFlowStatus?.nextAction === 'RUN_PRE_VERIFICATION' && poaRunLocked)
                    || (kycFlowStatus?.nextAction === 'START_AADHAAR' && aadhaarStartLocked)
                    || (kycFlowStatus?.nextAction === 'CREATE_KYC_REQUEST' && kycRequestCreateLocked)
                    || (kycFlowStatus?.nextAction === 'START_ESIGN' && esignStartLocked)
                  }
                  error={kycFlowError}
                  alwaysVisible
                  onRunNext={runKycFlowNextAction}
                />
                </div>

                <div className="mx-auto max-w-2xl rounded-2xl border border-slate-200 bg-slate-50 p-5 mt-5">
                <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                  <div className="flex gap-3">
                    <div className={`flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-xl ${
                      kycDecision.canProceed ? 'bg-green-100 text-green-700' : 'bg-blue-100 text-blue-700'
                    }`}>
                      {kycPhase === 'checking'
                        ? <Loader2 className="h-6 w-6 animate-spin" />
                        : <ShieldCheck className="h-6 w-6" />
                      }
                    </div>
                    <div>
                      <h3 className="text-sm font-bold text-slate-800">{kycDecision.title}</h3>
                      <p className="mt-1 text-xs leading-5 text-slate-500">{kycDecision.message}</p>
                      {(kycReasonDialogContent || cybrillaKycWarnings.length > 0 || getPreVerificationRows(kycPreVerification).some(row => row.reason)) && (
                        <button
                          type="button"
                          onClick={() => presentKycReasonDialog({
                            decision: kycDecision,
                            warnings: cybrillaKycWarnings,
                            preVerification: kycPreVerification,
                            flowMessage: kycFlowStatus?.message,
                          }, { force: true })}
                          className="mt-2 text-[11px] font-semibold text-indigo-700 underline-offset-2 hover:underline"
                        >
                          View all Platizio details
                        </button>
                      )}
                    </div>
                  </div>

                  <div className="flex flex-shrink-0 flex-wrap gap-2">
                    <button
                      type="button"
                      onClick={runPoaPreVerification}
                      disabled={poaRunLocked}
                      className="inline-flex items-center justify-center gap-2 rounded-lg bg-[#0B1B3E] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {kycPhase === 'checking' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ShieldCheck className="h-3.5 w-3.5" />}
                      {kycPreVerification ? 'Run again' : 'Run check'}
                    </button>
                    {showPoaRefreshButton && (
                      <button
                        type="button"
                        onClick={() => void refreshPoaPreVerification()}
                        disabled={kycPhase === 'checking' || Boolean(kycFlowActionLoading)}
                        className="inline-flex items-center justify-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        <RefreshCw className={`h-3.5 w-3.5 ${kycPhase === 'checking' ? 'animate-spin' : ''}`} />
                        Refresh
                      </button>
                    )}
                  </div>
                </div>

                {Object.keys(kycValidationErrors).length > 0 && (
                  <div className="mt-4 rounded-xl border border-red-100 bg-red-50 p-3 text-xs font-medium text-red-700">
                    {Object.values(kycValidationErrors).slice(0, 3).map(message => (
                      <p key={message}>{message}</p>
                    ))}
                  </div>
                )}

                {kycActionError && (
                  <p className="mt-4 flex items-center gap-1.5 text-xs font-medium text-red-600">
                    <AlertTriangle className="h-3.5 w-3.5" /> {kycActionError}
                  </p>
                )}
                {kycActionMessage && !kycActionError && (
                  <p className={`mt-4 flex items-center gap-1.5 text-xs font-medium ${kycDecision.canProceed ? 'text-green-600' : 'text-slate-600'}`}>
                    {kycDecision.canProceed ? <CheckCircle2 className="h-3.5 w-3.5" /> : <ShieldCheck className="h-3.5 w-3.5" />}
                    {kycActionMessage}
                  </p>
                )}

                {getPreVerificationRows(kycPreVerification).length > 0 && (
                  <div className="mt-5 grid gap-2 sm:grid-cols-2">
                    {getPreVerificationRows(kycPreVerification).map(row => (
                      <div key={row.field} className="rounded-xl border border-white bg-white p-3 shadow-sm">
                        <div className="flex items-center justify-between gap-3">
                          <p className="text-xs font-semibold text-slate-500">{row.label}</p>
                          <span className={`rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase ${preVerificationStatusClasses(row.status)}`}>
                            {row.status || 'pending'}
                          </span>
                        </div>
                        {(row.code || row.reason || row.value) && (
                          <p className="mt-2 text-[11px] leading-4 text-slate-500">
                            {[row.value, row.code, row.reason].filter(Boolean).join(' - ')}
                          </p>
                        )}
                      </div>
                    ))}
                  </div>
                )}

                {freshKycRequestAllowed && (
                  <div className="mt-5 flex flex-col gap-3 rounded-xl border border-amber-100 bg-amber-50 p-4 sm:flex-row sm:items-center sm:justify-between">
                    <div className="min-w-0">
                      <p className="text-xs font-semibold text-amber-900">Platizio investor readiness</p>
                      <p className="mt-1 text-xs font-medium leading-5 text-amber-800">
                        {kycDecision.message}
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={createFreshKycRequestFromPreVerification}
                      disabled={kycRequestCreateLocked}
                      className="inline-flex items-center justify-center gap-2 rounded-lg bg-amber-600 px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-amber-700 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {creatingKycRequest ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ArrowRight className="h-3.5 w-3.5" />}
                      Create KYC request
                    </button>
                  </div>
                )}

                {isReadinessFailureBlockingKycRequest(
                  kycPreVerification,
                  activeInvestor as Record<string, unknown> | null,
                ) && (
                  <div className="mt-5 rounded-xl border border-red-100 bg-red-50 p-4">
                    <p className="text-xs font-semibold text-red-900">Investor readiness failed</p>
                    <p className="mt-1 text-xs leading-5 text-red-800">
                      {kycDecision.message || readCybrillaReadinessReason(kycPreVerification, { investor: activeInvestor })}
                      {' '}Fix PAN, name, or date of birth, then run pre-verification again before creating a KYC request.
                    </p>
                  </div>
                )}

                {panAadhaarNotLinked && (
                  <div className="mt-5 rounded-xl border border-amber-200 bg-amber-50 p-4">
                    <div className="flex items-start gap-3">
                      <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-700" />
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-semibold text-amber-900">PAN–Aadhaar link required</p>
                        <p className="mt-1 text-xs leading-5 text-amber-800">
                          Platizio reported code <span className="font-mono">aadhaar_not_linked</span>.
                          The investor must link PAN with Aadhaar on the Income Tax portal, then run pre-verification again.
                        </p>
                        {isCybrillaSandboxMode() && (
                          <p className="mt-2 text-[11px] leading-4 text-amber-700">
                            Sandbox simulator: use PAN pattern XXXPANNNNX to test this outcome.
                          </p>
                        )}
                        <button
                          type="button"
                          onClick={runPoaPreVerification}
                          disabled={poaRunLocked}
                          className="mt-3 inline-flex items-center justify-center gap-2 rounded-lg border border-amber-300 bg-white px-3 py-2 text-xs font-semibold text-amber-800 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {kycPhase === 'checking' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
                          Re-run pre-verification
                        </button>
                      </div>
                    </div>
                  </div>
                )}

                {kycRequestId && (
                  <div className="mt-5 rounded-xl border border-indigo-100 bg-indigo-50/70 p-4">
                    <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                      <div className="flex gap-3">
                        <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-indigo-100 text-indigo-700">
                          <Fingerprint className="h-5 w-5" />
                        </div>
                        <div>
                          <h4 className="text-sm font-semibold text-slate-800">Aadhaar via Digilocker</h4>
                          <p className="mt-1 text-xs leading-5 text-slate-600">
                            Create an identity document and redirect the investor to Digilocker.
                            OTP authentication during fetch satisfies liveliness for fresh KYC.
                          </p>
                          {aadhaarFetchStatus && (
                            <p className={`mt-2 text-[11px] font-semibold uppercase ${aadhaarFetchComplete ? 'text-green-700' : 'text-indigo-700'}`}>
                              Fetch status: {aadhaarFetchStatus.replace(/_/g, ' ')}
                            </p>
                          )}
                        </div>
                      </div>
                      <div className="flex flex-shrink-0 flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={createAadhaarIdentityDocument}
                          disabled={aadhaarStartLocked}
                          className="inline-flex items-center justify-center gap-2 rounded-lg bg-indigo-700 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-indigo-800 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {aadhaarActionLoading === 'create' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Fingerprint className="h-3.5 w-3.5" />}
                          Start Aadhaar fetch
                        </button>
                        {aadhaarDocumentId && (
                          <button
                            type="button"
                            onClick={refreshAadhaarIdentityDocument}
                            disabled={anyKycApiBusy && aadhaarActionLoading !== 'refresh'}
                            className="inline-flex items-center justify-center gap-2 rounded-lg border border-indigo-200 bg-white px-3 py-2 text-xs font-semibold text-indigo-700 transition-colors hover:bg-indigo-50 disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            {aadhaarActionLoading === 'refresh' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
                            Refresh status
                          </button>
                        )}
                      </div>
                    </div>
                    {aadhaarRedirectUrl && !aadhaarFetchComplete && (
                      <KycProviderLink
                        href={aadhaarRedirectUrl}
                        label="Open Digilocker for investor"
                        icon={<Fingerprint className="h-3.5 w-3.5" />}
                        disabled={aadhaarLinkLocked}
                        disabledReason="Digilocker link is disabled while Aadhaar fetch is starting or already in progress."
                      />
                    )}
                    {aadhaarFetchComplete && (
                      <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-green-700">
                        <CheckCircle2 className="h-3.5 w-3.5" />
                        {aadhaarProofsAttached
                          ? 'Aadhaar attached to the KYC request as identity and address proof.'
                          : 'Aadhaar document fetched from Digilocker.'}
                      </p>
                    )}
                  </div>
                )}

                {isCybrillaSandboxMode() && kycRequestId
                  && String(activeInvestor?.kycStatus || '').toUpperCase() !== 'COMPLETED' && (
                  <div className="mt-5 rounded-xl border border-dashed border-emerald-300 bg-emerald-50/60 p-4">
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                      <div className="min-w-0">
                        <p className="text-xs font-semibold text-emerald-900">Sandbox shortcut — simulate KYC approval</p>
                        <p className="mt-1 text-xs leading-5 text-emerald-800">
                          The Cybrilla sandbox can't complete a real Digilocker/eSign session, so the Aadhaar step
                          can't finish here. Use this to mark the KYC request <span className="font-semibold">successful</span>
                          {' '}and continue the demo. Sandbox only — never shown in production.
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={simulateKycApprovalSandbox}
                        disabled={anyKycApiBusy}
                        className="inline-flex flex-shrink-0 items-center justify-center gap-2 rounded-lg bg-emerald-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {aadhaarActionLoading === 'simulate-kyc' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
                        Simulate KYC approval
                      </button>
                    </div>
                  </div>
                )}

                {kycRequestId && aadhaarFetchComplete && (
                  <div className="mt-5 rounded-xl border border-violet-100 bg-violet-50/70 p-4">
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                      <div>
                        <h4 className="text-sm font-semibold text-slate-800">eSign KYC application</h4>
                        <p className="mt-1 text-xs leading-5 text-slate-600">
                          After Aadhaar proofs are attached, the investor must eSign to submit the Platizio KYC application.
                        </p>
                      </div>
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={() => void startEsign()}
                          disabled={esignStartLocked}
                          className="inline-flex items-center justify-center gap-2 rounded-lg bg-violet-700 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-violet-800 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {kycFlowActionLoading === 'START_ESIGN' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ShieldCheck className="h-3.5 w-3.5" />}
                          Start eSign
                        </button>
                        {(draftInvestor?.externalEsignId || resumeInvestor?.externalEsignId) && (
                          <button
                            type="button"
                            onClick={() => void refreshEsign()}
                            disabled={Boolean(kycFlowActionLoading)}
                            className="inline-flex items-center justify-center gap-2 rounded-lg border border-violet-200 bg-white px-3 py-2 text-xs font-semibold text-violet-700 transition-colors hover:bg-violet-50 disabled:opacity-50"
                          >
                            {kycFlowActionLoading === 'REFRESH_ESIGN' ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
                            Refresh eSign
                          </button>
                        )}
                      </div>
                    </div>
                    {esignRedirectUrl && (
                      <KycProviderLink
                        href={esignRedirectUrl}
                        label="Open eSign for investor"
                        disabled={esignLinkLocked}
                        className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-violet-700 hover:text-violet-800"
                        disabledReason="eSign link is disabled while signing is starting or already in progress."
                      />
                    )}
                  </div>
                )}
              </div>

            </div>
          )}

          {/* ── Step 3 — Personal Details (required before Cybrilla KYC) ─ */}
          {step === 3 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Personal Details</h2>
              <p className="text-sm text-slate-500 mb-6">
                Address and profile data required by Platizio before KYC and transactions.
              </p>
              <div className="grid grid-cols-2 gap-5 mb-5">
                <Field label="Address Line 1" required>
                  <input value={s4.addressLine1} onChange={e => setS4({ ...s4, addressLine1: e.target.value })} placeholder="House / flat, street" className={inp} />
                </Field>
                <Field label="Address Line 2">
                  <input value={s4.addressLine2} onChange={e => setS4({ ...s4, addressLine2: e.target.value })} placeholder="Area, landmark (optional)" className={inp} />
                </Field>
                <PincodeCityFields
                  postalCode={s4.postalCode}
                  city={s4.city}
                  state={s4.state}
                  onPostalCodeChange={value => setS4(prev => ({ ...prev, postalCode: value }))}
                  onLocationResolved={({ city, state }) => setS4(prev => ({ ...prev, city, state }))}
                  onCityChange={value => setS4(prev => ({ ...prev, city: value }))}
                  onStateChange={value => setS4(prev => ({ ...prev, state: value }))}
                  inputClassName={inp}
                  selectClassName={sel}
                />
              </div>
              <div className="grid grid-cols-2 gap-5">
                <Field label="Gender" required>
                  <select value={s4.gender} onChange={e => setS4({ ...s4, gender: e.target.value })} className={sel}>
                    <option value="">Select gender</option>
                    {['Male', 'Female', 'Other', 'Prefer not to say'].map(g => <option key={g}>{g}</option>)}
                  </select>
                </Field>
                <Field label="Occupation" required>
                  <select value={s4.occupation} onChange={e => setS4({ ...s4, occupation: e.target.value })} className={sel}>
                    <option value="">Select occupation</option>
                    {['Salaried', 'Self-Employed', 'Business Owner', 'Retired', 'Student', 'Homemaker'].map(o => <option key={o}>{o}</option>)}
                  </select>
                </Field>
                <Field label="Annual Income Range" required>
                  <select value={s4.income} onChange={e => setS4({ ...s4, income: e.target.value })} className={sel}>
                    <option value="">Select range</option>
                    {['Below ₹1 L', '₹1–5 L', '₹5–10 L', '₹10–25 L', '₹25–50 L', 'Above ₹50 L'].map(r => <option key={r}>{r}</option>)}
                  </select>
                </Field>
                <Field label="Contact Ownership" required>
                  <select value={s4.contactOwner} onChange={e => setS4({ ...s4, contactOwner: e.target.value })} className={sel}>
                    <option value="" disabled>Select owner</option>
                    {['Self', 'Spouse', 'Guardian', 'Other'].map(c => <option key={c}>{c}</option>)}
                  </select>
                </Field>
              </div>

              {/* Nominee — expandable */}
              <div className="mt-5 border border-slate-200 rounded-xl overflow-hidden">
                <button
                  type="button"
                  onClick={() => setShowNominee(n => !n)}
                  className="w-full flex items-center justify-between px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 transition-colors"
                >
                  <span className="flex items-center gap-2">
                    <User className="w-4 h-4 text-slate-400" />
                    Nominee Details
                    <span className="text-xs font-normal text-slate-400">(optional)</span>
                  </span>
                  <span className={`text-slate-400 text-xs transition-transform duration-200 ${showNominee ? 'rotate-180' : ''}`}>▼</span>
                </button>
                <AnimatePresence>
                  {showNominee && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      className="overflow-hidden"
                    >
                      <div className="px-4 pb-5 pt-3 grid grid-cols-2 gap-4 border-t border-slate-100 bg-slate-50">
                        <Field label="Nominee Full Name">
                          <input value={nominee.name} onChange={e => setNominee({ ...nominee, name: e.target.value })} placeholder="Full legal name" className={inp} />
                        </Field>
                        <Field label="Relationship">
                          <select value={nominee.relation} onChange={e => setNominee({ ...nominee, relation: e.target.value })} className={sel}>
                            <option value="">Select</option>
                            {['Spouse', 'Son', 'Daughter', 'Father', 'Mother', 'Brother', 'Sister'].map(r => <option key={r}>{r}</option>)}
                          </select>
                        </Field>
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
            </div>
          )}

          {/* ── Step 5 — Bank Details ─────────────────────────────────── */}
          {step === 5 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Bank Details</h2>
              <p className="text-sm text-slate-500 mb-2">
                {showBankReadOnly
                  ? "Review the investor's verified bank account."
                  : "Link the investor's bank account for Platizio bank pre-verification."}
              </p>
              <p className="mb-6 text-xs text-slate-400">
                Sandbox tip: account numbers ending in <span className="font-semibold text-slate-600">1193</span> verify successfully;
                ending in <span className="font-semibold text-slate-600">1515</span> fail verification.
              </p>

              {bankStepError && (
                <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                  {bankStepError}
                </div>
              )}
              {bankStepMessage && (
                <div className="mb-4 rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">
                  {bankStepMessage}
                </div>
              )}

              {bankAccountsLoading && existingBanks.length === 0 && (
                <div className="mb-4 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-500">
                  Loading saved bank account...
                </div>
              )}

              {showBankReadOnly ? (
                <div className="rounded-2xl border border-green-200 bg-green-50/60 p-5">
                  <div className="flex items-start gap-3">
                    <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl bg-green-100 text-green-700">
                      <Building2 className="h-5 w-5" />
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <h3 className="text-sm font-bold text-slate-800">{verifiedBank?.bankName || 'Bank account'}</h3>
                        <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-bold uppercase text-green-700">
                          <CheckCircle2 className="h-3 w-3" /> Verified
                        </span>
                      </div>
                      <p className="mt-1 text-xs text-slate-500">
                        This bank account is verified by Platizio and is ready for transactions.
                      </p>
                    </div>
                  </div>

                  <div className="mt-4 grid grid-cols-2 gap-4">
                    <div>
                      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Account Number</p>
                      <p className="mt-0.5 font-mono text-sm text-slate-800">{maskAccountNumber(String(verifiedBank?.accountNumber || ''))}</p>
                    </div>
                    <div>
                      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">IFSC</p>
                      <p className="mt-0.5 font-mono text-sm text-slate-800">{verifiedBank?.ifscCode || '—'}</p>
                    </div>
                    <div>
                      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Account Holder</p>
                      <p className="mt-0.5 text-sm text-slate-800">{verifiedBank?.accountHolderName || fullName}</p>
                    </div>
                    <div>
                      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Status</p>
                      <p className="mt-0.5 text-sm font-semibold text-green-700">Verified</p>
                    </div>
                  </div>

                  <div className="mt-5 flex flex-col gap-3 border-t border-green-200 pt-4 sm:flex-row sm:items-center sm:justify-between">
                    <p className="text-xs text-slate-500">Need to change the bank account? Open the edit form to capture new details.</p>
                    <button
                      type="button"
                      onClick={startBankEdit}
                      className="inline-flex flex-shrink-0 items-center gap-2 rounded-lg border border-slate-300 bg-white px-4 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
                    >
                      Edit bank details
                    </button>
                  </div>
                </div>
              ) : (
                <div>
                  {verifiedBank && bankEditMode && (
                    <div className="mb-4 flex flex-col gap-2 rounded-xl border border-amber-100 bg-amber-50 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                      <p className="text-xs font-medium leading-5 text-amber-800">
                        Editing bank details. Saving replaces the current verified account and starts a new Platizio verification.
                      </p>
                      <button
                        type="button"
                        onClick={() => setBankEditMode(false)}
                        className="flex-shrink-0 text-xs font-semibold text-slate-600 hover:underline"
                      >
                        Cancel edit
                      </button>
                    </div>
                  )}
                  <div className="grid grid-cols-2 gap-5">
                    <Field label="Account Number" required error={serverErrors.accNumber}>
                      <input
                        value={s5.accNumber}
                        onChange={e => setBankField('accNumber', e.target.value.replace(/\D/g, ''), ['accNumber', 'accountNumber'])}
                        placeholder="12345678901234"
                        maxLength={18}
                        className={inp + ' font-mono'}
                      />
                    </Field>
                    <Field label="Account Type">
                      <select value={s5.accType} onChange={e => setBankField('accType', e.target.value, ['accType', 'accountType'])} className={sel}>
                        {['Savings', 'Current', 'NRE', 'NRO'].map(t => <option key={t}>{t}</option>)}
                      </select>
                    </Field>
                    <div className="col-span-2">
                      <Field label="IFSC Code" required error={serverErrors.ifsc}>
                        <div className="flex gap-3 items-start flex-wrap">
                          <input
                            value={s5.ifsc}
                            onChange={e => setBankField('ifsc', e.target.value.toUpperCase(), ['ifsc', 'ifscCode'])}
                            placeholder="HDFC0001234"
                            maxLength={11}
                            className={inp + ' font-mono w-44'}
                          />
                          <AnimatePresence>
                            {(ifscLookupLoading || bankName) && (
                              <motion.div
                                initial={{ opacity: 0, scale: 0.9 }}
                                animate={{ opacity: 1, scale: 1 }}
                                className="flex items-center gap-2 bg-green-50 border border-green-200 rounded-xl px-3.5 py-2.5"
                              >
                                {ifscLookupLoading
                                  ? <Loader2 className="w-4 h-4 text-green-600 flex-shrink-0 animate-spin" />
                                  : <Building2 className="w-4 h-4 text-green-600 flex-shrink-0" />}
                                <span className="text-sm font-semibold text-green-700">
                                  {ifscLookupLoading ? 'Looking up IFSC…' : `${bankName}${ifscBranchName ? ` · ${ifscBranchName}` : ''}`}
                                </span>
                              </motion.div>
                            )}
                          </AnimatePresence>
                        </div>
                      </Field>
                    </div>
                    <div className="col-span-2">
                      <label
                        className="flex items-center gap-3 cursor-pointer"
                        onClick={() => setBankField('primary', !s5.primary, ['primary'])}
                      >
                        <div className={`w-5 h-5 rounded border-2 flex items-center justify-center transition-all flex-shrink-0 ${s5.primary ? 'bg-[#0B1B3E] border-[#0B1B3E]' : 'border-slate-300 bg-white'
                          }`}>
                          {s5.primary && <Check className="w-3 h-3 text-white" />}
                        </div>
                        <span className="text-sm text-slate-700 select-none">Set as primary bank account</span>
                      </label>
                    </div>
                  </div>

                  {existingBanks.some(bank => !bankIsVerified(bank)) && (
                    <div className="mt-5 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
                      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                        <div>
                          <p className="text-xs font-semibold text-amber-900">Bank verification status</p>
                          <p className="mt-1 text-xs text-amber-800">
                            {bankVerificationStatusText(existingBanks.find(bank => !bankIsVerified(bank)))}
                          </p>
                        </div>
                        <button
                          type="button"
                          onClick={() => void refreshPendingBankStatus()}
                          disabled={bankStepBusy}
                          className="inline-flex items-center gap-2 rounded-lg border border-amber-300 bg-white px-3 py-2 text-xs font-semibold text-amber-800 transition-colors hover:bg-amber-100 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          <RefreshCw className={`h-3.5 w-3.5 ${bankStepBusy ? 'animate-spin' : ''}`} />
                          Refresh status
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}

          {/* ── Step 6 — FATCA ────────────────────────────────────────── */}
          {step === 6 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">FATCA Declaration</h2>
              <p className="text-sm text-slate-500 mb-6">Foreign Account Tax Compliance Act — mandatory for all investors</p>
              <div className="grid grid-cols-2 gap-5">
                <Field label="Country of Tax Residency" required>
                  <select value={s6.taxResidency} onChange={e => setS6({ ...s6, taxResidency: e.target.value })} className={sel}>
                    <option value="" disabled>Select country</option>
                    {['India', 'USA', 'UK', 'Canada', 'Australia', 'UAE', 'Singapore', 'Other'].map(c => <option key={c}>{c}</option>)}
                  </select>
                </Field>
                {s6.taxResidency && s6.taxResidency !== 'India' && (
                  <Field label="Overseas Tax ID / TIN">
                    <input value={s6.taxCountry} onChange={e => setS6({ ...s6, taxCountry: e.target.value })} placeholder="Tax identification number" className={inp} />
                  </Field>
                )}
                <Field label="Annual Income Slab" required>
                  <select value={s6.incomeSlab} onChange={e => setS6({ ...s6, incomeSlab: e.target.value })} className={sel}>
                    <option value="" disabled>Select slab</option>
                    {['Below ₹1 L', '₹1–5 L', '₹5–10 L', '₹10–25 L', '₹25–50 L', 'Above ₹50 L', 'Above ₹1 Cr'].map(r => <option key={r}>{r}</option>)}
                  </select>
                </Field>
                <Field label="Politically Exposed Person (PEP)" required>
                  <select value={s6.politicalExp} onChange={e => setS6({ ...s6, politicalExp: e.target.value })} className={sel}>
                    <option value="" disabled>Select</option>
                    {['No', 'Yes', 'Related to PEP'].map(v => <option key={v}>{v}</option>)}
                  </select>
                </Field>
              </div>

              <div className="mt-6 bg-slate-50 rounded-xl border border-slate-200 p-4">
                <p className="text-xs text-slate-600 leading-relaxed mb-4">
                  I declare that all information provided is true and correct. I confirm that I am not a US Person / non-Indian tax resident unless declared above. I understand this information may be shared with relevant tax authorities under FATCA/CRS regulations.
                </p>
                <label
                  className="flex items-start gap-3 cursor-pointer"
                  onClick={() => setS6({ ...s6, declared: !s6.declared })}
                >
                  <div className={`w-5 h-5 rounded border-2 flex items-center justify-center mt-0.5 transition-all flex-shrink-0 ${s6.declared ? 'bg-[#0B1B3E] border-[#0B1B3E]' : 'border-slate-300 bg-white'
                    }`}>
                    {s6.declared && <Check className="w-3 h-3 text-white" />}
                  </div>
                  <span className="text-sm font-semibold text-slate-700 select-none">
                    I confirm the above declaration on behalf of the investor
                  </span>
                </label>
              </div>

              {/* Task 4 / DF-10 — Terms & Conditions acceptance (separate from FATCA) */}
              <div className="mt-4 bg-slate-50 rounded-xl border border-slate-200 p-4">
                <p className="text-xs text-slate-600 leading-relaxed mb-4">
                  The investor has read and accepted the Platizio Terms &amp; Conditions (v1.0), including the
                  schedule of charges, the risk disclosures, and the privacy policy.
                </p>
                <label
                  className="flex items-start gap-3 cursor-pointer"
                  onClick={() => setS6({ ...s6, termsAccepted: !s6.termsAccepted })}
                >
                  <div className={`w-5 h-5 rounded border-2 flex items-center justify-center mt-0.5 transition-all flex-shrink-0 ${s6.termsAccepted ? 'bg-[#0B1B3E] border-[#0B1B3E]' : 'border-slate-300 bg-white'
                    }`}>
                    {s6.termsAccepted && <Check className="w-3 h-3 text-white" />}
                  </div>
                  <span className="text-sm font-semibold text-slate-700 select-none">
                    The investor accepts the Terms &amp; Conditions (v1.0)
                  </span>
                </label>
              </div>
            </div>
          )}

          {/* ── Step 7 — Documents ────────────────────────────────────── */}
          {step === 7 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Document Upload</h2>
              <p className="text-sm text-slate-500 mb-2">
                Upload self-attested PAN, address proof, and signature. Each file is saved immediately to Platizio
                (PostgreSQL backup) — Platizio digital KYC already collects Aadhaar proofs on step 4.
              </p>
              <p className="text-xs text-slate-400 mb-6">
                Supported: PDF, JPG, PNG (max 5 MB). Sandbox test files: see <span className="font-mono">docs/investor-document-upload-guide.md</span> in the backend repo.
              </p>

              {docsLoading && (
                <div className="mb-4 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-500">
                  Loading saved documents…
                </div>
              )}

              {!investorIdForDocuments && (
                <div className="mb-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs text-amber-800">
                  Investor draft not found yet. Complete Personal and KYC steps first, then return here to upload.
                </div>
              )}

              <div className="space-y-4">
                {([
                  { key: 'pan' as const, label: 'PAN Card', sub: 'Front side, self-attested' },
                  { key: 'address' as const, label: 'Address Proof', sub: 'Aadhaar / Passport / Utility bill (local copy)' },
                  { key: 'signature' as const, label: 'Signature Specimen', sub: 'On white paper — scan or photo' },
                ]).map(doc => {
                  const file = docs[doc.key];
                  const saved = savedDocuments[doc.key];
                  const progress = docProgress[doc.key] || 0;
                  const error = docErrors[doc.key];
                  const uploading = Boolean(docUploading[doc.key]);
                  const isSaved = Boolean(saved?.id);

                  return (
                    <div
                      key={doc.key}
                      className={`p-4 border-2 rounded-xl transition-all ${isSaved
                          ? 'border-green-400 bg-green-50'
                          : 'border-dashed border-slate-300 bg-white hover:border-slate-400 hover:bg-slate-50'
                        }`}
                    >
                      <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-3 min-w-0">
                          {isSaved
                            ? <CheckCircle2 className="w-8 h-8 text-green-600 flex-shrink-0" />
                            : <div className="w-8 h-8 rounded-lg bg-slate-100 flex items-center justify-center flex-shrink-0">
                              <Upload className="w-4 h-4 text-slate-400" />
                            </div>
                          }
                          <div className="min-w-0">
                            <p className="text-sm font-semibold text-slate-800">
                              {doc.label} <span className="text-red-400">*</span>
                              {isSaved && (
                                <span className="ml-2 inline-flex rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-bold uppercase text-green-700">
                                  Saved in Platizio
                                </span>
                              )}
                            </p>
                            <p className="text-xs text-slate-500 truncate">
                              {saved?.fileName || file?.name || doc.sub}
                            </p>
                            {isSaved && saved?.sizeBytes ? (
                              <p className="text-[11px] text-slate-400 mt-0.5">
                                {Math.max(1, Math.round(saved.sizeBytes / 1024))} KB stored in database backup
                              </p>
                            ) : null}
                            {error && <p className="text-xs text-red-600 mt-1">{error}</p>}
                          </div>
                        </div>

                        <label className={`text-xs font-semibold border px-3 py-1.5 rounded-lg transition-colors flex-shrink-0 ${uploading || !investorIdForDocuments
                            ? 'cursor-not-allowed border-slate-200 bg-slate-100 text-slate-400'
                            : 'cursor-pointer text-blue-600 border-blue-200 bg-blue-50 hover:bg-blue-100'
                          }`}>
                          {uploading ? 'Saving…' : isSaved ? 'Replace' : 'Upload'}
                          <input
                            type="file"
                            accept=".pdf,.jpg,.jpeg,.png"
                            className="hidden"
                            disabled={uploading || !investorIdForDocuments}
                            onChange={e => handleDocumentSelect(doc.key, e.target.files?.[0])}
                          />
                        </label>
                      </div>

                      {(uploading || (submitting && file && !isSaved)) && (
                        <div className="mt-3">
                          <div className="h-2 rounded-full bg-green-100 overflow-hidden">
                            <div
                              className="h-full bg-green-500 transition-all"
                              style={{ width: `${progress}%` }}
                            />
                          </div>
                          <p className="text-[11px] text-green-700 font-semibold mt-1">
                            {uploading ? `Saving to Platizio ${progress}%` : `Uploading ${progress}%`}
                          </p>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              <div className="mt-8 rounded-2xl border border-slate-200 bg-slate-50 p-5">
                <h3 className="text-sm font-semibold text-slate-800 mb-3">Document checklist</h3>
                <ul className="space-y-2 mb-5">
                  {([
                    { key: 'pan' as const, label: 'PAN card' },
                    { key: 'address' as const, label: 'Address proof' },
                    { key: 'signature' as const, label: 'Signature specimen' },
                  ]).map(item => {
                    const done = Boolean(savedDocuments[item.key]?.id) || Boolean(docs[item.key]);
                    return (
                      <li key={item.key} className="flex items-center gap-2 text-sm">
                        {done
                          ? <CheckCircle2 className="w-4 h-4 text-green-600 flex-shrink-0" />
                          : <div className="w-4 h-4 rounded-full border-2 border-slate-300 flex-shrink-0" />}
                        <span className={done ? 'text-slate-700' : 'text-slate-500'}>{item.label}</span>
                      </li>
                    );
                  })}
                </ul>
                <button
                  type="button"
                  onClick={() => void submitInvestorToBackend()}
                  disabled={!allRequiredDocumentsSaved || submitting || !investorIdForDocuments || !approvalAttested}
                  title={!approvalAttested ? 'Awaiting investor approval — finalize is locked until the investor approves this exact submission.' : undefined}
                  className="w-full flex items-center justify-center gap-2 px-6 py-3 text-sm font-semibold bg-green-700 text-white rounded-xl hover:bg-green-800 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  {submitting ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Submitting onboarding…
                    </>
                  ) : (
                    <>
                      <CheckCircle2 className="w-4 h-4" />
                      Submit documents &amp; complete onboarding
                    </>
                  )}
                </button>
                <p className="mt-3 text-xs text-slate-500 text-center">
                  {approvalAttested
                    ? 'This finalizes your investor profile, confirms saved documents, and verifies bank status for SIP/mandate setup.'
                    : 'Finalize is locked until the investor approves this exact submission. Use “Submit for investor approval” above.'}
                </p>
              </div>
            </div>
          )}

        </motion.div>
      </AnimatePresence>

      {/* ── Navigation bar ────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between mt-6">
        <button
          type="button"
          onClick={goBack}
          disabled={step === 4 && !isKycFailed}
          title={
            step === 4 && !isKycFailed
              ? 'Complete or resolve KYC on this step before going back, or use Back at the top to leave onboarding'
              : step <= minStep
                ? 'Return to the previous page'
                : undefined
          }
          className="flex items-center gap-2 px-5 py-2.5 text-sm font-semibold text-slate-600 border border-slate-200 bg-white rounded-xl hover:bg-slate-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <ArrowLeft className="w-4 h-4" />
          {step <= minStep ? 'Back' : step === 4 && isKycFailed ? 'Edit details' : 'Back'}
        </button>

        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-400 font-medium">{step} / {STEPS.length}</span>
          <button
            type="button"
            onClick={goNext}
            disabled={!canNext || submitting || bankStepBusy || (step === 7 && !approvalAttested)}
            title={step === 7 && !approvalAttested ? 'Awaiting investor approval — finalize is locked until the investor approves this exact submission.' : undefined}
            className="flex items-center gap-2 px-6 py-2.5 text-sm font-semibold bg-[#0B1B3E] text-white rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {submitting
              ? 'Submitting...'
              : bankStepBusy
                ? 'Verifying bank...'
                : step === 7
                  ? 'Complete onboarding'
                  : step === 1 && (consentCompleted || editingForKycRetry)
                    ? (editingForKycRetry ? 'Continue to KYC' : 'Continue to Personal')
                    : step === 5 && !showBankReadOnly
                      ? 'Save & verify bank'
                      : 'Continue'}
            {(submitting || bankStepBusy) ? <Loader2 className="w-4 h-4 animate-spin" /> : step < 7 && <ArrowRight className="w-4 h-4" />}
          </button>
        </div>
      </div>

      <AnimatePresence>
        {submitError && (
          <motion.div
            className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm"
            role="dialog"
            aria-modal="true"
            aria-labelledby="investor-onboarding-error-title"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
          >
            <motion.div
              className="w-full max-w-md rounded-2xl border border-red-100 bg-white p-6 shadow-2xl"
              initial={{ opacity: 0, y: 18, scale: 0.97 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 12, scale: 0.98 }}
              transition={{ duration: 0.18 }}
            >
              <div className="flex items-start justify-between gap-4">
                <div className="flex items-start gap-3">
                  <div className="mt-0.5 flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl bg-red-50 text-red-600">
                    <AlertTriangle className="h-5 w-5" />
                  </div>
                  <div>
                    <h2 id="investor-onboarding-error-title" className="text-base font-semibold text-slate-900">
                      Investor onboarding failed
                    </h2>
                    <p className="mt-1 text-sm leading-6 text-slate-600">{submitError}</p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setSubmitError('')}
                  className="rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
                  aria-label="Close error message"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
              <div className="mt-5 flex justify-end">
                <button
                  type="button"
                  onClick={() => setSubmitError('')}
                  className="rounded-xl bg-[#0B1B3E] px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
                >
                  Review and retry
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      <CybrillaKycReasonDialog
        open={kycReasonDialogOpen}
        content={kycReasonDialogContent}
        onClose={() => setKycReasonDialogOpen(false)}
      />

    </div>
  );
}
