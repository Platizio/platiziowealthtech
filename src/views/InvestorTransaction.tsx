import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  ArrowLeft, ArrowRight, CheckCircle2, Building2,
  CreditCard, TrendingUp, ShieldCheck, Clock, Wallet, AlertCircle, Search,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import InvestorActionLink from '../components/InvestorActionLink';
import Pagination from '../components/Pagination';
import { useDebounce } from '../hooks/useDebounce';
import type { TransactionOrder } from '../types/order';
import { formatOrderStatusLabel, normalizeOrderStatus } from '../utils/investorAction';
import { getPageContent, getPageMeta } from '../utils/pagination';
import { formatDate } from '../utils/formatDate';
import { isPersistedSchemeId } from '../utils/productSchemeKey';
import {
  formatSchemeDisplayName,
  isTransactionReadyScheme,
  parseSchemeMetadata,
  readSchemeMinSip,
} from '../utils/orderableScheme';

const firstMetaValue = (meta: any, keys: string[]) => {
  for (const key of keys) {
    const value = meta?.[key];
    if (value !== undefined && value !== null && value !== '') return value;
  }
  return undefined;
};

const formatSchemeNav = (rawNav: unknown): string => {
  const num = typeof rawNav === 'number' ? rawNav : Number(String(rawNav ?? '').replace(/[^0-9.]/g, ''));
  // DF-07: when NAV is unavailable, show an em dash rather than a misleading ₹0.00.
  return Number.isFinite(num) && num > 0
    ? `₹${num.toLocaleString('en-IN', { maximumFractionDigits: 2, minimumFractionDigits: 2 })}`
    : '—';
};

// Allotment helpers: units up to 3 decimals, NAV as ₹ with 2–4 decimals.
const formatUnits = (value: number): string =>
  value.toLocaleString('en-IN', { maximumFractionDigits: 3 });

const formatAllotmentNav = (value: number): string =>
  `₹${value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`;

const formatSchemeReturn = (rawReturn: unknown): string => {
  const num = typeof rawReturn === 'number' ? rawReturn : Number(String(rawReturn ?? '').replace(/[^0-9.-]/g, ''));
  if (!Number.isFinite(num)) return '+0.0%';
  return `${num >= 0 ? '+' : ''}${num.toFixed(1)}%`;
};

/** FP sandbox: purchase review succeeds when amount ends in 0; ending in 1 simulates failure. */
const sandboxAmountLastDigit = (amount: number) => Math.trunc(amount) % 10;

const isSandboxLumpsumAmountValid = (amount: number) => sandboxAmountLastDigit(amount) === 0;

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
  productSchemeId?: string;
  externalSchemeCode?: string;
  externalIsin?: string;
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
  frequency: z.enum(['MONTHLY', 'QUARTERLY'], { message: 'Select a SIP frequency' }),
  startDate: z.string().refine(d => new Date(d) > new Date(), 'Start date must be in the future'),
  installmentDay: z.number({ message: 'Select a SIP date (1–28)' })
    .int()
    .min(1, 'SIP date must be between 1 and 28')
    .max(28, 'SIP date must be between 1 and 28'),
  instalments: z.number().int().positive().optional(),
});

type SipFormValues = z.infer<typeof sipSchema>;

const frequencyLabel = (value: string | undefined) =>
  value === 'MONTHLY' ? 'Monthly' : value === 'QUARTERLY' ? 'Quarterly' : '—';

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

