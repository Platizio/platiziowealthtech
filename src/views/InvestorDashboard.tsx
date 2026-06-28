import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  PieChart, Pie, Cell, Tooltip, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Legend,
} from 'recharts';
import {
  Wallet, TrendingUp, TrendingDown, Activity, Percent, CalendarClock,
  Loader2, AlertCircle, PieChart as PieChartIcon, RefreshCw, Layers, Hash, Info,
} from 'lucide-react';
import EmptyState from '../components/EmptyState';
import { apiFetch } from '../config/api';

/**
 * Phase-3 investor dashboard (FR-DSH). Reads the investor-scoped dashboard endpoint
 * and renders a portfolio summary, allocation/performance charts and a detailed
 * per-holding breakdown (folio, AMC, SIP, average cost NAV, latest NAV, invested,
 * current value, absolute/percent/1-day returns and money-weighted XIRR).
 *
 * Locked decision (carried from Phase-2 holdings): the backend NEVER fabricates a
 * value when valuation inputs are missing. When a holding's dataQuality is STALE or
 * UNAVAILABLE we surface every fact we DO have (invested, units, cost, folio) and label
 * the valuation as "pending NAV" instead of showing a fabricated number.
 */

// ── Field shape (aligns to InvestorDashboardResponse.DashboardHolding) ────────
interface DashboardHolding {
  productSchemeId?: string;
  schemeName?: string;
  amcName?: string;
  category?: string;
  folio?: string;
  sipName?: string;
  units?: number | null;
  latestNav?: number | null;
  navAsOf?: string | null;
  averageCostNav?: number | null;
  invested?: number | null;
  currentValue?: number | null;
  absoluteReturn?: number | null;
  percentReturn?: number | null;
  oneDayReturn?: number | null;
  oneDayReturnPercent?: number | null;
  xirr?: number | null;
  dataQuality?: string; // OK | STALE | UNAVAILABLE
}
interface DashboardTotals {
  totalInvested?: number | null;
  totalCurrentValue?: number | null;
  totalReturn?: number | null;
  totalReturnPercent?: number | null;
  totalOneDayReturn?: number | null;
  portfolioXirr?: number | null;
}
interface DashboardPayload {
  holdings?: DashboardHolding[];
  totals?: DashboardTotals;
}

const PIE_COLORS = ['#0B1B3E', '#1A3066', '#3B82F6', '#8B5CF6', '#10B981', '#F59E0B', '#EF4444', '#06B6D4'];

