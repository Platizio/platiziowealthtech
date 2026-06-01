import React, { useState, useMemo, useEffect } from 'react';
import CalculatorShell, { SliderField, fmtINR, buildYearLabels, ChartPoint } from './CalculatorShell';

// ── SIP Future Value Formula ──────────────────────────────────────────────────
// FV = P × [((1+i)^n - 1)/i] × (1+i)   where i = annual_rate / 12 / 100
function sipFV(P: number, annualRatePct: number, years: number): number {
  const i = annualRatePct / 100 / 12;
  const n = years * 12;
  if (i === 0) return P * n;
  return P * ((Math.pow(1 + i, n) - 1) / i) * (1 + i);
}

function buildChartData(P: number, rate: number, years: number): ChartPoint[] {
  return Array.from({ length: years }, (_, k) => {
    const yr       = k + 1;
    const invested = P * 12 * yr;
    const corpus   = sipFV(P, rate, yr);
    return { label: buildYearLabels(yr, years), yr, invested, gains: Math.max(0, corpus - invested) };
  });
}

export interface SipCalculatorProps {
  defaultAmount?: number;
  defaultRate?:   number;
  defaultYears?:  number;
  fundName?:      string;
  userData?:      any;
  onLogin?:       () => void;
}

export default function SipCalculator({
  defaultAmount = 5000,
  defaultRate   = 12,
  defaultYears  = 10,
  fundName,
  userData,
  onLogin,
}: SipCalculatorProps) {
  const [amount, setAmount] = useState(defaultAmount);
  const [rate,   setRate]   = useState(defaultRate);
  const [years,  setYears]  = useState(defaultYears);

  useEffect(() => { setAmount(defaultAmount); }, [defaultAmount]);
  useEffect(() => { setRate(defaultRate);     }, [defaultRate]);
  useEffect(() => { setYears(defaultYears);   }, [defaultYears]);

  const totalInvested = amount * 12 * years;
  const corpus        = sipFV(amount, rate, years);
  const gains         = Math.max(0, corpus - totalInvested);
  const multiple      = totalInvested > 0 ? corpus / totalInvested : 1;
  const chartData     = useMemo(() => buildChartData(amount, rate, years), [amount, rate, years]);

  return (
    <CalculatorShell
      summary={{ invested: totalInvested, gains, corpus, multiple }}
      chartData={chartData}
      userData={userData}
      onLogin={onLogin}
      goalPayload={{
        goalType:      'SIP',
        monthlyAmount: amount,
        expectedRate:  rate,
        durationYears: years,
        fundName:      fundName ?? null,
      }}
    >
      {fundName && (
        <p className="text-xs text-slate-500">
          Calculating for <span className="font-semibold text-slate-700">{fundName}</span>
        </p>
      )}
      <SliderField
        label="Monthly Investment"
        value={amount} min={500} max={100000} step={500}
        display={fmtINR(amount)}
        onChange={setAmount}
        labelMin="₹500" labelMax="₹1.00 L"
      />
      <SliderField
        label="Expected Annual Return"
        value={rate} min={1} max={30} step={0.5}
        display={`${rate}%`}
        onChange={setRate}
        labelMin="1%" labelMax="30%"
      />
      <SliderField
        label="Investment Duration"
        value={years} min={1} max={30} step={1}
        display={`${years} yr${years > 1 ? 's' : ''}`}
        onChange={setYears}
        labelMin="1 yr" labelMax="30 yrs"
      />
    </CalculatorShell>
  );
}
