import React, { useEffect, useMemo, useState } from 'react';
import {
  CalendarDays,
  Download,
  FileSpreadsheet,
  FileText,
  Filter,
  RefreshCw,
  Scale,
  Search,
  TrendingUp,
} from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { apiFetch } from '../config/api';
import { formatDate } from '../utils/formatDate';
import EmptyState from '../components/EmptyState';

type Column = { key: string; label: string };

type CapitalGainsSummary = {
  totalSaleValue?: number;
  totalStcg?: number;
  totalLtcg?: number;
  totalGrandfatheredCost?: number;
};

type CapitalGainsReportResponse = {
  distributorId?: string;
  financialYear?: string;
  periodStart?: string;
  periodEnd?: string;
  generatedAt?: string;
  totalSaleValue?: number;
  totalPurchaseCost?: number;
  totalGrandfatheredCost?: number;
  shortTermCapitalGain?: number;
  longTermCapitalGain?: number;
  summary?: CapitalGainsSummary;
  lineItems?: Record<string, unknown>[];
};

const REPORT_TYPES = [
  {
    id: 'capital-gains',
    label: 'Capital Gains FY Statement',
    desc: 'STCG/LTCG split, grandfathering and ITR-ready CSV exports',
  },
  { id: 'investors', label: 'Investor Report', desc: 'All investors with KYC and status details' },
  { id: 'transactions', label: 'Transaction Report', desc: 'All orders with type, amount and status' },
  { id: 'sip', label: 'SIP Report', desc: 'SIP and recurring plan transactions' },
  { id: 'leads', label: 'Lead Conversion Report', desc: 'Lead pipeline and conversion tracking' },
  { id: 'actions', label: 'Pending Actions Report', desc: 'Outstanding KYC, bank and order issues' },
  { id: 'aum', label: 'AUM Report', desc: 'AUM breakdown by investor and category' },
];

const COLUMNS: Record<string, Column[]> = {
  'capital-gains': [
    { key: 'investorName', label: 'Investor' },
    { key: 'investorPan', label: 'PAN' },
    { key: 'schemeName', label: 'Scheme' },
    { key: 'isin', label: 'ISIN' },
    { key: 'purchaseDate', label: 'Purchase Date' },
    { key: 'saleDate', label: 'Sale Date' },
    { key: 'units', label: 'Units' },
    { key: 'saleValue', label: 'Sale Value' },
    { key: 'taxableCost', label: 'Taxable Cost' },
    { key: 'fairMarketValueAsOf20180131', label: '31 Jan 2018 FMV' },
    { key: 'capitalGain', label: 'Gain/Loss' },
    { key: 'gainType', label: 'Type' },
  ],
  investors: [
    { key: 'fullName', label: 'Name' },
    { key: 'pan', label: 'PAN' },
    { key: 'email', label: 'Email' },
    { key: 'city', label: 'City' },
    { key: 'investorStatus', label: 'Status' },
    { key: 'kycStatus', label: 'KYC' },
    { key: 'bankVerificationStatus', label: 'Bank' },
    { key: 'riskProfile', label: 'Risk' },
  ],
  transactions: [
    { key: 'transactionType', label: 'Type' },
    { key: 'orderStatus', label: 'Status' },
    { key: 'amount', label: 'Amount (INR)' },
    { key: 'paymentMode', label: 'Payment Mode' },
    { key: 'productCategory', label: 'Category' },
    { key: 'createdAt', label: 'Date' },
  ],
  sip: [
    { key: 'transactionType', label: 'Type' },
    { key: 'orderStatus', label: 'Status' },
    { key: 'amount', label: 'Amount (INR)' },
    { key: 'mandateMode', label: 'Mandate Mode' },
    { key: 'createdAt', label: 'Date' },
  ],
  leads: [
    { key: 'prospectName', label: 'Name' },
    { key: 'mobileNumber', label: 'Mobile' },
    { key: 'email', label: 'Email' },
    { key: 'city', label: 'City' },
    { key: 'source', label: 'Source' },
    { key: 'status', label: 'Status' },
  ],
  actions: [
    { key: 'investorName', label: 'Investor' },
    { key: 'issueType', label: 'Issue Type' },
    { key: 'priority', label: 'Priority' },
    { key: 'description', label: 'Description' },
  ],
  aum: [
    { key: 'transactionType', label: 'Type' },
    { key: 'orderStatus', label: 'Status' },
    { key: 'amount', label: 'Amount (INR)' },
    { key: 'productCategory', label: 'Category' },
    { key: 'createdAt', label: 'Date' },
  ],
};

