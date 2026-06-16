import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Info, CheckCircle2, ChevronRight, X, Search, RefreshCw, AlertCircle,
  TrendingUp, BarChart2, Layers, FileText, Award, Shield, Calendar, Clock,
  Target, Users, BookOpen, PieChart, Activity, Eye,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import InvestorActionLink from '../components/InvestorActionLink';
import { formatOrderStatusLabel } from '../utils/investorAction';
import BackendFundDetailModal from '../components/BackendFundDetailModal';
import Pagination from '../components/Pagination';
import { useDebounce } from '../hooks/useDebounce';
import { useFocusTrap } from '../hooks/useFocusTrap';
import { getPageContent, getPageMeta, isPagePayload } from '../utils/pagination';
import { productSchemeKey, isPersistedSchemeId } from '../utils/productSchemeKey';
import {
  formatAmcDisplayName,
  formatSchemeDisplayName,
  isTransactionReadyScheme,
  readSchemeMinSip,
} from '../utils/orderableScheme';

// ─── Colour maps ──────────────────────────────────────────────────────────────
const categoryStyle: Record<string, string> = {
  Equity:   'bg-blue-50 text-blue-600',
  Debt:     'bg-emerald-50 text-emerald-600',
  ELSS:     'bg-orange-50 text-orange-600',
  Hybrid:   'bg-purple-50 text-purple-600',
  Liquid:   'bg-cyan-50 text-cyan-600',
  Other:    'bg-slate-100 text-slate-600',
};

const productTypeStyle: Record<string, string> = {
  MF:    'bg-blue-50 text-blue-600',
  SIF:   'bg-amber-50 text-amber-700',
  ETF:   'bg-violet-50 text-violet-700',
  BOND:  'bg-emerald-50 text-emerald-700',
  OTHER: 'bg-slate-100 text-slate-500',
};

const LIVE_SCHEME_SYNC_KEY = 'platizio:fundSchemes:lastLiveSync:v2';
const LIVE_SCHEME_SYNC_BACKOFF_KEY = 'platizio:fundSchemes:liveSyncBackoffUntil:v2';
const LIVE_SCHEME_SYNC_FAILURE_BACKOFF_MS = 15 * 60 * 1000;
const DEFAULT_PAGE_SIZE = 12;
const ASSET_FILTERS = ['All', 'MF', 'SIF'] as const;
type AssetFilter = typeof ASSET_FILTERS[number];
const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';

const getAssetClass = (scheme: any): Exclude<AssetFilter, 'All'> => {
  const productType = String(scheme.productType || '').toUpperCase();
  const category = String(scheme.category || '').toUpperCase();
  const name = String(scheme.schemeName || '').toUpperCase();
  return productType.includes('SIF') || category.includes('SIF') || name.includes('SIF') ? 'SIF' : 'MF';
};

