import React, { useState } from 'react';
import { Beaker, ChevronDown, ChevronUp, Copy, Check, Sparkles } from 'lucide-react';
import {
  SANDBOX_BANK_HINTS,
  SANDBOX_DEMO_SCENARIOS,
  SANDBOX_ORDER_HINTS,
  type SandboxDemoScenario,
} from '../utils/sandboxDemoData';

type Props = {
  onApplyScenario?: (scenario: SandboxDemoScenario) => void;
  compact?: boolean;
  defaultExpanded?: boolean;
};

const pathBadge = (path: SandboxDemoScenario['path']) => {
  if (path === 'full-kyc') return 'bg-indigo-100 text-indigo-800';
  if (path === 'fast') return 'bg-green-100 text-green-800';
  return 'bg-slate-100 text-slate-600';
};

const pathLabel = (path: SandboxDemoScenario['path']) => {
  if (path === 'full-kyc') return 'Full KYC';
  if (path === 'fast') return 'Fast path';
  return 'Negative test';
};

export default function SandboxDemoGuide({ onApplyScenario, compact, defaultExpanded }: Props) {
  const [expanded, setExpanded] = useState(defaultExpanded ?? !compact);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [filter, setFilter] = useState<'all' | 'high' | 'full-kyc'>('high');

  const scenarios = SANDBOX_DEMO_SCENARIOS.filter(s => {
    if (filter === 'high') return s.priority === 'high';
    if (filter === 'full-kyc') return s.path === 'full-kyc';
    return true;
  });

  const copyScenario = async (scenario: SandboxDemoScenario) => {
    const text = [
      `Scenario: ${scenario.label}`,
      `PAN: ${scenario.pan}`,
      `Name: ${scenario.firstName} ${scenario.lastName}`,
      `DOB: ${scenario.dob}`,
      `Mobile: ${scenario.mobile}`,
      `Email: ${scenario.email}`,
      `Address: ${scenario.addressLine1}, ${scenario.city}, ${scenario.state} ${scenario.postalCode}`,
      `Bank: ${scenario.bankAccount} (${scenario.ifsc})`,
      `Expected readiness: ${scenario.expectedReadiness}`,
      `Flow: ${scenario.expectedFlow}`,
      scenario.orderHint || '',
    ].filter(Boolean).join('\n');
    await navigator.clipboard.writeText(text);
    setCopiedId(scenario.id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  return (
    <div className="rounded-xl border border-amber-300 bg-gradient-to-br from-amber-50 to-orange-50 shadow-sm">
      <button
        type="button"
        onClick={() => setExpanded(v => !v)}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left"
      >
        <div className="flex items-center gap-2">
          <Beaker className="h-4 w-4 text-amber-700" />
          <span className="text-sm font-semibold text-amber-900">Sandbox demo guide</span>
          <span className="rounded-full bg-amber-200 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-900">
            Platizio test data
          </span>
        </div>
        {expanded ? <ChevronUp className="h-4 w-4 text-amber-700" /> : <ChevronDown className="h-4 w-4 text-amber-700" />}
      </button>

      {expanded && (
        <div className="border-t border-amber-200 px-4 pb-4 pt-3">
          <p className="mb-3 text-xs leading-5 text-amber-900/80">
            Official Platizio sandbox simulators only. Use <span className="font-semibold">BBBPB3753B</span> for the full
            kyc_unavailable demo, or <span className="font-semibold">AAAPA3751A</span> for a fast purchase.
          </p>

          <div className="mb-3 flex flex-wrap gap-2">
            {(['high', 'full-kyc', 'all'] as const).map(key => (
              <button
                key={key}
                type="button"
                onClick={() => setFilter(key)}
                className={`rounded-lg px-2.5 py-1 text-[11px] font-semibold transition-colors ${
                  filter === key ? 'bg-amber-800 text-white' : 'bg-white text-amber-800 ring-1 ring-amber-200 hover:bg-amber-100'
                }`}
              >
                {key === 'high' ? 'Demo picks' : key === 'full-kyc' ? 'kyc_unavailable' : 'All cases'}
              </button>
            ))}
          </div>

          <div className="space-y-2">
            {scenarios.map(scenario => (
              <div
                key={scenario.id}
                className="rounded-lg border border-amber-200/80 bg-white/90 p-3"
              >
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="text-xs font-bold text-slate-800">{scenario.label}</p>
                      <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${pathBadge(scenario.path)}`}>
                        {pathLabel(scenario.path)}
                      </span>
                    </div>
                    <p className="mt-1 text-[11px] leading-4 text-slate-600">{scenario.description}</p>
                    <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[10px] text-slate-500 sm:grid-cols-3">
                      <div><dt className="inline font-semibold text-slate-700">PAN </dt><dd className="inline font-mono text-slate-800">{scenario.pan}</dd></div>
                      <div><dt className="inline font-semibold text-slate-700">Readiness </dt><dd className="inline text-slate-800">{scenario.expectedReadiness}</dd></div>
                      <div><dt className="inline font-semibold text-slate-700">Bank </dt><dd className="inline font-mono text-slate-800">…{scenario.bankAccount.slice(-4)}</dd></div>
                    </dl>
                    <p className="mt-1.5 text-[10px] text-indigo-700">{scenario.expectedFlow}</p>
                  </div>
                  <div className="flex flex-shrink-0 gap-1.5">
                    <button
                      type="button"
                      onClick={() => copyScenario(scenario)}
                      className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[10px] font-semibold text-slate-600 hover:bg-slate-50"
                    >
                      {copiedId === scenario.id ? <Check className="h-3 w-3 text-green-600" /> : <Copy className="h-3 w-3" />}
                      Copy
                    </button>
                    {onApplyScenario && (
                      <button
                        type="button"
                        onClick={() => onApplyScenario(scenario)}
                        className="inline-flex items-center gap-1 rounded-md bg-[#0B1B3E] px-2 py-1 text-[10px] font-semibold text-white hover:bg-[#1A3066]"
                      >
                        <Sparkles className="h-3 w-3" />
                        Use
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>

          {!compact && (
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <div className="rounded-lg border border-amber-200 bg-white/80 p-3">
                <p className="text-[11px] font-bold text-slate-700">Bank account suffixes</p>
                <ul className="mt-1.5 space-y-1">
                  {SANDBOX_BANK_HINTS.map(h => (
                    <li key={h.suffix} className="text-[10px] text-slate-600">
                      <span className="font-mono font-semibold text-slate-800">{h.suffix}</span> — {h.outcome}
                    </li>
                  ))}
                </ul>
              </div>
              <div className="rounded-lg border border-amber-200 bg-white/80 p-3">
                <p className="text-[11px] font-bold text-slate-700">Order amounts</p>
                <ul className="mt-1.5 space-y-1">
                  {SANDBOX_ORDER_HINTS.map(h => (
                    <li key={h.amount} className="text-[10px] text-slate-600">
                      <span className="font-semibold text-slate-800">{h.amount}</span> — {h.outcome}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
