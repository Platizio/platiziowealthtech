import React, { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Search, Filter, Trash2, X, ChevronDown, Lock, Rocket, Eye, EyeOff, RefreshCw, AlertCircle, Layers } from 'lucide-react';
import { Product } from '../data/products';
import { apiFetch } from '../config/api';
import Pagination from '../components/Pagination';
import { getPageContent, getPageMeta } from '../utils/pagination';
import { useDebounce } from '../hooks/useDebounce';
import EmptyState from '../components/EmptyState';
import { useFocusTrap } from '../hooks/useFocusTrap';

// ─── Display helpers ──────────────────────────────────────────────────────────

const futureClasses = [
  { code: 'PMS',       full: 'Portfolio Management Service', minInvest: '₹50L+',        eta: 'Q3 2025', grad: 'from-purple-50 to-violet-50', border: 'border-violet-200', accent: 'text-violet-700', dot: 'bg-violet-400' },
  { code: 'AIF',       full: 'Alternative Investment Fund',  minInvest: '₹1 Cr+',        eta: 'Q4 2025', grad: 'from-blue-50 to-sky-50',      border: 'border-blue-200',   accent: 'text-blue-700',   dot: 'bg-blue-400'   },
  { code: 'NPS',       full: 'National Pension System',      minInvest: '₹500/month',     eta: 'Q1 2026', grad: 'from-green-50 to-emerald-50', border: 'border-green-200',  accent: 'text-green-700',  dot: 'bg-green-400'  },
  { code: 'Bonds',     full: 'Corporate & Govt Bonds',       minInvest: '₹10,000+',       eta: 'Q1 2026', grad: 'from-amber-50 to-yellow-50',  border: 'border-amber-200',  accent: 'text-amber-700',  dot: 'bg-amber-400'  },
  { code: 'Insurance', full: 'Term & ULIP Products',         minInvest: 'Varies',         eta: 'Q2 2026', grad: 'from-rose-50 to-pink-50',     border: 'border-rose-200',   accent: 'text-rose-700',   dot: 'bg-rose-400'   },
];

const visibilityConfig: Record<string, string> = {
  'All Tiers':      'bg-slate-100 text-slate-600',
  'Silver & Above': 'bg-slate-100 text-slate-700',
  'Gold & Above':   'bg-amber-50 text-amber-700',
  'Platinum Only':  'bg-violet-50 text-violet-700',
};

const riskColor: Record<string, string> = {
  Low:         'text-green-600',
  Moderate:    'text-blue-600',
  High:        'text-orange-600',
  'Very High': 'text-red-600',
};

const ASSET_CLASSES = ['All', 'MF', 'SIF'];
const CATEGORIES    = ['All', 'Equity', 'Debt', 'ELSS', 'Hybrid', 'Strategic'];
const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';

interface BackendProductScheme {
  id?: string;
  schemeName?: string;
  amcName?: string;
  category?: string;
  externalSchemeCode?: string;
  externalIsin?: string;
  productType?: string;
  active?: boolean;
  metadataJson?: string;
}

const parseMetadata = (metadataJson?: string) => {
  if (!metadataJson) return {};
  try {
    return JSON.parse(metadataJson);
  } catch {
    return {};
  }
};

const firstMetadataValue = (metadata: any, keys: string[]) => {
  for (const key of keys) {
    const value = metadata?.[key];
    if (value !== undefined && value !== null && value !== '') return value;
  }
  return undefined;
};

const formatCurrency = (value: unknown, fallback = 'Rs 0') => {
  const numberValue = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[^0-9.]/g, ''));
  return Number.isFinite(numberValue) && numberValue > 0
    ? `Rs ${numberValue.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`
    : fallback;
};

const formatReturn = (value: unknown) => {
  const numberValue = typeof value === 'number' ? value : Number(String(value ?? '').replace(/[^0-9.-]/g, ''));
  if (!Number.isFinite(numberValue)) return '+0.0%';
  return `${numberValue >= 0 ? '+' : ''}${numberValue.toFixed(1)}%`;
};

