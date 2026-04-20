import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Search, Info, CheckCircle2, ChevronRight, X } from 'lucide-react';

const funds = [
  { id: 1, name: 'HDFC Large & Mid Cap Fund', category: 'Equity', risk: 'Very High', nav: '₹245.60', return1y: '28.4%' },
  { id: 2, name: 'Parag Parikh Flexi Cap Fund', category: 'Equity', risk: 'Very High', nav: '₹68.32', return1y: '32.1%' },
  { id: 3, name: 'ICICI Prudential Bluechip', category: 'Equity', risk: 'High', nav: '₹89.15', return1y: '21.5%' },
  { id: 4, name: 'SBI Liquid Fund', category: 'Debt', risk: 'Low', nav: '₹3451.20', return1y: '6.8%' },
  { id: 5, name: 'Quant Small Cap Fund', category: 'Equity', risk: 'Very High', nav: '₹182.40', return1y: '45.2%' },
  { id: 6, name: 'Kotak Corporate Bond Fund', category: 'Debt', risk: 'Moderate', nav: '₹3182.10', return1y: '7.2%' },
];

export default function Ledger() {
  const [investModal, setInvestModal] = useState<number | null>(null);

  const selectedFund = investModal ? funds.find(f => f.id === investModal) : null;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Investment Ledger</h1>
          <p className="text-slate-500 text-sm mt-1">Catalog and execute mutual fund transactions</p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {funds.map((fund) => (
          <div key={fund.id} className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200 flex flex-col group relative overflow-hidden transition-all hover:shadow-md">
            <div className="flex justify-between items-start mb-4">
              <span className={`px-2 py-1 rounded text-[10px] font-bold uppercase tracking-wider ${
                fund.category === 'Equity' ? 'bg-blue-50 text-blue-600' : 'bg-emerald-50 text-emerald-600'
              }`}>
                {fund.category}
              </span>
              <span className="text-xs font-medium text-slate-400 flex items-center gap-1"><Info className="w-3 h-3" /> {fund.risk}</span>
            </div>
            
            <h3 className="font-semibold text-slate-800 mb-6 pr-4">{fund.name}</h3>
            
            <div className="mt-auto grid grid-cols-2 gap-4 pb-12 transition-transform duration-300 group-hover:-translate-y-4">
              <div>
                <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">Current NAV</p>
                <p className="font-mono font-medium text-slate-700">{fund.nav}</p>
              </div>
              <div>
                <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">1Y Return</p>
                <p className="font-mono font-medium text-green-600">{fund.return1y}</p>
              </div>
            </div>

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

      <AnimatePresence>
        {investModal && (
          <TransactionModal fund={selectedFund} onClose={() => setInvestModal(null)} />
        )}
      </AnimatePresence>
    </motion.div>
  );
}

function TransactionModal({ fund, onClose }: { fund: any, onClose: () => void }) {
  const [step, setStep] = useState(1);
  const [type, setType] = useState('SIP');

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* Backdrop */}
      <motion.div 
        initial={{ opacity: 0 }} 
        animate={{ opacity: 1 }} 
        exit={{ opacity: 0 }} 
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm"
      />
      
      {/* Modal Content */}
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

          <div className="flex items-center gap-2 mb-8">
            <div className={`h-1.5 flex-1 rounded-full ${step >= 1 ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
            <div className={`h-1.5 flex-1 rounded-full ${step >= 2 ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
            <div className={`h-1.5 flex-1 rounded-full ${step >= 3 ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
          </div>

          <AnimatePresence mode="wait">
            {step === 1 && (
              <motion.div key="step1" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Select Investor</label>
                  <div className="relative">
                    <Search className="absolute left-4 top-3.5 w-4 h-4 text-slate-400" />
                    <input type="text" placeholder="Search investor by name or PAN..." className="w-full pl-11 pr-4 py-3 bg-slate-50 border border-slate-200 rounded-xl text-sm outline-none focus:ring-2 focus:ring-blue-100" />
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
              <motion.div key="step2" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div className="flex bg-slate-100 p-1 rounded-lg">
                  <button onClick={() => setType('SIP')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'SIP' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>SIP</button>
                  <button onClick={() => setType('Lumpsum')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'Lumpsum' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>Lumpsum</button>
                </div>

                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Investment Amount</label>
                  <div className="relative">
                    <span className="absolute left-4 top-3.5 text-slate-500 font-medium">₹</span>
                    <input type="number" defaultValue={type === 'SIP' ? 5000 : 100000} className="w-full pl-8 pr-4 py-3 bg-white border border-slate-200 rounded-xl text-lg font-medium outline-none focus:ring-2 focus:ring-blue-100" />
                  </div>
                </div>

                {type === 'SIP' && (
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Frequency</label>
                      <select className="w-full px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm outline-none cursor-pointer">
                        <option>Monthly</option>
                        <option>Weekly</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Debit Date</label>
                      <select className="w-full px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm outline-none cursor-pointer">
                        <option>5th of Month</option>
                        <option>10th of Month</option>
                        <option>15th of Month</option>
                      </select>
                    </div>
                  </div>
                )}
                
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Mandate Type</label>
                  <select className="w-full px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm outline-none cursor-pointer">
                    <option>UPI Mandate</option>
                    <option>eNACH via NetBanking</option>
                  </select>
                </div>
              </motion.div>
            )}

            {step === 3 && (
              <motion.div key="step3" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
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
                
                <div className="flex items-start gap-3 bg-blue-50/50 p-4 rounded-xl border border-blue-100 mt-6">
                  <Info className="w-5 h-5 text-blue-500 flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-blue-800 leading-relaxed">
                    A payment link will be sent to the investor's registered email and mobile number. The order will be processed upon successful mandate authorization.
                  </p>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          <div className="mt-10 flex gap-3">
            {step > 1 && (
              <button 
                onClick={() => setStep(step - 1)}
                className="px-6 py-3 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors"
              >
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
