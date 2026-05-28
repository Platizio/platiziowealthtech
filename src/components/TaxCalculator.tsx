import React, { useState, useMemo } from 'react';
import { CheckCircle2, Info } from 'lucide-react';
import { SliderField, fmtINR } from './CalculatorShell';

// ── Types ─────────────────────────────────────────────────────────────────────
interface Slab { from: number; to: number; rate: number; }

interface TaxResult {
  taxableIncome: number;
  slabTax:       number;
  rebate:        number;
  cess:          number;
  total:         number;
  effectiveRate: number;
}

// ── Tax Config (FY 2024-25) ───────────────────────────────────────────────────
// TODO: replace with apiFetch('/config/tax-slabs').then(r => r.json()) once the
// Spring config endpoint is live. Shape is intentionally identical to the planned
// API response, so the swap is a one-liner with zero UI changes.
const TAX_CONFIG = {
  year: '2024-25',
  oldRegime: {
    standardDeduction: 50_000,
    slabs: [
      { from: 0,         to: 250_000,   rate: 0.00 },
      { from: 250_000,   to: 500_000,   rate: 0.05 },
      { from: 500_000,   to: 1_000_000, rate: 0.20 },
      { from: 1_000_000, to: Infinity,  rate: 0.30 },
    ] as Slab[],
    rebateLimit: 500_000,   // 87A: taxable income ≤ ₹5L → rebate up to ₹12,500
    rebateMax:   12_500,
    cessRate:    0.04,
    limits: {
      sec80C:     150_000,  // PPF / ELSS / LIC / EPF / home loan principal
      sec80D:      50_000,  // self ₹25K + parents ₹25K
      sec24b:     200_000,  // home loan interest (self-occupied)
      sec80CCD1B:  50_000,  // NPS additional
    },
  },
  newRegime: {
    standardDeduction: 75_000,
    slabs: [
      { from: 0,         to: 300_000,   rate: 0.00 },
      { from: 300_000,   to: 700_000,   rate: 0.05 },
      { from: 700_000,   to: 1_000_000, rate: 0.10 },
      { from: 1_000_000, to: 1_200_000, rate: 0.15 },
      { from: 1_200_000, to: 1_500_000, rate: 0.20 },
      { from: 1_500_000, to: Infinity,  rate: 0.30 },
    ] as Slab[],
    rebateLimit: 700_000,   // 87A: taxable income ≤ ₹7L → rebate up to ₹25,000
    rebateMax:   25_000,
    cessRate:    0.04,
  },
};

// ── Helpers ───────────────────────────────────────────────────────────────────
function applySlabs(taxableIncome: number, slabs: Slab[]): number {
  let tax = 0;
  for (const { from, to, rate } of slabs) {
    if (taxableIncome <= from) break;
    tax += (Math.min(taxableIncome, to) - from) * rate;
  }
  return Math.round(tax);
}

function computeTax(
  grossIncome:  number,
  deductions:   number,
  slabs:        Slab[],
  rebateLimit:  number,
  rebateMax:    number,
  cessRate:     number,
): TaxResult {
  const taxableIncome = Math.max(0, grossIncome - deductions);
  const slabTax       = applySlabs(taxableIncome, slabs);
  const rebate        = taxableIncome <= rebateLimit ? Math.min(slabTax, rebateMax) : 0;
  const afterRebate   = Math.max(0, slabTax - rebate);
  const cess          = Math.round(afterRebate * cessRate);
  const total         = afterRebate + cess;
  const effectiveRate = grossIncome > 0 ? (total / grossIncome) * 100 : 0;
  return { taxableIncome, slabTax, rebate, cess, total, effectiveRate };
}