const mapSchemeToProduct = (scheme: any, index: number): Product | null => {
  if (!isTransactionReadyScheme(scheme)) return null;

  const meta = parseSchemeMetadata(scheme.metadataJson);
  const rawNav = firstMetaValue(meta, ['nav', 'current_nav', 'last_nav']);
  const ret = (meta?.returns || {}) as Record<string, unknown>;
  const colors: ProductColor[] = ['blue', 'violet', 'indigo', 'rose', 'emerald'];
  const fundCategory = readText(meta.category, meta.fund_category, scheme.category, scheme.productType) || 'Mutual Fund';

  return {
    id: scheme.id || scheme.externalSchemeCode || scheme.externalIsin || `scheme-${index}`,
    productSchemeId: isPersistedSchemeId(scheme.id) ? scheme.id : undefined,
    externalSchemeCode: readText(scheme.externalSchemeCode, scheme.external_scheme_code),
    externalIsin: readText(scheme.externalIsin, scheme.external_isin),
    name: formatSchemeDisplayName(scheme),
    category: fundCategory,
    risk: scheme.riskLevel || 'Moderate',
    nav: formatSchemeNav(rawNav),
    returns: {
      '1Y': formatSchemeReturn(ret['1y'] ?? ret.one_year),
      '3Y': formatSchemeReturn(ret['3y'] ?? ret.three_year),
      '5Y': formatSchemeReturn(ret['5y'] ?? ret.five_year),
    },
    minSip: readSchemeMinSip(scheme, 500),
    minLumpsum: Number(scheme.minInvestment || scheme.minLumpsum || 1000),
    color: colors[index % colors.length],
  };
};

const readText = (...values: unknown[]) => {
  for (const value of values) {
    if (value === undefined || value === null) continue;
    const text = String(value).trim();
    if (text) return text;
  }
  return '';
};

