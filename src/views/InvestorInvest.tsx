import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Search, Loader2, AlertCircle, TrendingUp, X, CheckCircle2, ArrowRight, Wallet, ShieldAlert,
} from 'lucide-react';
import { apiFetch, BACKEND_ORIGIN } from '../config/api';

/**
 * Investor "Invest / Buy funds" page. Browses the live POA catalogue
 * (GET /investor/schemes), then places a lumpsum order (POST /investor/orders),
 * which is gated server-side on KYC COMPLETED + a verified bank. On success the
 * order is PENDING_INVESTOR_ACTION with a payment URL served by the backend.
 */

interface Scheme {
  id: string;
  schemeName?: string;
  amcName?: string;
  category?: string;
  externalIsin?: string;
}
interface OrderResult {
  id?: string;
  orderStatus?: string;
  amount?: number;
  externalOrderId?: string;
  investorActionUrl?: string;
}

const fmtMoney = (v?: number | null) =>
  typeof v === 'number' && Number.isFinite(v) ? `₹${v.toLocaleString('en-IN')}` : '—';

export default function InvestorInvest() {
  const navigate = useNavigate();
  const [schemes, setSchemes] = useState<Scheme[]>([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [selected, setSelected] = useState<Scheme | null>(null);
  const [amount, setAmount] = useState('5000');
  const [placing, setPlacing] = useState(false);
  const [orderError, setOrderError] = useState('');
  const [order, setOrder] = useState<OrderResult | null>(null);

  // LUMPSUM vs SIP. SIP fields start EMPTY (no defaulted compliance values).
  const [mode, setMode] = useState<'LUMPSUM' | 'SIP'>('LUMPSUM');
  const [sipFrequency, setSipFrequency] = useState('');     // '' | MONTHLY | WEEKLY
  const [sipStartDate, setSipStartDate] = useState('');
  const [sipInstalments, setSipInstalments] = useState('');

  // Bank setup (investor self-service → order-ready)
  const [showBank, setShowBank] = useState(false);
  const [bank, setBank] = useState({ accountHolderName: '', accountNumber: '', ifscCode: '', accountType: 'savings', bankName: '' });
  const [bankSaving, setBankSaving] = useState(false);
  const [bankError, setBankError] = useState('');
  const [bankDone, setBankDone] = useState(false);

  const openBank = () => { setBankDone(false); setBankError(''); setShowBank(true); };

  const addBank = async () => {
    if (!bank.accountHolderName.trim() || !bank.accountNumber.trim() || !bank.ifscCode.trim()) {
      setBankError('Fill account holder name, account number and IFSC.');
      return;
    }
    setBankSaving(true);
    setBankError('');
    try {
      const res = await apiFetch('/investor/bank-accounts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(bank),
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not add the bank account.');
      setBankDone(true);
      setOrderError('');
    } catch (e) {
      setBankError(e instanceof Error ? e.message : 'Could not add the bank account.');
    } finally {
      setBankSaving(false);
    }
  };

  const loadSchemes = useCallback(async (q: string) => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch(`/investor/schemes?size=30${q ? `&query=${encodeURIComponent(q)}` : ''}`);
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || `Unable to load funds (${res.status}).`);
      const list: Scheme[] = Array.isArray(body?.content) ? body.content : Array.isArray(body) ? body : [];
      setSchemes(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load funds.');
      setSchemes([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadSchemes(''); }, [loadSchemes]);

  const openInvest = (s: Scheme) => {
    setSelected(s);
    setAmount('5000');
    setOrderError('');
    setOrder(null);
    setMode('LUMPSUM');
    setSipFrequency('');
    setSipStartDate('');
    setSipInstalments('');
  };

  const placeOrder = async () => {
    if (!selected) return;
    const amt = Number(amount);
    if (!Number.isFinite(amt) || amt < 100) { setOrderError('Enter an amount of ₹100 or more.'); return; }

    // Build the order payload by mode. LUMPSUM keeps today's behaviour; SIP adds
    // frequency/start-date/instalments — all required, none defaulted.
    let payload: Record<string, unknown>;
    if (mode === 'SIP') {
      if (!sipFrequency) { setOrderError('Select a SIP frequency.'); return; }
      if (!sipStartDate) { setOrderError('Pick a SIP start date.'); return; }
      const inst = Number(sipInstalments);
      if (!Number.isInteger(inst) || inst < 1) { setOrderError('Enter the number of SIP instalments.'); return; }
      payload = {
        productSchemeId: selected.id,
        transactionType: 'SIP',
        amount: amt,
        paymentMode: 'UPI',
        sipFrequency,
        sipStartDate,
        sipInstalments: inst,
      };
    } else {
      payload = { productSchemeId: selected.id, transactionType: 'LUMPSUM_PURCHASE', amount: amt, paymentMode: 'UPI' };
    }

    setPlacing(true);
    setOrderError('');
    try {
      const res = await apiFetch('/investor/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        skipAuthRedirect: true,
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body as { message?: string } | null)?.message || 'Could not place the order.');
      setOrder(body as OrderResult);
    } catch (e) {
      setOrderError(e instanceof Error ? e.message : 'Could not place the order.');
    } finally {
      setPlacing(false);
    }
  };

  const paymentUrl = useMemo(
    () => (order?.investorActionUrl ? `${BACKEND_ORIGIN}${order.investorActionUrl}` : null),
    [order],
  );
  const kycRequired = /kyc/i.test(orderError);
  const bankRequired = /bank/i.test(orderError);

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-8">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Invest in mutual funds</h1>
          <p className="mt-1 text-sm text-slate-500">Browse funds and place a lumpsum order. KYC and a verified bank are required to invest.</p>
        </div>
      </div>

      {/* Readiness helper — KYC + bank are required to invest */}
      <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-blue-100 bg-blue-50/60 px-4 py-3">
        <ShieldAlert className="h-4 w-4 flex-shrink-0 text-blue-600" />
        <p className="min-w-[200px] flex-1 text-sm text-blue-900">New here? Complete KYC and add a bank account to start investing.</p>
        <button onClick={() => navigate('/investor/kyc')} className="rounded-lg border border-blue-200 bg-white px-3 py-1.5 text-xs font-semibold text-blue-700 hover:bg-blue-100">Complete KYC</button>
        <button onClick={openBank} className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700">Add bank account</button>
      </div>

      {/* Search */}
      <div className="relative max-w-md">
        <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
        <input value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && loadSchemes(query)}
          placeholder="Search funds (e.g. Aditya Birla, bluechip)…"
          className="w-full bg-white border border-slate-200 rounded-xl pl-10 pr-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
      </div>

      {loading ? (
        <div className="flex min-h-[40vh] items-center justify-center">
          <div className="text-center"><Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" /><p className="mt-3 text-sm text-slate-600">Loading funds…</p></div>
        </div>
      ) : error ? (
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <div className="flex items-start gap-2.5"><AlertCircle className="mt-0.5 h-5 w-5 text-red-500" /><div><p className="font-semibold">Couldn't load funds</p><p className="mt-1 text-sm">{error}</p><button onClick={() => loadSchemes(query)} className="mt-3 text-sm font-semibold text-red-600 hover:underline">Try again</button></div></div>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {schemes.map(s => (
            <div key={s.id} className="flex flex-col justify-between rounded-2xl border border-slate-100 bg-white p-5 shadow-sm">
              <div>
                <p className="text-sm font-semibold text-slate-800 leading-snug line-clamp-2">{s.schemeName || 'Fund'}</p>
                <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px]">
                  {s.amcName && <span className="text-slate-500">{s.amcName}</span>}
                  {s.category && <span className="rounded bg-slate-100 px-1.5 py-0.5 font-medium text-slate-500">{s.category}</span>}
                </div>
                {s.externalIsin && <p className="mt-1 font-mono text-[10px] text-slate-400">{s.externalIsin}</p>}
              </div>
              <button onClick={() => openInvest(s)}
                className="mt-4 flex items-center justify-center gap-1.5 rounded-xl bg-[#0B1B3E] py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066] transition-colors">
                <TrendingUp className="w-4 h-4" /> Invest
              </button>
            </div>
          ))}
          {schemes.length === 0 && <p className="text-sm text-slate-500">No funds found. Try a different search.</p>}
        </div>
      )}

      {/* Invest modal */}
      {selected && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Invest in</p>
                <p className="text-sm font-semibold text-slate-800 leading-snug">{selected.schemeName}</p>
              </div>
              <button onClick={() => setSelected(null)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100"><X className="h-4 w-4" /></button>
            </div>

            {order ? (
              <div className="mt-5 text-center">
                <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-100"><CheckCircle2 className="h-7 w-7 text-emerald-600" /></div>
                <p className="text-sm font-semibold text-slate-800">Order placed — {fmtMoney(order.amount)}</p>
                {/* Orders now create a transaction-2FA challenge: the investor must
                    approve with email-OTP + consent in the Approval Center before paying. */}
                <p className="mt-1 text-xs text-slate-500">Status: {order.orderStatus}. Approve this order in your Approval Center to continue.</p>
                <button onClick={() => { setSelected(null); navigate('/investor/approvals'); }}
                  className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white hover:bg-[#1A3066]">
                  Go to Approval Center <ArrowRight className="h-4 w-4" />
                </button>
                {paymentUrl && (
                  <a href={paymentUrl} target="_blank" rel="noopener noreferrer"
                    className="mt-2 flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50">
                    Proceed to payment <ArrowRight className="h-4 w-4" />
                  </a>
                )}
                <button onClick={() => { setSelected(null); navigate('/investor/dashboard'); }}
                  className="mt-2 w-full rounded-xl border border-slate-200 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50">
                  Back to dashboard
                </button>
              </div>
            ) : (
              <>
                {/* LUMPSUM vs SIP toggle */}
                <div className="mt-5 grid grid-cols-2 gap-2 rounded-xl bg-slate-100 p-1">
                  {(['LUMPSUM', 'SIP'] as const).map(m => (
                    <button key={m} type="button"
                      onClick={() => { setMode(m); setOrderError(''); }}
                      className={`rounded-lg py-2 text-xs font-semibold transition-colors ${mode === m ? 'bg-white text-[#0B1B3E] shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}>
                      {m === 'LUMPSUM' ? 'One-time (Lumpsum)' : 'SIP'}
                    </button>
                  ))}
                </div>

                <div className="mt-4">
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">{mode === 'SIP' ? 'SIP amount (₹)' : 'Amount (₹)'}</label>
                  <div className="relative">
                    <Wallet className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                    <input type="number" min={100} value={amount}
                      onChange={e => { setAmount(e.target.value); setOrderError(''); }}
                      className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
                  </div>
                  <div className="mt-2 flex gap-2">
                    {[1000, 5000, 25000].map(a => (
                      <button key={a} onClick={() => setAmount(String(a))} className="rounded-lg bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600 hover:bg-slate-200">₹{a.toLocaleString('en-IN')}</button>
                    ))}
                  </div>
                </div>

                {/* SIP details — empty defaults, all required (no defaulted values) */}
                {mode === 'SIP' && (
                  <div className="mt-4 space-y-3">
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">SIP frequency</label>
                      <select value={sipFrequency}
                        onChange={e => { setSipFrequency(e.target.value); setOrderError(''); }}
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl px-3.5 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none">
                        <option value="" disabled>Select frequency</option>
                        <option value="MONTHLY">Monthly</option>
                        <option value="WEEKLY">Weekly</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">SIP start date</label>
                      <input type="date" value={sipStartDate}
                        onChange={e => { setSipStartDate(e.target.value); setOrderError(''); }}
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl px-3.5 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Number of instalments</label>
                      <input type="number" min={1} step={1} value={sipInstalments}
                        onChange={e => { setSipInstalments(e.target.value); setOrderError(''); }}
                        placeholder="e.g. 12"
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl px-3.5 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
                    </div>
                  </div>
                )}

                {orderError && (
                  <div className="mt-4 flex items-start gap-2 rounded-xl border border-amber-100 bg-amber-50 px-3 py-2.5">
                    <ShieldAlert className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600" />
                    <div className="text-xs text-amber-800">
                      <p>{orderError}</p>
                      {kycRequired && (
                        <button onClick={() => navigate('/investor/kyc')} className="mt-1 font-semibold underline">Complete KYC →</button>
                      )}
                      {bankRequired && (
                        <button onClick={openBank} className="mt-1 ml-3 font-semibold underline">Add bank account →</button>
                      )}
                    </div>
                  </div>
                )}

                <button onClick={placeOrder} disabled={placing}
                  className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white hover:bg-[#1A3066] disabled:opacity-60">
                  {placing ? (<><Loader2 className="h-4 w-4 animate-spin" /> Placing order…</>) : (<><TrendingUp className="h-4 w-4" /> {mode === 'SIP' ? `Start SIP of ${fmtMoney(Number(amount))}` : `Buy for ${fmtMoney(Number(amount))}`}</>)}
                </button>
                <p className="mt-2 text-center text-[11px] text-slate-400">Requires completed KYC and a verified bank account.</p>
              </>
            )}
          </div>
        </div>
      )}

      {/* Bank-add modal (self-service → order-ready) */}
      {showBank && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Bank account</p>
                <p className="text-sm font-semibold text-slate-800">Add a bank to invest</p>
              </div>
              <button onClick={() => setShowBank(false)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100"><X className="h-4 w-4" /></button>
            </div>
            {bankDone ? (
              <div className="mt-5 text-center">
                <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-100"><CheckCircle2 className="h-7 w-7 text-emerald-600" /></div>
                <p className="text-sm font-semibold text-slate-800">Bank account verified</p>
                <p className="mt-1 text-xs text-slate-500">You can now place your order.</p>
                <button onClick={() => setShowBank(false)} className="mt-5 w-full rounded-xl bg-[#0B1B3E] py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066]">Done</button>
              </div>
            ) : (
              <div className="mt-5 space-y-3">
                {([
                  { k: 'accountHolderName', label: 'Account holder name', ph: 'As per bank records' },
                  { k: 'accountNumber', label: 'Account number', ph: 'e.g. 50100123456789' },
                  { k: 'ifscCode', label: 'IFSC code', ph: 'e.g. HDFC0000123', upper: true },
                  { k: 'bankName', label: 'Bank name (optional)', ph: 'e.g. HDFC Bank' },
                ] as { k: keyof typeof bank; label: string; ph: string; upper?: boolean }[]).map(f => (
                  <div key={f.k}>
                    <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">{f.label}</label>
                    <input
                      value={bank[f.k]}
                      onChange={e => { const v = f.upper ? e.target.value.toUpperCase() : e.target.value; setBank(b => ({ ...b, [f.k]: v })); setBankError(''); }}
                      placeholder={f.ph}
                      className={`w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 ${f.upper ? 'uppercase' : ''}`}
                    />
                  </div>
                ))}
                <div>
                  <label className="mb-1 block text-xs font-bold uppercase tracking-wider text-slate-500">Account type</label>
                  <select value={bank.accountType} onChange={e => setBank(b => ({ ...b, accountType: e.target.value }))}
                    className="w-full rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100">
                    <option value="savings">Savings</option>
                    <option value="current">Current</option>
                  </select>
                </div>
                {bankError && <p className="text-xs text-red-600">{bankError}</p>}
                <button onClick={addBank} disabled={bankSaving}
                  className="mt-1 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white hover:bg-[#1A3066] disabled:opacity-60">
                  {bankSaving ? (<><Loader2 className="h-4 w-4 animate-spin" /> Adding…</>) : 'Add & verify bank'}
                </button>
                <p className="text-center text-[11px] text-slate-400">Sandbox: the bank is verified instantly so you can transact.</p>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
