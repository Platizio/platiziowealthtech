import React, { useState, useEffect, useCallback } from 'react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { DollarSign, BarChart2, TrendingUp, Users, AlertTriangle } from 'lucide-react';
import { apiFetch } from '../config/api';

function fmt(n: number) {
  if (n >= 10_000_000) return `₹${(n / 10_000_000).toFixed(2)}Cr`;
  if (n >= 100_000) return `₹${(n / 100_000).toFixed(2)}L`;
  return `₹${n.toLocaleString('en-IN')}`;
}

const num = (v: unknown) => Number(v) || 0;

const statusClass = (displayStatus: string) => {
  const s = String(displayStatus || '').toLowerCase();
  if (s.includes('failed')) return 'bg-red-50 text-red-700';
  if (s.includes('cancelled')) return 'bg-slate-100 text-slate-600';
  if (s.includes('pending')) return 'bg-amber-50 text-amber-700';
  if (s.includes('successful') || s.includes('active') || s.includes('completed')) return 'bg-green-50 text-green-700';
  return 'bg-slate-50 text-slate-600';
};

type PortfolioPayload = {
  summary?: {
    totalAum?: number;
    mfAum?: number;
    sifAum?: number;
    investorCount?: number;
    holdingCount?: number;
    failedOrUnknownCount?: number;
  };
  holdings?: Array<{
    orderId: string;
    investorName: string;
    schemeName: string;
    amcName: string;
    category: string;
    amount: number;
    displayStatus: string;
    orderStatus: string;
    schemeKnown: boolean;
    countsTowardAum: boolean;
    transactionType: string;
    paymentMode?: string;
    mandateStatus?: string;
    externalMandateId?: number;
    sipFrequency?: string;
    failureReason?: string;
  }>;
  investors?: Array<{
    investorId: string;
    investorName: string;
    totalAum: number;
    mfAum: number;
    sifAum: number;
  }>;
  schemes?: Array<{
    schemeName: string;
    amcName: string;
    category: string;
    investedValue: number;
    dailyReturn?: number;
    ytdReturn?: number;
    oneYearReturn?: number;
    fiveYearReturn?: number;
  }>;
  sipMandates?: Array<{
    orderId: string;
    investorName: string;
    schemeName: string;
    amcName: string;
    category: string;
    amount: number;
    sipFrequency?: string;
    mandateId?: number;
    mandateStatus?: string;
    mandateMode?: string;
    planId?: string;
    setupStatus: string;
    orderStatus?: string;
  }>;
};

