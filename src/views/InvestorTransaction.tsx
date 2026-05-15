import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  ArrowLeft, ArrowRight, CheckCircle2, Building2,
  CreditCard, TrendingUp, ShieldCheck, Clock, Wallet,
} from 'lucide-react';

// ── Types ────────────────────────────────────────────────────────────────────
interface Investor {
  id: number;
  name: string;
  type: string;
  kyc: string;
  aum: string;
}

interface Props {
  investor: Investor;
  onComplete: () => void;
  onBack: () => void;
}

// ── Sample funds ─────────────────────────────────────────────────────────────
const PRODUCTS = [
  {
    id: 1, name: 'HDFC Large & Mid Cap Fund',
    category: 'Equity – Large & Mid Cap', risk: 'Moderate',
    nav: '₹198.45', returns: { '1Y': '+18.2%', '3Y': '+14.1%', '5Y': '+12.8%' },
    minSip: 1000, minLumpsum: 5000, color: 'blue',
  },
  {
    id: 2, name: 'Parag Parikh Flexi Cap Fund',
    category: 'Equity – Flexi Cap', risk: 'Moderate',
    nav: '₹89.12', returns: { '1Y': '+22.4%', '3Y': '+17.5%', '5Y': '+16.1%' },
    minSip: 1000, minLumpsum: 1000, color: 'violet',
  },
  {
    id: 3, name: 'ICICI Prudential Bluechip Fund',
    category: 'Equity – Large Cap', risk: 'Moderately Low',
    nav: '₹112.78', returns: { '1Y': '+15.3%', '3Y': '+12.8%', '5Y': '+11.4%' },
    minSip: 1000, minLumpsum: 5000, color: 'indigo',
  },
  {
    id: 4, name: 'SBI Small Cap Fund',
    category: 'Equity – Small Cap', risk: 'High',
    nav: '₹148.30', returns: { '1Y': '+28.7%', '3Y': '+21.2%', '5Y': '+20.5%' },
    minSip: 500, minLumpsum: 5000, color: 'rose',
  },
  {
    id: 5, name: 'HDFC Short Term Debt Fund',
    category: 'Debt – Short Term', risk: 'Low',
    nav: '₹28.14', returns: { '1Y': '+7.4%', '3Y': '+6.9%', '5Y': '+7.1%' },
    minSip: 1000, minLumpsum: 5000, color: 'emerald',
  },
];

const BANKS = [
  { id: 1, name: 'HDFC Bank',          account: '****4532', ifsc: 'HDFC0001234' },
  { id: 2, name: 'ICICI Bank',         account: '****8901', ifsc: 'ICIC0002345' },
  { id: 3, name: 'State Bank of India', account: '****2217', ifsc: 'SBIN0003456' },
];

const STEPS = [
  { id: 1, label: 'Select Fund' },
  { id: 2, label: 'Amount'      },
  { id: 3, label: 'Bank'        },
  { id: 4, label: 'Confirm'     },
];

const riskColor: Record<string, string> = {
  'Low':              'text-green-700 bg-green-50',
  'Moderately Low':   'text-cyan-700 bg-cyan-50',
  'Moderate':         'text-amber-700 bg-amber-50',
  'High':             'text-red-700 bg-red-50',
};

const accentColor: Record<string, string> = {
  blue: 'border-blue-500 bg-blue-50',
  violet: 'border-violet-500 bg-violet-50',
  indigo: 'border-indigo-500 bg-indigo-50',
  rose: 'border-rose-500 bg-rose-50',
  emerald: 'border-emerald-500 bg-emerald-50',
};

