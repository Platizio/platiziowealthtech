import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Info, CheckCircle2, ChevronRight, X, Search } from 'lucide-react';
import { Product, DISTRIBUTOR_TIER, isVisibleToTier } from '../data/products';

const categoryStyle: Record<string, string> = {
  Equity:    'bg-blue-50 text-blue-600',
  Debt:      'bg-emerald-50 text-emerald-600',
  ELSS:      'bg-orange-50 text-orange-600',
  Hybrid:    'bg-purple-50 text-purple-600',
  Strategic: 'bg-violet-50 text-violet-700',
};

const tierBadge: Record<string, string> = {
  'Gold & Above':   'bg-amber-50 text-amber-700 border-amber-200',
  'Silver & Above': 'bg-slate-100 text-slate-600 border-slate-200',
  'Platinum Only':  'bg-violet-50 text-violet-700 border-violet-200',
};

export default function Ledger({ products }: { products: Product[] }) {
  const [investModal,     setInvestModal]     = useState<number | null>(null);
  const [categoryFilter,  setCategoryFilter]  = useState('All');

  // Only show Active products that the distributor's tier can access
  const visible = products.filter(
    p => p.status === 'Active' && isVisibleToTier(p.visibility, DISTRIBUTOR_TIER)
  );

  // Derive category list from currently visible products
  const categories = ['All', ...Array.from(new Set(visible.map(p => p.category)))];

  const filtered = categoryFilter === 'All'
    ? visible
    : visible.filter(p => p.category === categoryFilter);

  const selectedFund = investModal !== null
    ? (products.find(f => f.id === investModal) ?? null)
    : null;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8">

      {/* Header */}
      <div className="flex justify-between items-start mb-6">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Product Catalog</h1>
          <p className="text-slate-500 text-sm mt-1">Browse available funds and execute investments</p>
        </div>
        <div className="text-right">
          <p className="text-xs text-slate-500">
            <span className="font-semibold text-slate-700">{filtered.length}</span> products available
          </p>
          <p className="text-[10px] text-slate-400 mt-0.5">
            Tier: <span className="font-bold text-amber-600">{DISTRIBUTOR_TIER}</span>
          </p>
        </div>
      </div>

      {/* Category filter pills */}
      <div className="flex gap-2 mb-6 flex-wrap">
        {categories.map(cat => (
          <button
            key={cat}
            onClick={() => setCategoryFilter(cat)}
            className={`px-3.5 py-1.5 text-xs font-semibold rounded-full border transition-colors ${
              categoryFilter === cat
                ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]'
                : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300 hover:bg-slate-50'
            }`}
          >
            {cat}
          </button>
        ))}
      </div>

      {/* Empty state */}
      {filtered.length === 0 && (
        <div className="flex flex-col items-center justify-center py-24 text-slate-400">
          <div className="w-14 h-14 rounded-2xl bg-slate-100 flex items-center justify-center mb-4">
            <Info className="w-6 h-6 text-slate-300" />
          </div>
          <p className="text-base font-semibold text-slate-500">No products available</p>
          <p className="text-sm mt-1">Products will appear here once activated by the admin team</p>
        </div>
      )}

      {/* Fund cards grid */}
      {filtered.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {filtered.map(fund => (
            <div
              key={fund.id}
              className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200 flex flex-col group relative overflow-hidden transition-all hover:shadow-md"
            >
              {/* Top badges */}
              <div className="flex justify-between items-start mb-4">
                <div className="flex gap-1.5 flex-wrap">
                  <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${
                    fund.assetClass === 'MF' ? 'bg-blue-50 text-blue-600' : 'bg-violet-50 text-violet-700'
                  }`}>
                    {fund.assetClass}
                  </span>
                  <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${
                    categoryStyle[fund.category] ?? 'bg-slate-50 text-slate-600'
                  }`}>
                    {fund.category}
                  </span>
                  {fund.visibility !== 'All Tiers' && (
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider border ${
                      tierBadge[fund.visibility] ?? 'bg-slate-50 text-slate-500 border-slate-200'
                    }`}>
                      {fund.visibility === 'Gold & Above'   ? 'Gold+'     :
                       fund.visibility === 'Silver & Above' ? 'Silver+'   :
                       fund.visibility === 'Platinum Only'  ? 'Platinum'  : fund.visibility}
                    </span>
                  )}
                </div>
                <span className="text-xs font-medium text-slate-400 flex items-center gap-1 flex-shrink-0">
                  <Info className="w-3 h-3" /> {fund.riskLevel}
                </span>
              </div>

              {/* Name + AMC */}
              <h3 className="font-semibold text-slate-800 mb-1 leading-snug">{fund.name}</h3>
              <p className="text-xs text-slate-400 mb-5">{fund.amc}</p>

              {/* Stats — slide up on hover to reveal CTA */}
              <div className="mt-auto grid grid-cols-3 gap-2 pb-12 transition-transform duration-300 group-hover:-translate-y-4">
                <div>
                  <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">NAV</p>
                  <p className="font-mono font-medium text-slate-700 text-sm">{fund.nav}</p>
                </div>
                <div>
                  <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">1Y Return</p>
                  <p className="font-mono font-medium text-green-600 text-sm">{fund.return1y}</p>
                </div>
                <div>
                  <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">Min.</p>
                  <p className="font-mono font-medium text-slate-700 text-sm leading-tight">{fund.minInvest}</p>
                </div>
              </div>

              {/* CTA — hidden below card, slides up into view */}
              <div className="absolute -bottom-16 left-0 right-0 p-4 transition-transform duration-300 group-hover:-translate-y-16">
                <button
                  onClick={() => setInvestModal(fund.id)}
                  className="w-full py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-lg hover:bg-[#1A3066] transition-colors"
                >
                  Invest Now
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <AnimatePresence>
        {investModal !== null && selectedFund && (
          <TransactionModal fund={selectedFund} onClose={() => setInvestModal(null)} />
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Transaction Modal ────────────────────────────────────────────────────────

function TransactionModal({ fund, onClose }: { fund: Product; onClose: () => void }) {
  const [step, setStep] = useState(1);
  const [type, setType] = useState('SIP');

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm"
      />
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 20 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white/95 backdrop-blur-xl w-full max-w-lg rounded-3xl shadow-xl overflow-hidden relative z-10 border border-white/20"
      >
        <div className="absolute top-4 right-4">
          <button onClick={onClose} className="p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-8">
          <h2 className="text-xl font-semibold mb-1 text-slate-800">New Transaction</h2>
          <p className="text-sm text-slate-500 mb-8">{fund.name}</p>

          {/* Progress bar */}
          <div className="flex items-center gap-2 mb-8">
            <div className={`h-1.5 flex-1 rounded-full ${step >= 1 ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
            <div className={`h-1.5 flex-1 rounded-full ${step >= 2 ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
            <div className={`h-1.5 flex-1 rounded-full ${step >= 3 ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
          </div>

          <AnimatePresence mode="wait">
            {step === 1 && (
              <motion.div key="s1" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Select Investor</label>
                  <div className="relative">
                    <Search className="absolute left-4 top-3.5 w-4 h-4 text-slate-400" />
                    <input type="text" placeholder="Search investor by name or PAN…"
                      className="w-full pl-11 pr-4 py-3 bg-slate-50 border border-slate-200 rounded-xl text-sm outline-none focus:ring-2 focus:ring-blue-100" />
                  </div>
                  <div className="mt-2 bg-slate-50 border border-slate-200 rounded-xl divide-y divide-slate-100">
                    <div className="p-3 flex items-center gap-3 cursor-pointer hover:bg-slate-100/50">
                      <div className="w-8 h-8 rounded-full bg-blue-100 text-blue-700 flex items-center justify-center text-xs font-bold">AS</div>
                      <div>
                        <div className="text-sm font-semibold">Aditya Sharma</div>
                        <div className="text-xs text-slate-500 font-mono">PAN: ABCDE1234F</div>
                      </div>
                    </div>
                  </div>
                </div>
              </motion.div>
            )}

            {step === 2 && (
              <motion.div key="s2" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div className="flex bg-slate-100 p-1 rounded-lg">
                  <button onClick={() => setType('SIP')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'SIP' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>SIP</button>
                  <button onClick={() => setType('Lumpsum')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'Lumpsum' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>Lumpsum</button>
                </div>
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Investment Amount</label>
                  <div className="relative">
                    <span className="absolute left-4 top-3.5 text-slate-500 font-medium">₹</span>
                    <input type="number" defaultValue={type === 'SIP' ? 5000 : 100000}
                      className="w-full pl-8 pr-4 py-3 bg-white border border-slate-200 rounded-xl text-lg font-medium outline-none focus:ring-2 focus:ring-blue-100" />
                  </div>
                </div>
                {type === 'SIP' && (
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Frequency</label>
                      <select className="w-full px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm outline-none cursor-pointer">
                        <option>Monthly</option><option>Weekly</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Debit Date</label>
                      <select className="w-full px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm outline-none cursor-pointer">
                        <option>5th of Month</option><option>10th of Month</option><option>15th of Month</option>
                      </select>
                    </div>
                  </div>
                )}
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Mandate Type</label>
                  <select className="w-full px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm outline-none cursor-pointer">
                    <option>UPI Mandate</option><option>eNACH via NetBanking</option>
                  </select>
                </div>
              </motion.div>
            )}

            {step === 3 && (
              <motion.div key="s3" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div className="bg-slate-50 rounded-xl p-6 border border-slate-100 space-y-4">
                  <div className="flex justify-between items-center pb-4 border-b border-slate-200">
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Investor</p>
                      <p className="font-medium text-slate-800">Aditya Sharma</p>
                    </div>
                    <CheckCircle2 className="w-5 h-5 text-green-500" />
                  </div>
                  <div>
                    <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Fund</p>
                    <p className="font-medium text-slate-800">{fund.name}</p>
                  </div>
                  <div className="grid grid-cols-2 pt-4">
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">{type}</p>
                      <p className="font-mono font-medium text-slate-800">₹{type === 'SIP' ? '5,000' : '1,00,000'}</p>
                    </div>
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Mode</p>
                      <p className="font-medium text-slate-800">UPI Mandate</p>
                    </div>
                  </div>
                </div>
                <div className="flex items-start gap-3 bg-blue-50/50 p-4 rounded-xl border border-blue-100">
                  <Info className="w-5 h-5 text-blue-500 flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-blue-800 leading-relaxed">
                    A payment link will be sent to the investor's registered email and mobile number. The order will be processed upon successful mandate authorisation.
                  </p>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          <div className="mt-10 flex gap-3">
            {step > 1 && (
              <button onClick={() => setStep(step - 1)}
                className="px-6 py-3 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">
                Back
              </button>
            )}
            <button
              onClick={() => step < 3 ? setStep(step + 1) : onClose()}
              className="flex-1 py-3 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors flex items-center justify-center gap-2"
            >
              {step === 3 ? 'Confirm & Trigger Link' : 'Continue'}
              {step !== 3 && <ChevronRight className="w-4 h-4" />}
            </button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
