import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Search, Filter, ChevronLeft, CheckCircle2, Clock, XCircle, AlertCircle, RefreshCw } from 'lucide-react';

const transactions = [
  { id: 1, investor: 'Aditya Sharma', fund: 'HDFC Large & Mid Cap Fund', type: 'SIP', amount: '₹5,000', status: 'Successful', date: 'Today, 10:32 AM', pan: 'ABCDE1234F', mandate: 'UPI Autopay' },
  { id: 2, investor: 'Meera Iyer', fund: 'Parag Parikh Flexi Cap Fund', type: 'Lumpsum', amount: '₹1,00,000', status: 'Payment Pending', date: 'Today, 09:15 AM', pan: 'FGHIJ5678K', mandate: 'Netbanking' },
  { id: 3, investor: 'Rahul Verma', fund: 'ICICI Prudential Bluechip', type: 'SIP', amount: '₹10,000', status: 'Pending Investor Action', date: 'Yesterday, 4:20 PM', pan: 'KLMNO9012P', mandate: 'UPI Autopay' },
  { id: 4, investor: 'Sunita Kapur', fund: 'SBI Liquid Fund', type: 'Redemption', amount: '₹50,000', status: 'Processing', date: 'Yesterday, 2:10 PM', pan: 'QRSTU3456V', mandate: '—' },
  { id: 5, investor: 'Tech Innovations PF', fund: 'Quant Small Cap Fund', type: 'Lumpsum', amount: '₹5,00,000', status: 'Failed', date: '18 Apr, 11:00 AM', pan: 'WXYZ7890A', mandate: 'Netbanking' },
  { id: 6, investor: 'Aditya Sharma', fund: 'Kotak Corporate Bond Fund', type: 'Switch', amount: '₹25,000', status: 'Submitted', date: '17 Apr, 3:30 PM', pan: 'ABCDE1234F', mandate: '—' },
  { id: 7, investor: 'Meera Iyer', fund: 'SBI Liquid Fund', type: 'SIP', amount: '₹3,000', status: 'Retry Available', date: '16 Apr, 9:00 AM', pan: 'FGHIJ5678K', mandate: 'eNACH' },
  { id: 8, investor: 'Rahul Verma', fund: 'HDFC Large & Mid Cap Fund', type: 'SIP', amount: '₹15,000', status: 'Created', date: '15 Apr, 8:45 AM', pan: 'KLMNO9012P', mandate: 'UPI Autopay' },
];

type StatusKey = 'Successful' | 'Processing' | 'Submitted' | 'Payment Pending' | 'Pending Investor Action' | 'Failed' | 'Retry Available' | 'Draft' | 'Created';

