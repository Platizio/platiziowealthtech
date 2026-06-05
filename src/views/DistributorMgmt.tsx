import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Search, Filter, X, CheckCircle2, Clock, XCircle, ChevronDown } from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer
} from 'recharts';
import { apiFetch, apiUrl } from '../config/api';
import { formatDate } from '../utils/formatDate';
import { useFocusTrap } from '../hooks/useFocusTrap';

// MVB-B2: derived-data shapes. We no longer trust `aum`/`investors`/`tier`
// off the backend distributor record — they don't exist there. AUM and
// investor counts come from /orders + /investors, tier is derived locally
// with the same thresholds used by AdminOverview so the two pages agree.

type TierName = 'Platinum' | 'Gold' | 'Silver' | 'Bronze';

interface OrderRow {
  id: string;
  distributorId?: string;
  transactionType?: string;
  orderStatus?: string;
  amount?: number | string;
}

interface InvestorRow {
  id: string;
  distributorId?: string;
}

// Mirrors AdminOverview.tsx exactly so per-page totals can't drift apart.
const AUM_OK_STATUSES: ReadonlySet<string> = new Set([
  'SUCCESSFUL', 'ACTIVE', 'COMPLETED', 'PROCESSING', 'SUBMITTED',
]);
const OUTFLOW_TYPES: ReadonlySet<string> = new Set([
  'REDEMPTION', 'SWP',
]);

// Indian-locale currency formatter — Cr / L / grouped rupees. Same shape as
// AdminOverview so a distributor's row reads identically across screens.
function formatCurrency(value: number): string {
  if (!isFinite(value)) return '₹0';
  const abs = Math.abs(value);
  if (abs >= 1e7) return `₹${(value / 1e7).toFixed(1)} Cr`;
  if (abs >= 1e5) return `₹${(value / 1e5).toFixed(1)} L`;
  return `₹${Math.round(value).toLocaleString('en-IN')}`;
}

function tierFor(aum: number): TierName {
  if (aum >= 50e7) return 'Platinum';
  if (aum >= 10e7) return 'Gold';
  if (aum >= 1e7)  return 'Silver';
  return 'Bronze';
}

const tierConfig: Record<string, string> = {
  Platinum: 'bg-violet-50 text-violet-700 border-violet-200',
  Gold:     'bg-amber-50 text-amber-700 border-amber-200',
  Silver:   'bg-slate-100 text-slate-600 border-slate-200',
  Bronze:   'bg-orange-50 text-orange-700 border-orange-200',
};

