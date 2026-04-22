import React from 'react';
import { motion } from 'motion/react';
import { ArrowLeft, TrendingUp, TrendingDown } from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Legend,
} from 'recharts';

const aumTrend = [
  { month: 'Nov', MF: 26.0, SIF: 7.0, Others: 2.1 },
  { month: 'Dec', MF: 27.5, SIF: 7.2, Others: 2.3 },
  { month: 'Jan', MF: 28.1, SIF: 7.5, Others: 2.5 },
  { month: 'Feb', MF: 29.4, SIF: 7.8, Others: 2.6 },
  { month: 'Mar', MF: 30.2, SIF: 8.1, Others: 2.7 },
  { month: 'Apr', MF: 31.2, SIF: 8.4, Others: 2.98 },
];

const investors = [
  { id: 1, name: 'Tech Innovations PF', type: 'Institutional', mf: '₹32 Cr',  sif: '₹10 Cr', total: '₹42 Cr',  change: '+8.2%', up: true  },
  { id: 2, name: 'Rahul Verma',          type: 'Institutional', mf: '₹10 Cr',  sif: '₹5 Cr',  total: '₹15 Cr',  change: '+3.5%', up: true  },
  { id: 3, name: 'Aditya Sharma',        type: 'HNI',           mf: '₹90 L',   sif: '₹30 L',  total: '₹1.2 Cr', change: '+5.1%', up: true  },
  { id: 4, name: 'Meera Iyer',           type: 'Retail',        mf: '₹40 L',   sif: '₹5 L',   total: '₹45 L',   change: '-1.2%', up: false },
  { id: 5, name: 'Sunita Kapur',         type: 'Retail',        mf: '₹10 L',   sif: '₹2 L',   total: '₹12 L',   change: '+2.3%', up: true  },
];

const typeBadge: Record<string, string> = {
  Institutional: 'bg-blue-100 text-blue-700',
  HNI:           'bg-violet-100 text-violet-700',
  Retail:        'bg-slate-100 text-slate-600',
};

export default function AumBreakdown({ onBack }: { onBack: () => void }) {
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
        <p className="text-slate-500 text-sm mt-1">Investor-wise asset under management — April 2025</p>
      </div>

      {/* ── Summary cards ───────────────────────────────────────────────── */}
      <div className="grid grid-cols-3 gap-5">
        {[
          { label: 'Total AUM',    value: '₹42.58 Cr', change: '↑ 4.2% MoM', up: true,  accent: 'border-blue-200   bg-blue-50   text-blue-700'   },
          { label: 'Mutual Funds', value: '₹31.2 Cr',  change: '↑ 5.1% MoM', up: true,  accent: 'border-green-200  bg-green-50  text-green-700'  },
          { label: 'SIF & Others', value: '₹11.38 Cr', change: '↑ 2.1% MoM', up: true,  accent: 'border-violet-200 bg-violet-50 text-violet-700' },
        ].map(card => (
          <div key={card.label} className={`rounded-2xl border p-5 ${card.accent}`}>
            <p className="text-[10px] font-bold uppercase tracking-wider opacity-60 mb-2">{card.label}</p>
            <p className="text-2xl font-bold mb-1">{card.value}</p>
            <p className="text-xs font-semibold opacity-80 flex items-center gap-1">
              {card.up
                ? <TrendingUp className="w-3 h-3" />
                : <TrendingDown className="w-3 h-3" />}
              {card.change}
            </p>
          </div>
        ))}
      </div>

      {/* ── AUM Growth chart ────────────────────────────────────────────── */}
      <div className="bg-white rounded-2xl border border-slate-200 p-6">
        <h2 className="font-semibold text-slate-800 mb-5">AUM Growth — Last 6 Months (₹ Cr)</h2>
        <ResponsiveContainer width="100%" height={240}>
          <BarChart data={aumTrend} barSize={20}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
            <XAxis dataKey="month" tick={{ fontSize: 12 }} />
            <YAxis tick={{ fontSize: 12 }} tickFormatter={v => `₹${v}`} />
            <Tooltip formatter={(v: number) => `₹${v} Cr`} />
            <Legend />
            <Bar dataKey="MF"     name="Mutual Funds" fill="#3b82f6" radius={[4,4,0,0]} />
            <Bar dataKey="SIF"    name="SIF"           fill="#8b5cf6" radius={[4,4,0,0]} />
            <Bar dataKey="Others" name="Others"        fill="#10b981" radius={[4,4,0,0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* ── Investor-wise table ──────────────────────────────────────────── */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-100">
          <h2 className="font-semibold text-slate-800">Investor-wise AUM</h2>
        </div>
        <table className="w-full">
          <thead>
            <tr className="bg-slate-50 border-b border-slate-100">
              {['Investor', 'Type', 'MF AUM', 'SIF AUM', 'Total AUM', 'MoM Change'].map(h => (
                <th
                  key={h}
                  className={`py-3 px-5 text-xs font-bold text-slate-500 uppercase tracking-wider ${
                    h === 'Investor' || h === 'Type' ? 'text-left' : 'text-right'
                  }`}
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {investors.map(inv => (
              <tr key={inv.id} className="hover:bg-slate-50 transition-colors">
                <td className="py-3.5 px-5 text-sm font-semibold text-slate-800">{inv.name}</td>
                <td className="py-3.5 px-5">
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${typeBadge[inv.type]}`}>
                    {inv.type}
                  </span>
                </td>
                <td className="py-3.5 px-5 text-sm text-right font-mono text-slate-700">{inv.mf}</td>
                <td className="py-3.5 px-5 text-sm text-right font-mono text-slate-700">{inv.sif}</td>
                <td className="py-3.5 px-5 text-sm text-right font-mono font-semibold text-slate-800">{inv.total}</td>
                <td className={`py-3.5 px-5 text-sm text-right font-semibold ${inv.up ? 'text-green-600' : 'text-red-500'}`}>
                  <span className="flex items-center justify-end gap-1">
                    {inv.up ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                    {inv.change}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="bg-slate-50 border-t-2 border-slate-200">
              <td colSpan={2} className="py-3.5 px-5 text-xs font-bold text-slate-500 uppercase tracking-wider">Total</td>
              <td className="py-3.5 px-5 text-sm text-right font-mono font-bold text-slate-800">₹31.2 Cr</td>
              <td className="py-3.5 px-5 text-sm text-right font-mono font-bold text-slate-800">₹8.4 Cr</td>
              <td className="py-3.5 px-5 text-sm text-right font-mono font-bold text-slate-800">₹42.58 Cr</td>
              <td className="py-3.5 px-5 text-sm text-right font-semibold text-green-600">↑ 4.2%</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </motion.div>
  );
}
