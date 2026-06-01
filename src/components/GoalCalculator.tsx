import React, { useState, useMemo, useEffect } from 'react';
import CalculatorShell, { SliderField, fmtINR, buildYearLabels, ChartPoint } from './CalculatorShell';

// ── Reverse SIP Formula ───────────────────────────────────────────────────────
// Solve for monthly SIP (P) given a target corpus (FV):
// P = FV × i / [((1+i)^n − 1) × (1+i)]   where i = annual_rate / 12 / 100
function goalSIP(FV: number, annualRatePct: number, years: number): number {
  const i = annualRatePct / 100 / 12;
  const n = years * 12;
  if (i === 0) return n > 0 ? FV / n : 0;
  return (FV * i) / ((Math.pow(1 + i, n) - 1) * (1 + i));
}

// Forward SIP FV — used to build year-wise chart given the calculated P
function sipFV(P: number, annualRatePct: number, years: number): number {
  const i = annualRatePct / 100 / 12;
  const n = years * 12;
  if (i === 0) return P * n;
  return P * ((Math.pow(1 + i, n) - 1) / i) * (1 + i);
}

function buildChartData(P: number, rate: number, years: number): ChartPoint[] {
  return Array.from({ length: years }, (_, k) => {
    const yr      = k + 1;
    const invested = P * 12 * yr;
    const corpus   = sipFV(P, rate, yr);
    return { label: buildYearLabels(yr, years), yr, invested, gains: Math.max(0, corpus - invested) };
  });
}

// ── Goal templates ────────────────────────────────────────────────────────────
const GOAL_TEMPLATES = [
  { label: '🏠 House',      fv: 2_500_000,  years: 8,  rate: 10 },
  { label: '🎓 Education',  fv: 5_000_000,  years: 15, rate: 12 },
  { label: '🌴 Retirement', fv: 10_000_000, years: 25, rate: 12 },
] as const;

// ── Props ─────────────────────────────────────────────────────────────────────
export interface GoalCalculatorProps {
  defaultFV?:    number;
  defaultRate?:  number;
  defaultYears?: number;
  fundName?:     string;
  userData?:     any;
  onLogin?:      () => void;
}

export default function GoalCalculator({
  defaultFV    = 5_000_000,
  defaultRate  = 12,
  defaultYears = 10,
  fundName,
  userData,
  onLogin,
}: GoalCalculatorProps) {
  const [fv,    setFv]    = useState(defaultFV);
  const [rate,  setRate]  = useState(defaultRate);
  const [years, setYears] = useState(defaultYears);

  useEffect(() => { setFv(defaultFV);       }, [defaultFV]);
  useEffect(() => { setRate(defaultRate);   }, [defaultRate]);
  useEffect(() => { setYears(defaultYears); }, [defaultYears]);

  const monthlyP      = goalSIP(fv, rate, years);
  const totalInvested = monthlyP * 12 * years;
  const gains         = Math.max(0, fv - totalInvested);
  const multiple      = totalInvested > 0 ? fv / totalInvested : 1;
  const chartData     = useMemo(() => buildChartData(monthlyP, rate, years), [monthlyP, rate, years]);

  return (
    <CalculatorShell
      summary={{ invested: totalInvested, gains, corpus: fv, multiple }}
      chartData={chartData}
      userData={userData}
      onLogin={onLogin}
      goalPayload={{
        goalType:      'GOAL_SIP',
        targetCorpus:  Math.round(fv),
        monthlyAmount: Math.round(monthlyP),
        expectedRate:  rate,
        durationYears: years,
        fundName:      fundName ?? null,
      }}
    >
      {/* ── Goal template pills ── */}
      <div>
        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Quick Templates</p>
        <div className="flex gap-2 flex-wrap">
          {GOAL_TEMPLATES.map(t => (
            <button
              key={t.label}
              onClick={() => { setFv(t.fv); setRate(t.rate); setYears(t.years); }}
              className="px-3 py-1.5 text-xs font-semibold rounded-lg border border-slate-200 bg-white hover:border-blue-300 hover:bg-blue-50 hover:text-blue-700 text-slate-600 transition-colors"
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* ── Required Monthly SIP — the primary output ── */}
      <div className="bg-[#0B1B3E] rounded-xl px-4 py-3 flex items-center justify-between">
        <p className="text-[10px] font-bold text-white/60 uppercase tracking-wider">Required Monthly SIP</p>
        <p className="text-xl font-bold text-white">{fmtINR(Math.round(monthlyP))}</p>
      </div>

      {fundName && (
        <p className="text-xs text-slate-500">
          Calculating for <span className="font-semibold text-slate-700">{fundName}</span>
        </p>
      )}

      <SliderField
        label="Target Corpus"
        value={fv} min={100_000} max={100_000_000} step={100_000}
        display={fmtINR(fv)}
        onChange={setFv}
        labelMin="₹1 L" labelMax="₹10 Cr"
      />
      <SliderField
        label="Expected Annual Return"
        value={rate} min={1} max={30} step={0.5}
        display={`${rate}%`}
        onChange={setRate}
        labelMin="1%" labelMax="30%"
      />
      <SliderField
        label="Time to Goal"
        value={years} min={1} max={30} step={1}
        display={`${years} yr${years > 1 ? 's' : ''}`}
        onChange={setYears}
        labelMin="1 yr" labelMax="30 yrs"
      />
    </CalculatorShell>
  );
}