// ── Formatting helpers ───────────────────────────────────────────────────────
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const numOrNull = (v: unknown): number | null => {
  if (isNum(v)) return v;
  if (typeof v === 'string' && v.trim() !== '') {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
};
const fmtMoney = (v?: number | null): string | null => {
  if (!isNum(v)) return null;
  const abs = Math.abs(v);
  if (abs >= 10_000_000) return `₹${(v / 10_000_000).toFixed(2)}Cr`;
  if (abs >= 100_000) return `₹${(v / 100_000).toFixed(2)}L`;
  return `₹${v.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
};
const fmtMoneyExact = (v?: number | null): string | null =>
  isNum(v) ? `₹${v.toLocaleString('en-IN', { maximumFractionDigits: 2 })}` : null;
const fmtUnits = (v?: number | null): string | null =>
  isNum(v) ? v.toLocaleString('en-IN', { maximumFractionDigits: 4 }) : null;
const fmtPercent = (v?: number | null): string | null =>
  isNum(v) ? `${v > 0 ? '+' : ''}${v.toFixed(2)}%` : null;
const fmtNav = (v?: number | null): string | null =>
  isNum(v) ? `₹${v.toLocaleString('en-IN', { minimumFractionDigits: 4, maximumFractionDigits: 4 })}` : null;
const fmtDate = (v?: string | null): string | null => {
  if (!v) return null;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
};
const gainColor = (v?: number | null): string => {
  if (!isNum(v)) return 'text-slate-400';
  if (v > 0) return 'text-emerald-600';
  if (v < 0) return 'text-red-600';
  return 'text-slate-600';
};
const normalizeQuality = (q?: string) => String(q || 'OK').trim().toUpperCase();
const isUsableQuality = (q?: string) => normalizeQuality(q) === 'OK';

// Field aliasing — read whatever the backend sent; never coerce missing → 0.
const hScheme = (h: DashboardHolding) => h.schemeName || 'Scheme name unavailable';
const hFolio = (h: DashboardHolding) => h.folio || null;
const hInvested = (h: DashboardHolding) => numOrNull(h.invested);
const hCurrent = (h: DashboardHolding) => numOrNull(h.currentValue);

/** Small labelled metric used inside a holding card. */
function Metric({ label, value, valueClass, mono }: { label: string; value: string | null; valueClass?: string; mono?: boolean }) {
  return (
    <div>
      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</p>
      <p className={`mt-0.5 text-sm font-semibold ${value == null ? 'text-slate-300' : valueClass ?? 'text-slate-800'} ${mono ? 'font-mono' : ''}`}>
        {value ?? '—'}
      </p>
    </div>
  );
}

export default function InvestorDashboard() {
  const [data, setData] = useState<DashboardPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  const loadDashboard = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true); else setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/dashboard');
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || `Unable to load your dashboard (${res.status}).`);
      }
      const payload: DashboardPayload = Array.isArray(body)
        ? { holdings: body as DashboardHolding[] }
        : (body as DashboardPayload) || {};
      setData(payload);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load your dashboard.');
      setData(null);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { void loadDashboard(); }, [loadDashboard]);

  const holdings = useMemo(() => data?.holdings ?? [], [data]);
  const totals = data?.totals ?? {};

  const valuedCount = useMemo(() => holdings.filter((h) => isUsableQuality(h.dataQuality)).length, [holdings]);
  const pendingCount = holdings.length - valuedCount;

  // Most recent NAV date across all holdings (drives the header freshness chip).
  const latestNavAsOf = useMemo(() => {
    let best: string | null = null;
    let bestTime = -Infinity;
    for (const h of holdings) {
      if (!h.navAsOf) continue;
      const t = new Date(h.navAsOf).getTime();
      if (Number.isNaN(t)) continue;
      if (t > bestTime) { bestTime = t; best = h.navAsOf; }
    }
    return best;
  }, [holdings]);

  const allocation = useMemo(() => holdings
    .map((h) => ({ name: hScheme(h), value: hCurrent(h) ?? hInvested(h) }))
    .filter((d): d is { name: string; value: number } => isNum(d.value) && d.value > 0)
    .sort((a, b) => b.value - a.value), [holdings]);

  const investedVsCurrent = useMemo(() => holdings
    .map((h) => {
      const invested = hInvested(h);
      const current = hCurrent(h);
      return isNum(invested) ? { name: hScheme(h), Invested: invested, Current: isNum(current) ? current : invested } : null;
    })
    .filter((d): d is { name: string; Invested: number; Current: number } => d != null)
    .slice(0, 8), [holdings]);

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading your portfolio…</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="mx-auto max-w-6xl p-8">
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <div className="flex items-start gap-2.5">
            <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
            <div>
              <p className="font-semibold">Dashboard unavailable</p>
              <p className="mt-1 text-sm">{error}</p>
              <button onClick={() => void loadDashboard()} className="mt-4 text-sm font-semibold text-red-600 hover:underline">Try again</button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (holdings.length === 0) {
    return (
      <div className="mx-auto max-w-6xl p-8">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-slate-800">Your portfolio</h1>
          <p className="mt-1 text-sm text-slate-500">A live view of your holdings, returns and allocation.</p>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white">
          <EmptyState icon={Wallet} title="No holdings yet"
            subtitle="Once your investments are confirmed, your portfolio value, returns and allocation will appear here." />
        </div>
      </div>
    );
  }

  const totalInvested = numOrNull(totals.totalInvested);
  const totalCurrentValue = numOrNull(totals.totalCurrentValue);
  const totalReturn = numOrNull(totals.totalReturn);
  const totalReturnPct = numOrNull(totals.totalReturnPercent);
  const totalOneDay = numOrNull(totals.totalOneDayReturn);
  const portfolioXirr = numOrNull(totals.portfolioXirr);

  const statCards: Array<{ label: string; value: string | null; icon: ReactNode; bg: string; iconColor: string; valueColor?: string; sub?: { text: string | null; color: string } }> = [
    { label: 'Total invested', value: fmtMoney(totalInvested), icon: <Wallet className="h-5 w-5" />, bg: 'bg-slate-50', iconColor: 'text-slate-600' },
    { label: 'Current value', value: fmtMoney(totalCurrentValue), icon: <Activity className="h-5 w-5" />, bg: 'bg-blue-50', iconColor: 'text-blue-600' },
    {
      label: 'Total returns', value: fmtMoney(totalReturn),
      icon: isNum(totalReturn) && totalReturn < 0 ? <TrendingDown className="h-5 w-5" /> : <TrendingUp className="h-5 w-5" />,
      bg: isNum(totalReturn) && totalReturn < 0 ? 'bg-red-50' : 'bg-emerald-50',
      iconColor: isNum(totalReturn) && totalReturn < 0 ? 'text-red-600' : 'text-emerald-600',
      valueColor: gainColor(totalReturn), sub: { text: fmtPercent(totalReturnPct), color: gainColor(totalReturnPct) },
    },
    { label: "Today's change", value: fmtMoney(totalOneDay), icon: <CalendarClock className="h-5 w-5" />, bg: 'bg-amber-50', iconColor: 'text-amber-600', valueColor: gainColor(totalOneDay) },
    { label: 'Portfolio XIRR', value: fmtPercent(portfolioXirr), icon: <Percent className="h-5 w-5" />, bg: 'bg-violet-50', iconColor: 'text-violet-600', valueColor: gainColor(portfolioXirr) },
  ];

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-8">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Your portfolio</h1>
          <p className="mt-1 flex flex-wrap items-center gap-x-1.5 text-sm text-slate-500">
            <span>
              {holdings.length} holding{holdings.length === 1 ? '' : 's'}
              {pendingCount > 0 && <span className="text-amber-600"> · {pendingCount} awaiting live valuation</span>}
            </span>
            {latestNavAsOf && (
              <span className="inline-flex items-center gap-1 rounded-md bg-slate-50 px-1.5 py-0.5 text-[11px] font-medium text-slate-500">
                <CalendarClock className="h-3 w-3 text-slate-400" /> NAVs as of {fmtDate(latestNavAsOf)}
              </span>
            )}
          </p>
        </div>
        <button onClick={() => void loadDashboard(true)} disabled={refreshing}
          className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-60">
          <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? 'animate-spin' : ''}`} /> Refresh
        </button>
      </div>

      {/* Summary stat cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {statCards.map((card) => (
          <div key={card.label} className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
            <div className={`mb-3 flex h-9 w-9 items-center justify-center rounded-xl ${card.bg} ${card.iconColor}`}>{card.icon}</div>
            <p className="mb-1 text-[10px] font-bold uppercase tracking-wider text-slate-400">{card.label}</p>
            <p className={`text-2xl font-bold ${card.valueColor ?? 'text-slate-800'}`}>
              {card.value ?? <span className="text-base font-semibold text-slate-300">Awaiting NAV</span>}
            </p>
            {card.sub && card.sub.text != null && <p className={`mt-1 text-xs font-semibold ${card.sub.color}`}>{card.sub.text}</p>}
          </div>
        ))}
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm lg:col-span-2">
          <h2 className="mb-4 text-sm font-semibold text-slate-700">Allocation by scheme</h2>
          {allocation.length > 0 ? (
            <>
              <ResponsiveContainer width="100%" height={200}>
                <PieChart>
                  <Pie data={allocation} cx="50%" cy="50%" innerRadius={55} outerRadius={85} dataKey="value" paddingAngle={3}>
                    {allocation.map((_, i) => <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />)}
                  </Pie>
                  <Tooltip formatter={(v: number | string) => fmtMoney(Number(v)) ?? '—'} />
                </PieChart>
              </ResponsiveContainer>
              <div className="mt-3 space-y-2">
                {allocation.slice(0, 6).map((d, i) => (
                  <div key={d.name} className="flex items-center justify-between gap-2 text-xs">
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span className="inline-block h-2.5 w-2.5 flex-shrink-0 rounded-full" style={{ background: PIE_COLORS[i % PIE_COLORS.length] }} />
                      <span className="truncate text-slate-600">{d.name}</span>
                    </span>
                    <span className="flex-shrink-0 font-semibold text-slate-700">{fmtMoney(d.value)}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="flex h-52 flex-col items-center justify-center gap-2 text-slate-300"><PieChartIcon className="h-8 w-8" /><p className="text-sm">No valued holdings to chart yet</p></div>
          )}
        </div>

        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm lg:col-span-3">
          <h2 className="mb-4 text-sm font-semibold text-slate-700">Invested vs current value</h2>
          {investedVsCurrent.length > 0 ? (
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={investedVsCurrent} margin={{ top: 5, right: 5, left: -10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#E2E8F0" vertical={false} />
                <XAxis dataKey="name" tick={{ fontSize: 10, fill: '#94A3B8' }} tickFormatter={(v: string) => (v.length > 12 ? `${v.slice(0, 12)}…` : v)} interval={0} angle={-15} textAnchor="end" height={50} />
                <YAxis tick={{ fontSize: 10, fill: '#94A3B8' }} tickFormatter={(v: number) => fmtMoney(v) ?? ''} />
                <Tooltip formatter={(v: number | string) => fmtMoney(Number(v)) ?? '—'} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="Invested" fill="#94A3B8" radius={[4, 4, 0, 0]} />
                <Bar dataKey="Current" fill="#0B1B3E" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <div className="flex h-52 flex-col items-center justify-center gap-2 text-slate-300"><Activity className="h-8 w-8" /><p className="text-sm">Not enough valued holdings to compare</p></div>
          )}
        </div>
      </div>

      {/* Detailed holdings */}
      <div>
        <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-slate-700"><Layers className="h-4 w-4 text-slate-400" /> Holdings</h2>
        <div className="space-y-4">
          {holdings.map((h, i) => {
            const quality = normalizeQuality(h.dataQuality);
            const pending = quality === 'UNAVAILABLE';
            const atCost = quality === 'STALE';
            const pct = numOrNull(h.percentReturn);
            const abs = numOrNull(h.absoluteReturn);
            return (
              <div key={`${hFolio(h) ?? hScheme(h)}-${i}`} className="overflow-hidden rounded-2xl border border-slate-100 bg-white shadow-sm">
                {/* Card header */}
                <div className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-50 px-5 py-4">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-slate-800">{hScheme(h)}</p>
                    <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px]">
                      {h.amcName && h.amcName !== '—' && <span className="text-slate-500">{h.amcName}</span>}
                      {h.category && <span className="rounded bg-slate-100 px-1.5 py-0.5 font-medium text-slate-500">{h.category}</span>}
                      {h.sipName && <span className="rounded bg-violet-50 px-1.5 py-0.5 font-medium text-violet-600">SIP · {h.sipName}</span>}
                    </div>
                  </div>
                  <div className="flex flex-col items-end gap-1">
                    {hFolio(h) ? (
                      <span className="flex items-center gap-1 rounded-md bg-slate-50 px-2 py-1 font-mono text-[11px] font-semibold text-slate-600">
                        <Hash className="h-3 w-3 text-slate-400" />{hFolio(h)}
                      </span>
                    ) : <span className="text-[11px] text-slate-300">No folio</span>}
                    <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${
                      quality === 'OK' ? 'bg-emerald-50 text-emerald-700' : quality === 'STALE' ? 'bg-amber-50 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
                      {quality === 'OK' ? 'Live valuation' : quality === 'STALE' ? 'At cost' : 'Valuation pending'}
                    </span>
                  </div>
                </div>
                {/* Metric grid */}
                <div className="grid grid-cols-2 gap-x-4 gap-y-4 px-5 py-4 sm:grid-cols-3 lg:grid-cols-6">
                  <Metric label="Units" value={fmtUnits(numOrNull(h.units))} mono />
                  <Metric label="Avg cost NAV" value={fmtNav(numOrNull(h.averageCostNav))} mono />
                  <Metric label="Latest NAV" value={fmtNav(numOrNull(h.latestNav)) ?? (pending ? 'Pending' : null)} mono valueClass={pending ? 'text-slate-300' : undefined} />
                  <Metric label="Invested" value={fmtMoneyExact(hInvested(h))} />
                  <Metric label="Current value" value={fmtMoneyExact(hCurrent(h))} valueClass="text-slate-900" />
                  <Metric label="Returns" value={fmtMoney(abs)} valueClass={gainColor(abs)} />
                  <Metric label="Return %" value={fmtPercent(pct)} valueClass={gainColor(pct)} />
                  <Metric label="1-day" value={fmtMoney(numOrNull(h.oneDayReturn))} valueClass={gainColor(numOrNull(h.oneDayReturn))} />
                  <Metric label="1-day %" value={fmtPercent(numOrNull(h.oneDayReturnPercent))} valueClass={gainColor(numOrNull(h.oneDayReturnPercent))} />
                  <Metric label="XIRR" value={fmtPercent(numOrNull(h.xirr))} valueClass={gainColor(numOrNull(h.xirr))} />
                  <Metric label="NAV as of" value={fmtDate(h.navAsOf)} />
                </div>
                {(pending || atCost) && (
                  <div className="flex items-start gap-2 border-t border-slate-50 bg-slate-50/60 px-5 py-2.5">
                    <Info className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-slate-400" />
                    <p className="text-[11px] text-slate-500">
                      {atCost
                        ? 'Valued at your average cost — a live market NAV for this scheme isn’t available yet, so current value equals invested and returns show at cost. It updates automatically once NAVs sync from the provider.'
                        : 'Current value and returns will appear once the latest NAV for this scheme is available. Your invested amount, units and cost are live.'}
                    </p>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
