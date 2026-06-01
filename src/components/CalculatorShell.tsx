import React, { useState, useEffect, useRef } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { BookmarkPlus, Check, ChevronDown, Target } from 'lucide-react';
import { apiFetch } from '../config/api';

// ── Shared types ──────────────────────────────────────────────────────────────

export interface ChartPoint {
  label:    string;
  yr:       number;
  invested: number;
  gains:    number;
}

export interface SummaryValues {
  invested: number;
  gains:    number;
  corpus:   number;
  multiple: number;
}

export interface CalculatorShellProps {
  /** Slider controls rendered in the left column */
  children:    React.ReactNode;
  summary:     SummaryValues;
  chartData:   ChartPoint[];
  userData?:   any;
  onLogin?:    () => void;
  /** Calculator-specific fields merged into the /goals/draft payload */
  goalPayload: Record<string, any>;
}

// ── Shared utilities (exported for use in individual calculators) ─────────────

export function fmtINR(n: number): string {
  if (n >= 10_000_000) return `₹${(n / 10_000_000).toFixed(2)} Cr`;
  if (n >= 100_000)    return `₹${(n / 100_000).toFixed(2)} L`;
  if (n >= 1_000)      return `₹${(n / 1_000).toFixed(1)} K`;
  return `₹${Math.round(n).toLocaleString('en-IN')}`;
}

export function buildYearLabels(yr: number, years: number): string {
  if (years > 20) return yr % 5 === 0 ? `Y${yr}` : '';
  if (years > 10) return yr % 2 === 0 ? `Y${yr}` : '';
  return `Y${yr}`;
}

export function SliderField({
  label, value, min, max, step, display, onChange, labelMin, labelMax,
}: {
  label: string; value: number; min: number; max: number; step: number;
  display: string; onChange: (v: number) => void;
  labelMin: string; labelMax: string;
}) {
  return (
    <div>
      <div className="flex justify-between items-center mb-2">
        <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">{label}</span>
        <span className="text-sm font-bold text-[#0B1B3E]">{display}</span>
      </div>
      <input
        type="range"
        min={min} max={max} step={step} value={value}
        onChange={e => onChange(Number(e.target.value))}
        className="w-full h-1.5 rounded-full appearance-none cursor-pointer accent-[#0B1B3E] bg-slate-200"
      />
      <div className="flex justify-between mt-1">
        <span className="text-[10px] text-slate-400">{labelMin}</span>
        <span className="text-[10px] text-slate-400">{labelMax}</span>
      </div>
    </div>
  );
}

// ── Internal sub-components ───────────────────────────────────────────────────

function SummaryCard({ label, value, cls, dark }: {
  label: string; value: string; cls: string; dark?: boolean;
}) {
  return (
    <div className={`rounded-xl p-4 border ${cls}`}>
      <p className={`text-[10px] font-bold uppercase tracking-wider mb-1 ${dark ? 'text-white/60' : 'opacity-60'}`}>
        {label}
      </p>
      <p className="text-lg font-bold leading-tight">{value}</p>
    </div>
  );
}

// ── CalculatorShell ───────────────────────────────────────────────────────────