const statusConfig: Record<StatusKey, { color: string; icon: React.ReactNode }> = {
  Successful: { color: 'bg-green-50 text-green-700', icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
  Processing: { color: 'bg-blue-50 text-blue-700', icon: <RefreshCw className="w-3.5 h-3.5 animate-spin" /> },
  Submitted: { color: 'bg-indigo-50 text-indigo-700', icon: <Clock className="w-3.5 h-3.5" /> },
  'Payment Pending': { color: 'bg-orange-50 text-orange-700', icon: <Clock className="w-3.5 h-3.5" /> },
  'Pending Investor Action': { color: 'bg-amber-50 text-amber-700', icon: <AlertCircle className="w-3.5 h-3.5" /> },
  Failed: { color: 'bg-red-50 text-red-700', icon: <XCircle className="w-3.5 h-3.5" /> },
  'Retry Available': { color: 'bg-purple-50 text-purple-700', icon: <RefreshCw className="w-3.5 h-3.5" /> },
  Draft: { color: 'bg-slate-100 text-slate-600', icon: <Clock className="w-3.5 h-3.5" /> },
  Created: { color: 'bg-slate-100 text-slate-600', icon: <Clock className="w-3.5 h-3.5" /> },
};

function buildTimeline(status: string) {
  const steps = [
    { label: 'Order Created', time: '09:15 AM' },
    { label: 'Investor Action Sent', time: '09:16 AM' },
    { label: 'Payment Authorized', time: status === 'Payment Pending' ? 'Awaiting' : '09:30 AM' },
    { label: 'Submitted to Exchange', time: ['Processing', 'Submitted', 'Successful'].includes(status) ? '09:45 AM' : '—' },
    { label: 'Order Successful', time: status === 'Successful' ? '10:32 AM' : '—' },
  ];
  const doneCount =
    status === 'Created' ? 1 :
    status === 'Pending Investor Action' ? 1 :
    status === 'Payment Pending' ? 2 :
    status === 'Submitted' ? 3 :
    status === 'Processing' ? 4 :
    status === 'Successful' ? 5 : 2;
  return steps.map((s, i) => ({ ...s, done: i < doneCount }));
}

const TYPE_FILTERS = ['All', 'SIP', 'Lumpsum', 'Redemption', 'Switch'];

export default function Transactions() {
  const [selected, setSelected] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('All');

  const filtered = transactions.filter(t => {
    const matchSearch = t.investor.toLowerCase().includes(search.toLowerCase()) ||
      t.fund.toLowerCase().includes(search.toLowerCase());
    const matchType = typeFilter === 'All' || t.type === typeFilter;
    return matchSearch && matchType;
  });

  if (selected !== null) {
    const tx = transactions.find(t => t.id === selected)!;
    return <TransactionDetail tx={tx} onBack={() => setSelected(null)} />;
  }

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 h-full flex flex-col">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Transactions</h1>
          <p className="text-slate-500 text-sm mt-1">Track all orders, SIPs and redemptions</p>
        </div>
        <div className="flex items-center gap-3">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Filter className="w-4 h-4" /> Filter
          </button>
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors">
            + New Transaction
          </button>
        </div>
      </div>

      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 flex flex-col flex-1 overflow-hidden">
        <div className="p-4 border-b border-slate-100 flex flex-wrap gap-3">
          <div className="relative flex-1 min-w-[200px] max-w-md">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search by investor or fund..."
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>
          <div className="flex gap-2">
            {TYPE_FILTERS.map(f => (
              <button
                key={f}
                onClick={() => setTypeFilter(f)}
                className={`px-3 py-2 text-xs font-semibold rounded-lg border transition-colors ${typeFilter === f ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-slate-50 border-slate-200 text-slate-600 hover:bg-slate-100'}`}
              >
                {f}
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 overflow-auto">
          <table className="w-full text-left">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 sticky top-0 z-10 font-semibold">
              <tr>
                <th className="px-6 py-4">Investor</th>
                <th className="px-6 py-4">Fund</th>
                <th className="px-6 py-4">Type</th>
                <th className="px-6 py-4">Amount</th>
                <th className="px-6 py-4">Status</th>
                <th className="px-6 py-4 text-right">Date</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filtered.map(tx => {
                const s = statusConfig[tx.status as StatusKey] ?? statusConfig.Draft;
                return (
                  <tr key={tx.id} onClick={() => setSelected(tx.id)} className="group hover:bg-slate-50 transition-colors cursor-pointer">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{tx.investor}</div>
                      <div className="text-xs text-slate-400 font-mono mt-1">TXN-{1000 + tx.id}</div>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600 max-w-[200px] truncate">{tx.fund}</td>
                    <td className="px-6 py-4">
                      <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${
                        tx.type === 'SIP' ? 'bg-blue-50 text-blue-700' :
                        tx.type === 'Lumpsum' ? 'bg-purple-50 text-purple-700' :
                        tx.type === 'Redemption' ? 'bg-amber-50 text-amber-700' :
                        'bg-slate-100 text-slate-600'
                      }`}>
                        {tx.type}
                      </span>
                    </td>
                    <td className="px-6 py-4 font-mono font-medium text-slate-700">{tx.amount}</td>
                    <td className="px-6 py-4">
                      <span className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-md w-fit ${s.color}`}>
                        {s.icon} {tx.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-xs text-slate-500 text-right">{tx.date}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </motion.div>
  );
}

function TransactionDetail({ tx, onBack }: { tx: any; onBack: () => void }) {
  const s = statusConfig[tx.status as StatusKey] ?? statusConfig.Draft;
  const timeline = buildTimeline(tx.status);

  return (
    <motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} className="p-8 max-w-5xl">
      <button onClick={onBack} className="flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-slate-800 mb-6 transition-colors">
        <ChevronLeft className="w-4 h-4" /> Back to Transactions
      </button>

      <div className="flex justify-between items-start mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">{tx.investor}</h1>
          <div className="flex items-center gap-3 mt-2">
            <span className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-md ${s.color}`}>
              {s.icon} {tx.status}
            </span>
            <span className="text-slate-400 text-xs font-mono">TXN-{1000 + tx.id}</span>
          </div>
        </div>
        {tx.status === 'Retry Available' && (
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors">
            <RefreshCw className="w-4 h-4" /> Retry Order
          </button>
        )}
      </div>

      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 space-y-6">
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <h2 className="font-semibold text-slate-800 mb-5">Order Details</h2>
            <div className="grid grid-cols-2 gap-5">
              {[
                { label: 'Fund', value: tx.fund },
                { label: 'Transaction Type', value: tx.type },
                { label: 'Amount', value: tx.amount },
                { label: 'Mandate / Payment Mode', value: tx.mandate },
                { label: 'Initiated', value: tx.date },
                { label: 'Investor PAN', value: tx.pan },
              ].map(({ label, value }) => (
                <div key={label}>
                  <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{label}</p>
                  <p className="text-sm font-medium text-slate-700">{value}</p>
                </div>
              ))}
            </div>
          </div>

          {(tx.status === 'Payment Pending' || tx.status === 'Pending Investor Action') && (
            <div className="bg-amber-50 rounded-2xl p-5 border border-amber-200 flex gap-4">
              <AlertCircle className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-semibold text-amber-800">Awaiting investor action</p>
                <p className="text-xs text-amber-700 mt-1 leading-relaxed">
                  A payment authorization link has been sent to the investor's registered email and mobile. Resend if they haven't acted.
                </p>
                <button className="mt-3 px-4 py-2 bg-amber-600 text-white text-xs font-semibold rounded-lg hover:bg-amber-700 transition-colors">
                  Resend Link
                </button>
              </div>
            </div>
          )}

          {tx.status === 'Failed' && (
            <div className="bg-red-50 rounded-2xl p-5 border border-red-200 flex gap-4">
              <XCircle className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-semibold text-red-800">Order failed</p>
                <p className="text-xs text-red-700 mt-1">Reason: Insufficient funds in the investor's bank account. Please ask the investor to top up and retry.</p>
                <button className="mt-3 px-4 py-2 bg-red-600 text-white text-xs font-semibold rounded-lg hover:bg-red-700 transition-colors">
                  Create New Order
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Timeline */}
        <div className="bg-[#0B1B3E] rounded-2xl p-6 text-white h-fit">
          <h2 className="font-semibold mb-6">Order Timeline</h2>
          <div className="space-y-0">
            {timeline.map((step, i) => (
              <div key={i} className="flex gap-3">
                <div className="flex flex-col items-center">
                  <div className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 ${step.done ? 'bg-green-500' : 'bg-white/10 border border-white/20'}`}>
                    {step.done && <CheckCircle2 className="w-3.5 h-3.5 text-white" />}
                  </div>
                  {i < timeline.length - 1 && (
                    <div className={`w-0.5 h-9 mt-1 ${step.done ? 'bg-green-500/40' : 'bg-white/10'}`} />
                  )}
                </div>
                <div className="pb-9">
                  <p className={`text-sm font-medium leading-tight ${step.done ? 'text-white' : 'text-white/40'}`}>{step.label}</p>
                  <p className="text-[10px] text-blue-300 mt-0.5">{step.time}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </motion.div>
  );
}
