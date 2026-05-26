import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  ArrowLeft, ArrowRight, CheckCircle2, Loader2, Check,
  ShieldCheck, Upload, Building2, User, AlertTriangle, X,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import { buildValidationSummary, mapServerErrorsToState, parseServerValidation } from '../utils/serverValidation';

// ── Step metadata ────────────────────────────────────────────────────────────
const STEPS = [
  { id: 1, label: 'Basic Info' },
  { id: 2, label: 'Consent' },
  { id: 3, label: 'KYC' },
  { id: 4, label: 'Personal' },
  { id: 5, label: 'Bank' },
  { id: 6, label: 'FATCA' },
  { id: 7, label: 'Documents' },
];

// ── IFSC prefix → bank name ──────────────────────────────────────────────────
const IFSC_MAP: Record<string, string> = {
  HDFC: 'HDFC Bank',
  ICIC: 'ICICI Bank',
  SBIN: 'State Bank of India',
  UTIB: 'Axis Bank',
  KKBK: 'Kotak Mahindra Bank',
  PUNB: 'Punjab National Bank',
  BARB: 'Bank of Baroda',
  CNRB: 'Canara Bank',
  BKID: 'Bank of India',
  IOBA: 'Indian Overseas Bank',
  ABCD: 'ABCD BANK'
};

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
const ALLOWED_DOCUMENT_TYPES = new Set(['application/pdf', 'image/jpeg', 'image/png']);
const ALLOWED_DOCUMENT_EXTENSIONS = new Set(['pdf', 'jpg', 'jpeg', 'png']);

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
export default function InvestorOnboarding({ prospect, userData, onComplete, onBack }: Props) {
  const [step, setStep] = useState(1);
  const [submitted, setSubmitted] = useState(false);
  const [externalSyncPending, setExternalSyncPending] = useState(false);
  const [externalSyncMessage, setExternalSyncMessage] = useState('');
  const [showExternalSyncNotice, setShowExternalSyncNotice] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});
  const [refNum] = useState(() => 'APX' + Date.now().toString().slice(-8));

  // ── Step 1 — Basic Identity ─────────────────────────────────────────────────
  const [s1, setS1] = useState({
    firstName: prospect?.firstName || '',
    lastName: prospect?.lastName || '',
    pan: prospect?.pan || '',
    dob: prospect?.dob || '',
    mobile: prospect?.mobile || '',
    email: prospect?.email || '',
    relationshipType: 'SELF',
    householdName: '',
    guardianPan: '',
  });

  // ── Step 2 — Consent ────────────────────────────────────────────────────────
  const [consentMode, setConsentMode] = useState<'aadhaar' | 'email'>('aadhaar');
  const [consentId, setConsentId] = useState('');
  const [otpSent, setOtpSent] = useState(false);
  const [otp, setOtp] = useState(['', '', '', '', '', '']);
  const [demoOtp, setDemoOtp] = useState('');
  const [countdown, setCountdown] = useState(0);
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  // ── Step 3 — KYC ────────────────────────────────────────────────────────────
  const [kycPhase, setKycPhase] = useState<'idle' | 'loading' | 'found'>('idle');

  // ── Step 4 — Personal ───────────────────────────────────────────────────────
  const [s4, setS4] = useState({ gender: '', occupation: '', income: '', contactOwner: 'Self' });
  const [showNominee, setShowNominee] = useState(false);
  const [nominee, setNominee] = useState({ name: '', relation: '' });

  // ── Step 5 — Bank ────────────────────────────────────────────────────────────
  const [s5, setS5] = useState({ accNumber: '', ifsc: '', accType: 'Savings', primary: true });
  const [bankName, setBankName] = useState('');

  // ── Step 6 — FATCA ───────────────────────────────────────────────────────────
  const [s6, setS6] = useState({
    taxResidency: 'India', taxCountry: '', incomeSlab: '',
    politicalExp: 'No', declared: false,
  });

  // ── Step 7 — Documents ───────────────────────────────────────────────────────
  const [docs, setDocs] = useState<{ pan: File | null; address: File | null; signature: File | null }>({
    pan: null,
    address: null,
    signature: null,
  });
  const [docErrors, setDocErrors] = useState<Record<string, string>>({});
  const [docProgress, setDocProgress] = useState<Record<string, number>>({});

  // ── Effects ─────────────────────────────────────────────────────────────────

  // Countdown tick
  useEffect(() => {
    if (!countdown) return;
    const t = setTimeout(() => setCountdown(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [countdown]);

  // IFSC auto-lookup
  useEffect(() => {
    const prefix = s5.ifsc.slice(0, 4).toUpperCase();
    setBankName(prefix.length === 4 ? (IFSC_MAP[prefix] ?? 'Unknown Bank') : '');
  }, [s5.ifsc]);

  // KYC simulation — auto-start when we enter step 3
  // Only depends on `step` so the cleanup doesn't cancel the timer when
  // kycPhase transitions idle→loading (which would re-run the effect).
  useEffect(() => {
    if (step !== 3) return;
    setKycPhase('loading');
    const t = setTimeout(() => setKycPhase('found'), 2600);
    return () => clearTimeout(t);
  }, [step]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── OTP helpers ──────────────────────────────────────────────────────────────
  const sendOtp = () => {
    const code = Math.floor(100000 + Math.random() * 900000).toString();
    setDemoOtp(code);
    setOtpSent(true);
    setCountdown(30);
    // F-18: in Vite dev only, auto-fill the boxes with the *generated* demo
    // code so the consent step doesn't block the demo. A literal "123456"
    // can't be used here — otpCorrect checks against this randomly generated
    // demoOtp, so the autofill must be `code` itself. import.meta.env.DEV is
    // false in production builds, so prod still requires manual entry (and
    // will be wired to a real backend OTP endpoint when one exists — there is
    // currently no /auth/send-otp or /auth/verify-otp on the backend).
    if (import.meta.env.DEV) {
      setOtp(code.split(''));
    }
    setTimeout(() => otpRefs.current[0]?.focus(), 50);
  };

  const handleOtpChange = (i: number, val: string) => {
    if (!/^[0-9]?$/.test(val)) return;
    const next = [...otp];
    next[i] = val;
    setOtp(next);
    if (val && i < 5) otpRefs.current[i + 1]?.focus();
  };

  const handleOtpKey = (i: number, e: React.KeyboardEvent) => {
    if (e.key === 'Backspace' && !otp[i] && i > 0) otpRefs.current[i - 1]?.focus();
  };

  const otpCorrect = demoOtp.length === 6 && otp.join('') === demoOtp;

  // ── Can proceed guard ─────────────────────────────────────────────────────────
  const canNext: boolean = (() => {
    switch (step) {
      case 1: return !!(
        s1.firstName
        && s1.lastName
        && s1.pan.length >= 10
        && s1.mobile.length >= 10
        && s1.email.includes('@')
        && (s1.relationshipType !== 'MINOR' || s1.guardianPan.length === 10)
      );
      case 2: return otpCorrect;
      case 3: return kycPhase === 'found';
      case 4: return !!(s4.gender && s4.occupation && s4.income);
      case 5: return s5.accNumber.length >= 9 && s5.ifsc.length >= 11;
      case 6: return !!(s6.incomeSlab && s6.declared);
      case 7: return !!(docs.pan && docs.address && docs.signature);
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

  const handleDocumentSelect = (key: keyof typeof docs, file?: File) => {
    if (!file) return;

    const validationError = validateDocumentFile(file);
    setDocErrors(prev => ({ ...prev, [key]: validationError }));
    setDocProgress(prev => ({ ...prev, [key]: 0 }));

    if (validationError) {
      setDocs(prev => ({ ...prev, [key]: null }));
      return;
    }

    setDocs(prev => ({ ...prev, [key]: file }));
  };

  const uploadKycDocument = async (investorId: string, key: keyof typeof docs, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('documentType', String(key).toUpperCase());

    setDocProgress(prev => ({ ...prev, [key]: 25 }));
    const response = await apiFetch(`/investors/${investorId}/documents`, {
      method: 'PUT',
      body: formData,
    });
    const data = await readJsonSafely(response);
    if (!response.ok) {
      throw new Error(typeof data === 'string' ? data : data?.message || `Document upload failed with HTTP ${response.status}.`);
    }

    setDocProgress(prev => ({ ...prev, [key]: 100 }));
    return data;
  };

  const appendExternalSyncWarning = (message: string) => {
    setExternalSyncPending(true);
    setExternalSyncMessage(prev => prev ? `${prev}\n${message}` : message);
    setShowExternalSyncNotice(true);
  };

  const runInitialKycApis = async (createdInvestor: any) => {
    if (!createdInvestor?.id) return createdInvestor;
    let latestInvestor = createdInvestor;

    try {
      console.log('step_1a_kyc_check_request=', {
        endpoint: `POST /api/v1/investors/${createdInvestor.id}/kyc-checks`,
        pan: createdInvestor.pan,
      });
      const kycCheckResponse = await apiFetch(`/investors/${createdInvestor.id}/kyc-checks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dateOfBirth: createdInvestor.dateOfBirth || s1.dob || null }),
      });
      const kycCheckResult = await readJsonSafely(kycCheckResponse);
      console.log('step_1a_kyc_check_response=', {
        status: kycCheckResponse.status,
        ok: kycCheckResponse.ok,
        body: kycCheckResult,
      });

      if (!kycCheckResponse.ok) {
        throw new Error(typeof kycCheckResult === 'string' ? kycCheckResult : kycCheckResult?.message || 'KYC check failed');
      }
      latestInvestor = kycCheckResult?.investor || latestInvestor;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to complete KYC check';
      console.warn('step_1a_kyc_check_external_sync=', message);
      appendExternalSyncWarning(`Unable to complete KYC status check with Cybrilla/Fintech Primitives: ${message}`);
      return latestInvestor;
    }

    if (latestInvestor?.kycStatus === 'COMPLETED') {
      return latestInvestor;
    }

    try {
      console.log('step_1a_kyc_request_create_request=', {
        endpoint: `POST /api/v1/investors/${createdInvestor.id}/kyc-requests`,
      });
      const kycRequestResponse = await apiFetch(`/investors/${createdInvestor.id}/kyc-requests`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fields: {} }),
      });
      const kycRequestResult = await readJsonSafely(kycRequestResponse);
      console.log('step_1a_kyc_request_create_response=', {
        status: kycRequestResponse.status,
        ok: kycRequestResponse.ok,
        body: kycRequestResult,
      });

      if (!kycRequestResponse.ok) {
        throw new Error(typeof kycRequestResult === 'string' ? kycRequestResult : kycRequestResult?.message || 'KYC request creation failed');
      }
      return kycRequestResult?.investor || latestInvestor;
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Unable to create KYC request';
      console.warn('step_1a_kyc_request_external_sync=', message);
      appendExternalSyncWarning(`Unable to create digital KYC request with Cybrilla/Fintech Primitives: ${message}`);
      return latestInvestor;
    }
  };

  const submitInvestorToBackend = async () => {
    const distributorId = userData?.id || userData?.distributorId;
    const fullName = `${s1.firstName} ${s1.lastName}`.trim();

    if (!distributorId) {
      setSubmitError('Distributor session was not found. Please log in again and retry.');
      console.error('[Cybrilla Workflow] Missing distributor id in user session', userData);
      return;
    }

    const investorPayload = {
      distributorId,
      fullName,
      mobileNumber: s1.mobile,
      email: s1.email,
      pan: s1.pan.trim().toUpperCase(),
      dateOfBirth: s1.dob || null,
      relationshipType: s1.relationshipType,
      householdName: s1.householdName.trim() || null,
      guardianPan: s1.relationshipType === 'MINOR' ? s1.guardianPan.trim().toUpperCase() : null,
      addressLine1: '',
      addressLine2: '',
      city: '',
      state: '',
      postalCode: '',
      onboardingNotes: [
        `frontend_reference=${refNum}`,
        `gender=${s4.gender}`,
        `occupation=${s4.occupation}`,
        `income=${s4.income}`,
        `contact_owner=${s4.contactOwner}`,
        `tax_residency=${s6.taxResidency}`,
        `pep=${s6.politicalExp}`,
      ].join('; '),
    };

    const bankPayload = {
      accountHolderName: fullName,
      accountNumber: s5.accNumber,
      ifscCode: s5.ifsc,
      bankName: bankName || undefined,
      branchName: '',
    };

    setSubmitting(true);
    setSubmitError('');
    setExternalSyncPending(false);
    setExternalSyncMessage('');
    setShowExternalSyncNotice(false);
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
      const investorResponse = await apiFetch('/investors', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(investorPayload),
      });
      let investorResult = await readJsonSafely(investorResponse);
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

      const pendingExternalProfile = Boolean(investorResult?.externalSyncPending) || !investorResult?.cybrillaInvestorId;
      setExternalSyncPending(pendingExternalProfile);
      if (pendingExternalProfile) {
        const message = investorResult?.externalSyncMessage
          || 'Unable to post investor data to Cybrilla/Fintech Primitives. The investor was saved locally with KYC PENDING.';
        setExternalSyncMessage(message);
        setShowExternalSyncNotice(true);
        console.warn('step_1_create_investor_external_sync=', message);
      }

      investorResult = await runInitialKycApis(investorResult);

      console.log('step_1b_upload_documents_request=', {
        endpoint: `PUT /api/v1/investors/${investorResult.id}/documents`,
        documents: (Object.entries(docs) as [keyof typeof docs, File | null][]).map(([key, file]) => ({
          documentType: String(key).toUpperCase(),
          fileName: file?.name,
          fileSize: file?.size,
        })),
      });

      for (const [key, file] of Object.entries(docs) as [keyof typeof docs, File | null][]) {
        if (file) {
          await uploadKycDocument(investorResult.id, key, file);
        }
      }

      console.log('step_2_add_bank_request=', {
        ...bankPayload,
        accountNumber: maskAccountNumber(bankPayload.accountNumber),
      });
      console.log('step_2_expected_backend_work=', [
        `POST /api/v1/investors/${investorResult.id}/bank-accounts?actorId=${distributorId}`,
        'InvestorService saves local bank copy',
        'Backend creates FP/Cybrilla bank account using cybrillaInvestorId',
        'Backend stores cybrillaBankId on the local bank account',
      ]);

      const bankResponse = await apiFetch(`/investors/${investorResult.id}/bank-accounts?actorId=${distributorId}`, {
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
      if (Boolean(bankResult?.externalSyncPending) || !bankResult?.cybrillaBankId) {
        const message = bankResult?.externalSyncMessage
          || 'Unable to post bank data to Cybrilla/Fintech Primitives. Bank details were saved locally for retry.';
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
    setStep(s=> s + 1);
  };

  const goBack = () => {
    if (step === 1) { onBack(); return; }
    if (step === 2) { setOtpSent(false); setOtp(['', '', '', '', '', '']); setDemoOtp(''); }
    if (step === 3) { setKycPhase('idle'); }
    setStep(s => s - 1);
  };

  // ═══════════════════════════════════════════════════════════════════════════
  // ── Submitted screen ──────────────────────────────────────────────────────
  if (submitted) {
    const statusRows = externalSyncPending
      ? [
          { label: 'Investor Saved', sub: 'Local profile created successfully', color: 'green', done: true },
          { label: 'KYC Pending', sub: 'PAN/external verification will be retried later', color: 'amber', done: false },
          { label: 'Bank Mandate', sub: 'Bank details saved locally for later sync', color: 'amber', done: false },
          { label: 'Compliance Review', sub: 'FATCA & PMLA check in queue', color: 'blue', done: false },
        ]
      : [
          { label: 'Identity Verified', sub: 'PAN & consent OTP matched', color: 'green', done: true },
          { label: 'KYC Processed', sub: 'CKYC registry record found', color: 'green', done: true },
          { label: 'Bank Mandate', sub: 'eNACH registration pending', color: 'amber', done: false },
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
            {externalSyncPending ? 'Investor created, KYC incomplete' : 'Application Submitted!'}
          </h1>
          <p className="text-slate-500 text-sm mb-6 leading-relaxed">
            {externalSyncPending
              ? (externalSyncMessage || `${s1.firstName} ${s1.lastName}'s profile has been saved with KYC pending. External verification will be retried once the platform is available.`)
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
                          {(externalSyncMessage || 'Unable to post data to Cybrilla/Fintech Primitives. The record was saved locally for retry.')
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
  return (
    <div className="p-8 max-w-4xl">

      {/* Breadcrumb */}
      <button
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
              <div className="grid grid-cols-2 gap-5">
                <Field label="First Name" required error={serverErrors.firstName}>
                  <input value={s1.firstName} onChange={e => setS1({ ...s1, firstName: e.target.value })} placeholder="Rahul" className={inp} />
                </Field>
                <Field label="Last Name" required error={serverErrors.lastName}>
                  <input value={s1.lastName} onChange={e => setS1({ ...s1, lastName: e.target.value })} placeholder="Verma" className={inp} />
                </Field>
                <Field label="PAN Number" required error={serverErrors.pan}>
                  <input
                    value={s1.pan}
                    onChange={e => setS1({ ...s1, pan: e.target.value.toUpperCase() })}
                    placeholder="ABCDE1234F"
                    maxLength={10}
                    className={inp + ' font-mono tracking-widest'}
                  />
                </Field>
                <Field label="Date of Birth" error={serverErrors.dob}>
                  <input type="date" value={s1.dob} onChange={e => setS1({ ...s1, dob: e.target.value })} className={inp} />
                </Field>
                <Field label="Relationship Type">
                  <select
                    value={s1.relationshipType}
                    onChange={e => setS1({ ...s1, relationshipType: e.target.value })}
                    className={sel}
                  >
                    <option value="SELF">Self / Primary</option>
                    <option value="SPOUSE">Spouse / Joint holder</option>
                    <option value="MINOR">Minor folio</option>
                    <option value="HUF">HUF</option>
                  </select>
                </Field>
                <Field label="Household Name">
                  <input
                    value={s1.householdName}
                    onChange={e => setS1({ ...s1, householdName: e.target.value })}
                    placeholder="Sharma Family"
                    className={inp}
                  />
                </Field>
                {s1.relationshipType === 'MINOR' && (
                  <Field label="Guardian PAN" required error={serverErrors.guardianPan}>
                    <input
                      value={s1.guardianPan}
                      onChange={e => setS1({ ...s1, guardianPan: e.target.value.toUpperCase() })}
                      placeholder="ABCDE1234F"
                      maxLength={10}
                      className={inp + ' font-mono tracking-widest'}
                    />
                  </Field>
                )}
                <Field label="Mobile Number" required error={serverErrors.mobile}>
                  <input value={s1.mobile} onChange={e => setS1({ ...s1, mobile: e.target.value.replace(/\D/g, '') })} placeholder="9876543210" maxLength={13} className={inp} />
                </Field>
                <Field label="Email Address" required error={serverErrors.email}>
                  <input type="email" value={s1.email} onChange={e => setS1({ ...s1, email: e.target.value })} placeholder="investor@email.com" className={inp} />
                </Field>
              </div>
            </div>
          )}

          {/* ── Step 2 — Consent OTP ──────────────────────────────────── */}
          {step === 2 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Investor Consent</h2>
              <p className="text-sm text-slate-500 mb-5">Verify investor identity via OTP before proceeding</p>

              {/* Mode toggle */}
              <div className="flex gap-2 mb-5">
                {(['aadhaar', 'email'] as const).map(m => (
                  <button
                    key={m}
                    onClick={() => { setConsentMode(m); setOtpSent(false); setConsentId(''); setOtp(['', '', '', '', '', '']); setDemoOtp(''); }}
                    className={`px-4 py-2 rounded-xl text-sm font-semibold border transition-all ${consentMode === m
                        ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]'
                        : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300'
                      }`}
                  >
                    {m === 'aadhaar' ? 'Aadhaar OTP' : 'Email OTP'}
                  </button>
                ))}
              </div>

              {!otpSent ? (
                <div className="space-y-4 max-w-sm">
                  <Field label={consentMode === 'aadhaar' ? 'Aadhaar Number' : 'Email Address'} required>
                    <input
                      value={consentId}
                      onChange={e => setConsentId(e.target.value)}
                      placeholder={consentMode === 'aadhaar' ? '1234 5678 9012' : (s1.email || 'investor@email.com')}
                      className={inp}
                    />
                  </Field>
                  <p className="text-xs text-slate-400">
                    A 6-digit OTP will be sent to the investor's {consentMode === 'aadhaar' ? 'Aadhaar-linked mobile' : 'registered email'}.
                  </p>
                  <button
                    onClick={sendOtp}
                    disabled={!consentId}
                    className="px-5 py-2.5 bg-[#0B1B3E] text-white text-sm font-semibold rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-40"
                  >
                    Send OTP
                  </button>
                </div>
              ) : (
                <div className="space-y-5 max-w-sm">
                  <p className="text-sm text-slate-600">
                    OTP sent to investor's {consentMode === 'aadhaar' ? 'Aadhaar-linked mobile' : 'email'}.
                  </p>
                  <div className="bg-blue-50 border border-blue-200 rounded-xl px-4 py-2.5 text-sm">
                    Demo hint — OTP:{' '}
                    <span className="font-mono font-bold text-blue-700 tracking-widest">{demoOtp}</span>
                  </div>

                  {/* 6-box OTP input */}
                  <div className="flex gap-2">
                    {otp.map((d, i) => (
                      <input
                        key={i}
                        ref={el => { otpRefs.current[i] = el; }}
                        value={d}
                        onChange={e => handleOtpChange(i, e.target.value.slice(-1))}
                        onKeyDown={e => handleOtpKey(i, e)}
                        maxLength={1}
                        className={`w-12 h-12 text-center text-lg font-bold border-2 rounded-xl outline-none transition-all ${otpCorrect
                            ? 'border-green-500 bg-green-50 text-green-700'
                            : d
                              ? 'border-blue-400 text-slate-800'
                              : 'border-slate-200 text-slate-400'
                          }`}
                      />
                    ))}
                  </div>

                  <AnimatePresence>
                    {otpCorrect && (
                      <motion.div
                        initial={{ opacity: 0, y: 4 }}
                        animate={{ opacity: 1, y: 0 }}
                        className="flex items-center gap-2 text-green-600 text-sm font-semibold"
                      >
                        <CheckCircle2 className="w-4 h-4" /> OTP verified successfully
                      </motion.div>
                    )}
                  </AnimatePresence>

                  <div>
                    {countdown > 0
                      ? <span className="text-xs text-slate-400">Resend in {countdown}s</span>
                      : <button onClick={sendOtp} className="text-xs font-semibold text-blue-600 hover:underline">Resend OTP</button>
                    }
                  </div>
                </div>
              )}
            </div>
          )}

          {/* ── Step 3 — KYC Processing ───────────────────────────────── */}
          {step === 3 && (
            <div className="text-center py-8">
              <h2 className="text-lg font-semibold text-slate-800 mb-1">KYC Processing</h2>
              <p className="text-sm text-slate-500 mb-8">Checking CKYC registry and verifying investor documents</p>

              {kycPhase === 'loading' && (
                <div className="space-y-6">
                  <div className="w-20 h-20 bg-blue-50 border-4 border-blue-100 rounded-full flex items-center justify-center mx-auto">
                    <Loader2 className="w-8 h-8 text-blue-600 animate-spin" />
                  </div>
                  <div className="space-y-2 max-w-xs mx-auto text-left">
                    {['Querying CKYC registry…', 'Verifying PAN details…', 'Matching Aadhaar data…'].map((t, i) => (
                      <div key={i} className="flex items-center gap-2 text-sm text-slate-500">
                        <Loader2 className="w-3.5 h-3.5 text-blue-400 animate-spin flex-shrink-0" />
                        {t}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {kycPhase === 'found' && (
                <motion.div
                  initial={{ opacity: 0, scale: 0.9 }}
                  animate={{ opacity: 1, scale: 1 }}
                  className="space-y-6"
                >
                  <div className="w-20 h-20 bg-green-100 rounded-full flex items-center justify-center mx-auto">
                    <ShieldCheck className="w-10 h-10 text-green-600" />
                  </div>
                  <div>
                    <h3 className="text-lg font-bold text-green-700">KYC Record Found!</h3>
                    <p className="text-sm text-slate-500 mt-1">CKYC registry match successful</p>
                  </div>
                  <div className="bg-slate-50 rounded-2xl border border-slate-200 p-5 max-w-xs mx-auto text-left space-y-3">
                    {[
                      { label: 'KYC Status', value: 'Compliant', badge: 'bg-green-100 text-green-700' },
                      { label: 'CKYC Number', value: 'CKYC-' + (s1.pan.slice(-4) || 'XXXX') + '-2025' },
                      { label: 'KYC Type', value: 'Full KYC (In-person)' },
                      { label: 'Verified By', value: 'CDSL Ventures Ltd' },
                    ].map(row => (
                      <div key={row.label} className="flex justify-between items-center">
                        <span className="text-xs text-slate-500">{row.label}</span>
                        {row.badge
                          ? <span className={`text-xs font-bold px-2 py-0.5 rounded-full ${row.badge}`}>{row.value}</span>
                          : <span className="text-xs font-semibold text-slate-800">{row.value}</span>
                        }
                      </div>
                    ))}
                  </div>
                </motion.div>
              )}
            </div>
          )}

          {/* ── Step 4 — Personal Details ─────────────────────────────── */}
          {step === 4 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Personal Details</h2>
              <p className="text-sm text-slate-500 mb-6">Additional investor profile information</p>
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
                <Field label="Contact Ownership">
                  <select value={s4.contactOwner} onChange={e => setS4({ ...s4, contactOwner: e.target.value })} className={sel}>
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
              <p className="text-sm text-slate-500 mb-6">Link the investor's bank account for transactions</p>
              <div className="grid grid-cols-2 gap-5">
                <Field label="Account Number" required error={serverErrors.accNumber}>
                  <input
                    value={s5.accNumber}
                    onChange={e => setS5({ ...s5, accNumber: e.target.value.replace(/\D/g, '') })}
                    placeholder="12345678901234"
                    maxLength={18}
                    className={inp + ' font-mono'}
                  />
                </Field>
                <Field label="Account Type">
                  <select value={s5.accType} onChange={e => setS5({ ...s5, accType: e.target.value })} className={sel}>
                    {['Savings', 'Current', 'NRE', 'NRO'].map(t => <option key={t}>{t}</option>)}
                  </select>
                </Field>
                <div className="col-span-2">
                  <Field label="IFSC Code" required error={serverErrors.ifsc}>
                    <div className="flex gap-3 items-start flex-wrap">
                      <input
                        value={s5.ifsc}
                        onChange={e => setS5({ ...s5, ifsc: e.target.value.toUpperCase() })}
                        placeholder="HDFC0001234"
                        maxLength={11}
                        className={inp + ' font-mono w-44'}
                      />
                      <AnimatePresence>
                        {bankName && (
                          <motion.div
                            initial={{ opacity: 0, scale: 0.9 }}
                            animate={{ opacity: 1, scale: 1 }}
                            className="flex items-center gap-2 bg-green-50 border border-green-200 rounded-xl px-3.5 py-2.5"
                          >
                            <Building2 className="w-4 h-4 text-green-600 flex-shrink-0" />
                            <span className="text-sm font-semibold text-green-700">{bankName}</span>
                          </motion.div>
                        )}
                      </AnimatePresence>
                    </div>
                  </Field>
                </div>
                <div className="col-span-2">
                  <label
                    className="flex items-center gap-3 cursor-pointer"
                    onClick={() => setS5({ ...s5, primary: !s5.primary })}
                  >
                    <div className={`w-5 h-5 rounded border-2 flex items-center justify-center transition-all flex-shrink-0 ${s5.primary ? 'bg-[#0B1B3E] border-[#0B1B3E]' : 'border-slate-300 bg-white'
                      }`}>
                      {s5.primary && <Check className="w-3 h-3 text-white" />}
                    </div>
                    <span className="text-sm text-slate-700 select-none">Set as primary bank account</span>
                  </label>
                </div>
              </div>
            </div>
          )}

          {/* ── Step 6 — FATCA ────────────────────────────────────────── */}
          {step === 6 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">FATCA Declaration</h2>
              <p className="text-sm text-slate-500 mb-6">Foreign Account Tax Compliance Act — mandatory for all investors</p>
              <div className="grid grid-cols-2 gap-5">
                <Field label="Country of Tax Residency">
                  <select value={s6.taxResidency} onChange={e => setS6({ ...s6, taxResidency: e.target.value })} className={sel}>
                    {['India', 'USA', 'UK', 'Canada', 'Australia', 'UAE', 'Singapore', 'Other'].map(c => <option key={c}>{c}</option>)}
                  </select>
                </Field>
                {s6.taxResidency !== 'India' && (
                  <Field label="Overseas Tax ID / TIN">
                    <input value={s6.taxCountry} onChange={e => setS6({ ...s6, taxCountry: e.target.value })} placeholder="Tax identification number" className={inp} />
                  </Field>
                )}
                <Field label="Annual Income Slab" required>
                  <select value={s6.incomeSlab} onChange={e => setS6({ ...s6, incomeSlab: e.target.value })} className={sel}>
                    <option value="">Select slab</option>
                    {['Below ₹1 L', '₹1–5 L', '₹5–10 L', '₹10–25 L', '₹25–50 L', 'Above ₹50 L', 'Above ₹1 Cr'].map(r => <option key={r}>{r}</option>)}
                  </select>
                </Field>
                <Field label="Politically Exposed Person (PEP)">
                  <select value={s6.politicalExp} onChange={e => setS6({ ...s6, politicalExp: e.target.value })} className={sel}>
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
            </div>
          )}

          {/* ── Step 7 — Documents ────────────────────────────────────── */}
          {step === 7 && (
            <div>
              <h2 className="text-lg font-semibold text-slate-800 mb-1">Document Upload</h2>
              <p className="text-sm text-slate-500 mb-6">Upload self-attested copies of the required documents</p>

              <div className="space-y-4">
                {[
                  { key: 'pan' as const, label: 'PAN Card', sub: 'Front side, self-attested' },
                  { key: 'address' as const, label: 'Address Proof', sub: 'Aadhaar / Passport / Utility bill' },
                  { key: 'signature' as const, label: 'Signature Specimen', sub: 'On white paper — scan or photo' },
                ].map(doc => {
                  const file = docs[doc.key];
                  const progress = docProgress[doc.key] || 0;
                  const error = docErrors[doc.key];

                  return (
                    <div
                      key={doc.key}
                      className={`p-4 border-2 rounded-xl transition-all ${file
                          ? 'border-green-400 bg-green-50'
                          : 'border-dashed border-slate-300 bg-white hover:border-slate-400 hover:bg-slate-50'
                        }`}
                    >
                      <div className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-3 min-w-0">
                          {file
                            ? <CheckCircle2 className="w-8 h-8 text-green-600 flex-shrink-0" />
                            : <div className="w-8 h-8 rounded-lg bg-slate-100 flex items-center justify-center flex-shrink-0">
                              <Upload className="w-4 h-4 text-slate-400" />
                            </div>
                          }
                          <div className="min-w-0">
                            <p className="text-sm font-semibold text-slate-800">
                              {doc.label} <span className="text-red-400">*</span>
                            </p>
                            <p className="text-xs text-slate-500 truncate">
                              {file ? file.name : doc.sub}
                            </p>
                            {error && <p className="text-xs text-red-600 mt-1">{error}</p>}
                          </div>
                        </div>

                        <label className="text-xs font-semibold text-blue-600 border border-blue-200 bg-blue-50 px-3 py-1.5 rounded-lg cursor-pointer hover:bg-blue-100 transition-colors flex-shrink-0">
                          {file ? 'Replace' : 'Upload'}
                          <input
                            type="file"
                            accept=".pdf,.jpg,.png"
                            className="hidden"
                            onChange={e => handleDocumentSelect(doc.key, e.target.files?.[0])}
                          />
                        </label>
                      </div>

                      {submitting && file && (
                        <div className="mt-3">
                          <div className="h-2 rounded-full bg-green-100 overflow-hidden">
                            <div
                              className="h-full bg-green-500 transition-all"
                              style={{ width: `${progress}%` }}
                            />
                          </div>
                          <p className="text-[11px] text-green-700 font-semibold mt-1">
                            Uploading {progress}%
                          </p>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              <p className="text-xs text-slate-400 mt-5">
                Supported: PDF, JPG, PNG (max 5 MB each). Files upload after the investor profile is created.
              </p>
            </div>
          )}

        </motion.div>
      </AnimatePresence>

      {/* ── Navigation bar ────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between mt-6">
        <button
          onClick={goBack}
          className="flex items-center gap-2 px-5 py-2.5 text-sm font-semibold text-slate-600 border border-slate-200 bg-white rounded-xl hover:bg-slate-50 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          {step === 1 ? 'Cancel' : 'Back'}
        </button>

        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-400 font-medium">{step} / {STEPS.length}</span>
          <button
            onClick={goNext}
            disabled={!canNext || submitting}
            className="flex items-center gap-2 px-6 py-2.5 text-sm font-semibold bg-[#0B1B3E] text-white rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          >
            {submitting ? 'Submitting...' : step === 7 ? 'Submit Application' : 'Continue'}
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : step < 7 && <ArrowRight className="w-4 h-4" />}
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

    </div>
  );
}
