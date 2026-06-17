import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { motion, AnimatePresence } from 'motion/react';
import {
  Search, Filter, ChevronLeft, Download, ShieldCheck, Users,
  TrendingUp, TrendingDown, AreaChart as AreaChartIcon, Activity,
  CheckCircle2, Clock, XCircle, AlertCircle, Upload, RefreshCw, Pencil,
  Trash2, Plus, ArrowRight,
} from 'lucide-react';
import {
  AreaChart as RechartsArea, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { apiClient, apiFetch, apiUrl } from '../config/api';
import Pagination from '../components/Pagination';
import { formatDate } from '../utils/formatDate';
import { isTransactionEligible, transactionEligibilityMessage } from '../utils/investorEligibility';
import {
  inferWizardStepFromInvestor,
  mapBackendNextStepToWizardStep,
} from '../utils/onboardingResume';
import { useDebounce } from '../hooks/useDebounce';
import {
  listArchivedInvestorsForDistributor,
  rememberArchivedInvestor,
  restoreArchivedInvestorRef,
} from '../utils/archivedInvestorRestore';
import EmptyState from '../components/EmptyState';
import InvestorEditForm from '../components/InvestorEditForm';
import CybrillaKycWarnings from '../components/CybrillaKycWarnings';
import CybrillaKycReasonDialog, { type CybrillaKycReasonDialogContent } from '../components/CybrillaKycReasonDialog';
import KycFlowPanel from '../components/KycFlowPanel';
import KycProviderLink from '../components/KycProviderLink';
import {
  shouldLockAadhaarStart,
  shouldLockEsignStart,
  shouldLockKycRequestCreate,
  shouldLockPoaRunButton,
  isPoaPreVerificationComplete,
} from '../utils/kycActionLocks';
import {
  extractPreVerification,
  resolvePreVerificationFromPayload,
  extractCybrillaWarningsFromPayload,
  isIdentityDocumentFetchComplete,
  isPanAadhaarNotLinked,
  normalizeAadhaarVerificationResponse,
  readAadhaarVerificationFromInvestor,
  getPreVerificationDecision,
  readCybrillaReadinessReason,
  canCreateFreshKycRequest,
  isReadinessFailureBlockingKycRequest,
  getPreVerificationRows,
  buildKycReasonDialogContent,
  type KycReasonDialogInput,
  parseStoredPreVerification,
  preVerificationStatusClasses,
  computeIdentityFingerprint,
  normalizePan,
  shouldForceNewKycCheck,
  validateInvestorIdentityForKyc,
  isCybrillaSandboxMode,
  type CybrillaKycWarning,
} from '../utils/kycPreVerification';
import {
  buildKycPostbackUrl,
  normalizeEsignVerificationResponse,
  normalizeKycFlowStatus,
  type KycFlowNextAction,
  type KycFlowStatusView,
} from '../utils/kycFlow';

// ─── KYC status config ─────────────────────────────────────────────────────────
const KYC_BADGE_CONFIG: Record<string, { label: string; bg: string; text: string }> = {
  COMPLETED: { label: 'KYC Verified', bg: 'bg-green-100', text: 'text-green-700' },
  VERIFIED: { label: 'KYC Verified', bg: 'bg-green-100', text: 'text-green-700' },
  PENDING: { label: 'KYC Pending', bg: 'bg-amber-100', text: 'text-amber-700' },
  IN_PROGRESS: { label: 'KYC In Progress', bg: 'bg-blue-100', text: 'text-blue-700' },
  FAILED: { label: 'KYC Failed', bg: 'bg-red-100', text: 'text-red-700' },
  RETRY_REQUIRED: { label: 'Retry Required', bg: 'bg-red-100', text: 'text-red-700' },
  REJECTED: { label: 'KYC Failed', bg: 'bg-red-100', text: 'text-red-700' },
  NOT_STARTED: { label: 'Not Started', bg: 'bg-slate-100', text: 'text-slate-500' }
};

const statusConfig: Record<string, string> = {
  DRAFT: 'bg-slate-100 text-slate-600',
  ONBOARDING: 'bg-blue-50 text-blue-600',
  PENDING: 'bg-amber-50 text-amber-700',
  READY_FOR_TRANSACTIONS: 'bg-green-50 text-green-700',
  ACTIVE: 'bg-green-50 text-green-700',
  BLOCKED: 'bg-red-50 text-red-600',
  ARCHIVED: 'bg-slate-100 text-slate-400',
  INACTIVE: 'bg-slate-100 text-slate-400',
};

const riskConfig: Record<string, string> = {
  UNASSESSED: 'bg-slate-50 text-slate-500',
  CONSERVATIVE: 'bg-blue-50 text-blue-600',
  MODERATE: 'bg-amber-50 text-amber-600',
  AGGRESSIVE: 'bg-red-50 text-red-600',
};

const matchesInvestorSearch = (inv: any, query: string) => {
  if (!query) return true;
  const normalized = query.toLowerCase();

  const searchableFields = normalized.length === 1
    ? [inv.fullName, inv.pan]
    : [
      inv.fullName,
      inv.pan,
      inv.email,
      inv.mobileNumber,
      inv.city,
    ];

  return searchableFields.some(value => String(value || '').toLowerCase().includes(normalized));
};

const MAX_KYC_DOCUMENT_SIZE = 5 * 1024 * 1024;
const ALLOWED_KYC_DOCUMENT_TYPES = new Set(['application/pdf', 'image/jpeg', 'image/png']);
const ALLOWED_KYC_DOCUMENT_EXTENSIONS = new Set(['pdf', 'jpg', 'jpeg', 'png']);

const validateKycDocument = (file: File) => {
  const extension = file.name.split('.').pop()?.toLowerCase() || '';
  if (!ALLOWED_KYC_DOCUMENT_TYPES.has(file.type) && !ALLOWED_KYC_DOCUMENT_EXTENSIONS.has(extension)) {
    return 'Only PDF, JPG, and PNG files are allowed.';
  }
  if (file.size > MAX_KYC_DOCUMENT_SIZE) {
    return 'File size must be 5 MB or less.';
  }
  return '';
};

const normalizeWorkflowStatus = (value?: string) => String(value || '').trim().toUpperCase();

const isKycVerifiedStatus = (status?: string) => {
  const normalized = normalizeWorkflowStatus(status);
  return normalized === 'COMPLETED' || normalized === 'VERIFIED';
};

const shouldShowContinueOnboarding = (investor: any) => {
  const investorStatus = normalizeWorkflowStatus(investor?.investorStatus);
  const kycStatus = normalizeWorkflowStatus(investor?.kycStatus);
  const bankStatus = normalizeWorkflowStatus(investor?.bankVerificationStatus);

  if (investorStatus === 'READY_FOR_TRANSACTIONS' || investorStatus === 'ACTIVE') return false;
  return ['DRAFT', 'ONBOARDING', 'PENDING'].includes(investorStatus)
    || ['NOT_STARTED', 'PENDING', 'IN_PROGRESS', 'FAILED', 'RETRY_REQUIRED', 'REJECTED'].includes(kycStatus)
    || bankStatus === 'NOT_CAPTURED'
    || bankStatus === 'VERIFICATION_PENDING'
    || bankStatus === 'PENDING';
};

const getOnboardingResumeStep = (investor: any) => inferWizardStepFromInvestor(investor);

const maskAccountNumber = (value?: string) => {
  if (!value) return 'Account not captured';
  const lastFour = value.slice(-4);
  return `${'*'.repeat(Math.max(value.length - 4, 4))}${lastFour}`;
};

function KycDocumentUpload({
  investorId,
  onUploaded,
}: {
  investorId?: string;
  onUploaded?: (updatedInvestor: any) => void;
}) {
  const [progress, setProgress] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const handleFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    setError('');
    setSuccess('');
    setProgress(0);

    if (!file) return;
    if (!investorId) {
      setError('Investor ID is missing. Please save the investor before uploading documents.');
      return;
    }

    const validationError = validateKycDocument(file);
    if (validationError) {
      setError(validationError);
      return;
    }

    const formData = new FormData();
    formData.append('file', file);
    formData.append('documentType', 'KYC');

    try {
      setUploading(true);
      const response = await apiClient.put(`/investors/${investorId}/documents`, formData, {
        onUploadProgress: event => {
          const total = event.total || file.size || event.loaded || 1;
          setProgress(Math.min(99, Math.round((event.loaded * 100) / total)));
        },
      });

      setProgress(100);
      setSuccess('KYC document uploaded successfully.');
      onUploaded?.(response.data?.data || response.data?.investor || response.data);
    } catch (err) {
      console.error('KYC document upload failed:', err);
      const responseData = (err as any)?.response?.data;
      setError(responseData?.message || (err instanceof Error ? err.message : 'KYC document upload failed.'));
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50 p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="text-sm font-semibold text-slate-800">KYC Document Upload</p>
          <p className="mt-0.5 text-xs text-slate-500">PDF, JPG, or PNG up to 5 MB.</p>
        </div>
        <label className={`flex cursor-pointer items-center justify-center gap-2 rounded-lg px-4 py-2 text-sm font-semibold transition-colors ${uploading ? 'bg-slate-200 text-slate-400' : 'bg-[#0B1B3E] text-white hover:bg-[#1A3066]'
          }`}>
          <Upload className="h-4 w-4" />
          {uploading ? 'Uploading...' : 'Upload file'}
          <input
            type="file"
            accept=".pdf,.jpg,.png"
            disabled={uploading}
            onChange={handleFileChange}
            className="hidden"
          />
        </label>
      </div>

      {uploading && (
        <div className="mt-4">
          <div className="mb-1 flex justify-between text-xs font-semibold text-slate-500">
            <span>Uploading</span>
            <span>{progress}%</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-slate-200">
            <div className="h-full rounded-full bg-blue-500 transition-all" style={{ width: `${progress}%` }} />
          </div>
        </div>
      )}

      {error && (
        <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-red-600">
          <AlertCircle className="h-3.5 w-3.5" /> {error}
        </p>
      )}
      {success && (
        <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-green-600">
          <CheckCircle2 className="h-3.5 w-3.5" /> {success}
        </p>
      )}
    </div>
  );
}


// ─── Component ────────────────────────────────────────────────────────────────
function BankAccountsPanel({
  investorId,
  onBankUpdated,
  defaultAccountHolderName,
}: {
  investorId?: string;
  onBankUpdated?: (patch: any) => void;
  defaultAccountHolderName?: string;
}) {
  const [accounts, setAccounts] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshingId, setRefreshingId] = useState('');
  const [adding, setAdding] = useState(false);
  const [savingAccount, setSavingAccount] = useState(false);
  const [addForm, setAddForm] = useState({
    accountHolderName: defaultAccountHolderName || '',
    accountNumber: '',
    ifscCode: '',
    bankName: '',
    branchName: '',
  });
  const [error, setError] = useState('');

  const loadAccounts = React.useCallback(async () => {
    if (!investorId) return;
    setLoading(true);
    setError('');
    try {
      const response = await apiFetch(`/investors/${investorId}/bank-accounts`);
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `Bank accounts failed with HTTP ${response.status}.`);
      }
      setAccounts(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Bank account fetch failed:', err);
      setError(err instanceof Error ? err.message : 'Bank accounts could not be loaded.');
      setAccounts([]);
    } finally {
      setLoading(false);
    }
  }, [investorId]);

  useEffect(() => {
    loadAccounts();
  }, [loadAccounts]);

  useEffect(() => {
    setAddForm(prev => ({
      ...prev,
      accountHolderName: prev.accountHolderName || defaultAccountHolderName || '',
    }));
  }, [defaultAccountHolderName]);

  const addBankAccount = async () => {
    if (!investorId) return;
    const payload = {
      ...addForm,
      accountHolderName: addForm.accountHolderName.trim(),
      accountNumber: addForm.accountNumber.trim(),
      ifscCode: addForm.ifscCode.trim().toUpperCase(),
      bankName: addForm.bankName.trim() || undefined,
      branchName: addForm.branchName.trim() || undefined,
    };

    if (!payload.accountHolderName || !payload.accountNumber || !payload.ifscCode) {
      setError('Account holder, account number, and IFSC are required.');
      return;
    }

    setSavingAccount(true);
    setError('');
    try {
      const response = await apiFetch(`/investors/${investorId}/bank-accounts`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `Bank account save failed with HTTP ${response.status}.`);
      }

      setAccounts(prev => [data, ...prev.filter(account => account.id !== data?.id)]);
      if (data?.verificationStatus || data?.cybrillaBankVerificationStatus) {
        onBankUpdated?.({
          bankVerificationStatus: data.verificationStatus || data.cybrillaBankVerificationStatus,
        });
      }
      setAddForm({
        accountHolderName: defaultAccountHolderName || '',
        accountNumber: '',
        ifscCode: '',
        bankName: '',
        branchName: '',
      });
      setAdding(false);
    } catch (err) {
      console.error('Bank account save failed:', err);
      setError(err instanceof Error ? err.message : 'Bank account could not be saved.');
    } finally {
      setSavingAccount(false);
    }
  };

  const refreshVerification = async (accountId: string) => {
    if (!investorId || !accountId) return;
    setRefreshingId(accountId);
    setError('');
    try {
      const response = await apiFetch(`/investors/${investorId}/bank-accounts/${accountId}/verification`, {
        method: 'PATCH',
      });
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `Bank verification refresh failed with HTTP ${response.status}.`);
      }
      setAccounts(prev => prev.map(account => account.id === accountId ? { ...account, ...data } : account));
      if (data?.verificationStatus) {
        onBankUpdated?.({ bankVerificationStatus: data.verificationStatus });
      }
    } catch (err) {
      console.error('Bank verification refresh failed:', err);
      setError(err instanceof Error ? err.message : 'Bank verification could not be refreshed.');
    } finally {
      setRefreshingId('');
    }
  };

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="text-sm font-semibold text-slate-800">Bank Verification</p>
          <p className="mt-0.5 text-xs text-slate-500">Platizio bank accounts and verification status.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setAdding(prev => !prev)}
            className="inline-flex items-center justify-center gap-2 rounded-lg border border-blue-200 px-3 py-2 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-50"
          >
            <Plus className="h-3.5 w-3.5" />
            Add bank
          </button>
          <button
            type="button"
            onClick={loadAccounts}
            disabled={loading}
            className="inline-flex items-center justify-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            Refresh list
          </button>
        </div>
      </div>

      {error && (
        <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-red-600">
          <AlertCircle className="h-3.5 w-3.5" /> {error}
        </p>
      )}

      {adding && (
        <div className="mt-4 rounded-xl border border-blue-100 bg-blue-50 p-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <input
              value={addForm.accountHolderName}
              onChange={event => setAddForm(prev => ({ ...prev, accountHolderName: event.target.value }))}
              placeholder="Account holder name"
              className="rounded-lg border border-blue-100 bg-white px-3 py-2 text-xs font-medium text-slate-700 outline-none focus:border-blue-400"
            />
            <input
              value={addForm.accountNumber}
              onChange={event => setAddForm(prev => ({ ...prev, accountNumber: event.target.value.replace(/\D/g, '') }))}
              placeholder="Account number"
              maxLength={18}
              className="rounded-lg border border-blue-100 bg-white px-3 py-2 text-xs font-mono text-slate-700 outline-none focus:border-blue-400"
            />
            <input
              value={addForm.ifscCode}
              onChange={event => setAddForm(prev => ({ ...prev, ifscCode: event.target.value.toUpperCase() }))}
              placeholder="IFSC"
              maxLength={11}
              className="rounded-lg border border-blue-100 bg-white px-3 py-2 text-xs font-mono text-slate-700 outline-none focus:border-blue-400"
            />
            <input
              value={addForm.bankName}
              onChange={event => setAddForm(prev => ({ ...prev, bankName: event.target.value }))}
              placeholder="Bank name"
              className="rounded-lg border border-blue-100 bg-white px-3 py-2 text-xs font-medium text-slate-700 outline-none focus:border-blue-400"
            />
            <input
              value={addForm.branchName}
              onChange={event => setAddForm(prev => ({ ...prev, branchName: event.target.value }))}
              placeholder="Branch"
              className="rounded-lg border border-blue-100 bg-white px-3 py-2 text-xs font-medium text-slate-700 outline-none focus:border-blue-400 sm:col-span-2"
            />
          </div>
          <div className="mt-3 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setAdding(false)}
              disabled={savingAccount}
              className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={addBankAccount}
              disabled={savingAccount}
              className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-3 py-2 text-xs font-semibold text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
            >
              <Plus className="h-3.5 w-3.5" />
              {savingAccount ? 'Saving...' : 'Save bank'}
            </button>
          </div>
        </div>
      )}

      <div className="mt-4 space-y-3">
        {loading ? (
          <p className="text-sm text-slate-500">Loading bank accounts...</p>
        ) : accounts.length === 0 ? (
          <p className="text-sm text-slate-500">No bank accounts captured yet.</p>
        ) : (
          accounts.map(account => {
            const status = String(account.verificationStatus || 'NOT_CAPTURED').replace(/_/g, ' ');
            const canRefresh = Boolean(account.cybrillaBankVerificationId);
            return (
              <div key={account.id} className="rounded-xl border border-slate-100 bg-slate-50 p-3">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-slate-800">
                      {account.bankName || 'Bank'} <span className="font-mono text-xs text-slate-500">{maskAccountNumber(account.accountNumber)}</span>
                    </p>
                    <p className="mt-1 text-xs text-slate-500">
                      IFSC {account.ifscCode || 'Currently unavailable'} · Platizio bank {account.cybrillaBankId || 'Currently unavailable'}
                    </p>
                    <p className="mt-1 text-xs text-slate-500">
                      Verification {account.cybrillaBankVerificationStatus || status}
                      {account.cybrillaBankVerificationConfidence ? ` · confidence ${account.cybrillaBankVerificationConfidence}` : ''}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => refreshVerification(account.id)}
                    disabled={!canRefresh || refreshingId === account.id}
                    className="inline-flex items-center justify-center gap-2 rounded-lg border border-blue-200 px-3 py-2 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-50 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <RefreshCw className={`h-3.5 w-3.5 ${refreshingId === account.id ? 'animate-spin' : ''}`} />
                    Refresh verification
                  </button>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}

export default function Investors({
  onInvest,
  userData,
}: {
  onInvest?: (investor: any) => void;
  userData?: any;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const focusInvestorId: string | undefined = (location.state as any)?.focusInvestorId;
  const focusHandledRef = React.useRef(false);
  const [investors, setInvestors] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounce(search, 300);
  const [kycFilter, setKycFilter] = useState('All');
  const [statusFilter, setStatusFilter] = useState('All');
  const [selectedInvestor, setSelectedInvestor] = useState<any | null>(null);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [kycRefreshingIds, setKycRefreshingIds] = useState<Record<string, boolean>>({});
  const [kycApplyingIds, setKycApplyingIds] = useState<Record<string, boolean>>({});
  const [deletingInvestorIds, setDeletingInvestorIds] = useState<Record<string, boolean>>({});
  const [bulkKycRefreshing, setBulkKycRefreshing] = useState(false);
  const [investorSyncLoading, setInvestorSyncLoading] = useState(false);
  const [kycRefreshNotice, setKycRefreshNotice] = useState('');
  const [investorActionNotice, setInvestorActionNotice] = useState('');
  const [investorActionError, setInvestorActionError] = useState('');
  const hasLoadedRef = React.useRef(false);
  const baseInvestorsRef = React.useRef<any[]>([]);
  const listScrollRef = React.useRef(0);
  const distributorId = userData?.id;

  const fetchInvestors = React.useCallback(async (options?: { forceSync?: boolean; syncFromCybrilla?: boolean }) => {
    if (!hasLoadedRef.current) setLoading(true);
    setError('');
    try {
      const query = debouncedSearch.trim();
      // Backend list endpoints call Finprim GET /v2/investor_profiles when Cybrilla source is enabled.
      const params = new URLSearchParams();
      if (options?.forceSync) params.set('forceSync', 'true');
      if (options?.syncFromCybrilla) params.set('syncFromCybrilla', 'true');
      let url = distributorId
        ? apiUrl(`/investors/by-distributor/${distributorId}`)
        : apiUrl('/investors');

      if (query.length === 1 && baseInvestorsRef.current.length > 0) {
        setInvestors(baseInvestorsRef.current);
        hasLoadedRef.current = true;
        setLoading(false);
        return;
      }

      const shouldUseSearchApi = query.length >= 2;
      if (shouldUseSearchApi) {
        params.set('query', query);
        params.set('limit', '50');
        if (distributorId) {
          params.set('distributorId', distributorId);
        }
        url = apiUrl(`/investors/search?${params.toString()}`);
      } else if (params.toString()) {
        url += `?${params.toString()}`;
      }

      const res = await apiFetch(url);
      const data = await res.json().catch(() => null);
      if (!res.ok) throw new Error(data?.message || `HTTP ${res.status}`);
      const nextInvestors = (Array.isArray(data) ? data : []).filter((inv: any) => inv?.isDeleted !== true);
      if (!shouldUseSearchApi) baseInvestorsRef.current = nextInvestors;
      setInvestors(nextInvestors);
    } catch (e: any) {
      console.error('Error fetching investors:', e);
      if (!hasLoadedRef.current) setError('Failed to load investors. Please try again.');
      throw e;
    } finally {
      hasLoadedRef.current = true;
      setLoading(false);
    }
  }, [debouncedSearch, distributorId]);

  useEffect(() => {
    fetchInvestors().catch(() => undefined);
  }, [fetchInvestors]);

  const syncInvestorsFromCybrilla = async () => {
    setInvestorSyncLoading(true);
    setInvestorActionNotice('');
    setInvestorActionError('');
    try {
      const params = new URLSearchParams();
      if (distributorId) {
        params.set('distributorId', distributorId);
      }
      const syncPath = params.size > 0
        ? `/investors/sync-from-cybrilla?${params.toString()}`
        : '/investors/sync-from-cybrilla';
      const response = await apiFetch(syncPath, { method: 'POST' });
      const result = await response.json().catch(() => null);
      if (!response.ok) {
        const detail = result?.message
          || result?.error
          || `Investor sync failed with HTTP ${response.status}. Restart the backend if you recently updated Java code.`;
        throw new Error(detail);
      }

      const archivedRefs = listArchivedInvestorsForDistributor(distributorId);
      const targetedRestoreResults = await Promise.all(
        archivedRefs.map(entry => restoreArchivedInvestorRef(entry, apiFetch)),
      );
      const targetedRestored = targetedRestoreResults.filter(item => item.ok);
      const targetedFailed = targetedRestoreResults.filter(item => !item.ok);

      await fetchInvestors({ forceSync: true });

      const restoredCount = Number(result?.restored ?? 0) + targetedRestored.length;
      const baseMessage = result?.message
        || `Platizio sync completed. Restored ${restoredCount}, updated ${Number(result?.updated ?? 0)}.`;

      if (targetedRestored.length > 0) {
        setInvestorActionNotice(
          `${baseMessage} Also re-linked ${targetedRestored.map(item => item.name).join(', ')} from your archive list.`,
        );
      } else if (Number(result?.restored ?? 0) === 0 && Number(result?.skippedUnlinked ?? 0) > 0) {
        setInvestorActionNotice(
          `${baseMessage} Platizio has ${Number(result?.providerCount ?? 0)} profile(s) with no local link. `
          + 'If you deleted a row from PostgreSQL directly, archive the investor in the UI first next time, '
          + 'or restore by PAN via support — recently archived investors are re-linked automatically.',
        );
      } else {
        setInvestorActionNotice(baseMessage);
      }

      if (targetedFailed.length > 0) {
        setInvestorActionError(
          targetedFailed.map(item => `${item.name}: ${item.error || 'restore failed'}`).join(' '),
        );
      }
    } catch (err) {
      console.error('Investor Cybrilla sync failed:', err);
      setInvestorActionError(
        err instanceof Error
          ? err.message
          : 'Could not sync investors from Platizio. Restart the backend (port 8081) and retry.',
      );
    } finally {
      setInvestorSyncLoading(false);
    }
  };

  const KYC_OPTIONS = ['All', 'COMPLETED', 'PENDING', 'IN_PROGRESS', 'FAILED', 'RETRY_REQUIRED', 'NOT_STARTED'];
  const STATUS_OPTIONS = ['All', 'ACTIVE', 'READY_FOR_TRANSACTIONS', 'ONBOARDING', 'DRAFT', 'PENDING', 'BLOCKED', 'ARCHIVED'];

  const filtered = useMemo(
    () => investors.filter(inv => {
      const matchSearch = matchesInvestorSearch(inv, search.trim());
      const matchKyc = kycFilter === 'All' || inv.kycStatus === kycFilter;
      const matchStatus = statusFilter === 'All' || inv.investorStatus === statusFilter;
      return matchSearch && matchKyc && matchStatus;
    }),
    [investors, search, kycFilter, statusFilter],
  );

  const resolvedTotalElements = filtered.length;
  const resolvedTotalPages = Math.max(Math.ceil(resolvedTotalElements / size) || 0, 1);

  useEffect(() => {
    if (page > resolvedTotalPages - 1) {
      setPage(Math.max(resolvedTotalPages - 1, 0));
    }
  }, [page, resolvedTotalPages]);

  const paginatedInvestors = useMemo(
    () => filtered.slice(page * size, page * size + size),
    [filtered, page, size],
  );

  const syncableVisibleInvestors = paginatedInvestors.filter(
    inv => inv.externalKycCheckId || inv.externalKycRequestId,
  );

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setPage(0);
  };

  const handleKycFilterChange = (value: string) => {
    setKycFilter(value);
    setPage(0);
  };

  const handleStatusFilterChange = (value: string) => {
    setStatusFilter(value);
    setPage(0);
  };

  const updateInvestorInState = (updatedInvestor: any) => {
    if (!updatedInvestor?.id) return;
    setInvestors(prev => prev.map(inv => inv.id === updatedInvestor.id ? { ...inv, ...updatedInvestor } : inv));
    baseInvestorsRef.current = baseInvestorsRef.current.map(inv =>
      inv.id === updatedInvestor.id ? { ...inv, ...updatedInvestor } : inv,
    );
    setSelectedInvestor(prev => prev?.id === updatedInvestor.id ? { ...prev, ...updatedInvestor } : prev);
  };

  const openInvestorDetail = (investor: any) => {
    listScrollRef.current = typeof window !== 'undefined' ? window.scrollY : 0;
    setSelectedInvestor(investor);
  };

  // When navigated here from the dashboard onboarding pipeline (or any caller
  // that passes location.state.focusInvestorId), auto-open that investor's
  // detail page. Falls back to a single-investor fetch if it isn't in the
  // currently loaded list (e.g. filtered out or on another page).
  useEffect(() => {
    if (!focusInvestorId || focusHandledRef.current || loading) return;
    let cancelled = false;

    const found = investors.find(inv => inv.id === focusInvestorId);
    if (found) {
      focusHandledRef.current = true;
      openInvestorDetail(found);
      return;
    }

    (async () => {
      try {
        const res = await apiFetch(apiUrl(`/investors/${focusInvestorId}`));
        if (!res.ok) return;
        const inv = await res.json().catch(() => null);
        if (!cancelled && inv?.id) {
          focusHandledRef.current = true;
          openInvestorDetail(inv);
        }
      } catch (e) {
        console.error('Failed to open investor from pipeline:', e);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [focusInvestorId, loading, investors]);

  const continueInvestorOnboarding = async (investor: any) => {
    if (!investor?.id) return;
    listScrollRef.current = typeof window !== 'undefined' ? window.scrollY : 0;

    let resumeInvestor = investor;
    let resumeStep = getOnboardingResumeStep(investor);

    try {
      const response = await apiFetch(`/investors/${investor.id}/onboarding/resume`);
      const data = await response.json().catch(() => null);
      if (response.ok && data?.investor?.id) {
        resumeInvestor = {
          ...data.investor,
          onboardingResumeNextStep: data.nextStep,
          onboardingDocumentsComplete: data.documentsComplete,
        };
        resumeStep = mapBackendNextStepToWizardStep(data.nextStep, data.documentsComplete)
          ?? getOnboardingResumeStep(data.investor);
      }
    } catch (error) {
      console.warn('Investor onboarding resume lookup failed; using list snapshot', error);
    }

    navigate('/distributor/investor-onboarding', {
      state: {
        investor: resumeInvestor,
        investorId: resumeInvestor.id,
        resumeStep,
        returnTo: '/distributor/investors',
        resetKey: `${resumeInvestor.id}-${Date.now()}`,
      },
    });
  };

  const openModifyKyc = (investor: any) => {
    if (!investor?.id) return;
    navigate(`/distributor/investors/${investor.id}/kyc-modify`);
  };

  const backToInvestorList = () => {
    setSelectedInvestor(null);
    if (typeof window !== 'undefined') {
      window.requestAnimationFrame(() => window.scrollTo({ top: listScrollRef.current }));
    }
  };

  const removeInvestorFromState = (investorId: string) => {
    setInvestors(prev => prev.filter(inv => inv.id !== investorId));
    baseInvestorsRef.current = baseInvestorsRef.current.filter(inv => inv.id !== investorId);
    setSelectedInvestor(prev => prev?.id === investorId ? null : prev);
  };

  const deleteInvestor = async (investor: any) => {
    if (!investor?.id) return false;
    const name = investor.fullName || 'this investor';
    const confirmed = window.confirm(
      `Archive ${name}? This removes the investor from your local Platizio list only. `
      + 'Their Platizio profile is not deleted. Use "Restore from Platizio" to bring them back into your list.',
    );
    if (!confirmed) return false;

    setDeletingInvestorIds(prev => ({ ...prev, [investor.id]: true }));
    setInvestorActionNotice('');
    setInvestorActionError('');
    try {
      const response = await apiFetch(`/investors/${investor.id}`, { method: 'DELETE' });
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `Investor delete failed with HTTP ${response.status}.`);
      }
      if (distributorId) {
        rememberArchivedInvestor({
          distributorId,
          pan: investor.pan,
          cybrillaInvestorId: investor.cybrillaInvestorId,
          fullName: investor.fullName,
        });
      }
      removeInvestorFromState(investor.id);
      setInvestorActionNotice(
        data?.message
          || `${name} archived locally. Platizio cannot delete investor profiles via API — click "Restore from Platizio" to bring them back.`,
      );
      return true;
    } catch (err) {
      console.error('Investor soft delete failed:', err);
      setInvestorActionError(err instanceof Error ? err.message : 'Investor could not be archived.');
      return false;
    } finally {
      setDeletingInvestorIds(prev => {
        const next = { ...prev };
        delete next[investor.id];
        return next;
      });
    }
  };

  const readKycActionResponse = async (response: Response, fallbackMessage: string) => {
    const data = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(data?.message || `${fallbackMessage} failed with HTTP ${response.status}.`);
      (error as any).status = response.status;
      (error as any).data = data;
      throw error;
    }
    return data;
  };

  const refreshInvestorKycWithSavedIds = async (investor: any) => {
    const response = await apiFetch(`/investors/${investor.id}/kyc-sync`, { method: 'POST' });
    return readKycActionResponse(response, 'KYC status sync');
  };

  const refreshInvestorKycStatus = async (investor: any, showNotice = true) => {
    if (!investor?.id) return null;
    setKycRefreshingIds(prev => ({ ...prev, [investor.id]: true }));
    if (showNotice) setKycRefreshNotice('');
    try {
      const data = await refreshInvestorKycWithSavedIds(investor);
      if (data?.investor) {
        updateInvestorInState(data.investor);
      }
      if (showNotice) {
        const status = data?.investor?.kycStatus || 'updated';
        setKycRefreshNotice(`${investor.fullName || 'Investor'} KYC refreshed: ${String(status).replace(/_/g, ' ')}.`);
      }
      return data;
    } finally {
      setKycRefreshingIds(prev => {
        const next = { ...prev };
        delete next[investor.id];
        return next;
      });
    }
  };

  const applyInvestorKycWorkflow = async (investor: any, showNotice = true) => {
    if (!investor?.id) return null;
    setKycApplyingIds(prev => ({ ...prev, [investor.id]: true }));
    if (showNotice) setKycRefreshNotice('');
    try {
      const response = await apiFetch(`/investors/${investor.id}/kyc/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dateOfBirth: investor.dateOfBirth || null }),
      });
      const data = await readKycActionResponse(response, 'KYC workflow');
      if (data?.investor) {
        updateInvestorInState(data.investor);
      }
      if (showNotice) {
        const status = data?.investor?.kycStatus || investor.kycStatus || 'updated';
        setKycRefreshNotice(`${investor.fullName || 'Investor'} KYC applied: ${String(status).replace(/_/g, ' ')}.`);
      }
      return data;
    } finally {
      setKycApplyingIds(prev => {
        const next = { ...prev };
        delete next[investor.id];
        return next;
      });
    }
  };

  const refreshVisibleKycStatuses = async () => {
    if (syncableVisibleInvestors.length === 0) {
      setKycRefreshNotice('No visible investors have saved Platizio KYC IDs to refresh.');
      return;
    }
    setBulkKycRefreshing(true);
    setKycRefreshNotice('');
    try {
      const results = await Promise.allSettled(
        syncableVisibleInvestors.map(inv => refreshInvestorKycStatus(inv, false)),
      );
      const refreshed = results.filter(result => result.status === 'fulfilled').length;
      const failed = results.length - refreshed;
      setKycRefreshNotice(
        failed > 0
          ? `KYC refresh completed for ${refreshed}; ${failed} need attention.`
          : `KYC refresh completed for ${refreshed} visible investor${refreshed === 1 ? '' : 's'}.`,
      );
    } finally {
      setBulkKycRefreshing(false);
    }
  };

  // ─── Investor Detail view ────────────────────────────────────────────────
  if (selectedInvestor) {
    return (
      <InvestorDetail
        investor={selectedInvestor}
        onBack={backToInvestorList}
        onInvest={onInvest}
        onInvestorUpdated={updateInvestorInState}
        onInvestorDeleted={deleteInvestor}
        onContinueOnboarding={continueInvestorOnboarding}
        onModifyKyc={openModifyKyc}
        isDeleting={Boolean(deletingInvestorIds[selectedInvestor.id])}
      />
    );
  }

  // ─── List view ────────────────────────────────────────────────────────────
  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-4 md:p-8 h-full flex flex-col">
      {/* Header */}
      <div className="flex flex-wrap justify-between items-center gap-3 mb-6">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Investor Archive</h1>
          <p className="text-slate-500 text-sm mt-1">
            {loading
              ? 'Loading…'
              : resolvedTotalElements === 0
                ? '0 investors'
                : `Showing ${page * size + 1}-${Math.min((page + 1) * size, resolvedTotalElements)} of ${resolvedTotalElements} investors`}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={syncInvestorsFromCybrilla}
            disabled={investorSyncLoading}
            className="inline-flex items-center gap-2 rounded-lg border border-violet-200 bg-violet-50 px-4 py-2 text-sm font-semibold text-violet-700 transition-colors hover:bg-violet-100 disabled:cursor-not-allowed disabled:opacity-50"
            title="Investor list already refreshes from Platizio GET /v2/investor_profiles on load. Use this to restore investors you archived in the UI."
          >
            <RefreshCw className={`h-4 w-4 ${investorSyncLoading ? 'animate-spin' : ''}`} />
            {investorSyncLoading ? 'Restoring…' : 'Restore from Platizio'}
          </button>
          <button
            onClick={refreshVisibleKycStatuses}
            disabled={bulkKycRefreshing || syncableVisibleInvestors.length === 0}
            className="inline-flex items-center gap-2 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2 text-sm font-semibold text-blue-700 transition-colors hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-50"
            title="Fetch latest KYC status from Platizio for the currently visible investors"
          >
            <RefreshCw className={`h-4 w-4 ${bulkKycRefreshing ? 'animate-spin' : ''}`} />
            Refresh visible KYC
          </button>
        </div>
      </div>

      {kycRefreshNotice && (
        <div className="mb-4 rounded-xl border border-blue-100 bg-blue-50 px-4 py-3 text-sm font-medium text-blue-700">
          {kycRefreshNotice}
        </div>
      )}
      {investorActionNotice && (
        <div className="mb-4 rounded-xl border border-green-100 bg-green-50 px-4 py-3 text-sm font-medium text-green-700">
          {investorActionNotice}
        </div>
      )}
      {investorActionError && (
        <div className="mb-4 rounded-xl border border-red-100 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">
          {investorActionError}
        </div>
      )}

      {/* Search + filters */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 mb-4">
        <div className="p-4 flex flex-wrap gap-3 items-center border-b border-slate-100 overflow-x-auto">
          <div className="relative flex-1 min-w-[180px] max-w-md">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text"
              value={search}
              onChange={e => handleSearchChange(e.target.value)}
              placeholder="Search by name, PAN, email, or mobile…"
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>

          <div className="flex items-center gap-2">
            <Filter className="w-4 h-4 text-slate-400" />
            <select
              value={kycFilter}
              onChange={e => handleKycFilterChange(e.target.value)}
              className="text-xs font-semibold bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 outline-none cursor-pointer"
            >
              {KYC_OPTIONS.map(o => <option key={o} value={o}>{o === 'All' ? 'All KYC' : (KYC_BADGE_CONFIG[o]?.label || o)}</option>)}
            </select>
            <select
              value={statusFilter}
              onChange={e => handleStatusFilterChange(e.target.value)}
              className="text-xs font-semibold bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 outline-none cursor-pointer"
            >
              {STATUS_OPTIONS.map(o => <option key={o} value={o}>{o === 'All' ? 'All Status' : o.replace(/_/g, ' ')}</option>)}
            </select>
          </div>
        </div>

        {/* Table */}
        <div className="overflow-x-auto">
          {loading ? (
            <div className="p-16 text-center text-slate-500">
              <div className="animate-spin w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
              Loading investors…
            </div>
          ) : error ? (
            <div className="p-12 text-center">
              <AlertCircle className="w-10 h-10 text-red-400 mx-auto mb-3" />
              <p className="text-sm font-semibold text-slate-700">{error}</p>
            </div>
          ) : filtered.length === 0 ? (
            <EmptyState
              icon={Users}
              title="No investors found"
              subtitle="Try adjusting your filters"
            />
          ) : (
            <table className="w-full text-left">
              <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 sticky top-0 z-10 font-semibold">
                <tr>
                  <th className="px-6 py-4">Investor</th>
                  <th className="px-6 py-4">PAN</th>
                  <th className="px-6 py-4">Status</th>
                  <th className="px-6 py-4">KYC</th>
                  <th className="px-6 py-4">Risk Profile</th>
                  <th className="px-6 py-4">City</th>
                  <th className="px-6 py-4 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {paginatedInvestors.map(inv => {
                  const kyc = KYC_BADGE_CONFIG[inv.kycStatus] || KYC_BADGE_CONFIG['NOT_STARTED'];
                  const stCls = statusConfig[inv.investorStatus] || 'bg-slate-100 text-slate-500';
                  const riskCls = riskConfig[inv.riskProfile] || 'bg-slate-50 text-slate-500';
                  const initials = (inv.fullName || 'IN').split(' ').map((n: string) => n[0]).join('').substring(0, 2).toUpperCase();
                  const isKycDone = isKycVerifiedStatus(inv.kycStatus);
                  const canInvest = isTransactionEligible(inv);
                  const investBlockedReason = canInvest ? '' : transactionEligibilityMessage(inv);
                  const canContinueOnboarding = shouldShowContinueOnboarding(inv);

                  return (
                    <tr key={inv.id} className="group hover:bg-slate-50 transition-colors">
                      <td className="px-6 py-4">
                        <button onClick={() => openInvestorDetail(inv)} className="flex items-center gap-3 text-left">
                          <div className="w-8 h-8 rounded-full bg-blue-100 text-blue-700 flex items-center justify-center text-xs font-bold flex-shrink-0">
                            {initials}
                          </div>
                          <div>
                            <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{inv.fullName}</div>
                            <div className="text-xs text-slate-400 mt-0.5">{inv.email}</div>
                          </div>
                        </button>
                      </td>
                      <td className="px-6 py-4 text-xs font-mono text-slate-600">{inv.pan || '—'}</td>
                      <td className="px-6 py-4">
                        <span className={`px-2 py-1 text-xs font-semibold rounded-md ${stCls}`}>
                          {(inv.investorStatus || 'DRAFT').replace(/_/g, ' ')}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        <span className={`inline-flex items-center px-2.5 py-1 text-xs font-semibold rounded-md ${kyc.bg} ${kyc.text}`}>
                          {kyc.label}
                        </span>
                      </td>
                      <td className="px-6 py-4">
                        <span className={`px-2 py-1 text-xs font-semibold rounded-md ${riskCls}`}>
                          {(inv.riskProfile || 'UNASSESSED').replace(/_/g, ' ')}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-sm text-slate-500">{inv.city || '—'}</td>
                      <td className="px-6 py-4 text-right">
                        <div className="flex flex-wrap items-center gap-2 justify-end">
                          {canContinueOnboarding && (
                            <button
                              onClick={() => continueInvestorOnboarding(inv)}
                              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-emerald-700 border border-emerald-200 rounded-lg hover:bg-emerald-50 transition-colors"
                              title="Resume this investor's onboarding from the next pending stage"
                            >
                              <ArrowRight className="w-3.5 h-3.5" />
                              Continue
                            </button>
                          )}
                          {!isKycDone && (
                            <button
                              onClick={() => {
                                applyInvestorKycWorkflow(inv).catch(err => {
                                  console.error('KYC apply failed:', err);
                                  setKycRefreshNotice(err instanceof Error ? err.message : 'KYC apply failed.');
                                });
                              }}
                              disabled={Boolean(kycApplyingIds[inv.id])}
                              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-amber-700 border border-amber-200 rounded-lg hover:bg-amber-50 transition-colors disabled:opacity-50"
                              title="Apply or retry KYC through our backend"
                            >
                              <RefreshCw className={`w-3.5 h-3.5 ${kycApplyingIds[inv.id] ? 'animate-spin' : ''}`} />
                              {kycApplyingIds[inv.id] ? 'Running' : 'Re-KYC'}
                            </button>
                          )}
                          {(inv.externalKycCheckId || inv.externalKycRequestId) && (
                            <button
                              onClick={() => {
                                refreshInvestorKycStatus(inv).catch(err => {
                                  console.error('KYC refresh failed:', err);
                                  setKycRefreshNotice(err instanceof Error ? err.message : 'KYC refresh failed.');
                                });
                              }}
                              disabled={Boolean(kycRefreshingIds[inv.id])}
                              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-slate-700 border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
                              title="Fetch latest KYC status from Platizio and update this investor"
                            >
                              <RefreshCw className={`w-3.5 h-3.5 ${kycRefreshingIds[inv.id] ? 'animate-spin' : ''}`} />
                              KYC
                            </button>
                          )}
                          <button
                            onClick={() => openInvestorDetail(inv)}
                            className="px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
                          >
                            View
                          </button>
                          <button
                            onClick={() => deleteInvestor(inv)}
                            disabled={Boolean(deletingInvestorIds[inv.id])}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-red-600 border border-red-200 rounded-lg hover:bg-red-50 transition-colors disabled:opacity-50"
                            title="Archive investor with backend soft delete"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                            {deletingInvestorIds[inv.id] ? 'Archiving' : 'Archive'}
                          </button>
                          {isKycDone && (
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                openModifyKyc(inv);
                              }}
                              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-indigo-700 border border-indigo-200 rounded-lg hover:bg-indigo-50 transition-colors"
                              title="Modify this investor's verified KYC record via Platizio (Aadhaar + eSign)"
                            >
                              <Pencil className="w-3.5 h-3.5" /> Modify KYC
                            </button>
                          )}
                          {canInvest && onInvest && (
                            <button
                              onClick={() => onInvest(inv)}
                              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                            >
                              <TrendingUp className="w-3.5 h-3.5" /> Invest
                            </button>
                          )}
                          {isKycDone && !canInvest && onInvest && (
                            <button
                              type="button"
                              disabled
                              title={investBlockedReason}
                              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold bg-slate-100 text-slate-400 rounded-lg cursor-not-allowed"
                            >
                              <TrendingUp className="w-3.5 h-3.5" /> Invest
                            </button>
                          )}
                          {isKycDone && (
                            <button
                              onClick={() => navigate(`/distributor/investors/${inv.id}/redeem`, { state: { investor: inv } })}
                              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-amber-700 border border-amber-200 rounded-lg hover:bg-amber-50 transition-colors"
                              title="Redeem (sell) this investor's mutual-fund holdings via Platizio"
                            >
                              <TrendingDown className="w-3.5 h-3.5" /> Redeem
                            </button>
                          )}
                        </div>
                      </td>
                      </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
        {!loading && !error && resolvedTotalElements > 0 && (
          <Pagination
            page={page}
            size={size}
            totalPages={resolvedTotalPages}
            totalElements={resolvedTotalElements}
            onPageChange={setPage}
            onSizeChange={nextSize => {
              setSize(nextSize);
              setPage(0);
            }}
          />
        )}
      </div>
    </motion.div>
  );
}

// ─── Investor Detail ──────────────────────────────────────────────────────────
function InvestorDetail({
  investor,
  onBack,
  onInvest,
  onInvestorUpdated,
  onInvestorDeleted,
  onContinueOnboarding,
  onModifyKyc,
  isDeleting,
}: {
  investor: any;
  onBack: () => void;
  onInvest?: (investor: any) => void;
  onInvestorUpdated?: (investor: any) => void;
  onInvestorDeleted?: (investor: any) => Promise<boolean>;
  onContinueOnboarding?: (investor: any) => void;
  onModifyKyc?: (investor: any) => void;
  isDeleting?: boolean;
}) {
  const [activeTab, setActiveTab] = useState(() =>
    isKycVerifiedStatus(investor?.kycStatus) ? 'overview' : 'compliance',
  );
  const [currentInvestor, setCurrentInvestor] = useState(investor);
  const [kycActionLoading, setKycActionLoading] = useState('');
  const [kycActionError, setKycActionError] = useState('');
  const [kycActionMessage, setKycActionMessage] = useState('');
  const [cybrillaApiWarnings, setCybrillaApiWarnings] = useState<CybrillaKycWarning[]>([]);
  const [identityRedirectUrl, setIdentityRedirectUrl] = useState('');
  const [identityDocumentId, setIdentityDocumentId] = useState('');
  const [identityFetchStatus, setIdentityFetchStatus] = useState('');
  const [aadhaarProofsAttached, setAadhaarProofsAttached] = useState(false);
  const [kycFlowStatus, setKycFlowStatus] = useState<KycFlowStatusView | null>(null);
  const [kycFlowLoading, setKycFlowLoading] = useState(false);
  const [kycFlowError, setKycFlowError] = useState('');
  const [kycFlowActionLoading, setKycFlowActionLoading] = useState('');
  const [esignRedirectUrl, setEsignRedirectUrl] = useState('');
  const [kycPreVerification, setKycPreVerification] = useState(() =>
    parseStoredPreVerification(investor.externalKycPayloadJson),
  );
  const [lastKycIdentityFingerprint, setLastKycIdentityFingerprint] = useState(() =>
    investor.externalKycPayloadJson
      ? computeIdentityFingerprint({
        fullName: investor.fullName,
        pan: investor.pan,
        dateOfBirth: investor.dateOfBirth,
        mobile: investor.mobileNumber,
        email: investor.email,
      })
      : '',
  );
  const kycCheckInFlightRef = React.useRef(false);
  const [kycReasonDialogOpen, setKycReasonDialogOpen] = useState(false);
  const [kycReasonDialogContent, setKycReasonDialogContent] = useState<CybrillaKycReasonDialogContent | null>(null);
  const kycReasonDialogSigRef = useRef('');

  const kycDecision = getPreVerificationDecision(kycPreVerification, {
    investor: currentInvestor as Record<string, unknown>,
  });
  const panAadhaarNotLinked = kycDecision.requiresPanAadhaarLink
    || isPanAadhaarNotLinked(kycPreVerification, currentInvestor as Record<string, unknown>);
  const aadhaarFetchComplete = isIdentityDocumentFetchComplete(identityFetchStatus) || aadhaarProofsAttached;
  const anyKycApiBusy = Boolean(kycActionLoading || kycFlowActionLoading);
  const poaPreVerificationComplete = isPoaPreVerificationComplete(kycDecision);
  const poaRunLocked = shouldLockPoaRunButton({
    anyBusy: anyKycApiBusy && kycActionLoading !== 'kyc-check',
    kycPhase: kycActionLoading === 'kyc-check' ? 'checking' : kycDecision.state,
    // `kyc_unavailable` (requiresFreshKyc) is a "start fresh KYC" signal, not a terminal pass —
    // keep pre-verification re-runnable so the investor is never permanently blocked.
    preVerificationComplete: poaPreVerificationComplete && !kycDecision.requiresFreshKyc,
  });
  const aadhaarStartLocked = shouldLockAadhaarStart({
    anyBusy: anyKycApiBusy,
    documentId: identityDocumentId,
    redirectUrl: identityRedirectUrl,
    externalDocumentId: currentInvestor.externalIdentityDocumentId,
  });
  const freshKycRequestAllowed = canCreateFreshKycRequest(
    kycPreVerification,
    currentInvestor as Record<string, unknown>,
  );
  const kycRequestCreateLocked = shouldLockKycRequestCreate({
    anyBusy: anyKycApiBusy,
    kycRequestId: currentInvestor.externalKycRequestId,
    readinessBlocksCreate: isReadinessFailureBlockingKycRequest(
      kycPreVerification,
      currentInvestor as Record<string, unknown>,
    ),
  });
  const esignStartLocked = shouldLockEsignStart({
    anyBusy: anyKycApiBusy,
    esignId: currentInvestor.externalEsignId,
    redirectUrl: esignRedirectUrl,
  });
  const aadhaarLinkLocked = anyKycApiBusy || aadhaarFetchComplete || aadhaarStartLocked;
  const esignLinkLocked = anyKycApiBusy || esignStartLocked;
  const cybrillaKycWarnings = useMemo(() => {
    if (cybrillaApiWarnings.length > 0) return cybrillaApiWarnings;
    return extractCybrillaWarningsFromPayload({
      externalResponse: kycPreVerification,
      investor: currentInvestor as Record<string, unknown>,
    });
  }, [kycPreVerification, currentInvestor, cybrillaApiWarnings]);

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

  // F-10: edit-form toggle for the Overview tab + a transient "Saved" pill
  // that auto-fades a few seconds after a successful PUT.
  const [isEditing, setIsEditing] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);
  const editScrollTopRef = React.useRef(0);
  useEffect(() => {
    setCurrentInvestor(investor);
    if (!isKycVerifiedStatus(investor?.kycStatus)) {
      setActiveTab('compliance');
    }
    const storedExternal = parseStoredPreVerification(investor.externalKycPayloadJson);
    setKycPreVerification(storedExternal);
    setCybrillaApiWarnings(extractCybrillaWarningsFromPayload({
      externalResponse: storedExternal,
      investor,
    }));
    const storedAadhaar = readAadhaarVerificationFromInvestor(investor as Record<string, unknown>);
    if (storedAadhaar?.identityDocumentId) setIdentityDocumentId(storedAadhaar.identityDocumentId);
    if (storedAadhaar?.fetchStatus) setIdentityFetchStatus(storedAadhaar.fetchStatus);
    setLastKycIdentityFingerprint(
      investor.externalKycPayloadJson
        ? computeIdentityFingerprint({
          fullName: investor.fullName,
          pan: investor.pan,
          dateOfBirth: investor.dateOfBirth,
          mobile: investor.mobileNumber,
          email: investor.email,
        })
        : '',
    );
  }, [investor]);

  useEffect(() => {
    if (!savedFlash) return;
    const t = setTimeout(() => setSavedFlash(false), 3000);
    return () => clearTimeout(t);
  }, [savedFlash]);
  // Pre-fill so the chart renders a flat baseline immediately rather than being empty.
  const ZERO_MONTHS = [
    { month: 'Jan', value: 0 }, { month: 'Feb', value: 0 },
    { month: 'Mar', value: 0 }, { month: 'Apr', value: 0 },
    { month: 'May', value: 0 }, { month: 'Jun', value: 0 },
  ];
  const [performanceData, setPerformanceData] = useState<any[]>(ZERO_MONTHS);

  useEffect(() => {
    apiFetch(`/orders/by-investor/${currentInvestor.id}`)
      .then(res => res.ok ? res.json() : [])
      .then(orders => {
        let total = 0;
        orders.forEach((o: any) => {
          if (o.orderStatus === 'COMPLETED' || o.orderStatus === 'SUCCESSFUL') {
            total += (o.amount || 0);
          }
        });

        const data = [];
        const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun'];
        for (let i = 0; i < 6; i++) {
          const factor = 1 - (5 - i) * 0.05; // mock growth curve based on actual AUM
          data.push({ month: months[i], value: Math.round(total * factor) });
        }
        setPerformanceData(data.length ? data : [
          { month: 'Jan', value: 0 }, { month: 'Feb', value: 0 },
          { month: 'Mar', value: 0 }, { month: 'Apr', value: 0 },
          { month: 'May', value: 0 }, { month: 'Jun', value: 0 },
        ]);
      }).catch(err => {
        console.error('Failed to fetch orders for performance data', err);
        setPerformanceData([
          { month: 'Jan', value: 0 }, { month: 'Feb', value: 0 },
          { month: 'Mar', value: 0 }, { month: 'Apr', value: 0 },
          { month: 'May', value: 0 }, { month: 'Jun', value: 0 },
        ]);
      });
  }, [currentInvestor.id]);

  const kyc = KYC_BADGE_CONFIG[currentInvestor.kycStatus] || KYC_BADGE_CONFIG['NOT_STARTED'];
  const stCls = statusConfig[currentInvestor.investorStatus] || 'bg-slate-100 text-slate-500';
  const isKycDone = isKycVerifiedStatus(currentInvestor.kycStatus);
  const canInvest = isTransactionEligible(currentInvestor);
  const investBlockedReason = canInvest ? '' : transactionEligibilityMessage(currentInvestor);
  const canContinueOnboarding = shouldShowContinueOnboarding(currentInvestor);
  const initials = (currentInvestor.fullName || 'IN').split(' ').map((n: string) => n[0]).join('').substring(0, 2).toUpperCase();

  const restoreEditScroll = () => {
    if (typeof window !== 'undefined') {
      window.requestAnimationFrame(() => window.scrollTo({ top: editScrollTopRef.current }));
    }
  };

  const startEditing = () => {
    editScrollTopRef.current = typeof window !== 'undefined' ? window.scrollY : 0;
    setIsEditing(true);
  };

  const stopEditing = () => {
    setIsEditing(false);
    restoreEditScroll();
  };

  const runKycAction = async (
    key: string,
    request: () => Promise<Response>,
    successMessage: string,
  ) => {
    if (kycActionLoading) return;
    if (key === 'identity-document' && aadhaarStartLocked) {
      setKycActionMessage('Aadhaar fetch is already started. Use Refresh Aadhaar after the investor completes Digilocker.');
      return;
    }
    if (key === 'kyc-request' && (kycRequestCreateLocked || !freshKycRequestAllowed)) return;
    if (key === 'esign-start' && esignStartLocked) return;
    setKycActionLoading(key);
    setKycActionError('');
    setKycActionMessage('');
    setCybrillaApiWarnings([]);
    try {
      const response = await request();
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        const warnings = data ? extractCybrillaWarningsFromPayload(data) : [];
        if (warnings.length > 0) {
          setCybrillaApiWarnings(warnings);
        }
        const errorMessage = data?.message || `KYC action failed with HTTP ${response.status}.`;
        reportKycIssue(errorMessage, { warnings, preVerification: resolvePreVerificationFromPayload(data) });
        return;
      }
      applyKycActionData(data);
      const warnings = extractCybrillaWarningsFromPayload(data);
      setCybrillaApiWarnings(warnings);
      setKycActionMessage(successMessage);
    } catch (err) {
      console.error('KYC action failed:', err);
      reportKycIssue(err instanceof Error ? err.message : 'KYC action failed.');
    } finally {
      setKycActionLoading('');
    }
  };

  const applyKycActionData = (data: any) => {
    if (data?.investor) {
      setCurrentInvestor((prev: any) => ({ ...prev, ...data.investor }));
      onInvestorUpdated?.(data.investor);
    }
    const preVerification = resolvePreVerificationFromPayload(data);
    if (preVerification) {
      setKycPreVerification(preVerification);
      const decision = getPreVerificationDecision(preVerification, {
        investor: data?.investor || currentInvestor,
        kyc: data?.kyc,
      });
      const warnings = extractCybrillaWarningsFromPayload(data);
      if (decision.message) {
        setKycActionMessage(decision.message);
      }
      presentKycReasonDialog({
        decision,
        warnings,
        preVerification,
      });
      setLastKycIdentityFingerprint(computeIdentityFingerprint({
        fullName: data?.investor?.fullName || currentInvestor.fullName,
        pan: data?.investor?.pan || currentInvestor.pan,
        dateOfBirth: data?.investor?.dateOfBirth || currentInvestor.dateOfBirth,
        mobile: data?.investor?.mobileNumber || currentInvestor.mobileNumber,
        email: data?.investor?.email || currentInvestor.email,
      }));
    }
    const fetchMeta = normalizeAadhaarVerificationResponse(data);
    if (fetchMeta?.redirectUrl) setIdentityRedirectUrl(fetchMeta.redirectUrl);
    if (fetchMeta?.identityDocumentId) setIdentityDocumentId(fetchMeta.identityDocumentId);
    if (fetchMeta?.fetchStatus) setIdentityFetchStatus(fetchMeta.fetchStatus);
    if (fetchMeta?.proofsAttachedToKycRequest) setAadhaarProofsAttached(true);
    return data?.investor;
  };

  const refreshAadhaarIdentityDocument = async () => {
    if (!currentInvestor?.id) {
      setKycActionError('Investor record is missing.');
      return;
    }
    setKycActionLoading('identity-document-refresh');
    setKycActionError('');
    try {
      const response = await apiFetch(
        `/investors/${currentInvestor.id}/identity-documents/refresh`,
        { method: 'POST' },
      );
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        if (data) setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(data));
        throw new Error(data?.message || `Aadhaar status refresh failed with HTTP ${response.status}.`);
      }
      applyKycActionData(data);
      await loadKycFlowStatus();
      const view = normalizeAadhaarVerificationResponse(data);
      if (view?.fetchComplete && view.proofsAttachedToKycRequest) {
        setKycActionMessage('Aadhaar fetched from Digilocker and attached to the KYC request.');
        setAadhaarProofsAttached(true);
      } else if (view?.fetchComplete) {
        setKycActionMessage('Aadhaar fetched successfully from Digilocker.');
      } else {
        setKycActionMessage('Digilocker fetch is still in progress.');
      }
    } catch (err) {
      reportKycIssue(err instanceof Error ? err.message : 'Unable to refresh Aadhaar verification status.');
    } finally {
      setKycActionLoading('');
    }
  };

  const loadKycFlowStatus = async () => {
    if (!currentInvestor?.id) return null;
    setKycFlowLoading(true);
    setKycFlowError('');
    try {
      const response = await apiFetch(`/investors/${currentInvestor.id}/kyc-flow/status`);
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `KYC flow status failed with HTTP ${response.status}.`);
      }
      const flow = normalizeKycFlowStatus(data);
      setKycFlowStatus(flow);
      if (flow?.investor) {
        setCurrentInvestor((prev: any) => ({ ...prev, ...flow.investor }));
        onInvestorUpdated?.(flow.investor);
        const storedAadhaar = readAadhaarVerificationFromInvestor(flow.investor);
        if (storedAadhaar?.redirectUrl) setIdentityRedirectUrl(storedAadhaar.redirectUrl);
        if (storedAadhaar?.fetchStatus) setIdentityFetchStatus(storedAadhaar.fetchStatus);
        if (storedAadhaar?.proofsAttachedToKycRequest) setAadhaarProofsAttached(true);
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
    if (!currentInvestor?.id) return;
    if (esignStartLocked) {
      setKycActionMessage('eSign is already started. Use Refresh eSign after the investor completes signing.');
      return;
    }
    setKycFlowActionLoading('START_ESIGN');
    setKycActionError('');
    try {
      const response = await apiFetch(`/investors/${currentInvestor.id}/esign/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          postbackUrl: buildKycPostbackUrl(currentInvestor.id, 'esign'),
        }),
      });
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `eSign start failed with HTTP ${response.status}.`);
      }
      const esign = normalizeEsignVerificationResponse(data);
      if (esign?.redirectUrl) setEsignRedirectUrl(esign.redirectUrl);
      if (esign?.investor) {
        setCurrentInvestor((prev: any) => ({ ...prev, ...esign.investor }));
        onInvestorUpdated?.(esign.investor);
      }
      setKycActionMessage('eSign started. Open the link for the investor to sign the KYC application.');
    } catch (err) {
      reportKycIssue(err instanceof Error ? err.message : 'Unable to start eSign.');
    } finally {
      setKycFlowActionLoading('');
    }
  };

  const refreshEsign = async () => {
    if (!currentInvestor?.id) return;
    setKycFlowActionLoading('REFRESH_ESIGN');
    setKycActionError('');
    try {
      const response = await apiFetch(`/investors/${currentInvestor.id}/esign/refresh`, { method: 'POST' });
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `eSign refresh failed with HTTP ${response.status}.`);
      }
      const esign = normalizeEsignVerificationResponse(data);
      if (esign?.redirectUrl) setEsignRedirectUrl(esign.redirectUrl);
      if (esign?.investor) {
        setCurrentInvestor((prev: any) => ({ ...prev, ...esign.investor }));
        onInvestorUpdated?.(esign.investor);
      }
      await loadKycFlowStatus();
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
    if (requestedAction === 'RUN_PRE_VERIFICATION' && poaRunLocked) return;
    if (requestedAction === 'START_AADHAAR' && aadhaarStartLocked) return;
    if (requestedAction === 'CREATE_KYC_REQUEST' && kycRequestCreateLocked) return;
    if (requestedAction === 'START_ESIGN' && esignStartLocked) return;
    setKycFlowActionLoading(requestedAction);
    try {
      switch (requestedAction) {
        case 'RUN_PRE_VERIFICATION':
          await createKycCheck();
          break;
        case 'CREATE_KYC_REQUEST':
          await createKycRequest();
          break;
        case 'START_AADHAAR':
          await createIdentityDocument();
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
      await loadKycFlowStatus();
    } finally {
      setKycFlowActionLoading('');
    }
  };

  useEffect(() => {
    if (activeTab !== 'compliance') return;
    void loadKycFlowStatus();
  }, [activeTab, currentInvestor.id]);

  const shouldCreateFreshKycRequest = (data: any, updatedInvestor: any) => {
    const preVerification = extractPreVerification(data);
    const readinessCode = String(preVerification?.readiness?.code || '').toLowerCase();
    return updatedInvestor?.kycStatus === 'NOT_STARTED'
      || readinessCode === 'kyc_unavailable'
      || readinessCode === 'unavailable';
  };

  const createKycCheck = async () => {
    if (poaRunLocked) return;
    const errors = validateInvestorIdentityForKyc({
      fullName: currentInvestor.fullName,
      pan: currentInvestor.pan,
      dob: currentInvestor.dateOfBirth,
    }, { requireContact: false });
    if (Object.keys(errors).length > 0) {
      setKycActionError(Object.values(errors)[0] || 'Investor identity details are incomplete.');
      setKycActionMessage('');
      return;
    }
    if (kycCheckInFlightRef.current) {
      console.log('kyc_api_call action=skipped reason=in_flight');
      return;
    }

    kycCheckInFlightRef.current = true;
    setKycActionLoading('kyc-check');
    setKycActionError('');
    setKycActionMessage('');
    setCybrillaApiWarnings([]);
    try {
      const currentFingerprint = computeIdentityFingerprint({
        fullName: currentInvestor.fullName,
        pan: currentInvestor.pan,
        dateOfBirth: currentInvestor.dateOfBirth,
        mobile: currentInvestor.mobileNumber,
        email: currentInvestor.email,
      });
      const forceNewCheck = shouldForceNewKycCheck(
        lastKycIdentityFingerprint,
        currentFingerprint,
        currentInvestor,
      );
      const kycEndpoint = forceNewCheck
        ? `/investors/${currentInvestor.id}/kyc/reapply`
        : `/investors/${currentInvestor.id}/kyc-checks`;

      console.log('kyc_api_call action=create', {
        timestamp: new Date().toISOString(),
        endpoint: `POST /api/v1${kycEndpoint}`,
        investorId: currentInvestor.id,
        pan: normalizePan(currentInvestor.pan),
        forceNewCheck,
      });

      const checkResponse = await apiFetch(kycEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          dateOfBirth: currentInvestor.dateOfBirth || null,
          forceNewCheck,
        }),
      });
      const checkData = await checkResponse.json().catch(() => null);
      console.log('kyc_api_call action=create_response', {
        status: checkResponse.status,
        ok: checkResponse.ok,
        forceNewCheck,
        body: checkData,
      });
      if (!checkResponse.ok) {
        if (checkData) {
          setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(checkData));
        }
        throw new Error(checkData?.message || `KYC check failed with HTTP ${checkResponse.status}.`);
      }

      let updatedInvestor = applyKycActionData(checkData);
      setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(checkData));
      let message = forceNewCheck
        ? 'Fresh POA pre-verification started with updated identity details.'
        : 'POA pre-verification submitted. Refresh if the response is still accepted.';

      if (shouldCreateFreshKycRequest(checkData, updatedInvestor || currentInvestor)) {
        console.log('kyc_api_call action=kyc_request_create', {
          endpoint: `POST /api/v1/investors/${currentInvestor.id}/kyc-requests`,
          investorId: currentInvestor.id,
        });
        const requestResponse = await apiFetch(`/investors/${currentInvestor.id}/kyc-requests`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            name: currentInvestor.fullName,
            pan: currentInvestor.pan,
            email: currentInvestor.email,
            mobile: currentInvestor.mobileNumber,
            dateOfBirth: currentInvestor.dateOfBirth || null,
            fields: {},
          }),
        });
        const requestData = await requestResponse.json().catch(() => null);
        if (!requestResponse.ok) {
          if (requestData) {
            setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(requestData));
          }
          throw new Error(requestData?.message || `KYC request failed with HTTP ${requestResponse.status}.`);
        }
        updatedInvestor = applyKycActionData(requestData) || updatedInvestor;
        setCybrillaApiWarnings(extractCybrillaWarningsFromPayload(requestData));
        message = 'POA says fresh KYC is required, so a Platizio KYC request was created and saved.';
      }

      if (updatedInvestor?.kycStatus === 'COMPLETED') {
        message = 'KYC verified by Platizio and saved in your database.';
      }
      setKycActionMessage(message);
      await loadKycFlowStatus();
    } catch (err) {
      console.error('kyc_api_call action=create_error', err);
      reportKycIssue(err instanceof Error ? err.message : 'KYC action failed.');
    } finally {
      kycCheckInFlightRef.current = false;
      setKycActionLoading('');
    }
  };

  const applyKycWorkflow = async () => {
    const errors = validateInvestorIdentityForKyc({
      fullName: currentInvestor.fullName,
      pan: currentInvestor.pan,
      dob: currentInvestor.dateOfBirth,
    }, { requireContact: false });
    if (Object.keys(errors).length > 0) {
      setKycActionError(Object.values(errors)[0] || 'Investor identity details are incomplete.');
      setKycActionMessage('');
      return;
    }

    return runKycAction(
      'kyc-apply',
      () => apiFetch(`/investors/${currentInvestor.id}/kyc/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dateOfBirth: currentInvestor.dateOfBirth || null }),
      }),
      'KYC workflow applied through backend and saved locally.',
    );
  };

  const syncSavedKycStatus = () => runKycAction(
    'kyc-sync',
    () => apiFetch(`/investors/${currentInvestor.id}/kyc-sync`, { method: 'POST' }),
    'KYC status synced through backend and saved locally.',
  );

  const fetchKycCheck = () => {
    const checkId = currentInvestor.externalKycCheckId || kycPreVerification?.id;
    if (!checkId) {
      setKycActionError('Run POA pre-verification before fetching it.');
      return;
    }
    runKycAction(
      'kyc-check-fetch',
      () => apiFetch(`/investors/${currentInvestor.id}/kyc-checks/${checkId}`),
      'POA pre-verification refreshed.',
    );
  };

  const refetchKycCheck = () => {
    const checkId = currentInvestor.externalKycCheckId || kycPreVerification?.id;
    if (!checkId) {
      setKycActionError('Run POA pre-verification before retrying it.');
      return;
    }
    runKycAction(
      'kyc-check-refetch',
      () => apiFetch(`/investors/${currentInvestor.id}/kyc-checks/${checkId}/refetch`, {
        method: 'PUT',
      }),
      'POA pre-verification refetched.',
    );
  };

  const createKycRequest = () => runKycAction(
    'kyc-request',
    () => apiFetch(`/investors/${currentInvestor.id}/kyc-requests`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fields: {} }),
    }),
    'KYC request created.',
  );

  const fetchKycRequest = () => {
    const requestId = currentInvestor.externalKycRequestId;
    if (!requestId) {
      setKycActionError('Create a KYC request before fetching it.');
      return;
    }
    runKycAction(
      'kyc-request-fetch',
      () => apiFetch(`/investors/${currentInvestor.id}/kyc-requests/${requestId}`),
      'KYC request refreshed.',
    );
  };

  const simulateKycRequest = () => {
    const requestId = currentInvestor.externalKycRequestId;
    if (!requestId) {
      setKycActionError('Create a KYC request before simulating it.');
      return;
    }
    runKycAction(
      'kyc-request-simulate',
      () => apiFetch(`/investors/${currentInvestor.id}/kyc-requests/${requestId}/simulate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'successful' }),
      }),
      'Sandbox KYC request simulation completed.',
    );
  };

  const createIdentityDocument = () => runKycAction(
    'identity-document',
    () => apiFetch(`/investors/${currentInvestor.id}/identity-documents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        type: 'aadhaar',
        postbackUrl: buildKycPostbackUrl(currentInvestor.id, 'aadhaar'),
      }),
    }),
    'Aadhaar Digilocker flow created.',
  );

  const tabs = [
    { id: 'overview', label: 'Overview' },
    { id: 'compliance', label: 'Compliance' },
  ];

  return (
    <motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} className="p-4 md:p-8 max-w-5xl mx-auto">
      <button onClick={onBack} className="flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-slate-800 mb-6 transition-colors">
        <ChevronLeft className="w-4 h-4" /> Back to Archive
      </button>

      {/* Header */}
      <div className="flex flex-wrap justify-between items-start gap-4 mb-8">
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 rounded-full bg-blue-100 text-blue-700 flex items-center justify-center text-xl font-bold flex-shrink-0">
            {initials}
          </div>
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-slate-800">{currentInvestor.fullName}</h1>
            <div className="flex flex-wrap items-center gap-2 mt-2">
              <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${stCls}`}>
                {(currentInvestor.investorStatus || 'DRAFT').replace(/_/g, ' ')}
              </span>
              <span className={`inline-flex items-center px-2.5 py-1 text-xs font-semibold rounded-md ${kyc.bg} ${kyc.text}`}>
                {kyc.label}
              </span>
              {currentInvestor.pan && (
                <span className="text-slate-400 font-mono text-xs">PAN: {currentInvestor.pan}</span>
              )}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {/* F-10: inline "Saved" pill — appears for 3s after a successful PUT
              in lieu of a toast library (none is installed). */}
          {savedFlash && (
            <span className="flex items-center gap-1.5 text-xs font-semibold text-green-700 bg-green-50 border border-green-200 px-2.5 py-1 rounded-full">
              <CheckCircle2 className="w-3.5 h-3.5" /> Saved
            </span>
          )}
          {canContinueOnboarding && onContinueOnboarding && (
            <button
              type="button"
              onClick={() => onContinueOnboarding(currentInvestor)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-emerald-600 text-white rounded-lg shadow-sm hover:bg-emerald-700 transition-colors"
            >
              <ArrowRight className="w-4 h-4" /> Continue Onboarding
            </button>
          )}
          {isKycDone && onModifyKyc && (
            <button
              type="button"
              onClick={() => onModifyKyc(currentInvestor)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-indigo-600 text-white rounded-lg shadow-sm hover:bg-indigo-700 transition-colors"
            >
              <Pencil className="w-4 h-4" /> Modify KYC
            </button>
          )}
          {!isEditing && (
            <button
              onClick={startEditing}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors"
            >
              <Pencil className="w-4 h-4" /> Edit
            </button>
          )}
          {onInvestorDeleted && (
            <button
              onClick={() => onInvestorDeleted(currentInvestor)}
              disabled={isDeleting}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-red-200 text-red-600 rounded-lg shadow-sm hover:bg-red-50 transition-colors disabled:opacity-50"
            >
              <Trash2 className="w-4 h-4" /> {isDeleting ? 'Archiving...' : 'Archive'}
            </button>
          )}
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Download className="w-4 h-4" /> Dossier
          </button>
          {canInvest && onInvest && (
            <button
              onClick={() => onInvest(currentInvestor)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-blue-600 text-white rounded-lg shadow-sm hover:bg-blue-700 transition-colors"
            >
              <TrendingUp className="w-4 h-4" /> Invest Now
            </button>
          )}
          {isKycDone && !canInvest && onInvest && (
            <button
              type="button"
              disabled
              title={investBlockedReason}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-slate-100 text-slate-400 rounded-lg cursor-not-allowed"
            >
              <TrendingUp className="w-4 h-4" /> Invest Now
            </button>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-6 border-b border-slate-200 mb-8">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`pb-3 text-sm font-medium transition-colors relative ${activeTab === tab.id ? 'text-[#0B1B3E]' : 'text-slate-500 hover:text-slate-800'}`}
          >
            {tab.label}
            {activeTab === tab.id && (
              <motion.div layoutId="activeInvTab" className="absolute bottom-0 left-0 right-0 h-0.5 bg-[#0B1B3E]" />
            )}
          </button>
        ))}
      </div>

      <AnimatePresence mode="wait">
        {activeTab === 'overview' && (
          <motion.div key="overview" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }} className="space-y-6">
            {/* F-10: when editing, the Overview tab content swaps to the
                edit form. The performance chart is intentionally hidden in
                edit mode to keep the focused task front-and-centre. */}
            {isEditing ? (
              <InvestorEditForm
                investor={currentInvestor}
                onSaved={updated => {
                  const previousFingerprint = computeIdentityFingerprint({
                    fullName: currentInvestor.fullName,
                    pan: currentInvestor.pan,
                    dateOfBirth: currentInvestor.dateOfBirth,
                    mobile: currentInvestor.mobileNumber,
                    email: currentInvestor.email,
                  });
                  const nextFingerprint = computeIdentityFingerprint({
                    fullName: updated.fullName,
                    pan: updated.pan,
                    dateOfBirth: updated.dateOfBirth,
                    mobile: updated.mobileNumber,
                    email: updated.email,
                  });
                  setCurrentInvestor((prev: any) => ({ ...prev, ...updated }));
                  onInvestorUpdated?.(updated);
                  if (previousFingerprint !== nextFingerprint) {
                    setKycPreVerification(null);
                    setKycActionError('');
                    setKycActionMessage('Identity updated. Run pre-check again with the new details.');
                  }
                  setIsEditing(false);
                  setSavedFlash(true);
                  restoreEditScroll();
                }}
                onCancel={stopEditing}
              />
            ) : (<>
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {/* Info cards */}
                {[
                  { label: 'Email', value: currentInvestor.email },
                  { label: 'Mobile', value: currentInvestor.mobileNumber },
                  { label: 'Date of Birth', value: formatDate(currentInvestor.dateOfBirth) },
                  { label: 'Anniversary', value: formatDate(currentInvestor.anniversaryDate) },
                  { label: 'Goal Maturity', value: formatDate(currentInvestor.goalMaturityDate) },
                  { label: 'City', value: currentInvestor.city || '—' },
                  { label: 'State', value: currentInvestor.state || '—' },
                  { label: 'Risk Profile', value: (currentInvestor.riskProfile || 'UNASSESSED').replace(/_/g, ' ') },
                ].map(({ label, value }) => (
                  <div key={label} className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm">
                    <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{label}</p>
                    <p className="text-sm font-semibold text-slate-800">{value}</p>
                  </div>
                ))}
              </div>

              {/* Performance chart placeholder */}
              <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
                <h3 className="font-semibold text-slate-800 flex items-center gap-2 mb-6">
                  <AreaChartIcon className="w-5 h-5 text-blue-500" /> Portfolio Performance (Demo)
                </h3>
                <div className="h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <RechartsArea data={performanceData} margin={{ top: 10, right: 0, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="colorVal" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3} />
                          <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                      <XAxis dataKey="month" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                      {/* domain + padding keep the line visible when all values are 0 */}
                      <YAxis
                        axisLine={false} tickLine={false}
                        tick={{ fontSize: 12, fill: '#94a3b8' }}
                        domain={[0, (max: number) => Math.max(max, 1)]}
                        padding={{ top: 16, bottom: 0 }}
                      />
                      <Tooltip contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }} />
                      <Area
                        type="monotone"
                        dataKey="value"
                        stroke="#3b82f6"
                        strokeWidth={3}
                        fillOpacity={1}
                        fill="url(#colorVal)"
                        connectNulls
                      />
                    </RechartsArea>
                  </ResponsiveContainer>
                </div>
              </div>
            </>)}
          </motion.div>
        )}

        {activeTab === 'compliance' && (
          <motion.div key="compliance" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="space-y-4">
            {[
              { label: 'KYC Status', value: (KYC_BADGE_CONFIG[currentInvestor.kycStatus]?.label || currentInvestor.kycStatus || 'Not Started') },
              { label: 'Bank Verification', value: (currentInvestor.bankVerificationStatus || 'NOT_CAPTURED').replace(/_/g, ' ') },
              { label: 'Investor Status', value: (currentInvestor.investorStatus || 'DRAFT').replace(/_/g, ' ') },
              { label: 'Risk Profile', value: (currentInvestor.riskProfile || 'UNASSESSED').replace(/_/g, ' ') },
              { label: 'External KYC Status', value: currentInvestor.externalKycStatus || 'Not synced' },
              { label: 'KYC Check ID', value: currentInvestor.externalKycCheckId || 'None' },
              { label: 'KYC Request ID', value: currentInvestor.externalKycRequestId || 'None' },
              { label: 'Onboarding Notes', value: currentInvestor.onboardingNotes || 'None' },
            ].map(({ label, value }) => (
              <div key={label} className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm flex justify-between items-center">
                <p className="text-sm text-slate-500 font-medium">{label}</p>
                <p className="text-sm font-semibold text-slate-800">{value}</p>
              </div>
            ))}

            <BankAccountsPanel
              investorId={currentInvestor.id}
              defaultAccountHolderName={currentInvestor.fullName}
              onBankUpdated={patch => {
                const nextInvestor = { ...currentInvestor, ...patch };
                setCurrentInvestor((prev: any) => ({ ...prev, ...patch }));
                onInvestorUpdated?.(nextInvestor);
              }}
            />

            <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
              <div className="mb-4 rounded-xl border border-indigo-200 bg-indigo-50 px-4 py-3">
                <p className="text-xs font-semibold text-indigo-900">Platizio KYC flow (fresh onboarding)</p>
                <p className="mt-1 text-xs leading-5 text-indigo-800">
                  Use the checklist below for pre-verification → Aadhaar Digilocker → eSign.
                  For investors still in onboarding, prefer <span className="font-semibold">Continue Onboarding</span> (Step 3).
                  <span className="font-semibold"> Modify KYC</span> is only for already-verified records.
                </p>
              </div>

              <KycFlowPanel
                flow={kycFlowStatus}
                loading={kycFlowLoading}
                actionLoading={kycFlowActionLoading || kycActionLoading}
                aadhaarRedirectUrl={identityRedirectUrl}
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

              <div className="mt-6 border-t border-slate-100 pt-4">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div>
                  <p className="text-sm font-semibold text-slate-800 flex items-center gap-2">
                    <ShieldCheck className="h-4 w-4 text-blue-600" /> POA Pre-verification
                  </p>
                  <p className="mt-0.5 text-xs text-slate-500">
                    Validate PAN, name, date of birth, and readiness before accepting investments.
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <button
                    onClick={applyKycWorkflow}
                    disabled={anyKycApiBusy}
                    className="inline-flex items-center justify-center gap-2 rounded-lg bg-[#0B1B3E] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {kycActionLoading === 'kyc-apply' ? <RefreshCw className="h-3.5 w-3.5 animate-spin" /> : <ShieldCheck className="h-3.5 w-3.5" />}
                    Apply KYC
                  </button>
                  <button
                    onClick={createKycCheck}
                    disabled={poaRunLocked}
                    className="inline-flex items-center justify-center gap-2 rounded-lg border border-blue-200 px-4 py-2 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-50 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {kycActionLoading === 'kyc-check' ? <RefreshCw className="h-3.5 w-3.5 animate-spin" /> : <ShieldCheck className="h-3.5 w-3.5" />}
                    Run pre-check
                  </button>
                </div>
              </div>
              <div className={`mt-4 rounded-xl border p-3 text-xs font-medium ${kycDecision.canProceed ? 'border-green-100 bg-green-50 text-green-700' : 'border-slate-200 bg-slate-50 text-slate-600'}`}>
                <p className="font-bold">{kycDecision.title}</p>
                <p className="mt-1 leading-5">{kycDecision.message}</p>
                <button
                  type="button"
                  onClick={() => presentKycReasonDialog({
                    decision: kycDecision,
                    warnings: cybrillaKycWarnings,
                    preVerification: kycPreVerification,
                    flowMessage: kycFlowStatus?.message,
                  }, { force: true })}
                  className="mt-2 font-semibold text-indigo-700 underline-offset-2 hover:underline"
                >
                  View all Platizio details
                </button>
              </div>

              {cybrillaKycWarnings.length > 0 && (
                <CybrillaKycWarnings
                  warnings={cybrillaKycWarnings}
                  title="Warnings from Platizio"
                  className="mt-4"
                />
              )}

              {panAadhaarNotLinked && (
                <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4 text-xs text-amber-800">
                  <p className="font-semibold text-amber-900">PAN–Aadhaar link required</p>
                  <p className="mt-1 leading-5">
                    Platizio returned <span className="font-mono">aadhaar_not_linked</span>.
                    Link PAN with Aadhaar on the Income Tax portal, then re-run pre-verification.
                  </p>
                </div>
              )}

              {getPreVerificationRows(kycPreVerification).length > 0 && (
                <div className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-2">
                  {getPreVerificationRows(kycPreVerification).map(row => (
                    <div key={row.field} className="rounded-xl border border-slate-100 bg-slate-50 p-3">
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

              <div className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
                <button
                  onClick={syncSavedKycStatus}
                  disabled={Boolean(kycActionLoading) || !(currentInvestor.externalKycCheckId || currentInvestor.externalKycRequestId)}
                  className="rounded-lg border border-blue-200 px-3 py-2 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-50 disabled:opacity-50"
                >
                  Refresh saved KYC
                </button>
                <button
                  onClick={fetchKycCheck}
                  disabled={Boolean(kycActionLoading)}
                  className="rounded-lg border border-slate-200 px-3 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:opacity-50"
                >
                  Fetch pre-verification
                </button>
                <button
                  onClick={refetchKycCheck}
                  disabled={Boolean(kycActionLoading)}
                  className="rounded-lg border border-slate-200 px-3 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:opacity-50"
                >
                  Retry pre-verification
                </button>
                <button
                  onClick={createKycRequest}
                  disabled={kycRequestCreateLocked || !freshKycRequestAllowed}
                  title={
                    !freshKycRequestAllowed
                      ? 'Run pre-verification and resolve investor readiness (kyc_unavailable) before creating a KYC request.'
                      : undefined
                  }
                  className="rounded-lg border border-blue-200 px-3 py-2 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Create KYC request
                </button>
                <button
                  onClick={fetchKycRequest}
                  disabled={Boolean(kycActionLoading)}
                  className="rounded-lg border border-slate-200 px-3 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:opacity-50"
                >
                  Fetch request
                </button>
                {isCybrillaSandboxMode() && (
                  <button
                    onClick={simulateKycRequest}
                    disabled={Boolean(kycActionLoading)}
                    className="rounded-lg border border-amber-200 px-3 py-2 text-xs font-semibold text-amber-700 transition-colors hover:bg-amber-50 disabled:opacity-50"
                    title="Sandbox only — calls Platizio /v2/kyc_requests/:id/simulate"
                  >
                    Simulate success
                  </button>
                )}
                <button
                  onClick={createIdentityDocument}
                  disabled={aadhaarStartLocked || !currentInvestor.externalKycRequestId}
                  className="rounded-lg border border-emerald-200 px-3 py-2 text-xs font-semibold text-emerald-700 transition-colors hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Aadhaar document
                </button>
                {identityDocumentId && (
                  <button
                    onClick={refreshAadhaarIdentityDocument}
                    disabled={Boolean(kycActionLoading)}
                    className="rounded-lg border border-indigo-200 px-3 py-2 text-xs font-semibold text-indigo-700 transition-colors hover:bg-indigo-50 disabled:opacity-50"
                  >
                    Refresh Aadhaar
                  </button>
                )}
                <button
                  onClick={startEsign}
                  disabled={esignStartLocked || !currentInvestor.externalKycRequestId}
                  className="rounded-lg border border-violet-200 px-3 py-2 text-xs font-semibold text-violet-700 transition-colors hover:bg-violet-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Start eSign
                </button>
                {currentInvestor.externalEsignId && (
                  <button
                    onClick={refreshEsign}
                    disabled={Boolean(kycActionLoading || kycFlowActionLoading)}
                    className="rounded-lg border border-violet-200 px-3 py-2 text-xs font-semibold text-violet-700 transition-colors hover:bg-violet-50 disabled:opacity-50"
                  >
                    Refresh eSign
                  </button>
                )}
              </div>

              {kycActionError && (
                <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-red-600">
                  <AlertCircle className="h-3.5 w-3.5" /> {kycActionError}
                </p>
              )}
              {kycActionMessage && (
                <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-green-600">
                  <CheckCircle2 className="h-3.5 w-3.5" /> {kycActionMessage}
                </p>
              )}
              {identityRedirectUrl && (
                <KycProviderLink
                  href={identityRedirectUrl}
                  label="Open Digilocker for Aadhaar fetch"
                  disabled={aadhaarLinkLocked}
                  className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-blue-700 hover:text-blue-800"
                  disabledReason="Digilocker link is disabled while Aadhaar fetch is starting or already in progress."
                />
              )}
              {identityFetchStatus && (
                <p className={`mt-2 text-xs font-semibold uppercase ${aadhaarFetchComplete ? 'text-green-700' : 'text-indigo-700'}`}>
                  Aadhaar fetch status: {identityFetchStatus.replace(/_/g, ' ')}
                  {aadhaarProofsAttached ? ' · attached to KYC request' : ''}
                </p>
              )}
              </div>
            </div>

            <KycDocumentUpload
              investorId={currentInvestor.id}
              onUploaded={updatedInvestor => {
                if (updatedInvestor && typeof updatedInvestor === 'object') {
                  setCurrentInvestor((prev: any) => ({ ...prev, ...updatedInvestor }));
                }
              }}
            />
          </motion.div>
        )}
      </AnimatePresence>

      <CybrillaKycReasonDialog
        open={kycReasonDialogOpen}
        content={kycReasonDialogContent}
        onClose={() => setKycReasonDialogOpen(false)}
      />
    </motion.div>
  );
}
