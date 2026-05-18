import React from 'react';
import { motion } from 'motion/react';
import { Download, TrendingUp, Clock } from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer
} from 'recharts';

const earningsData = [
  { month: 'Nov', trail: 18000, upfront: 5000 },
  { month: 'Dec', trail: 21000, upfront: 8000 },
  { month: 'Jan', trail: 24500, upfront: 12000 },
  { month: 'Feb', trail: 22000, upfront: 6000 },
  { month: 'Mar', trail: 28000, upfront: 15000 },
  { month: 'Apr', trail: 31200, upfront: 9000 },
];

const clientBreakdown = [
  { name: 'Rahul Verma', aum: '₹15 Cr', trail: '₹15,000', upfront: '₹0' },
  { name: 'Tech Innovations PF', aum: '₹42 Cr', trail: '₹8,200', upfront: '₹5,000' },
  { name: 'Aditya Sharma', aum: '₹1.2 Cr', trail: '₹4,100', upfront: '₹2,000' },
  { name: 'Sunita Kapur', aum: '₹12 L', trail: '₹2,400', upfront: '₹800' },
  { name: 'Meera Iyer', aum: '₹45 L', trail: '₹1,500', upfront: '₹1,200' },
];

const schemeBreakdown = [
  { name: 'HDFC Large & Mid Cap Fund', category: 'Equity', earnings: '₹9,200' },
  { name: 'Parag Parikh Flexi Cap Fund', category: 'Equity', earnings: '₹7,800' },
  { name: 'ICICI Prudential Bluechip', category: 'Equity', earnings: '₹6,400' },
  { name: 'SBI Liquid Fund', category: 'Debt', earnings: '₹3,100' },
  { name: 'Quant Small Cap Fund', category: 'Equity', earnings: '₹8,500' },
  { name: 'Kotak Corporate Bond Fund', category: 'Debt', earnings: '₹5,200' },
];

