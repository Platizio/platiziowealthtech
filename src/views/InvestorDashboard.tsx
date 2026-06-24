import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  PieChart, Pie, Cell, Tooltip, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Legend,
} from 'recharts';
import {
  Wallet, TrendingUp, TrendingDown, Activity, Percent,
  Loader2, AlertCircle, PieChart as PieChartIcon,
} from 'lucide-react';
import EmptyState from '../components/EmptyState';
import { apiFetch } from '../config/api';

/**
 * Phase-3 investor dashboard (FR-DSH). Reads the investor-scoped dashboard
 * endpoint and renders a portfolio summary, a holdings table and an allocation
 * visualisation. Mirrors the navy/Inter design system and the Phase-2 investor
 * pages (Approval Center, Withdrawal) exactly.
 *
 * Locked decision (carried from Phase-2 holdings): the backend NEVER fabricates
 * a value when valuation inputs are missing. When a holding's {@code dataQuality}
 * is STALE or UNAVAILABLE we show a clear "—"/"Unavailable" caveat rather than a
 * fabricated 0, and exclude it from the allocation chart.
 */

// ── Field shape (aligns to the documented dashboard DTO) ─────────────────────
// Defensive aliases are accepted so the view survives small backend naming
// differences without crashing — but it never invents numbers.
interface DashboardHolding {
  schemeName?: string;
  scheme?: string;
  folio?: string;
  folioNumber?: string;
  units?: number | null;
  availableUnits?: number | null;
  latestNav?: number | null;
  navAsOf?: string | null;
  invested?: number | null;
  investedValue?: number | null;
  costBasis?: number | null;
  currentValue?: number | null;
  oneDayReturn?: number | null;
  percentReturn?: number | null;
  totalReturnPercent?: number | null;
  xirr?: number | null;
  dataQuality?: string; // OK | STALE | UNAVAILABLE
}

