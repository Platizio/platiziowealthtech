import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  ArrowLeft, AlertCircle, ShieldCheck, Landmark,
  ArrowLeftRight, RefreshCw, Package, ChevronRight,
  CheckCircle2, XCircle,
} from 'lucide-react';
import { apiFetch } from '../config/api';

type Category = 'All' | 'KYC' | 'Bank' | 'Transaction' | 'SIP' | 'Maturing';
type Priority  = 'High' | 'Medium' | 'Low';

interface Action {
  id: string; category: Exclude<Category, 'All'>;
  priority: Priority; investor: string; desc: string; age: string;
}

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

const catIcon: Record<string, { icon: React.ReactNode; cls: string }> = {
  KYC:         { icon: <ShieldCheck    className="w-4 h-4" />, cls: 'bg-blue-100   text-blue-600'   },
  Bank:        { icon: <Landmark       className="w-4 h-4" />, cls: 'bg-green-100  text-green-600'  },
  Transaction: { icon: <ArrowLeftRight className="w-4 h-4" />, cls: 'bg-violet-100 text-violet-600' },
  SIP:         { icon: <RefreshCw      className="w-4 h-4" />, cls: 'bg-orange-100 text-orange-600' },
  Maturing:    { icon: <Package        className="w-4 h-4" />, cls: 'bg-slate-100  text-slate-600'  },
};

export default function ActionCenter({ onBack, userData }: { onBack: () => void; userData?: any }) {
  const [tab, setTab] = useState<Category>('All');
  const [actions, setActions] = useState<Action[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchActions = React.useCallback(() => {
    // ── FIX: if there is no authenticated user, stop the spinner immediately ──
    if (!userData?.id) {
      setLoading(false);
      return;
    }

    setLoading(true);
    setError('');
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };

    apiFetch(`/dashboard/distributor/${userData.id}/actions`, { headers })
      .then(res => {
        if (!res.ok) throw new Error(`Server error ${res.status}`);
        return res.json();
      })
      .then((data: Action[]) => {
        // Defensive: treat null / non-array as empty list — never leave spinner on
        setActions(Array.isArray(data) ? data : []);
        setLoading(false);
      })
      .catch(err => {
        console.error('Failed to fetch action center', err);
        setError('Could not load action items. Please try again.');
        setLoading(false);  // ── FIX: always exit the spinner even on failure ──
      });
  }, [userData]);

  React.useEffect(() => {
    fetchActions();
  }, [fetchActions]);

  const filtered = tab === 'All' ? actions : actions.filter(a => a.category === tab);
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
          const count = t.key === 'All' ? actions.length : actions.filter(a => a.category === t.key).length;
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
            key={`${tab}-${loading}-${error}`}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.15 }}
          >
            {/* ── State 1: Loading ── */}
            {loading ? (
              <div className="py-20 flex flex-col items-center gap-3 text-slate-400">
                <div className="w-8 h-8 border-4 border-slate-200 border-t-[#0B1B3E] rounded-full animate-spin" />
                <p className="text-sm">Loading action items…</p>
              </div>

            /* ── State 2: Error ── */
            ) : error ? (
              <div className="py-16 flex flex-col items-center gap-4 text-center px-8">
                <div className="w-12 h-12 rounded-2xl bg-red-50 flex items-center justify-center">
                  <XCircle className="w-6 h-6 text-red-400" />
                </div>
                <div>
                  <p className="font-semibold text-slate-700 mb-1">{error}</p>
                  <p className="text-xs text-slate-400">Check your connection or try refreshing.</p>
                </div>
                <button
                  onClick={fetchActions}
                  className="flex items-center gap-2 px-4 py-2 text-sm font-semibold bg-[#0B1B3E] text-white rounded-xl hover:bg-[#1A3066] transition-colors"
                >
                  <RefreshCw className="w-3.5 h-3.5" /> Retry
                </button>
              </div>

            /* ── State 3: Empty — API returned [] or tab has no items ── */
            ) : filtered.length === 0 ? (
              <div className="py-16 flex flex-col items-center gap-4 text-center px-8">
                <div className="w-12 h-12 rounded-2xl bg-green-50 flex items-center justify-center">
                  <CheckCircle2 className="w-6 h-6 text-green-500" />
                </div>
                <div>
                  <p className="font-semibold text-slate-700 mb-1">
                    {tab === 'All' ? 'No pending actions' : `No pending ${tab} actions`}
                  </p>
                  <p className="text-xs text-slate-400">
                    {tab === 'All'
                      ? 'All caught up! There are no tasks requiring your attention right now.'
                      : `No items in the ${tab} category at the moment.`}
                  </p>
                </div>
              </div>

            /* ── State 4: Has items ── */
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