const normalizeCategory = (value?: string) => {
  const normalized = (value || '').toLowerCase();
  if (normalized.includes('equity')) return 'Equity';
  if (normalized.includes('debt') || normalized.includes('income')) return 'Debt';
  if (normalized.includes('elss') || normalized.includes('tax')) return 'ELSS';
  if (normalized.includes('hybrid') || normalized.includes('balanced')) return 'Hybrid';
  if (normalized.includes('sif') || normalized.includes('strategic')) return 'Strategic';
  return 'Equity';
};

const mapBackendSchemeToProduct = (scheme: BackendProductScheme, index: number): Product => {
  const metadata = parseMetadata(scheme.metadataJson);
  const rawCategory = firstMetadataValue(metadata, ['category', 'scheme_category', 'sub_category']) || scheme.category;
  const rawRisk = firstMetadataValue(metadata, ['risk_level', 'riskometer', 'risk', 'risk_grade']);
  const rawVisibility = firstMetadataValue(metadata, ['visibility', 'distributor_visibility']);
  const rawReturn = metadata?.returns?.['1y'] ?? metadata?.returns?.one_year ?? firstMetadataValue(metadata, ['return_1y', 'one_year_return']);
  const rawMinInvestment = firstMetadataValue(metadata, ['minimum_purchase_amount', 'min_purchase_amount', 'min_initial_investment', 'purchase_amount_minimum']);
  const rawNav = firstMetadataValue(metadata, ['nav', 'current_nav', 'last_nav']);

  return {
    id: scheme.id || scheme.externalSchemeCode || `scheme-${index + 1}`,
    name: scheme.schemeName || scheme.externalSchemeCode || 'Unnamed Fund Scheme',
    assetClass: scheme.category === 'SIF' ? 'SIF' : 'MF',
    category: normalizeCategory(String(rawCategory || '')),
    return1y: formatReturn(rawReturn),
    minInvest: formatCurrency(rawMinInvestment, 'Rs 100'),
    nav: formatCurrency(rawNav, 'Rs 0'),
    visibility: rawVisibility ? String(rawVisibility) : 'All Tiers',
    riskLevel: rawRisk ? String(rawRisk) : 'Moderate',
    status: scheme.active === false ? 'Inactive' : 'Active',
    amc: scheme.amcName || 'Unknown AMC',
    externalSchemeCode: scheme.externalSchemeCode,
    externalIsin: scheme.externalIsin,
    productType: scheme.productType,
  };
};

const parseDisplayNumber = (value: string) => {
  const numberValue = Number(String(value || '').replace(/[^0-9.-]/g, ''));
  return Number.isFinite(numberValue) ? numberValue : 0;
};

const productToSchemePayload = (product: Omit<Product, 'id'>) => {
  const active = product.status !== 'Inactive';
  const metadata = {
    category: product.category,
    risk_level: product.riskLevel,
    return_1y: parseDisplayNumber(product.return1y),
    minimum_purchase_amount: parseDisplayNumber(product.minInvest),
    nav: parseDisplayNumber(product.nav),
    visibility: product.visibility,
  };

  return {
    schemeName: product.name.trim(),
    amcName: product.amc.trim(),
    category: product.assetClass,
    externalSchemeCode: (product.externalSchemeCode || '').trim(),
    externalIsin: product.externalIsin?.trim() || null,
    productType: product.productType?.trim() || (product.assetClass === 'SIF' ? 'SIF' : 'MUTUAL_FUND'),
    active,
    metadataJson: JSON.stringify(metadata),
  };
};

// ─── Component ────────────────────────────────────────────────────────────────

