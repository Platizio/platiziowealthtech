import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  ArrowLeft, AlertCircle, ShieldCheck, Landmark,
  ArrowLeftRight, RefreshCw, Package, ChevronRight,
} from 'lucide-react';

type Category = 'All' | 'KYC' | 'Bank' | 'Transaction' | 'SIP' | 'Maturing';
type Priority  = 'High' | 'Medium' | 'Low';

interface Action {
  id: number; category: Exclude<Category, 'All'>;
  priority: Priority; investor: string; desc: string; age: string;
}

const ACTIONS: Action[] = [
  /* KYC — 5 */
  { id:  1, category: 'KYC', priority: 'High',   investor: 'Priya Nair',          desc: 'Selfie upload required for KYC completion',             age: '2 days'    },
  { id:  2, category: 'KYC', priority: 'High',   investor: 'Prakash Mehta',       desc: 'PAN–Aadhaar mismatch — verification pending',           age: '4 days'    },
  { id:  3, category: 'KYC', priority: 'Medium', investor: 'Nisha Patel',         desc: 'KYC in progress, awaiting CKYC registry update',        age: '1 day'     },
  { id:  4, category: 'KYC', priority: 'High',   investor: 'Mohit Gupta',         desc: 'Address proof expired — fresh submission needed',       age: '6 days'    },
  { id:  5, category: 'KYC', priority: 'Low',    investor: 'Deepa Rao',           desc: 'Video KYC appointment not yet scheduled',              age: '3 days'    },
  /* Bank — 4 */
  { id:  6, category: 'Bank', priority: 'High',   investor: 'Vikram Singh',       desc: 'Bank account not verified — penny drop failed',         age: '1 day'     },
  { id:  7, category: 'Bank', priority: 'Medium', investor: 'Rajesh Kumar',       desc: 'IFSC code mismatch on submitted cancelled cheque',      age: '3 days'    },
  { id:  8, category: 'Bank', priority: 'High',   investor: 'Anjali Desai',       desc: 'Cancelled cheque not submitted',                        age: '5 days'    },
  { id:  9, category: 'Bank', priority: 'Low',    investor: 'Sunita Kapur',       desc: 'Bank mandate (eNACH) registration pending',            age: '2 days'    },
  /* Transaction — 8 */
  { id: 10, category: 'Transaction', priority: 'High',   investor: 'Tech Innovations PF', desc: 'Lumpsum payment failed — insufficient funds',  age: 'Today'     },
  { id: 11, category: 'Transaction', priority: 'High',   investor: 'Meera Iyer',          desc: 'Redemption stuck in processing for 2 days',    age: '2 days'    },
  { id: 12, category: 'Transaction', priority: 'Medium', investor: 'Rahul Verma',         desc: 'Switch order awaiting investor confirmation',  age: '1 day'     },
  { id: 13, category: 'Transaction', priority: 'Medium', investor: 'Aditya Sharma',       desc: 'NFO subscription window closes in 2 days',    age: '1 day'     },
  { id: 14, category: 'Transaction', priority: 'Low',    investor: 'Priya Nair',          desc: 'Partial redemption request stalled at BSE',   age: '3 days'    },
  { id: 15, category: 'Transaction', priority: 'Low',    investor: 'Nisha Patel',         desc: 'Growth-to-IDCW switch pending investor sign-off', age: '4 days' },
  { id: 16, category: 'Transaction', priority: 'High',   investor: 'Vikram Singh',        desc: 'Netbanking payment rejected — retry available',age: 'Today'     },
  { id: 17, category: 'Transaction', priority: 'Low',    investor: 'Mohit Gupta',         desc: 'STT discrepancy on equity redemption',         age: '5 days'    },
  /* SIP — 4 */
  { id: 18, category: 'SIP', priority: 'High',   investor: 'Meera Iyer',          desc: 'SIP eNACH mandate failed for 2 consecutive months',    age: 'Today'     },
  { id: 19, category: 'SIP', priority: 'High',   investor: 'Anjali Desai',        desc: 'SIP amount deducted but units not allotted',            age: '1 day'     },
  { id: 20, category: 'SIP', priority: 'Medium', investor: 'Rajesh Kumar',        desc: 'Upcoming SIP on 5th — insufficient balance alert',     age: 'Today'     },
  { id: 21, category: 'SIP', priority: 'Low',    investor: 'Deepa Rao',           desc: 'SIP pause request received from investor',              age: '2 days'    },
  /* Maturing — 2 */
  { id: 22, category: 'Maturing', priority: 'Medium', investor: 'Rahul Verma',     desc: 'Fixed Deposit maturing 10 May — renewal or payout needed', age: 'In 18d' },
  { id: 23, category: 'Maturing', priority: 'Low',    investor: 'Tech Innovations PF', desc: 'LAS OD limit review due end of month',              age: 'In 25d' },
];

const TABS: { key: Category; label: string; icon: React.ReactNode }[] = [
  { key: 'All',         label: 'All',          icon: <AlertCircle     className="w-3.5 h-3.5" /> },
  { key: 'KYC',         label: 'KYC',          icon: <ShieldCheck     className="w-3.5 h-3.5" /> },
  { key: 'Bank',        label: 'Bank',         icon: <Landmark        className="w-3.5 h-3.5" /> },
  { key: 'Transaction', label: 'Transactions', icon: <ArrowLeftRight  className="w-3.5 h-3.5" /> },
  { key: 'SIP',         label: 'SIP',          icon: <RefreshCw       className="w-3.5 h-3.5" /> },
  { key: 'Maturing',    label: 'Maturing',     icon: <Package         className="w-3.5 h-3.5" /> },
];

