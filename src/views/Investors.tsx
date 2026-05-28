import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Search, Filter, ChevronLeft, Download, ShieldCheck, Users,
  TrendingUp, AreaChart as AreaChartIcon, Activity,
  CheckCircle2, Clock, XCircle, AlertCircle, Upload, RefreshCw, ExternalLink, Pencil,
} from 'lucide-react';
import {
  AreaChart as RechartsArea, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { apiClient, apiFetch, apiUrl } from '../config/api';
import Pagination from '../components/Pagination';
import { getPageContent, getPageMeta } from '../utils/pagination';
import { formatDate } from '../utils/formatDate';
import { useDebounce } from '../hooks/useDebounce';
import EmptyState from '../components/EmptyState';
import InvestorEditForm from '../components/InvestorEditForm';
import {
  extractPreVerification,
  getPreVerificationDecision,
  getPreVerificationRows,
  parseStoredPreVerification,
  preVerificationStatusClasses,
  validateInvestorIdentityForKyc,
} from '../utils/kycPreVerification';

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
}: {
  investorId?: string;
  onBankUpdated?: (patch: any) => void;
}) {
  const [accounts, setAccounts] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshingId, setRefreshingId] = useState('');
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
          <p className="mt-0.5 text-xs text-slate-500">FP bank accounts and verification status.</p>
        </div>
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

      {error && (
        <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-red-600">
          <AlertCircle className="h-3.5 w-3.5" /> {error}
        </p>
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
                      IFSC {account.ifscCode || 'Currently unavailable'} · FP bank {account.cybrillaBankId || 'Currently unavailable'}
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
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const hasLoadedRef = React.useRef(false);
  const baseInvestorsRef = React.useRef<any[]>([]);
  const distributorId = userData?.id;

  useEffect(() => {
    let cancelled = false;

    const fetchInvestors = async () => {
      if (!hasLoadedRef.current) setLoading(true);
      setError('');
      try {
        const query = debouncedSearch.trim();
        const params = new URLSearchParams();
        let url = distributorId
          ? apiUrl(`/investors/by-distributor/${distributorId}`)
          : apiUrl('/investors');

        if (query.length === 1 && baseInvestorsRef.current.length > 0) {
          console.log('[Investors] 1-char local search source:', {
            query,
            count: baseInvestorsRef.current.length,
            data: baseInvestorsRef.current,
          });
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
        }

        const res = await apiFetch(url);
        const data = await res.json().catch(() => null);
        console.log('[Investors] API response:', {
          url,
          status: res.status,
          ok: res.ok,
          query,
          data,
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const nextInvestors = Array.isArray(data) ? data : [];
        if (!shouldUseSearchApi) baseInvestorsRef.current = nextInvestors;
        if (!cancelled) setInvestors(nextInvestors);
      } catch (e: any) {
        console.error('Error fetching investors:', e);
        if (!cancelled && !hasLoadedRef.current) setError('Failed to load investors. Please try again.');
      } finally {
        if (!cancelled) {
          hasLoadedRef.current = true;
          setLoading(false);
        }
      }
    };

    fetchInvestors();
    return () => {
      cancelled = true;
    };
  }, [debouncedSearch, distributorId]);

  const KYC_OPTIONS = ['All', 'COMPLETED', 'PENDING', 'IN_PROGRESS', 'FAILED', 'RETRY_REQUIRED', 'NOT_STARTED'];
  const STATUS_OPTIONS = ['All', 'ACTIVE', 'READY_FOR_TRANSACTIONS', 'ONBOARDING', 'DRAFT', 'BLOCKED', 'ARCHIVED'];

  const filtered = investors.filter(inv => {
    const matchSearch = matchesInvestorSearch(inv, search.trim());
    const matchKyc = kycFilter === 'All' || inv.kycStatus === kycFilter;
    const matchStatus = statusFilter === 'All' || inv.investorStatus === statusFilter;
    return matchSearch && matchKyc && matchStatus;
  });

  // ─── Investor Detail view ────────────────────────────────────────────────
  if (selectedInvestor) {
    return (
      <InvestorDetail
        investor={selectedInvestor}
        onBack={() => setSelectedInvestor(null)}
        onInvest={onInvest}
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
            {loading ? 'Loading…' : `${filtered.length} of ${investors.length} investors`}
          </p>
        </div>
      </div>

      {/* Search + filters */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 mb-4">
        <div className="p-4 flex flex-wrap gap-3 items-center border-b border-slate-100 overflow-x-auto">
          <div className="relative flex-1 min-w-[180px] max-w-md">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search by name, PAN, email, or mobile…"
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>

          <div className="flex items-center gap-2">
            <Filter className="w-4 h-4 text-slate-400" />
            <select
              value={kycFilter}
              onChange={e => setKycFilter(e.target.value)}
              className="text-xs font-semibold bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 outline-none cursor-pointer"
            >
              {KYC_OPTIONS.map(o => <option key={o} value={o}>{o === 'All' ? 'All KYC' : (KYC_BADGE_CONFIG[o]?.label || o)}</option>)}
            </select>
            <select
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value)}
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
                {filtered.map(inv => {
                  const kyc = KYC_BADGE_CONFIG[inv.kycStatus] || KYC_BADGE_CONFIG['NOT_STARTED'];
                  const stCls = statusConfig[inv.investorStatus] || 'bg-slate-100 text-slate-500';
                  const riskCls = riskConfig[inv.riskProfile] || 'bg-slate-50 text-slate-500';
                  const initials = (inv.fullName || 'IN').split(' ').map((n: string) => n[0]).join('').substring(0, 2).toUpperCase();
                  const isKycDone = inv.kycStatus === 'COMPLETED' || inv.kycStatus === 'VERIFIED';

                  return (
                    <tr key={inv.id} className="group hover:bg-slate-50 transition-colors">
                      <td className="px-6 py-4">
                        <button onClick={() => setSelectedInvestor(inv)} className="flex items-center gap-3 text-left">
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
                        <div className="flex items-center gap-2 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                          <button
                            onClick={() => setSelectedInvestor(inv)}
                            className="px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
                          >
                            View
                          </button>
                          {isKycDone && onInvest && (
                            <button
                              onClick={() => onInvest(inv)}
                              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                            >
                              <TrendingUp className="w-3.5 h-3.5" /> Invest
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
      </div>
    </motion.div>
  );
}

// ─── Investor Detail ──────────────────────────────────────────────────────────
function InvestorDetail({
  investor,
  onBack,
  onInvest,
}: {
  investor: any;
  onBack: () => void;
  onInvest?: (investor: any) => void;
}) {
  const [activeTab, setActiveTab] = useState('overview');
  const [currentInvestor, setCurrentInvestor] = useState(investor);
  const [kycActionLoading, setKycActionLoading] = useState('');
  const [kycActionError, setKycActionError] = useState('');
  const [kycActionMessage, setKycActionMessage] = useState('');
  const [identityRedirectUrl, setIdentityRedirectUrl] = useState('');
  const [kycPreVerification, setKycPreVerification] = useState(() =>
    parseStoredPreVerification(investor.externalKycPayloadJson),
  );
  const kycDecision = getPreVerificationDecision(kycPreVerification);

  // F-10: edit-form toggle for the Overview tab + a transient "Saved" pill
  // that auto-fades a few seconds after a successful PUT.
  const [isEditing, setIsEditing] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);
  useEffect(() => {
    setCurrentInvestor(investor);
    setKycPreVerification(parseStoredPreVerification(investor.externalKycPayloadJson));
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
  const isKycDone = currentInvestor.kycStatus === 'COMPLETED' || currentInvestor.kycStatus === 'VERIFIED';
  const initials = (currentInvestor.fullName || 'IN').split(' ').map((n: string) => n[0]).join('').substring(0, 2).toUpperCase();

  const runKycAction = async (
    key: string,
    request: () => Promise<Response>,
    successMessage: string,
  ) => {
    setKycActionLoading(key);
    setKycActionError('');
    setKycActionMessage('');
    try {
      const response = await request();
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(data?.message || `KYC action failed with HTTP ${response.status}.`);
      }
      if (data?.investor) {
        setCurrentInvestor((prev: any) => ({ ...prev, ...data.investor }));
      }
      const preVerification = extractPreVerification(data);
      if (preVerification) {
        setKycPreVerification(preVerification);
      }
      const redirectUrl = data?.externalResponse?.fetch?.redirect_url;
      if (redirectUrl) setIdentityRedirectUrl(redirectUrl);
      setKycActionMessage(successMessage);
    } catch (err) {
      console.error('KYC action failed:', err);
      setKycActionError(err instanceof Error ? err.message : 'KYC action failed.');
    } finally {
      setKycActionLoading('');
    }
  };

  const createKycCheck = () => {
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

    runKycAction(
      'kyc-check',
      () => apiFetch(`/investors/${currentInvestor.id}/kyc-checks`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dateOfBirth: currentInvestor.dateOfBirth || null }),
      }),
      'POA pre-verification submitted. Refresh if the response is still accepted.',
    );
  };

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
        postbackUrl: `${window.location.origin}/distributor/investors`,
      }),
    }),
    'Aadhaar identity document flow created.',
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
          {!isEditing && (
            <button
              onClick={() => setIsEditing(true)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors"
            >
              <Pencil className="w-4 h-4" /> Edit
            </button>
          )}
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Download className="w-4 h-4" /> Dossier
          </button>
          {isKycDone && onInvest && (
            <button
              onClick={() => onInvest(currentInvestor)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-blue-600 text-white rounded-lg shadow-sm hover:bg-blue-700 transition-colors"
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
                  setCurrentInvestor((prev: any) => ({ ...prev, ...updated }));
                  setIsEditing(false);
                  setSavedFlash(true);
                }}
                onCancel={() => setIsEditing(false)}
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
              onBankUpdated={patch => {
                setCurrentInvestor((prev: any) => ({ ...prev, ...patch }));
              }}
            />

            <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div>
                  <p className="text-sm font-semibold text-slate-800 flex items-center gap-2">
                    <ShieldCheck className="h-4 w-4 text-blue-600" /> POA Pre-verification
                  </p>
                  <p className="mt-0.5 text-xs text-slate-500">
                    Validate PAN, name, date of birth, and readiness before accepting investments.
                  </p>
                </div>
                <button
                  onClick={createKycCheck}
                  disabled={Boolean(kycActionLoading)}
                  className="inline-flex items-center justify-center gap-2 rounded-lg bg-[#0B1B3E] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {kycActionLoading === 'kyc-check' ? <RefreshCw className="h-3.5 w-3.5 animate-spin" /> : <ShieldCheck className="h-3.5 w-3.5" />}
                  Run check
                </button>
              </div>
              <div className={`mt-4 rounded-xl border p-3 text-xs font-medium ${kycDecision.canProceed ? 'border-green-100 bg-green-50 text-green-700' : 'border-slate-200 bg-slate-50 text-slate-600'}`}>
                <p className="font-bold">{kycDecision.title}</p>
                <p className="mt-1 leading-5">{kycDecision.message}</p>
              </div>

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
                  disabled={Boolean(kycActionLoading)}
                  className="rounded-lg border border-blue-200 px-3 py-2 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-50 disabled:opacity-50"
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
                <button
                  onClick={simulateKycRequest}
                  disabled={Boolean(kycActionLoading)}
                  className="rounded-lg border border-amber-200 px-3 py-2 text-xs font-semibold text-amber-700 transition-colors hover:bg-amber-50 disabled:opacity-50"
                >
                  Simulate success
                </button>
                <button
                  onClick={createIdentityDocument}
                  disabled={Boolean(kycActionLoading)}
                  className="rounded-lg border border-emerald-200 px-3 py-2 text-xs font-semibold text-emerald-700 transition-colors hover:bg-emerald-50 disabled:opacity-50"
                >
                  Aadhaar document
                </button>
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
                <a
                  href={identityRedirectUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-blue-700 hover:text-blue-800"
                >
                  Open Aadhaar fetch link <ExternalLink className="h-3.5 w-3.5" />
                </a>
              )}
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
    </motion.div>
  );
}
