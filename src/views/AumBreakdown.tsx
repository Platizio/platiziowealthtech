import React, { useState, useEffect } from 'react';
import { motion } from 'motion/react';
import { ArrowLeft, TrendingUp, TrendingDown } from 'lucide-react';
import {
  ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Legend,
} from 'recharts';

const typeBadge: Record<string, string> = {
  INSTITUTIONAL: 'bg-blue-100 text-blue-700',
  HNI: 'bg-violet-100 text-violet-700',
  RETAIL: 'bg-slate-100 text-slate-600',
};

function fmtInr(amount: number): string {
  if (amount >= 10000000) return `₹${(amount / 10000000).toFixed(2)} Cr`;
  if (amount >= 100000) return `₹${(amount / 100000).toFixed(2)} L`;
  if (amount >= 1000) return `₹${(amount / 1000).toFixed(1)} k`;
  return `₹${amount.toFixed(0)}`;
}

const MONTH_LABELS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

type RangeKey = '1M' | '3M' | '6M' | '1Y' | 'ALL';

const RANGES: { key: RangeKey; label: string }[] = [
  { key: '1M',  label: '1M'  },
  { key: '3M',  label: '3M'  },
  { key: '6M',  label: '6M'  },
  { key: '1Y',  label: '1Y'  },
  { key: 'ALL', label: 'All' },
];

const RANGE_LABELS: Record<RangeKey, string> = {
  '1M':  'Last 1 Month',
  '3M':  'Last 3 Months',
  '6M':  'Last 6 Months',
  '1Y':  'Last 1 Year',
  'ALL': 'All Time',
};

function buildTrend(orders: any[], schemeMap: Map<string, any>, range: RangeKey): any[] {
  const now      = new Date();
  const nowYear  = now.getFullYear();
  const nowMonth = now.getMonth();
  const useAll   = range === 'ALL';

  const monthsBack = range === '1M' ? 1 : range === '3M' ? 3 : range === '6M' ? 6 : 12;
  const cutoff     = useAll ? null : new Date(nowYear, nowMonth - monthsBack, 1);
  const cutoffYear = cutoff?.getFullYear() ?? 0;
  const cutoffMonth = cutoff?.getMonth() ?? 0;

  const monthlyMf:  Record<string, number> = {};
  const monthlySif: Record<string, number> = {};
  let earliestKey: string | null = null;

  for (const o of orders) {
    if (o.orderStatus !== 'COMPLETED') continue;
    const d  = new Date(o.createdAt || Date.now());
    const yr = d.getFullYear();
    const mo = d.getMonth();
    if (!useAll && (yr < cutoffYear || (yr === cutoffYear && mo < cutoffMonth))) continue;

    const key = `${yr}-${String(mo).padStart(2, '0')}`;
    if (!earliestKey || key < earliestKey) earliestKey = key;

    const amt = (o.amount || 0) / 10000000;
    const s   = schemeMap.get(o.productSchemeId);
    const rawCat = (
      o.productCategory || o.category ||
      s?.productCategory || s?.category || s?.assetClass || ''
    ).toString().toUpperCase();
    const cat = rawCat.includes('MF') || rawCat.includes('MUTUAL') ? 'MF' : 'SIF';

    if (cat === 'MF') monthlyMf[key]  = (monthlyMf[key]  || 0) + amt;
    else              monthlySif[key] = (monthlySif[key] || 0) + amt;
  }

  let startYear: number, startMonth: number;
  if (useAll && earliestKey) {
    [startYear, startMonth] = earliestKey.split('-').map(Number);
  } else if (useAll) {
    startYear = nowYear; startMonth = nowMonth;
  } else {
    startYear = cutoffYear; startMonth = cutoffMonth;
  }

  const showYear = range === '1Y' || range === 'ALL';
  const trend: any[] = [];
  let yr = startYear, mo = startMonth;

  while (yr < nowYear || (yr === nowYear && mo <= nowMonth)) {
    const key = `${yr}-${String(mo).padStart(2, '0')}`;
    trend.push({
      month: showYear
        ? `${MONTH_LABELS[mo]} '${String(yr).slice(2)}`
        : MONTH_LABELS[mo],
      MF:  parseFloat((monthlyMf[key]  || 0).toFixed(2)),
      SIF: parseFloat((monthlySif[key] || 0).toFixed(2)),
    });
    if (++mo > 11) { mo = 0; yr++; }
  }
  // Compute running cumulative total for the line
  let cumulative = 0;
  for (const point of trend) {
    cumulative += point.MF + point.SIF;
    point.cumulativeTotal = parseFloat(cumulative.toFixed(2));
  }

  return trend;
}

interface AumBreakdownProps {
  onBack: () => void;
  userData?: any;
}