const priorityCls: Record<Priority, string> = {
  High:   'bg-red-100   text-red-700',
  Medium: 'bg-amber-100 text-amber-700',
  Low:    'bg-slate-100 text-slate-600',
};

const catIcon: Record<Exclude<Category, 'All'>, { icon: React.ReactNode; cls: string }> = {
  KYC:         { icon: <ShieldCheck    className="w-4 h-4" />, cls: 'bg-blue-100   text-blue-600'   },
  Bank:        { icon: <Landmark       className="w-4 h-4" />, cls: 'bg-green-100  text-green-600'  },
  Transaction: { icon: <ArrowLeftRight className="w-4 h-4" />, cls: 'bg-violet-100 text-violet-600' },
  SIP:         { icon: <RefreshCw      className="w-4 h-4" />, cls: 'bg-orange-100 text-orange-600' },
  Maturing:    { icon: <Package        className="w-4 h-4" />, cls: 'bg-slate-100  text-slate-600'  },
};

export default function ActionCenter({ onBack }: { onBack: () => void }) {
  const [tab, setTab] = useState<Category>('All');

  const filtered = tab === 'All' ? ACTIONS : ACTIONS.filter(a => a.category === tab);
  const high   = filtered.filter(a => a.priority === 'High').length;
  const medium = filtered.filter(a => a.priority === 'Medium').length;
  const low    = filtered.filter(a => a.priority === 'Low').length;

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

      {/* ── Header ──────────────────────────────────────────────────────── */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Action Center</h1>
          <p className="text-slate-500 text-sm mt-1">All pending tasks requiring your attention</p>
        </div>
        {/* Priority summary pills */}
        <div className="flex gap-3">
          <div className="bg-red-50 border border-red-200 rounded-xl px-4 py-2 text-center min-w-[64px]">
            <p className="text-2xl font-bold text-red-700">{high}</p>
            <p className="text-[10px] text-red-500 font-bold uppercase tracking-wider">High</p>
          </div>
          <div className="bg-amber-50 border border-amber-200 rounded-xl px-4 py-2 text-center min-w-[64px]">
            <p className="text-2xl font-bold text-amber-700">{medium}</p>
            <p className="text-[10px] text-amber-500 font-bold uppercase tracking-wider">Medium</p>
          </div>
          <div className="bg-slate-100 border border-slate-200 rounded-xl px-4 py-2 text-center min-w-[64px]">
            <p className="text-2xl font-bold text-slate-600">{low}</p>
            <p className="text-[10px] text-slate-400 font-bold uppercase tracking-wider">Low</p>
          </div>
        </div>
      </div>

      {/* ── Category tabs ───────────────────────────────────────────────── */}
      <div className="flex gap-2 flex-wrap">
        {TABS.map(t => {
          const count = t.key === 'All' ? ACTIONS.length : ACTIONS.filter(a => a.category === t.key).length;
          const active = tab === t.key;
          return (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-medium border transition-all ${
                active
                  ? 'bg-[#0B1B3E] text-white border-[#0B1B3E] shadow-sm'
                  : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300 hover:text-slate-800'
              }`}
            >
              {t.icon}
              {t.label}
              <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded-full ${active ? 'bg-white/20 text-white' : 'bg-slate-100 text-slate-500'}`}>
                {count}
              </span>
            </button>
          );
        })}
      </div>

      {/* ── Action list ─────────────────────────────────────────────────── */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
        <AnimatePresence mode="wait">
          <motion.div
            key={tab}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.15 }}
          >
            {filtered.length === 0 ? (
              <div className="py-16 text-center">
                <p className="text-slate-400 text-sm">No pending actions in this category.</p>
              </div>
            ) : (
              <div className="divide-y divide-slate-100">
                {filtered.map(action => {
                  const cat = catIcon[action.category];
                  return (
                    <div
                      key={action.id}
                      className="flex items-start gap-4 px-5 py-4 hover:bg-slate-50 transition-colors cursor-pointer group"
                    >
                      {/* Category icon */}
                      <div className={`w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 ${cat.cls}`}>
                        {cat.icon}
                      </div>

                      {/* Content */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                          <p className="text-sm font-semibold text-slate-800">{action.investor}</p>
                          <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded-full ${priorityCls[action.priority]}`}>
                            {action.priority}
                          </span>
                          <span className="text-[10px] text-slate-400 bg-slate-100 px-1.5 py-0.5 rounded-full ml-auto">
                            {action.age}
                          </span>
                        </div>
                        <p className="text-xs text-slate-500 leading-relaxed">{action.desc}</p>
                      </div>

                      {/* Arrow */}
                      <ChevronRight className="w-4 h-4 text-slate-300 group-hover:text-slate-500 transition-colors flex-shrink-0 mt-1" />
                    </div>
                  );
                })}
              </div>
            )}
          </motion.div>
        </AnimatePresence>
      </div>
    </motion.div>
  );
}