// ── TaxResultCard ─────────────────────────────────────────────────────────────
function TaxResultCard({ regime, result, isBetter }: {
  regime:   string;
  result:   TaxResult;
  isBetter: boolean;
}) {
  return (
    <div className={`rounded-xl border p-3.5 ${
      isBetter ? 'bg-emerald-50 border-emerald-200' : 'bg-slate-50 border-slate-200'
    }`}>
      <div className="flex items-center justify-between mb-3">
        <p className={`text-[9px] font-bold uppercase tracking-widest ${
          isBetter ? 'text-emerald-700' : 'text-slate-500'
        }`}>
          {regime} Regime
        </p>
        {isBetter && (
          <span className="text-[8px] font-bold bg-emerald-500 text-white px-1.5 py-0.5 rounded-full uppercase tracking-wide">
            Best
          </span>
        )}
      </div>

      <div className="space-y-1.5 mb-3">
        <div className="flex justify-between text-[10px]">
          <span className="text-slate-400">Taxable Inc.</span>
          <span className="font-mono text-slate-700">{fmtINR(result.taxableIncome)}</span>
        </div>
        <div className="flex justify-between text-[10px]">
          <span className="text-slate-400">Slab Tax</span>
          <span className="font-mono text-slate-700">{fmtINR(result.slabTax)}</span>
        </div>
        {result.rebate > 0 && (
          <div className="flex justify-between text-[10px]">
            <span className="text-slate-400">87A Rebate</span>
            <span className="font-mono text-emerald-600">−{fmtINR(result.rebate)}</span>
          </div>
        )}
        <div className="flex justify-between text-[10px]">
          <span className="text-slate-400">Cess (4%)</span>
          <span className="font-mono text-slate-700">{fmtINR(result.cess)}</span>
        </div>
      </div>

      <div className={`border-t pt-2.5 ${isBetter ? 'border-emerald-200' : 'border-slate-200'}`}>
        <p className={`text-lg font-bold leading-tight ${isBetter ? 'text-emerald-700' : 'text-slate-800'}`}>
          {fmtINR(result.total)}
        </p>
        <p className={`text-[10px] mt-0.5 ${isBetter ? 'text-emerald-600' : 'text-slate-400'}`}>
          {result.effectiveRate.toFixed(1)}% effective rate
        </p>
      </div>
    </div>
  );
}

// ── TaxCalculator ─────────────────────────────────────────────────────────────
export interface TaxCalculatorProps {
  userData?: any;
}