const FUND_PAGE_SIZE_DEFAULT = 12;

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
  const [products, setProducts] = useState<Product[]>([]);
  const [productsLoading, setProductsLoading] = useState(true);
  const [productsError, setProductsError] = useState('');
  const [fundSearch, setFundSearch] = useState('');
  const debouncedFundSearch = useDebounce(fundSearch, 300);
  const [fundPage, setFundPage] = useState(0);
  const [fundPageSize, setFundPageSize] = useState(FUND_PAGE_SIZE_DEFAULT);
  const [fundTotalPages, setFundTotalPages] = useState(1);
  const [fundTotalElements, setFundTotalElements] = useState(0);
  const [product,  setProduct]  = useState<Product | null>(null);
  const [txType,   setTxType]   = useState<'sip' | 'lumpsum'>('sip');
  const [banks, setBanks] = useState<BankAccount[]>([]);
  const [bank, setBank] = useState<BankAccount | null>(null);
  const [bankLoading, setBankLoading] = useState(false);
  const [bankError, setBankError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done,     setDone]     = useState(false);
  const [submitError, setSubmitError] = useState('');
  const [createdOrder, setCreatedOrder] = useState<TransactionOrder | null>(null);
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
      // DF-09: no pre-selected frequency — the user must actively choose.
      frequency: '' as unknown as SipFormValues['frequency'],
      startDate: '',
    },
  });

  const amountNum = parseAmountInput(watch('amount'));
  const frequency = watch('frequency'); // DF-09: no MONTHLY fallback
  const startDate = watch('startDate') || '';
  const instalments = watch('instalments');
  const installmentDay = watch('installmentDay');

  // Distributor-initiated 2FA: ask the investor to approve this order with an OTP (Phase-2).
  const [approvalReq, setApprovalReq] = useState<{ challengeId?: string; maskedDestination?: string; status?: string } | null>(null);
  const [requestingApproval, setRequestingApproval] = useState(false);
  const [approvalReqError, setApprovalReqError] = useState('');
  const minAmount = product ? (txType === 'sip' ? product.minSip : product.minLumpsum) : 0;
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
    setFundPage(0);
  }, [debouncedFundSearch]);

  React.useEffect(() => {
    let cancelled = false;

    const loadOrderableSchemes = async () => {
      setProductsLoading(true);
      setProductsError('');

      const fetchOrderableSchemes = async () => {
        // POA catalogue: GET /v2/mf_scheme_plans/cybrillapoa via Platizio backend (never browser → Cybrilla).
        const params = new URLSearchParams({
          page: String(fundPage),
          size: String(fundPageSize),
          active: 'true',
        });
        if (debouncedFundSearch.trim()) {
          params.set('query', debouncedFundSearch.trim());
        }
        const response = await apiFetch(`/products/schemes/page?${params}`);
        const payload = await response.json().catch(() => null);
        if (!response.ok) {
          throw new Error(payload?.message || `Unable to load funds (${response.status})`);
        }
        const content = getPageContent(payload);
        const meta = getPageMeta(payload, content.length);
        const colorOffset = fundPage * fundPageSize;
        const mapped = content
          .map((scheme: any, index: number) => mapSchemeToProduct(scheme, colorOffset + index))
          .filter((entry): entry is Product => entry !== null);
        return { mapped, meta };
      };

      try {
        const { mapped, meta } = await fetchOrderableSchemes();

        if (cancelled) return;
        setProducts(mapped);
        setFundTotalPages(meta.totalPages);
        setFundTotalElements(meta.totalElements);
        setProduct(prev => (prev && mapped.some(item => item.id === prev.id) ? prev : null));
        if (mapped.length === 0) {
          setProductsError(
            debouncedFundSearch.trim()
              ? 'No funds match your search. Try a different name, AMC, or scheme code.'
              : 'No orderable funds found. Open Ledger and refresh the Platizio POA catalogue, then try again.',
          );
        }
      } catch (err: any) {
        console.error('Failed to load orderable product schemes for transaction form', err);
        if (!cancelled) {
          setProducts([]);
          setFundTotalPages(1);
          setFundTotalElements(0);
          setProduct(null);
          setProductsError(err?.message || 'Could not load funds for this transaction.');
        }
      } finally {
        if (!cancelled) setProductsLoading(false);
      }
    };

    loadOrderableSchemes();
    return () => { cancelled = true; };
  }, [debouncedFundSearch, fundPage, fundPageSize]);

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
    if (!product || (!product.productSchemeId && !product.externalSchemeCode && !product.externalIsin)) {
      setSubmitError('Select a synced fund from the catalogue before placing an order.');
      return;
    }
    if (!bank || !isVerifiedBank(bank) || !amountValid || !validSipForm) return;

    if (!isSip && !isSandboxLumpsumAmountValid(amountNum)) {
      const lastDigit = sandboxAmountLastDigit(amountNum);
      const message = `Platizio sandbox rejects lumpsum amounts ending in ${lastDigit}. Use ₹5000, ₹10000, etc. (last digit must be 0).`;
      console.warn('[Platizio] lumpsum_order_blocked_sandbox_amount', { amount: amountNum, lastDigit });
      setSubmitError(message);
      return;
    }

    setSubmitting(true);
    setSubmitError('');
    try {
      console.group('[Platizio] lumpsum_order_create');
      console.log('request', {
        investorId: investor.id,
        productSchemeId: product.productSchemeId,
        externalSchemeCode: product.externalSchemeCode,
        externalIsin: product.externalIsin,
        amount: amountNum,
        paymentMode: isSip ? 'MANDATE' : 'UPI',
        transactionType: isSip ? 'SIP' : 'LUMPSUM_PURCHASE',
      });
      const payload = {
        investorId: investor.id,
        productSchemeId: product.productSchemeId,
        externalSchemeCode: product.externalSchemeCode || undefined,
        externalIsin: product.externalIsin || undefined,
        type: isSip ? 'SIP' : 'LUMPSUM',
        transactionType: isSip ? 'SIP' : 'LUMPSUM_PURCHASE',
        amount: amountNum,
        paymentMode: isSip ? 'MANDATE' : 'UPI',
        mandateMode: isSip ? 'AUTO_DEBIT' : undefined,
        sipFrequency: isSip ? frequency : undefined,
        sipStartDate: isSip ? startDate : undefined,
        sipInstalments: isSip && instalments ? instalments : undefined,
        installmentDay: isSip ? installmentDay : undefined,
      };

      const response = await apiFetch('/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      const result = await response.json().catch(() => null);
      console.log('response', {
        ok: response.ok,
        status: response.status,
        orderId: result?.id,
        orderStatus: result?.orderStatus,
        externalOrderId: result?.externalOrderId,
        failureReason: result?.failureReason,
        investorActionUrl: result?.investorActionUrl,
      });
      console.groupEnd();
      if (!response.ok) {
        const baseMessage = result?.message || `Order creation failed (${response.status})`;
        if (response.status === 404 && /product scheme not found/i.test(baseMessage)) {
          throw new Error(
            'Selected fund is not in the local catalogue. Go back, reload funds, pick a synced scheme, then place the order again.',
          );
        }
        // 503 = provider unreachable; the backend has saved the order for retry.
        // 502 = provider error/rate-limit. Both are retryable from the UI.
        if (response.status === 503 || response.status === 502) {
          throw new Error(`${baseMessage} You can retry placing this order in a moment.`);
        }
        throw new Error(baseMessage);
      }

      const order = (result || null) as TransactionOrder;
      if (!isSip && normalizeOrderStatus(order?.orderStatus) === 'FAILED') {
        console.warn('[Platizio] lumpsum_order_fp_review_failed', {
          orderId: order?.id,
          externalOrderId: order?.externalOrderId,
          failureReason: order?.failureReason,
          tip: 'Use amount ending in 0 (e.g. ₹5000). Open investor-action on :8081 for live FP debug.',
        });
      }
      setCreatedOrder(order);
      setDone(true);
    } catch (err: any) {
      console.error('[Platizio] lumpsum_order_create_error', err);
      console.groupEnd();
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

  // Distributor asks the investor to authorize this order with a 2FA OTP. The
  // distributor never sees the code — the investor enters it in their portal.
  const requestInvestorApproval = async () => {
    if (!createdOrder?.id) return;
    setRequestingApproval(true);
    setApprovalReqError('');
    try {
      const res = await apiFetch(`/orders/${createdOrder.id}/request-investor-approval`, { method: 'POST' });
      const data: any = await res.json().catch(() => null);
      if (!res.ok) throw new Error(data?.message || 'Could not request investor approval.');
      setApprovalReq({ challengeId: data?.challengeId, maskedDestination: data?.maskedDestination, status: data?.status });
    } catch (e) {
      setApprovalReqError(e instanceof Error ? e.message : 'Could not request investor approval.');
    } finally {
      setRequestingApproval(false);
    }
  };

  // ── Success screen ─────────────────────────────────────────────────────────
  if (done) {
    const orderFailed = normalizeOrderStatus(createdOrder?.orderStatus) === 'FAILED';
    return (
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        className="p-8 max-w-xl mx-auto text-center"
      >
        <div className="bg-white rounded-3xl shadow-sm border border-slate-200 p-10">
          <div className={`w-20 h-20 rounded-full flex items-center justify-center mx-auto mb-6 ${orderFailed ? 'bg-red-100' : 'bg-green-100'}`}>
            {orderFailed ? (
              <AlertCircle className="w-10 h-10 text-red-500" />
            ) : (
              <CheckCircle2 className="w-10 h-10 text-green-500" />
            )}
          </div>
          <h1 className="text-2xl font-bold text-slate-800 mb-2">
            {orderFailed ? 'Order Rejected by Provider' : 'Order Created'}
          </h1>
          <p className="text-slate-500 text-sm mb-6">
            {orderFailed ? (
              <>
                Platizio rejected this purchase during review.
                {createdOrder?.failureReason ? (
                  <> {createdOrder.failureReason}</>
                ) : (
                  <> Use investor <span className="font-semibold text-slate-700">Anita Verma</span>, bank account ending in <span className="font-semibold text-slate-700">1193</span>, and amount ending in <span className="font-semibold text-slate-700">0</span> (e.g. ₹5000), then place a new order.</>
                )}
              </>
            ) : (
              <>
                The {txType === 'sip' ? 'SIP' : 'lumpsum'} order was created. Share the investor link below so the investor can confirm and pay.
                {txType === 'lumpsum' && (
                  <> Sandbox tip: use amount ending in <span className="font-semibold text-slate-700">0</span> (e.g. ₹5000) for a successful payment, or <span className="font-semibold text-slate-700">1</span> (e.g. ₹5001) to simulate failure.</>
                )}
              </>
            )}
          </p>

          <div className="bg-slate-50 rounded-2xl p-5 text-left space-y-3 mb-6">
            <Row label="Order ID"       value={createdOrder?.id || '—'}              mono />
            <Row label="Investor"       value={investorName}                      />
            <Row label="Fund"           value={product?.name || ''}               />
            <Row label="Type"           value={txType === 'sip' ? `SIP · ${frequencyLabel(frequency)}` : 'Lumpsum'} />
            <Row label="Amount"         value={`₹${amountNum.toLocaleString('en-IN')}`} />
            {/* Contract-note / allotment fields — only after allotment, when present. */}
            {createdOrder?.units != null && (
              <Row label="Units allotted" value={formatUnits(createdOrder.units)} mono />
            )}
            {createdOrder?.allotmentNav != null && (
              <Row label="NAV (allotment price)" value={formatAllotmentNav(createdOrder.allotmentNav)} mono />
            )}
            {createdOrder?.allotmentDate && (
              <Row label="Allotment date" value={formatDate(createdOrder.allotmentDate)} />
            )}
            {createdOrder?.folioNumber && (
              <Row label="Folio number" value={createdOrder.folioNumber} mono />
            )}
            {createdOrder?.stampDuty != null && (
              <Row label="Stamp duty" value={`₹${createdOrder.stampDuty.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`} />
            )}
            {createdOrder?.netInvested != null && (
              <Row label="Net invested" value={`₹${createdOrder.netInvested.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`} />
            )}
            {txType === 'sip' && <Row label="Start Date" value={formatDate(startDate)} />}
            {txType === 'sip' && installmentDay && <Row label="SIP date" value={`Day ${installmentDay} of each ${frequency === 'QUARTERLY' ? 'quarter' : 'month'}`} />}
            {txType === 'sip' && instalments && <Row label="Instalments" value={String(instalments)} />}
            <Row label="Bank"           value={bankLabel(bank)}  />
            <Row label="Status"         value={formatOrderStatusLabel(createdOrder?.orderStatus)}
              valueClass={`inline-flex items-center gap-1.5 font-semibold ${orderFailed ? 'text-red-700' : 'text-amber-700'}`}>
              {orderFailed ? <AlertCircle className="w-3.5 h-3.5" /> : <Clock className="w-3.5 h-3.5" />}
            </Row>
            {createdOrder?.failureReason && (
              <Row label="Reason" value={createdOrder.failureReason} />
            )}
          </div>

          <div className="mb-8 text-left">
            <InvestorActionLink
              orderId={createdOrder?.id}
              orderStatus={createdOrder?.orderStatus}
              investorActionUrl={createdOrder?.investorActionUrl}
            />
          </div>

          {!orderFailed && (
            <div className="mb-8 text-left rounded-2xl border border-slate-200 p-4">
              <p className="text-sm font-semibold text-slate-800">Investor 2FA approval</p>
              <p className="mt-1 text-xs text-slate-500">
                Ask the investor to authorize this order with a one-time passcode in their portal.
                For security, the passcode is never shown to the distributor.
              </p>
              {approvalReq ? (
                <p className="mt-2 text-xs font-medium text-emerald-700">
                  Approval requested — the investor can now enter the code sent to{' '}
                  {approvalReq.maskedDestination || 'their registered contact'} (status: {approvalReq.status || 'PENDING'}).
                </p>
              ) : (
                <button
                  type="button"
                  onClick={requestInvestorApproval}
                  disabled={requestingApproval}
                  className="mt-3 rounded-xl bg-[#0B1B3E] px-4 py-2 text-xs font-semibold text-white hover:bg-[#1A3066] disabled:opacity-50"
                >
                  {requestingApproval ? 'Requesting…' : 'Request investor approval (2FA)'}
                </button>
              )}
              {approvalReqError && <p className="mt-2 text-xs text-red-600">{approvalReqError}</p>}
            </div>
          )}

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

      {/*
        MVP-B8: the wizard flashed blank for ~300 ms on every Next because the
        per-step motion.divs had `initial={{ opacity: 0, … }}` and `exit={{
        opacity: 0, … }}` while AnimatePresence ran in `mode="wait"`. The
        outgoing step faded to opacity 0, unmounted, and only THEN did the new
        step mount and start its own fade-from-0 — so there were a few hundred
        milliseconds with nothing on screen between them. Fix: the steps now
        animate ONLY on the X axis (slide left/right) at constant opacity, so
        content is visible the entire transition. `mode="wait"` is preserved
        so we don't stack two full-height steps in the layout simultaneously.
      */}
      <AnimatePresence mode="wait">

        {/* ── Step 1: Select Fund ─────────────────────────────────────────── */}
        {step === 1 && (
          <motion.div key="s1" initial={{ x: 20 }} animate={{ x: 0 }} exit={{ x: -20 }} transition={{ duration: 0.22 }}>
            <h2 className="font-semibold text-slate-800 mb-4">Select a Fund</h2>
            <p className="text-xs text-slate-500 mb-4">
              Browse the Platizio POA catalogue — only synced, orderable schemes can be selected.
            </p>

            <div className="relative mb-4">
              <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
              <input
                type="text"
                value={fundSearch}
                onChange={e => setFundSearch(e.target.value)}
                placeholder="Search by fund name, AMC, or scheme code…"
                className="w-full pl-9 pr-4 py-2.5 text-sm bg-white border border-slate-200 rounded-xl focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
              />
            </div>

            {productsLoading && (
              <p className="text-sm text-slate-500 mb-4">Loading orderable funds…</p>
            )}
            {!productsLoading && productsError && (
              <div className="mb-4 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" />
                <span>{productsError}</span>
              </div>
            )}
            <div className="space-y-3">
              {!productsLoading && products.map((p, index) => (
                <button
                  key={p.id || `${p.name}-${index}`}
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

            {!productsLoading && fundTotalElements > 0 && (
              <div className="mt-4 rounded-2xl border border-slate-200 bg-white shadow-sm">
                <Pagination
                  page={fundPage}
                  size={fundPageSize}
                  totalPages={fundTotalPages}
                  totalElements={fundTotalElements}
                  onPageChange={setFundPage}
                  onSizeChange={nextSize => {
                    setFundPageSize(nextSize);
                    setFundPage(0);
                  }}
                />
              </div>
            )}

            <div className="flex justify-end mt-6">
              <button
                onClick={() => setStep(2)}
                disabled={productsLoading || !product || products.length === 0}
                className="flex items-center gap-2 px-6 py-3 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-50 disabled:cursor-not-allowed shadow-lg"
              >
                Next <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          </motion.div>
        )}

        {/* ── Step 2: Amount ──────────────────────────────────────────────── */}
        {step === 2 && product && (
          <motion.div key="s2" initial={{ x: 20 }} animate={{ x: 0 }} exit={{ x: -20 }} transition={{ duration: 0.22 }}>
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
              {!isSip && amountNum > 0 && amountValid && !isSandboxLumpsumAmountValid(amountNum) && (
                <p className="text-xs text-amber-700 mt-2 font-medium">
                  Sandbox tip: lumpsum amount must end in <span className="font-bold">0</span> (e.g. ₹5000). Ending in 1 forces Platizio review failure.
                </p>
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
              <div className="mb-6 grid grid-cols-1 md:grid-cols-4 gap-4">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Frequency</label>
                  <select
                    {...register('frequency')}
                    className={`w-full px-3.5 py-3 text-sm bg-slate-50 border rounded-xl outline-none focus:ring-2 focus:ring-blue-100 focus:border-blue-500 ${errors.frequency ? 'border-red-300 bg-red-50' : 'border-slate-200'}`}
                  >
                    <option value="" disabled>Select frequency</option>
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
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">SIP date (day)</label>
                  <input
                    type="number"
                    min={1}
                    max={28}
                    placeholder="1–28"
                    {...register('installmentDay', { setValueAs: parseOptionalPositiveInt })}
                    className={`w-full px-3.5 py-3 text-sm bg-slate-50 border rounded-xl outline-none focus:ring-2 focus:ring-blue-100 focus:border-blue-500 ${errors.installmentDay ? 'border-red-300 bg-red-50' : 'border-slate-200'}`}
                  />
                  {errors.installmentDay && <p className="text-xs text-red-500 mt-1">{errors.installmentDay.message}</p>}
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
          <motion.div key="s3" initial={{ x: 20 }} animate={{ x: 0 }} exit={{ x: -20 }} transition={{ duration: 0.22 }}>
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
          <motion.div key="s4" initial={{ x: 20 }} animate={{ x: 0 }} exit={{ x: -20 }} transition={{ duration: 0.22 }}>
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
