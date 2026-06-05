import React, { useEffect, useMemo, useState } from 'react';
import { motion } from 'motion/react';
import {
  ArrowUpRight, Users, Building2, TrendingUp, ArrowDownUp, XCircle,
} from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, PieChart, Pie, Cell,
} from 'recharts';
import { apiFetch } from '../config/api';

// ─────────────────────────────────────────────────────────────────────────────
// Entity shapes — narrow projections of the backend DTOs containing only the
// fields the dashboard reads. Anything else from the server is ignored.
// ─────────────────────────────────────────────────────────────────────────────
interface Distributor {
  id: string;
  fullName: string;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | string;
  role: 'ADMIN' | 'MASTER_DISTRIBUTOR' | 'SUB_DISTRIBUTOR' | string;
  createdAt: string;
}

interface Investor {
  id: string;
  fullName: string;
  distributorId: string;
  createdAt: string;
}

type OrderType =
  | 'PURCHASE' | 'LUMPSUM_PURCHASE' | 'REDEMPTION' | 'SWITCH'
  | 'SIP' | 'SWP' | 'STP' | 'PAUSE_RECURRING_PLAN';

type OrderStatus =
  | 'DRAFT' | 'CREATED' | 'PENDING_INVESTOR_ACTION' | 'PAYMENT_PENDING'
  | 'SUBMITTED' | 'PROCESSING' | 'ACTIVE' | 'PAUSED' | 'SUCCESSFUL'
  | 'FAILED' | 'RETRY_AVAILABLE' | 'COMPLETED';

interface TransactionOrder {
  id: string;
  investorId: string;
  distributorId: string;
  transactionType: OrderType;
  orderStatus: OrderStatus;
  productCategory: 'MF' | 'SIF' | 'OTHER' | 'MUTUAL_FUND' | 'EQUITY' | null;
  amount: number | string;
  createdAt: string;
}

interface SpringPage<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ─────────────────────────────────────────────────────────────────────────────
// Visual maps (shared between this file's empty + populated states).
// ─────────────────────────────────────────────────────────────────────────────
const tierColors: Record<string, string> = {
  Platinum: 'bg-violet-50 text-violet-700',
  Gold:     'bg-amber-50 text-amber-700',
  Silver:   'bg-slate-100 text-slate-600',
  Bronze:   'bg-orange-50 text-orange-700',
};

const statusColors: Record<string, string> = {
  Successful: 'text-green-600',
  Pending:    'text-amber-600',
  Processing: 'text-blue-600',
  Failed:     'text-red-600',
};

// Status buckets that "count" as AUM-contributing — anything else is in-flight
// or rejected and excluded from totals so we don't double-count or mislead.
const AUM_OK_STATUSES: ReadonlySet<OrderStatus> = new Set<OrderStatus>([
  'SUCCESSFUL', 'ACTIVE', 'COMPLETED', 'PROCESSING', 'SUBMITTED',
]);
const OUTFLOW_TYPES: ReadonlySet<OrderType> = new Set<OrderType>([
  'REDEMPTION', 'SWP',
]);

const CACHE_KEY = 'admin_overview_v1';
const CACHE_TTL_MS = 5 * 60 * 1_000;

// ─────────────────────────────────────────────────────────────────────────────
// Formatting helpers — INR amounts with Indian-locale grouping, plus a small
// human-readable relative-time helper for the recent-transactions column.
// ─────────────────────────────────────────────────────────────────────────────
function formatCurrency(value: number): string {
  if (!isFinite(value)) return '₹0';
  const abs = Math.abs(value);
  if (abs >= 1e7) return `₹${(value / 1e7).toFixed(1)} Cr`;
  if (abs >= 1e5) return `₹${(value / 1e5).toFixed(1)} L`;
  return `₹${Math.round(value).toLocaleString('en-IN')}`;
}

function formatRupeesPlain(value: number): string {
  if (!isFinite(value)) return '₹0';
  return `₹${Math.round(value).toLocaleString('en-IN')}`;
}