export default function CalculatorShell({
  children, summary, chartData, userData, onLogin, goalPayload,
}: CalculatorShellProps) {
  const [investors,    setInvestors]    = useState<any[]>([]);
  const [invSearch,    setInvSearch]    = useState('');
  const [selectedInv,  setSelectedInv]  = useState<any | null>(null);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [saving,       setSaving]       = useState(false);
  const [saved,        setSaved]        = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!userData?.id) return;
    apiFetch(`/investors/by-distributor/${userData.id}`)
      .then(r => r.ok ? r.json() : [])
      .then(d => setInvestors(Array.isArray(d) ? d : []))
      .catch(() => {});
  }, [userData?.id]);

  useEffect(() => {
    if (!dropdownOpen) return;
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [dropdownOpen]);

  const filteredInvestors = investors.filter(inv =>
    !invSearch || (inv.fullName || '').toLowerCase().includes(invSearch.toLowerCase()),
  );

  const handleSave = async () => {
    if (!userData) { onLogin?.(); return; }
    if (!selectedInv) return;
    setSaving(true);
    try {
      await apiFetch('/goals/draft', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          investorId:    selectedInv.id,
          distributorId: userData.id,
          targetCorpus:  Math.round(summary.corpus),
          ...goalPayload,
        }),
      });
    } catch { /* silent — /goals/draft may not exist yet */ } finally {
      setSaving(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    }
  };

  return (
    <div className="w-full space-y-6">

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

        {/* ── Left: slider controls + summary ── */}
        <div className="space-y-5">
          {children}
          <div className="grid grid-cols-2 gap-3 pt-1">
            <SummaryCard label="Total Invested"  value={fmtINR(summary.invested)} cls="bg-slate-50 border-slate-200 text-slate-700" />
            <SummaryCard label="Est. Gains"      value={fmtINR(summary.gains)}    cls="bg-emerald-50 border-emerald-100 text-emerald-700" />
            <SummaryCard label="Total Corpus"    value={fmtINR(summary.corpus)}   cls="bg-[#0B1B3E] border-transparent text-white" dark />
            <SummaryCard label="Return Multiple" value={`${summary.multiple.toFixed(2)}×`} cls="bg-amber-50 border-amber-100 text-amber-700" />
          </div>
        </div>

        {/* ── Right: stacked bar chart ── */}
        <div>
          <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-3">Year-wise Growth</p>
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={chartData} margin={{ top: 4, right: 4, bottom: 0, left: 0 }} barCategoryGap="20%">
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
              <XAxis
                dataKey="label"
                tick={{ fontSize: 9, fill: '#94a3b8' }}
                tickLine={false}
                axisLine={false}
              />
              <YAxis
                tickFormatter={fmtINR}
                tick={{ fontSize: 9, fill: '#94a3b8' }}
                tickLine={false}
                axisLine={false}
                width={54}
              />
              <Tooltip
                formatter={(v: number, name: string) => [fmtINR(v), name === 'invested' ? 'Invested' : 'Gains']}
                labelFormatter={(_: any, payload: any[]) =>
                  payload?.[0]?.payload?.yr ? `Year ${payload[0].payload.yr}` : ''
                }
                contentStyle={{ borderRadius: 12, border: '1px solid #e2e8f0', fontSize: 12 }}
              />
              <Legend
                formatter={name => name === 'invested' ? 'Invested' : 'Estimated Gains'}
                wrapperStyle={{ fontSize: 11, paddingTop: 8 }}
              />
              <Bar dataKey="invested" stackId="a" fill="#3B82F6" radius={[0, 0, 3, 3]} />
              <Bar dataKey="gains"    stackId="a" fill="#10B981" radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
          <p className="text-[10px] text-slate-400 mt-1 text-center">
            Illustrative only. Past performance is not indicative of future results.
          </p>
        </div>
      </div>

      {/* ── Save to Investor CTA ── */}
      <div className="border-t border-slate-100 pt-5">
        {userData ? (
          <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
            <div className="relative flex-1" ref={dropdownRef}>
              <button
                onClick={() => setDropdownOpen(o => !o)}
                className="w-full flex items-center justify-between gap-2 px-4 py-2.5 bg-white border border-slate-200 rounded-xl text-sm text-left hover:border-blue-300 transition-colors"
              >
                <span className={selectedInv ? 'font-medium text-slate-800' : 'text-slate-400'}>
                  {selectedInv ? selectedInv.fullName : 'Select investor…'}
                </span>
                <ChevronDown className={`w-4 h-4 text-slate-400 flex-shrink-0 transition-transform ${dropdownOpen ? 'rotate-180' : ''}`} />
              </button>
              {dropdownOpen && (
                <div className="absolute z-20 mt-1 w-full bg-white border border-slate-200 rounded-xl shadow-lg overflow-hidden">
                  <div className="p-2 border-b border-slate-100">
                    <input
                      autoFocus
                      type="text"
                      value={invSearch}
                      onChange={e => setInvSearch(e.target.value)}
                      placeholder="Search investors…"
                      className="w-full px-3 py-2 text-sm bg-slate-50 rounded-lg outline-none"
                    />
                  </div>
                  <div className="max-h-44 overflow-y-auto">
                    {filteredInvestors.length === 0 ? (
                      <p className="p-4 text-sm text-slate-400 text-center">No investors found</p>
                    ) : (
                      filteredInvestors.slice(0, 20).map(inv => (
                        <button
                          key={inv.id}
                          onClick={() => { setSelectedInv(inv); setDropdownOpen(false); setInvSearch(''); }}
                          className="w-full px-4 py-2.5 text-left hover:bg-slate-50 text-sm border-b border-slate-50 last:border-0"
                        >
                          <p className="font-medium text-slate-800">{inv.fullName}</p>
                          {inv.pan && <p className="text-[10px] text-slate-400 font-mono">{inv.pan}</p>}
                        </button>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>
            <button
              onClick={handleSave}
              disabled={!selectedInv || saving}
              className={`flex items-center justify-center gap-2 px-5 py-2.5 rounded-xl text-sm font-semibold transition-all flex-shrink-0 ${
                saved
                  ? 'bg-emerald-500 text-white'
                  : 'bg-[#0B1B3E] text-white hover:bg-[#1A3066] disabled:bg-slate-200 disabled:text-slate-400 disabled:cursor-not-allowed'
              }`}
            >
              {saved
                ? <><Check className="w-4 h-4" /> Goal Saved!</>
                : <><BookmarkPlus className="w-4 h-4" /> Save to Investor</>
              }
            </button>
          </div>
        ) : (
          <div className="flex items-center justify-between gap-4 bg-blue-50 rounded-xl p-4 border border-blue-100">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-full bg-blue-100 flex items-center justify-center flex-shrink-0">
                <Target className="w-4 h-4 text-blue-600" />
              </div>
              <div>
                <p className="text-sm font-semibold text-blue-800">Save this plan to an investor goal</p>
                <p className="text-xs text-blue-600 mt-0.5">Log in as a distributor to save goals for your investors.</p>
              </div>
            </div>
            <button
              onClick={onLogin}
              className="px-4 py-2 bg-[#0B1B3E] text-white text-sm font-semibold rounded-xl hover:bg-[#1A3066] transition-colors flex-shrink-0"
            >
              Log In
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