export default function Portfolio({ userData }: { userData?: any }) {
  const [data, setData] = useState<PortfolioPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [catFilter, setCatFilter] = useState<'ALL' | 'MF' | 'SIF'>('ALL');

  const loadPortfolio = useCallback(async () => {
    if (!userData?.id) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const response = await apiFetch(
        `/dashboard/distributor/${userData.id}/portfolio?category=${catFilter}`,
        { headers: { 'Content-Type': 'application/json' } },
      );
      if (!response.ok) {
        throw new Error(`Portfolio load failed (${response.status})`);
      }
      setData(await response.json());
    } catch (err: any) {
      setError(err?.message || 'Could not load portfolio');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [userData?.id, catFilter]);

  useEffect(() => {
    void loadPortfolio();
  }, [loadPortfolio]);

  const summary = data?.summary;
  const holdings = data?.holdings || [];
  const sipMandates = data?.sipMandates || [];
  const investors = data?.investors || [];
  const schemes = data?.schemes || [];

  const totalAum = num(summary?.totalAum);
  const mfTotal = num(summary?.mfAum);
  const sifTotal = num(summary?.sifAum);

  const pieData = [
    { name: 'Mutual Funds', value: mfTotal, color: '#3B82F6' },
    { name: 'SIF', value: sifTotal, color: '#8B5CF6' },
  ].filter(d => d.value > 0);

  const getReturnColor = (val?: number) => {
    if (val === undefined || val === null) return 'text-slate-500';
    return val >= 0 ? 'text-green-600' : 'text-red-600';
  };

  const fmtReturn = (val?: number) =>
    val === undefined || val === null ? '—' : `${val > 0 ? '+' : ''}${val}%`;

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-6">
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <p className="font-semibold">Portfolio unavailable</p>
          <p className="text-sm mt-1">{error}</p>
          <button onClick={() => void loadPortfolio()} className="mt-4 text-sm font-semibold underline">
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex justify-between items-end">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Portfolio</h1>
          <p className="text-sm text-slate-500 mt-0.5">
            Live holdings from completed orders (Cybrilla POA). Unknown funds show as payment failed.
          </p>
        </div>
        <div className="flex gap-1 bg-slate-100 p-1 rounded-lg text-[10px] font-bold uppercase tracking-wider">
          {(['ALL', 'MF', 'SIF'] as const).map(c => (
            <button
              key={c}
              onClick={() => setCatFilter(c)}
              className={`px-3 py-1.5 rounded ${catFilter === c ? 'bg-white shadow-sm text-slate-900' : 'text-slate-500 hover:text-slate-700'}`}
            >
              {c === 'ALL' ? 'All Assets' : c === 'MF' ? 'Mutual Funds' : 'SIF'}
            </button>
          ))}
        </div>
      </div>

      {num(summary?.failedOrUnknownCount) > 0 && (
        <div className="flex items-start gap-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0" />
          <p>
            {summary?.failedOrUnknownCount} order(s) have an unknown fund or failed payment and are excluded from AUM.
          </p>
        </div>
      )}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Total AUM', value: fmt(totalAum), icon: <DollarSign className="w-5 h-5" />, bg: 'bg-blue-50', text: 'text-blue-600' },
          { label: 'Mutual Funds', value: fmt(mfTotal), icon: <BarChart2 className="w-5 h-5" />, bg: 'bg-green-50', text: 'text-green-600' },
          { label: 'Specialised Funds (SIF)', value: fmt(sifTotal), icon: <TrendingUp className="w-5 h-5" />, bg: 'bg-violet-50', text: 'text-violet-600' },
          { label: 'Investors with AUM', value: summary?.investorCount ?? 0, icon: <Users className="w-5 h-5" />, bg: 'bg-slate-50', text: 'text-slate-600' },
        ].map(card => (
          <div key={card.label} className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
            <div className={`w-9 h-9 rounded-xl ${card.bg} flex items-center justify-center ${card.text} mb-3`}>
              {card.icon}
            </div>
            <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{card.label}</p>
            <p className="text-2xl font-bold text-slate-800">{card.value}</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-700 mb-4">Asset Allocation</h2>
          {pieData.length > 0 ? (
            <>
              <ResponsiveContainer width="100%" height={180}>
                <PieChart>
                  <Pie data={pieData} cx="50%" cy="50%" innerRadius={50} outerRadius={75} dataKey="value" paddingAngle={3}>
                    {pieData.map((entry, i) => <Cell key={i} fill={entry.color} />)}
                  </Pie>
                  <Tooltip formatter={(v: any) => fmt(Number(v))} />
                </PieChart>
              </ResponsiveContainer>
              <div className="space-y-2 mt-2">
                {pieData.map(d => (
                  <div key={d.name} className="flex items-center justify-between text-xs">
                    <span className="flex items-center gap-1.5">
                      <span className="w-2.5 h-2.5 rounded-full inline-block" style={{ background: d.color }} />
                      {d.name}
                    </span>
                    <span className="font-semibold text-slate-700">{fmt(d.value)}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="h-48 flex flex-col items-center justify-center text-slate-300 gap-2">
              <PieChart className="w-8 h-8" />
              <p className="text-sm">No AUM data yet</p>
            </div>
          )}
        </div>

        <div className="lg:col-span-2 bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-700 mb-4">
            {catFilter === 'ALL' ? 'Top Investors by AUM' : `Top ${catFilter} Investors`}
          </h2>
          {investors.length === 0 ? (
            <div className="h-48 flex items-center justify-center text-sm text-slate-400">No investors with holdings in this category</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-xs font-semibold text-slate-400 border-b border-slate-100">
                    <th className="text-left pb-3">Investor</th>
                    {catFilter === 'ALL' ? (
                      <>
                        <th className="text-right pb-3">MF AUM</th>
                        <th className="text-right pb-3">SIF AUM</th>
                      </>
                    ) : (
                      <th className="text-right pb-3">{catFilter} AUM</th>
                    )}
                    <th className="text-right pb-3">Total AUM</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-50">
                  {investors.slice(0, 8).map(inv => (
                    <tr key={inv.investorId} className="hover:bg-slate-50/80">
                      <td className="py-3 font-medium text-slate-800">{inv.investorName}</td>
                      {catFilter === 'ALL' ? (
                        <>
                          <td className="py-3 text-right text-slate-500">{fmt(num(inv.mfAum))}</td>
                          <td className="py-3 text-right text-slate-500">{fmt(num(inv.sifAum))}</td>
                        </>
                      ) : (
                        <td className="py-3 text-right text-slate-500">
                          {fmt(catFilter === 'MF' ? num(inv.mfAum) : num(inv.sifAum))}
                        </td>
                      )}
                      <td className="py-3 text-right font-semibold text-slate-800">{fmt(num(inv.totalAum))}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      <div className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-700 mb-4">Holdings</h2>
        {holdings.length === 0 ? (
          <div className="h-20 flex items-center justify-center text-sm text-slate-400">
            No completed holdings yet. Lumpsum and active SIP orders appear here after payment.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs font-semibold text-slate-400 border-b border-slate-100">
                  <th className="text-left pb-3">Investor</th>
                  <th className="text-left pb-3">Scheme</th>
                  <th className="text-left pb-3">Type</th>
                  <th className="text-right pb-3">Amount</th>
                  <th className="text-left pb-3">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {holdings.map(row => {
                  const isSip = String(row.transactionType || '').toUpperCase() === 'SIP';
                  return (
                    <tr key={row.orderId} className="hover:bg-slate-50/80">
                      <td className="py-3 font-medium text-slate-800">{row.investorName}</td>
                      <td className="py-3">
                        <p className="font-medium text-slate-800">{row.schemeName}</p>
                        <p className="text-xs text-slate-500">{row.amcName}</p>
                        {!row.schemeKnown && row.failureReason && (
                          <p className="text-xs text-red-600 mt-1">{row.failureReason}</p>
                        )}
                      </td>
                      <td className="py-3">
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${isSip ? 'bg-indigo-50 text-indigo-700' : 'bg-emerald-50 text-emerald-700'}`}>
                          {isSip ? `SIP ${row.sipFrequency || ''}`.trim() : 'Lumpsum'}
                        </span>
                      </td>
                      <td className="py-3 text-right font-semibold text-slate-800">{fmt(num(row.amount))}</td>
                      <td className="py-3">
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${statusClass(row.displayStatus)}`}>
                          {row.displayStatus}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="bg-white rounded-2xl p-5 border border-indigo-100 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-700 mb-1">SIP Mandates</h2>
        <p className="text-xs text-slate-500 mb-4">
          Mandate registration and plan setup — per Cybrilla flow, the SIP plan is created only after mandate approval.
        </p>
        {sipMandates.length === 0 ? (
          <div className="h-20 flex items-center justify-center text-sm text-slate-400">
            No SIP mandates yet. Place a mandate SIP from Ledger and complete investor authorization.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs font-semibold text-slate-400 border-b border-slate-100">
                  <th className="text-left pb-3">Investor</th>
                  <th className="text-left pb-3">Scheme</th>
                  <th className="text-right pb-3">SIP Amount</th>
                  <th className="text-left pb-3">Mandate</th>
                  <th className="text-left pb-3">Plan ID</th>
                  <th className="text-left pb-3">Setup Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {sipMandates.map(row => (
                  <tr key={row.orderId} className="hover:bg-slate-50/80">
                    <td className="py-3 font-medium text-slate-800">{row.investorName}</td>
                    <td className="py-3">
                      <p className="font-medium text-slate-800">{row.schemeName}</p>
                      <p className="text-xs text-slate-500">{row.amcName} · {row.sipFrequency || 'monthly'}</p>
                    </td>
                    <td className="py-3 text-right font-semibold text-slate-800">{fmt(num(row.amount))}</td>
                    <td className="py-3 text-slate-600">
                      {row.mandateId
                        ? `${row.mandateMode || 'MANDATE'} #${row.mandateId} · ${row.mandateStatus || 'CREATED'}`
                        : (row.mandateMode || '—')}
                    </td>
                    <td className="py-3 text-xs font-mono text-slate-500">{row.planId || '—'}</td>
                    <td className="py-3">
                      <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${statusClass(row.setupStatus)}`}>
                        {row.setupStatus}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-700 mb-4">
          {catFilter === 'ALL' ? 'Top Schemes by AUM' : `Top ${catFilter} Schemes`}
        </h2>
        {schemes.length === 0 ? (
          <div className="h-20 flex items-center justify-center text-sm text-slate-400">No scheme allocations in this category</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs font-semibold text-slate-400 border-b border-slate-100">
                  <th className="text-left pb-3">Scheme</th>
                  <th className="text-left pb-3">Category</th>
                  <th className="text-right pb-3">Invested Value</th>
                  <th className="text-right pb-3">Daily</th>
                  <th className="text-right pb-3">YTD</th>
                  <th className="text-right pb-3">1Y</th>
                  <th className="text-right pb-3">5Y</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {schemes.map((scheme, i) => (
                  <tr key={i} className="hover:bg-slate-50/80">
                    <td className="py-3">
                      <p className="font-medium text-slate-800">{scheme.schemeName}</p>
                      <p className="text-xs text-slate-500">{scheme.amcName}</p>
                    </td>
                    <td className="py-3">
                      <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${scheme.category === 'MF' ? 'bg-blue-50 text-blue-600' : 'bg-violet-50 text-violet-600'}`}>
                        {scheme.category}
                      </span>
                    </td>
                    <td className="py-3 text-right font-semibold text-slate-800">{fmt(num(scheme.investedValue))}</td>
                    <td className={`py-3 text-right font-medium ${getReturnColor(scheme.dailyReturn)}`}>{fmtReturn(scheme.dailyReturn)}</td>
                    <td className={`py-3 text-right font-medium ${getReturnColor(scheme.ytdReturn)}`}>{fmtReturn(scheme.ytdReturn)}</td>
                    <td className={`py-3 text-right font-medium ${getReturnColor(scheme.oneYearReturn)}`}>{fmtReturn(scheme.oneYearReturn)}</td>
                    <td className={`py-3 text-right font-medium ${getReturnColor(scheme.fiveYearReturn)}`}>{fmtReturn(scheme.fiveYearReturn)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