export default function TaxCalculator(_: TaxCalculatorProps) {
  // ── Income state ──────────────────────────────────────────────────────────
  const [salary,      setSalary]      = useState(1_000_000);
  const [otherIncome, setOtherIncome] = useState(0);

  // ── Old-regime deduction state ────────────────────────────────────────────
  const [sec80C,   setSec80C]   = useState(150_000);
  const [sec80D,   setSec80D]   = useState(25_000);
  const [hra,      setHra]      = useState(0);
  const [homeLoan, setHomeLoan] = useState(0);
  const [nps,      setNps]      = useState(0);

  // ── Derived values ────────────────────────────────────────────────────────
  const grossIncome = salary + otherIncome;

  const { oldResult, newResult } = useMemo(() => {
    const { oldRegime, newRegime } = TAX_CONFIG;
    const oldDeductions =
      oldRegime.standardDeduction +
      Math.min(sec80C,   oldRegime.limits.sec80C) +
      Math.min(sec80D,   oldRegime.limits.sec80D) +
      Math.min(hra,      grossIncome) +
      Math.min(homeLoan, oldRegime.limits.sec24b) +
      Math.min(nps,      oldRegime.limits.sec80CCD1B);

    return {
      oldResult: computeTax(
        grossIncome, oldDeductions,
        oldRegime.slabs, oldRegime.rebateLimit, oldRegime.rebateMax, oldRegime.cessRate,
      ),
      newResult: computeTax(
        grossIncome, newRegime.standardDeduction,
        newRegime.slabs, newRegime.rebateLimit, newRegime.rebateMax, newRegime.cessRate,
      ),
    };
  }, [grossIncome, sec80C, sec80D, hra, homeLoan, nps]);

  const betterRegime   = oldResult.total <= newResult.total ? 'old' : 'new';
  const savings        = Math.abs(oldResult.total - newResult.total);
  const betterResult   = betterRegime === 'old' ? oldResult : newResult;
  const worseResult    = betterRegime === 'old' ? newResult : oldResult;

  return (
    <div className="w-full space-y-6">
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

        {/* ── Left: inputs ── */}
        <div className="space-y-5">

          {/* Income */}
          <div>
            <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-3">Income</p>
            <div className="space-y-4">
              <SliderField
                label="Gross Annual Salary"
                value={salary} min={100_000} max={5_000_000} step={50_000}
                display={fmtINR(salary)}
                onChange={setSalary}
                labelMin="₹1 L" labelMax="₹50 L"
              />
              <SliderField
                label="Other Income"
                value={otherIncome} min={0} max={1_000_000} step={10_000}
                display={fmtINR(otherIncome)}
                onChange={setOtherIncome}
                labelMin="₹0" labelMax="₹10 L"
              />
            </div>
          </div>

          {/* Old-regime deductions */}
          <div>
            <div className="flex items-center gap-2 mb-3">
              <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
                Old Regime Deductions
              </p>
              <span className="text-[9px] font-bold bg-slate-100 text-slate-400 px-1.5 py-0.5 rounded uppercase tracking-wide">
                Not in New Regime
              </span>
            </div>
            <div className="space-y-4">
              <SliderField
                label="80C Investments (cap ₹1.5 L)"
                value={sec80C} min={0} max={150_000} step={5_000}
                display={fmtINR(sec80C)}
                onChange={setSec80C}
                labelMin="₹0" labelMax="₹1.5 L"
              />
              <SliderField
                label="80D Health Insurance (cap ₹50 K)"
                value={sec80D} min={0} max={50_000} step={5_000}
                display={fmtINR(sec80D)}
                onChange={setSec80D}
                labelMin="₹0" labelMax="₹50 K"
              />
              <SliderField
                label="HRA Exemption"
                value={hra} min={0} max={500_000} step={5_000}
                display={fmtINR(hra)}
                onChange={setHra}
                labelMin="₹0" labelMax="₹5 L"
              />
              <SliderField
                label="Home Loan Interest 24(b) (cap ₹2 L)"
                value={homeLoan} min={0} max={200_000} step={10_000}
                display={fmtINR(homeLoan)}
                onChange={setHomeLoan}
                labelMin="₹0" labelMax="₹2 L"
              />
              <SliderField
                label="NPS 80CCD(1B) (cap ₹50 K)"
                value={nps} min={0} max={50_000} step={5_000}
                display={fmtINR(nps)}
                onChange={setNps}
                labelMin="₹0" labelMax="₹50 K"
              />
            </div>
            <p className="text-[10px] text-slate-400 mt-2.5 flex items-start gap-1.5">
              <Info className="w-3 h-3 mt-px flex-shrink-0" />
              Standard Deduction (₹50K old / ₹75K new) applied automatically.
              Enter your pre-calculated HRA exemption, not the gross HRA received.
            </p>
          </div>
        </div>

        {/* ── Right: comparison ── */}
        <div className="space-y-4">
          <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
            Tax Comparison — FY {TAX_CONFIG.year}
          </p>
          <div className="grid grid-cols-2 gap-3">
            <TaxResultCard regime="Old" result={oldResult} isBetter={betterRegime === 'old'} />
            <TaxResultCard regime="New" result={newResult} isBetter={betterRegime === 'new'} />
          </div>
        </div>
      </div>

      {/* ── Recommendation banner ── */}
      <div className={`rounded-2xl p-4 flex items-start gap-3 border ${
        savings === 0
          ? 'bg-slate-50 border-slate-200'
          : 'bg-emerald-50 border-emerald-200'
      }`}>
        {savings === 0
          ? <Info        className="w-5 h-5 text-slate-400   flex-shrink-0 mt-0.5" />
          : <CheckCircle2 className="w-5 h-5 text-emerald-600 flex-shrink-0 mt-0.5" />
        }
        <div>
          {savings === 0 ? (
            <p className="text-sm font-semibold text-slate-700">
              Both regimes result in equal tax liability for this income profile.
            </p>
          ) : (
            <>
              <p className="text-sm font-semibold text-emerald-800">
                {betterRegime === 'old' ? 'Old' : 'New'} Regime saves you{' '}
                <span className="font-bold">{fmtINR(savings)}</span> this year.
              </p>
              <p className="text-xs text-emerald-600 mt-0.5">
                Effective rate {betterResult.effectiveRate.toFixed(1)}% vs {worseResult.effectiveRate.toFixed(1)}%
                {betterRegime === 'old'
                  ? ' — your deductions make Old Regime the better choice.'
                  : ' — New Regime offers lower tax with simplified compliance.'}
              </p>
            </>
          )}
        </div>
      </div>

      <p className="text-[10px] text-slate-400 text-center">
        Estimates for FY {TAX_CONFIG.year}. Excludes surcharge & marginal relief. Consult a tax advisor before filing.
      </p>
    </div>
  );
}
