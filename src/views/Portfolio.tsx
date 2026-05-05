import React, { useState, useEffect } from 'react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { DollarSign, BarChart2, TrendingUp, Users } from 'lucide-react';

const BASE = 'http://localhost:8081/api/v1';

function fmt(n: number) {
  if (n >= 10_000_000) return `₹${(n / 10_000_000).toFixed(2)}Cr`;
  if (n >= 100_000)    return `₹${(n / 100_000).toFixed(2)}L`;
  return `₹${n.toLocaleString('en-IN')}`;
}

const STATUSES_AUM = new Set(['SUCCESSFUL', 'COMPLETED']);

export default function Portfolio({ userData }: { userData?: any }) {
  const [orders, setOrders]       = useState<any[]>([]);
  const [investors, setInvestors] = useState<any[]>([]);
  const [schemes, setSchemes]     = useState<any[]>([]);
  const [loading, setLoading]     = useState(true);

  useEffect(() => {
    if (!userData?.id) { setLoading(false); return; }

    const token = userData.token || sessionStorage.getItem('token') || '';
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    Promise.all([
      fetch(`${BASE}/orders/by-distributor/${userData.id}`, { headers }).then(r => r.ok ? r.json() : []),
      fetch(`${BASE}/investors/by-distributor/${userData.id}`, { headers }).then(r => r.ok ? r.json() : []),
      fetch(`${BASE}/products/schemes`, { headers }).then(r => r.ok ? r.json() : []),
    ])
      .then(([ord, inv, sch]) => {
        setOrders(Array.isArray(ord) ? ord : []);
        setInvestors(Array.isArray(inv) ? inv : []);
        setSchemes(Array.isArray(sch) ? sch : []);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [userData?.id]);

  const completedOrders = orders.filter(o => STATUSES_AUM.has(o.orderStatus));

  const totalAum = completedOrders.reduce((s, o) => s + (o.amount || 0), 0);
  const mfTotal  = completedOrders.filter(o => o.productCategory === 'MF' || o.productCategory === 'EQUITY').reduce((s, o) => s + (o.amount || 0), 0);
  const sifTotal = completedOrders.filter(o => o.productCategory === 'SIF').reduce((s, o) => s + (o.amount || 0), 0);

  const schemeMap = Object.fromEntries(schemes.map(s => [s.id, s]));

  const investorRows = investors
    .map(inv => {
      const inv_orders = completedOrders.filter(o => o.investorId === inv.id);
      const totalInv   = inv_orders.reduce((s, o) => s + (o.amount || 0), 0);
      const mfInv      = inv_orders.filter(o => o.productCategory === 'MF' || o.productCategory === 'EQUITY').reduce((s, o) => s + (o.amount || 0), 0);
      const sifInv     = inv_orders.filter(o => o.productCategory === 'SIF').reduce((s, o) => s + (o.amount || 0), 0);
      return { ...inv, totalAum: totalInv, mfAum: mfInv, sifAum: sifInv };
    })
    .filter(r => r.totalAum > 0)
    .sort((a, b) => b.totalAum - a.totalAum);

  const schemeAumMap: Record<string, { scheme: any; total: number; investorSet: Set<string> }> = {};
  completedOrders.forEach(o => {
    const sid = o.productSchemeId;
    if (!sid) return;
    if (!schemeAumMap[sid]) schemeAumMap[sid] = { scheme: schemeMap[sid] || {}, total: 0, investorSet: new Set() };
    schemeAumMap[sid].total += o.amount || 0;
    schemeAumMap[sid].investorSet.add(o.investorId);
  });
  const schemeRows = Object.values(schemeAumMap)
    .sort((a, b) => b.total - a.total)
    .slice(0, 10);

  const pieData = [
    { name: 'Mutual Funds', value: mfTotal,  color: '#3B82F6' },
    { name: 'SIF',          value: sifTotal, color: '#8B5CF6' },
  ].filter(d => d.value > 0);

  const summaryCards = [
    { label: 'Total AUM',    value: fmt(totalAum), icon: <DollarSign className="w-5 h-5" />, bg: 'bg-blue-50',   text: 'text-blue-600' },
    { label: 'MF AUM',       value: fmt(mfTotal),  icon: <BarChart2 className="w-5 h-5" />,  bg: 'bg-indigo-50', text: 'text-indigo-600' },
    { label: 'SIF AUM',      value: fmt(sifTotal), icon: <TrendingUp className="w-5 h-5" />, bg: 'bg-violet-50', text: 'text-violet-600' },
    { label: 'Total Investors', value: investorRows.length, icon: <Users className="w-5 h-5" />, bg: 'bg-slate-50', text: 'text-slate-600' },
  ];

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-xl font-bold text-slate-800">Portfolio</h1>
        <p className="text-sm text-slate-500 mt-0.5">Consolidated holdings and AUM across all investors</p>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {summaryCards.map(card => (
          <div key={card.label} className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
            <div className={`w-9 h-9 rounded-xl ${card.bg} flex items-center justify-center ${card.text} mb-3`}>
              {card.icon}
            </div>
            <p className="text-xs text-slate-500 font-medium">{card.label}</p>
            <p className="text-2xl font-bold text-slate-800 mt-1">{card.value}</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Allocation donut */}
        <div className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-700 mb-4">MF vs SIF Allocation</h2>
          {pieData.length > 0 ? (
            <>
              <ResponsiveContainer width="100%" height={180}>
                <PieChart>
                  <Pie
                    data={pieData}
                    cx="50%" cy="50%"
                    innerRadius={50} outerRadius={75}
                    dataKey="value"
                    paddingAngle={3}
                  >
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

        {/* Top investors */}
        <div className="lg:col-span-2 bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-700 mb-4">Top Investors by AUM</h2>
          {investorRows.length === 0 ? (
            <div className="h-48 flex items-center justify-center text-sm text-slate-400">
              No completed transactions yet
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-xs font-semibold text-slate-400 border-b border-slate-100">
                    <th className="text-left pb-3">Investor</th>
                    <th className="text-right pb-3">MF AUM</th>
                    <th className="text-right pb-3">SIF AUM</th>
                    <th className="text-right pb-3">Total AUM</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-50">
                  {investorRows.slice(0, 8).map(inv => (
                    <tr key={inv.id} className="hover:bg-slate-50/80">
                      <td className="py-3 font-medium text-slate-800">{inv.fullName || inv.full_name}</td>
                      <td className="py-3 text-right text-slate-500">{fmt(inv.mfAum)}</td>
                      <td className="py-3 text-right text-slate-500">{fmt(inv.sifAum)}</td>
                      <td className="py-3 text-right font-semibold text-slate-800">{fmt(inv.totalAum)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Top schemes */}
      <div className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
        <h2 className="text-sm font-semibold text-slate-700 mb-4">Top Schemes by AUM</h2>
        {schemeRows.length === 0 ? (
          <div className="h-20 flex items-center justify-center text-sm text-slate-400">
            No scheme data yet
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs font-semibold text-slate-400 border-b border-slate-100">
                  <th className="text-left pb-3">Scheme</th>
                  <th className="text-left pb-3">AMC</th>
                  <th className="text-left pb-3">Category</th>
                  <th className="text-right pb-3">Investors</th>
                  <th className="text-right pb-3">Invested Value</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {schemeRows.map(({ scheme, total, investorSet }, i) => (
                  <tr key={i} className="hover:bg-slate-50/80">
                    <td className="py-3 font-medium text-slate-800">{scheme.schemeName || scheme.scheme_name || '—'}</td>
                    <td className="py-3 text-slate-500">{scheme.amcName || scheme.amc_name || '—'}</td>
                    <td className="py-3">
                      <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${
                        scheme.category === 'SIF'
                          ? 'bg-violet-50 text-violet-600'
                          : 'bg-blue-50 text-blue-600'
                      }`}>
                        {scheme.category || '—'}
                      </span>
                    </td>
                    <td className="py-3 text-right text-slate-600">{investorSet.size}</td>
                    <td className="py-3 text-right font-semibold text-slate-800">{fmt(total)}</td>
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