interface DashboardTotals {
  totalInvested?: number | null;
  totalCurrentValue?: number | null;
  totalReturn?: number | null;
  totalReturnPercent?: number | null;
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

/** Compact INR — matches Portfolio.tsx fmt(). Returns null only when input is null. */
const fmtMoney = (v?: number | null): string | null => {
  if (!isNum(v)) return null;
  const abs = Math.abs(v);
  if (abs >= 10_000_000) return `₹${(v / 10_000_000).toFixed(2)}Cr`;
  if (abs >= 100_000) return `₹${(v / 100_000).toFixed(2)}L`;
  return `₹${v.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
};

/** Exact INR for table cells (no compaction). */
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

// Field aliasing (read whichever the backend sent; never coerce missing → 0).
const holdingScheme = (h: DashboardHolding) => h.schemeName || h.scheme || 'Scheme name unavailable';
const holdingFolio = (h: DashboardHolding) => h.folio || h.folioNumber || '—';
const holdingUnits = (h: DashboardHolding) => numOrNull(h.units ?? h.availableUnits);
const holdingInvested = (h: DashboardHolding) => numOrNull(h.invested ?? h.investedValue ?? h.costBasis);
const holdingCurrent = (h: DashboardHolding) => numOrNull(h.currentValue);
const holdingPct = (h: DashboardHolding) => numOrNull(h.percentReturn ?? h.totalReturnPercent);

/** Renders a value or a clear caveat — never a fabricated number. */
function Val({ text, muted }: { text: string | null; muted?: string }) {
  if (text == null) {
    return <span className="text-slate-300">{muted ?? '—'}</span>;
  }
  return <>{text}</>;
}

export default function InvestorDashboard() {
  const [data, setData] = useState<DashboardPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/dashboard');
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || `Unable to load your dashboard (${res.status}).`);
      }
      // Accept either {holdings, totals} or a bare holdings array.
      const payload: DashboardPayload = Array.isArray(body)
        ? { holdings: body as DashboardHolding[] }
        : (body as DashboardPayload) || {};
      setData(payload);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load your dashboard.');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadDashboard();
  }, [loadDashboard]);

  const holdings = useMemo(() => data?.holdings ?? [], [data]);
  const totals = data?.totals ?? {};

  // Allocation by current value per scheme — only OK-quality holdings with a
  // real current value contribute (never fabricate / never count nulls).
  const allocation = useMemo(() => {
    return holdings
      .filter((h) => isUsableQuality(h.dataQuality))
      .map((h) => ({ name: holdingScheme(h), value: holdingCurrent(h) }))
      .filter((d): d is { name: string; value: number } => isNum(d.value) && d.value > 0)
      .sort((a, b) => b.value - a.value);
  }, [holdings]);

  // Invested vs current per scheme (only rows where both are real numbers).
  const investedVsCurrent = useMemo(() => {
    return holdings
      .filter((h) => isUsableQuality(h.dataQuality))
      .map((h) => {
        const invested = holdingInvested(h);
        const current = holdingCurrent(h);
        return isNum(invested) && isNum(current)
          ? { name: holdingScheme(h), Invested: invested, Current: current }
          : null;
      })
      .filter((d): d is { name: string; Invested: number; Current: number } => d != null)
      .slice(0, 8);
  }, [holdings]);

  // ── Loading ────────────────────────────────────────────────────────────────
  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading your dashboard…</p>
        </div>
      </div>
    );
  }

  // ── Error ────────────────────────────────────────────────────────────────────
  if (error) {
    return (
      <div className="mx-auto max-w-5xl p-8">
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <div className="flex items-start gap-2.5">
            <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
            <div>
              <p className="font-semibold">Dashboard unavailable</p>
              <p className="mt-1 text-sm">{error}</p>
              <button
                onClick={() => void loadDashboard()}
                className="mt-4 text-sm font-semibold text-red-600 hover:underline"
              >
                Try again
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ── Empty ────────────────────────────────────────────────────────────────────
  if (holdings.length === 0) {
    return (
      <div className="mx-auto max-w-5xl p-8">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-slate-800">Your portfolio</h1>
          <p className="mt-1 text-sm text-slate-500">A live view of your holdings, returns and allocation.</p>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white">
          <EmptyState
            icon={Wallet}
            title="No holdings yet"
            subtitle="Once your investments are confirmed, your portfolio value, returns and allocation will appear here."
          />
        </div>
      </div>
    );
  }

  // ── Summary stat cards ───────────────────────────────────────────────────────
  const totalInvested = numOrNull(totals.totalInvested);
  const totalCurrentValue = numOrNull(totals.totalCurrentValue);
  const totalReturn = numOrNull(totals.totalReturn);
  const totalReturnPct = numOrNull(totals.totalReturnPercent);
  const portfolioXirr = numOrNull(totals.portfolioXirr);

  const statCards: Array<{
    label: string;
    value: string | null;
    icon: ReactNode;
    bg: string;
    iconColor: string;
    valueColor?: string;
    sub?: { text: string | null; color: string };
  }> = [
    {
      label: 'Total invested',
      value: fmtMoney(totalInvested),
      icon: <Wallet className="h-5 w-5" />,
      bg: 'bg-slate-50',
      iconColor: 'text-slate-600',
    },
    {
      label: 'Current value',
      value: fmtMoney(totalCurrentValue),
      icon: <Activity className="h-5 w-5" />,
      bg: 'bg-blue-50',
      iconColor: 'text-blue-600',
    },
    {
      label: 'Total return',
      value: fmtMoney(totalReturn),
      icon: isNum(totalReturn) && totalReturn < 0
        ? <TrendingDown className="h-5 w-5" />
        : <TrendingUp className="h-5 w-5" />,
      bg: isNum(totalReturn) && totalReturn < 0 ? 'bg-red-50' : 'bg-emerald-50',
      iconColor: isNum(totalReturn) && totalReturn < 0 ? 'text-red-600' : 'text-emerald-600',
      valueColor: gainColor(totalReturn),
      sub: { text: fmtPercent(totalReturnPct), color: gainColor(totalReturnPct) },
    },
    {
      label: 'Portfolio XIRR',
      value: fmtPercent(portfolioXirr),
      icon: <Percent className="h-5 w-5" />,
      bg: 'bg-violet-50',
      iconColor: 'text-violet-600',
      valueColor: gainColor(portfolioXirr),
    },
  ];

  return (
    <div className="mx-auto max-w-5xl space-y-6 p-8">
      <div>
        <h1 className="text-2xl font-semibold text-slate-800">Your portfolio</h1>
        <p className="mt-1 text-sm text-slate-500">A live view of your holdings, returns and allocation.</p>
      </div>

      {/* Summary stat cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {statCards.map((card) => (
          <div key={card.label} className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
            <div className={`mb-3 flex h-9 w-9 items-center justify-center rounded-xl ${card.bg} ${card.iconColor}`}>
              {card.icon}
            </div>
            <p className="mb-1 text-[10px] font-bold uppercase tracking-wider text-slate-400">{card.label}</p>
            <p className={`text-2xl font-bold ${card.valueColor ?? 'text-slate-800'}`}>
              {card.value ?? <span className="text-slate-300">Unavailable</span>}
            </p>
            {card.sub && card.sub.text != null && (
              <p className={`mt-1 text-xs font-semibold ${card.sub.color}`}>{card.sub.text}</p>
            )}
          </div>
        ))}
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
        {/* Allocation donut */}
        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm lg:col-span-2">
          <h2 className="mb-4 text-sm font-semibold text-slate-700">Allocation by scheme</h2>
          {allocation.length > 0 ? (
            <>
              <ResponsiveContainer width="100%" height={200}>
                <PieChart>
                  <Pie
                    data={allocation}
                    cx="50%"
                    cy="50%"
                    innerRadius={55}
                    outerRadius={85}
                    dataKey="value"
                    paddingAngle={3}
                  >
                    {allocation.map((_, i) => (
                      <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(v: number | string) => fmtMoney(Number(v)) ?? '—'} />
                </PieChart>
              </ResponsiveContainer>
              <div className="mt-3 space-y-2">
                {allocation.slice(0, 6).map((d, i) => (
                  <div key={d.name} className="flex items-center justify-between gap-2 text-xs">
                    <span className="flex min-w-0 items-center gap-1.5">
                      <span
                        className="inline-block h-2.5 w-2.5 flex-shrink-0 rounded-full"
                        style={{ background: PIE_COLORS[i % PIE_COLORS.length] }}
                      />
                      <span className="truncate text-slate-600">{d.name}</span>
                    </span>
                    <span className="flex-shrink-0 font-semibold text-slate-700">{fmtMoney(d.value)}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="flex h-52 flex-col items-center justify-center gap-2 text-slate-300">
              <PieChartIcon className="h-8 w-8" />
              <p className="text-sm">No valued holdings to chart yet</p>
            </div>
          )}
        </div>

        {/* Invested vs current bar */}
        <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm lg:col-span-3">
          <h2 className="mb-4 text-sm font-semibold text-slate-700">Invested vs current value</h2>
          {investedVsCurrent.length > 0 ? (
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={investedVsCurrent} margin={{ top: 5, right: 5, left: -10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#E2E8F0" vertical={false} />
                <XAxis
                  dataKey="name"
                  tick={{ fontSize: 10, fill: '#94A3B8' }}
                  tickFormatter={(v: string) => (v.length > 12 ? `${v.slice(0, 12)}…` : v)}
                  interval={0}
                  angle={-15}
                  textAnchor="end"
                  height={50}
                />
                <YAxis tick={{ fontSize: 10, fill: '#94A3B8' }} tickFormatter={(v: number) => fmtMoney(v) ?? ''} />
                <Tooltip formatter={(v: number | string) => fmtMoney(Number(v)) ?? '—'} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="Invested" fill="#94A3B8" radius={[4, 4, 0, 0]} />
                <Bar dataKey="Current" fill="#0B1B3E" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <div className="flex h-52 flex-col items-center justify-center gap-2 text-slate-300">
              <Activity className="h-8 w-8" />
              <p className="text-sm">Not enough valued holdings to compare</p>
            </div>
          )}
        </div>
      </div>

      {/* Holdings table */}
      <div className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
        <h2 className="mb-4 text-sm font-semibold text-slate-700">Holdings</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-xs font-semibold text-slate-400">
                <th className="pb-3 text-left">Scheme</th>
                <th className="pb-3 text-left">Folio</th>
                <th className="pb-3 text-right">Units</th>
                <th className="pb-3 text-right">Latest NAV</th>
                <th className="pb-3 text-right">Invested</th>
                <th className="pb-3 text-right">Current value</th>
                <th className="pb-3 text-right">1-day</th>
                <th className="pb-3 text-right">Total return</th>
                <th className="pb-3 text-right">XIRR</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-50">
              {holdings.map((h, i) => {
                const quality = normalizeQuality(h.dataQuality);
                const stale = quality === 'STALE';
                const unavailable = quality === 'UNAVAILABLE';
                const caveat = unavailable ? 'Unavailable' : stale ? 'Stale' : '—';
                const navText = fmtNav(h.latestNav);
                const navAsOf = fmtDate(h.navAsOf);
                const oneDay = numOrNull(h.oneDayReturn);
                const pct = holdingPct(h);
                const xirr = numOrNull(h.xirr);
                return (
                  <tr key={`${holdingFolio(h)}-${i}`} className="hover:bg-slate-50/80">
                    <td className="py-3">
                      <p className="font-medium text-slate-800">{holdingScheme(h)}</p>
                      {(stale || unavailable) && (
                        <span className="mt-1 inline-block rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-semibold text-amber-700">
                          Valuation {quality.toLowerCase()}
                        </span>
                      )}
                    </td>
                    <td className="py-3 font-mono text-xs text-slate-500">
                      <Val text={holdingFolio(h) === '—' ? null : holdingFolio(h)} />
                    </td>
                    <td className="py-3 text-right text-slate-600">
                      <Val text={fmtUnits(holdingUnits(h))} muted={caveat} />
                    </td>
                    <td className="py-3 text-right text-slate-600">
                      {navText != null ? (
                        <>
                          <span>{navText}</span>
                          {navAsOf && <p className="text-[10px] text-slate-400">as of {navAsOf}</p>}
                        </>
                      ) : (
                        <span className="text-slate-300">{caveat}</span>
                      )}
                    </td>
                    <td className="py-3 text-right font-medium text-slate-700">
                      <Val text={fmtMoneyExact(holdingInvested(h))} muted={caveat} />
                    </td>
                    <td className="py-3 text-right font-semibold text-slate-800">
                      <Val text={fmtMoneyExact(holdingCurrent(h))} muted={caveat} />
                    </td>
                    <td className={`py-3 text-right font-medium ${gainColor(oneDay)}`}>
                      <Val text={fmtMoney(oneDay)} muted={caveat} />
                    </td>
                    <td className={`py-3 text-right font-medium ${gainColor(pct)}`}>
                      <Val text={fmtPercent(pct)} muted={caveat} />
                    </td>
                    <td className={`py-3 text-right font-medium ${gainColor(xirr)}`}>
                      <Val text={fmtPercent(xirr)} muted={caveat} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
