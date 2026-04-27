import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Search, Filter, ChevronLeft, Download, ShieldCheck,
  AreaChart, Activity, TrendingUp,
} from 'lucide-react';
import { AreaChart as RechartsArea, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

interface Investor {
  id: number;
  name: string;
  type: string;
  kyc: string;
  aum: string;
  lastActive: string;
}

const investors: Investor[] = [
  { id: 1, name: 'Aditya Sharma',       type: 'HNI',           kyc: 'Verified', aum: '₹1.2 Cr', lastActive: '2 days ago' },
  { id: 2, name: 'Meera Iyer',          type: 'Retail',        kyc: 'Pending',  aum: '₹45 L',   lastActive: '5 hours ago' },
  { id: 3, name: 'Rahul Verma',         type: 'Institutional', kyc: 'Verified', aum: '₹15 Cr',  lastActive: '1 week ago' },
  { id: 4, name: 'Sunita Kapur',        type: 'Retail',        kyc: 'Verified', aum: '₹12 L',   lastActive: 'Today' },
  { id: 5, name: 'Tech Innovations PF', type: 'Institutional', kyc: 'Verified', aum: '₹42 Cr',  lastActive: 'Today' },
];

const performanceData = [
  { month: 'Jan', value: 100 },
  { month: 'Feb', value: 105 },
  { month: 'Mar', value: 102 },
  { month: 'Apr', value: 110 },
  { month: 'May', value: 115 },
  { month: 'Jun', value: 125 },
];

export default function Investors({
  onInvest,
}: {
  onInvest?: (investor: Investor) => void;
}) {
  const [selectedInvestor, setSelectedInvestor] = useState<number | null>(null);

  if (selectedInvestor !== null) {
    const inv = investors.find(i => i.id === selectedInvestor);
    if (!inv) { setSelectedInvestor(null); return null; }
    return (
      <InvestorDetail
        investor={inv}
        onBack={() => setSelectedInvestor(null)}
        onInvest={onInvest}
      />
    );
  }

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 h-full flex flex-col">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Investor Archive</h1>
          <p className="text-slate-500 text-sm mt-1">Manage and analyze your client portfolios</p>
        </div>
        <div className="flex items-center gap-3">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Filter className="w-4 h-4" /> Filter
          </button>
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors">
            + New Investor
          </button>
        </div>
      </div>

      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 flex flex-col flex-1 overflow-hidden">
        <div className="p-4 border-b border-slate-100 flex gap-4">
          <div className="relative flex-1 max-w-md">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text"
              placeholder="Search by name, PAN, or tier..."
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>
        </div>

        <div className="flex-1 overflow-auto">
          <table className="w-full text-left">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 sticky top-0 z-10 font-semibold">
              <tr>
                <th className="px-6 py-4 font-semibold">Investor Name</th>
                <th className="px-6 py-4 font-semibold">Classification</th>
                <th className="px-6 py-4 font-semibold">AUM</th>
                <th className="px-6 py-4 font-semibold">KYC Status</th>
                <th className="px-6 py-4 font-semibold">Last Interaction</th>
                <th className="px-6 py-4 font-semibold text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {investors.map(inv => (
                <tr
                  key={inv.id}
                  className="group hover:bg-slate-50 transition-colors"
                >
                  <td className="px-6 py-4">
                    <button
                      onClick={() => setSelectedInvestor(inv.id)}
                      className="text-left"
                    >
                      <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{inv.name}</div>
                      <div className="text-xs text-slate-400 font-mono mt-1">ID: INV-{1000 + inv.id}</div>
                    </button>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${
                      inv.type === 'Institutional' ? 'bg-purple-50 text-purple-700' :
                      inv.type === 'HNI' ? 'bg-amber-50 text-amber-700' : 'bg-slate-100 text-slate-600'
                    }`}>
                      {inv.type}
                    </span>
                  </td>
                  <td className="px-6 py-4 font-mono font-medium text-slate-700">{inv.aum}</td>
                  <td className="px-6 py-4">
                    <span className={`flex items-center gap-1.5 text-xs font-medium ${inv.kyc === 'Verified' ? 'text-green-600' : 'text-orange-500'}`}>
                      {inv.kyc === 'Verified' && <ShieldCheck className="w-3.5 h-3.5" />}
                      {inv.kyc}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-xs text-slate-500">{inv.lastActive}</td>
                  <td className="px-6 py-4 text-right">
                    {inv.kyc === 'Verified' && onInvest && (
                      <button
                        onClick={() => onInvest(inv)}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                      >
                        <TrendingUp className="w-3.5 h-3.5" /> Invest Now
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </motion.div>
  );
}

function InvestorDetail({
  investor,
  onBack,
  onInvest,
}: {
  investor: Investor;
  onBack: () => void;
  onInvest?: (investor: Investor) => void;
}) {
  const [activeTab, setActiveTab] = useState('overview');

  const tabs = [
    { id: 'overview',     label: 'Overview'          },
    { id: 'portfolio',    label: 'Portfolio Analysis' },
    { id: 'compliance',   label: 'Compliance'         },
    { id: 'transactions', label: 'Transactions'       },
  ];

  return (
    <motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} className="p-8 max-w-6xl mx-auto">
      <button onClick={onBack} className="flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-slate-800 mb-6 transition-colors">
        <ChevronLeft className="w-4 h-4" /> Back to Archive
      </button>

      <div className="flex justify-between items-start mb-8">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight text-slate-800">{investor.name}</h1>
          <div className="flex flex-wrap items-center gap-3 mt-3 text-sm">
            <span className="px-2.5 py-1 bg-slate-100 text-slate-600 rounded-md font-medium text-xs border border-slate-200">{investor.type}</span>
            <span className="flex items-center gap-1 text-green-600 font-medium text-xs"><ShieldCheck className="w-4 h-4" /> KYC {investor.kyc}</span>
            <span className="text-slate-400">|</span>
            <span className="text-slate-500 font-mono text-xs">PAN: ABCDE1234F</span>
          </div>
        </div>
        <div className="flex gap-2">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Download className="w-4 h-4" /> Dossier
          </button>
          {investor.kyc === 'Verified' && onInvest && (
            <button
              onClick={() => onInvest(investor)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-blue-600 text-white rounded-lg shadow-sm hover:bg-blue-700 transition-colors"
            >
              <TrendingUp className="w-4 h-4" /> Invest Now
            </button>
          )}
        </div>
      </div>

      <div className="flex gap-6 border-b border-slate-200 mb-8">
        {tabs.map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`pb-3 text-sm font-medium transition-colors relative ${activeTab === tab.id ? 'text-[#0B1B3E]' : 'text-slate-500 hover:text-slate-800'}`}
          >
            {tab.label}
            {activeTab === tab.id && (
              <motion.div layoutId="activeTab" className="absolute bottom-0 left-0 right-0 h-0.5 bg-[#0B1B3E]" />
            )}
          </button>
        ))}
      </div>

      <AnimatePresence mode="wait">
        {activeTab === 'overview' && (
          <motion.div key="overview" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }} className="space-y-6">
            <div className="grid grid-cols-3 gap-6">
              <div className="col-span-2 bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
                <div className="flex justify-between items-center mb-6">
                  <h3 className="font-semibold text-slate-800 flex items-center gap-2">
                    <AreaChart className="w-5 h-5 text-blue-500" /> Performance History
                  </h3>
                  <select className="text-xs font-semibold bg-slate-50 border-none rounded-md outline-none cursor-pointer">
                    <option>Year to Date</option>
                    <option>Last 1 Year</option>
                  </select>
                </div>
                <div className="h-64">
                  <ResponsiveContainer width="100%" height="100%">
                    <RechartsArea data={performanceData} margin={{ top: 10, right: 0, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="colorVal" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%"  stopColor="#3b82f6" stopOpacity={0.3} />
                          <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}   />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                      <XAxis dataKey="month" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                      <YAxis axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                      <Tooltip contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }} />
                      <Area type="monotone" dataKey="value" stroke="#3b82f6" strokeWidth={3} fillOpacity={1} fill="url(#colorVal)" />
                    </RechartsArea>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className="bg-[#0B1B3E] rounded-2xl shadow-sm p-6 text-white flex flex-col justify-between">
                <div>
                  <p className="text-xs font-semibold text-blue-300 uppercase tracking-wider mb-2">Current Value (AUM)</p>
                  <p className="text-4xl font-semibold tracking-tight">{investor.aum}</p>
                  <p className="text-sm text-green-400 mt-2 font-medium flex items-center gap-1">
                    <Activity className="w-4 h-4" /> +12.5% XIRR
                  </p>
                </div>
                <div className="bg-white/10 rounded-xl p-4 mt-8">
                  <p className="text-xs font-semibold mb-2 text-blue-200">Asset Allocation</p>
                  <div className="space-y-3">
                    {[
                      { label: 'Equity', pct: '65%', w: 'w-[65%]', color: 'bg-blue-400' },
                      { label: 'Debt',   pct: '25%', w: 'w-[25%]', color: 'bg-emerald-400' },
                      { label: 'Liquid', pct: '10%', w: 'w-[10%]', color: 'bg-amber-400' },
                    ].map(a => (
                      <div key={a.label}>
                        <div className="flex justify-between text-xs mb-1"><span>{a.label}</span><span>{a.pct}</span></div>
                        <div className="h-1.5 w-full bg-white/20 rounded-full overflow-hidden">
                          <div className={`h-full ${a.color} ${a.w}`} />
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </motion.div>
        )}
        {activeTab !== 'overview' && (
          <motion.div key="other" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="py-20 text-center">
            <div className="w-24 h-24 mx-auto mb-6 bg-slate-100 rounded-full flex items-center justify-center border-4 border-white shadow-sm">
              <ShieldCheck className="w-10 h-10 text-slate-300" />
            </div>
            <h3 className="text-lg font-semibold text-slate-800 mb-2">Module locked for this demo</h3>
            <p className="text-slate-500">The '{tabs.find(t => t.id === activeTab)?.label}' view is structurally identical to the overarching design rules.</p>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
