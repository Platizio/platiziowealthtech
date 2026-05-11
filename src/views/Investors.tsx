import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Search, Filter, ChevronLeft, Download, ShieldCheck,
  TrendingUp, AreaChart as AreaChartIcon, Activity,
  CheckCircle2, Clock, XCircle, AlertCircle,
} from 'lucide-react';
import {
  AreaChart as RechartsArea, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { apiUrl } from '../config/api';

// ─── KYC status config ─────────────────────────────────────────────────────────
const kycConfig: Record<string, { label: string; color: string; icon: React.ReactNode }> = {
  COMPLETED:       { label: 'KYC Verified',   color: 'text-green-600',  icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
  IN_PROGRESS:     { label: 'In Progress',    color: 'text-amber-600',  icon: <Clock        className="w-3.5 h-3.5" /> },
  PENDING:         { label: 'Pending',        color: 'text-amber-600',  icon: <Clock        className="w-3.5 h-3.5" /> },
  NOT_STARTED:     { label: 'Not Started',    color: 'text-slate-400',  icon: <XCircle      className="w-3.5 h-3.5" /> },
  FAILED:          { label: 'KYC Failed',     color: 'text-red-600',    icon: <XCircle      className="w-3.5 h-3.5" /> },
  RETRY_REQUIRED:  { label: 'Retry Required', color: 'text-orange-500', icon: <AlertCircle  className="w-3.5 h-3.5" /> },
};

const statusConfig: Record<string, string> = {
  DRAFT:                   'bg-slate-100 text-slate-600',
  ONBOARDING:              'bg-blue-50 text-blue-600',
  READY_FOR_TRANSACTIONS:  'bg-green-50 text-green-700',
  ACTIVE:                  'bg-green-50 text-green-700',
  BLOCKED:                 'bg-red-50 text-red-600',
  ARCHIVED:                'bg-slate-100 text-slate-400',
  INACTIVE:                'bg-slate-100 text-slate-400',
};

const riskConfig: Record<string, string> = {
  UNASSESSED:  'bg-slate-50 text-slate-500',
  CONSERVATIVE:'bg-blue-50 text-blue-600',
  MODERATE:    'bg-amber-50 text-amber-600',
  AGGRESSIVE:  'bg-red-50 text-red-600',
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


// ─── Component ────────────────────────────────────────────────────────────────
export default function Investors({
  onInvest,
  userData,
}: {
  onInvest?: (investor: any) => void;
  userData?: any;
}) {
  const [investors, setInvestors]         = useState<any[]>([]);
  const [loading, setLoading]             = useState(true);
  const [error, setError]                 = useState('');
  const [search, setSearch]               = useState('');
  const [kycFilter, setKycFilter]         = useState('All');
  const [statusFilter, setStatusFilter]   = useState('All');
  const [selectedInvestor, setSelectedInvestor] = useState<any | null>(null);
  const hasLoadedRef = React.useRef(false);
  const baseInvestorsRef = React.useRef<any[]>([]);
  const distributorId = userData?.id;

  useEffect(() => {
    let cancelled = false;

    const fetchInvestors = async () => {
      if (!hasLoadedRef.current) setLoading(true);
      setError('');
      try {
        const query = search.trim();
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

        const res = await fetch(url);
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

    const timer = window.setTimeout(fetchInvestors, search.trim().length >= 2 ? 250 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search, distributorId]);

  const KYC_OPTIONS    = ['All', 'COMPLETED', 'PENDING', 'IN_PROGRESS', 'NOT_STARTED', 'FAILED', 'RETRY_REQUIRED'];
  const STATUS_OPTIONS = ['All', 'ACTIVE', 'READY_FOR_TRANSACTIONS', 'ONBOARDING', 'DRAFT', 'BLOCKED', 'ARCHIVED'];

  const filtered = investors.filter(inv => {
    const matchSearch = matchesInvestorSearch(inv, search.trim());
    const matchKyc    = kycFilter    === 'All' || inv.kycStatus    === kycFilter;
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
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 h-full flex flex-col">
      {/* Header */}
      <div className="flex justify-between items-center mb-6">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Investor Archive</h1>
          <p className="text-slate-500 text-sm mt-1">
            {loading ? 'Loading…' : `${filtered.length} of ${investors.length} investors`}
          </p>
        </div>
      </div>

      {/* Search + filters */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 mb-4">
        <div className="p-4 flex flex-wrap gap-3 items-center border-b border-slate-100">
          <div className="relative flex-1 min-w-[220px] max-w-md">
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
              {KYC_OPTIONS.map(o => <option key={o} value={o}>{o === 'All' ? 'All KYC' : (kycConfig[o]?.label || o)}</option>)}
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
        <div className="overflow-auto">
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
            <div className="p-16 text-center text-slate-400">
              <ShieldCheck className="w-12 h-12 mx-auto mb-4 text-slate-200" />
              <p className="font-semibold text-slate-500">No investors found</p>
              <p className="text-sm mt-1">Try adjusting your filters</p>
            </div>
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
                  const kyc    = kycConfig[inv.kycStatus]    || kycConfig['NOT_STARTED'];
                  const stCls  = statusConfig[inv.investorStatus] || 'bg-slate-100 text-slate-500';
                  const riskCls = riskConfig[inv.riskProfile] || 'bg-slate-50 text-slate-500';
                  const initials = (inv.fullName || 'IN').split(' ').map((n: string) => n[0]).join('').substring(0, 2).toUpperCase();
                  const isKycDone = inv.kycStatus === 'COMPLETED';

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
                        <span className={`flex items-center gap-1.5 text-xs font-medium ${kyc.color}`}>
                          {kyc.icon} {kyc.label}
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
  const [performanceData, setPerformanceData] = useState<any[]>([]);

  useEffect(() => {
    fetch(apiUrl(`/orders/by-investor/${investor.id}`))
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
        for(let i=0; i<6; i++) {
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
  }, [investor.id]);

  const kyc = kycConfig[investor.kycStatus] || kycConfig['NOT_STARTED'];
  const stCls = statusConfig[investor.investorStatus] || 'bg-slate-100 text-slate-500';
  const isKycDone = investor.kycStatus === 'COMPLETED';
  const initials = (investor.fullName || 'IN').split(' ').map((n: string) => n[0]).join('').substring(0, 2).toUpperCase();

  const tabs = [
    { id: 'overview',   label: 'Overview'   },
    { id: 'compliance', label: 'Compliance'  },
  ];

  return (
    <motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} className="p-8 max-w-5xl mx-auto">
      <button onClick={onBack} className="flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-slate-800 mb-6 transition-colors">
        <ChevronLeft className="w-4 h-4" /> Back to Archive
      </button>

      {/* Header */}
      <div className="flex justify-between items-start mb-8">
        <div className="flex items-center gap-4">
          <div className="w-14 h-14 rounded-full bg-blue-100 text-blue-700 flex items-center justify-center text-xl font-bold flex-shrink-0">
            {initials}
          </div>
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-slate-800">{investor.fullName}</h1>
            <div className="flex flex-wrap items-center gap-2 mt-2">
              <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${stCls}`}>
                {(investor.investorStatus || 'DRAFT').replace(/_/g, ' ')}
              </span>
              <span className={`flex items-center gap-1.5 text-xs font-medium ${kyc.color}`}>
                {kyc.icon} {kyc.label}
              </span>
              {investor.pan && (
                <span className="text-slate-400 font-mono text-xs">PAN: {investor.pan}</span>
              )}
            </div>
          </div>
        </div>
        <div className="flex gap-2">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Download className="w-4 h-4" /> Dossier
          </button>
          {isKycDone && onInvest && (
            <button
              onClick={() => onInvest(investor)}
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
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {/* Info cards */}
              {[
                { label: 'Email',        value: investor.email },
                { label: 'Mobile',       value: investor.mobileNumber },
                { label: 'Date of Birth',value: investor.dateOfBirth || '—' },
                { label: 'City',         value: investor.city || '—' },
                { label: 'State',        value: investor.state || '—' },
                { label: 'Risk Profile', value: (investor.riskProfile || 'UNASSESSED').replace(/_/g, ' ') },
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
                        <stop offset="5%"  stopColor="#3b82f6" stopOpacity={0.3} />
                        <stop offset="95%" stopColor="#3b82f6" stopOpacity={0} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                    <XAxis dataKey="month" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                    <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                    <Tooltip contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }} />
                    <Area type="monotone" dataKey="value" stroke="#3b82f6" strokeWidth={3} fillOpacity={1} fill="url(#colorVal)" />
                  </RechartsArea>
                </ResponsiveContainer>
              </div>
            </div>
          </motion.div>
        )}

        {activeTab === 'compliance' && (
          <motion.div key="compliance" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="space-y-4">
            {[
              { label: 'KYC Status',          value: (kycConfig[investor.kycStatus]?.label || investor.kycStatus || 'Not Started') },
              { label: 'Bank Verification',   value: (investor.bankVerificationStatus || 'NOT_CAPTURED').replace(/_/g, ' ') },
              { label: 'Investor Status',     value: (investor.investorStatus || 'DRAFT').replace(/_/g, ' ') },
              { label: 'Risk Profile',        value: (investor.riskProfile || 'UNASSESSED').replace(/_/g, ' ') },
              { label: 'Onboarding Notes',    value: investor.onboardingNotes || 'None' },
            ].map(({ label, value }) => (
              <div key={label} className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm flex justify-between items-center">
                <p className="text-sm text-slate-500 font-medium">{label}</p>
                <p className="text-sm font-semibold text-slate-800">{value}</p>
              </div>
            ))}
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
