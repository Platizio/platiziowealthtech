import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import {
  ArrowLeft, LogOut, Sparkles, TrendingUp, TrendingDown, Wallet,
  Activity, Percent, Loader2, AlertCircle, ShieldCheck, ArrowUpRight, ArrowDownRight,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { apiFetch } from '../config/api';
import type { InvestorUser } from '../types/investorAuth';

/**
 * A separate, immersive "luxe" investor dashboard (route /investor/luxe). It reads
 * the SAME backend endpoint and field shape as the standard InvestorDashboard, but
 * renders a premium dark experience — animated aurora, count-up numbers, staggered
 * glass cards and animated charts. The standard dashboard is intentionally left
 * untouched; this is purely additive.
 */

// ── Field shape (same DTO as InvestorDashboard) ──────────────────────────────
interface Holding {
  schemeName?: string; scheme?: string; amcName?: string;
  folio?: string; sipName?: string | null;
  units?: number | null; latestNav?: number | null; navAsOf?: string | null;
  invested?: number | null; currentValue?: number | null;
  oneDayReturn?: number | null; percentReturn?: number | null;
  xirr?: number | null; dataQuality?: string;
}
interface Totals {
  totalInvested?: number | null; totalCurrentValue?: number | null;
  totalReturn?: number | null; totalReturnPercent?: number | null;
  totalOneDayReturn?: number | null; portfolioXirr?: number | null;
}
interface Payload { holdings?: Holding[]; totals?: Totals; }

// Luxe accent palette for allocation.
const LUXE = ['#E9C46A', '#60A5FA', '#A78BFA', '#34D399', '#22D3EE', '#F472B6', '#FB923C', '#94A3B8'];

const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

const fmtMoney = (v?: number | null, compact = false): string => {
  if (!isNum(v)) return '—';
  if (compact) {
    const a = Math.abs(v);
    if (a >= 1e7) return `₹${(v / 1e7).toFixed(2)} Cr`;
    if (a >= 1e5) return `₹${(v / 1e5).toFixed(2)} L`;
  }
  return `₹${v.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
};
const fmtPct = (v?: number | null): string => (isNum(v) ? `${v > 0 ? '+' : ''}${v.toFixed(2)}%` : '—');
const fmtNav = (v?: number | null): string =>
  isNum(v) ? `₹${v.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}` : '—';
const fmtUnits = (v?: number | null): string =>
  isNum(v) ? v.toLocaleString('en-IN', { maximumFractionDigits: 4 }) : '—';
const fmtDate = (v?: string | null): string => {
  if (!v) return '';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
};
const usable = (h: Holding) => String(h.dataQuality || 'OK').toUpperCase() === 'OK';
const stale = (h: Holding) => String(h.dataQuality || 'OK').toUpperCase() === 'STALE';

// ── Count-up hook (easeOutExpo) ──────────────────────────────────────────────
function useCountUp(target: number | null | undefined, duration = 1400, deps: unknown[] = []) {
  const [val, setVal] = useState(0);
  const raf = useRef(0);
  useEffect(() => {
    if (!isNum(target)) { setVal(0); return; }
    const to = target as number;
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / duration);
      const eased = t === 1 ? 1 : 1 - Math.pow(2, -10 * t); // easeOutExpo
      setVal(to * eased);
      if (t < 1) raf.current = requestAnimationFrame(tick);
    };
    raf.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target, duration, ...deps]);
  return val;
}

function AnimatedMoney({ value, compact, className }: { value?: number | null; compact?: boolean; className?: string }) {
  const v = useCountUp(value);
  return <span className={className}>{isNum(value) ? fmtMoney(v, compact) : '—'}</span>;
}
function AnimatedPct({ value, className }: { value?: number | null; className?: string }) {
  const v = useCountUp(value);
  return <span className={className}>{isNum(value) ? `${v > 0 ? '+' : ''}${v.toFixed(2)}%` : '—'}</span>;
}

const card = 'rounded-3xl border border-white/10 bg-white/[0.045] backdrop-blur-2xl shadow-[0_10px_50px_-12px_rgba(0,0,0,0.6)]';

export default function InvestorDashboardLuxe({
  investor, onSignOut,
}: { investor: InvestorUser; onSignOut: () => void }) {
  const navigate = useNavigate();
  const [data, setData] = useState<Payload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const res = await apiFetch('/investor/dashboard');
      const body = (await res.json().catch(() => null)) as Payload | null;
      if (!res.ok) throw new Error((body as unknown as { message?: string })?.message || 'Could not load your portfolio.');
      setData(body || { holdings: [], totals: {} });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load your portfolio.');
    } finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const holdings = useMemo(() => (data?.holdings ?? []).slice().sort(
    (a, b) => (b.currentValue ?? -1) - (a.currentValue ?? -1)), [data]);
  const totals = data?.totals ?? {};
  const alloc = useMemo(() =>
    holdings.filter(h => usable(h) && isNum(h.currentValue))
      .map((h, i) => ({ name: h.schemeName || h.scheme || 'Fund', value: h.currentValue as number, color: LUXE[i % LUXE.length] })),
    [holdings]);
  const allocTotal = alloc.reduce((s, a) => s + a.value, 0);

  const totalReturn = totals.totalReturn;
  const gain = isNum(totalReturn) ? totalReturn >= 0 : true;
  const firstName = (investor.fullName || 'Investor').split(' ')[0];

  return (
    <div className="relative min-h-screen w-full overflow-x-hidden bg-[#060B1A] text-white font-sans">
      {/* ── Animated aurora background ─────────────────────────────────── */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <motion.div
          className="absolute -top-40 -left-20 h-[36rem] w-[36rem] rounded-full bg-blue-600/20 blur-[140px]"
          animate={{ x: [0, 60, 0], y: [0, 40, 0], scale: [1, 1.15, 1] }}
          transition={{ duration: 18, repeat: Infinity, ease: 'easeInOut' }}
        />
        <motion.div
          className="absolute top-1/3 -right-32 h-[34rem] w-[34rem] rounded-full bg-violet-600/20 blur-[140px]"
          animate={{ x: [0, -50, 0], y: [0, 60, 0], scale: [1, 1.2, 1] }}
          transition={{ duration: 22, repeat: Infinity, ease: 'easeInOut' }}
        />
        <motion.div
          className="absolute bottom-0 left-1/3 h-[30rem] w-[30rem] rounded-full bg-amber-400/10 blur-[150px]"
          animate={{ x: [0, 40, 0], y: [0, -30, 0], scale: [1, 1.1, 1] }}
          transition={{ duration: 26, repeat: Infinity, ease: 'easeInOut' }}
        />
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top,_rgba(255,255,255,0.04),_transparent_55%)]" />
      </div>

      <div className="relative z-10 mx-auto max-w-7xl px-5 py-8 md:px-8 md:py-10">
        {/* ── Top bar ──────────────────────────────────────────────────── */}
        <motion.div
          initial={{ opacity: 0, y: -12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.5 }}
          className="mb-10 flex items-center justify-between"
        >
          <button
            onClick={() => navigate('/investor/dashboard')}
            className="group flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/70 backdrop-blur-xl transition-all hover:-translate-y-0.5 hover:bg-white/10 hover:text-white"
          >
            <ArrowLeft className="h-4 w-4 transition-transform group-hover:-translate-x-0.5" /> Standard view
          </button>
          <div className="flex items-center gap-2 rounded-full border border-amber-300/20 bg-amber-300/5 px-4 py-1.5">
            <Sparkles className="h-3.5 w-3.5 text-amber-300" />
            <span className="bg-gradient-to-r from-amber-200 to-amber-400 bg-clip-text text-xs font-semibold uppercase tracking-[0.25em] text-transparent">Luxe</span>
          </div>
          <button
            onClick={onSignOut}
            className="flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/70 backdrop-blur-xl transition-all hover:-translate-y-0.5 hover:bg-white/10 hover:text-white"
          >
            <LogOut className="h-3.5 w-3.5" /> Sign out
          </button>
        </motion.div>

        {loading ? (
          <div className="flex min-h-[60vh] flex-col items-center justify-center gap-3 text-white/50">
            <Loader2 className="h-8 w-8 animate-spin text-amber-300" />
            <p className="text-sm tracking-wide">Composing your portfolio…</p>
          </div>
        ) : error ? (
          <div className={`${card} mx-auto max-w-md p-8 text-center`}>
            <AlertCircle className="mx-auto mb-3 h-8 w-8 text-rose-400" />
            <p className="mb-5 text-sm text-white/70">{error}</p>
            <button onClick={load} className="rounded-xl bg-white/10 px-5 py-2.5 text-sm font-semibold text-white transition-all hover:-translate-y-0.5 hover:bg-white/15">Retry</button>
          </div>
        ) : (
          <>
            {/* ── Hero ───────────────────────────────────────────────────── */}
            <motion.div
              initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1] }}
              className="mb-10"
            >
              <p className="mb-2 text-sm font-light text-white/50">
                Good to see you, <span className="text-white/80">{firstName}</span>.
              </p>
              <p className="mb-3 text-[11px] font-semibold uppercase tracking-[0.35em] text-amber-200/70">Total portfolio value</p>
              <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
                <AnimatedMoney
                  value={totals.totalCurrentValue}
                  className="bg-gradient-to-br from-white via-white to-white/60 bg-clip-text text-5xl font-semibold tracking-tight text-transparent md:text-7xl"
                />
                {isNum(totalReturn) && (
                  <motion.div
                    initial={{ opacity: 0, scale: 0.9 }} animate={{ opacity: 1, scale: 1 }} transition={{ delay: 0.5 }}
                    className={`mb-2 flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-sm font-semibold ${
                      gain ? 'border-emerald-400/30 bg-emerald-400/10 text-emerald-300'
                           : 'border-rose-400/30 bg-rose-400/10 text-rose-300'}`}
                  >
                    {gain ? <ArrowUpRight className="h-4 w-4" /> : <ArrowDownRight className="h-4 w-4" />}
                    <AnimatedPct value={totals.totalReturnPercent} />
                    <span className="text-white/40">·</span>
                    <span>{fmtMoney(totalReturn, true)}</span>
                  </motion.div>
                )}
              </div>
              <motion.div
                initial={{ scaleX: 0 }} animate={{ scaleX: 1 }} transition={{ delay: 0.4, duration: 0.9, ease: 'easeOut' }}
                className="mt-5 h-px w-full origin-left bg-gradient-to-r from-amber-300/40 via-white/10 to-transparent"
              />
            </motion.div>

            {/* ── KPI cards ─────────────────────────────────────────────────── */}
            <motion.div
              variants={{ show: { transition: { staggerChildren: 0.09 } } }} initial="hide" animate="show"
              className="mb-8 grid grid-cols-2 gap-4 lg:grid-cols-4"
            >
              <KpiCard label="Invested" icon={<Wallet className="h-4 w-4" />} accent="text-blue-300">
                <AnimatedMoney value={totals.totalInvested} compact className="text-2xl font-semibold tracking-tight md:text-3xl" />
              </KpiCard>
              <KpiCard label="Current value" icon={<Activity className="h-4 w-4" />} accent="text-amber-300">
                <AnimatedMoney value={totals.totalCurrentValue} compact className="text-2xl font-semibold tracking-tight md:text-3xl" />
              </KpiCard>
              <KpiCard label="Total return" icon={gain ? <TrendingUp className="h-4 w-4" /> : <TrendingDown className="h-4 w-4" />} accent={gain ? 'text-emerald-300' : 'text-rose-300'}>
                <AnimatedMoney value={totals.totalReturn} compact className={`text-2xl font-semibold tracking-tight md:text-3xl ${gain ? 'text-emerald-300' : 'text-rose-300'}`} />
                <span className="mt-0.5 block text-xs text-white/40">{fmtPct(totals.totalReturnPercent)}</span>
              </KpiCard>
              <KpiCard label="Portfolio XIRR" icon={<Percent className="h-4 w-4" />} accent="text-violet-300">
                <AnimatedPct
                  value={isNum(totals.portfolioXirr) ? (totals.portfolioXirr as number) * 100 : null}
                  className="text-2xl font-semibold tracking-tight text-violet-200 md:text-3xl"
                />
              </KpiCard>
            </motion.div>

            <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
              {/* ── Allocation donut ───────────────────────────────────────── */}
              <motion.div
                initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.2, duration: 0.6 }}
                className={`${card} p-6 lg:col-span-1`}
              >
                <p className="mb-1 text-[11px] font-semibold uppercase tracking-[0.25em] text-white/40">Allocation</p>
                <p className="mb-4 text-sm text-white/60">By current value</p>
                {alloc.length === 0 ? (
                  <div className="flex h-56 items-center justify-center text-sm text-white/30">No valued holdings yet</div>
                ) : (
                  <div className="relative">
                    <ResponsiveContainer width="100%" height={230}>
                      <PieChart>
                        <Pie data={alloc} dataKey="value" nameKey="name" cx="50%" cy="50%"
                          innerRadius={68} outerRadius={95} paddingAngle={3} stroke="none"
                          startAngle={90} endAngle={-270} animationDuration={1100}>
                          {alloc.map((a, i) => <Cell key={i} fill={a.color} />)}
                        </Pie>
                        <Tooltip
                          contentStyle={{ background: 'rgba(10,16,32,0.95)', border: '1px solid rgba(255,255,255,0.12)', borderRadius: 14, color: '#fff', fontSize: 12 }}
                          formatter={(val: number) => fmtMoney(val)}
                        />
                      </PieChart>
                    </ResponsiveContainer>
                    <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                      <p className="text-[10px] uppercase tracking-[0.2em] text-white/40">Value</p>
                      <p className="text-lg font-semibold">{fmtMoney(allocTotal, true)}</p>
                    </div>
                  </div>
                )}
                <div className="mt-4 space-y-2">
                  {alloc.slice(0, 5).map((a, i) => (
                    <motion.div key={i} initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.5 + i * 0.08 }}
                      className="flex items-center justify-between text-xs">
                      <span className="flex items-center gap-2 truncate text-white/70">
                        <span className="h-2.5 w-2.5 flex-shrink-0 rounded-full" style={{ background: a.color }} />
                        <span className="truncate">{a.name}</span>
                      </span>
                      <span className="flex-shrink-0 text-white/50">{allocTotal > 0 ? `${((a.value / allocTotal) * 100).toFixed(1)}%` : '—'}</span>
                    </motion.div>
                  ))}
                </div>
              </motion.div>

              {/* ── Holdings list ─────────────────────────────────────────── */}
              <motion.div
                initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.3, duration: 0.6 }}
                className={`${card} p-6 lg:col-span-2`}
              >
                <div className="mb-5 flex items-center justify-between">
                  <div>
                    <p className="text-[11px] font-semibold uppercase tracking-[0.25em] text-white/40">Holdings</p>
                    <p className="text-sm text-white/60">{holdings.length} fund{holdings.length === 1 ? '' : 's'}</p>
                  </div>
                  <ShieldCheck className="h-5 w-5 text-emerald-300/60" />
                </div>

                {holdings.length === 0 ? (
                  <div className="flex h-48 flex-col items-center justify-center gap-2 text-white/30">
                    <Wallet className="h-7 w-7" />
                    <p className="text-sm">No holdings to show yet.</p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    <AnimatePresence>
                      {holdings.map((h, i) => {
                        const share = allocTotal > 0 && isNum(h.currentValue) ? (h.currentValue / allocTotal) * 100 : 0;
                        const up = isNum(h.percentReturn) ? h.percentReturn >= 0 : true;
                        return (
                          <motion.div
                            key={(h.schemeName || h.scheme || '') + i}
                            initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }}
                            transition={{ delay: 0.4 + i * 0.07, duration: 0.5 }}
                            whileHover={{ y: -3 }}
                            className="group relative overflow-hidden rounded-2xl border border-white/5 bg-white/[0.03] p-4 transition-colors hover:border-white/15 hover:bg-white/[0.06]"
                          >
                            {/* share bar */}
                            <div className="absolute inset-x-0 bottom-0 h-px bg-white/5">
                              <motion.div className="h-full bg-gradient-to-r from-amber-300/70 to-blue-400/70"
                                initial={{ width: 0 }} animate={{ width: `${Math.min(100, share)}%` }} transition={{ delay: 0.7 + i * 0.07, duration: 0.9 }} />
                            </div>
                            <div className="flex items-center justify-between gap-4">
                              <div className="min-w-0">
                                <div className="flex items-center gap-2">
                                  <p className="truncate font-semibold text-white">{h.schemeName || h.scheme || 'Fund'}</p>
                                  {h.sipName && <span className="rounded-md bg-blue-400/15 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-blue-200">{h.sipName}</span>}
                                  {stale(h) && <span className="rounded-md bg-amber-400/15 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-amber-200">Stale</span>}
                                  {!usable(h) && !stale(h) && <span className="rounded-md bg-white/10 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-white/50">No NAV</span>}
                                </div>
                                <p className="mt-0.5 truncate text-xs text-white/40">
                                  {h.amcName || '—'} · {fmtUnits(h.units)} units · NAV {fmtNav(h.latestNav)}
                                  {fmtDate(h.navAsOf) ? ` · ${fmtDate(h.navAsOf)}` : ''}
                                </p>
                                {h.folio && (
                                  <p className="mt-0.5 truncate font-mono text-[11px] text-white/30">Folio {h.folio}</p>
                                )}
                              </div>
                              <div className="flex flex-shrink-0 items-center gap-5 text-right">
                                <div>
                                  <p className="text-[10px] uppercase tracking-wider text-white/35">Value</p>
                                  <p className="font-semibold text-white">{fmtMoney(h.currentValue, true)}</p>
                                </div>
                                <div className="hidden sm:block">
                                  <p className="text-[10px] uppercase tracking-wider text-white/35">Return</p>
                                  <p className={`font-semibold ${isNum(h.percentReturn) ? (up ? 'text-emerald-300' : 'text-rose-300') : 'text-white/40'}`}>{fmtPct(h.percentReturn)}</p>
                                </div>
                                <div className="hidden md:block">
                                  <p className="text-[10px] uppercase tracking-wider text-white/35">XIRR</p>
                                  <p className="font-semibold text-violet-200">{isNum(h.xirr) ? `${(h.xirr * 100).toFixed(1)}%` : '—'}</p>
                                </div>
                              </div>
                            </div>
                          </motion.div>
                        );
                      })}
                    </AnimatePresence>
                  </div>
                )}
              </motion.div>
            </div>

            <motion.p
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 1 }}
              className="mt-8 text-center text-[11px] tracking-wider text-white/25"
            >
              Valuation reflects units × latest NAV · stale or unavailable inputs are flagged, never fabricated.
            </motion.p>
          </>
        )}
      </div>
    </div>
  );
}

function KpiCard({ label, icon, accent, children }: { label: string; icon: React.ReactNode; accent: string; children: React.ReactNode }) {
  return (
    <motion.div
      variants={{ hide: { opacity: 0, y: 18 }, show: { opacity: 1, y: 0 } }}
      transition={{ duration: 0.55, ease: [0.16, 1, 0.3, 1] }}
      whileHover={{ y: -4 }}
      className={`${card} group relative overflow-hidden p-5`}
    >
      <div className="absolute -right-6 -top-6 h-20 w-20 rounded-full bg-white/[0.05] blur-2xl transition-opacity group-hover:opacity-100 opacity-60" />
      <div className="mb-3 flex items-center justify-between">
        <p className="text-[10px] font-semibold uppercase tracking-[0.2em] text-white/40">{label}</p>
        <span className={accent}>{icon}</span>
      </div>
      <div className="text-white">{children}</div>
    </motion.div>
  );
}