function formatRelative(iso: string): string {
  const then = new Date(iso).getTime();
  if (!isFinite(then)) return '—';
  const diffMs = Date.now() - then;
  const mins = Math.floor(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
}

const TYPE_LABEL: Record<OrderType, string> = {
  PURCHASE: 'Purchase',
  LUMPSUM_PURCHASE: 'Lumpsum',
  REDEMPTION: 'Redemption',
  SWITCH: 'Switch',
  SIP: 'SIP',
  SWP: 'SWP',
  STP: 'STP',
  PAUSE_RECURRING_PLAN: 'Pause',
};

function humanizeStatus(s: OrderStatus): keyof typeof statusColors {
  if (s === 'SUCCESSFUL' || s === 'COMPLETED' || s === 'ACTIVE') return 'Successful';
  if (s === 'FAILED') return 'Failed';
  if (s === 'PROCESSING' || s === 'SUBMITTED' || s === 'PAYMENT_PENDING') return 'Processing';
  return 'Pending';
}

function bucketCategory(c: TransactionOrder['productCategory']): 'Mutual Fund' | 'Equity' | 'SIF' | 'Other' {
  if (c === 'MF' || c === 'MUTUAL_FUND') return 'Mutual Fund';
  if (c === 'EQUITY') return 'Equity';
  if (c === 'SIF') return 'SIF';
  return 'Other';
}

function tierFor(aum: number): 'Platinum' | 'Gold' | 'Silver' | 'Bronze' {
  if (aum >= 50e7) return 'Platinum';
  if (aum >= 10e7) return 'Gold';
  if (aum >= 1e7)  return 'Silver';
  return 'Bronze';
}

// ─────────────────────────────────────────────────────────────────────────────
// Skeleton — mirrors the loaded layout (header, 4 KPIs, 2/3 + 1/3 chart row,
// 2/3 + 1/3 bottom row). Module scope so React keeps a stable identity.
// ─────────────────────────────────────────────────────────────────────────────
function AdminOverviewSkeleton() {
  const pulse = 'bg-slate-100 animate-pulse rounded-xl';
  return (
    <div className="p-4 md:p-8 space-y-6">
      {/* header */}
      <div className="flex justify-between items-end">
        <div className="space-y-2">
          <div className={`${pulse} h-7 w-56`} />
          <div className={`${pulse} h-4 w-72`} />
        </div>
        <div className={`${pulse} h-9 w-48 rounded-lg`} />
      </div>

      {/* KPI cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-6">
        {[0, 1, 2, 3].map(i => (
          <div key={i} className="bg-white rounded-2xl border border-slate-200 p-6 space-y-3">
            <div className={`${pulse} h-3 w-28`} />
            <div className={`${pulse} h-8 w-24`} />
            <div className={`${pulse} h-3 w-32`} />
          </div>
        ))}
      </div>

      {/* chart row */}
      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 bg-white rounded-2xl border border-slate-200 p-6 space-y-4">
          <div className={`${pulse} h-5 w-40`} />
          <div className={`${pulse} h-52 w-full rounded-xl`} />
        </div>
        <div className="bg-white rounded-2xl border border-slate-200 p-6 space-y-4">
          <div className={`${pulse} h-5 w-40`} />
          <div className={`${pulse} h-36 w-full rounded-xl`} />
          <div className={`${pulse} h-3 w-full`} />
          <div className={`${pulse} h-3 w-2/3`} />
        </div>
      </div>

      {/* bottom row */}
      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 bg-white rounded-2xl border border-slate-200 overflow-hidden">
          <div className="p-5 border-b border-slate-100">
            <div className={`${pulse} h-5 w-40`} />
          </div>
          {[0, 1, 2, 3, 4].map(i => (
            <div key={i} className="px-5 py-3.5 border-b border-slate-50 flex gap-4">
              <div className={`${pulse} h-4 flex-1`} />
              <div className={`${pulse} h-4 flex-1`} />
              <div className={`${pulse} h-4 w-16`} />
            </div>
          ))}
        </div>
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
          <div className="p-5 border-b border-slate-100">
            <div className={`${pulse} h-5 w-36`} />
          </div>
          {[0, 1, 2, 3, 4].map(i => (
            <div key={i} className="px-5 py-4 border-b border-slate-50 flex gap-3 items-center">
              <div className={`${pulse} h-7 w-7 rounded-full`} />
              <div className="flex-1 space-y-2">
                <div className={`${pulse} h-4 w-32`} />
                <div className={`${pulse} h-3 w-24`} />
              </div>
              <div className={`${pulse} h-5 w-14`} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// Shared chrome so the error state still feels like the same page rather than
// dumping the user onto a bare error card with no context.
function AdminOverviewChrome({ children }: { children: React.ReactNode }) {
  return (
    <div className="p-4 md:p-8 space-y-6">
      <div className="flex flex-wrap justify-between items-end gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Master Overview</h1>
          <p className="text-slate-500 text-sm mt-1">Full network view across all distributors and investors</p>
        </div>
      </div>
      {children}
    </div>
  );
}

function AdminOverviewError() {
  return (
    <AdminOverviewChrome>
      <div className="bg-white rounded-2xl border border-red-200 py-16 text-center">
        <XCircle className="w-7 h-7 text-red-400 mx-auto mb-2" />
        <p className="text-sm font-semibold text-slate-700">Couldn’t load Master Overview</p>
        <p className="text-xs text-slate-400 mt-1">
          Please try again later.
        </p>
      </div>
    </AdminOverviewChrome>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// Cache payload — what we stash in sessionStorage to dedupe fetches within a
// 5-minute window.
// ─────────────────────────────────────────────────────────────────────────────
interface CachePayload {
  ts: number;
  distributors: Distributor[];
  investors: Investor[];
  investorsTotal: number;
  recentOrders: TransactionOrder[];
  allOrders: TransactionOrder[];
}

interface AdminOverviewProps {
  userData?: {
    id?: string;
    distributorId?: string;
    role?: string;
  };
}

export default function AdminOverview({ userData }: AdminOverviewProps = {}) {
  const userId = userData?.id ?? userData?.distributorId;
  const userRole = (userData?.role ?? '').trim().toUpperCase();
  const isAdmin = userRole === 'ADMIN';
  const [distributors, setDistributors] = useState<Distributor[]>([]);
  const [investors, setInvestors] = useState<Investor[]>([]);
  const [investorsTotal, setInvestorsTotal] = useState(0);
  const [recentOrders, setRecentOrders] = useState<TransactionOrder[]>([]);
  const [allOrders, setAllOrders] = useState<TransactionOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const distributorMap = useMemo(() => {
    const m = new Map<string, Distributor>();
    distributors.forEach(d => m.set(d.id, d));
    return m;
  }, [distributors]);

  const investorMap = useMemo(() => {
    const m = new Map<string, Investor>();
    investors.forEach(i => m.set(i.id, i));
    return m;
  }, [investors]);

  const investorCountByDistributor = useMemo(() => {
    const m = new Map<string, number>();
    investors.forEach(i => m.set(i.distributorId, (m.get(i.distributorId) ?? 0) + 1));
    return m;
  }, [investors]);

  useEffect(() => {
    let cancelled = false;

    // ── Session-cache short-circuit ─────────────────────────────────────────
    try {
      const raw = sessionStorage.getItem(CACHE_KEY);
      if (raw) {
        const cached = JSON.parse(raw) as CachePayload;
        if (Date.now() - cached.ts < CACHE_TTL_MS) {
          setDistributors(cached.distributors || []);
          setInvestors(cached.investors || []);
          setInvestorsTotal(cached.investorsTotal || 0);
          setRecentOrders(cached.recentOrders || []);
          setAllOrders(cached.allOrders || []);
          setLoadError(false);
          setLoading(false);
          return;
        }
      }
    } catch {
      // Malformed cache — ignore and fall through to a fresh fetch.
    }

    const headers = { 'Content-Type': 'application/json' };

    // ADMIN can list every distributor; MASTER_DISTRIBUTOR only sees their
    // sub-distributor network and is forbidden from /distributors (returns 403).
    const distributorsUrl = isAdmin
      ? '/distributors'
      : userId
      ? `/distributors/sub-distributors?requesterId=${encodeURIComponent(userId)}`
      : null;

    if (!distributorsUrl) {
      setLoadError(true);
      setLoading(false);
      return;
    }

    // For MASTER_DISTRIBUTOR, /distributors/sub-distributors returns only the
    // master's downstream nodes — the master themselves is not in that list,
    // which means their own orders/name would show "—" in the panels below.
    // Fetch their own record alongside so the network view includes self.
    const selfUrl = !isAdmin && userId
      ? `/distributors/${encodeURIComponent(userId)}`
      : null;

    (async () => {
      try {
        const [distRes, selfRes, invRes, recentOrdersRes, allOrdersRes] = await Promise.all([
          apiFetch(distributorsUrl,                 { headers }),
          selfUrl ? apiFetch(selfUrl,               { headers }) : Promise.resolve(null),
          apiFetch('/investors?page=0&size=500',    { headers }),
          apiFetch('/orders?page=0&size=5',         { headers }),
          apiFetch('/orders?page=0&size=500',       { headers }),
        ]);

        if (!distRes.ok || !invRes.ok || !recentOrdersRes.ok || !allOrdersRes.ok) {
          throw new Error('One or more overview endpoints failed');
        }
        if (selfRes && !selfRes.ok) {
          throw new Error('Could not load own distributor record');
        }

        const [distJson, selfJson, invJson, recentJson, allJson] = await Promise.all([
          distRes.json() as Promise<Distributor[]>,
          selfRes ? selfRes.json() as Promise<Distributor> : Promise.resolve(null),
          invRes.json() as Promise<SpringPage<Investor>>,
          recentOrdersRes.json() as Promise<SpringPage<TransactionOrder>>,
          allOrdersRes.json() as Promise<SpringPage<TransactionOrder>>,
        ]);

        if (cancelled) return;

        // Merge self into the list when present, de-duped by id (in case the
        // backend later starts returning the master themselves).
        const baseList = distJson || [];
        const nextDistributors = selfJson
          ? [selfJson, ...baseList.filter(d => d.id !== selfJson.id)]
          : baseList;
        const nextInvestors = invJson?.content ?? [];
        const nextInvestorsTotal = invJson?.totalElements ?? nextInvestors.length;
        const nextRecent = recentJson?.content ?? [];
        const nextAll = allJson?.content ?? [];

        setDistributors(nextDistributors);
        setInvestors(nextInvestors);
        setInvestorsTotal(nextInvestorsTotal);
        setRecentOrders(nextRecent);
        setAllOrders(nextAll);
        setLoadError(false);
        setLoading(false);

        try {
          const payload: CachePayload = {
            ts: Date.now(),
            distributors: nextDistributors,
            investors: nextInvestors,
            investorsTotal: nextInvestorsTotal,
            recentOrders: nextRecent,
            allOrders: nextAll,
          };
          sessionStorage.setItem(CACHE_KEY, JSON.stringify(payload));
        } catch {
          // Quota / private browsing — fine, just skip caching this run.
        }
      } catch (err) {
        if (cancelled) return;
        console.error('Failed to fetch Admin Overview', err);
        setLoadError(true);
        setLoading(false);
      }
    })();

    return () => { cancelled = true; };
  }, [isAdmin, userId]);

  // ── Derived: KPI #1 (Total AUM) ────────────────────────────────────────────
  const totalAum = useMemo(() => {
    let sum = 0;
    for (const o of allOrders) {
      if (!AUM_OK_STATUSES.has(o.orderStatus)) continue;
      if (OUTFLOW_TYPES.has(o.transactionType)) continue;
      sum += Number(o.amount) || 0;
    }
    return sum;
  }, [allOrders]);

  // ── Derived: KPI #2 sub-text — investors whose record was created in the
  // last 30 days. We now have the full investor list, so this is exact, not
  // an order-activity proxy. (If the caption were derived from recent order
  // activity it would mislead: an investor who placed an order yesterday but
  // was onboarded 2 years ago is not a "new" investor this month.)
  const investorsCreatedLast30Days = useMemo(() => {
    const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
    let n = 0;
    for (const inv of investors) {
      const t = new Date(inv.createdAt).getTime();
      if (isFinite(t) && t >= cutoff) n++;
    }
    return n;
  }, [investors]);

  // ── Derived: KPI #3 sub-text ───────────────────────────────────────────────
  const distributorsPending = useMemo(
    () => distributors.filter(d => d.status === 'PENDING_APPROVAL').length,
    [distributors],
  );

  // ── Derived: KPI #4 (Net Inflow for current month + delta vs last month) ──
  const { netInflowThisMonth, netInflowPctDelta } = useMemo(() => {
    const now = new Date();
    const thisMonthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime();
    const lastMonthStart = new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime();

    let thisIn = 0, thisOut = 0;
    let lastIn = 0, lastOut = 0;
    for (const o of allOrders) {
      if (!AUM_OK_STATUSES.has(o.orderStatus)) continue;
      const t = new Date(o.createdAt).getTime();
      if (!isFinite(t)) continue;
      const amt = Number(o.amount) || 0;
      const isOut = OUTFLOW_TYPES.has(o.transactionType);
      if (t >= thisMonthStart) {
        if (isOut) thisOut += amt; else thisIn += amt;
      } else if (t >= lastMonthStart && t < thisMonthStart) {
        if (isOut) lastOut += amt; else lastIn += amt;
      }
    }
    const thisNet = thisIn - thisOut;
    const lastNet = lastIn - lastOut;
    const delta = lastNet > 0 ? ((thisNet - lastNet) / lastNet) * 100 : null;
    return { netInflowThisMonth: thisNet, netInflowPctDelta: delta };
  }, [allOrders]);

  // ── Derived: AUM trend (6 rolling months, cumulative qualifying amount) ───
  const aumTrend = useMemo(() => {
    const now = new Date();
    // Build month buckets: oldest first
    const months: { key: string; label: string; end: number }[] = [];
    for (let i = 5; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i + 1, 0, 23, 59, 59, 999);
      const label = new Date(now.getFullYear(), now.getMonth() - i, 1)
        .toLocaleDateString('en-US', { month: 'short' });
      months.push({
        key: `${d.getFullYear()}-${d.getMonth()}`,
        label,
        end: d.getTime(),
      });
    }
    const qualifying = allOrders.filter(o =>
      AUM_OK_STATUSES.has(o.orderStatus) && !OUTFLOW_TYPES.has(o.transactionType),
    );
    return months.map(m => {
      const aumRupees = qualifying.reduce((acc, o) => {
        const t = new Date(o.createdAt).getTime();
        return isFinite(t) && t <= m.end ? acc + (Number(o.amount) || 0) : acc;
      }, 0);
      return { month: m.label, aum: Number((aumRupees / 1e7).toFixed(2)) };
    });
  }, [allOrders]);

  const aumTrendHasData = useMemo(
    () => aumTrend.some(p => p.aum > 0),
    [aumTrend],
  );

  const aumSixMonthPctGrowth = useMemo(() => {
    if (!aumTrendHasData) return null;
    const first = aumTrend.find(p => p.aum > 0)?.aum ?? 0;
    const last = aumTrend[aumTrend.length - 1]?.aum ?? 0;
    if (first <= 0) return null;
    return ((last - first) / first) * 100;
  }, [aumTrend, aumTrendHasData]);

  // ── Derived: asset-class distribution (pie) ────────────────────────────────
  const assetDist = useMemo(() => {
    const buckets = new Map<string, number>();
    for (const o of allOrders) {
      if (!AUM_OK_STATUSES.has(o.orderStatus)) continue;
      if (OUTFLOW_TYPES.has(o.transactionType)) continue;
      const k = bucketCategory(o.productCategory);
      buckets.set(k, (buckets.get(k) ?? 0) + (Number(o.amount) || 0));
    }
    const total = Array.from(buckets.values()).reduce((a, b) => a + b, 0);
    if (total <= 0) return [] as { name: string; value: number; color: string }[];

    const palette = ['#3b82f6', '#22c55e', '#8b5cf6', '#f59e0b'];
    return Array.from(buckets.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4)
      .map(([name, rupees], i) => ({
        name,
        value: Math.round((rupees / total) * 100),
        color: palette[i],
      }));
  }, [allOrders]);

  // ── Derived: top 5 distributors by AUM ────────────────────────────────────
  const topDistributors = useMemo(() => {
    const aumByDistributor = new Map<string, number>();
    for (const o of allOrders) {
      if (!AUM_OK_STATUSES.has(o.orderStatus)) continue;
      if (OUTFLOW_TYPES.has(o.transactionType)) continue;
      if (!o.distributorId) continue;
      aumByDistributor.set(
        o.distributorId,
        (aumByDistributor.get(o.distributorId) ?? 0) + (Number(o.amount) || 0),
      );
    }
    return distributors
      .map(d => ({
        id: d.id,
        name: d.fullName,
        aum: aumByDistributor.get(d.id) ?? 0,
        investors: investorCountByDistributor.get(d.id) ?? 0,
      }))
      .sort((a, b) => b.aum - a.aum)
      .slice(0, 5)
      .map(d => ({ ...d, tier: tierFor(d.aum) }));
  }, [distributors, allOrders, investorCountByDistributor]);

  // ── Render guards (skeleton → error → real) ───────────────────────────────
  if (loading) return <AdminOverviewSkeleton />;
  if (loadError) return <AdminOverviewError />;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-4 md:p-8 space-y-6">
      <div className="flex flex-wrap justify-between items-end gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Master Overview</h1>
          <p className="text-slate-500 text-sm mt-1">Full network view across all distributors and investors</p>
        </div>
        {/* Inert toggle — visual parity only; wiring is out of scope. */}
        <div className="flex gap-1 text-xs font-semibold text-slate-500 uppercase tracking-wider bg-slate-100 p-1 rounded-lg">
          <button type="button" className="px-3 py-1.5 bg-white shadow-sm rounded text-slate-900">Today</button>
          <button type="button" className="px-3 py-1.5 hover:text-slate-700 rounded">Monthly</button>
          <button type="button" className="px-3 py-1.5 hover:text-slate-700 rounded">YTD</button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-6">
        {[
          {
            label: 'Total AUM',
            value: formatCurrency(totalAum),
            trend: 'Across all distributors',
            trendColor: 'text-slate-500',
            icon: <TrendingUp className="w-5 h-5 text-green-400" />,
          },
          {
            label: 'Total Investors',
            value: investorsTotal.toLocaleString('en-IN'),
            trend: investorsCreatedLast30Days > 0
              ? `+${investorsCreatedLast30Days} this month`
              : 'Network growing',
            trendColor: investorsCreatedLast30Days > 0 ? 'text-blue-500' : 'text-slate-500',
            icon: <Users className="w-5 h-5 text-blue-400" />,
          },
          {
            label: 'Total Distributors',
            value: distributors.length.toLocaleString('en-IN'),
            trend: distributorsPending > 0
              ? `${distributorsPending} pending approval`
              : 'Active network',
            trendColor: distributorsPending > 0 ? 'text-amber-500' : 'text-slate-500',
            icon: <Building2 className="w-5 h-5 text-amber-400" />,
          },
          {
            label: 'Net Inflow (this month)',
            value: formatCurrency(netInflowThisMonth),
            trend: netInflowPctDelta === null
              ? 'No prior data'
              : `${netInflowPctDelta >= 0 ? '↑' : '↓'} ${Math.abs(netInflowPctDelta).toFixed(1)}% vs last month`,
            trendColor: netInflowPctDelta === null
              ? 'text-slate-500'
              : netInflowPctDelta >= 0 ? 'text-green-500' : 'text-red-500',
            icon: <ArrowDownUp className="w-5 h-5 text-emerald-400" />,
          },
        ].map(k => (
          <div key={k.label} className="bg-white p-6 rounded-2xl shadow-sm border border-slate-200">
            <div className="flex justify-between items-start mb-3">
              <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">{k.label}</p>
              {k.icon}
            </div>
            <p className="text-2xl font-semibold text-slate-800">{k.value}</p>
            <p className={`text-xs mt-3 font-medium ${k.trendColor}`}>{k.trend}</p>
          </div>
        ))}
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-3 gap-6">
        {/* AUM Trend */}
        <div className="col-span-2 bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
          <div className="flex justify-between items-center mb-6">
            <div>
              <h2 className="font-semibold text-slate-800">AUM Trend</h2>
              <p className="text-xs text-slate-500 mt-0.5">Total AUM across all distributors (₹ Cr)</p>
            </div>
            {aumTrendHasData && aumSixMonthPctGrowth !== null && (
              <span className="text-xs font-semibold text-green-600 bg-green-50 px-3 py-1 rounded-full border border-green-100">
                {aumSixMonthPctGrowth >= 0 ? '↑' : '↓'} {Math.abs(aumSixMonthPctGrowth).toFixed(0)}% over 6M
              </span>
            )}
          </div>
          <div className="h-52">
            {aumTrendHasData ? (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={aumTrend} margin={{ top: 5, right: 5, left: -20, bottom: 0 }}>
                  <defs>
                    <linearGradient id="aumGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%"  stopColor="#0B1B3E" stopOpacity={0.18} />
                      <stop offset="95%" stopColor="#0B1B3E" stopOpacity={0}    />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                  <XAxis dataKey="month" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                  <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                  <Tooltip
                    formatter={(v: number) => [`₹${v} Cr`, 'AUM']}
                    contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }}
                  />
                  <Area
                    type="monotone"
                    dataKey="aum"
                    stroke="#0B1B3E"
                    strokeWidth={2.5}
                    fillOpacity={1}
                    fill="url(#aumGrad)"
                    dot={{ fill: '#0B1B3E', strokeWidth: 0, r: 4 }}
                  />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <div className="h-full flex flex-col items-center justify-center text-center">
                <TrendingUp className="w-7 h-7 text-slate-300 mb-2" />
                <p className="text-xs text-slate-500 max-w-xs">
                  Not enough data yet — AUM trend will populate as orders are placed.
                </p>
              </div>
            )}
          </div>
        </div>

        {/* Asset Distribution */}
        <div className="bg-[#0B1B3E] rounded-2xl p-6 text-white flex flex-col relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-blue-500 opacity-10 rounded-full blur-2xl" />
          <h2 className="font-semibold mb-1">Asset Distribution</h2>
          <p className="text-xs text-blue-300 mb-4">% of total AUM by class</p>
          {assetDist.length > 0 ? (
            <>
              <div className="h-36">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={assetDist} cx="50%" cy="50%" innerRadius={42} outerRadius={62} paddingAngle={3} dataKey="value" stroke="none">
                      {assetDist.map((e, i) => <Cell key={i} fill={e.color} />)}
                    </Pie>
                    <Tooltip
                      contentStyle={{ backgroundColor: '#1A3066', border: 'none', borderRadius: '8px', color: 'white', fontSize: '11px' }}
                      itemStyle={{ color: '#fff' }}
                    />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="mt-3 space-y-2.5">
                {assetDist.map(item => (
                  <div key={item.name} className="flex justify-between items-center text-xs">
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: item.color }} />
                      <span className="text-blue-100">{item.name}</span>
                    </div>
                    <span className="font-semibold font-mono">{item.value}%</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="h-36 flex items-center justify-center">
              <p className="text-xs text-white/50">No order data yet</p>
            </div>
          )}
        </div>
      </div>

      {/* Bottom row */}
      <div className="grid grid-cols-3 gap-6">
        {/* Recent Transactions */}
        <div className="col-span-2 bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div className="p-5 border-b border-slate-100 flex justify-between items-center">
            <h2 className="font-semibold text-slate-800">Recent Transactions</h2>
            <button type="button" className="text-[#0B1B3E] text-xs font-semibold flex items-center gap-1 hover:underline">
              View All <ArrowUpRight className="w-3 h-3" />
            </button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead className="bg-slate-50 text-[10px] uppercase text-slate-500 font-semibold tracking-wider">
                <tr>
                  <th className="px-5 py-3">Investor</th>
                  <th className="px-5 py-3">Distributor</th>
                  <th className="px-5 py-3">Type / Amount</th>
                  <th className="px-5 py-3 text-right">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {recentOrders.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="px-5 py-12 text-center text-sm text-slate-400">
                      No transactions yet — orders placed via Ledger will appear here.
                    </td>
                  </tr>
                ) : recentOrders.map(o => {
                  const distName = distributorMap.get(o.distributorId)?.fullName ?? '—';
                  const investorName = investorMap.get(o.investorId)?.fullName;
                  const investorLabel = investorName
                    ?? (o.investorId ? `…${o.investorId.slice(-6)}` : '—');
                  const status = humanizeStatus(o.orderStatus);
                  return (
                    <tr key={o.id} className="hover:bg-slate-50 transition-colors cursor-pointer">
                      <td className="px-5 py-3.5 text-sm font-semibold text-slate-800">{investorLabel}</td>
                      <td className="px-5 py-3.5 text-xs text-slate-500">{distName}</td>
                      <td className="px-5 py-3.5">
                        <div className="text-sm font-mono font-medium text-slate-700">
                          {formatRupeesPlain(Number(o.amount) || 0)}
                        </div>
                        <div className="text-[10px] text-slate-400 mt-0.5">
                          {TYPE_LABEL[o.transactionType] ?? o.transactionType}
                        </div>
                      </td>
                      <td className="px-5 py-3.5 text-right">
                        <span className={`text-xs font-semibold ${statusColors[status] ?? 'text-slate-500'}`}>{status}</span>
                        <div className="text-[10px] text-slate-400 mt-0.5">{formatRelative(o.createdAt)}</div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>

        {/* Top Distributors */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div className="p-5 border-b border-slate-100 flex justify-between items-center">
            <h2 className="font-semibold text-slate-800">Top Distributors</h2>
            <button type="button" className="text-[#0B1B3E] text-xs font-semibold flex items-center gap-1 hover:underline">
              View All <ArrowUpRight className="w-3 h-3" />
            </button>
          </div>
          {topDistributors.length === 0 ? (
            <div className="py-12 text-center text-sm text-slate-400">
              No distributors onboarded yet
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {topDistributors.map((d, i) => (
                <div key={d.id} className="px-5 py-4 flex items-center gap-3 hover:bg-slate-50 transition-colors cursor-pointer">
                  <div className="w-7 h-7 rounded-full bg-slate-100 flex items-center justify-center text-xs font-bold text-slate-600 flex-shrink-0">
                    {i + 1}
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-semibold text-slate-800 truncate">{d.name}</p>
                    <p className="text-xs text-slate-500 mt-0.5">
                      {formatCurrency(d.aum)}
                      {' · '}
                      {d.investors} {d.investors === 1 ? 'investor' : 'investors'}
                    </p>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <span className={`text-[10px] font-bold px-2 py-0.5 rounded ${tierColors[d.tier] ?? 'bg-slate-100 text-slate-600'}`}>
                      {d.tier}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </motion.div>
  );
}
