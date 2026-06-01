import React, { useState } from 'react';
import { motion } from 'motion/react';
import { ArrowLeft, CheckCircle2, XCircle, Clock, RefreshCw } from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts';
import { apiFetch } from '../config/api';

interface Sip {
  id: string; investor: string; fund: string;
  amount: string; status: 'Active' | 'Failed' | 'Paused';
  nextDue: string; mandate: string; category?: string;
}

interface SipTrend {
  month: string; value: number; count: number;
}

type StatusFilter = 'All' | 'Active' | 'Failed' | 'Paused';
type CatFilter = 'ALL' | 'MF' | 'SIF';

const statusCfg: Record<string, { icon: React.ReactNode; cls: string }> = {
  Active: { icon: <CheckCircle2 className="w-3.5 h-3.5" />, cls: 'bg-green-100 text-green-700' },
  Failed: { icon: <XCircle      className="w-3.5 h-3.5" />, cls: 'bg-red-100   text-red-700'   },
  Paused: { icon: <Clock        className="w-3.5 h-3.5" />, cls: 'bg-amber-100 text-amber-700' },
};

const statusFallback = { icon: <Clock className="w-3.5 h-3.5" />, cls: 'bg-slate-100 text-slate-600' };

// ─────────────────────────────────────────────────────────────────────────────
// Loading skeleton — shown while the first fetch is in-flight.
// Defined at module scope so its identity is stable across re-renders.
// ─────────────────────────────────────────────────────────────────────────────
function SipDashboardSkeleton() {
  const pulse = 'bg-slate-100 animate-pulse rounded-xl';
  return (
    <div className="p-8 space-y-6">
      {/* back button */}
      <div className={`${pulse} h-5 w-32`} />

      {/* header row */}
      <div className="flex justify-between items-end">
        <div className="space-y-2">
          <div className={`${pulse} h-7 w-48`} />
          <div className={`${pulse} h-4 w-64`} />
        </div>
        <div className={`${pulse} h-9 w-48 rounded-lg`} />
      </div>

      {/* KPI cards */}
      <div className="grid grid-cols-4 gap-5">
        {[...Array(4)].map((_, i) => (
          <div key={i} className="bg-white rounded-2xl border border-slate-100 p-5 space-y-3">
            <div className={`${pulse} h-3 w-28`} />
            <div className={`${pulse} h-8 w-20`} />
            <div className={`${pulse} h-3 w-32`} />
          </div>
        ))}
      </div>

      {/* trend chart */}
      <div className="bg-white rounded-2xl border border-slate-200 p-6 space-y-4">
        <div className={`${pulse} h-5 w-56`} />
        <div className={`${pulse} h-[220px] w-full rounded-xl`} />
      </div>

      {/* SIP table */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-100">
          <div className={`${pulse} h-5 w-32`} />
        </div>
        {[...Array(5)].map((_, i) => (
          <div key={i} className="px-5 py-3.5 border-b border-slate-50 flex gap-4">
            <div className={`${pulse} h-4 flex-1`} />
            <div className={`${pulse} h-4 flex-1`} />
            <div className={`${pulse} h-4 w-16`} />
          </div>
        ))}
      </div>
    </div>
  );
}

// Shared chrome (breadcrumb + heading) so the empty/error screens are not
// dead-ends and stay visually consistent with the loaded dashboard.
function SipDashboardChrome({ onBack, children }: { onBack: () => void; children: React.ReactNode }) {
  return (
    <div className="p-8 space-y-6">
      <button
        onClick={onBack}
        className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
      >
        <ArrowLeft className="w-4 h-4" /> Back to Dashboard
      </button>
      <div>
        <h1 className="text-2xl font-semibold text-slate-800">SIP Dashboard</h1>
        <p className="text-slate-500 text-sm mt-1">Track active, failed and upcoming systematic investment plans</p>
      </div>
      {children}
    </div>
  );
}

function SipDashboardEmpty({ onBack }: { onBack: () => void }) {
  return (
    <SipDashboardChrome onBack={onBack}>
      <div className="bg-white rounded-2xl border border-slate-200 py-16 text-center">
        <p className="text-sm font-semibold text-slate-600">No SIP data yet</p>
        <p className="text-xs text-slate-400 mt-1">
          SIPs will appear here once your investors set up systematic plans.
        </p>
      </div>
    </SipDashboardChrome>
  );
}

function SipDashboardError({ onBack }: { onBack: () => void }) {
  return (
    <SipDashboardChrome onBack={onBack}>
      <div className="bg-white rounded-2xl border border-red-200 py-16 text-center">
        <XCircle className="w-7 h-7 text-red-400 mx-auto mb-2" />
        <p className="text-sm font-semibold text-slate-700">Couldn’t load SIP data</p>
        <p className="text-xs text-slate-400 mt-1">
          Something went wrong fetching this dashboard. Please try again later.
        </p>
      </div>
    </SipDashboardChrome>
  );
}

// Session-cache TTL: 5 minutes
const CACHE_TTL_MS = 5 * 60 * 1_000;

export default function SipDashboard({ onBack, userData }: { onBack: () => void; userData?: any }) {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('All');
  const [catFilter, setCatFilter] = useState<CatFilter>('ALL');
  const [sips, setSips] = useState<Sip[]>([]);
  // F-16: do NOT pre-populate with fabricated zero months. The dedicated
  // <SipDashboardSkeleton/> (rendered while `loading`) is the loading visual;
  // a non-null placeholder here only ever surfaced on the error path, where it
  // rendered a fake flat-zero chart that looked like real data. Start empty.
  const [sipTrend, setSipTrend] = useState<SipTrend[]>([]);
  const [loading, setLoading] = useState(true);
  const [cancellingId, setCancellingId] = useState('');
  const [loadError, setLoadError] = useState(false);
  const [cancelError, setCancelError] = useState('');

  React.useEffect(() => {
    if (!userData?.id) return;

    const cacheKey = `sip_dash_${userData.id}`;

    // ── Try the session cache first ──────────────────────────────────────────
    // This prevents the chart from flickering to different values on every
    // navigation back to this page within the same browser session.
    try {
      const raw = sessionStorage.getItem(cacheKey);
      if (raw) {
        const { ts, payload } = JSON.parse(raw) as { ts: number; payload: any };
        if (Date.now() - ts < CACHE_TTL_MS) {
          setSips(payload.sips || []);
          setSipTrend(
            (payload.trend || []).map((t: any) => ({
              month: t.month,
              value: Number(t.amount) || 0,
              count: t.count ?? 0,
            }))
          );
          setLoadError(false);
          setLoading(false);
          return; // skip network fetch — cache is still fresh
        }
      }
    } catch {
      // Malformed cache entry — fall through to a fresh fetch
    }

    // ── Cache miss: fetch from backend ──────────────────────────────────────
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };

    apiFetch(`/dashboard/distributor/${userData.id}/sips`, { headers })
      .then(res => res.json())
      .then(data => {
        // Persist to session cache for subsequent navigations
        try {
          sessionStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), payload: data }));
        } catch {
          // Quota exceeded or private-browsing restriction — silently skip
        }

        setSips(data.sips || []);
        // Normalise backend {month, amount, count} → {month, value, count}.
        // Coerce amount to Number to handle BigDecimal serialised as string.
        setSipTrend(
          (data.trend || []).map((t: any) => ({
            month: t.month,
            value: Number(t.amount) || 0,
            count: t.count ?? 0,
          }))
        );
        setLoadError(false);
        setLoading(false);
      })
      .catch(err => {
        console.error('Failed to fetch SIP dashboard', err);
        // F-16: surface a real error state instead of silently rendering
        // empty/zero values that the user would read as "no SIPs".
        setLoadError(true);
        setLoading(false);
      });
  }, [userData]);

  const cancelSip = async (sip: Sip) => {
    if (!window.confirm(`Cancel SIP for ${sip.investor}?`)) return;
    setCancellingId(sip.id);
    setCancelError('');
    try {
      const response = await apiFetch(`/orders/${sip.id}`, { method: 'DELETE' });
      if (!response.ok) {
        const errorBody = await response.json().catch(() => null);
        throw new Error(errorBody?.message || `Cancel SIP failed (${response.status})`);
      }

      if (userData?.id) sessionStorage.removeItem(`sip_dash_${userData.id}`);
      setSips(prev => prev.filter(item => item.id !== sip.id));
    } catch (err: any) {
      setCancelError(err?.message || 'Cancel SIP failed. Please try again.');
    } finally {
      setCancellingId('');
    }
  };

  const visible = sips.filter(s => {
    const matchStatus = statusFilter === 'All' || s.status === statusFilter;
    const matchCat = catFilter === 'ALL' || s.category === catFilter;
    return matchStatus && matchCat;
  });

  const totalAmount = sips.reduce((sum, s) => {
    const val = parseFloat(s.amount.replace(/[^0-9.]/g, '')) || 0;
    return sum + val;
  }, 0);
  const activeSips = sips.filter(s => s.status === 'Active').length;
  const failedSips = sips.filter(s => s.status === 'Failed').length;
  const pausedSips = sips.filter(s => s.status === 'Paused').length;

  // F-16 render guards — strict order so no fabricated/stale values ever show:
  //   loading → skeleton (never a zero-filled chart)
  //   error   → explicit error screen (not a misleading "no data" / ₹0.00)
  //   empty   → empty state (genuinely no SIPs, distinct from an error)
  //   else    → real dashboard, with real resolved data only
  if (loading) return <SipDashboardSkeleton />;
  if (loadError) return <SipDashboardError onBack={onBack} />;
  if (sips.length === 0) return <SipDashboardEmpty onBack={onBack} />;

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-4 md:p-8 space-y-6"
    >
      {/* ── Breadcrumb ──────────────────────────────────────────────────── */}
      <button
        onClick={onBack}
        className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
      >
        <ArrowLeft className="w-4 h-4" /> Back to Dashboard
      </button>

      <div className="flex flex-wrap justify-between items-end gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">SIP Dashboard</h1>
          <p className="text-slate-500 text-sm mt-1">Track active, failed and upcoming systematic investment plans</p>
        </div>
        <div className="flex gap-1 bg-slate-100 p-1 rounded-lg text-[10px] font-bold uppercase tracking-wider">
          {['ALL', 'MF', 'SIF'].map(c => (
            <button
              key={c}
              onClick={() => setCatFilter(c as any)}
              className={`px-3 py-1.5 rounded ${catFilter === c ? 'bg-white shadow-sm text-slate-900' : 'text-slate-500 hover:text-slate-700'}`}
            >
              {c === 'ALL' ? 'All Assets' : c === 'MF' ? 'Mutual Funds' : 'SIF'}
            </button>
          ))}
        </div>
      </div>

      {/* ── KPI row ─────────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 md:gap-5">
        {[
          { label: 'Monthly SIP Amount', value: `₹${(totalAmount / 100000).toFixed(2)} L`, sub: 'Calculated from active SIPs', accent: 'bg-blue-50   border-blue-200   text-blue-700'   },
          { label: 'Active SIPs',        value: activeSips.toString(),     sub: 'Currently running',   accent: 'bg-green-50  border-green-200  text-green-700'  },
          { label: 'Failed SIPs',        value: failedSips.toString(),       sub: 'Needs attention',    accent: 'bg-red-50    border-red-200    text-red-700'    },
          { label: 'Paused SIPs',        value: pausedSips.toString(),       sub: 'Awaiting reactivation', accent: 'bg-amber-50  border-amber-200  text-amber-700'  },
        ].map(k => (
          <div key={k.label} className={`rounded-2xl border p-5 ${k.accent}`}>
            <p className="text-[10px] font-bold uppercase tracking-wider opacity-60 mb-2">{k.label}</p>
            <p className="text-2xl font-bold mb-1">{k.value}</p>
            <p className="text-xs font-semibold opacity-80">{k.sub}</p>
          </div>
        ))}
      </div>

      {/* ── Trend chart ─────────────────────────────────────────────────── */}
      <div className="bg-white rounded-2xl border border-slate-200 p-6">
        <h2 className="font-semibold text-slate-800 mb-5">SIP Amount Trend — Last 6 Months (₹ L)</h2>
        <ResponsiveContainer width="100%" height={220}>
          <AreaChart
            data={sipTrend}
            margin={{ top: 10, right: 10, left: 0, bottom: 0 }}
          >
            <defs>
              <linearGradient id="sipGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%"  stopColor="#3b82f6" stopOpacity={0.15} />
                <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}    />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
            <XAxis dataKey="month" tick={{ fontSize: 12 }} axisLine={false} tickLine={false} />
            {/* domain ensures a non-zero max so the line is never clipped to the
                bottom edge of the chart when all values are 0 */}
            <YAxis
              tick={{ fontSize: 12 }}
              tickFormatter={v => `₹${v}L`}
              axisLine={false}
              tickLine={false}
              domain={[0, (max: number) => Math.max(max, 1)]}
              padding={{ top: 16, bottom: 0 }}
            />
            <Tooltip formatter={(v: number) => [`₹${v} L`, 'SIP Amount']} />
            <Area
              type="monotone"
              dataKey="value"
              name="SIP Amount (₹L)"
              stroke="#3b82f6"
              strokeWidth={2}
              fill="url(#sipGrad)"
              connectNulls
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {/* ── SIP list ────────────────────────────────────────────────────── */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
        {cancelError && <div className="mx-5 mt-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{cancelError}</div>}
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h2 className="font-semibold text-slate-800">
            {catFilter === 'ALL' ? 'All SIPs' : `${catFilter} SIPs`}
            <span className="ml-2 text-xs font-normal text-slate-400">({visible.length})</span>
          </h2>
          {/* Filter pill */}
          <div className="flex bg-slate-100 rounded-lg p-0.5 gap-0.5">
            {(['All', 'Active', 'Failed', 'Paused'] as StatusFilter[]).map(f => (
              <button
                key={f}
                onClick={() => setStatusFilter(f)}
                className={`px-3 py-1 text-xs font-semibold rounded-md transition-all ${
                  statusFilter === f
                    ? 'bg-white shadow-sm text-slate-800'
                    : 'text-slate-500 hover:text-slate-700'
                }`}
              >
                {f}
              </button>
            ))}
          </div>
        </div>
        {/* F-14: overflow-x-auto so the table can scroll horizontally on
            screens narrower than the table's natural width, instead of being
            clipped by the card's overflow-hidden corner mask. */}
        <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="bg-slate-50 border-b border-slate-100">
              {[
                { h: 'Investor', align: 'text-left'  },
                { h: 'Fund',     align: 'text-left'  },
                { h: 'Category', align: 'text-left'  },
                { h: 'Amount',   align: 'text-right' },
                { h: 'Mandate',  align: 'text-left'  },
                { h: 'Next Due', align: 'text-left'  },
                { h: 'Status',   align: 'text-left'  },
                { h: 'Actions',  align: 'text-right' },
              ].map(col => (
                <th key={col.h} className={`py-3 px-5 text-xs font-bold text-slate-500 uppercase tracking-wider ${col.align}`}>
                  {col.h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {visible.map(sip => {
              const cfg = statusCfg[sip.status] ?? statusFallback;
              const isMF = sip.category === 'MF';
              return (
                <tr key={sip.id} className="hover:bg-slate-50 transition-colors">
                  <td className="py-3.5 px-5 text-sm font-semibold text-slate-800">{sip.investor}</td>
                  <td className="py-3.5 px-5 text-sm text-slate-600 max-w-[200px]">
                    <span className="truncate block">{sip.fund}</span>
                  </td>
                  <td className="py-3.5 px-5">
                    <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${
                      isMF ? 'bg-blue-50 text-blue-600' : 'bg-violet-50 text-violet-600'
                    }`}>
                      {isMF ? 'MF' : 'SIF'}
                    </span>
                  </td>
                  <td className="py-3.5 px-5 text-sm text-right font-mono font-semibold text-slate-800">{sip.amount}</td>
                  <td className="py-3.5 px-5 text-xs text-slate-500">{sip.mandate}</td>
                  <td className="py-3.5 px-5 text-sm text-slate-600">{sip.nextDue}</td>
                  <td className="py-3.5 px-5">
                    <span className={`inline-flex items-center gap-1 text-[10px] font-bold px-2 py-0.5 rounded-full ${cfg.cls}`}>
                      {cfg.icon} {sip.status}
                    </span>
                  </td>
                  <td className="py-3.5 px-5 text-right">
                    <button
                      onClick={() => cancelSip(sip)}
                      disabled={cancellingId === sip.id || sip.status !== 'Active'}
                      className="rounded-lg border border-red-200 px-3 py-1.5 text-xs font-semibold text-red-600 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      {cancellingId === sip.id ? 'Cancelling...' : 'Cancel SIP'}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        </div>

        {visible.length === 0 && (
          <div className="py-12 text-center text-sm text-slate-400">
            No {statusFilter.toLowerCase()} {catFilter !== 'ALL' ? catFilter : ''} SIPs found.
          </div>
        )}
      </div>
    </motion.div>
  );
}
