import React, { useState, useEffect } from 'react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { DollarSign, BarChart2, TrendingUp, Users } from 'lucide-react';
import { API_BASE_URL } from '../config/api';

const BASE = API_BASE_URL;

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

  const schemeMap = Object.fromEntries(schemes.map(s => [s.id, s]));

  const getCategory = (o: any) => {
    const s = schemeMap[o.productSchemeId];
    const rawCat = (o.productCategory || o.category || o.product_category || s?.productCategory || s?.category || s?.product_category || s?.assetClass || 'OTHER').toString().toUpperCase();
    return (rawCat.includes('MF') || rawCat.includes('MUTUAL')) ? 'MF' : 'SIF';
  };

  const completedOrders = orders.filter(o => STATUSES_AUM.has(o.orderStatus));

  let totalAum = 0, mfTotal = 0, sifTotal = 0;
  completedOrders.forEach(o => {
    const amt = o.amount || 0;
    const cat = getCategory(o);
    if (cat === 'MF') mfTotal += amt;
    else sifTotal += amt;
    totalAum += amt;
  });

  const investorRows = investors
    .map(inv => {
      const inv_orders = completedOrders.filter(o => o.investorId === inv.id);
      let t = 0, mf = 0, sif = 0;
      inv_orders.forEach(o => {
        const amt = o.amount || 0;
        const cat = getCategory(o);
        if (cat === 'MF') mf += amt;
        else sif += amt;
        t += amt;
      });
      return { ...inv, totalAum: t, mfAum: mf, sifAum: sif };
    })
    .filter(r => r.totalAum > 0)
    .sort((a, b) => b.totalAum - a.totalAum);

  const [catFilter, setCatFilter] = useState<'ALL' | 'MF' | 'SIF'>('ALL');

  const parseMetadata = (jsonStr?: string) => {
    try {
      return jsonStr ? JSON.parse(jsonStr) : {};
    } catch {
      return {};
    }
  };

  const getReturnColor = (val: number | undefined) => {
    if (val === undefined) return 'text-slate-500';
    return val >= 0 ? 'text-green-600' : 'text-red-600';
  };

  // Top schemes
  const schemeAumMap: Record<string, { scheme: any; total: number; investorSet: Set<string> }> = {};
  completedOrders.forEach(o => {
    const sid = o.productSchemeId;
    if (!sid) return;
    const cat = getCategory(o);
    if (catFilter !== 'ALL' && cat !== catFilter) return;

    if (!schemeAumMap[sid]) schemeAumMap[sid] = { scheme: schemeMap[sid] || {}, total: 0, investorSet: new Set() };
    schemeAumMap[sid].total += o.amount || 0;
    schemeAumMap[sid].investorSet.add(o.investorId);
  });
  const schemeRows = Object.values(schemeAumMap)
    .sort((a, b) => b.total - a.total)
    .slice(0, 10);

  const filteredInvestorRows = investorRows.map(inv => {
    if (catFilter === 'ALL') return inv;
    if (catFilter === 'MF' && inv.mfAum > 0) return { ...inv, displayAum: inv.mfAum };
    if (catFilter === 'SIF' && inv.sifAum > 0) return { ...inv, displayAum: inv.sifAum };
    return null;
  }).filter(Boolean) as any[];

  const pieData = [
    { name: 'Mutual Funds', value: mfTotal,  color: '#3B82F6' },
    { name: 'SIF',          value: sifTotal, color: '#8B5CF6' },
  ].filter(d => d.value > 0);

  const summaryCards = [
    { label: 'Total AUM',    value: fmt(totalAum), icon: <DollarSign className="w-5 h-5" />, bg: 'bg-blue-50',   text: 'text-blue-600' },
    { label: 'Mutual Funds', value: fmt(mfTotal),  icon: <BarChart2 className="w-5 h-5" />,  bg: 'bg-green-50', text: 'text-green-600' },
    { label: 'Specialised Funds (SIF)', value: fmt(sifTotal), icon: <TrendingUp className="w-5 h-5" />, bg: 'bg-violet-50', text: 'text-violet-600' },
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
      <div className="flex justify-between items-end">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Portfolio</h1>
          <p className="text-sm text-slate-500 mt-0.5">Consolidated holdings and AUM across all investors</p>
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

      {/* Summary cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {summaryCards.map(card => (
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
        {/* Allocation donut */}
        <div className="bg-white rounded-2xl p-5 border border-slate-100 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-700 mb-4">Asset Allocation</h2>
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
          <h2 className="text-sm font-semibold text-slate-700 mb-4">
            {catFilter === 'ALL' ? 'Top Investors by AUM' : `Top ${catFilter} Investors`}
          </h2>
          {filteredInvestorRows.length === 0 ? (
            <div className="h-48 flex items-center justify-center text-sm text-slate-400">
              No investors found for this category
            </div>
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
                  {filteredInvestorRows.slice(0, 8).map(inv => (
                    <tr key={inv.id} className="hover:bg-slate-50/80">
                      <td className="py-3 font-medium text-slate-800">{inv.fullName || inv.full_name}</td>
                      {catFilter === 'ALL' ? (
                        <>
                          <td className="py-3 text-right text-slate-500">{fmt(inv.mfAum)}</td>
                          <td className="py-3 text-right text-slate-500">{fmt(inv.sifAum)}</td>
                        </>
                      ) : (
                        <td className="py-3 text-right text-slate-500">{fmt(inv.displayAum)}</td>
                      )}
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
        <h2 className="text-sm font-semibold text-slate-700 mb-4">
          {catFilter === 'ALL' ? 'Top Schemes by AUM' : `Top ${catFilter} Schemes`}
        </h2>
        {schemeRows.length === 0 ? (
          <div className="h-20 flex items-center justify-center text-sm text-slate-400">
            No schemes found for this category
          </div>
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
                {schemeRows.map(({ scheme, total, investorSet }, i) => {
                  const meta = parseMetadata(scheme.metadataJson || scheme.metadata_json);
                  const ret = meta.returns || {};
                  const rawCat = (scheme.productCategory || scheme.category || scheme.product_category || scheme.assetClass || 'OTHER').toString().toUpperCase();
                  const isMF = rawCat.includes('MF') || rawCat.includes('MUTUAL');
                  return (
                    <tr key={i} className="hover:bg-slate-50/80">
                      <td className="py-3">
                        <p className="font-medium text-slate-800">{scheme.schemeName || scheme.scheme_name || '—'}</p>
                        <p className="text-xs text-slate-500">{scheme.amcName || scheme.amc_name || '—'}</p>
                      </td>
                      <td className="py-3">
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${
                          isMF ? 'bg-blue-50 text-blue-600' : 'bg-violet-50 text-violet-600'
                        }`}>
                          {isMF ? 'MF' : 'SIF'}
                        </span>
                      </td>
                      <td className="py-3 text-right font-semibold text-slate-800">{fmt(total)}</td>
                      <td className={`py-3 text-right font-medium ${getReturnColor(ret.daily)}`}>{ret.daily !== undefined ? `${ret.daily > 0 ? '+' : ''}${ret.daily}%` : '—'}</td>
                      <td className={`py-3 text-right font-medium ${getReturnColor(ret.ytd)}`}>{ret.ytd !== undefined ? `${ret.ytd > 0 ? '+' : ''}${ret.ytd}%` : '—'}</td>
                      <td className={`py-3 text-right font-medium ${getReturnColor(ret['1y'])}`}>{ret['1y'] !== undefined ? `${ret['1y'] > 0 ? '+' : ''}${ret['1y']}%` : '—'}</td>
                      <td className={`py-3 text-right font-medium ${getReturnColor(ret['5y'])}`}>{ret['5y'] !== undefined ? `${ret['5y'] > 0 ? '+' : ''}${ret['5y']}%` : '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
