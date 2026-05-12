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
  month: string; amount: number; count: number;
}

type StatusFilter = 'All' | 'Active' | 'Failed' | 'Paused';
type CatFilter = 'ALL' | 'MF' | 'SIF';

const statusCfg: Record<string, { icon: React.ReactNode; cls: string }> = {
  Active: { icon: <CheckCircle2 className="w-3.5 h-3.5" />, cls: 'bg-green-100 text-green-700' },
  Failed: { icon: <XCircle      className="w-3.5 h-3.5" />, cls: 'bg-red-100   text-red-700'   },
  Paused: { icon: <Clock        className="w-3.5 h-3.5" />, cls: 'bg-amber-100 text-amber-700' },
};

export default function SipDashboard({ onBack, userData }: { onBack: () => void; userData?: any }) {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('All');
  const [catFilter, setCatFilter] = useState<CatFilter>('ALL');
  const [sips, setSips] = useState<Sip[]>([]);
  const [sipTrend, setSipTrend] = useState<SipTrend[]>([]);
  const [loading, setLoading] = useState(true);

  React.useEffect(() => {
    if (!userData?.id) return;
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };

    apiFetch(`/dashboard/distributor/${userData.id}/sips`, { headers })
      .then(res => res.json())
      .then(data => {
        setSips(data.sips || []);
        setSipTrend(data.trend || []);
        setLoading(false);
      })
      .catch(err => {
        console.error('Failed to fetch SIP dashboard', err);
        setLoading(false);
      });
  }, [userData]);

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

      <div className="flex justify-between items-end">
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
      <div className="grid grid-cols-4 gap-5">
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
          <AreaChart data={sipTrend}>
            <defs>
              <linearGradient id="sipGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%"  stopColor="#3b82f6" stopOpacity={0.15} />
                <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}    />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
            <XAxis dataKey="month" tick={{ fontSize: 12 }} />
            <YAxis tick={{ fontSize: 12 }} tickFormatter={v => `₹${v}L`} />
            <Tooltip formatter={(v: number) => `₹${v} L`} />
            <Area
              type="monotone" dataKey="amount" name="SIP Amount (₹L)"
              stroke="#3b82f6" strokeWidth={2} fill="url(#sipGrad)"
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {/* ── SIP list ────────────────────────────────────────────────────── */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
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
              ].map(col => (
                <th key={col.h} className={`py-3 px-5 text-xs font-bold text-slate-500 uppercase tracking-wider ${col.align}`}>
                  {col.h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {visible.map(sip => {
              const cfg = statusCfg[sip.status];
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
                </tr>
              );
            })}
          </tbody>
        </table>

        {visible.length === 0 && (
          <div className="py-12 text-center text-sm text-slate-400">
            No {statusFilter.toLowerCase()} {catFilter !== 'ALL' ? catFilter : ''} SIPs found.
          </div>
        )}
      </div>
    </motion.div>
  );
}