export default function AumBreakdown({ onBack, userData }: AumBreakdownProps) {
  const [loading,      setLoading]      = useState(true);
  const [investorRows, setInvestorRows] = useState<any[]>([]);
  const [aumTrend,     setAumTrend]     = useState<any[]>([]);
  const [summary,      setSummary]      = useState({ totalAum: 0, mfAum: 0, sifAum: 0, othersAum: 0 });
  const [range,        setRange]        = useState<RangeKey>('6M');
  const [rawOrders,    setRawOrders]    = useState<any[]>([]);
  const [rawSchemes,   setRawSchemes]   = useState<Map<string, any>>(new Map());

  useEffect(() => {
    if (!userData?.id) return;

    const fetchData = async () => {
      try {
        setLoading(true);
        const [ordersRes, investorsRes, schemesRes] = await Promise.all([
          fetch(`http://localhost:8081/api/v1/orders/by-distributor/${userData.id}`),
          fetch(`http://localhost:8081/api/v1/investors/by-distributor/${userData.id}`),
          fetch(`http://localhost:8081/api/v1/products/schemes`),
        ]);

        const orders: any[] = ordersRes.ok ? await ordersRes.json() : [];
        const investors: any[] = investorsRes.ok ? await investorsRes.json() : [];
        const schemes: any[] = schemesRes.ok ? await schemesRes.json() : [];

        const schemeMap = new Map(schemes.map(s => [s.id, s]));
        const investorMap = new Map(investors.map(i => [i.id, i]));

        // ── Per-investor AUM map ─────────────────────────────────────────
        const invAum: Record<string, { mf: number; sif: number; others: number }> = {};
        let totalAum = 0, mfAum = 0, sifAum = 0, othersAum = 0;

        orders.forEach((o: any) => {
          if (o.orderStatus !== 'COMPLETED') return;
          const amt   = o.amount || 0;
          const invId = o.investorId;
          const s     = schemeMap.get(o.productSchemeId);

          const rawCat = (o.productCategory || o.category || o.product_category || s?.productCategory || s?.category || s?.product_category || s?.assetClass || 'OTHER').toString().toUpperCase();
          const cat    = rawCat.includes('MF') || rawCat.includes('MUTUAL') ? 'MF' : 'SIF';

          if (!invAum[invId]) invAum[invId] = { mf: 0, sif: 0, others: 0 };
          if (cat === 'MF') { invAum[invId].mf += amt; mfAum += amt; }
          else              { invAum[invId].sif += amt; sifAum += amt; }
          totalAum += amt;
        });

        setSummary({ totalAum, mfAum, sifAum, othersAum });
        setRawOrders(orders);
        setRawSchemes(schemeMap);

        // ── Build investor table rows ────────────────────────────────────
        const rows = Object.entries(invAum).map(([invId, aum]) => {
          const inv = investorMap.get(invId);
          const rowTotal = aum.mf + aum.sif + aum.others;
          return {
            id: invId,
            name: inv?.fullName || 'Unknown Investor',
            type: (inv?.investorType || 'RETAIL').toUpperCase(),
            mf: fmtInr(aum.mf),
            sif: fmtInr(aum.sif),
            total: fmtInr(rowTotal),
            totalRaw: rowTotal,
          };
        }).sort((a, b) => b.totalRaw - a.totalRaw);

        setInvestorRows(rows);
      } catch (err) {
        console.error('AumBreakdown: fetch error', err);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [userData?.id]);

  // ── Rebuild chart whenever range or raw orders change ─────────────────
  useEffect(() => {
    setAumTrend(buildTrend(rawOrders, rawSchemes, range));
  }, [range, rawOrders, rawSchemes]);

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-8 space-y-6"
    >
      {/* ── Breadcrumb ──────────────────────────────────────────────────── */}
      <button
        onClick={onBack}
        className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
      >
        <ArrowLeft className="w-4 h-4" /> Back to Dashboard
      </button>

      <div>
        <h1 className="text-2xl font-semibold text-slate-800">AUM Breakdown</h1>
        <p className="text-slate-500 text-sm mt-1">
          Investor-wise asset under management — Overview
        </p>
      </div>

      {loading ? (
        <div className="flex items-center justify-center h-48">
          <div className="flex flex-col items-center gap-3 text-slate-400">
            <div className="w-7 h-7 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
            <p className="text-sm">Loading AUM data…</p>
          </div>
        </div>
      ) : (
        <>
          {/* ── Summary cards ───────────────────────────────────────────── */}
          <div className="grid grid-cols-3 gap-5">
            {[
              { label: 'Total AUM', value: fmtInr(summary.totalAum), up: true, accent: 'border-blue-200   bg-blue-50   text-blue-700' },
              { label: 'Mutual Funds', value: fmtInr(summary.mfAum), up: true, accent: 'border-green-200  bg-green-50  text-green-700' },
              { label: 'Specialised Funds (SIF)', value: fmtInr(summary.sifAum), up: true, accent: 'border-violet-200 bg-violet-50 text-violet-700' },
            ].map(card => (
              <div key={card.label} className={`rounded-2xl border p-5 ${card.accent}`}>
                <p className="text-[10px] font-bold uppercase tracking-wider opacity-60 mb-2">{card.label}</p>
                <p className="text-2xl font-bold mb-1">{card.value}</p>
                <p className="text-xs font-semibold opacity-80 flex items-center gap-1">
                  {card.up ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                  Live updates
                </p>
              </div>
            ))}
          </div>

          {/* ── AUM Growth chart ─────────────────────────────────────────── */}
          <div className="bg-white rounded-2xl border border-slate-200 p-6">
            <div className="flex items-center justify-between mb-5">
              <h2 className="font-semibold text-slate-800">
                AUM Growth — {RANGE_LABELS[range]} <span className="text-slate-400 font-normal">(₹ Cr)</span>
              </h2>
              <div className="flex items-center bg-slate-100 rounded-lg p-1 gap-0.5">
                {RANGES.map(r => (
                  <button
                    key={r.key}
                    onClick={() => setRange(r.key)}
                    className={`px-3 py-1 text-xs font-semibold rounded-md transition-all ${
                      range === r.key
                        ? 'bg-white text-slate-900 shadow-sm'
                        : 'text-slate-500 hover:text-slate-700'
                    }`}
                  >
                    {r.label}
                  </button>
                ))}
              </div>
            </div>
            {aumTrend.every(t => t.MF === 0 && t.SIF === 0 && t.Others === 0) ? (
              <div className="h-48 flex items-center justify-center text-slate-400 text-sm">
                No completed orders available to plot.
              </div>
            ) : (
              <ResponsiveContainer width="100%" height={280}>
                <ComposedChart data={aumTrend} barSize={18} margin={{ top: 10, right: 16, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
                  <XAxis dataKey="month" tick={{ fontSize: 12 }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 12 }} tickFormatter={v => `₹${v}`} axisLine={false} tickLine={false} />
                  <Tooltip
                    formatter={(v: number, name: string) => [`₹${v} Cr`, name]}
                    contentStyle={{ borderRadius: 10, border: '1px solid #e2e8f0', fontSize: 12 }}
                  />
                  <Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                  <Bar dataKey="MF"  name="Mutual Funds (Monthly)"         fill="#3b82f6" radius={[4, 4, 0, 0]} fillOpacity={0.85} />
                  <Bar dataKey="SIF" name="Specialised Fund (Monthly)"     fill="#8b5cf6" radius={[4, 4, 0, 0]} fillOpacity={0.85} />
                  <Line
                    type="monotone"
                    dataKey="cumulativeTotal"
                    name="Total AUM (Cumulative)"
                    stroke="#0f172a"
                    strokeWidth={2.5}
                    dot={{ r: 3, fill: '#0f172a', strokeWidth: 0 }}
                    activeDot={{ r: 5, fill: '#0f172a' }}
                  />
                </ComposedChart>
              </ResponsiveContainer>
            )}
          </div>

          {/* ── Investor-wise table ────────────────────────────────────── */}
          <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
            <div className="px-5 py-4 border-b border-slate-100">
              <h2 className="font-semibold text-slate-800">Investor-wise AUM</h2>
            </div>
            <table className="w-full">
              <thead>
                <tr className="bg-slate-50 border-b border-slate-100">
                  {['Investor', 'Type', 'MF AUM', 'SIF AUM', 'Total AUM'].map(h => (
                    <th
                      key={h}
                      className={`py-3 px-5 text-xs font-bold text-slate-500 uppercase tracking-wider ${h === 'Investor' || h === 'Type' ? 'text-left' : 'text-right'
                        }`}
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {investorRows.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-12 text-center text-sm text-slate-400">
                      No completed orders found for AUM calculation.
                    </td>
                  </tr>
                ) : (
                  investorRows.map(inv => (
                    <tr key={inv.id} className="hover:bg-slate-50 transition-colors">
                      <td className="py-3.5 px-5 text-sm font-semibold text-slate-800">{inv.name}</td>
                      <td className="py-3.5 px-5">
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${typeBadge[inv.type] || typeBadge.RETAIL}`}>
                          {inv.type}
                        </span>
                      </td>
                      <td className="py-3.5 px-5 text-sm text-right font-mono text-slate-700">{inv.mf}</td>
                      <td className="py-3.5 px-5 text-sm text-right font-mono text-slate-700">{inv.sif}</td>
                      <td className="py-3.5 px-5 text-sm text-right font-mono font-semibold text-slate-800">{inv.total}</td>
                    </tr>
                  ))
                )}
              </tbody>
              {investorRows.length > 0 && (
                <tfoot>
                  <tr className="bg-slate-50 border-t-2 border-slate-200">
                    <td colSpan={2} className="py-3.5 px-5 text-xs font-bold text-slate-500 uppercase tracking-wider">Total</td>
                    <td className="py-3.5 px-5 text-sm text-right font-mono font-bold text-slate-800">{fmtInr(summary.mfAum)}</td>
                    <td className="py-3.5 px-5 text-sm text-right font-mono font-bold text-slate-800">{fmtInr(summary.sifAum)}</td>
                    <td className="py-3.5 px-5 text-sm text-right font-mono font-bold text-slate-800">{fmtInr(summary.totalAum)}</td>
                  </tr>
                </tfoot>
              )}
            </table>
          </div>
        </>
      )}
    </motion.div>
  );
}
