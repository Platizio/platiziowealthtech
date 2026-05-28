import React, { useState, useMemo, useEffect } from 'react';
import CalculatorShell, { SliderField, fmtINR, buildYearLabels, ChartPoint } from './CalculatorShell';

// ── Lumpsum Future Value Formula ──────────────────────────────────────────────
// FV = P × (1 + r)^n   where r = annual_rate / 100, n = years
function lumpsumFV(P: number, annualRatePct: number, years: number): number {
  return P * Math.pow(1 + annualRatePct / 100, years);
}

function buildChartData(P: number, rate: number, years: number): ChartPoint[] {
  return Array.from({ length: years }, (_, k) => {
    const yr = k + 1;
    const fv = lumpsumFV(P, rate, yr);
    return { label: buildYearLabels(yr, years), yr, invested: P, gains: Math.max(0, fv - P) };
  });
}

export interface LumpsumCalculatorProps {
  defaultPrincipal?: number;
  defaultRate?:      number;
  defaultYears?:     number;
  fundName?:         string;
  userData?:         any;
  onLogin?:          () => void;
}

export default function LumpsumCalculator({
  defaultPrincipal = 100000,
  defaultRate      = 12,
  defaultYears     = 10,
  fundName,
  userData,
  onLogin,
}: LumpsumCalculatorProps) {
  const [principal, setPrincipal] = useState(defaultPrincipal);
  const [rate,      setRate]      = useState(defaultRate);
  const [years,     setYears]     = useState(defaultYears);

  useEffect(() => { setPrincipal(defaultPrincipal); }, [defaultPrincipal]);
  useEffect(() => { setRate(defaultRate);            }, [defaultRate]);
  useEffect(() => { setYears(defaultYears);          }, [defaultYears]);

  const corpus   = lumpsumFV(principal, rate, years);
  const gains    = Math.max(0, corpus - principal);
  const multiple = principal > 0 ? corpus / principal : 1;
  const chartData = useMemo(() => buildChartData(principal, rate, years), [principal, rate, years]);

  return (
    <CalculatorShell
      summary={{ invested: principal, gains, corpus, multiple }}
      chartData={chartData}
      userData={userData}
      onLogin={onLogin}
      goalPayload={{
        goalType:        'LUMPSUM',
        principalAmount: principal,
        expectedRate:    rate,
        durationYears:   years,
        fundName:        fundName ?? null,
      }}
    >
      {fundName && (
        <p className="text-xs text-slate-500">
          Calculating for <span className="font-semibold text-slate-700">{fundName}</span>
        </p>
      )}
      <SliderField
        label="Investment Amount"
        value={principal} min={1000} max={10000000} step={1000}
        display={fmtINR(principal)}
        onChange={setPrincipal}
        labelMin="₹1 K" labelMax="₹1 Cr"
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