export default function ProductMgmt({
  products,
  setProducts,
  userData,
}: {
  products:    Product[];
  setProducts: React.Dispatch<React.SetStateAction<Product[]>>;
  userData?: any;
}) {
  const [search,         setSearch]         = useState('');
  const debouncedSearch                     = useDebounce(search, 300);
  const [assetFilter,    setAssetFilter]    = useState('All');
  const [categoryFilter, setCategoryFilter] = useState('All');
  const [showFilters,    setShowFilters]    = useState(false);
  const [showAddModal,   setShowAddModal]   = useState(false);
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [savingProduct, setSavingProduct] = useState(false);
  const [productActionId, setProductActionId] = useState<string | number | null>(null);
  const [syncError, setSyncError] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const refreshInFlightRef = useRef(false);
  const canRefreshLiveSchemes = normalizeRole(userData?.role) === 'ADMIN';

  const readJsonSafely = async (response: Response) => {
    const text = await response.text();
    if (!text) return null;
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  };

  const loadCachedProducts = async () => {
    setLoadingProducts(true);
    setSyncError('');
    try {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (debouncedSearch.trim()) params.set('query', debouncedSearch.trim());
      const response = await apiFetch(`/products/schemes?${params.toString()}`);
      const result = response.ok ? await response.json() : [];
      const schemes = getPageContent(result);
      const meta = getPageMeta(result, schemes.length);
      setProducts(schemes.map(mapBackendSchemeToProduct));
      setTotalPages(meta.totalPages);
      setTotalElements(meta.totalElements);
    } catch (error) {
      console.error('Failed to load cached product schemes:', error);
      setSyncError(error instanceof Error ? error.message : 'Unable to load cached products');
    } finally {
      setLoadingProducts(false);
    }
  };

  useEffect(() => {
    loadCachedProducts();
  }, [page, size, debouncedSearch]);

  const fetchProductsFromCybrilla = async () => {
    if (!canRefreshLiveSchemes) {
      setSyncError('Only admin users can sync products from Cybrilla.');
      return;
    }

    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setLoadingProducts(true);
    setSyncError('');
    console.groupCollapsed('[Cybrilla Workflow] Fetch fund schemes');
    console.log('frontend_route=', '/admin/product-mgmt');
    console.log('frontend_request=', 'POST /api/v1/products/schemes/refresh');
    console.log('backend_expected_external_call=', 'GET https://s.finprim.com/api/oms/fund_schemes?page=0&size=100');
    console.log('frontend_note=', 'Browser calls Platizio backend only. Backend uses the server-side Fintech Primitives bearer token.');

    try {
      const response = await apiFetch('/products/schemes/refresh', { method: 'POST' });
      const result = await readJsonSafely(response);
      console.log('frontend_response=', {
        status: response.status,
        ok: response.ok,
        count: Array.isArray(result) ? result.length : undefined,
        sample: Array.isArray(result) ? result.slice(0, 3) : result,
      });

      if (!response.ok) {
        throw new Error(typeof result === 'string' ? result : result?.message || 'Unable to fetch fund schemes from Cybrilla/FP');
      }

      const schemes = Array.isArray(result) ? result : [];
      setProducts(schemes.map(mapBackendSchemeToProduct));
      console.log('workflow_status=', 'completed');
    } catch (error) {
      console.error('workflow_status=', 'failed');
      console.error('workflow_error=', error);
      setSyncError(error instanceof Error ? error.message : 'Unable to fetch fund schemes from Cybrilla/FP');
      await loadCachedProducts();
    } finally {
      console.groupEnd();
      refreshInFlightRef.current = false;
      setLoadingProducts(false);
    }
  };

  // ── Derived state ──────────────────────────────────────────────────────────
  const filtered = products.filter(p => {
    const matchSearch   = p.name.toLowerCase().includes(search.toLowerCase()) ||
                          p.amc.toLowerCase().includes(search.toLowerCase());
    const matchAsset    = assetFilter    === 'All' || p.assetClass === assetFilter;
    const matchCategory = categoryFilter === 'All' || p.category   === categoryFilter;
    return matchSearch && matchAsset && matchCategory;
  });

  const mfCount  = products.filter(p => p.assetClass === 'MF').length;
  const sifCount = products.filter(p => p.assetClass === 'SIF').length;

  // ── Handlers ───────────────────────────────────────────────────────────────
  const apiErrorMessage = (result: any, fallback: string) =>
    typeof result === 'string' ? result : result?.message || fallback;

  const handleDelete = async (id: string | number) => {
    const product = products.find(p => p.id === id);
    if (!product || !window.confirm(`Delete ${product.name}? This removes it from the backend catalog.`)) return;

    setProductActionId(id);
    setSyncError('');
    try {
      const response = await apiFetch(`/products/schemes/${id}`, { method: 'DELETE' });
      const result = await readJsonSafely(response);
      if (!response.ok) throw new Error(apiErrorMessage(result, 'Unable to delete product'));
      setProducts(prev => prev.filter(p => p.id !== id));
      setTotalElements(prev => Math.max(prev - 1, 0));
    } catch (error) {
      console.error('Failed to delete product:', error);
      setSyncError(error instanceof Error ? error.message : 'Unable to delete product');
    } finally {
      setProductActionId(null);
    }
  };

  const handleToggleStatus = async (id: string | number) => {
    const product = products.find(p => p.id === id);
    if (!product) return;

    const nextActive = product.status !== 'Active';
    setProductActionId(id);
    setSyncError('');
    try {
      const response = await apiFetch(`/products/schemes/${id}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ active: nextActive }),
      });
      const result = await readJsonSafely(response);
      if (!response.ok) throw new Error(apiErrorMessage(result, 'Unable to update product status'));
      const updated = mapBackendSchemeToProduct(result, products.findIndex(p => p.id === id));
      setProducts(prev => prev.map(p => p.id === id ? updated : p));
    } catch (error) {
      console.error('Failed to update product status:', error);
      setSyncError(error instanceof Error ? error.message : 'Unable to update product status');
    } finally {
      setProductActionId(null);
    }
  };

  const handleAdd = async (newProduct: Omit<Product, 'id'>) => {
    setSavingProduct(true);
    setSyncError('');
    try {
      const response = await apiFetch('/products/schemes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(productToSchemePayload(newProduct)),
      });
      const result = await readJsonSafely(response);
      if (!response.ok) throw new Error(apiErrorMessage(result, 'Unable to add product'));
      const created = mapBackendSchemeToProduct(result, products.length);
      setProducts(prev => [created, ...prev]);
      setTotalElements(prev => prev + 1);
      setShowAddModal(false);
    } catch (error) {
      console.error('Failed to add product:', error);
      setSyncError(error instanceof Error ? error.message : 'Unable to add product');
    } finally {
      setSavingProduct(false);
    }
  };

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 space-y-6">

      {/* Page heading */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Product Management</h1>
          <p className="text-slate-500 text-sm mt-1">Manage fund listings, visibility and tier access</p>
        </div>
        <div className="flex items-center gap-3">
          {canRefreshLiveSchemes && (
            <button
              onClick={fetchProductsFromCybrilla}
              disabled={loadingProducts}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <RefreshCw className={`w-4 h-4 ${loadingProducts ? 'animate-spin' : ''}`} />
              {loadingProducts ? 'Fetching...' : 'Fetch from Cybrilla'}
            </button>
          )}
          <button
            onClick={() => setShowAddModal(true)}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors"
          >
            + Add New Product
          </button>
        </div>
      </div>

      {syncError && (
        <div className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
          <span>{syncError}</span>
        </div>
      )}

      {/* Asset class banner */}
      <div className="grid grid-cols-2 gap-4">
        {[
          { label: 'Mutual Funds (MF)',               count: mfCount,  sub: 'Equity · Debt · ELSS · Hybrid',  color: 'from-blue-600 to-blue-700',    filter: 'MF'  },
          { label: 'Strategic Investment Funds (SIF)', count: sifCount, sub: 'Strategic products',             color: 'from-violet-600 to-violet-700', filter: 'SIF' },
        ].map(item => (
          <button
            key={item.filter}
            onClick={() => { setAssetFilter(assetFilter === item.filter ? 'All' : item.filter); setPage(0); }}
            className={`bg-gradient-to-r ${item.color} text-white rounded-2xl p-5 text-left transition-all shadow-sm hover:shadow-md ${assetFilter === item.filter ? 'ring-2 ring-offset-2 ring-blue-400' : ''}`}
          >
            <div className="flex justify-between items-start">
              <div>
                <p className="text-sm font-semibold opacity-80">{item.label}</p>
                <p className="text-3xl font-bold mt-1">{item.count} <span className="text-base font-medium opacity-70">funds</span></p>
                <p className="text-xs opacity-60 mt-1">{item.sub}</p>
              </div>
              <span className="text-[10px] font-bold bg-white/20 px-2 py-0.5 rounded-full uppercase tracking-wider">Active</span>
            </div>
          </button>
        ))}
      </div>

      {/* Table card */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">

        {/* Search + filter bar */}
        <div className="p-4 border-b border-slate-100 flex flex-wrap gap-3 items-center">
          <div className="relative flex-1 min-w-[200px] max-w-sm">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text" value={search} onChange={e => { setSearch(e.target.value); setPage(0); }}
              placeholder="Search by fund name or AMC…"
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>
          <button
            onClick={() => setShowFilters(p => !p)}
            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium border rounded-lg transition-colors ${
              showFilters ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'
            }`}
          >
            <Filter className="w-4 h-4" /> Filter
            <ChevronDown className={`w-3.5 h-3.5 transition-transform ${showFilters ? 'rotate-180' : ''}`} />
          </button>
        </div>

        {/* Animated filter panel */}
        <AnimatePresence>
          {showFilters && (
            <motion.div
              initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
              className="overflow-hidden border-b border-slate-100"
            >
              <div className="p-4 bg-slate-50 flex flex-wrap gap-6">
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Asset Class</p>
                  <div className="flex gap-2">
                    {ASSET_CLASSES.map(c => (
                      <button key={c} onClick={() => { setAssetFilter(c); setPage(0); }}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${assetFilter === c ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {c}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Category</p>
                  <div className="flex gap-2 flex-wrap">
                    {CATEGORIES.map(c => (
                      <button key={c} onClick={() => { setCategoryFilter(c); setPage(0); }}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${categoryFilter === c ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {c}
                      </button>
                    ))}
                  </div>
                </div>
                {(assetFilter !== 'All' || categoryFilter !== 'All') && (
                  <button
                    onClick={() => { setAssetFilter('All'); setCategoryFilter('All'); setPage(0); }}
                    className="self-end flex items-center gap-1 text-xs text-red-500 hover:text-red-700 font-semibold"
                  >
                    <X className="w-3 h-3" /> Clear
                  </button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Table */}
        <div className="overflow-auto">
          {loadingProducts ? (
            <div className="p-16 text-center text-slate-500">
              <div className="animate-spin w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
              Fetching fund schemes from Cybrilla…
            </div>
          ) : filtered.length === 0 ? (
            <EmptyState
              icon={Layers}
              title="No products found"
              subtitle="Try adjusting your filters"
            />
          ) : (
          <table className="w-full text-left">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold sticky top-0 z-10">
              <tr>
                <th className="px-6 py-4">Product Name</th>
                <th className="px-6 py-4">Asset Class</th>
                <th className="px-6 py-4">NAV</th>
                <th className="px-6 py-4">Performance (1Y)</th>
                <th className="px-6 py-4">Min. Investment</th>
                <th className="px-6 py-4">Distributor Visibility</th>
                <th className="px-6 py-4">Risk</th>
                <th className="px-6 py-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filtered.map(p => (
                <tr
                  key={p.id}
                  className={`group hover:bg-slate-50 transition-colors ${p.status === 'Inactive' ? 'opacity-40' : ''}`}
                >
                  <td className="px-6 py-4">
                    <div className="font-semibold text-slate-800 max-w-[220px] truncate">{p.name}</div>
                    <div className="text-xs text-slate-400 mt-0.5">{p.amc} · {p.category}</div>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`px-2.5 py-1 text-xs font-bold rounded-md ${p.assetClass === 'MF' ? 'bg-blue-50 text-blue-700' : 'bg-violet-50 text-violet-700'}`}>
                      {p.assetClass}
                    </span>
                  </td>
                  <td className="px-6 py-4 font-mono text-sm text-slate-600">{p.nav}</td>
                  <td className="px-6 py-4">
                    <span className={`font-mono font-semibold text-sm ${parseFloat(p.return1y) > 0 ? 'text-green-600' : 'text-red-500'}`}>
                      {p.return1y}
                    </span>
                  </td>
                  <td className="px-6 py-4 font-mono text-sm text-slate-700">{p.minInvest}</td>
                  <td className="px-6 py-4">
                    <span className={`px-2.5 py-1 text-xs font-semibold rounded-md flex items-center gap-1 w-fit ${visibilityConfig[p.visibility] ?? 'bg-slate-100 text-slate-600'}`}>
                      {p.visibility !== 'All Tiers' && <Lock className="w-3 h-3" />}
                      {p.visibility}
                    </span>
                  </td>
                  <td className={`px-6 py-4 text-xs font-semibold ${riskColor[p.riskLevel] ?? 'text-slate-500'}`}>{p.riskLevel}</td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex items-center gap-1 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                      {/* Toggle active / inactive */}
                      <button
                        onClick={() => handleToggleStatus(p.id)}
                        disabled={productActionId === p.id}
                        title={p.status === 'Active' ? 'Deactivate (hides from distributor)' : 'Activate'}
                        className={`p-1.5 rounded-lg transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${p.status === 'Active' ? 'text-green-500 hover:bg-green-50' : 'text-slate-400 hover:bg-slate-100'}`}
                      >
                        {p.status === 'Active' ? <Eye className="w-4 h-4" /> : <EyeOff className="w-4 h-4" />}
                      </button>
                      {/* Delete */}
                      <button
                        onClick={() => handleDelete(p.id)}
                        disabled={productActionId === p.id}
                        title="Remove product"
                        className="p-1.5 text-red-400 hover:bg-red-50 rounded-lg transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          )}
        </div>
        <Pagination
          page={page}
          size={size}
          totalPages={totalPages}
          totalElements={totalElements}
          onPageChange={setPage}
          onSizeChange={nextSize => { setSize(nextSize); setPage(0); }}
        />
      </div>

      {/* Coming-soon section */}
      <div>
        <div className="flex items-center gap-3 mb-4">
          <Rocket className="w-5 h-5 text-slate-400" />
          <div>
            <h2 className="font-semibold text-slate-800">Upcoming Asset Classes</h2>
            <p className="text-xs text-slate-500">These product categories are on our roadmap</p>
          </div>
        </div>
        <div className="grid grid-cols-5 gap-4">
          {futureClasses.map(f => (
            <div key={f.code} className={`bg-gradient-to-br ${f.grad} rounded-2xl p-5 border ${f.border} relative overflow-hidden`}>
              <div className="absolute top-3 right-3">
                <span className="text-[9px] font-bold bg-white/60 text-slate-600 px-1.5 py-0.5 rounded-full uppercase tracking-wider">{f.eta}</span>
              </div>
              <div className="w-8 h-8 rounded-xl bg-white/50 flex items-center justify-center mb-3">
                <div className={`w-3 h-3 rounded-full ${f.dot}`} />
              </div>
              <p className={`text-lg font-bold ${f.accent}`}>{f.code}</p>
              <p className="text-xs text-slate-600 mt-0.5 leading-tight">{f.full}</p>
              <p className="text-[10px] text-slate-400 mt-2">Min: {f.minInvest}</p>
              <div className="mt-3 flex items-center gap-1">
                <Lock className={`w-3 h-3 ${f.accent}`} />
                <span className={`text-[10px] font-bold ${f.accent}`}>Coming Soon</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Add product modal */}
      <AnimatePresence>
        {showAddModal && (
          <AddProductModal
            onClose={() => setShowAddModal(false)}
            onAdd={handleAdd}
            saving={savingProduct}
          />
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Add Product Modal ────────────────────────────────────────────────────────

function AddProductModal({
  onClose,
  onAdd,
  saving,
}: {
  onClose: () => void;
  onAdd:   (p: Omit<Product, 'id'>) => Promise<void>;
  saving:  boolean;
}) {
  const dialogRef = useFocusTrap<HTMLDivElement>(true);
  const [assetClass,    setAssetClass]    = useState<'MF' | 'SIF'>('MF');
  const [name,          setName]          = useState('');
  const [amc,           setAmc]           = useState('');
  const [schemeCode,    setSchemeCode]    = useState('');
  const [isin,          setIsin]          = useState('');
  const [category,      setCategory]      = useState('Equity');
  const [riskLevel,     setRiskLevel]     = useState('Moderate');
  const [visibility,    setVisibility]    = useState('All Tiers');
  const [returnVal,     setReturnVal]     = useState('');
  const [minInvestVal,  setMinInvestVal]  = useState('');
  const [navVal,        setNavVal]        = useState('');

  const mfCategories  = ['Equity', 'Debt', 'ELSS', 'Hybrid', 'Liquid'];
  const sifCategories = ['Strategic Growth', 'Strategic Income'];

  const handleSubmit = async () => {
    if (!name.trim() || !amc.trim() || !schemeCode.trim() || saving) return;

    const ret = parseFloat(returnVal);
    const return1y = !isNaN(ret)
      ? (ret >= 0 ? `+${ret.toFixed(1)}` : ret.toFixed(1)) + '%'
      : '+0.0%';

    const navNum = parseFloat(navVal);
    const nav = !isNaN(navNum) ? `₹${navNum.toFixed(2)}` : '₹100.00';

    const minNum = parseInt(minInvestVal, 10);
    const minInvest = !isNaN(minNum) ? `₹${minNum.toLocaleString('en-IN')}` : '₹100';

    await onAdd({
      name: name.trim(),
      assetClass,
      category,
      amc: amc.trim(),
      return1y,
      minInvest,
      nav,
      riskLevel,
      visibility,
      status: 'Active',
      externalSchemeCode: schemeCode.trim(),
      externalIsin: isin.trim() || undefined,
      productType: assetClass === 'SIF' ? 'SIF' : 'MUTUAL_FUND',
    });
  };

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="add-product-title"
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
    >
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 20 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white w-full max-w-lg rounded-3xl shadow-xl relative z-10 overflow-hidden max-h-[90vh] overflow-y-auto"
      >
        <button onClick={onClose} aria-label="Close dialog" className="absolute top-4 right-4 p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors z-10">
          <X className="w-5 h-5" aria-hidden="true" />
        </button>

        <div className="p-8">
          <h2 id="add-product-title" className="text-xl font-semibold text-slate-800 mb-1">Add New Product</h2>
          <p className="text-sm text-slate-500 mb-6">New products immediately appear in the distributor catalog</p>

          <div className="space-y-5">

            {/* Asset class toggle */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Asset Class</label>
              <div className="flex bg-slate-100 p-1 rounded-xl gap-1">
                {(['MF', 'SIF'] as const).map(c => (
                  <button key={c} onClick={() => { setAssetClass(c); setCategory(c === 'MF' ? 'Equity' : 'Strategic Growth'); }}
                    className={`flex-1 py-2 text-sm font-semibold rounded-lg transition-all ${assetClass === c ? 'bg-white shadow-sm text-slate-900' : 'text-slate-500'}`}>
                    {c === 'MF' ? 'Mutual Fund' : 'Strategic Investment Fund (SIF)'}
                  </button>
                ))}
              </div>
              <div className="mt-2 flex flex-wrap gap-1.5">
                {['PMS', 'AIF', 'NPS', 'Bonds', 'Insurance'].map(fc => (
                  <span key={fc} className="px-2 py-0.5 text-[10px] font-semibold bg-slate-100 text-slate-400 rounded flex items-center gap-1 cursor-not-allowed">
                    <Lock className="w-2.5 h-2.5" /> {fc}
                  </span>
                ))}
              </div>
              <p className="text-[10px] text-slate-400 mt-1">More asset classes coming soon</p>
            </div>

            {/* Fund name */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Fund Name</label>
              <input type="text" value={name} onChange={e => setName(e.target.value)}
                placeholder="e.g. HDFC Large & Mid Cap Fund"
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Scheme Code</label>
                <input type="text" value={schemeCode} onChange={e => setSchemeCode(e.target.value.toUpperCase())}
                  placeholder="e.g. MF-HDFC-001"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm font-mono focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
              </div>
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">ISIN</label>
                <input type="text" value={isin} onChange={e => setIsin(e.target.value.toUpperCase())}
                  placeholder="Optional"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm font-mono focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
              </div>
            </div>

            {/* Category + Risk */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Category</label>
                <select value={category} onChange={e => setCategory(e.target.value)}
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                  {(assetClass === 'MF' ? mfCategories : sifCategories).map(c => <option key={c}>{c}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Risk Level</label>
                <select value={riskLevel} onChange={e => setRiskLevel(e.target.value)}
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                  {['Low', 'Moderate', 'High', 'Very High'].map(r => <option key={r}>{r}</option>)}
                </select>
              </div>
            </div>

            {/* AMC */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">AMC / Fund House</label>
              <input type="text" value={amc} onChange={e => setAmc(e.target.value)}
                placeholder="e.g. HDFC AMC"
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
            </div>

            {/* Return + NAV */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">1Y Return (%)</label>
                <div className="relative">
                  <input type="number" value={returnVal} onChange={e => setReturnVal(e.target.value)}
                    placeholder="e.g. 28.4"
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 pr-8 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
                  <span className="absolute right-3 top-2.5 text-slate-400 text-sm">%</span>
                </div>
              </div>
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Current NAV (₹)</label>
                <div className="relative">
                  <span className="absolute left-4 top-2.5 text-slate-500 text-sm">₹</span>
                  <input type="number" value={navVal} onChange={e => setNavVal(e.target.value)}
                    placeholder="e.g. 245.60"
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-8 pr-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
                </div>
              </div>
            </div>

            {/* Min. Investment */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Min. Investment (₹)</label>
              <div className="relative">
                <span className="absolute left-4 top-2.5 text-slate-500 text-sm">₹</span>
                <input type="number" value={minInvestVal} onChange={e => setMinInvestVal(e.target.value)}
                  placeholder="e.g. 500"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-8 pr-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
              </div>
            </div>

            {/* Distributor visibility */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Distributor Visibility</label>
              <select value={visibility} onChange={e => setVisibility(e.target.value)}
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                {['All Tiers', 'Silver & Above', 'Gold & Above', 'Platinum Only'].map(v => <option key={v}>{v}</option>)}
              </select>
              <p className="text-xs text-slate-400 mt-1">Controls which distributor tiers can see and trade this product</p>
            </div>
          </div>

          <div className="mt-8 flex gap-3">
            <button onClick={onClose} disabled={saving} className="px-5 py-2.5 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors disabled:opacity-50 disabled:cursor-not-allowed">Cancel</button>
            <button
              onClick={handleSubmit}
              disabled={!name.trim() || !amc.trim() || !schemeCode.trim() || saving}
              className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {saving ? 'Saving...' : 'Add Product'}
            </button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
