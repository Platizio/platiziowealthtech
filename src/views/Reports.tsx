import React, { useState } from 'react';
import { FileText, Download, Search, Filter } from 'lucide-react';
import { apiFetch } from '../config/api';
<<<<<<< HEAD
import { formatDate } from '../utils/formatDate';
=======
import EmptyState from '../components/EmptyState';
>>>>>>> 9097ae05093f32c59233f39e83ef0a37300917e0

const REPORT_TYPES = [
  { id: 'investors',    label: 'Investor Report',         desc: 'All investors with KYC and status details' },
  { id: 'transactions', label: 'Transaction Report',      desc: 'All orders with type, amount and status' },
  { id: 'sip',         label: 'SIP Report',              desc: 'SIP and recurring plan transactions' },
  { id: 'leads',       label: 'Lead Conversion Report',  desc: 'Lead pipeline and conversion tracking' },
  { id: 'actions',     label: 'Pending Actions Report',  desc: 'Outstanding KYC, bank and order issues' },
  { id: 'aum',         label: 'AUM Report',              desc: 'AUM breakdown by investor and category' },
];

const COLUMNS: Record<string, { key: string; label: string }[]> = {
  investors: [
    { key: 'fullName',              label: 'Name' },
    { key: 'pan',                   label: 'PAN' },
    { key: 'email',                 label: 'Email' },
    { key: 'city',                  label: 'City' },
    { key: 'investorStatus',        label: 'Status' },
    { key: 'kycStatus',             label: 'KYC' },
    { key: 'bankVerificationStatus',label: 'Bank' },
    { key: 'riskProfile',           label: 'Risk' },
  ],
  transactions: [
    { key: 'transactionType', label: 'Type' },
    { key: 'orderStatus',     label: 'Status' },
    { key: 'amount',          label: 'Amount (₹)' },
    { key: 'paymentMode',     label: 'Payment Mode' },
    { key: 'productCategory', label: 'Category' },
    { key: 'createdAt',       label: 'Date' },
  ],
  sip: [
    { key: 'transactionType', label: 'Type' },
    { key: 'orderStatus',     label: 'Status' },
    { key: 'amount',          label: 'Amount (₹)' },
    { key: 'mandateMode',     label: 'Mandate Mode' },
    { key: 'createdAt',       label: 'Date' },
  ],
  leads: [
    { key: 'prospectName',  label: 'Name' },
    { key: 'mobileNumber',  label: 'Mobile' },
    { key: 'email',         label: 'Email' },
    { key: 'city',          label: 'City' },
    { key: 'source',        label: 'Source' },
    { key: 'status',        label: 'Status' },
  ],
  actions: [
    { key: 'investorName', label: 'Investor' },
    { key: 'issueType',    label: 'Issue Type' },
    { key: 'priority',     label: 'Priority' },
    { key: 'description',  label: 'Description' },
  ],
  aum: [
    { key: 'transactionType', label: 'Type' },
    { key: 'orderStatus',     label: 'Status' },
    { key: 'amount',          label: 'Amount (₹)' },
    { key: 'productCategory', label: 'Category' },
    { key: 'createdAt',       label: 'Date' },
  ],
};

