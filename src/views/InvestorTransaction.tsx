import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  ArrowLeft, ArrowRight, CheckCircle2, Building2,
  CreditCard, TrendingUp, ShieldCheck, Clock, Wallet, AlertCircle,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import { getPageContent } from '../utils/pagination';
import { formatDate } from '../utils/formatDate';

// ── Types ────────────────────────────────────────────────────────────────────
interface Investor {
  id: string;
  name?: string;
  fullName?: string;
  type: string;
  kyc: string;
  aum: string;
  bankVerificationStatus?: string;
}

interface Props {
  investor: Investor;
  onComplete: () => void;
  onBack: () => void;
}

type ProductColor = 'blue' | 'violet' | 'indigo' | 'rose' | 'emerald';
type Product = {
  id: string;
  name: string;
  category: string;
  risk: string;
  nav: string;
  returns: Record<string, string>;
  minSip: number;
  minLumpsum: number;
  color: ProductColor;
};

type BankAccount = {
  id: string;
  bankName?: string;
  accountNumber?: string;
  ifscCode?: string;
  verificationStatus?: string;
  cybrillaBankId?: string;
};

const sipSchema = z.object({
  amount: z.number().min(500, 'Minimum SIP is ₹500'),
  frequency: z.enum(['MONTHLY', 'QUARTERLY']),
  startDate: z.string().refine(d => new Date(d) > new Date(), 'Start date must be in the future'),
  instalments: z.number().int().positive().optional(),
});

type SipFormValues = z.infer<typeof sipSchema>;

const frequencyLabel = (value: SipFormValues['frequency']) =>
  value === 'MONTHLY' ? 'Monthly' : 'Quarterly';

const parseAmountInput = (value: unknown) => {
  if (typeof value === 'number') return value;
  const parsed = Number(String(value ?? '').replace(/,/g, ''));
  return Number.isFinite(parsed) ? parsed : 0;
};

const parseOptionalPositiveInt = (value: unknown) => {
  if (value === '' || value === null || value === undefined) return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
};

