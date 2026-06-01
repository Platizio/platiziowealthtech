import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Calculator, TrendingUp, Target, Shield, Clock, BarChart2, ChevronDown, FileText } from 'lucide-react';
import SipCalculator from '../components/SipCalculator';
import LumpsumCalculator from '../components/LumpsumCalculator';
import GoalCalculator from '../components/GoalCalculator';
import TaxCalculator from '../components/TaxCalculator';

const CALCULATORS = [
  {
    id:        'sip'        as const,
    label:     'SIP Calculator',
    desc:      'Future value of a fixed monthly SIP with year-wise growth chart',
    Icon:      Calculator,
    available: true,
  },
  {
    id:        'lumpsum'    as const,
    label:     'Lumpsum Calculator',
    desc:      'Estimate growth of a one-time lumpsum investment over time',
    Icon:      TrendingUp,
    available: true,
  },
  {
    id:        'goal'       as const,
    label:     'Goal Planner',
    desc:      'Monthly SIP required to reach a target corpus by a set date',
    Icon:      Target,
    available: true,
  },
  {
    id:        'tax'        as const,
    label:     'Income Tax (Old vs New)',
    desc:      'Side-by-side liability under both regimes with savings recommendation',
    Icon:      FileText,
    available: true,
  },
  {
    id:        'elss'       as const,
    label:     'Tax Savings (ELSS)',
    desc:      'Estimate annual tax savings from ELSS fund investments',
    Icon:      Shield,
    available: false,
  },
  {
    id:        'retirement' as const,
    label:     'Retirement Planner',
    desc:      'Build a SIP plan to fund a comfortable post-retirement life',
    Icon:      Clock,
    available: false,
  },
  {
    id:        'inflation'  as const,
    label:     'Inflation Adjuster',
    desc:      'See real returns on your investments after adjusting for inflation',
    Icon:      BarChart2,
    available: false,
  },
];

type CalcId = typeof CALCULATORS[number]['id'];

export default function CalculatorsView({ userData }: { userData?: any }) {
  const [open, setOpen] = useState<CalcId | null>(null);

  // Jan (0), Feb (1), Mar (2) — tax declaration season in India
  const isTaxSeason = [0, 1, 2].includes(new Date().getMonth());

  const toggle = (id: CalcId, available: boolean) => {
    if (!available) return;
    setOpen(prev => (prev === id ? null : id));
  };

  const activeCalc = CALCULATORS.find(c => c.id === open);

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-6 space-y-6">

      <div>
        <h1 className="text-xl font-bold text-slate-800">Calculators</h1>
        <p className="text-sm text-slate-500 mt-0.5">Interactive tools to plan and visualise investment goals</p>
      </div>

      {/* Jan–Mar tax season CTA */}
      {isTaxSeason && (
        <div className="flex items-center gap-4 bg-amber-50 border border-amber-200 rounded-2xl px-5 py-3.5">
          <span className="text-xl flex-shrink-0">🧾</span>
          <div className="flex-1 min-w-0">
            <p className="text-sm font-semibold text-amber-800">Tax declaration season is here</p>
            <p className="text-xs text-amber-700 mt-0.5">
              Compare Old vs New regime before your employer requests your investment declaration.
            </p>
          </div>
          <button
            onClick={() => setOpen('tax')}
            className="px-4 py-2 bg-amber-500 text-white text-xs font-semibold rounded-xl hover:bg-amber-600 transition-colors flex-shrink-0"
          >
            Compare Now
          </button>
        </div>
      )}

      {/* Calculator type cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
        {CALCULATORS.map(({ id, label, desc, Icon, available }) => (
          <button
            key={id}
            onClick={() => toggle(id, available)}
            disabled={!available}
            className={`text-left p-4 rounded-2xl border transition-all ${
              !available
                ? 'bg-white border-slate-100 opacity-50 cursor-not-allowed'
                : open === id
                  ? 'bg-blue-50 border-blue-300 shadow-sm'
                  : 'bg-white border-slate-100 hover:border-slate-200 hover:shadow-sm'
            }`}
          >
            <div className="flex items-start justify-between mb-2">
              <Icon className={`w-4 h-4 ${open === id ? 'text-blue-600' : 'text-slate-400'}`} />
              {!available ? (
                <span className="text-[9px] font-bold bg-slate-100 text-slate-400 px-1.5 py-0.5 rounded uppercase tracking-wide">
                  Soon
                </span>
              ) : (
                <ChevronDown className={`w-4 h-4 text-slate-400 transition-transform duration-200 ${open === id ? 'rotate-180' : ''}`} />
              )}
            </div>
            <p className={`text-sm font-semibold ${open === id ? 'text-blue-700' : 'text-slate-700'}`}>
              {label}
            </p>
            <p className="text-xs text-slate-400 mt-0.5 leading-relaxed">{desc}</p>
          </button>
        ))}
      </div>

      {/* Expanded calculator panel */}
      <AnimatePresence>
        {open && activeCalc && (
          <motion.div
            key={open}
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.18 }}
            className="bg-white rounded-2xl border border-slate-100 shadow-sm"
          >
            {/* Panel header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
              <div className="flex items-center gap-2">
                <activeCalc.Icon className="w-4 h-4 text-blue-500" />
                <h2 className="text-sm font-bold text-slate-700">{activeCalc.label}</h2>
              </div>
              <button
                onClick={() => setOpen(null)}
                className="text-xs font-medium text-slate-500 hover:text-slate-700 px-3 py-1.5 bg-slate-100 rounded-lg hover:bg-slate-200 transition-colors"
              >
                Close
              </button>
            </div>

            {/* Panel content */}
            <div className="p-6">
              {open === 'sip'     && <SipCalculator      userData={userData} />}
              {open === 'lumpsum' && <LumpsumCalculator userData={userData} />}
              {open === 'goal'    && <GoalCalculator    userData={userData} />}
              {open === 'tax'     && <TaxCalculator     userData={userData} />}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

    </motion.div>
  );
}