// ── Step indicator ───────────────────────────────────────────────────────────
function StepBar({ current }: { current: number }) {
  return (
    <div className="flex items-center gap-0 mb-8">
      {STEPS.map((s, i) => (
        <React.Fragment key={s.id}>
          <div className="flex flex-col items-center">
            <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold border-2 transition-all ${
              s.id < current
                ? 'bg-green-500 border-green-500 text-white'
                : s.id === current
                  ? 'bg-[#0B1B3E] border-[#0B1B3E] text-white'
                  : 'bg-white border-slate-200 text-slate-400'
            }`}>
              {s.id < current ? <CheckCircle2 className="w-4 h-4" /> : s.id}
            </div>
            <span className={`text-[10px] font-semibold mt-1 ${s.id === current ? 'text-slate-800' : 'text-slate-400'}`}>
              {s.label}
            </span>
          </div>
          {i < STEPS.length - 1 && (
            <div className={`flex-1 h-0.5 mx-2 mb-5 ${s.id < current ? 'bg-green-400' : 'bg-slate-200'}`} />
          )}
        </React.Fragment>
      ))}
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────
export default function InvestorTransaction({ investor, onComplete, onBack }: Props) {
  const [step,     setStep]     = useState(1);
  const [product,  setProduct]  = useState<typeof PRODUCTS[0] | null>(null);
  const [txType,   setTxType]   = useState<'sip' | 'lumpsum'>('sip');
  const [amount,   setAmount]   = useState('');
  const [bank,     setBank]     = useState<typeof BANKS[0] | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done,     setDone]     = useState(false);
  const [refNo]                 = useState(`APX${Date.now().toString().slice(-8)}`);

  const amountNum = parseFloat(amount.replace(/,/g, '')) || 0;
  const minAmount = product ? (txType === 'sip' ? product.minSip : product.minLumpsum) : 0;
  const amountValid = amountNum >= minAmount;

  const handleConfirm = () => {
    setSubmitting(true);
    setTimeout(() => { setSubmitting(false); setDone(true); }, 1800);
  };

  // ── Success screen ─────────────────────────────────────────────────────────
  if (done) {
    return (
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        className="p-8 max-w-xl mx-auto text-center"
      >
        <div className="bg-white rounded-3xl shadow-sm border border-slate-200 p-10">
          <div className="w-20 h-20 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-6">
            <CheckCircle2 className="w-10 h-10 text-green-500" />
          </div>
          <h1 className="text-2xl font-bold text-slate-800 mb-2">Transaction Submitted!</h1>
          <p className="text-slate-500 text-sm mb-8">
            Your {txType === 'sip' ? 'SIP mandate' : 'lumpsum investment'} has been placed successfully.
          </p>

          <div className="bg-slate-50 rounded-2xl p-5 text-left space-y-3 mb-8">
            <Row label="Reference No."  value={refNo}                              mono />
            <Row label="Investor"       value={investor.name}                      />
            <Row label="Fund"           value={product?.name || ''}               />
            <Row label="Type"           value={txType === 'sip' ? 'SIP' : 'Lumpsum'} />
            <Row label="Amount"         value={`₹${Number(amount.replace(/,/g,'')).toLocaleString('en-IN')}`} />
            <Row label="Bank"           value={`${bank?.name} ${bank?.account}`}  />
            <Row label="Status"         value="Processing"
              valueClass="inline-flex items-center gap-1.5 text-amber-700 font-semibold">
              <Clock className="w-3.5 h-3.5" />
            </Row>
          </div>

          <div className="grid grid-cols-3 gap-3 text-center text-xs mb-8">
            {[
              { icon: <ShieldCheck className="w-4 h-4 text-green-500" />, label: 'SEBI Compliant' },
              { icon: <Clock        className="w-4 h-4 text-blue-500"  />, label: '1–2 Working Days' },
              { icon: <Wallet       className="w-4 h-4 text-violet-500"/>, label: 'Auto-Debit Setup' },
            ].map(b => (
              <div key={b.label} className="bg-slate-50 rounded-xl p-3 flex flex-col items-center gap-1.5">
                {b.icon}
                <span className="text-slate-600 font-medium">{b.label}</span>
              </div>
            ))}
          </div>

          <button
            onClick={onComplete}
            className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors shadow-lg"
          >
            Back to Investors
          </button>
        </div>
      </motion.div>
    );
  }

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-8 max-w-3xl"
    >
      {/* Back */}
      <button onClick={onBack}
        className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors mb-6">
        <ArrowLeft className="w-4 h-4" /> Back to Investors
      </button>

      <div className="mb-8">
        <h1 className="text-2xl font-semibold text-slate-800">New Investment</h1>
        <p className="text-slate-500 text-sm mt-1">
          Placing transaction for <span className="font-semibold text-slate-700">{investor.name}</span>
        </p>
      </div>

      <StepBar current={step} />

      <AnimatePresence mode="wait">

        {/* ── Step 1: Select Fund ─────────────────────────────────────────── */}
        {step === 1 && (
          <motion.div key="s1" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
            <h2 className="font-semibold text-slate-800 mb-4">Select a Fund</h2>
            <div className="space-y-3">
              {PRODUCTS.map(p => (
                <button
                  key={p.id}
                  onClick={() => setProduct(p)}
                  className={`w-full text-left rounded-2xl border-2 p-5 transition-all ${
                    product?.id === p.id
                      ? accentColor[p.color]
                      : 'border-slate-200 bg-white hover:border-slate-300'
                  }`}
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <p className="font-semibold text-slate-800 text-sm">{p.name}</p>
                        {product?.id === p.id && (
                          <CheckCircle2 className="w-4 h-4 text-green-500 flex-shrink-0" />
                        )}
                      </div>
                      <p className="text-xs text-slate-500 mb-2">{p.category}</p>
                      <div className="flex items-center gap-3">
                        <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full ${riskColor[p.risk]}`}>
                          {p.risk} Risk
                        </span>
                        <span className="text-xs text-slate-400">NAV {p.nav}</span>
                      </div>
                    </div>
                    <div className="text-right flex-shrink-0">
                      <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">Returns</p>
                      <div className="flex gap-3">
                        {Object.entries(p.returns).map(([period, ret]) => (
                          <div key={period} className="text-center">
                            <p className="text-[10px] text-slate-400">{period}</p>
                            <p className="text-xs font-bold text-green-600">{ret}</p>
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </button>
              ))}
            </div>

            <div className="flex justify-end mt-6">
              <button
                onClick={() => setStep(2)}
                disabled={!product}
                className="flex items-center gap-2 px-6 py-3 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-50 disabled:cursor-not-allowed shadow-lg"
              >
                Next <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </motion.div>
        )}

        {/* ── Step 2: Amount ──────────────────────────────────────────────── */}
        {step === 2 && product && (
          <motion.div key="s2" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
            <h2 className="font-semibold text-slate-800 mb-4">Investment Details</h2>

            {/* Selected fund summary */}
            <div className="bg-slate-50 rounded-2xl p-4 border border-slate-200 mb-6">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-blue-100 flex items-center justify-center flex-shrink-0">
                  <TrendingUp className="w-5 h-5 text-blue-600" />
                </div>
                <div>
                  <p className="font-semibold text-slate-800 text-sm">{product.name}</p>
                  <p className="text-xs text-slate-500">{product.category} · NAV {product.nav}</p>
                </div>
              </div>
            </div>

            {/* SIP / Lumpsum toggle */}
            <div className="mb-6">
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Investment Type</label>
              <div className="flex bg-slate-100 rounded-xl p-1 gap-1 w-fit">
                {(['sip', 'lumpsum'] as const).map(t => (
                  <button
                    key={t}
                    onClick={() => { setTxType(t); setAmount(''); }}
                    className={`px-5 py-2 text-sm font-semibold rounded-lg transition-all ${
                      txType === t ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500 hover:text-slate-700'
                    }`}
                  >
                    {t === 'sip' ? 'SIP (Monthly)' : 'Lumpsum'}
                  </button>
                ))}
              </div>
            </div>

            {/* Amount input */}
            <div className="mb-6">
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                {txType === 'sip' ? 'Monthly SIP Amount' : 'Investment Amount'}{' '}
                <span className="text-red-400">*</span>
              </label>
              <div className="relative">
                <span className="absolute left-4 top-3.5 text-sm font-semibold text-slate-400">₹</span>
                <input
                  type="text"
                  value={amount}
                  onChange={e => setAmount(e.target.value.replace(/[^0-9,]/g, ''))}
                  placeholder={`Min. ₹${(txType === 'sip' ? product.minSip : product.minLumpsum).toLocaleString('en-IN')}`}
                  className={`w-full pl-8 pr-4 py-3 bg-slate-50 border rounded-xl text-sm font-mono font-semibold focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all ${
                    amount && !amountValid ? 'border-red-300 bg-red-50' : 'border-slate-200'
                  }`}
                />
              </div>
              {amount && !amountValid && (
                <p className="text-xs text-red-500 mt-1">
                  Minimum {txType === 'sip' ? 'SIP' : 'lumpsum'} amount is ₹{(txType === 'sip' ? product.minSip : product.minLumpsum).toLocaleString('en-IN')}
                </p>
              )}
              <p className="text-[11px] text-slate-400 mt-1">
                {txType === 'sip'
                  ? 'Amount will be auto-debited every month on the chosen SIP date.'
                  : 'One-time investment. Amount will be debited within 2 working days.'}
              </p>
            </div>

            {txType === 'sip' && (
              <div className="mb-6">
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">SIP Date</label>
                <div className="flex gap-2 flex-wrap">
                  {[1, 5, 7, 10, 15, 20, 25, 28].map(d => (
                    <button key={d}
                      className="w-10 h-10 rounded-xl border-2 border-slate-200 bg-white text-sm font-semibold text-slate-600 hover:border-blue-400 hover:text-blue-600 transition-all">
                      {d}
                    </button>
                  ))}
                </div>
                <p className="text-[11px] text-slate-400 mt-1">Select the day of each month for your SIP debit.</p>
              </div>
            )}

            <div className="flex items-center justify-between mt-6">
              <button onClick={() => setStep(1)}
                className="flex items-center gap-2 px-5 py-2.5 text-sm font-medium text-slate-600 border border-slate-200 rounded-xl hover:bg-slate-50 transition-colors">
                <ArrowLeft className="w-4 h-4" /> Back
              </button>
              <button
                onClick={() => setStep(3)}
                disabled={!amountValid}
                className="flex items-center gap-2 px-6 py-3 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-50 disabled:cursor-not-allowed shadow-lg"
              >
                Next <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </motion.div>
        )}

        {/* ── Step 3: Bank ────────────────────────────────────────────────── */}
        {step === 3 && (
          <motion.div key="s3" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
            <h2 className="font-semibold text-slate-800 mb-4">Select Payment Bank</h2>
            <p className="text-sm text-slate-500 mb-6">
              Choose the bank account to {txType === 'sip' ? 'set up auto-debit mandate' : 'debit the investment amount'}.
            </p>

            <div className="space-y-3 mb-6">
              {BANKS.map(b => (
                <button
                  key={b.id}
                  onClick={() => setBank(b)}
                  className={`w-full text-left rounded-2xl border-2 p-5 transition-all ${
                    bank?.id === b.id
                      ? 'border-blue-500 bg-blue-50'
                      : 'border-slate-200 bg-white hover:border-slate-300'
                  }`}
                >
                  <div className="flex items-center gap-4">
                    <div className="w-10 h-10 rounded-xl bg-slate-100 flex items-center justify-center flex-shrink-0">
                      <Building2 className="w-5 h-5 text-slate-500" />
                    </div>
                    <div className="flex-1">
                      <p className="font-semibold text-slate-800 text-sm">{b.name}</p>
                      <p className="text-xs text-slate-500">Account {b.account} · IFSC {b.ifsc}</p>
                    </div>
                    {bank?.id === b.id && <CheckCircle2 className="w-5 h-5 text-blue-500 flex-shrink-0" />}
                  </div>
                </button>
              ))}
            </div>

            <div className="bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 flex items-start gap-3 mb-6">
              <CreditCard className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-amber-800 leading-relaxed">
                {txType === 'sip'
                  ? 'A UPI AutoPay / eNACH mandate will be registered for the selected bank account.'
                  : 'The investment amount will be debited from the selected account within 1–2 working days.'}
              </p>
            </div>

            <div className="flex items-center justify-between">
              <button onClick={() => setStep(2)}
                className="flex items-center gap-2 px-5 py-2.5 text-sm font-medium text-slate-600 border border-slate-200 rounded-xl hover:bg-slate-50 transition-colors">
                <ArrowLeft className="w-4 h-4" /> Back
              </button>
              <button
                onClick={() => setStep(4)}
                disabled={!bank}
                className="flex items-center gap-2 px-6 py-3 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-50 disabled:cursor-not-allowed shadow-lg"
              >
                Review Order <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </motion.div>
        )}

        {/* ── Step 4: Confirm ─────────────────────────────────────────────── */}
        {step === 4 && product && bank && (
          <motion.div key="s4" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
            <h2 className="font-semibold text-slate-800 mb-4">Review &amp; Confirm</h2>

            <div className="bg-white rounded-2xl border border-slate-200 p-6 mb-6 space-y-4">
              <p className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-2">Order Summary</p>
              <Row label="Investor"    value={investor.name} />
              <Row label="Fund"        value={product.name}  />
              <Row label="Category"    value={product.category} />
              <Row label="Type"        value={txType === 'sip' ? 'SIP – Monthly' : 'Lumpsum (One-time)'} />
              <Row label="Amount"
                value={`₹${Number(amount.replace(/,/g,'')).toLocaleString('en-IN')}${txType === 'sip' ? ' / month' : ''}`}
                valueClass="font-bold text-blue-700" />
              <Row label="Bank"
                value={`${bank.name} · ${bank.account}`} />
              <Row label="IFSC"        value={bank.ifsc} />
            </div>

            <div className="bg-[#0B1B3E]/5 border border-[#0B1B3E]/10 rounded-xl px-4 py-3 flex items-start gap-3 mb-6">
              <ShieldCheck className="w-4 h-4 text-[#0B1B3E] flex-shrink-0 mt-0.5" />
              <p className="text-xs text-slate-600 leading-relaxed">
                By confirming, you authorise Platizio to place this {txType === 'sip' ? 'SIP mandate' : 'investment order'} on behalf of{' '}
                <span className="font-semibold">{investor.name}</span> in accordance with SEBI regulations and the investor's signed consent.
              </p>
            </div>

            <div className="flex items-center justify-between">
              <button onClick={() => setStep(3)}
                className="flex items-center gap-2 px-5 py-2.5 text-sm font-medium text-slate-600 border border-slate-200 rounded-xl hover:bg-slate-50 transition-colors">
                <ArrowLeft className="w-4 h-4" /> Back
              </button>
              <button
                onClick={handleConfirm}
                disabled={submitting}
                className="flex items-center gap-2 px-8 py-3 bg-green-600 text-white font-semibold text-sm rounded-xl hover:bg-green-700 transition-colors disabled:opacity-60 shadow-lg"
              >
                {submitting
                  ? (<><div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> Processing…</>)
                  : (<><CheckCircle2 className="w-4 h-4" /> Confirm &amp; Place Order</>)}
              </button>
            </div>
          </motion.div>
        )}

      </AnimatePresence>
    </motion.div>
  );
}

// ── Helper row ────────────────────────────────────────────────────────────────
function Row({
  label, value, valueClass = '', mono = false, children,
}: {
  label: string; value: string; valueClass?: string; mono?: boolean; children?: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-2.5 border-b border-slate-100 last:border-0">
      <span className="text-xs font-semibold text-slate-500 flex-shrink-0">{label}</span>
      <span className={`text-sm text-slate-800 text-right ${mono ? 'font-mono' : 'font-medium'} ${valueClass} flex items-center gap-1.5`}>
        {children}{value}
      </span>
    </div>
  );
}