// ── Sample funds ─────────────────────────────────────────────────────────────
const PRODUCTS: Product[] = [
  {
    id: '11111111-1111-4111-8111-111111111111', name: 'HDFC Large & Mid Cap Fund',
    category: 'Equity – Large & Mid Cap', risk: 'Moderate',
    nav: '₹198.45', returns: { '1Y': '+18.2%', '3Y': '+14.1%', '5Y': '+12.8%' },
    minSip: 1000, minLumpsum: 5000, color: 'blue',
  },
  {
    id: '22222222-2222-4222-8222-222222222222', name: 'Parag Parikh Flexi Cap Fund',
    category: 'Equity – Flexi Cap', risk: 'Moderate',
    nav: '₹89.12', returns: { '1Y': '+22.4%', '3Y': '+17.5%', '5Y': '+16.1%' },
    minSip: 1000, minLumpsum: 1000, color: 'violet',
  },
  {
    id: '33333333-3333-4333-8333-333333333333', name: 'ICICI Prudential Bluechip Fund',
    category: 'Equity – Large Cap', risk: 'Moderately Low',
    nav: '₹112.78', returns: { '1Y': '+15.3%', '3Y': '+12.8%', '5Y': '+11.4%' },
    minSip: 1000, minLumpsum: 5000, color: 'indigo',
  },
  {
    id: '44444444-4444-4444-8444-444444444444', name: 'SBI Small Cap Fund',
    category: 'Equity – Small Cap', risk: 'High',
    nav: '₹148.30', returns: { '1Y': '+28.7%', '3Y': '+21.2%', '5Y': '+20.5%' },
    minSip: 500, minLumpsum: 5000, color: 'rose',
  },
  {
    id: '55555555-5555-4555-8555-555555555555', name: 'HDFC Short Term Debt Fund',
    category: 'Debt – Short Term', risk: 'Low',
    nav: '₹28.14', returns: { '1Y': '+7.4%', '3Y': '+6.9%', '5Y': '+7.1%' },
    minSip: 1000, minLumpsum: 5000, color: 'emerald',
  },
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

const accentColor: Record<ProductColor, string> = {
  blue: 'border-blue-500 bg-blue-50',
  violet: 'border-violet-500 bg-violet-50',
  indigo: 'border-indigo-500 bg-indigo-50',
  rose: 'border-rose-500 bg-rose-50',
  emerald: 'border-emerald-500 bg-emerald-50',
};

const maskAccountNumber = (value?: string) => {
  if (!value) return 'Account not captured';
  const lastFour = value.slice(-4);
  return `${'*'.repeat(Math.max(value.length - 4, 4))}${lastFour}`;
};

const bankLabel = (bank?: BankAccount | null) =>
  bank ? `${bank.bankName || 'Bank'} ${maskAccountNumber(bank.accountNumber)}` : '';

const isVerifiedBank = (bank: BankAccount) =>
  String(bank.verificationStatus || '').toUpperCase() === 'VERIFIED';

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
  const [products, setProducts] = useState(PRODUCTS);
  const [product,  setProduct]  = useState<Product | null>(null);
  const [txType,   setTxType]   = useState<'sip' | 'lumpsum'>('sip');
  const [banks, setBanks] = useState<BankAccount[]>([]);
  const [bank, setBank] = useState<BankAccount | null>(null);
  const [bankLoading, setBankLoading] = useState(false);
  const [bankError, setBankError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done,     setDone]     = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [refNo]                 = useState(`APX${Date.now().toString().slice(-8)}`);
  const {
    register,
    watch,
    trigger,
    formState: { errors },
    resetField,
  } = useForm<SipFormValues>({
    resolver: zodResolver(sipSchema),
    mode: 'onBlur',
    reValidateMode: 'onBlur',
    defaultValues: {
      frequency: 'MONTHLY',
      startDate: '',
    },
  });

  const amountNum = parseAmountInput(watch('amount'));
  const frequency = watch('frequency') || 'MONTHLY';
  const startDate = watch('startDate') || '';
  const instalments = watch('instalments');
  const minAmount = product ? (txType === 'sip' ? 500 : product.minLumpsum) : 0;
  const amountValid = amountNum >= minAmount;
  const investorName = investor.fullName || investor.name || 'Investor';
  const BANKS = banks.filter(isVerifiedBank).map(b => ({
    ...b,
    name: b.bankName || 'Bank',
    account: maskAccountNumber(b.accountNumber),
    ifsc: b.ifscCode || 'Currently unavailable',
  }));
  const isSip = txType === 'sip';
  const sipFieldsValid = !isSip || sipSchema.safeParse({
    amount: amountNum,
    frequency,
    startDate,
    instalments,
  }).success;

  React.useEffect(() => {
    let cancelled = false;

    apiFetch('/products/schemes?page=0&size=50')
      .then(res => res.ok ? res.json() : null)
      .then(payload => {
        if (cancelled || !payload) return;
        const schemes = getPageContent(payload);
        if (schemes.length === 0) return;
        const colors: ProductColor[] = ['blue', 'violet', 'indigo', 'rose', 'emerald'];
        setProducts(schemes.map((scheme: any, index: number) => ({
          id: scheme.id,
          name: scheme.schemeName || scheme.name || 'Unknown Scheme',
          category: scheme.category || scheme.productType || 'Mutual Fund',
          risk: scheme.riskLevel || 'Moderate',
          nav: scheme.nav ? `₹${scheme.nav}` : '₹0.00',
          returns: { '1Y': '+0.0%', '3Y': '+0.0%', '5Y': '+0.0%' },
          minSip: Number(scheme.minSip || 500),
          minLumpsum: Number(scheme.minInvestment || 1000),
          color: colors[index % colors.length],
        })));
      })
      .catch(err => console.error('Failed to load product schemes for transaction form', err));

    return () => { cancelled = true; };
  }, []);

  React.useEffect(() => {
    let cancelled = false;
    setBankLoading(true);
    setBankError('');
    setBank(null);

    apiFetch(`/investors/${investor.id}/bank-accounts`)
      .then(async res => {
        const data = await res.json().catch(() => null);
        if (!res.ok) throw new Error(data?.message || `HTTP ${res.status}`);
        if (cancelled) return;
        const nextBanks = Array.isArray(data) ? data : [];
        setBanks(nextBanks);
        setBank(nextBanks.find(isVerifiedBank) || nextBanks[0] || null);
      })
      .catch(err => {
        console.error('Failed to load investor bank accounts for transaction form', err);
        if (!cancelled) {
          setBanks([]);
          setBankError('Could not load verified bank account for this investor.');
        }
      })
      .finally(() => {
        if (!cancelled) setBankLoading(false);
      });

    return () => { cancelled = true; };
  }, [investor.id]);

  const handleConfirm = async () => {
    const validSipForm = !isSip || await trigger();
    if (!product || !bank || !isVerifiedBank(bank) || !amountValid || !validSipForm) return;

    setSubmitting(true);
    setSubmitError('');
    try {
      const payload = {
        investorId: investor.id,
        productSchemeId: product.id,
        type: isSip ? 'SIP' : 'LUMPSUM',
        transactionType: isSip ? 'SIP' : 'LUMPSUM_PURCHASE',
        amount: amountNum,
        paymentMode: isSip ? 'MANDATE' : 'BANK_TRANSFER',
        mandateMode: isSip ? 'AUTO_DEBIT' : bank.bankName,
        sipFrequency: isSip ? frequency : undefined,
        sipStartDate: isSip ? startDate : undefined,
        sipInstalments: isSip && instalments ? instalments : undefined,
      };

      const response = await apiFetch('/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      const result = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(result?.message || `Order creation failed (${response.status})`);
      }

      setDone(true);
    } catch (err: any) {
      setSubmitError(err?.message || 'Order creation failed. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const goToBankStep = async () => {
    if (isSip) {
      const isValid = await trigger(['amount', 'frequency', 'startDate', 'instalments']);
      if (!isValid) return;
    }
    if (!amountValid) return;
    setStep(3);
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
            <Row label="Investor"       value={investorName}                      />
            <Row label="Fund"           value={product?.name || ''}               />
            <Row label="Type"           value={txType === 'sip' ? `SIP · ${frequencyLabel(frequency)}` : 'Lumpsum'} />
            <Row label="Amount"         value={`₹${amountNum.toLocaleString('en-IN')}`} />
            {txType === 'sip' && <Row label="Start Date" value={formatDate(startDate)} />}
            {txType === 'sip' && instalments && <Row label="Instalments" value={String(instalments)} />}
            <Row label="Bank"           value={bankLabel(bank)}  />
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
        <h1 className="text-2xl font-semibold text-slate-800">{txType === 'sip' ? 'Start SIP' : 'New Investment'}</h1>
        <p className="text-slate-500 text-sm mt-1">
          Placing transaction for <span className="font-semibold text-slate-700">{investorName}</span>
        </p>
      </div>

      <StepBar current={step} />

      <AnimatePresence mode="wait">

        {/* ── Step 1: Select Fund ─────────────────────────────────────────── */}
        {step === 1 && (
          <motion.div key="s1" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
            <h2 className="font-semibold text-slate-800 mb-4">Select a Fund</h2>
            <div className="space-y-3">
              {products.map(p => (
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
                    onClick={() => { setTxType(t); resetField('amount'); }}
                    className={`px-5 py-2 text-sm font-semibold rounded-lg transition-all ${
                      txType === t ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500 hover:text-slate-700'
                    }`}
                  >
                    {t === 'sip' ? 'Start SIP' : 'Lumpsum'}
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
                  {...register('amount', {
                    setValueAs: parseAmountInput,
                    onChange: e => {
                      e.target.value = e.target.value.replace(/[^0-9,]/g, '');
                    },
                  })}
                  placeholder={`Min. ₹${minAmount.toLocaleString('en-IN')}`}
                  className={`w-full pl-8 pr-4 py-3 bg-slate-50 border rounded-xl text-sm font-mono font-semibold focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all ${
                    (isSip && errors.amount) || (!isSip && amountNum > 0 && !amountValid) ? 'border-red-300 bg-red-50' : 'border-slate-200'
                  }`}
                />
              </div>
              {isSip && errors.amount && (
                <p className="text-xs text-red-500 mt-1">{errors.amount.message}</p>
              )}
              {!isSip && amountNum > 0 && !amountValid && (
                <p className="text-xs text-red-500 mt-1">
                  Minimum lumpsum amount is ₹{minAmount.toLocaleString('en-IN')}
                </p>
              )}
              <p className="text-[11px] text-slate-400 mt-1">
                {txType === 'sip'
                  ? 'Amount will be auto-debited every month on the chosen SIP date.'
                  : 'One-time investment. Amount will be debited within 2 working days.'}
              </p>
            </div>

            {txType === 'sip' && (
              <div className="mb-6 grid grid-cols-1 md:grid-cols-3 gap-4">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Frequency</label>
                  <select
                    {...register('frequency')}
                    className={`w-full px-3.5 py-3 text-sm bg-slate-50 border rounded-xl outline-none focus:ring-2 focus:ring-blue-100 focus:border-blue-500 ${errors.frequency ? 'border-red-300 bg-red-50' : 'border-slate-200'}`}
                  >
                    <option value="MONTHLY">Monthly</option>
                    <option value="QUARTERLY">Quarterly</option>
                  </select>
                  {errors.frequency && <p className="text-xs text-red-500 mt-1">{errors.frequency.message}</p>}
                </div>
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Start Date</label>
                  <input
                    type="date"
                    min={new Date(Date.now() + 86400000).toISOString().slice(0, 10)}
                    {...register('startDate')}
                    className={`w-full px-3.5 py-3 text-sm bg-slate-50 border rounded-xl outline-none focus:ring-2 focus:ring-blue-100 focus:border-blue-500 ${errors.startDate ? 'border-red-300 bg-red-50' : 'border-slate-200'}`}
                  />
                  {errors.startDate && <p className="text-xs text-red-500 mt-1">{errors.startDate.message}</p>}
                </div>
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Instalments</label>
                  <input
                    type="number"
                    min={1}
                    {...register('instalments', { setValueAs: parseOptionalPositiveInt })}
                    placeholder="Optional"
                    className={`w-full px-3.5 py-3 text-sm bg-slate-50 border rounded-xl outline-none focus:ring-2 focus:ring-blue-100 focus:border-blue-500 ${errors.instalments ? 'border-red-300 bg-red-50' : 'border-slate-200'}`}
                  />
                  {errors.instalments && <p className="text-xs text-red-500 mt-1">{errors.instalments.message}</p>}
                </div>
              </div>
            )}

            <div className="flex items-center justify-between mt-6">
              <button onClick={() => setStep(1)}
                className="flex items-center gap-2 px-5 py-2.5 text-sm font-medium text-slate-600 border border-slate-200 rounded-xl hover:bg-slate-50 transition-colors">
                <ArrowLeft className="w-4 h-4" /> Back
              </button>
              <button
                onClick={goToBankStep}
                disabled={!amountValid || !sipFieldsValid}
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
              {bankLoading && (
                <div className="rounded-2xl border border-slate-200 bg-white p-5 text-sm text-slate-500">
                  Loading bank accounts...
                </div>
              )}
              {!bankLoading && bankError && (
                <div className="rounded-2xl border border-red-100 bg-red-50 p-5 text-sm font-medium text-red-700">
                  {bankError}
                </div>
              )}
              {!bankLoading && !bankError && BANKS.length === 0 && (
                <div className="rounded-2xl border border-amber-100 bg-amber-50 p-5 text-sm font-medium text-amber-800">
                  No verified bank account is available for this investor.
                </div>
              )}
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
                disabled={!bank || !isVerifiedBank(bank)}
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
              <Row label="Investor"    value={investorName} />
              <Row label="Fund"        value={product.name}  />
              <Row label="Category"    value={product.category} />
              <Row label="Type"        value={txType === 'sip' ? `SIP – ${frequencyLabel(frequency)}` : 'Lumpsum (One-time)'} />
              <Row label="Amount"
                value={`₹${amountNum.toLocaleString('en-IN')}${txType === 'sip' ? ' / month' : ''}`}
                valueClass="font-bold text-blue-700" />
              {txType === 'sip' && <Row label="Start Date" value={formatDate(startDate)} />}
              {txType === 'sip' && instalments && <Row label="Instalments" value={String(instalments)} />}
              <Row label="Bank"
                value={bankLabel(bank)} />
              <Row label="IFSC"        value={bank.ifscCode || 'Currently unavailable'} />
            </div>

            <div className="bg-[#0B1B3E]/5 border border-[#0B1B3E]/10 rounded-xl px-4 py-3 flex items-start gap-3 mb-6">
              <ShieldCheck className="w-4 h-4 text-[#0B1B3E] flex-shrink-0 mt-0.5" />
              <p className="text-xs text-slate-600 leading-relaxed">
                By confirming, you authorise Platizio to place this {txType === 'sip' ? 'SIP mandate' : 'investment order'} on behalf of{' '}
                <span className="font-semibold">{investorName}</span> in accordance with SEBI regulations and the investor's signed consent.
              </p>
            </div>
            {submitError && (
              <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700 flex gap-2">
                <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
                {submitError}
              </div>
            )}

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
