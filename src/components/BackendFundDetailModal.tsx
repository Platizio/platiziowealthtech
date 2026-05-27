import React, { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import {
  Activity,
  Award,
  BarChart2,
  BookOpen,
  Calendar,
  CheckCircle2,
  FileText,
  Info,
  Layers,
  PieChart,
  Shield,
  Target,
  TrendingUp,
  Users,
  X,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import { useFocusTrap } from '../hooks/useFocusTrap';

const MISSING_VALUE = 'Currently unavailable';

const productTypeStyle: Record<string, string> = {
  MF: 'bg-blue-50 text-blue-600',
  SIF: 'bg-amber-50 text-amber-700',
  ETF: 'bg-violet-50 text-violet-700',
  BOND: 'bg-emerald-50 text-emerald-700',
  OTHER: 'bg-slate-100 text-slate-500',
};

const getAssetClass = (scheme: any) => {
  const productType = String(scheme?.productType || '').toUpperCase();
  const category = String(scheme?.category || '').toUpperCase();
  const name = String(scheme?.schemeName || '').toUpperCase();
  return productType.includes('SIF') || category.includes('SIF') || name.includes('SIF') ? 'SIF' : 'MF';
};

const parseMetadata = (metadataJson?: string) => {
  if (!metadataJson) return {};
  try {
    const parsed = JSON.parse(metadataJson);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (error) {
    console.warn('[Product Details] Unable to parse metadataJson:', error);
    return {};
  }
};

const readPath = (source: any, path: string) =>
  path.split('.').reduce((acc: any, key) => acc?.[key], source);

const firstValue = (source: any, paths: string[]) =>
  paths
    .map(path => readPath(source, path))
    .find(value => value !== undefined && value !== null && String(value).trim() !== '');

const formatValue = (value: any) => {
  if (value === undefined || value === null || String(value).trim() === '') return MISSING_VALUE;
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
};

const formatPercent = (value: any) => {
  if (value === undefined || value === null || String(value).trim() === '') return MISSING_VALUE;
  if (typeof value === 'number') return `${value}%`;
  return String(value);
};

const toNumber = (value: any) => {
  const parsed = typeof value === 'number'
    ? value
    : Number(String(value ?? '').replace(/[^0-9.-]/g, ''));
  return Number.isFinite(parsed) ? parsed : null;
};

const normalizeList = (value: any): string[] => {
  if (Array.isArray(value)) {
    return value
      .map(item => typeof item === 'string'
        ? item
        : firstValue(item, ['name', 'full_name', 'fund_manager_name', 'label', 'title', 'description', 'text']))
      .filter(Boolean)
      .map(String);
  }

  if (typeof value === 'string' && value.trim()) {
    return value.split(/\n|;/).map(item => item.trim()).filter(Boolean);
  }

  return [];
};

const normalizeManagers = (value: any) => {
  if (!Array.isArray(value)) return [];

  return value
    .map((manager: any) => ({
      name: formatValue(firstValue(manager, ['name', 'full_name', 'fund_manager_name'])),
      role: formatValue(firstValue(manager, ['role', 'designation', 'title'])),
      experience: formatValue(firstValue(manager, ['experience', 'years_experience', 'exp'])),
    }))
    .filter(manager => manager.name !== MISSING_VALUE);
};

const normalizeAllocations = (value: any) => {
  if (!Array.isArray(value)) return [];

  return value
    .map((item: any) => {
      const name = formatValue(firstValue(item, [
        'name',
        'security_name',
        'holding_name',
        'instrument',
        'sector',
        'asset',
        'category',
        'issuer',
      ]));
      const pct = toNumber(firstValue(item, [
        'pct',
        'percentage',
        'percent',
        'allocation',
        'weight',
        'net_assets_percentage',
      ]));
      return { name, pct };
    })
    .filter(item => item.name !== MISSING_VALUE || item.pct !== null);
};

const flattenMetadata = (source: any, prefix = ''): { label: string; value: string }[] => {
  if (!source || typeof source !== 'object') return [];

  return Object.entries(source).flatMap(([key, value]) => {
    const label = prefix ? `${prefix}.${key}` : key;

    if (value === undefined || value === null) return [];
    if (typeof value === 'string' && !value.trim()) return [];

    if (Array.isArray(value)) {
      const primitiveValues = value.filter(item => item === null || typeof item !== 'object');
      if (primitiveValues.length === value.length) {
        return [{ label, value: primitiveValues.map(formatValue).join(', ') }];
      }
      return [{ label, value: `${value.length} item${value.length === 1 ? '' : 's'}` }];
    }

    if (typeof value === 'object') {
      return flattenMetadata(value, label);
    }

    return [{ label, value: formatValue(value) }];
  });
};

function EmptyBackendState({ text }: { text: string }) {
  return (
    <div className="rounded-xl border border-slate-100 bg-slate-50 p-4 text-sm text-slate-500">
      {text}
    </div>
  );
}

function FieldCard({ label, value, Icon }: { key?: React.Key; label: string; value: string; Icon: React.ElementType }) {
  return (
    <div className="bg-slate-50 rounded-xl p-4 border border-slate-100">
      <div className="flex items-center gap-1.5 mb-2">
        <Icon className="w-3 h-3 text-slate-400" />
        <p className="text-[9px] uppercase font-bold text-slate-400 tracking-wider leading-snug">{label}</p>
      </div>
      <p className="text-sm font-bold text-slate-800 break-words leading-snug">{value}</p>
    </div>
  );
}

function InfoSection({ icon, title, value }: { icon: React.ReactNode; title: string; value: string }) {
  return (
    <section>
      <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
        {icon} {title}
      </h3>
      <div className="rounded-xl border border-slate-100 bg-slate-50 p-4 text-sm text-slate-600 leading-relaxed whitespace-pre-wrap">
        {value}
      </div>
    </section>
  );
}

function ListSection({
  icon,
  title,
  items,
  emptyText,
}: {
  icon: React.ReactNode;
  title: string;
  items: string[];
  emptyText: string;
}) {
  return (
    <section>
      <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
        {icon} {title}
      </h3>
      {items.length > 0 ? (
        <ul className="space-y-2.5">
          {items.map((item, index) => (
            <li key={`${item}-${index}`} className="flex items-start gap-3 text-sm text-slate-600">
              <span className="w-5 h-5 rounded-full bg-blue-50 text-blue-600 flex items-center justify-center text-[10px] font-bold flex-shrink-0 mt-0.5">
                {index + 1}
              </span>
              <span className="whitespace-pre-wrap">{item}</span>
            </li>
          ))}
        </ul>
      ) : (
        <EmptyBackendState text={emptyText} />
      )}
    </section>
  );
}

function AllocationSection({
  title,
  icon,
  rows,
  emptyText,
}: {
  title: string;
  icon: React.ReactNode;
  rows: { name: string; pct: number | null }[];
  emptyText: string;
}) {
  return (
    <section>
      <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
        {icon} {title}
      </h3>
      {rows.length > 0 ? (
        <div className="rounded-xl overflow-hidden border border-slate-200">
          <div className="grid grid-cols-[1fr,120px] bg-slate-50 px-4 py-2.5 border-b border-slate-200">
            <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Field</p>
            <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider text-right">Allocation</p>
          </div>
          {rows.map((row, index) => (
            <div key={`${row.name}-${index}`} className="grid grid-cols-[1fr,120px] px-4 py-3 border-b border-slate-100 last:border-0">
              <p className="text-sm text-slate-700 font-medium break-words">{row.name}</p>
              <div className="flex items-center justify-end gap-2">
                {row.pct !== null && (
                  <div className="w-14 bg-slate-100 rounded-full h-1.5 overflow-hidden">
                    <div className="h-full bg-blue-400 rounded-full" style={{ width: `${Math.min(100, Math.max(0, row.pct))}%` }} />
                  </div>
                )}
                <p className="text-sm font-bold text-slate-700 w-12 text-right">
                  {row.pct === null ? MISSING_VALUE : `${row.pct}%`}
                </p>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <EmptyBackendState text={emptyText} />
      )}
    </section>
  );
}

export default function BackendFundDetailModal({
  fund,
  onClose,
  onInvest,
}: {
  fund: any;
  onClose: () => void;
  onInvest: (fundForOrder: any) => void;
}) {
  const dialogRef = useFocusTrap<HTMLDivElement>(true);
  const [activeTab, setActiveTab] = useState<'overview' | 'performance' | 'portfolio' | 'details'>('overview');
  const [scheme, setScheme] = useState<any>(fund);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setScheme(fund);
    setLoading(true);
    setError('');

    apiFetch(`/products/schemes/${fund.id}`)
      .then(async response => {
        const body = await response.json().catch(() => null);
        console.log('[Product Details] GET /products/schemes/{id}', {
          schemeId: fund.id,
          status: response.status,
          ok: response.ok,
          body,
        });
        if (!response.ok) throw new Error(body?.message || `HTTP ${response.status}`);
        if (!cancelled) setScheme(body || fund);
      })
      .catch(fetchError => {
        console.error('Failed to fetch product scheme detail:', fetchError);
        if (!cancelled) setError(MISSING_VALUE);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [fund]);

  const metadata = parseMetadata(scheme?.metadataJson);
  const hasMetadata = Object.keys(metadata).length > 0;
  const textFromMeta = (paths: string[]) => formatValue(firstValue(metadata, paths));
  const rawMetaRows = flattenMetadata(metadata).slice(0, 60);

  const nav = textFromMeta(['nav', 'latest_nav', 'current_nav', 'mf_scheme.latest_nav', 'mf_scheme_plan.latest_nav']);
  const aum = textFromMeta(['aum', 'assets_under_management', 'asset_under_management', 'mf_scheme.aum']);
  const riskLevel = textFromMeta(['risk', 'risk_level', 'riskLevel', 'mf_scheme.risk_level', 'riskometer']);
  const objective = textFromMeta(['objective', 'investment_objective', 'scheme_objective', 'mf_scheme.investment_objective']);
  const strategy = textFromMeta(['strategy', 'investment_strategy', 'fund_discipline', 'scheme_strategy']);
  const benchmark = textFromMeta(['benchmark', 'benchmark_name', 'benchmark_index', 'mf_scheme.benchmark']);
  const exitLoad = textFromMeta(['exit_load', 'exitLoad', 'load.exit', 'mf_scheme.exit_load']);
  const entryLoad = textFromMeta(['entry_load', 'entryLoad', 'load.entry']);
  const expenseRatio = textFromMeta(['expense_ratio', 'ter', 'total_expense_ratio', 'mf_scheme.expense_ratio']);
  const minSip = textFromMeta(['min_sip', 'minimum_sip_amount', 'sip_minimum_installment_amount', 'minSip']);
  const minLumpsum = textFromMeta(['min_lumpsum', 'minimum_purchase_amount', 'min_initial_investment', 'minInvestment']);
  const allotmentDate = textFromMeta(['allotment_date', 'inception_date', 'inceptionDate', 'mf_scheme.inception_date']);

  const returnRows = [
    { label: '1M', value: formatPercent(firstValue(metadata, ['returns.1m', 'returns.1M', 'one_month_return', 'return_1m'])) },
    { label: '3M', value: formatPercent(firstValue(metadata, ['returns.3m', 'returns.3M', 'three_month_return', 'return_3m'])) },
    { label: 'YTD', value: formatPercent(firstValue(metadata, ['returns.ytd', 'ytd_return', 'return_ytd'])) },
    { label: '1Y', value: formatPercent(firstValue(metadata, ['returns.1y', 'returns.1Y', 'one_year_return', 'return_1y'])) },
    { label: '3Y', value: formatPercent(firstValue(metadata, ['returns.3y', 'returns.3Y', 'three_year_return', 'return_3y'])) },
    { label: '5Y', value: formatPercent(firstValue(metadata, ['returns.5y', 'returns.5Y', 'five_year_return', 'return_5y'])) },
    { label: 'Since Inception', value: formatPercent(firstValue(metadata, ['returns.inception', 'since_inception_return', 'inception_return'])) },
  ];
  const availableReturnRows = returnRows.filter(row => row.value !== MISSING_VALUE && toNumber(row.value) !== null);
  const maxReturn = Math.max(1, ...availableReturnRows.map(row => Math.abs(toNumber(row.value) || 0)));

  const holdings = normalizeAllocations(firstValue(metadata, [
    'holdings',
    'top_holdings',
    'portfolio.holdings',
    'portfolio.top_holdings',
    'securities',
  ]));
  const sectors = normalizeAllocations(firstValue(metadata, [
    'sectors',
    'sector_allocation',
    'portfolio.sectors',
    'portfolio.sector_allocation',
    'asset_allocation',
  ]));
  const managers = normalizeManagers(firstValue(metadata, [
    'fund_managers',
    'fundManagers',
    'managers',
    'mf_scheme.fund_managers',
  ]));
  const features = normalizeList(firstValue(metadata, [
    'features',
    'why_invest',
    'whyInvest',
    'highlights',
    'suitability',
  ]));
  const taxDetails = normalizeList(firstValue(metadata, [
    'tax',
    'tax_details',
    'taxation',
  ]));

  const detailCards = [
    { label: 'Product Type', value: formatValue(scheme?.productType), Icon: Layers },
    { label: 'Category', value: formatValue(scheme?.category), Icon: PieChart },
    { label: 'AMC', value: formatValue(scheme?.amcName), Icon: Award },
    { label: 'Scheme Code', value: formatValue(scheme?.externalSchemeCode), Icon: FileText },
    { label: 'ISIN', value: formatValue(scheme?.externalIsin), Icon: FileText },
    { label: 'Active', value: formatValue(scheme?.active), Icon: CheckCircle2 },
    { label: 'Latest NAV', value: nav, Icon: Activity },
    { label: 'AUM', value: aum, Icon: BarChart2 },
    { label: 'Benchmark', value: benchmark, Icon: TrendingUp },
    { label: 'Expense Ratio', value: expenseRatio, Icon: Activity },
    { label: 'Minimum SIP', value: minSip, Icon: Target },
    { label: 'Minimum Lumpsum', value: minLumpsum, Icon: Target },
    { label: 'Entry Load', value: entryLoad, Icon: FileText },
    { label: 'Exit Load', value: exitLoad, Icon: FileText },
    { label: 'Allotment / Inception', value: allotmentDate, Icon: Calendar },
    { label: 'Risk Level', value: riskLevel, Icon: Shield },
  ];

  const tabs = [
    { id: 'overview' as const, label: 'Overview', Icon: BookOpen },
    { id: 'performance' as const, label: 'Performance', Icon: TrendingUp },
    { id: 'portfolio' as const, label: 'Portfolio', Icon: PieChart },
    { id: 'details' as const, label: 'More Details', Icon: FileText },
  ];

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="fund-detail-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
    >
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/50 backdrop-blur-sm"
      />
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 20 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 20 }}
        transition={{ type: 'spring', stiffness: 300, damping: 30 }}
        className="relative z-10 bg-white w-full max-w-4xl max-h-[92vh] rounded-3xl shadow-2xl overflow-hidden flex flex-col"
      >
        <div className="bg-[#0B1B3E] px-8 pt-7 pb-0 flex-shrink-0">
          <div className="flex justify-between items-start gap-4 mb-4">
            <div className="flex gap-2 flex-wrap">
              <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${productTypeStyle[getAssetClass(scheme)] || productTypeStyle.OTHER}`}>
                {getAssetClass(scheme)}
              </span>
              <span className="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-white/15 text-white/80">
                Fund details
              </span>
            </div>
            <button onClick={onClose} aria-label="Close dialog" className="p-2 text-white/60 hover:text-white bg-white/10 rounded-full transition-colors flex-shrink-0">
              <X className="w-4 h-4" aria-hidden="true" />
            </button>
          </div>

          <h2 id="fund-detail-title" className="text-xl font-bold text-white leading-snug mb-1">
            {formatValue(scheme?.schemeName)}
          </h2>
          <p className="text-white/55 text-sm mb-5">{formatValue(scheme?.amcName)}</p>

          {(loading || error || !hasMetadata) && (
            <div className={`mb-4 rounded-xl px-4 py-2 text-xs font-semibold ${
              error ? 'bg-amber-100 text-amber-800' : 'bg-white/10 text-white/70'
            }`}>
              {error || (loading ? 'Fetching latest fund details...' : MISSING_VALUE)}
            </div>
          )}

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-5">
            {[
              { label: 'Latest NAV', value: nav, color: 'text-white' },
              { label: 'AUM', value: aum, color: 'text-white' },
              { label: '1Y Return', value: returnRows.find(row => row.label === '1Y')?.value || MISSING_VALUE, color: 'text-emerald-400' },
              { label: 'Risk Level', value: riskLevel, color: 'text-amber-400' },
            ].map(item => (
              <div key={item.label} className="bg-white/10 rounded-xl px-4 py-3">
                <p className="text-white/50 text-[9px] uppercase font-bold tracking-wider mb-1">{item.label}</p>
                <p className={`text-sm font-bold leading-snug break-words ${item.color}`}>{item.value}</p>
              </div>
            ))}
          </div>

          <div className="flex gap-1 bg-white/10 rounded-xl p-1">
            {tabs.map(({ id, label, Icon }) => (
              <button
                key={id}
                onClick={() => setActiveTab(id)}
                className={`flex-1 flex items-center justify-center gap-1.5 py-2 text-xs font-semibold rounded-lg transition-all ${
                  activeTab === id ? 'bg-white text-[#0B1B3E] shadow-sm' : 'text-white/60 hover:text-white'
                }`}
              >
                <Icon className="w-3.5 h-3.5" aria-hidden="true" />
                <span className="hidden sm:inline">{label}</span>
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 overflow-y-auto">
          <AnimatePresence mode="wait">
            {activeTab === 'overview' && (
              <motion.div key="overview" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">
                <InfoSection icon={<Target className="w-4 h-4 text-blue-500" />} title="Investment Objective" value={objective} />
                <InfoSection icon={<Info className="w-4 h-4 text-slate-500" />} title="Investment Strategy" value={strategy} />
                <ListSection
                  icon={<Award className="w-4 h-4 text-amber-500" />}
                  title="Highlights"
                  items={features}
                  emptyText={MISSING_VALUE}
                />
                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Users className="w-4 h-4 text-purple-500" /> Fund Managers
                  </h3>
                  {managers.length > 0 ? (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      {managers.map(manager => (
                        <div key={manager.name} className="bg-slate-50 rounded-xl p-4 border border-slate-100">
                          <p className="text-sm font-semibold text-slate-800">{manager.name}</p>
                          <p className="text-xs text-slate-500 mt-1">{manager.role}</p>
                          <p className="text-xs text-slate-400 mt-1">{manager.experience}</p>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <EmptyBackendState text={MISSING_VALUE} />
                  )}
                </section>
              </motion.div>
            )}

            {activeTab === 'performance' && (
              <motion.div key="performance" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">
                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <TrendingUp className="w-4 h-4 text-emerald-500" /> Returns
                  </h3>
                  <div className="rounded-xl overflow-hidden border border-slate-200">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="bg-[#0B1B3E] text-white text-xs">
                          <th className="text-left px-4 py-3 font-semibold">Period</th>
                          <th className="text-right px-4 py-3 font-semibold">Return</th>
                        </tr>
                      </thead>
                      <tbody>
                        {returnRows.map((row, index) => (
                          <tr key={row.label} className={`border-b border-slate-100 last:border-0 ${index % 2 === 0 ? 'bg-white' : 'bg-slate-50/60'}`}>
                            <td className="px-4 py-3 font-semibold text-slate-700">{row.label}</td>
                            <td className="px-4 py-3 text-right font-bold text-slate-700">{row.value}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <BarChart2 className="w-4 h-4 text-blue-500" /> Return Chart
                  </h3>
                  {availableReturnRows.length > 0 ? (
                    <div className="space-y-3">
                      {availableReturnRows.map(row => {
                        const pct = Math.abs(toNumber(row.value) || 0);
                        return (
                          <div key={row.label} className="flex items-center gap-3">
                            <p className="text-xs font-semibold text-slate-500 w-24 text-right flex-shrink-0">{row.label}</p>
                            <div className="flex-1 bg-slate-100 rounded-full h-7 overflow-hidden">
                              <motion.div
                                initial={{ width: 0 }}
                                animate={{ width: `${Math.min(100, (pct / maxReturn) * 100)}%` }}
                                transition={{ duration: 0.7, ease: 'easeOut' }}
                                className="h-full bg-gradient-to-r from-[#0B1B3E] to-blue-500 rounded-full flex items-center justify-end pr-2.5"
                              >
                                <span className="text-[10px] font-bold text-white">{row.value}</span>
                              </motion.div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  ) : (
                    <EmptyBackendState text={MISSING_VALUE} />
                  )}
                </section>
              </motion.div>
            )}

            {activeTab === 'portfolio' && (
              <motion.div key="portfolio" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">
                <AllocationSection
                  title="Portfolio Holdings"
                  icon={<Layers className="w-4 h-4 text-blue-500" />}
                  rows={holdings}
                  emptyText={MISSING_VALUE}
                />
                <AllocationSection
                  title="Sector / Asset Allocation"
                  icon={<PieChart className="w-4 h-4 text-purple-500" />}
                  rows={sectors}
                  emptyText={MISSING_VALUE}
                />
              </motion.div>
            )}

            {activeTab === 'details' && (
              <motion.div key="details" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">
                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <FileText className="w-4 h-4 text-slate-500" /> Scheme Fields
                  </h3>
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                    {detailCards.map(card => (
                      <FieldCard key={card.label} {...card} />
                    ))}
                  </div>
                </section>

                <ListSection
                  icon={<BookOpen className="w-4 h-4 text-orange-500" />}
                  title="Tax Details"
                  items={taxDetails}
                  emptyText={MISSING_VALUE}
                />

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <Activity className="w-4 h-4 text-blue-500" /> Metadata
                  </h3>
                  {rawMetaRows.length > 0 ? (
                    <div className="rounded-xl overflow-hidden border border-slate-200">
                      {rawMetaRows.map((row, index) => (
                        <div key={`${row.label}-${index}`} className="grid grid-cols-1 sm:grid-cols-[220px,1fr] gap-2 px-4 py-3 border-b border-slate-100 last:border-0">
                          <p className="text-xs font-bold text-slate-500 break-words">{row.label}</p>
                          <p className="text-sm text-slate-700 break-words">{row.value}</p>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <EmptyBackendState text={MISSING_VALUE} />
                  )}
                </section>
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        <div className="px-8 py-4 border-t border-slate-100 bg-white flex-shrink-0 flex items-center gap-3">
          <button onClick={onClose} className="px-5 py-2.5 text-sm font-medium bg-slate-100 text-slate-600 rounded-xl hover:bg-slate-200 transition-colors">
            Close
          </button>
          <button
            onClick={() => onInvest(scheme)}
            className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-semibold rounded-xl hover:bg-[#1A3066] transition-colors"
          >
            Invest Now
          </button>
        </div>
      </motion.div>
    </div>
  );
}
