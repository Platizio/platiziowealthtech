import React from 'react';
import { motion } from 'motion/react';
import { ArrowUpRight, Users, Building2, TrendingUp, ArrowDownUp } from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, PieChart, Pie, Cell
} from 'recharts';

const aumTrend = [
  { month: 'Nov', aum: 280 },
  { month: 'Dec', aum: 305 },
  { month: 'Jan', aum: 320 },
  { month: 'Feb', aum: 340 },
  { month: 'Mar', aum: 375 },
  { month: 'Apr', aum: 412 },
];

const assetDist = [
  { name: 'Equity MF', value: 58, color: '#3b82f6' },
  { name: 'Debt MF',   value: 24, color: '#22c55e' },
  { name: 'SIF',       value: 10, color: '#8b5cf6' },
  { name: 'Liquid',    value:  8, color: '#f59e0b' },
];

const recentTxns = [
  { investor: 'Aditya Sharma',      distributor: 'Direct',             type: 'SIP',       amount: '₹5,000',    status: 'Successful', time: '10m ago' },
  { investor: 'Meera Iyer',         distributor: 'Rahul Distributors', type: 'Lumpsum',   amount: '₹1,00,000', status: 'Pending',    time: '45m ago' },
  { investor: 'Sunita Kapur',       distributor: 'Direct',             type: 'Redemption',amount: '₹50,000',   status: 'Processing', time: '2h ago'  },
  { investor: 'Tech Innovations PF',distributor: 'ProFunds',           type: 'Lumpsum',   amount: '₹5,00,000', status: 'Successful', time: '5h ago'  },
  { investor: 'Rahul Verma',        distributor: 'WealthEdge',         type: 'SIP',       amount: '₹10,000',   status: 'Successful', time: '1d ago'  },
];

const topDist = [
  { name: 'Rahul Distributors',  aum: '₹85 Cr', investors: 420, tier: 'Platinum', growth: '+12%' },
  { name: 'WealthEdge Advisory', aum: '₹62 Cr', investors: 310, tier: 'Platinum', growth: '+8%'  },
  { name: 'ProFunds India',      aum: '₹38 Cr', investors: 195, tier: 'Gold',     growth: '+15%' },
  { name: 'Apex Partners',       aum: '₹22 Cr', investors: 112, tier: 'Gold',     growth: '+6%'  },
  { name: 'FinTree Wealth',      aum: '₹8 Cr',  investors:  58, tier: 'Silver',   growth: '+22%' },
];

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

export default function AdminOverview() {
  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-4 md:p-8 space-y-6">
      <div className="flex flex-wrap justify-between items-end gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Master Overview</h1>
          <p className="text-slate-500 text-sm mt-1">Full network view across all distributors and investors</p>
        </div>
        <div className="flex gap-1 text-xs font-semibold text-slate-500 uppercase tracking-wider bg-slate-100 p-1 rounded-lg">
          <button className="px-3 py-1.5 bg-white shadow-sm rounded text-slate-900">Today</button>
          <button className="px-3 py-1.5 hover:text-slate-700 rounded">Monthly</button>
          <button className="px-3 py-1.5 hover:text-slate-700 rounded">YTD</button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-6">
        {[
          { label: 'Total AUM',         value: '₹412 Cr',  trend: '↑ 9.8% this month',    trendColor: 'text-green-500',  icon: <TrendingUp   className="w-5 h-5 text-green-400"  /> },
          { label: 'Total Investors',   value: '1,240',    trend: '+18 this month',        trendColor: 'text-blue-500',   icon: <Users        className="w-5 h-5 text-blue-400"   /> },
          { label: 'Total Distributors',value: '8',        trend: '2 pending approval',    trendColor: 'text-amber-500',  icon: <Building2    className="w-5 h-5 text-amber-400"  /> },
          { label: 'Net Inflow (Apr)',  value: '₹12.4 Cr', trend: '↑ 22% vs last month',  trendColor: 'text-green-500',  icon: <ArrowDownUp  className="w-5 h-5 text-emerald-400"/> },
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
            <span className="text-xs font-semibold text-green-600 bg-green-50 px-3 py-1 rounded-full border border-green-100">↑ 47% over 6M</span>
          </div>
          <div className="h-52">
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
                <Area type="monotone" dataKey="aum" stroke="#0B1B3E" strokeWidth={2.5} fillOpacity={1} fill="url(#aumGrad)" dot={{ fill: '#0B1B3E', strokeWidth: 0, r: 4 }} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Asset Distribution */}
        <div className="bg-[#0B1B3E] rounded-2xl p-6 text-white flex flex-col relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-blue-500 opacity-10 rounded-full blur-2xl" />
          <h2 className="font-semibold mb-1">Asset Distribution</h2>
          <p className="text-xs text-blue-300 mb-4">% of total AUM by class</p>
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
        </div>
      </div>

      {/* Bottom row */}
      <div className="grid grid-cols-3 gap-6">
        {/* Recent Transactions */}
        <div className="col-span-2 bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div className="p-5 border-b border-slate-100 flex justify-between items-center">
            <h2 className="font-semibold text-slate-800">Recent Transactions</h2>
            <button className="text-[#0B1B3E] text-xs font-semibold flex items-center gap-1 hover:underline">
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
                {recentTxns.map((t, i) => (
                  <tr key={i} className="hover:bg-slate-50 transition-colors cursor-pointer">
                    <td className="px-5 py-3.5 text-sm font-semibold text-slate-800">{t.investor}</td>
                    <td className="px-5 py-3.5 text-xs text-slate-500">{t.distributor}</td>
                    <td className="px-5 py-3.5">
                      <div className="text-sm font-mono font-medium text-slate-700">{t.amount}</div>
                      <div className="text-[10px] text-slate-400 mt-0.5">{t.type}</div>
                    </td>
                    <td className="px-5 py-3.5 text-right">
                      <span className={`text-xs font-semibold ${statusColors[t.status] ?? 'text-slate-500'}`}>{t.status}</span>
                      <div className="text-[10px] text-slate-400 mt-0.5">{t.time}</div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* Top Distributors */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div className="p-5 border-b border-slate-100 flex justify-between items-center">
            <h2 className="font-semibold text-slate-800">Top Distributors</h2>
            <button className="text-[#0B1B3E] text-xs font-semibold flex items-center gap-1 hover:underline">
              View All <ArrowUpRight className="w-3 h-3" />
            </button>
          </div>
          <div className="divide-y divide-slate-100">
            {topDist.map((d, i) => (
              <div key={i} className="px-5 py-4 flex items-center gap-3 hover:bg-slate-50 transition-colors cursor-pointer">
                <div className="w-7 h-7 rounded-full bg-slate-100 flex items-center justify-center text-xs font-bold text-slate-600 flex-shrink-0">
                  {i + 1}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold text-slate-800 truncate">{d.name}</p>
                  <p className="text-xs text-slate-500 mt-0.5">{d.aum} · {d.investors} investors</p>
                </div>
                <div className="text-right flex-shrink-0">
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded ${tierColors[d.tier] ?? 'bg-slate-100 text-slate-600'}`}>
                    {d.tier}
                  </span>
                  <p className="text-[10px] text-green-500 font-semibold mt-0.5">{d.growth}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </motion.div>
  );
}
