import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Search, Filter, Upload, UserPlus, X, CheckCircle2, Clock, XCircle, AlertCircle, ChevronDown, Download, RefreshCw } from 'lucide-react';
import { apiFetch, apiUrl } from '../config/api';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface Investor {
  id: number | string;
  name: string;
  distributor: string;
  distributorId?: string;
  productClasses: string[];
  invested: string;
  kyc: 'Verified' | 'Pending' | 'In Progress' | 'Failed';
  kycStatus?: string;
  investorStatus?: string;
  externalKycCheckId?: string;
  externalKycRequestId?: string;
  raw?: any;
  pan: string;
}

// MVP-B2: removed mockInvestors entirely — a transient backend error must not
// silently surface 12 fabricated rows that contradict AdminOverview's counts.
// The catch handler below now sets investors to [] and raises an error banner.

const kycConfig: Record<string, { color: string; bg: string; icon: React.ReactNode }> = {
  Verified:    { color: 'text-green-700', bg: 'bg-green-50',  icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
  Pending:     { color: 'text-amber-700', bg: 'bg-amber-50',  icon: <Clock        className="w-3.5 h-3.5" /> },
  'In Progress':{ color: 'text-blue-700', bg: 'bg-blue-50',   icon: <Clock        className="w-3.5 h-3.5" /> },
  Failed:      { color: 'text-red-700',   bg: 'bg-red-50',    icon: <XCircle      className="w-3.5 h-3.5" /> },
};

const DISTRIBUTORS = ['All', 'Direct (Master)', 'Rahul Distributors', 'WealthEdge Advisory', 'ProFunds India', 'Apex Partners', 'FinTree Wealth', 'MoneyGrow'];
const KYC_STATUSES = ['All', 'Verified', 'Pending', 'In Progress', 'Failed'];
const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const isUuid = (value?: string) => Boolean(value && UUID_PATTERN.test(value));

const getDistributorDisplayName = (distributor: any): string =>
  String(distributor?.fullName || distributor?.name || distributor?.companyName || distributor?.email || distributor?.id || '');

const getKycLabel = (status?: string): Investor['kyc'] => {
  const normalized = status?.trim().toUpperCase() || '';
  if (normalized === 'COMPLETED' || normalized === 'VERIFIED') return 'Verified';
  if (normalized === 'IN_PROGRESS') return 'In Progress';
  if (normalized === 'FAILED' || normalized === 'RETRY_REQUIRED') return 'Failed';
  return 'Pending';
};

export default function InvestorMgmt({ userData }: { userData?: any }) {
  const [investors,      setInvestors]      = useState<Investor[]>([]);
  const [distributors,   setDistributors]   = useState<any[]>([]);
  const [loading,        setLoading]        = useState(true);
  const [loadError,      setLoadError]      = useState(false);
  const [search,         setSearch]         = useState('');
  const [kycFilter,      setKycFilter]      = useState('All');
  const [distFilter,     setDistFilter]     = useState('All');
  const [showFilters,    setShowFilters]    = useState(false);
  const [addModal,       setAddModal]       = useState<'manual' | 'csv' | null>(null);
  const [fetchedDistributorNames, setFetchedDistributorNames] = useState<Record<string, string>>({});
  const [rekycLoadingId, setRekycLoadingId] = useState<string | number | null>(null);
  const [kycActionMessage, setKycActionMessage] = useState('');
  const [kycActionError, setKycActionError] = useState('');
  const hasLoadedRef = React.useRef(false);
  const userId = userData?.id;
  const userRole = userData?.role;

  const distributorNameById = React.useMemo<Map<string, string>>(() => {
    return new Map<string, string>([
      ...distributors.map((d: any) => [String(d.id), getDistributorDisplayName(d)] as [string, string]),
      ...Object.entries(fetchedDistributorNames),
    ]);
  }, [distributors, fetchedDistributorNames]);

  const mapInvestor = React.useCallback((inv: any): Investor => ({
    id: inv.id,
    name: inv.fullName || inv.name || 'Unnamed Investor',
    distributorId: inv.distributorId,
    distributor: distributorNameById.get(inv.distributorId) || inv.distributorName || inv.distributor || inv.distributorId || 'Direct (Master)',
    productClasses: inv.productClasses || ['MF'],
    invested: inv.invested || '—',
    kyc: getKycLabel(inv.kycStatus || inv.kyc),
    kycStatus: inv.kycStatus || inv.kyc,
    investorStatus: inv.investorStatus,
    externalKycCheckId: inv.externalKycCheckId,
    externalKycRequestId: inv.externalKycRequestId,
    raw: inv,
    pan: inv.pan || '—',
  }), [distributorNameById]);

  const resolveDistributorName = React.useCallback((inv: Investor): string => {
    const id = inv.distributorId || (isUuid(inv.distributor) ? inv.distributor : undefined);
    const resolved = id ? distributorNameById.get(id) : undefined;
    if (resolved && !isUuid(resolved)) return resolved;
    if (inv.distributor && !isUuid(inv.distributor)) return inv.distributor;
    return 'Direct (Master)';
  }, [distributorNameById]);

  React.useEffect(() => {
    let cancelled = false;

    const fetchDistributors = async () => {
      try {
        const role = normalizeRole(userRole);
        const params = new URLSearchParams();
        let url = apiUrl('/distributors');

        if (role === 'MASTER_DISTRIBUTOR' && userId) {
          params.set('requesterId', userId);
          url = apiUrl(`/distributors/sub-distributors?${params.toString()}`);
        }

        const res = await apiFetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        if (!cancelled) setDistributors(Array.isArray(data) ? data : []);
      } catch (err) {
        console.error('Failed to fetch distributors for investor filters:', err);
        if (!cancelled) setDistributors([]);
      }
    };

    fetchDistributors();
    return () => { cancelled = true; };
  }, [userId, userRole]);

  React.useEffect(() => {
    let cancelled = false;

    const fetchInvestors = async () => {
      if (!hasLoadedRef.current) setLoading(true);
      try {
        const query = search.trim();
        const params = new URLSearchParams();
        let url = userId
          ? apiUrl(`/investors/visible-to/${userId}`)
          : apiUrl('/investors');

        if (query.length >= 1) {
          params.set('query', query);
          params.set('limit', '50');
          if (userId) params.set('requesterId', userId);
          url = apiUrl(`/investors/search?${params.toString()}`);
        }

        const res = await apiFetch(url);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        if (!cancelled) {
          setInvestors((Array.isArray(data) ? data : []).map(mapInvestor));
          setLoadError(false);
        }
      } catch (err) {
        console.error('Failed to fetch investors:', err);
        if (!cancelled) {
          // MVP-B2: empty + banner, never mock data — fake counts here would
          // contradict AdminOverview's totals and erode trust in the page.
          setInvestors([]);
          setLoadError(true);
        }
      } finally {
        if (!cancelled) {
          hasLoadedRef.current = true;
          setLoading(false);
        }
      }
    };

    const timer = window.setTimeout(fetchInvestors, search.trim().length >= 1 ? 250 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search, userId, mapInvestor]);

  React.useEffect(() => {
    let cancelled = false;
    const missingIds = Array.from(new Set(
      investors
        .map(inv => inv.distributorId || (isUuid(inv.distributor) ? inv.distributor : undefined))
        .filter((id): id is string => Boolean(id && !distributorNameById.has(id)))
    ));

    if (missingIds.length === 0) return () => { cancelled = true; };

    const fetchMissingDistributors = async () => {
      const entries = await Promise.all(
        missingIds.map(async id => {
          try {
            const res = await apiFetch(`/distributors/${id}`);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data = await res.json();
            return [id, getDistributorDisplayName(data) || id] as const;
          } catch (err) {
            console.error(`Failed to fetch distributor ${id}:`, err);
            return [id, id] as const;
          }
        })
      );

      if (!cancelled) {
        setFetchedDistributorNames(prev => ({
          ...prev,
          ...Object.fromEntries(entries),
        }));
      }
    };

    fetchMissingDistributors();
    return () => { cancelled = true; };
  }, [investors, distributorNameById]);

  const filtered = investors.filter(inv => {
    const matchKyc  = kycFilter  === 'All' || inv.kyc         === kycFilter;
    const matchDist = distFilter === 'All' || resolveDistributorName(inv) === distFilter;
    return matchKyc && matchDist;
  });

  const handleReKyc = async (inv: Investor) => {
    if (!isUuid(String(inv.id))) {
      setKycActionError('Re-KYC is available only for saved backend investor records. Refresh the page and try again.');
      setKycActionMessage('');
      return;
    }

    setRekycLoadingId(inv.id);
    setKycActionError('');
    setKycActionMessage('');
    try {
      const response = await apiFetch(`/investors/${inv.id}/kyc/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      });
      const result = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(result?.message || `KYC workflow failed with HTTP ${response.status}`);
      }

      let updatedInvestor = result?.investor;
      if (updatedInvestor) {
        setInvestors(prev => prev.map(row => row.id === inv.id ? mapInvestor(updatedInvestor) : row));
      }
      const status = updatedInvestor?.kycStatus || result?.status || inv.kycStatus || 'updated';
      setKycActionMessage(`${inv.name} KYC updated through backend: ${String(status).replace(/_/g, ' ')}.`);
    } catch (err) {
      console.error('Re-KYC failed:', err);
      setKycActionError(err instanceof Error ? err.message : 'Re-KYC failed.');
    } finally {
      setRekycLoadingId(null);
    }
  };

  const distributorOptions: string[] = [
    'All',
    ...Array.from(new Set<string>(
      investors
        .map(inv => resolveDistributorName(inv))
        .filter((name): name is string => Boolean(name))
    )),
  ];

  // KYC stats
  const total       = investors.length;
  const verified    = investors.filter(i => i.kyc === 'Verified').length;
  const pending     = investors.filter(i => i.kyc === 'Pending').length;
  const inProgress  = investors.filter(i => i.kyc === 'In Progress').length;
  const failed      = investors.filter(i => i.kyc === 'Failed').length;
  const pct = (count: number) => total ? Math.round((count / total) * 100) : 0;
  const verifiedPct = pct(verified);
  const actionNeeded = pending + failed;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Investor Management</h1>
          <p className="text-slate-500 text-sm mt-1">
            {loading ? 'Loading investors…' : `${total} ${total === 1 ? 'investor' : 'investors'} across visible distributors`}
          </p>
        </div>
        <div className="flex gap-3">
          <button onClick={() => setAddModal('csv')}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Upload className="w-4 h-4" /> Upload CSV
          </button>
          <button onClick={() => setAddModal('manual')}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors">
            <UserPlus className="w-4 h-4" /> Onboard Investor
          </button>
        </div>
      </div>

      {/* Load-error banner — surfaced when the investors fetch fails so the
          user sees something honest instead of fabricated rows. */}
      {loadError && (
        <div className="flex items-center gap-2.5 px-4 py-3 rounded-xl border border-red-200 bg-red-50 text-red-700 text-sm font-medium">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          Couldn't load investors. Please try again later.
        </div>
      )}

      {/* KYC Status Banner */}
      <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
        <div className="flex justify-between items-start mb-5">
          <div>
            <h2 className="font-semibold text-slate-800">KYC Completion Status</h2>
            <p className="text-xs text-slate-500 mt-0.5">
              {actionNeeded} investor{actionNeeded !== 1 ? 's' : ''} require immediate action
            </p>
          </div>
          {actionNeeded > 0 && (
            <div className="flex items-center gap-1.5 px-3 py-1.5 bg-amber-50 border border-amber-200 rounded-lg">
              <AlertCircle className="w-4 h-4 text-amber-600" />
              <span className="text-xs font-semibold text-amber-700">{actionNeeded} Action Required</span>
            </div>
          )}
        </div>

        {/* Progress bar */}
        <div className="mb-4">
          <div className="flex justify-between text-xs font-medium text-slate-600 mb-2">
            <span>{verified} Verified ({verifiedPct}%)</span>
            <span className="text-slate-400">{total} Total</span>
          </div>
          <div className="h-3 bg-slate-100 rounded-full overflow-hidden flex">
            <div className="h-full bg-green-500 transition-all" style={{ width: `${pct(verified)}%` }} />
            <div className="h-full bg-blue-400 transition-all"  style={{ width: `${pct(inProgress)}%` }} />
            <div className="h-full bg-amber-400 transition-all" style={{ width: `${pct(pending)}%` }} />
            <div className="h-full bg-red-400 transition-all"   style={{ width: `${pct(failed)}%` }} />
          </div>
        </div>

        {/* Stat pills */}
        <div className="grid grid-cols-4 gap-4">
          {[
            { label: 'Verified',     count: verified,   pct: pct(verified), color: 'bg-green-50 border-green-200 text-green-700' },
            { label: 'In Progress',  count: inProgress, pct: pct(inProgress), color: 'bg-blue-50  border-blue-200  text-blue-700'  },
            { label: 'Pending',      count: pending,    pct: pct(pending), color: 'bg-amber-50 border-amber-200 text-amber-700' },
            { label: 'Failed',       count: failed,     pct: pct(failed), color: 'bg-red-50   border-red-200   text-red-700'   },
          ].map(s => (
            <div key={s.label} className={`rounded-xl p-4 border ${s.color}`}>
              <p className="text-2xl font-bold">{s.count}</p>
              <p className="text-xs font-semibold mt-0.5">{s.label}</p>
              <p className="text-xs opacity-70 mt-1">{s.pct}% of total</p>
            </div>
          ))}
        </div>
      </div>

      {/* Table card */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="p-4 border-b border-slate-100 flex flex-wrap gap-3 items-center">
          <div className="relative flex-1 min-w-[200px] max-w-sm">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input type="text" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search by name, PAN or distributor..."
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none" />
          </div>
          <button onClick={() => setShowFilters(p => !p)}
            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium border rounded-lg transition-colors ${showFilters ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'}`}>
            <Filter className="w-4 h-4" /> Filter
            <ChevronDown className={`w-3.5 h-3.5 transition-transform ${showFilters ? 'rotate-180' : ''}`} />
          </button>
        </div>

        <AnimatePresence>
          {showFilters && (
            <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
              className="overflow-hidden border-b border-slate-100">
              <div className="p-4 bg-slate-50 flex flex-wrap gap-6">
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">KYC Status</p>
                  <div className="flex gap-2 flex-wrap">
                    {KYC_STATUSES.map(s => (
                      <button key={s} onClick={() => setKycFilter(s)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${kycFilter === s ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {s}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Distributor</p>
                  <div className="flex gap-2 flex-wrap">
                    {distributorOptions.slice(0, 5).map(d => (
                      <button key={d} onClick={() => setDistFilter(d)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${distFilter === d ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {d === 'Direct (Master)' ? 'Direct' : d.split(' ')[0]}
                      </button>
                    ))}
                  </div>
                </div>
                {(kycFilter !== 'All' || distFilter !== 'All') && (
                  <button onClick={() => { setKycFilter('All'); setDistFilter('All'); }}
                    className="self-end flex items-center gap-1 text-xs text-red-500 hover:text-red-700 font-semibold">
                    <X className="w-3 h-3" /> Clear
                  </button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {(kycActionMessage || kycActionError) && (
          <div className={`mx-4 mt-4 rounded-xl border px-4 py-3 text-sm font-medium ${
            kycActionError
              ? 'border-red-100 bg-red-50 text-red-700'
              : 'border-green-100 bg-green-50 text-green-700'
          }`}>
            {kycActionError || kycActionMessage}
          </div>
        )}

        <div className="overflow-auto">
          {loading ? (
            <div className="p-12 text-center text-slate-500 font-medium">
              <div className="animate-spin w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4"></div>
              Loading investors...
            </div>
          ) : (
          <table className="w-full text-left">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold sticky top-0 z-10">
              <tr>
                <th className="px-6 py-4">Investor Name</th>
                <th className="px-6 py-4">Distributor</th>
                <th className="px-6 py-4">Product Classes</th>
                <th className="px-6 py-4">Invested Amount</th>
                <th className="px-6 py-4">KYC Status</th>
                <th className="px-6 py-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {!loading && investors.length === 0 && !loadError && (
                <tr>
                  <td colSpan={6} className="px-6 py-12 text-center text-sm text-slate-400">
                    No investors yet — onboard one using the buttons above.
                  </td>
                </tr>
              )}
              {filtered.map(inv => {
                const kc = kycConfig[inv.kyc];
                return (
                  <tr key={inv.id} className="group hover:bg-slate-50 transition-colors cursor-pointer">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{inv.name}</div>
                      <div className="text-xs text-slate-400 font-mono mt-0.5">{inv.pan}</div>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">{resolveDistributorName(inv)}</td>
                    <td className="px-6 py-4">
                      <div className="flex gap-1.5 flex-wrap">
                        {inv.productClasses.map(pc => (
                          <span key={pc} className={`px-2 py-0.5 text-[10px] font-bold rounded ${pc === 'MF' ? 'bg-blue-50 text-blue-700' : 'bg-violet-50 text-violet-700'}`}>
                            {pc}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="px-6 py-4 font-mono font-semibold text-slate-800">{inv.invested}</td>
                    <td className="px-6 py-4">
                      <span className={`flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-md w-fit ${kc.bg} ${kc.color}`}>
                        {kc.icon} {inv.kyc}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center gap-2 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                        <button className="px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors">View</button>
                        {(inv.kyc === 'Pending' || inv.kyc === 'Failed' || inv.kyc === 'In Progress') && (
                          <button
                            onClick={() => handleReKyc(inv)}
                            disabled={rekycLoadingId === inv.id}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-amber-600 border border-amber-200 rounded-lg hover:bg-amber-50 transition-colors disabled:opacity-50"
                          >
                            {rekycLoadingId === inv.id && <RefreshCw className="h-3.5 w-3.5 animate-spin" />}
                            {rekycLoadingId === inv.id ? 'Running' : 'Re-KYC'}
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

      {/* Modals */}
      <AnimatePresence>
        {addModal === 'manual' && <ManualOnboardModal onClose={() => setAddModal(null)} />}
        {addModal === 'csv'    && <CsvUploadModal     onClose={() => setAddModal(null)} />}
      </AnimatePresence>
    </motion.div>
  );
}

function ManualOnboardModal({ onClose }: { onClose: () => void }) {
  const dialogRef = useFocusTrap<HTMLDivElement>(true);
  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="manual-onboard-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
    >
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <motion.div initial={{ opacity: 0, scale: 0.95, y: 20 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white w-full max-w-md rounded-3xl shadow-xl relative z-10 overflow-hidden max-h-[90vh] overflow-y-auto">
        <button onClick={onClose} aria-label="Close dialog" className="absolute top-4 right-4 p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors z-10">
          <X className="w-5 h-5" aria-hidden="true" />
        </button>
        <div className="p-8">
          <h2 id="manual-onboard-title" className="text-xl font-semibold text-slate-800 mb-1">Onboard Investor</h2>
          <p className="text-sm text-slate-500 mb-6">Manually register a new investor</p>
          <div className="space-y-4">
            {[
              { label: 'Full Name',     placeholder: 'e.g. Aditya Sharma',     type: 'text'  },
              { label: 'Mobile Number', placeholder: '+91 XXXXX XXXXX',        type: 'tel'   },
              { label: 'Email Address', placeholder: 'investor@example.com',   type: 'email' },
              { label: 'PAN Number',    placeholder: 'ABCDE1234F',             type: 'text', upper: true },
            ].map(f => (
              <div key={f.label}>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">{f.label}</label>
                <input type={f.type} placeholder={f.placeholder}
                  className={`w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none ${f.upper ? 'uppercase' : ''}`} />
              </div>
            ))}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Date of Birth</label>
              <input type="date" className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Assign Distributor</label>
              <select className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                {DISTRIBUTORS.map(d => <option key={d}>{d}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Address (Optional)</label>
              <textarea rows={2} placeholder="Full address..."
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none resize-none" />
            </div>
          </div>
          <div className="mt-8 flex gap-3">
            <button onClick={onClose} className="px-5 py-2.5 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">Cancel</button>
            <button onClick={onClose} className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors">Create Investor</button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}

function CsvUploadModal({ onClose }: { onClose: () => void }) {
  const dialogRef = useFocusTrap<HTMLDivElement>(true);
  const [dragging, setDragging] = useState(false);
  const [uploaded, setUploaded] = useState(false);

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="csv-upload-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
    >
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <motion.div initial={{ opacity: 0, scale: 0.95, y: 20 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white w-full max-w-md rounded-3xl shadow-xl relative z-10 overflow-hidden">
        <button onClick={onClose} aria-label="Close dialog" className="absolute top-4 right-4 p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors">
          <X className="w-5 h-5" aria-hidden="true" />
        </button>
        <div className="p-8">
          <h2 id="csv-upload-title" className="text-xl font-semibold text-slate-800 mb-1">Bulk Upload via CSV</h2>
          <p className="text-sm text-slate-500 mb-6">Import multiple investors at once using a CSV file</p>

          {!uploaded ? (
            <>
              <div
                onDragOver={e => { e.preventDefault(); setDragging(true); }}
                onDragLeave={() => setDragging(false)}
                onDrop={e => { e.preventDefault(); setDragging(false); setUploaded(true); }}
                onClick={() => setUploaded(true)}
                className={`border-2 border-dashed rounded-2xl p-10 text-center cursor-pointer transition-all ${dragging ? 'border-blue-400 bg-blue-50' : 'border-slate-200 hover:border-blue-300 hover:bg-slate-50'}`}
              >
                <Upload className={`w-10 h-10 mx-auto mb-3 ${dragging ? 'text-blue-500' : 'text-slate-300'}`} />
                <p className="text-sm font-semibold text-slate-700">Drop your CSV here</p>
                <p className="text-xs text-slate-400 mt-1">or click to browse files</p>
                <p className="text-[10px] text-slate-400 mt-3">Supports .csv files up to 5MB</p>
              </div>

              <div className="mt-4 p-4 bg-slate-50 rounded-xl border border-slate-100">
                <div className="flex justify-between items-center">
                  <div>
                    <p className="text-xs font-semibold text-slate-700">Required CSV columns:</p>
                    <p className="text-[10px] text-slate-500 mt-1 font-mono">name, mobile, email, pan, dob, distributor_arn, address</p>
                  </div>
                  <button className="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-slate-200 rounded-lg text-xs font-semibold text-blue-600 hover:bg-blue-50 transition-colors">
                    <Download className="w-3.5 h-3.5" /> Template
                  </button>
                </div>
              </div>
            </>
          ) : (
            <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
              className="text-center py-6">
              <div className="w-16 h-16 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-4">
                <CheckCircle2 className="w-8 h-8 text-green-500" />
              </div>
              <p className="font-semibold text-slate-800">investors_batch.csv uploaded</p>
              <p className="text-sm text-slate-500 mt-1">24 investors detected · 0 errors</p>
              <div className="mt-4 bg-slate-50 rounded-xl p-4 text-left border border-slate-100">
                <p className="text-xs font-semibold text-slate-600 mb-2">Preview (first 3 rows)</p>
                {['Kavya Reddy · PQRST1234U', 'Amit Joshi · VWXYZ5678A', 'Deepa Nair · BCDEF9012G'].map(r => (
                  <div key={r} className="flex items-center gap-2 py-1 text-xs text-slate-500">
                    <CheckCircle2 className="w-3 h-3 text-green-500 flex-shrink-0" /> {r}
                  </div>
                ))}
              </div>
            </motion.div>
          )}

          <div className="mt-6 flex gap-3">
            <button onClick={onClose} className="px-5 py-2.5 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">Cancel</button>
            <button onClick={onClose} disabled={!uploaded}
              className={`flex-1 py-2.5 text-sm font-medium rounded-xl transition-colors ${uploaded ? 'bg-[#0B1B3E] text-white hover:bg-[#1A3066]' : 'bg-slate-200 text-slate-400 cursor-not-allowed'}`}>
              {uploaded ? 'Import 24 Investors' : 'Waiting for file...'}
            </button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