const MONEY_KEYS = new Set([
  'amount',
  'saleValue',
  'purchaseCost',
  'taxableCost',
  'fairMarketValueAsOf20180131',
  'grandfatheredCost',
  'capitalGain',
]);

const DATE_KEYS = new Set(['createdAt', 'updatedAt', 'purchaseDate', 'saleDate']);

function getCurrentFinancialYear() {
  const today = new Date();
  const year = today.getFullYear();
  const start = today.getMonth() >= 3 ? year : year - 1;
  return `${start}-${start + 1}`;
}

function getFinancialYearOptions() {
  const currentStart = Number(getCurrentFinancialYear().slice(0, 4));
  return Array.from({ length: 5 }, (_, index) => {
    const start = currentStart - index;
    return `${start}-${start + 1}`;
  });
}

function formatCurrency(value: unknown) {
  const number = Number(value ?? 0);
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(Number.isFinite(number) ? number : 0);
}

function formatUnits(value: unknown) {
  const number = Number(value ?? 0);
  return new Intl.NumberFormat('en-IN', {
    maximumFractionDigits: 4,
  }).format(Number.isFinite(number) ? number : 0);
}

function formatCell(key: string, value: unknown) {
  if (DATE_KEYS.has(key)) return value ? formatDate(String(value)) : '-';
  if (MONEY_KEYS.has(key)) return formatCurrency(value);
  if (key === 'units') return formatUnits(value);
  return String(value ?? '-');
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function downloadCsv(rows: Record<string, unknown>[], filename: string, columns: Column[]) {
  const header = columns.map(c => c.label).join(',');
  const body = rows.map(row =>
    columns.map(c => `"${formatCell(c.key, row[c.key]).replace(/"/g, '""')}"`).join(','),
  );
  const blob = new Blob([[header, ...body].join('\n')], { type: 'text/csv;charset=utf-8' });
  downloadBlob(blob, filename);
}

export default function Reports({ userData }: { userData?: any }) {
  const [selected, setSelected] = useState<string>('capital-gains');
  const [rawData, setRawData] = useState<Record<string, unknown>[]>([]);
  const [capitalGainsReport, setCapitalGainsReport] = useState<CapitalGainsReportResponse | null>(null);
  const [financialYear, setFinancialYear] = useState(getCurrentFinancialYear());
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [error, setError] = useState('');

  const fyOptions = useMemo(() => getFinancialYearOptions(), []);
  const columns = selected ? (COLUMNS[selected] ?? []) : [];

  const getHeaders = () => ({ 'Content-Type': 'application/json' });

  const fetchReport = async (type: string) => {
    if (!userData?.id) return;

    setSelected(type);
    setLoading(true);
    setRawData([]);
    setSearch('');
    setStatusFilter('');
    setError('');

    try {
      if (type === 'capital-gains') {
        const response = await apiFetch(
          `/reports/distributor/${userData.id}/capital-gains?financialYear=${encodeURIComponent(financialYear)}`,
          { headers: getHeaders() },
        );

        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const report = (await response.json()) as CapitalGainsReportResponse;
        setCapitalGainsReport(report);
        setRawData(Array.isArray(report.lineItems) ? report.lineItems : []);
        return;
      }

      setCapitalGainsReport(null);

      const urlMap: Record<string, string> = {
        investors: `/investors/by-distributor/${userData.id}`,
        transactions: `/orders/by-distributor/${userData.id}`,
        sip: `/orders/by-distributor/${userData.id}`,
        leads: `/leads/distributor/${userData.id}`,
        actions: `/dashboard/distributor/${userData.id}/actions`,
        aum: `/orders/by-distributor/${userData.id}`,
      };

      const response = await apiFetch(urlMap[type], { headers: getHeaders() });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const data = await response.json();
      let rows = Array.isArray(data) ? data : (data?.items ?? data?.actions ?? []);

      if (type === 'sip') {
        rows = rows.filter((order: any) =>
          ['SIP', 'SWP', 'STP'].includes(String(order.transactionType ?? '').toUpperCase()),
        );
      }

      setRawData(rows);
    } catch (err) {
      setRawData([]);
      setError(err instanceof Error ? err.message : 'Unable to load report');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (userData?.id) fetchReport('capital-gains');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userData?.id, financialYear]);

  const filteredData = useMemo(() => {
    return rawData.filter(row => {
      const blob = JSON.stringify(row).toLowerCase();
      const matchSearch = !search || blob.includes(search.toLowerCase());
      const anyStatus = row.status || row.orderStatus || row.investorStatus || row.kycStatus || row.gainType || '';
      const matchStatus = !statusFilter || String(anyStatus).toLowerCase().includes(statusFilter.toLowerCase());
      return matchSearch && matchStatus;
    });
  }, [rawData, search, statusFilter]);

  const downloadCapitalGainsCsv = async (format: 'QUICKO' | 'CLEARTAX') => {
    if (!userData?.id) return;

    setExporting(format);
    setError('');

    try {
      const response = await apiFetch(
        `/reports/distributor/${userData.id}/capital-gains/export?financialYear=${encodeURIComponent(financialYear)}&format=${format}`,
        { headers: getHeaders() },
      );

      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const blob = await response.blob();
      downloadBlob(blob, `capital_gains_${financialYear}_${format.toLowerCase()}.csv`);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to export report');
    } finally {
      setExporting(null);
    }
  };

  const summary = {
    totalSaleValue: capitalGainsReport?.totalSaleValue ?? capitalGainsReport?.summary?.totalSaleValue,
    totalStcg: capitalGainsReport?.shortTermCapitalGain ?? capitalGainsReport?.summary?.totalStcg,
    totalLtcg: capitalGainsReport?.longTermCapitalGain ?? capitalGainsReport?.summary?.totalLtcg,
    totalGrandfatheredCost:
      capitalGainsReport?.totalGrandfatheredCost ?? capitalGainsReport?.summary?.totalGrandfatheredCost,
  };
  const periodLabel =
    capitalGainsReport?.periodStart && capitalGainsReport?.periodEnd
      ? `${formatDate(capitalGainsReport.periodStart)} to ${formatDate(capitalGainsReport.periodEnd)}`
      : '';

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Reports</h1>
          <p className="text-sm text-slate-500 mt-0.5">
            View and download platform reports, including the Capital Gains FY Statement.
          </p>
        </div>

        <div className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2">
          <CalendarDays className="w-4 h-4 text-slate-400" />
          <select
            value={financialYear}
            onChange={event => setFinancialYear(event.target.value)}
            className="text-sm font-medium text-slate-700 bg-transparent focus:outline-none"
          >
            {fyOptions.map(option => (
              <option key={option} value={option}>
                FY {option}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3">
        {REPORT_TYPES.map(rt => (
          <button
            key={rt.id}
            onClick={() => selected === rt.id ? setSelected(null) : fetchReport(rt.id)}
            className={`text-left p-4 rounded-2xl border transition-all ${
              selected === rt.id
                ? 'bg-blue-50 border-blue-300 shadow-sm'
                : 'bg-white border-slate-100 hover:border-slate-200 hover:shadow-sm'
            }`}
          >
            <FileText className={`w-4 h-4 mb-2 ${selected === rt.id ? 'text-blue-600' : 'text-slate-400'}`} />
            <p className={`text-sm font-semibold ${selected === rt.id ? 'text-blue-700' : 'text-slate-700'}`}>
              {rt.label}
            </p>
            <p className="text-xs text-slate-400 mt-0.5">{rt.desc}</p>
          </button>
        ))}
      </div>

      {selected === 'capital-gains' && (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3 mb-6">
          <div className="bg-white border border-slate-100 rounded-2xl p-4 shadow-sm">
            <FileSpreadsheet className="w-4 h-4 text-blue-500 mb-2" />
            <p className="text-xs text-slate-400">Sale Value</p>
            <p className="text-lg font-bold text-slate-800">{formatCurrency(summary.totalSaleValue)}</p>
          </div>
          <div className="bg-white border border-slate-100 rounded-2xl p-4 shadow-sm">
            <TrendingUp className="w-4 h-4 text-amber-500 mb-2" />
            <p className="text-xs text-slate-400">Short-Term Gain</p>
            <p className="text-lg font-bold text-slate-800">{formatCurrency(summary.totalStcg)}</p>
          </div>
          <div className="bg-white border border-slate-100 rounded-2xl p-4 shadow-sm">
            <Scale className="w-4 h-4 text-emerald-500 mb-2" />
            <p className="text-xs text-slate-400">Long-Term Gain</p>
            <p className="text-lg font-bold text-slate-800">{formatCurrency(summary.totalLtcg)}</p>
          </div>
          <div className="bg-white border border-slate-100 rounded-2xl p-4 shadow-sm">
            <FileText className="w-4 h-4 text-violet-500 mb-2" />
            <p className="text-xs text-slate-400">Grandfathered Cost</p>
            <p className="text-lg font-bold text-slate-800">{formatCurrency(summary.totalGrandfatheredCost)}</p>
          </div>
        </div>
      )}

      {/* Results table */}
      <AnimatePresence>
      {selected && (
        <motion.div
          key={selected}
          initial={{ opacity: 0, y: -6 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -6 }}
          transition={{ duration: 0.18 }}
          className="bg-white rounded-2xl border border-slate-100 shadow-sm"
        >
          {/* Toolbar */}
          <div className="p-4 border-b border-slate-100 flex items-center justify-between gap-3 flex-wrap">
            <div className="flex items-center gap-3 flex-wrap flex-1">
              <div className="relative w-60">
                <Search className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-400" />
                <input
                  type="text"
                  placeholder="Search..."
                  value={search}
                  onChange={event => setSearch(event.target.value)}
                  className="w-full pl-8 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>
              <div className="relative w-44">
                <Filter className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-400" />
                <input
                  type="text"
                  placeholder="Filter by status..."
                  value={statusFilter}
                  onChange={event => setStatusFilter(event.target.value)}
                  className="w-full pl-8 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>
              {!loading && <p className="text-xs text-slate-400">{filteredData.length} rows</p>}
              {periodLabel && selected === 'capital-gains' && (
                <p className="text-xs text-slate-400">Period {periodLabel}</p>
              )}
            </div>

            <div className="flex items-center gap-2 flex-wrap">
              <button
                onClick={() => fetchReport(selected)}
                disabled={loading}
                className="flex items-center gap-1.5 px-3 py-2 text-sm font-medium border border-slate-200 text-slate-600 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-40"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} /> Refresh
              </button>
              {selected === 'capital-gains' ? (
                <>
                  <button
                    onClick={() => downloadCapitalGainsCsv('QUICKO')}
                    disabled={loading || exporting !== null}
                    className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium bg-slate-800 text-white rounded-lg hover:bg-slate-900 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <Download className="w-3.5 h-3.5" /> Quicko CSV
                  </button>
                  <button
                    onClick={() => downloadCapitalGainsCsv('CLEARTAX')}
                    disabled={loading || exporting !== null}
                    className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <Download className="w-3.5 h-3.5" /> ClearTax CSV
                  </button>
                </>
              ) : (
                <button
                  onClick={() => downloadCsv(filteredData, `${selected}_report.csv`, columns)}
                  disabled={filteredData.length === 0}
                  className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium bg-slate-800 text-white rounded-lg hover:bg-slate-900 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <Download className="w-3.5 h-3.5" /> Download CSV
                </button>
              )}
            </div>
          </div>

          {error && (
            <div className="mx-4 mt-4 rounded-lg border border-red-100 bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </div>
          )}

          {loading ? (
            <div className="flex items-center justify-center h-52">
              <div className="w-7 h-7 border-4 border-blue-500 border-t-transparent rounded-full animate-spin" />
            </div>
          ) : filteredData.length === 0 ? (
            <EmptyState icon={FileText} title="No data available" />
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50">
                    <tr>
                      {columns.map(col => (
                        <th
                          key={col.key}
                          className="text-left text-xs font-semibold text-slate-400 uppercase tracking-wider px-4 py-3 whitespace-nowrap"
                        >
                          {col.label}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-50">
                    {filteredData.slice(0, 100).map((row, index) => (
                      <tr key={index} className="hover:bg-slate-50/80">
                        {columns.map(col => (
                          <td key={col.key} className="px-4 py-3 text-slate-700 whitespace-nowrap">
                            {formatCell(col.key, row[col.key])}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {filteredData.length > 100 && (
                <p className="text-center text-xs text-slate-400 py-3 border-t border-slate-50">
                  Showing 100 of {filteredData.length} rows. Download CSV for full data.
                </p>
              )}
            </>
          )}
        </motion.div>
      )}
      </AnimatePresence>
    </div>
  );
}