const statusConfig: Record<string, { color: string; icon: React.ReactNode }> = {
  ACTIVE:   { color: 'text-green-600', icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
  APPROVED: { color: 'text-green-600', icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
  PENDING_APPROVAL:  { color: 'text-amber-600', icon: <Clock        className="w-3.5 h-3.5" /> },
  SUBMITTED: { color: 'text-amber-600', icon: <Clock        className="w-3.5 h-3.5" /> },
  DRAFT: { color: 'text-slate-400', icon: <Clock        className="w-3.5 h-3.5" /> },
  INACTIVE: { color: 'text-slate-400', icon: <XCircle      className="w-3.5 h-3.5" /> },
  REJECTED: { color: 'text-red-600', icon: <XCircle      className="w-3.5 h-3.5" /> },
};

const TIERS   = ['All', 'Platinum', 'Gold', 'Silver', 'Bronze'];
const STATUSES = ['All', 'Active', 'Pending', 'Inactive'];
const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';

// F-19: backend DistributorStatus enum is
//   { DRAFT, SUBMITTED, PENDING_APPROVAL, APPROVED, REJECTED, INACTIVE }
// The UI filter labels are ('Active' | 'Pending' | 'Inactive'). Previously the
// filter compared d.status to the literal label (uppercased), so clicking
// "Active" matched zero rows because the backend never returns 'ACTIVE' — an
// approved distributor is 'APPROVED'. This map fixes that and also folds
// REJECTED under the Inactive bucket (it was silently dropped before).
const UI_STATUS_TO_BACKEND: Record<string, string[]> = {
  Active:   ['APPROVED', 'ACTIVE'],
  Pending:  ['PENDING_APPROVAL', 'SUBMITTED', 'DRAFT', 'PENDING'],
  Inactive: ['INACTIVE', 'REJECTED'],
};

const matchesUiStatus = (uiLabel: string, backendStatus?: string) => {
  if (uiLabel === 'All') return true;
  const allowed = UI_STATUS_TO_BACKEND[uiLabel];
  if (!allowed) return false;
  return allowed.includes((backendStatus ?? '').trim().toUpperCase());
};

export default function DistributorMgmt({ userData }: { userData: any }) {
  const [distributors, setDistributors] = useState<any[]>([]);
  const [orders, setOrders] = useState<OrderRow[]>([]);
  const [investors, setInvestors] = useState<InvestorRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [tierFilter, setTierFilter] = useState('All');
  const [statusFilter, setStatusFilter] = useState('All');
  const [showAddModal, setShowAddModal] = useState(false);
  const [showFilters, setShowFilters] = useState(false);
  const hasLoadedRef = React.useRef(false);
  const userId = userData?.id;
  const userRole = userData?.role;

  // ── Distributor list fetch (search-aware, debounced on keystroke). ─────────
  // For MASTER_DISTRIBUTOR with no active search we ALSO fetch our own record
  // and prepend it — mirroring AdminOverview, since the sub-distributors
  // endpoint never returns the requester themselves.
  React.useEffect(() => {
    let cancelled = false;

    const fetchDistributors = async () => {
      if (!hasLoadedRef.current) setLoading(true);
      try {
        const query = search.trim();
        const role = normalizeRole(userRole);
        const params = new URLSearchParams();
        let url = apiUrl('/distributors');
        const isMasterDefault = role === 'MASTER_DISTRIBUTOR' && userId && query.length === 0;

        if (query.length >= 1) {
          params.set('query', query);
          params.set('limit', '50');
          if (userId) params.set('requesterId', userId);
          url = apiUrl(`/distributors/search?${params.toString()}`);
        } else if (role === 'MASTER_DISTRIBUTOR' && userId) {
          params.set('requesterId', userId);
          url = apiUrl(`/distributors/sub-distributors?${params.toString()}`);
        }

        const [listRes, selfRes] = await Promise.all([
          apiFetch(url),
          isMasterDefault ? apiFetch(apiUrl(`/distributors/${encodeURIComponent(userId)}`)) : Promise.resolve(null),
        ]);
        if (!listRes.ok) throw new Error('Failed to fetch distributors');
        const data = await listRes.json();
        const baseList = Array.isArray(data) ? data : [];

        let merged = baseList;
        if (selfRes && selfRes.ok) {
          const self = await selfRes.json();
          if (self && self.id) {
            merged = [self, ...baseList.filter((d: any) => d.id !== self.id)];
          }
        }

        if (!cancelled) setDistributors(merged);
      } catch (err) {
        console.error('Error fetching distributors:', err);
        if (!cancelled) setDistributors([]);
      } finally {
        if (!cancelled) {
          hasLoadedRef.current = true;
          setLoading(false);
        }
      }
    };

    const timer = window.setTimeout(fetchDistributors, search.trim().length >= 1 ? 250 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search, userId, userRole]);

  // ── Orders + investors fetch (search-independent, runs once). ──────────────
  // These feed per-distributor AUM and investor counts. Re-fetching them on
  // every keystroke would be wasteful — the search only narrows the
  // distributor list, not the AUM math behind each row.
  React.useEffect(() => {
    let cancelled = false;

    const fetchAggregates = async () => {
      try {
        const [invRes, ordRes] = await Promise.all([
          apiFetch(apiUrl('/investors?page=0&size=500')),
          apiFetch(apiUrl('/orders?page=0&size=500')),
        ]);
        if (!invRes.ok || !ordRes.ok) throw new Error('Failed to fetch aggregates');
        const [invJson, ordJson] = await Promise.all([invRes.json(), ordRes.json()]);
        const invList: InvestorRow[] = invJson?.content ?? (Array.isArray(invJson) ? invJson : []);
        const ordList: OrderRow[] = ordJson?.content ?? (Array.isArray(ordJson) ? ordJson : []);
        if (!cancelled) {
          setInvestors(invList);
          setOrders(ordList);
        }
      } catch (err) {
        console.error('Error fetching investors/orders for distributor view:', err);
        if (!cancelled) {
          setInvestors([]);
          setOrders([]);
        }
      }
    };

    fetchAggregates();
    return () => { cancelled = true; };
  }, []);

  // ── Per-distributor AUM (Cr-eligible totals only). ─────────────────────────
  const aumByDistributor = React.useMemo(() => {
    const m = new Map<string, number>();
    for (const o of orders) {
      const status = (o.orderStatus ?? '').toUpperCase();
      const type = (o.transactionType ?? '').toUpperCase();
      if (!AUM_OK_STATUSES.has(status)) continue;
      if (OUTFLOW_TYPES.has(type)) continue;
      if (!o.distributorId) continue;
      m.set(o.distributorId, (m.get(o.distributorId) ?? 0) + (Number(o.amount) || 0));
    }
    return m;
  }, [orders]);

  // ── Per-distributor investor counts. ───────────────────────────────────────
  const investorCountByDistributor = React.useMemo(() => {
    const m = new Map<string, number>();
    for (const inv of investors) {
      if (!inv.distributorId) continue;
      m.set(inv.distributorId, (m.get(inv.distributorId) ?? 0) + 1);
    }
    return m;
  }, [investors]);

  // ── Enrich each distributor row with derived AUM/tier/count once. ──────────
  // Doing this in one pass keeps the filter, the pill counts, and the table
  // body all reading from the same source of truth.
  const enriched = React.useMemo(() => {
    return distributors.map((d: any) => {
      const aum = aumByDistributor.get(d.id) ?? 0;
      const investorsCount = investorCountByDistributor.get(d.id) ?? 0;
      return {
        ...d,
        _aum: aum,
        _aumLabel: formatCurrency(aum),
        _investors: investorsCount,
        _tier: tierFor(aum) as TierName,
      };
    });
  }, [distributors, aumByDistributor, investorCountByDistributor]);

  const filtered = enriched.filter(d => {
    const matchTier   = tierFilter   === 'All' || d._tier === tierFilter;
    const matchStatus = matchesUiStatus(statusFilter, d.status);
    return matchTier && matchStatus;
  });

  const tierCounts = TIERS.slice(1).reduce((acc, t) => {
    acc[t] = enriched.filter(d => d._tier === t).length;
    return acc;
  }, {} as Record<string, number>);

  // ── Network growth: cumulative distributor count per month, last 6 months.
  // Mirrors the structure of AdminOverview's AUM trend so the chart card
  // tells the same story — onboarding momentum, not invented numbers.
  const networkGrowth = React.useMemo(() => {
    const now = new Date();
    const months: { key: string; label: string; end: number }[] = [];
    for (let i = 5; i >= 0; i--) {
      const endOfMonth = new Date(now.getFullYear(), now.getMonth() - i + 1, 0, 23, 59, 59, 999);
      const label = new Date(now.getFullYear(), now.getMonth() - i, 1)
        .toLocaleDateString('en-US', { month: 'short' });
      months.push({
        key: `${endOfMonth.getFullYear()}-${endOfMonth.getMonth()}`,
        label,
        end: endOfMonth.getTime(),
      });
    }
    return months.map(m => {
      const count = distributors.reduce((acc: number, d: any) => {
        const t = new Date(d.createdAt).getTime();
        return isFinite(t) && t <= m.end ? acc + 1 : acc;
      }, 0);
      return { month: m.label, count };
    });
  }, [distributors]);

  const networkGrowthDelta = networkGrowth.length
    ? (networkGrowth[networkGrowth.length - 1].count - networkGrowth[0].count)
    : 0;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Distributor Management</h1>
          <p className="text-slate-500 text-sm mt-1">
            {distributors.length} {distributors.length === 1 ? 'distributor' : 'distributors'} enrolled in the network
          </p>
        </div>
        <button
          onClick={() => setShowAddModal(true)}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors"
        >
          + Add Distributor
        </button>
      </div>

      {/* Tier summary pills */}
      <div className="flex gap-3 flex-wrap">
        {TIERS.slice(1).map(tier => (
          <div key={tier} className={`flex items-center gap-2 px-4 py-2.5 rounded-xl border text-sm font-medium cursor-pointer transition-all ${tierFilter === tier ? tierConfig[tier] + ' ring-2 ring-offset-1 ring-current' : 'bg-white border-slate-200 text-slate-600 hover:border-slate-300'}`}
            onClick={() => setTierFilter(tierFilter === tier ? 'All' : tier)}>
            <span className={`w-2 h-2 rounded-full ${tier === 'Platinum' ? 'bg-violet-500' : tier === 'Gold' ? 'bg-amber-500' : tier === 'Silver' ? 'bg-slate-400' : 'bg-orange-400'}`} />
            {tier}
            <span className="ml-1 font-bold text-xs">{tierCounts[tier]}</span>
          </div>
        ))}
      </div>

      {/* Table card */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        {/* Toolbar */}
        <div className="p-4 border-b border-slate-100 flex flex-wrap gap-3 items-center">
          <div className="relative flex-1 min-w-[200px] max-w-sm">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search by name or ARN..."
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>
          <button
            onClick={() => setShowFilters(p => !p)}
            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium border rounded-lg transition-colors ${showFilters ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'}`}
          >
            <Filter className="w-4 h-4" /> Filter
            <ChevronDown className={`w-3.5 h-3.5 transition-transform ${showFilters ? 'rotate-180' : ''}`} />
          </button>
        </div>

        {/* Filter panel */}
        <AnimatePresence>
          {showFilters && (
            <motion.div
              initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
              className="overflow-hidden border-b border-slate-100"
            >
              <div className="p-4 bg-slate-50 flex flex-wrap gap-6">
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Tier</p>
                  <div className="flex gap-2">
                    {TIERS.map(t => (
                      <button key={t} onClick={() => setTierFilter(t)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${tierFilter === t ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {t}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Status</p>
                  <div className="flex gap-2">
                    {STATUSES.map(s => (
                      <button key={s} onClick={() => setStatusFilter(s)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${statusFilter === s ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {s}
                      </button>
                    ))}
                  </div>
                </div>
                {(tierFilter !== 'All' || statusFilter !== 'All') && (
                  <button onClick={() => { setTierFilter('All'); setStatusFilter('All'); }}
                    className="self-end flex items-center gap-1 text-xs text-red-500 hover:text-red-700 font-semibold">
                    <X className="w-3 h-3" /> Clear filters
                  </button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Table */}
        <div className="overflow-auto">
          {loading ? (
            <div className="p-12 text-center text-slate-500 font-medium">
              <div className="animate-spin w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4"></div>
              Loading distributors...
            </div>
          ) : (
            <table className="w-full text-left">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold sticky top-0 z-10">
              <tr>
                <th className="px-6 py-4">Distributor Name</th>
                <th className="px-6 py-4">ARN Number</th>
                <th className="px-6 py-4">AUM</th>
                <th className="px-6 py-4">Investors</th>
                <th className="px-6 py-4">Status</th>
                <th className="px-6 py-4">Tier</th>
                <th className="px-6 py-4">Date Onboarded</th>
                <th className="px-6 py-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filtered.map(d => {
                const name = d.name || d.fullName || 'Unnamed';
                const arn = d.arn || d.arnNumber || 'N/A';
                const aum = d._aumLabel;
                const investorsCount = d._investors;
                const status = d.status || 'DRAFT';
                const tier: TierName = d._tier;
                const onboarded = (d.onboarded || d.createdAt) ? formatDate(d.onboarded || d.createdAt) : 'N/A';
                
                const sc = statusConfig[status.toUpperCase()] || statusConfig['DRAFT'];
                
                return (
                  <tr key={d.id} className="group hover:bg-slate-50 transition-colors">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{name}</div>
                      <div className="text-xs text-slate-400 mt-0.5">{d.email}</div>
                    </td>
                    <td className="px-6 py-4 text-sm font-mono text-slate-600">{arn}</td>
                    <td className="px-6 py-4 font-mono font-semibold text-slate-800">{aum}</td>
                    <td className="px-6 py-4 text-sm font-medium text-slate-700">{investorsCount.toLocaleString()}</td>
                    <td className="px-6 py-4">
                      <span className={`flex items-center gap-1.5 text-xs font-semibold w-fit ${sc.color}`}>
                        {sc.icon} {status}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <span className={`px-2.5 py-1 text-xs font-bold rounded-md border ${tierConfig[tier]}`}>
                        {tier}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-500">{onboarded}</td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center gap-2 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                        <button className="px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors">View</button>
                        <button className="px-3 py-1.5 text-xs font-semibold text-slate-600 border border-slate-200 rounded-lg hover:bg-slate-100 transition-colors">Edit</button>
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

      {/* Network Growth — cumulative distributor count, rolling 6 months. */}
      {distributors.length > 0 && (
        <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
          <div className="flex justify-between items-center mb-6">
            <div>
              <h2 className="font-semibold text-slate-800">Network Growth</h2>
              <p className="text-xs text-slate-500 mt-0.5">Total distributors onboarded over time</p>
            </div>
            <span className="text-xs font-semibold text-green-600 bg-green-50 px-3 py-1 rounded-full border border-green-100">
              {networkGrowthDelta > 0 ? `+${networkGrowthDelta} in 6 months` : 'No new in 6M'}
            </span>
          </div>
          <div className="h-40">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={networkGrowth} margin={{ top: 5, right: 5, left: -20, bottom: 0 }}>
                <defs>
                  <linearGradient id="netGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%"  stopColor="#8b5cf6" stopOpacity={0.2} />
                    <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0}   />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                <XAxis dataKey="month" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} allowDecimals={false} />
                <Tooltip contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }} />
                <Area type="monotone" dataKey="count" stroke="#8b5cf6" strokeWidth={2.5} fillOpacity={1} fill="url(#netGrad)" dot={{ fill: '#8b5cf6', strokeWidth: 0, r: 4 }} name="Distributors" />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      {/* Add Distributor Modal */}
      <AnimatePresence>
        {showAddModal && <AddDistributorModal onClose={() => setShowAddModal(false)} />}
      </AnimatePresence>
    </motion.div>
  );
}

function AddDistributorModal({ onClose }: { onClose: () => void }) {
  const dialogRef = useFocusTrap<HTMLDivElement>(true);
  const [step, setStep] = useState(1);

  const fields1 = [
    { label: 'Full Name',      placeholder: 'e.g. Rahul Sharma',          type: 'text'  },
    { label: 'Mobile Number',  placeholder: '+91 XXXXX XXXXX',            type: 'tel'   },
    { label: 'Email Address',  placeholder: 'distributor@example.com',    type: 'email' },
    { label: 'ARN Number',     placeholder: 'ARN-XXXXXX',                 type: 'text'  },
  ];
  const fields2 = [
    { label: 'IFSC Code',      placeholder: 'HDFC0001234',                type: 'text'  },
    { label: 'Account Number', placeholder: 'Enter account number',       type: 'password' },
    { label: 'Confirm Account',placeholder: 'Re-enter account number',    type: 'text'  },
    { label: 'Account Holder', placeholder: 'Name as on bank account',    type: 'text'  },
  ];

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="add-distributor-title"
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
          <h2 id="add-distributor-title" className="text-xl font-semibold text-slate-800 mb-1">Add Distributor</h2>
          <p className="text-sm text-slate-500 mb-6">Manually enrol a new distributor into the network</p>

          {/* Progress */}
          <div className="flex items-center gap-2 mb-8">
            {['Identity & Contact', 'Bank Details', 'Review'].map((s, i) => (
              <React.Fragment key={s}>
                <div className="flex items-center gap-1.5">
                  <div className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold flex-shrink-0 ${step > i + 1 ? 'bg-green-500 text-white' : step === i + 1 ? 'bg-[#0B1B3E] text-white' : 'bg-slate-200 text-slate-500'}`}>
                    {step > i + 1 ? '✓' : i + 1}
                  </div>
                  <span className={`text-xs font-medium hidden sm:block ${step === i + 1 ? 'text-slate-800' : 'text-slate-400'}`}>{s}</span>
                </div>
                {i < 2 && <div className={`flex-1 h-0.5 rounded ${step > i + 1 ? 'bg-green-500' : 'bg-slate-200'}`} />}
              </React.Fragment>
            ))}
          </div>

          <AnimatePresence mode="wait">
            {step === 1 && (
              <motion.div key="s1" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-4">
                {fields1.map(f => (
                  <div key={f.label}>
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">{f.label}</label>
                    <input type={f.type} placeholder={f.placeholder}
                      className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                  </div>
                ))}
              </motion.div>
            )}
            {step === 2 && (
              <motion.div key="s2" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-4">
                {fields2.map(f => (
                  <div key={f.label}>
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">{f.label}</label>
                    <input type={f.type} placeholder={f.placeholder}
                      className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                  </div>
                ))}
              </motion.div>
            )}
            {step === 3 && (
              <motion.div key="s3" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }}>
                <div className="bg-slate-50 rounded-2xl p-5 border border-slate-100 space-y-3">
                  {[
                    { label: 'Name',    value: 'New Distributor' },
                    { label: 'ARN',     value: 'ARN-XXXXXX'      },
                    { label: 'Email',   value: 'dist@example.com' },
                    { label: 'Bank',    value: 'HDFC Bank – HDFC0001234' },
                    { label: 'Status',  value: 'Pending Approval' },
                  ].map(({ label, value }) => (
                    <div key={label} className="flex justify-between text-sm">
                      <span className="text-slate-500 font-medium">{label}</span>
                      <span className="font-semibold text-slate-800">{value}</span>
                    </div>
                  ))}
                </div>
                <div className="mt-4 p-4 bg-blue-50 rounded-xl border border-blue-100 text-xs text-blue-700 leading-relaxed">
                  The distributor will be created in <strong>Pending Approval</strong> status. Login access will be enabled once manually approved.
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          <div className="mt-8 flex gap-3">
            {step > 1 && (
              <button onClick={() => setStep(s => s - 1)}
                className="px-5 py-2.5 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">
                Back
              </button>
            )}
            <button onClick={() => step < 3 ? setStep(s => s + 1) : onClose()}
              className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors">
              {step === 3 ? 'Confirm & Create' : 'Continue'}
            </button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