function downloadCsv(rows: any[], filename: string, columns: { key: string; label: string }[]) {
  const header = columns.map(c => c.label).join(',');
  const body   = rows.map(row =>
    columns.map(c => {
      let val = row[c.key];
      if (c.key === 'createdAt' || c.key === 'updatedAt') val = formatDate(val);
      return `"${String(val ?? '').replace(/"/g, '""')}"`;
    }).join(',')
  );
  const blob = new Blob([[header, ...body].join('\n')], { type: 'text/csv' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href     = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export default function Reports({ userData }: { userData?: any }) {
  const [selected, setSelected]       = useState<string | null>(null);
  const [rawData, setRawData]         = useState<any[]>([]);
  const [loading, setLoading]         = useState(false);
  const [search, setSearch]           = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  const getHeaders = () => {
    return { 'Content-Type': 'application/json' };
  };

  const fetchReport = (type: string) => {
    if (!userData?.id) return;
    setSelected(type);
    setLoading(true);
    setRawData([]);
    setSearch('');
    setStatusFilter('');

    const urlMap: Record<string, string> = {
      investors:    `/investors/by-distributor/${userData.id}`,
      transactions: `/orders/by-distributor/${userData.id}`,
      sip:          `/orders/by-distributor/${userData.id}`,
      leads:        `/leads/distributor/${userData.id}`,
      actions:      `/dashboard/distributor/${userData.id}/actions`,
      aum:          `/orders/by-distributor/${userData.id}`,
    };

    apiFetch(urlMap[type], { headers: getHeaders() })
      .then(r => r.ok ? r.json() : [])
      .then(d => {
        let rows = Array.isArray(d) ? d : (d?.items ?? d?.actions ?? []);
        if (type === 'sip') {
          rows = rows.filter((o: any) => o.transactionType === 'SIP' || o.transactionType === 'SWP' || o.transactionType === 'STP');
        }
        setRawData(rows);
      })
      .catch(() => setRawData([]))
      .finally(() => setLoading(false));
  };

  const columns = selected ? (COLUMNS[selected] ?? []) : [];

  const filteredData = rawData.filter(row => {
    const blob       = JSON.stringify(row).toLowerCase();
    const matchSearch = !search || blob.includes(search.toLowerCase());
    const anyStatus   = row.status || row.orderStatus || row.investorStatus || row.kycStatus || '';
    const matchStatus = !statusFilter || anyStatus.toLowerCase().includes(statusFilter.toLowerCase());
    return matchSearch && matchStatus;
  });

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-xl font-bold text-slate-800">Reports</h1>
        <p className="text-sm text-slate-500 mt-0.5">View and download platform reports</p>
      </div>

      {/* Report type cards */}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
        {REPORT_TYPES.map(rt => (
          <button
            key={rt.id}
            onClick={() => fetchReport(rt.id)}
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

      {/* Results table */}
      {selected && (
        <div className="bg-white rounded-2xl border border-slate-100 shadow-sm">
          {/* Toolbar */}
          <div className="p-4 border-b border-slate-100 flex items-center justify-between gap-3 flex-wrap">
            <div className="flex items-center gap-3 flex-wrap flex-1">
              <div className="relative w-60">
                <Search className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-400" />
                <input
                  type="text"
                  placeholder="Search…"
                  value={search}
                  onChange={e => setSearch(e.target.value)}
                  className="w-full pl-8 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>
              <div className="relative w-44">
                <Filter className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-400" />
                <input
                  type="text"
                  placeholder="Filter by status…"
                  value={statusFilter}
                  onChange={e => setStatusFilter(e.target.value)}
                  className="w-full pl-8 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>
              {!loading && (
                <p className="text-xs text-slate-400">{filteredData.length} rows</p>
              )}
            </div>
            <button
              onClick={() => downloadCsv(filteredData, `${selected}_report.csv`, columns)}
              disabled={filteredData.length === 0}
              className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium bg-slate-800 text-white rounded-lg hover:bg-slate-900 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <Download className="w-3.5 h-3.5" /> Download CSV
            </button>
          </div>

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
                    {filteredData.slice(0, 100).map((row, i) => (
                      <tr key={i} className="hover:bg-slate-50/80">
                        {columns.map(col => (
                          <td key={col.key} className="px-4 py-3 text-slate-700 whitespace-nowrap">
                            {col.key === 'createdAt' || col.key === 'updatedAt' ? formatDate(row[col.key]) : String(row[col.key] ?? '—')}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {filteredData.length > 100 && (
                <p className="text-center text-xs text-slate-400 py-3 border-t border-slate-50">
                  Showing 100 of {filteredData.length} rows — download CSV for full data.
                </p>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