// ─── Ledger component ─────────────────────────────────────────────────────────
export default function Ledger({ userData }: { userData?: any }) {
  const [schemes, setSchemes]           = useState<any[]>([]);
  const [loading, setLoading]           = useState(true);
  const [refreshing, setRefreshing]     = useState(false);
  const [error, setError]               = useState('');
  const [assetFilter, setAssetFilter]   = useState<AssetFilter>('All');
  const [categoryFilter, setCategoryFilter] = useState('All');
  const [typeFilter, setTypeFilter]     = useState('All');
  const [search, setSearch]             = useState('');
  const debouncedSearch                 = useDebounce(search, 300);
  const [page, setPage]                 = useState(0);
  const [size, setSize]                 = useState(DEFAULT_PAGE_SIZE);
  const [totalPages, setTotalPages]     = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [backendPaged, setBackendPaged] = useState(false);
  const [investModal, setInvestModal]   = useState<any | null>(null);
  const [detailModal, setDetailModal]   = useState<any | null>(null);
  const refreshInFlightRef = useRef(false);

  const readJsonSafely = async (response: Response) => {
    const text = await response.text();
    if (!text) return null;
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  };

  const liveSyncBackoffRemainingMs = () => {
    const backoffUntil = Number(window.localStorage.getItem(LIVE_SCHEME_SYNC_BACKOFF_KEY) || 0);
    return Math.max(backoffUntil - Date.now(), 0);
  };

  const markLiveSyncBackoff = () => {
    window.localStorage.setItem(
      LIVE_SCHEME_SYNC_BACKOFF_KEY,
      String(Date.now() + LIVE_SCHEME_SYNC_FAILURE_BACKOFF_MS),
    );
  };

  const applySchemePage = (data: unknown, pageContent: any[]) => {
    const isPaged = isPagePayload(data);
    const meta = getPageMeta(data, pageContent.length);
    const active = pageContent.filter((s: any) => s.active !== false);
    setBackendPaged(isPaged);
    setSchemes(active);
    setTotalPages(isPaged ? meta.totalPages : Math.max(Math.ceil(active.length / Math.max(size, 1)), 1));
    setTotalElements(isPaged ? meta.totalElements : active.length);
  };

  const fetchLocalSchemeFallback = async (params: URLSearchParams) => {
    const fallbackParams = new URLSearchParams(params);
    fallbackParams.set('local', 'true');
    console.log('fallback_request=', 'GET /api/v1/products/schemes/page?local=true');
    const cachedRes = await apiFetch(`/products/schemes/page?${fallbackParams.toString()}`);
    const cachedData = cachedRes.ok ? await readJsonSafely(cachedRes) : [];
    const cachedContent = getPageContent(cachedData);
    applySchemePage(cachedData, cachedContent);
    return cachedContent.filter((s: any) => s.active !== false);
  };

  const fetchSchemes = async (options?: { forceRefresh?: boolean }) => {
    const forceRefresh = options?.forceRefresh ?? false;
    const backoffRemainingMs = forceRefresh ? 0 : liveSyncBackoffRemainingMs();

    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    if (forceRefresh) setRefreshing(true);
    else setLoading(true);
    setError(
      backoffRemainingMs > 0
        ? `Live POA catalogue is cooling down. Showing cached products; try again in ${Math.ceil(backoffRemainingMs / 60000)} min.`
        : '',
    );

    const requestPage = forceRefresh ? 0 : page;
    const params = new URLSearchParams({ page: String(requestPage), size: String(size) });
    if (debouncedSearch.trim()) params.set('query', debouncedSearch.trim());
    if (assetFilter !== 'All') params.set('assetClass', assetFilter);
    if (categoryFilter !== 'All') params.set('category', categoryFilter);
    if (typeFilter !== 'All') params.set('productType', typeFilter);

    console.groupCollapsed('[Cybrilla Workflow] Display fund schemes');
    console.log('frontend_route=', '/distributor/ledger');
    console.log('frontend_request=', 'GET /api/v1/products/schemes/page (POA mf_scheme_plans live browse)');
    console.log(
      'backend_expected_external_call=',
      'GET https://s.finprim.com/v2/mf_scheme_plans/cybrillapoa (paginated); upserts POA-orderable rows',
    );
    console.log('frontend_note=', 'Browser calls Platizio backend only. Backend uses the currently valid server-side bearer token and never exposes credentials to the frontend.');

    try {
      if (backoffRemainingMs > 0) {
        const cachedActive = await fetchLocalSchemeFallback(params);
        if (cachedActive.length === 0) {
          setError('Failed to load products. Please try again later.');
        }
        console.log('workflow_status=', 'completed_from_cache');
        return;
      }

      const res = await apiFetch(`/products/schemes/page?${params.toString()}`);
      const data = await readJsonSafely(res);
      const pageContent = getPageContent(data);
      console.log('frontend_response=', {
        status: res.status,
        ok: res.ok,
        count: pageContent.length,
        sample: pageContent.slice(0, 3),
      });

      if (!res.ok) throw new Error(typeof data === 'string' ? data : data?.message || `HTTP ${res.status}`);
      applySchemePage(data, pageContent);
      window.localStorage.setItem(LIVE_SCHEME_SYNC_KEY, String(Date.now()));
      window.localStorage.removeItem(LIVE_SCHEME_SYNC_BACKOFF_KEY);
      if (forceRefresh) setPage(0);
      console.log('workflow_status=', 'completed');
    } catch (e: any) {
      console.error('Error fetching product schemes:', e);
      console.error('workflow_status=', 'failed');
      markLiveSyncBackoff();
      try {
        const cachedActive = await fetchLocalSchemeFallback(params);
        if (cachedActive.length > 0) {
          setError('Showing locally cached products because the live POA catalogue is temporarily unavailable. Invest Now may be disabled until refresh succeeds.');
        } else {
          setError('Failed to load products from Cybrilla. Please try again later.');
        }
      } catch (fallbackError) {
        console.error('Fallback product scheme fetch failed:', fallbackError);
        setError('Failed to load products. Please try again later.');
      }
    } finally {
      console.groupEnd();
      refreshInFlightRef.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchSchemes();
  }, [page, size, debouncedSearch, assetFilter, categoryFilter, typeFilter]);

  useEffect(() => {
    setPage(0);
  }, [assetFilter, categoryFilter, typeFilter, debouncedSearch]);

  // Derived filter options
  const categories = ['All', ...Array.from(new Set(schemes.map(s => s.category).filter(Boolean)))];
  const types      = ['All', ...Array.from(new Set(schemes.map(s => s.productType).filter(Boolean)))];
  const assetCounts = ASSET_FILTERS.reduce((counts, filter) => {
    counts[filter] = filter === 'All'
      ? schemes.length
      : schemes.filter(s => getAssetClass(s) === filter).length;
    return counts;
  }, {} as Record<AssetFilter, number>);

  const filtered = schemes.filter(s => {
    const name = s.schemeName || '';
    const amc  = s.amcName   || '';
    const code = s.externalSchemeCode || '';
    const searchTerm = debouncedSearch.trim().toLowerCase();
    const matchSearch   = !searchTerm
      || name.toLowerCase().includes(searchTerm)
      || amc.toLowerCase().includes(searchTerm)
      || code.toLowerCase().includes(searchTerm);
    const matchAsset    = assetFilter    === 'All' || getAssetClass(s) === assetFilter;
    const matchCategory = categoryFilter === 'All' || s.category   === categoryFilter;
    const matchType     = typeFilter      === 'All' || s.productType === typeFilter;
    return matchSearch && matchAsset && matchCategory && matchType;
  });

  const filtersActive = assetFilter !== 'All' || categoryFilter !== 'All' || typeFilter !== 'All' || debouncedSearch.trim() !== '';
  const hasClientOnlyFilters = assetFilter !== 'All' || categoryFilter !== 'All' || typeFilter !== 'All';
  const effectiveTotalElements = backendPaged && !hasClientOnlyFilters ? totalElements : filtered.length;
  const effectiveTotalPages = backendPaged
    ? (hasClientOnlyFilters ? Math.max(1, Math.ceil(effectiveTotalElements / Math.max(size, 1))) : totalPages)
    : Math.max(1, Math.ceil(effectiveTotalElements / Math.max(size, 1)));
  const currentPage = Math.min(page, Math.max(effectiveTotalPages - 1, 0));
  const pageStart = currentPage * size;
  const paginated = backendPaged ? filtered : filtered.slice(pageStart, pageStart + size);

  const clearFilters = () => {
    setAssetFilter('All');
    setCategoryFilter('All');
    setTypeFilter('All');
    setSearch('');
    setPage(0);
  };

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8">
      {/* Header */}
      <div className="flex justify-between items-start mb-6">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Product Catalog</h1>
          <p className="text-slate-500 text-sm mt-1">Browse available funds and execute investments</p>
        </div>
        <div className="flex items-center gap-3">
          <p className="text-xs text-slate-500">
            <span className="font-semibold text-slate-700">{effectiveTotalElements}</span> products available
          </p>
          <button
            onClick={() => fetchSchemes({ forceRefresh: true })}
            disabled={refreshing}
            className="flex items-center gap-2 px-3 py-2 text-xs font-semibold bg-white border border-slate-200 text-slate-600 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} />
            {refreshing ? 'Syncing...' : 'Sync from Cybrilla'}
          </button>
        </div>
      </div>

      {/* Search + filter bar */}
      <div className="flex flex-wrap gap-3 mb-6 items-center">
        <div className="flex bg-white border border-slate-200 rounded-lg p-1">
          {ASSET_FILTERS.map(filter => (
            <button
              key={filter}
              onClick={() => setAssetFilter(filter)}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                assetFilter === filter
                  ? 'bg-[#0B1B3E] text-white'
                  : 'text-slate-600 hover:bg-slate-50'
              }`}
            >
              <span>{filter === 'All' ? 'All' : filter}</span>
              <span className={`text-[10px] ${assetFilter === filter ? 'text-white/75' : 'text-slate-400'}`}>
                {assetCounts[filter]}
              </span>
            </button>
          ))}
        </div>
        <div className="relative flex-1 min-w-[220px] max-w-sm">
          <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by fund name, AMC, or code…"
            className="w-full pl-9 pr-4 py-2 text-sm bg-white border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
          />
        </div>
        {/* Category pills */}
        <div className="flex gap-2 flex-wrap">
          {categories.map(cat => (
            <button
              key={cat}
              onClick={() => setCategoryFilter(cat)}
              className={`px-3 py-1.5 text-xs font-semibold rounded-full border transition-colors ${
                categoryFilter === cat
                  ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]'
                  : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300 hover:bg-slate-50'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>
        {types.length > 1 && (
          <select
            value={typeFilter}
            onChange={e => setTypeFilter(e.target.value)}
            className="text-xs font-semibold bg-white border border-slate-200 rounded-lg px-3 py-2 outline-none cursor-pointer"
          >
            {types.map(t => <option key={t} value={t}>{t === 'All' ? 'All Types' : t}</option>)}
          </select>
        )}
      </div>

      {/* States: loading / error / empty / grid */}
      {loading ? (
        <div className="py-24 text-center text-slate-500">
          <div className="animate-spin w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
          Loading product schemes…
        </div>
      ) : error ? (
        <div className="py-24 text-center">
          <AlertCircle className="w-12 h-12 text-red-300 mx-auto mb-4" />
          <p className="font-semibold text-slate-700 mb-2">{error}</p>
          <button
            onClick={() => fetchSchemes({ forceRefresh: true })}
            className="text-sm text-blue-600 hover:underline font-medium"
          >Try again</button>
        </div>
      ) : paginated.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-24 text-slate-400">
          <div className="w-14 h-14 rounded-2xl bg-slate-100 flex items-center justify-center mb-4">
            <Info className="w-6 h-6 text-slate-300" />
          </div>
          <p className="text-base font-semibold text-slate-500">
            {filtersActive ? 'No products match these filters' : 'No products available'}
          </p>
          <p className="text-sm mt-1">
            {filtersActive
              ? 'Try a different MF/SIF, category, type, or search filter.'
              : 'No products found. Sync from Cybrilla to load the live fund catalogue.'}
          </p>
          <button
            onClick={() => {
              if (filtersActive) clearFilters();
              else fetchSchemes({ forceRefresh: true });
            }}
            disabled={!filtersActive && refreshing}
            className="mt-5 flex items-center gap-2 px-4 py-2 text-sm font-semibold bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors disabled:opacity-50"
          >
            {filtersActive ? (
              <X className="w-4 h-4" />
            ) : (
              <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
            )}
            {filtersActive ? 'Clear filters' : refreshing ? 'Syncing...' : 'Sync from Cybrilla'}
          </button>
        </div>
      ) : (
        <>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {paginated.map((scheme, index) => {
            const assetClass = getAssetClass(scheme);
            const catCls  = categoryStyle[scheme.category]    || categoryStyle['Other'];
            const typeCls = productTypeStyle[scheme.productType] || productTypeStyle[assetClass] || productTypeStyle['OTHER'];
            return (
              <div
                key={productSchemeKey(scheme, index)}
                className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200 flex flex-col group relative overflow-hidden transition-all hover:shadow-md"
              >
                {/* Badges */}
                <div className="flex justify-between items-start mb-4">
                  <div className="flex gap-1.5 flex-wrap">
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${productTypeStyle[assetClass]}`}>
                      {assetClass}
                    </span>
                    {scheme.productType && scheme.productType !== assetClass && (
                      <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${typeCls}`}>
                        {scheme.productType}
                      </span>
                    )}
                    {scheme.category && (
                      <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${catCls}`}>
                        {scheme.category}
                      </span>
                    )}
                  </div>
                  {scheme.externalIsin && (
                    <span className="text-[10px] font-mono text-slate-400 flex-shrink-0">{scheme.externalIsin}</span>
                  )}
                </div>

                {/* Name + AMC */}
                <h3 className="font-semibold text-slate-800 mb-1 leading-snug text-sm">{formatSchemeDisplayName(scheme)}</h3>
                <p className="text-xs text-slate-400 mb-5">{formatAmcDisplayName(scheme)}</p>

                {/* Scheme code */}
                <div className="mt-auto pb-4">
                  <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">Scheme Code</p>
                  <p className="font-mono text-xs font-medium text-slate-600">{scheme.externalSchemeCode || '—'}</p>
                </div>

                {/* CTA buttons */}
                <div className="flex gap-2 pt-3 border-t border-slate-100">
                  <button
                    onClick={() => setDetailModal(scheme)}
                    className="flex-1 flex items-center justify-center gap-1.5 py-2 border border-[#0B1B3E] text-[#0B1B3E] text-xs font-semibold rounded-lg hover:bg-[#0B1B3E] hover:text-white transition-colors"
                  >
                    <Eye className="w-3.5 h-3.5" />
                    View Details
                  </button>
                  <button
                    onClick={() => setInvestModal(scheme)}
                    disabled={!isTransactionReadyScheme(scheme)}
                    title={
                      isTransactionReadyScheme(scheme)
                        ? 'Place SIP or lumpsum order'
                        : 'Fund needs a saved POA catalogue id and ISIN (refresh catalogue if missing)'
                    }
                    className="flex-1 py-2 bg-[#0B1B3E] text-white text-xs font-semibold rounded-lg hover:bg-[#1A3066] transition-colors disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    Invest Now
                  </button>
                </div>
              </div>
            );
          })}
        </div>
        {effectiveTotalElements > size && (
          <div className="mt-6 rounded-2xl border border-slate-200 bg-white shadow-sm">
            <Pagination
              page={currentPage}
              size={size}
              totalPages={effectiveTotalPages}
              totalElements={effectiveTotalElements}
              onPageChange={setPage}
              onSizeChange={nextSize => { setSize(nextSize); setPage(0); }}
            />
          </div>
        )}
        </>
      )}

      {/* Fund Detail modal */}
      <AnimatePresence>
        {detailModal && (
          <BackendFundDetailModal
            fund={detailModal}
            onClose={() => setDetailModal(null)}
            onInvest={(fundForOrder) => { setInvestModal(fundForOrder); setDetailModal(null); }}
          />
        )}
      </AnimatePresence>

      {/* Transaction modal */}
      <AnimatePresence>
        {investModal && (
          <TransactionModal fund={investModal} userData={userData} onClose={() => setInvestModal(null)} />
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Fund Detail Modal ────────────────────────────────────────────────────────
function FundDetailModal({ fund, onClose, onInvest }: { fund: any; onClose: () => void; onInvest: (fundForOrder: any) => void }) {
  const dialogRef = useFocusTrap<HTMLDivElement>(true);
  const [activeTab, setActiveTab] = useState<'overview' | 'performance' | 'portfolio' | 'details'>('overview');
  const [schemeDetail, setSchemeDetail] = useState<any>(fund);
  const [detailLoading, setDetailLoading] = useState(true);
  const [detailError, setDetailError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setSchemeDetail(fund);
    setDetailLoading(true);
    setDetailError('');

    if (!isPersistedSchemeId(fund?.id)) {
      setDetailLoading(false);
      return () => { cancelled = true; };
    }

    apiFetch(`/products/schemes/${fund.id}`)
      .then(async res => {
        const data = await res.json().catch(() => null);
        if (!res.ok) throw new Error(data?.message || `HTTP ${res.status}`);
        if (!cancelled) setSchemeDetail(data || fund);
      })
      .catch(err => {
        console.error('Failed to fetch product scheme detail:', err);
        if (!cancelled) setDetailError('Currently unavailable');
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });

    return () => { cancelled = true; };
  }, [fund]);

  const displayFund = schemeDetail || fund;
  const meta = (() => { try { return displayFund.metadataJson ? JSON.parse(displayFund.metadataJson) : {}; } catch { return {}; } })();
  const metaPath = (path: string) => path.split('.').reduce((acc: any, key) => acc?.[key], meta);
  const firstValue = (...values: any[]) => values.find(value => value !== undefined && value !== null && String(value).trim() !== '');
  const backendValue = (...paths: string[]) => firstValue(...paths.map(path => metaPath(path)));
  const formatBackendPercent = (value: any, fallback: string) => {
    const raw = firstValue(value);
    if (raw === undefined) return fallback;
    return typeof raw === 'number' ? `${raw}%` : String(raw);
  };
  const notAvailable = 'Currently unavailable';
  const toNumber = (value: any) => {
    const parsed = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[^0-9.-]/g, ''));
    return Number.isFinite(parsed) ? parsed : null;
  };
  const normalizeTextList = (value: any): string[] => {
    if (Array.isArray(value)) {
      return value
        .map(item => typeof item === 'string' ? item : firstValue(item?.text, item?.label, item?.description, item?.name))
        .filter(Boolean)
        .map(String);
    }
    if (typeof value === 'string' && value.trim()) {
      return value.split(/\n|;/).map(item => item.trim()).filter(Boolean);
    }
    return [];
  };
  const normalizeFundManagers = (value: any) => {
    if (!Array.isArray(value)) return [];
    return value.map((manager: any) => ({
      name: String(firstValue(manager.name, manager.full_name, manager.fund_manager_name) || 'N/A'),
      role: String(firstValue(manager.role, manager.designation, manager.title) || ''),
      exp: String(firstValue(manager.experience, manager.years_experience, manager.exp) || ''),
    })).filter((manager: any) => manager.name !== 'N/A');
  };

  const category = (displayFund.category || '').toLowerCase();
  const isEquity  = category.includes('equity') || category === 'elss' || category.includes('strategic') || category.includes('multi');
  const isDebt    = category.includes('debt') || category.includes('liquid') || category.includes('bond');
  const isHybrid  = category.includes('hybrid') || category.includes('balanced');
  const seed       = (displayFund.externalSchemeCode || displayFund.id || 'X').toString().split('').reduce((acc: number, char: string) => acc + char.charCodeAt(0), 0);
  const pr         = (min: number, max: number, offset = 0) => min + ((seed + offset) % Math.max(1, max - min));
  const pd         = (offset: number) => ((seed + offset) % 10) / 10;

  const riskLabel = String(firstValue(
    displayFund.riskLevel,
    backendValue('risk', 'risk_level', 'riskLevel', 'mf_scheme.risk_level'),
    notAvailable,
  ));
  const riskIdx = (() => {
    if (riskLabel === notAvailable) return -1;
    const risk = riskLabel.toLowerCase();
    if (risk.includes('very')) return 4;
    if (risk.includes('high')) return 3;
    if (risk.includes('moderate')) return 2;
    if (risk.includes('low')) return 1;
    return 2;
  })();

  const returns = {
    '1M':        formatBackendPercent(backendValue('returns.1m', 'returns.1M', 'one_month_return', 'return_1m'), 'N/A'),
    '3M':        formatBackendPercent(backendValue('returns.3m', 'returns.3M', 'three_month_return', 'return_3m'), 'N/A'),
    'YTD':       formatBackendPercent(backendValue('returns.ytd', 'ytd_return', 'return_ytd'), 'N/A'),
    '1Y':        formatBackendPercent(backendValue('returns.1y', 'returns.1Y', 'one_year_return', 'return_1y'), 'N/A'),
    '3Y':        formatBackendPercent(backendValue('returns.3y', 'returns.3Y', 'three_year_return', 'return_3y'), 'N/A'),
    '5Y':        formatBackendPercent(backendValue('returns.5y', 'returns.5Y', 'five_year_return', 'return_5y'), 'N/A'),
    'Inception': formatBackendPercent(backendValue('returns.inception', 'since_inception_return', 'inception_return'), 'N/A'),
  };
  const returnEntries = (Object.entries(returns) as [string, string][])
    .filter(([, value]) => value !== 'N/A' && toNumber(value) !== null);

  const nav            = firstValue(backendValue('nav', 'latest_nav', 'current_nav', 'mf_scheme.latest_nav'), meta.nav) || `₹${pr(isDebt?500:50, isDebt?3500:500, 6)}.${pr(10,99,40)}`;
  const aum            = firstValue(backendValue('aum', 'assets_under_management', 'asset_under_management', 'mf_scheme.aum'), meta.aum) || `₹${pr(1000, 25000, 7).toLocaleString('en-IN')} Cr`;
  const ter            = firstValue(backendValue('ter', 'expense_ratio', 'total_expense_ratio'), meta.ter) || `${(isDebt ? pr(20,50,8) : pr(80,180,8)) / 100}.${pr(10,99,41)}`;
  const sharpe         = firstValue(backendValue('sharpe', 'sharpe_ratio'), meta.sharpe) || `${(isEquity ? pr(80,150,9) : pr(40,100,9)) / 100}.${pr(10,99,42)}`;
  const stdDev         = firstValue(backendValue('stdDev', 'std_dev', 'standard_deviation'), meta.stdDev) || `${pr(isDebt?1:10, isDebt?8:22, 10)}.${pr(0,9,43)}%`;
  const beta           = firstValue(backendValue('beta', 'beta_ratio'), meta.beta) || (isEquity ? `${(pr(80,115,11)/100).toFixed(2)}` : 'N/A');
  const minSIP         = firstValue(displayFund.minSip, backendValue('minSip', 'min_sip', 'minimum_sip_amount'), meta.minSip) || (isDebt ? '₹500' : '₹100');
  const minLump        = firstValue(displayFund.minInvestment, backendValue('minLumpsum', 'min_lumpsum', 'minimum_purchase_amount'), meta.minLumpsum) || (getAssetClass(displayFund) === 'SIF' ? '₹10,00,000' : '₹500');
  const exitLoad       = firstValue(backendValue('exitLoad', 'exit_load'), meta.exitLoad) || (isDebt ? 'NIL' : '1% if redeemed within 1 year');
  const horizon        = firstValue(backendValue('investment_horizon', 'horizon'), meta.horizon) || (isDebt ? '3 months – 2 years' : isHybrid ? '2–5 years' : '5+ years');
  const inceptionYear  = 2015 + pr(0, 7, 12);
  const months         = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  const inceptionDate  = firstValue(backendValue('inception_date', 'inceptionDate', 'allotment_date', 'mf_scheme.inception_date'), `${pr(1,28,13)} ${months[pr(0,12,14)]} ${inceptionYear}`);

  const holdings = isDebt ? [
    { name: 'Government Securities',  pct: pr(20,40,20) },
    { name: 'AAA Corporate Bonds',    pct: pr(15,28,21) },
    { name: 'Treasury Bills',         pct: pr(8,18,22)  },
    { name: 'AA+ Corporate Bonds',    pct: pr(5,12,23)  },
    { name: 'Commercial Papers',      pct: pr(3,8,24)   },
    { name: 'Others',                 pct: pr(5,12,25)  },
  ] : [
    { name: 'HDFC Bank Ltd.',             pct: pr(4,9,20)  },
    { name: 'Reliance Industries Ltd.',   pct: pr(3,7,21)  },
    { name: 'ICICI Bank Ltd.',            pct: pr(3,6,22)  },
    { name: 'Infosys Ltd.',               pct: pr(2,5,23)  },
    { name: 'Tata Consultancy Services',  pct: pr(2,5,24)  },
    { name: 'Bharti Airtel Ltd.',         pct: pr(1,4,25)  },
    { name: 'Axis Bank Ltd.',             pct: pr(1,4,26)  },
    { name: 'L&T Limited',               pct: pr(1,3,27)  },
    { name: 'Maruti Suzuki India',        pct: pr(1,3,28)  },
    { name: 'Others',                     pct: pr(25,40,29)},
  ];

  const sectors = isDebt ? [
    { name: 'Sovereign',  pct: pr(30,50,35) },
    { name: 'AAA Rated',  pct: pr(20,32,36) },
    { name: 'AA+ Rated',  pct: pr(8,18,37)  },
    { name: 'Others',     pct: pr(5,14,38)  },
  ] : [
    { name: 'Financial Services', pct: pr(22,36,35) },
    { name: 'Technology',         pct: pr(10,20,36) },
    { name: 'Consumer',           pct: pr(7,14,37)  },
    { name: 'Energy',             pct: pr(5,12,38)  },
    { name: 'Healthcare',         pct: pr(4,10,39)  },
    { name: 'Others',             pct: pr(10,20,40) },
  ];

  const normalizeAllocation = (items: any) => Array.isArray(items)
    ? items.map((item: any) => ({
        name: String(firstValue(item.name, item.security_name, item.instrument, item.holding_name, item.sector, item.asset) || 'N/A'),
        pct: Number(firstValue(item.pct, item.percentage, item.percent, item.allocation, item.net_assets_percentage) || 0),
      })).filter((item: any) => item.name !== 'N/A' || item.pct > 0)
    : [];
  const backendHoldings = normalizeAllocation(backendValue('holdings', 'top_holdings', 'portfolio.holdings', 'securities'));
  const backendSectors = normalizeAllocation(backendValue('sectors', 'sector_allocation', 'portfolio.sectors', 'asset_allocation'));
  const displayedHoldings = backendHoldings.length > 0 ? backendHoldings : holdings;
  const displayedSectors = backendSectors.length > 0 ? backendSectors : sectors;

  const TABS = [
    { id: 'overview'     as const, label: 'Overview',     Icon: BookOpen    },
    { id: 'performance'  as const, label: 'Performance',  Icon: TrendingUp  },
    { id: 'portfolio'    as const, label: 'Portfolio',    Icon: PieChart    },
    { id: 'details'      as const, label: 'Fund Details', Icon: FileText    },
  ];

  const whyPoints = isEquity ? [
    'Long-term wealth creation through equity growth potential',
    'Diversified portfolio across sectors and market capitalizations',
    'Professional fund management with a proven track record',
    'SIP facility for disciplined, rupee-cost-averaging investments',
  ] : isDebt ? [
    'Stable returns with lower volatility compared to equity funds',
    'Capital preservation with regular income generation',
    'High credit quality portfolio with AAA/AA+ rated instruments',
    'Ideal for short-to-medium term financial goals',
  ] : [
    'Diversification across asset classes through a single fund',
    'Dynamic allocation capturing opportunities in equity, debt & commodities',
    'Built-in volatility management for better risk-adjusted returns',
    'Expert multi-asset portfolio management under one roof',
  ];

  const taxPoints = isEquity
    ? ['Short Term Capital Gains (held < 12 months): taxed at 20%',
       'Long Term Capital Gains (held > 12 months): taxed at 12.5% (gains above ₹1.25 lakh)']
    : isDebt
    ? ['Gains are taxed as per your applicable income tax slab rate (regardless of holding period)']
    : ['If equity allocation ≥ 65%: Equity taxation — STCG 20%, LTCG 12.5%',
       'If equity allocation < 65%: Debt taxation — gains taxed at applicable slab rates'];

  const maxRet = isEquity ? 45 : isDebt ? 12 : 25;

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="fund-detail-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
    >
      <motion.div
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/50 backdrop-blur-sm"
      />
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 20 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 20 }}
        transition={{ type: 'spring', stiffness: 300, damping: 30 }}
        className="relative z-10 bg-white w-full max-w-3xl max-h-[92vh] rounded-3xl shadow-2xl overflow-hidden flex flex-col"
      >
        {/* ── Modal header (dark) ── */}
        <div className="bg-[#0B1B3E] px-8 pt-7 pb-0 flex-shrink-0">
          <div className="flex justify-between items-start mb-4">
            <div className="flex gap-2 flex-wrap">
              <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${productTypeStyle[getAssetClass(displayFund)] || productTypeStyle['OTHER']}`}>
                {getAssetClass(displayFund)}
              </span>
              {displayFund.category && (
                <span className="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-white/15 text-white/80">
                  {displayFund.category}
                </span>
              )}
            </div>
            <button onClick={onClose} aria-label="Close dialog" className="p-2 text-white/60 hover:text-white bg-white/10 rounded-full transition-colors flex-shrink-0 ml-4">
              <X className="w-4 h-4" aria-hidden="true" />
            </button>
          </div>

          <h2 id="fund-detail-title" className="text-xl font-bold text-white leading-snug mb-1">{displayFund.schemeName}</h2>
          <p className="text-white/55 text-sm mb-5">{displayFund.amcName}</p>
          {(detailLoading || detailError) && (
            <div className={`mb-4 rounded-xl px-4 py-2 text-xs font-semibold ${detailError ? 'bg-amber-100 text-amber-800' : 'bg-white/10 text-white/70'}`}>
              {detailError || 'Fetching latest fund details...'}
            </div>
          )}

          {/* Key stats */}
          <div className="grid grid-cols-4 gap-3 mb-5">
            {[
              { label: 'Latest NAV', value: nav,       color: 'text-white'        },
              { label: 'AUM',        value: aum,       color: 'text-white'        },
              { label: '1Y Return',  value: returns['1Y'],  color: 'text-emerald-400'  },
              { label: 'Risk Level', value: riskLabel, color: 'text-amber-400'    },
            ].map(s => (
              <div key={s.label} className="bg-white/10 rounded-xl px-4 py-3">
                <p className="text-white/50 text-[9px] uppercase font-bold tracking-wider mb-1">{s.label}</p>
                <p className={`text-sm font-bold leading-snug ${s.color}`}>{s.value}</p>
              </div>
            ))}
          </div>

          {/* Tabs */}
          <div className="flex gap-1 bg-white/10 rounded-xl p-1">
            {TABS.map(({ id, label, Icon }) => (
              <button
                key={id}
                onClick={() => setActiveTab(id)}
                aria-label={label}
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

        {/* ── Scrollable content ── */}
        <div className="flex-1 overflow-y-auto">
          <AnimatePresence mode="wait">

            {/* ── OVERVIEW TAB ── */}
            {activeTab === 'overview' && (
              <motion.div key="ov" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Target className="w-4 h-4 text-blue-500" /> Investment Objective
                  </h3>
                  <p className="text-sm text-slate-600 leading-relaxed bg-slate-50 rounded-xl p-4 border border-slate-100">
                    {isEquity
                      ? `The investment objective is to provide long-term capital appreciation by predominantly investing in equity and equity-related instruments across market capitalizations. The Scheme does not guarantee/indicate any returns. There can be no assurance that the objective of the Scheme will be achieved.`
                      : isDebt
                      ? `The investment objective is to provide regular income and capital preservation by investing in high-quality debt and money market instruments. The Scheme does not guarantee/indicate any returns. There can be no assurance that the objective of the Scheme will be achieved.`
                      : `The investment objective is to provide long-term capital appreciation by investing across asset classes like Equity, Debt, and Commodities through a diversified, dynamic allocation strategy. The Scheme does not guarantee/indicate any returns.`}
                  </p>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Award className="w-4 h-4 text-amber-500" /> Why Should You Invest
                  </h3>
                  <ul className="space-y-2.5">
                    {whyPoints.map((pt, i) => (
                      <li key={i} className="flex items-start gap-3 text-sm text-slate-600">
                        <span className="w-5 h-5 rounded-full bg-blue-50 text-blue-600 flex items-center justify-center text-[10px] font-bold flex-shrink-0 mt-0.5">{i + 1}</span>
                        {pt}
                      </li>
                    ))}
                  </ul>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Shield className="w-4 h-4 text-orange-500" /> Riskometer
                  </h3>
                  <div className="bg-slate-50 rounded-xl p-5 border border-slate-100">
                    <div className="flex items-end gap-2 mb-4">
                      {['Low', 'Mod.', 'Mod. High', 'High', 'Very High'].map((lvl, i) => (
                        <div key={lvl} className="flex-1 flex flex-col items-center gap-1.5">
                          <div className={`w-full rounded-full transition-all ${i === riskIdx ? 'h-8' : i < riskIdx ? 'h-4' : 'h-2'} ${
                            i === 0 ? 'bg-green-400' :
                            i === 1 ? 'bg-lime-400' :
                            i === 2 ? 'bg-yellow-400' :
                            i === 3 ? 'bg-orange-400' : 'bg-red-500'
                          } ${i > riskIdx ? 'opacity-25' : ''}`} />
                          <p className={`text-[9px] font-semibold text-center leading-tight ${i === riskIdx ? 'text-slate-700' : 'text-slate-400'}`}>{lvl}</p>
                        </div>
                      ))}
                    </div>
                    <p className="text-xs text-slate-600">
                      <span className="font-semibold">Principal at {riskLabel} risk.</span>{' '}
                      {isDebt ? 'Suitable for conservative investors seeking stable, regular income.'
                              : isHybrid ? 'Suitable for moderate-risk investors seeking balanced growth and stability.'
                              : 'Suitable for investors with high risk tolerance seeking long-term wealth creation.'}
                    </p>
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Users className="w-4 h-4 text-purple-500" /> Fund Managers
                  </h3>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    {[
                      { name: 'Mr. Anil Kumar Verma',   role: 'Lead Fund Manager', exp: '15+ yrs' },
                      { name: 'Ms. Priya Ramachandran', role: 'Co-Fund Manager',   exp: '10+ yrs' },
                    ].map(fm => (
                      <div key={fm.name} className="flex items-center gap-3 bg-slate-50 rounded-xl p-4 border border-slate-100">
                        <div className="w-10 h-10 rounded-full bg-[#0B1B3E] text-white flex items-center justify-center font-bold text-sm flex-shrink-0">
                          {fm.name.split(' ').slice(1,3).map(n => n[0]).join('')}
                        </div>
                        <div>
                          <p className="text-sm font-semibold text-slate-800">{fm.name}</p>
                          <p className="text-xs text-slate-500 mt-0.5">{fm.role} · {fm.exp} experience</p>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>
              </motion.div>
            )}

            {/* ── PERFORMANCE TAB ── */}
            {activeTab === 'performance' && (
              <motion.div key="perf" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <TrendingUp className="w-4 h-4 text-emerald-500" /> Annualized Returns
                  </h3>
                  <div className="rounded-xl overflow-hidden border border-slate-200">
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="bg-[#0B1B3E] text-white text-xs">
                            <th className="text-left px-4 py-3 font-semibold">Period</th>
                            <th className="text-right px-4 py-3 font-semibold">This Fund</th>
                            <th className="text-right px-4 py-3 font-semibold">Benchmark</th>
                            <th className="text-right px-4 py-3 font-semibold">Alpha</th>
                          </tr>
                        </thead>
                        <tbody>
                          {(Object.entries(returns) as [string, string][]).map(([period, val], i) => {
                            const fundPct   = parseFloat(val);
                            const benchPct  = Math.max(0, fundPct - pr(1, 4, 50 + i) - pd(50 + i));
                            const alpha     = fundPct - benchPct;
                            return (
                              <tr key={period} className={`border-b border-slate-100 last:border-0 ${i % 2 === 0 ? 'bg-white' : 'bg-slate-50/60'}`}>
                                <td className="px-4 py-3 font-semibold text-slate-700">{period}</td>
                                <td className="px-4 py-3 text-right font-bold text-emerald-600">{val}</td>
                                <td className="px-4 py-3 text-right text-slate-500">+{benchPct.toFixed(1)}%</td>
                                <td className="px-4 py-3 text-right">
                                  <span className="text-xs font-bold text-emerald-500">+{alpha.toFixed(1)}%</span>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  </div>
                  <p className="text-[11px] text-slate-400 mt-2">* Returns over 1 year are CAGR. Past performance does not guarantee future returns.</p>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <BarChart2 className="w-4 h-4 text-blue-500" /> Returns Comparison Chart
                  </h3>
                  <div className="space-y-3">
                    {(['1Y','3Y','5Y','Inception'] as const).map(period => {
                      const pct = parseFloat(returns[period]);
                      return (
                        <div key={period} className="flex items-center gap-3">
                          <p className="text-xs font-semibold text-slate-500 w-16 text-right flex-shrink-0">{period}</p>
                          <div className="flex-1 bg-slate-100 rounded-full h-7 overflow-hidden">
                            <motion.div
                              initial={{ width: 0 }}
                              animate={{ width: `${Math.min(100, (pct / maxRet) * 100)}%` }}
                              transition={{ duration: 0.9, ease: 'easeOut' }}
                              className="h-full bg-gradient-to-r from-[#0B1B3E] to-blue-500 rounded-full flex items-center justify-end pr-2.5"
                            >
                              <span className="text-[10px] font-bold text-white">{returns[period]}</span>
                            </motion.div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Activity className="w-4 h-4 text-violet-500" /> Investment Growth Snapshot
                  </h3>
                  <div className="grid grid-cols-3 gap-3">
                    {[
                      { label: '₹10,000 SIP × 1Y', value: `₹${(10000 * 12 * (1 + parseFloat(returns['1Y'])/100)).toLocaleString('en-IN', { maximumFractionDigits: 0 })}` },
                      { label: '₹10,000 SIP × 3Y', value: `₹${(10000 * 36 * (1 + parseFloat(returns['3Y'])/100)).toLocaleString('en-IN', { maximumFractionDigits: 0 })}` },
                      { label: '₹1L Lumpsum × 5Y', value: `₹${(100000 * Math.pow(1 + parseFloat(returns['5Y'])/100, 5)).toLocaleString('en-IN', { maximumFractionDigits: 0 })}` },
                    ].map(g => (
                      <div key={g.label} className="bg-emerald-50 rounded-xl p-4 border border-emerald-100 text-center">
                        <p className="text-[10px] font-bold text-emerald-600 uppercase tracking-wider mb-2">{g.label}</p>
                        <p className="text-base font-bold text-emerald-800">{g.value}</p>
                        <p className="text-[9px] text-emerald-500 mt-1">Estimated value</p>
                      </div>
                    ))}
                  </div>
                  <p className="text-[10px] text-slate-400 mt-2">Estimates are illustrative only based on past returns and do not guarantee future performance.</p>
                </section>
              </motion.div>
            )}

            {/* ── PORTFOLIO TAB ── */}
            {activeTab === 'portfolio' && (
              <motion.div key="port" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <Layers className="w-4 h-4 text-blue-500" /> Portfolio & Top Holdings
                  </h3>
                  <div className="rounded-xl overflow-hidden border border-slate-200">
                    <div className="grid grid-cols-[1fr,90px] bg-slate-50 px-4 py-2.5 border-b border-slate-200">
                      <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Security / Instrument</p>
                      <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider text-right">% Net Assets</p>
                    </div>
                    {displayedHoldings.map((h, i) => (
                      <div key={i} className="grid grid-cols-[1fr,90px] px-4 py-3 border-b border-slate-100 last:border-0 hover:bg-slate-50 transition-colors">
                        <div className="flex items-center gap-2.5">
                          <div className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: `hsl(${200 + i * 22}, 65%, 52%)` }} />
                          <p className="text-sm text-slate-700 font-medium">{h.name}</p>
                        </div>
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-12 bg-slate-100 rounded-full h-1.5 overflow-hidden">
                            <div className="h-full bg-blue-400 rounded-full" style={{ width: `${Math.min(100, h.pct * 2.5)}%` }} />
                          </div>
                          <p className="text-sm font-bold text-slate-700 w-10 text-right">{h.pct.toFixed(2)}%</p>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <PieChart className="w-4 h-4 text-purple-500" /> Sector / Asset Holdings
                  </h3>
                  <div className="space-y-3">
                    {displayedSectors.map((s, i) => (
                      <div key={s.name} className="flex items-center gap-3">
                        <p className="text-xs font-medium text-slate-600 w-36 flex-shrink-0">{s.name}</p>
                        <div className="flex-1 bg-slate-100 rounded-full h-5 overflow-hidden">
                          <motion.div
                            initial={{ width: 0 }}
                            animate={{ width: `${Math.min(100, s.pct)}%` }}
                            transition={{ duration: 0.7, delay: i * 0.08, ease: 'easeOut' }}
                            className="h-full rounded-full"
                            style={{ backgroundColor: `hsl(${200 + i * 28}, 62%, 52%)` }}
                          />
                        </div>
                        <p className="text-xs font-bold text-slate-600 w-9 text-right">{s.pct}%</p>
                      </div>
                    ))}
                  </div>
                </section>
              </motion.div>
            )}

            {/* ── FUND DETAILS TAB ── */}
            {activeTab === 'details' && (
              <motion.div key="det" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <FileText className="w-4 h-4 text-slate-500" /> Fund Details
                  </h3>
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                    {[
                      { label: 'CAGR (Since Inception)',   value: returns['Inception'], Icon: TrendingUp  },
                      { label: 'Latest NAV',               value: nav,                  Icon: Activity    },
                      { label: 'AUM',                      value: aum,                  Icon: BarChart2   },
                      { label: 'Inception Date',           value: inceptionDate,        Icon: Calendar    },
                      { label: 'Risk',                     value: riskLabel,            Icon: Shield      },
                      { label: 'Investment Horizon',       value: horizon,              Icon: Clock       },
                      { label: 'Min SIP Amount',           value: minSIP,              Icon: Target      },
                      { label: 'Min Lumpsum Amount',       value: minLump,             Icon: Target      },
                      { label: 'Entry Load',               value: 'NIL',               Icon: FileText    },
                      { label: 'Exit Load',                value: exitLoad,            Icon: FileText    },
                      { label: 'ISIN',                     value: displayFund.externalIsin || 'N/A', Icon: FileText },
                      { label: 'Scheme Code',              value: displayFund.externalSchemeCode || 'N/A', Icon: FileText },
                    ].map(({ label, value, Icon: Ic }) => (
                      <div key={label} className="bg-slate-50 rounded-xl p-4 border border-slate-100">
                        <div className="flex items-center gap-1.5 mb-2">
                          <Ic className="w-3 h-3 text-slate-400" />
                          <p className="text-[9px] uppercase font-bold text-slate-400 tracking-wider leading-snug">{label}</p>
                        </div>
                        <p className="text-sm font-bold text-slate-800 break-words leading-snug">{value}</p>
                      </div>
                    ))}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <Activity className="w-4 h-4 text-blue-500" /> Additional Features / Qualitative Metrics
                  </h3>
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                    {[
                      { label: 'Total Expense Ratio (TER)', value: `${ter}%`   },
                      { label: 'Sharpe Ratio',              value: sharpe       },
                      { label: 'Beta Ratio',                value: beta         },
                      { label: 'Standard Deviation',        value: stdDev       },
                    ].map(m => (
                      <div key={m.label} className="bg-blue-50 rounded-xl p-4 border border-blue-100 text-center">
                        <p className="text-[9px] font-bold text-blue-500 uppercase tracking-wider mb-2 leading-snug">{m.label}</p>
                        <p className="text-xl font-bold text-blue-800">{m.value}</p>
                      </div>
                    ))}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <BookOpen className="w-4 h-4 text-orange-500" /> Tax Applicability
                  </h3>
                  <div className="bg-amber-50 rounded-xl p-5 border border-amber-100 space-y-3">
                    <p className="text-xs font-bold text-amber-700">On Redemption (applicable on or after April 1, 2025)</p>
                    <ul className="space-y-1.5">
                      {taxPoints.map((t, i) => (
                        <li key={i} className="flex items-start gap-2 text-xs text-amber-800">
                          <span className="mt-0.5 flex-shrink-0">•</span>{t}
                        </li>
                      ))}
                    </ul>
                    <p className="text-[10px] text-amber-600 italic mt-1">
                      Rates are exclusive of surcharge and cess. Consult your tax/financial advisor for personalized guidance.
                    </p>
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Info className="w-4 h-4 text-slate-400" /> Fund Discipline
                  </h3>
                  <p className="text-sm text-slate-600 leading-relaxed bg-slate-50 rounded-xl p-4 border border-slate-100">
                    {isEquity
                      ? 'The scheme follows a diversified equity strategy with the fund manager having the flexibility to invest across large-cap, mid-cap, and small-cap segments. Stock selection is based on bottom-up fundamental research combined with top-down macro analysis.'
                      : isDebt
                      ? 'The scheme maintains a high-quality portfolio by investing predominantly in AAA-rated and sovereign instruments. Duration management is actively undertaken based on interest rate outlook and credit quality assessments.'
                      : 'The scheme dynamically allocates across equity (65–80%), debt (10–25%), and commodities (10–25%) based on market conditions, with the fund manager adjusting allocation to optimise risk-adjusted returns across market cycles.'}
                  </p>
                </section>
              </motion.div>
            )}

          </AnimatePresence>
        </div>

        {/* ── Footer CTA ── */}
        <div className="px-8 py-4 border-t border-slate-100 bg-white flex-shrink-0 flex items-center gap-3">
          <button onClick={onClose} className="px-5 py-2.5 text-sm font-medium bg-slate-100 text-slate-600 rounded-xl hover:bg-slate-200 transition-colors">
            Close
          </button>
          <button
            onClick={() => onInvest(displayFund)}
            className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-semibold rounded-xl hover:bg-[#1A3066] transition-colors"
          >
            Invest Now
          </button>
        </div>
      </motion.div>
    </div>
  );
}

// ─── Transaction Modal ────────────────────────────────────────────────────────
function TransactionModal({ fund, userData, onClose }: { fund: any; userData?: any; onClose: () => void }) {
  const tomorrowDate = () => {
    const date = new Date();
    date.setDate(date.getDate() + 1);
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  };
  const dialogRef = useFocusTrap<HTMLDivElement>(true);
  const [step, setStep] = useState(1);
  const [type, setType] = useState('SIP');
  const [investorQuery, setInvestorQuery] = useState('');
  const debouncedInvestorQuery = useDebounce(investorQuery, 300);
  const [investorResults, setInvestorResults] = useState<any[]>([]);
  const [selectedInvestor, setSelectedInvestor] = useState<any | null>(null);
  const [bankAccounts, setBankAccounts] = useState<any[]>([]);
  const [selectedBank, setSelectedBank] = useState<any | null>(null);
  const [bankLoading, setBankLoading] = useState(false);
  const [bankError, setBankError] = useState('');
  const [investorLoading, setInvestorLoading] = useState(false);
  const [investorError, setInvestorError] = useState('');
  const [amount, setAmount] = useState('5000');
  const [sipFrequency, setSipFrequency] = useState<'MONTHLY' | 'QUARTERLY'>('MONTHLY');
  const [sipStartDate, setSipStartDate] = useState(tomorrowDate);
  const [orderSubmitting, setOrderSubmitting] = useState(false);
  const [orderError, setOrderError] = useState('');
  const [createdOrder, setCreatedOrder] = useState<any | null>(null);

  const investorName = (investor: any) => investor?.fullName || investor?.name || 'Unnamed Investor';
  const maskAccountNumber = (value?: string) => {
    if (!value) return 'Account not captured';
    const lastFour = value.slice(-4);
    return `${'*'.repeat(Math.max(value.length - 4, 4))}${lastFour}`;
  };
  const bankLabel = (bank: any) =>
    bank ? `${bank.bankName || 'Bank'} ${maskAccountNumber(bank.accountNumber)}` : 'No verified bank selected';
  const isVerifiedBank = (bank: any) =>
    String(bank?.verificationStatus || '').toUpperCase() === 'VERIFIED';
  const investorSearchText = (investor: any) =>
    [
      investorName(investor),
      investor?.pan,
      investor?.email,
      investor?.mobileNumber,
    ].filter(Boolean).join(' ').toLowerCase();
  const isTransactionEligible = (investor: any) => {
    const kyc = String(investor?.kycStatus || investor?.kyc || '').toUpperCase();
    const bank = String(investor?.bankVerificationStatus || '').toUpperCase();
    return ['COMPLETED', 'VERIFIED'].includes(kyc) && bank === 'VERIFIED';
  };
  const extractInvestors = (payload: any) => {
    if (Array.isArray(payload)) return payload;
    if (Array.isArray(payload?.content)) return payload.content;
    if (Array.isArray(payload?.data?.content)) return payload.data.content;
    if (Array.isArray(payload?.data)) return payload.data;
    return [];
  };
  const parseMoneyValue = (value: any, fallback: number) => {
    const parsed = typeof value === 'number'
      ? value
      : Number(String(value ?? '').replace(/[^0-9.]/g, ''));
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
  };
  const minAmount = type === 'SIP'
    ? readSchemeMinSip(fund, 500)
    : parseMoneyValue(fund.minInvestment || fund.minimumInvestment || fund.minLumpsum, 1000);
  const amountNumber = Number(amount);
  const amountValid = Number.isFinite(amountNumber) && amountNumber >= minAmount;
  const sipStartDateValid = type !== 'SIP' || Boolean(sipStartDate && new Date(`${sipStartDate}T00:00:00`) > new Date());
  const canContinueAmountStep = amountValid && sipStartDateValid;

  useEffect(() => {
    let cancelled = false;

    const searchInvestors = async () => {
      const query = debouncedInvestorQuery.trim();
      setInvestorLoading(true);
      setInvestorError('');
      try {
        const role = normalizeRole(userData?.role);
        const shouldScopeToDistributor = Boolean(userData?.id && role !== 'ADMIN');
        const params = new URLSearchParams(
          query
            ? { query, limit: '50' }
            : { page: '0', size: '50' }
        );

        if (shouldScopeToDistributor) {
          params.set('distributorId', userData.id);
        }

        const endpoint = query
          ? `/investors/search/transaction-eligible?${params.toString()}`
          : `/investors?${params.toString()}`;
        const res = await apiFetch(endpoint);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        const investors = extractInvestors(data)
          .filter((investor: any) => query ? true : isTransactionEligible(investor))
          .filter((investor: any) => {
            if (!query) return true;
            return investorSearchText(investor).includes(query.toLowerCase());
          });

        if (!cancelled) setInvestorResults(investors);
      } catch (err) {
        console.error('Failed to search transaction eligible investors:', err);
        if (!cancelled) {
          setInvestorResults([]);
          setInvestorError('Could not load investors for this distributor. Please try again.');
        }
      } finally {
        if (!cancelled) setInvestorLoading(false);
      }
    };

    searchInvestors();
    return () => {
      cancelled = true;
    };
  }, [debouncedInvestorQuery, userData]);

  useEffect(() => {
    let cancelled = false;
    setBankAccounts([]);
    setSelectedBank(null);
    setBankError('');

    if (!selectedInvestor?.id) {
      return () => {
        cancelled = true;
      };
    }

    setBankLoading(true);
    apiFetch(`/investors/${selectedInvestor.id}/bank-accounts`)
      .then(async res => {
        const data = await res.json().catch(() => null);
        if (!res.ok) throw new Error(data?.message || `HTTP ${res.status}`);
        if (cancelled) return;
        const accounts = Array.isArray(data) ? data : [];
        const verifiedAccounts = accounts.filter(isVerifiedBank);
        setBankAccounts(verifiedAccounts);
        setSelectedBank(verifiedAccounts[0] || null);
      })
      .catch(err => {
        console.error('Failed to load investor bank accounts:', err);
        if (!cancelled) setBankError('Could not load a verified bank account for this investor.');
      })
      .finally(() => {
        if (!cancelled) setBankLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [selectedInvestor?.id]);

  const updateType = (nextType: string) => {
    setType(nextType);
    setAmount(nextType === 'SIP' ? '5000' : String(parseMoneyValue(fund.minInvestment || fund.minimumInvestment || fund.minLumpsum, 1000)));
    if (nextType === 'SIP' && !sipStartDate) setSipStartDate(tomorrowDate());
    setOrderError('');
  };

  const submitOrder = async () => {
    if (!selectedInvestor || !amountValid || orderSubmitting) return;
    if (!isTransactionReadyScheme(fund)) {
      setOrderError('This fund is not POA-orderable. Sync the Cybrilla POA catalogue and pick a fund with a valid ISIN.');
      return;
    }

    setOrderSubmitting(true);
    setOrderError('');
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), 180_000);
    try {
      const payload = {
        investorId: selectedInvestor.id,
        productSchemeId: isPersistedSchemeId(fund.id) ? fund.id : undefined,
        externalSchemeCode: fund.externalSchemeCode || undefined,
        externalIsin: fund.externalIsin || undefined,
        type: type === 'SIP' ? 'SIP' : 'LUMPSUM',
        transactionType: type === 'SIP' ? 'SIP' : 'LUMPSUM_PURCHASE',
        amount: amountNumber,
        paymentMode: type === 'SIP' ? 'MANDATE' : 'UPI',
        mandateMode: type === 'SIP' ? 'AUTO_DEBIT' : undefined,
        sipFrequency: type === 'SIP' ? sipFrequency : undefined,
        sipStartDate: type === 'SIP' ? sipStartDate : undefined,
        sipInstalments: type === 'SIP' ? 12 : undefined,
      };

      const response = await apiFetch('/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });
      const result = await response.json().catch(() => null);

      if (!response.ok) {
        const baseMessage = result?.message || `Order creation failed (${response.status})`;
        if (response.status === 404 && /product scheme not found/i.test(baseMessage)) {
          throw new Error(
            'Selected fund is no longer in the local catalogue. Close this dialog, refresh funds on the Ledger, then try again.',
          );
        }
        if (response.status === 400 && /kyc must be completed/i.test(baseMessage)) {
          throw new Error('Investor KYC must be completed before placing an order.');
        }
        if (response.status === 400 && /verified bank account/i.test(baseMessage)) {
          throw new Error('Investor must have a verified bank account before placing an order.');
        }
        if (response.status === 400 && /investor profile|mf investment account|fintech primitives/i.test(baseMessage)) {
          throw new Error(
            `${baseMessage} Use Investors → Sync from Cybrilla for this investor, or select Anita Verma (demo-ready) and retry.`,
          );
        }
        throw new Error(baseMessage);
      }

      setCreatedOrder(result || {});
    } catch (err: any) {
      if (err?.name === 'AbortError') {
        setOrderError(
          'Order creation is taking longer than expected. Cybrilla may still be preparing the investor profile — check Transactions in a moment, then retry if no order appears.',
        );
      } else {
        setOrderError(err?.message || 'Order creation failed. Please try again.');
      }
    } finally {
      window.clearTimeout(timeoutId);
      setOrderSubmitting(false);
    }
  };

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="transaction-modal-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
    >
      <motion.div
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm"
      />
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 20 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white/95 backdrop-blur-xl w-full max-w-lg rounded-3xl shadow-xl overflow-hidden relative z-10 border border-white/20"
      >
        <div className="absolute top-4 right-4">
          <button onClick={onClose} aria-label="Close dialog" className="p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors">
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>

        <div className="p-8">
          {createdOrder ? (
            <div className="text-center py-6">
              <div className="mx-auto mb-5 flex h-16 w-16 items-center justify-center rounded-full bg-green-100">
                <CheckCircle2 className="h-8 w-8 text-green-600" />
              </div>
              <h2 id="transaction-modal-title" className="text-xl font-semibold text-slate-800">Investment Order Created</h2>
              <p className="mt-2 text-sm text-slate-500">
                {type === 'SIP' ? 'SIP' : 'Lumpsum'} order for {investorName(selectedInvestor)} has been sent for processing.
              </p>
              <div className="mt-6 rounded-2xl border border-slate-100 bg-slate-50 p-4 text-left text-sm">
                <div className="flex justify-between gap-4 py-1">
                  <span className="text-slate-500">Order ID</span>
                  <span className="font-mono text-xs font-medium text-slate-800 text-right">{createdOrder.id || '—'}</span>
                </div>
                <div className="flex justify-between gap-4 py-1">
                  <span className="text-slate-500">Fund</span>
                  <span className="font-medium text-slate-800 text-right">{formatSchemeDisplayName(fund)}</span>
                </div>
                <div className="flex justify-between gap-4 py-1">
                  <span className="text-slate-500">Amount</span>
                  <span className="font-mono font-semibold text-slate-800">Rs {amountNumber.toLocaleString('en-IN')}</span>
                </div>
                <div className="flex justify-between gap-4 py-1">
                  <span className="text-slate-500">Status</span>
                  <span className="font-semibold text-amber-700">{formatOrderStatusLabel(createdOrder.orderStatus)}</span>
                </div>
              </div>
              <div className="mt-5 text-left">
                <InvestorActionLink
                  orderId={createdOrder.id}
                  orderStatus={createdOrder.orderStatus}
                  investorActionUrl={createdOrder.investorActionUrl}
                  compact
                />
              </div>
              <button
                onClick={onClose}
                className="mt-7 w-full rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
              >
                Done
              </button>
            </div>
          ) : (
          <>
          <h2 id="transaction-modal-title" className="text-xl font-semibold mb-1 text-slate-800">New Transaction</h2>
          <p className="text-sm text-slate-500 mb-8">{formatSchemeDisplayName(fund)}</p>

          {/* Progress bar */}
          <div className="flex items-center gap-2 mb-8">
            {[1, 2, 3].map(i => (
              <div key={i} className={`h-1.5 flex-1 rounded-full ${step >= i ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
            ))}
          </div>

          <AnimatePresence mode="wait">
            {step === 1 && (
              <motion.div key="s1" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Select Investor</label>
                  <div className="relative">
                    <Search className="absolute left-4 top-3.5 w-4 h-4 text-slate-400" />
                    <input
                      type="text"
                      value={investorQuery}
                      onChange={e => {
                        setInvestorQuery(e.target.value);
                        setSelectedInvestor(null);
                      }}
                      placeholder="Search investor by name or PAN…"
                      className="w-full pl-11 pr-4 py-3 bg-slate-50 border border-slate-200 rounded-xl text-sm outline-none focus:ring-2 focus:ring-blue-100" />
                  </div>
                  {selectedInvestor && (
                    <div className="mt-3 rounded-xl border border-green-100 bg-green-50 p-3">
                      <p className="text-sm font-semibold text-green-800">{investorName(selectedInvestor)}</p>
                      <p className="text-xs text-green-700 font-mono mt-0.5">{selectedInvestor.pan}</p>
                      <p className="mt-2 text-xs font-semibold text-green-800">
                        Bank: {bankLoading ? 'Loading...' : selectedBank ? bankLabel(selectedBank) : 'No verified bank account'}
                      </p>
                      {bankError && <p className="mt-1 text-xs font-semibold text-red-600">{bankError}</p>}
                    </div>
                  )}
                  {!selectedInvestor && (
                    <div className="mt-3 max-h-48 overflow-auto rounded-xl border border-slate-200 bg-white">
                      {investorLoading ? (
                        <div className="p-4 text-sm text-slate-500">Loading eligible investors...</div>
                      ) : investorError ? (
                        <div className="p-4 text-sm text-red-500">{investorError}</div>
                      ) : investorResults.length === 0 ? (
                        <div className="p-4 text-sm text-slate-500">No transaction-ready investors found under this distributor.</div>
                      ) : (
                        investorResults.map(inv => (
                          <button
                            key={inv.id}
                            onClick={() => {
                              setSelectedInvestor(inv);
                              setInvestorQuery(investorName(inv));
                            }}
                            className="w-full px-4 py-3 text-left hover:bg-slate-50 border-b border-slate-100 last:border-b-0"
                          >
                            <p className="text-sm font-semibold text-slate-800">{investorName(inv)}</p>
                            <p className="text-xs text-slate-500 font-mono mt-0.5">
                              {inv.pan || 'PAN not captured'}{inv.mobileNumber ? ` · ${inv.mobileNumber}` : ''}
                            </p>
                          </button>
                        ))
                      )}
                    </div>
                  )}
                </div>
              </motion.div>
            )}
            {step === 2 && (
              <motion.div key="s2" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div className="flex bg-slate-100 p-1 rounded-lg">
                  <button onClick={() => updateType('SIP')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'SIP' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>SIP</button>
                  <button onClick={() => updateType('Lumpsum')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'Lumpsum' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>Lumpsum</button>
                </div>
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Investment Amount</label>
                  <div className="relative">
                    <span className="absolute left-4 top-3.5 text-slate-500 font-medium">Rs</span>
                    <input
                      type="number"
                      min={minAmount}
                      value={amount}
                      onChange={e => setAmount(e.target.value)}
                      className={`w-full pl-12 pr-4 py-3 bg-white border rounded-xl text-lg font-medium outline-none focus:ring-2 focus:ring-blue-100 ${amount && !amountValid ? 'border-red-300 bg-red-50' : 'border-slate-200'}`}
                    />
                  </div>
                  {amount && !amountValid && (
                    <p className="mt-1 text-xs text-red-500">Minimum {type === 'SIP' ? 'SIP' : 'lumpsum'} amount is Rs {minAmount.toLocaleString('en-IN')}.</p>
                  )}
                </div>
                {type === 'SIP' && (
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Frequency</label>
                      <select
                        value={sipFrequency}
                        onChange={e => setSipFrequency(e.target.value as 'MONTHLY' | 'QUARTERLY')}
                        className="w-full rounded-xl border border-slate-200 bg-white px-3 py-3 text-sm font-medium outline-none focus:ring-2 focus:ring-blue-100"
                      >
                        <option value="MONTHLY">Monthly</option>
                        <option value="QUARTERLY">Quarterly</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Start Date</label>
                      <input
                        type="date"
                        min={tomorrowDate()}
                        value={sipStartDate}
                        onChange={e => setSipStartDate(e.target.value)}
                        className={`w-full rounded-xl border bg-white px-3 py-3 text-sm font-medium outline-none focus:ring-2 focus:ring-blue-100 ${!sipStartDateValid ? 'border-red-300 bg-red-50' : 'border-slate-200'}`}
                      />
                      {!sipStartDateValid && (
                        <p className="mt-1 text-xs text-red-500">Start date must be in the future.</p>
                      )}
                    </div>
                  </div>
                )}
              </motion.div>
            )}
            {step === 3 && (
              <motion.div key="s3" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div className="bg-slate-50 rounded-xl p-6 border border-slate-100 space-y-4">
                  <div className="flex justify-between items-center pb-4 border-b border-slate-200">
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Fund</p>
                      <p className="font-medium text-slate-800 text-sm">{formatSchemeDisplayName(fund)}</p>
                    </div>
                    <CheckCircle2 className="w-5 h-5 text-green-500" />
                  </div>
                  <div>
                    <p className="text-xs text-slate-400 font-semibold uppercase mb-1">AMC</p>
                    <p className="font-medium text-slate-800 text-sm">{formatAmcDisplayName(fund)}</p>
                  </div>
                  <div className="grid grid-cols-2 pt-4">
                    <div className="col-span-2 pb-4">
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Investor</p>
                      <p className="font-medium text-slate-800 text-sm">{investorName(selectedInvestor)}</p>
                    </div>
                    <div className="col-span-2 pb-4">
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Bank</p>
                      <p className="font-medium text-slate-800 text-sm">{bankLabel(selectedBank)}</p>
                    </div>
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">{type}</p>
                      <p className="font-mono font-medium text-slate-800">Rs {amountNumber.toLocaleString('en-IN')}</p>
                    </div>
                    {type === 'SIP' && (
                      <div>
                        <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Frequency</p>
                        <p className="font-medium text-slate-800">{sipFrequency === 'MONTHLY' ? 'Monthly' : 'Quarterly'}</p>
                      </div>
                    )}
                    {type === 'SIP' && (
                      <div className="pt-4">
                        <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Start Date</p>
                        <p className="font-medium text-slate-800">{sipStartDate}</p>
                      </div>
                    )}
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Mode</p>
                      <p className="font-medium text-slate-800">{type === 'SIP' ? 'UPI Mandate' : 'UPI Payment'}</p>
                    </div>
                  </div>
                </div>
                <div className="flex items-start gap-3 bg-blue-50/50 p-4 rounded-xl border border-blue-100">
                  <Info className="w-5 h-5 text-blue-500 flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-blue-800 leading-relaxed">
                    A payment link will be sent to the investor's registered email and mobile number.
                  </p>
                </div>
                {orderError && (
                  <div className="rounded-xl border border-red-100 bg-red-50 p-3 text-sm font-medium text-red-700">
                    {orderError}
                  </div>
                )}
              </motion.div>
            )}
          </AnimatePresence>

          <div className="mt-10 flex gap-3">
            {step > 1 && (
              <button onClick={() => setStep(step - 1)}
                className="px-6 py-3 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">
                Back
              </button>
            )}
            <button
              onClick={() => step < 3 ? setStep(step + 1) : submitOrder()}
              disabled={(step === 1 && (!selectedInvestor || bankLoading || !selectedBank)) || (step === 2 && !canContinueAmountStep) || orderSubmitting}
              className="flex-1 py-3 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors flex items-center justify-center gap-2 disabled:bg-slate-200 disabled:text-slate-400 disabled:cursor-not-allowed"
            >
              {step === 3 ? orderSubmitting ? 'Creating Order...' : 'Confirm & Create Order' : 'Continue'}
              {step !== 3 && <ChevronRight className="w-4 h-4" />}
            </button>
          </div>
          {step === 3 && orderSubmitting && (
            <p className="mt-3 text-center text-xs text-slate-500">
              Syncing with Cybrilla (profile, bank, purchase). First order for an investor can take up to 2 minutes.
            </p>
          )}
          </>
          )}
        </div>
      </motion.div>
    </div>
  );
}