export default function Earnings() {
  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 space-y-6">
      <div className="flex justify-between items-end">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Earnings Summary</h1>
          <p className="text-slate-500 text-sm mt-1">Trail commissions and upfront brokerage overview</p>
        </div>
        <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
          <Download className="w-4 h-4" /> Download Statement
        </button>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-3 gap-6">
        <div className="bg-[#0B1B3E] rounded-2xl p-6 text-white relative overflow-hidden">
          <div className="absolute top-0 right-0 w-32 h-32 bg-blue-500 opacity-10 rounded-full blur-2xl" />
          <p className="text-[10px] font-bold text-blue-300 uppercase tracking-wider mb-2">Current Period (Apr)</p>
          <p className="text-3xl font-semibold">₹40,200</p>
          <p className="text-xs text-green-400 font-medium mt-2 flex items-center gap-1">
            <TrendingUp className="w-3.5 h-3.5" /> +14.5% vs last month
          </p>
        </div>
        <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
          <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Lifetime Earnings</p>
          <p className="text-3xl font-semibold text-slate-800">₹3.12 L</p>
          <p className="text-xs text-slate-500 mt-2">Since January 2023</p>
        </div>
        <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
          <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Pending Payout</p>
          <p className="text-3xl font-semibold text-slate-800">₹31,200</p>
          <p className="text-xs text-orange-500 font-medium mt-2 flex items-center gap-1">
            <Clock className="w-3.5 h-3.5" /> Expected by 15th May
          </p>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-6">
        {/* Trend Chart */}
        <div className="col-span-2 bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
          <h2 className="font-semibold text-slate-800 mb-1">6-Month Earnings Trend</h2>
          <p className="text-xs text-slate-500 mb-6">Trail vs Upfront commissions</p>
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={earningsData} barGap={4} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                <XAxis dataKey="month" axisLine={false} tickLine={false} tick={{ fontSize: 12, fill: '#94a3b8' }} />
                {/* domain={[0,'auto']} anchors the axis floor at 0 so bars always
                    grow upward from the baseline — without this, Recharts may float
                    the min to the lowest data value, causing the tallest bar to
                    appear compressed near the bottom of the chart area. */}
                <YAxis
                  axisLine={false}
                  tickLine={false}
                  tick={{ fontSize: 12, fill: '#94a3b8' }}
                  domain={[0, 'auto']}
                />
                <Tooltip
                  contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)' }}
                  formatter={(val: number) => [`₹${val.toLocaleString('en-IN')}`, '']}
                />
                <Bar dataKey="trail" fill="#3b82f6" radius={[4, 4, 0, 0]} name="Trail" />
                <Bar dataKey="upfront" fill="#e2e8f0" radius={[4, 4, 0, 0]} name="Upfront" />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="flex gap-4 mt-4">
            <div className="flex items-center gap-1.5 text-xs text-slate-500">
              <span className="w-3 h-3 rounded-sm bg-blue-500 inline-block" /> Trail
            </div>
            <div className="flex items-center gap-1.5 text-xs text-slate-500">
              <span className="w-3 h-3 rounded-sm bg-slate-200 inline-block" /> Upfront
            </div>
          </div>
        </div>

        {/* April breakdown */}
        <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200 flex flex-col">
          <h2 className="font-semibold text-slate-800 mb-5">April Breakdown</h2>
          <div className="space-y-5 flex-1">
            {[
              { label: 'Trail Commission', value: '₹31,200', pct: 78, color: 'bg-blue-500' },
              { label: 'Upfront Commission', value: '₹9,000', pct: 22, color: 'bg-slate-300' },
            ].map(item => (
              <div key={item.label}>
                <div className="flex justify-between text-sm mb-1.5">
                  <span className="text-slate-600">{item.label}</span>
                  <span className="font-semibold text-slate-800">{item.value}</span>
                </div>
                <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
                  <div className={`h-full ${item.color} rounded-full`} style={{ width: `${item.pct}%` }} />
                </div>
                <p className="text-[10px] text-slate-400 mt-1">{item.pct}% of total</p>
              </div>
            ))}
          </div>
          <div className="pt-5 mt-5 border-t border-slate-100">
            <div className="flex justify-between text-sm">
              <span className="font-semibold text-slate-700">Total (Apr)</span>
              <span className="font-bold text-slate-900">₹40,200</span>
            </div>
          </div>
        </div>
      </div>

      {/* Client-wise breakdown */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="p-5 border-b border-slate-100 flex justify-between items-center">
          <h2 className="font-semibold text-slate-800">Client-wise Breakdown</h2>
          <span className="text-xs text-slate-500 bg-slate-100 px-3 py-1 rounded-full font-medium">April 2025</span>
        </div>
        <table className="w-full text-left">
          <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
            <tr>
              <th className="px-6 py-4">Client</th>
              <th className="px-6 py-4">AUM</th>
              <th className="px-6 py-4">Trail Commission</th>
              <th className="px-6 py-4">Upfront Commission</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {clientBreakdown.map(c => (
              <tr key={c.name} className="hover:bg-slate-50 transition-colors">
                <td className="px-6 py-4 text-sm font-semibold text-slate-800">{c.name}</td>
                <td className="px-6 py-4 text-sm font-mono text-slate-600">{c.aum}</td>
                <td className="px-6 py-4 text-sm font-mono font-medium text-slate-800">{c.trail}</td>
                <td className="px-6 py-4 text-sm font-mono font-medium text-slate-800">{c.upfront}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Scheme-wise breakdown */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="p-5 border-b border-slate-100 flex justify-between items-center">
          <h2 className="font-semibold text-slate-800">Scheme-wise Breakdown</h2>
          <span className="text-xs text-slate-500 bg-slate-100 px-3 py-1 rounded-full font-medium">April 2025</span>
        </div>
        <table className="w-full text-left">
          <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
            <tr>
              <th className="px-6 py-4">Scheme</th>
              <th className="px-6 py-4">Category</th>
              <th className="px-6 py-4">Total Earnings</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {schemeBreakdown.map(s => (
              <tr key={s.name} className="hover:bg-slate-50 transition-colors">
                <td className="px-6 py-4 text-sm font-semibold text-slate-800">{s.name}</td>
                <td className="px-6 py-4">
                  <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${
                    s.category === 'Equity' ? 'bg-blue-50 text-blue-700' : 'bg-emerald-50 text-emerald-700'
                  }`}>
                    {s.category}
                  </span>
                </td>
                <td className="px-6 py-4 text-sm font-mono font-semibold text-slate-800">{s.earnings}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </motion.div>
  );
}
