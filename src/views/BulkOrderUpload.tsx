import React, { useMemo, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  FileSpreadsheet,
  Loader2,
  Send,
  Upload,
  XCircle,
} from 'lucide-react';
import { apiFetch } from '../config/api';

type UploadStatus = 'VALIDATED' | 'VALIDATION_FAILED' | 'SUBMITTED' | 'SUBMISSION_FAILED';

type BulkOrderRowResult = {
  rowNumber: number;
  investorId?: string;
  productSchemeId?: string;
  schemeCode?: string;
  schemeName?: string;
  transactionType?: string;
  amount?: number;
  units?: number;
  status: UploadStatus;
  orderId?: string;
  externalOrderId?: string;
  investorActionUrl?: string;
  errors?: string[];
};

type BulkOrderUploadResponse = {
  totalRows: number;
  validRows: number;
  submittedRows: number;
  failedRows: number;
  batchSize: number;
  platform: string;
  dryRun: boolean;
  rows: BulkOrderRowResult[];
};

const SAMPLE_CSV = [
  'investorId,schemeCode,transactionType,amount,units,paymentMode,mandateMode,sipFrequency,sipStartDate,sipInstalments',
  '00000000-0000-0000-0000-000000000000,MF-201,LUMPSUM_PURCHASE,2500,,NET_BANKING,,,,',
  '00000000-0000-0000-0000-000000000001,MF-201,SIP,1000,,MANDATE,AUTO_DEBIT,MONTHLY,2026-07-01,12',
].join('\n');

const STATUS_STYLE: Record<UploadStatus, string> = {
  VALIDATED: 'bg-blue-50 text-blue-700 border-blue-100',
  VALIDATION_FAILED: 'bg-red-50 text-red-700 border-red-100',
  SUBMITTED: 'bg-emerald-50 text-emerald-700 border-emerald-100',
  SUBMISSION_FAILED: 'bg-amber-50 text-amber-700 border-amber-100',
};

const STATUS_LABEL: Record<UploadStatus, string> = {
  VALIDATED: 'Validated',
  VALIDATION_FAILED: 'Invalid',
  SUBMITTED: 'Submitted',
  SUBMISSION_FAILED: 'Failed',
};

function downloadText(filename: string, content: string) {
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function formatMoney(value?: number) {
  if (value === undefined || value === null) return '-';
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(Number(value));
}

export default function BulkOrderUpload({ userData }: { userData?: any }) {
  const [file, setFile] = useState<File | null>(null);
  const [platform, setPlatform] = useState('AUTO');
  const [batchSize, setBatchSize] = useState(25);
  const [dragging, setDragging] = useState(false);
  const [submittingMode, setSubmittingMode] = useState<'validate' | 'submit' | null>(null);
  const [result, setResult] = useState<BulkOrderUploadResponse | null>(null);
  const [error, setError] = useState('');

  const canUpload = Boolean(file && userData?.id && !submittingMode);

  const invalidRows = useMemo(
    () => result?.rows?.filter(row => row.status === 'VALIDATION_FAILED' || row.status === 'SUBMISSION_FAILED') ?? [],
    [result],
  );

  const handleFile = (nextFile?: File | null) => {
    if (!nextFile) return;
    setError('');
    setResult(null);
    if (!nextFile.name.toLowerCase().endsWith('.csv')) {
      setFile(null);
      setError('Please select a CSV file.');
      return;
    }
    setFile(nextFile);
  };

  const uploadCsv = async (dryRun: boolean) => {
    if (!file) return;
    setSubmittingMode(dryRun ? 'validate' : 'submit');
    setError('');

    try {
      const params = new URLSearchParams({
        dryRun: String(dryRun),
        platform,
        batchSize: String(batchSize),
      });
      const form = new FormData();
      form.append('file', file);

      const response = await apiFetch(`/orders/bulk-upload?${params.toString()}`, {
        method: 'POST',
        body: form,
      });
      const payload = await response.json().catch(() => null);
      if (!response.ok) {
        throw new Error(payload?.message || `Upload failed (${response.status})`);
      }
      setResult(payload);
    } catch (err: any) {
      setError(err?.message || 'Bulk order upload failed.');
    } finally {
      setSubmittingMode(null);
    }
  };

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Distributor Tools</h1>
          <p className="text-sm text-slate-500 mt-0.5">Bulk Order Upload</p>
        </div>
        <button
          onClick={() => downloadText('bulk_order_upload_sample.csv', SAMPLE_CSV)}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50"
        >
          <Download className="w-4 h-4" />
          Sample CSV
        </button>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_360px] gap-4">
        <section className="bg-white rounded-2xl border border-slate-100 shadow-sm p-5 space-y-4">
          <label
            onDragOver={event => {
              event.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={event => {
              event.preventDefault();
              setDragging(false);
              handleFile(event.dataTransfer.files?.[0]);
            }}
            className={`block rounded-2xl border-2 border-dashed p-8 text-center cursor-pointer transition-colors ${
              dragging ? 'border-blue-400 bg-blue-50' : 'border-slate-200 hover:border-slate-300'
            }`}
          >
            <input
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              onChange={event => handleFile(event.target.files?.[0])}
            />
            <Upload className={`w-10 h-10 mx-auto mb-3 ${dragging ? 'text-blue-500' : 'text-slate-300'}`} />
            <p className="text-sm font-semibold text-slate-800">
              {file ? file.name : 'Drop CSV file or browse'}
            </p>
            <p className="text-xs text-slate-400 mt-1">CSV only, up to 1,000 order rows</p>
          </label>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <label className="space-y-1.5">
              <span className="text-xs font-semibold uppercase tracking-wide text-slate-400">Exchange route</span>
              <select
                value={platform}
                onChange={event => setPlatform(event.target.value)}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-100"
              >
                <option value="AUTO">Auto</option>
                <option value="BSE_STAR_MF">BSE Star MF</option>
                <option value="NSE_NMF_II">NSE NMF II</option>
              </select>
            </label>
            <label className="space-y-1.5">
              <span className="text-xs font-semibold uppercase tracking-wide text-slate-400">Batch size</span>
              <input
                type="number"
                min={1}
                max={100}
                value={batchSize}
                onChange={event => setBatchSize(Number(event.target.value))}
                className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-100"
              />
            </label>
          </div>

          {error && (
            <div className="flex items-start gap-2 rounded-xl border border-red-100 bg-red-50 px-3 py-2 text-sm text-red-700">
              <AlertTriangle className="w-4 h-4 mt-0.5" />
              <span>{error}</span>
            </div>
          )}

          <div className="flex items-center gap-2 flex-wrap">
            <button
              onClick={() => uploadCsv(true)}
              disabled={!canUpload}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-slate-200 bg-white text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {submittingMode === 'validate' ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />}
              Validate
            </button>
            <button
              onClick={() => uploadCsv(false)}
              disabled={!canUpload}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-[#0B1B3E] text-white text-sm font-medium hover:bg-[#1A3066] disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {submittingMode === 'submit' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
              Submit Orders
            </button>
          </div>
        </section>

        <aside className="bg-white rounded-2xl border border-slate-100 shadow-sm p-5 space-y-4">
          <div className="flex items-center gap-2">
            <FileSpreadsheet className="w-5 h-5 text-blue-500" />
            <h2 className="font-semibold text-slate-800">CSV columns</h2>
          </div>
          <div className="grid grid-cols-1 gap-2 text-sm text-slate-600">
            {[
              'investorId',
              'schemeCode or productSchemeId',
              'transactionType',
              'amount or units',
              'paymentMode',
              'mandateMode',
              'sipFrequency',
              'sipStartDate',
              'sipInstalments',
            ].map(item => (
              <div key={item} className="rounded-lg bg-slate-50 px-3 py-2">
                {item}
              </div>
            ))}
          </div>
        </aside>
      </div>

      {result && (
        <section className="bg-white rounded-2xl border border-slate-100 shadow-sm overflow-hidden">
          <div className="p-4 border-b border-slate-100 flex items-center justify-between gap-3 flex-wrap">
            <div>
              <h2 className="font-semibold text-slate-800">
                {result.dryRun ? 'Validation Result' : 'Submission Result'}
              </h2>
              <p className="text-xs text-slate-400 mt-0.5">
                {(result.platform ?? '').replaceAll('_', ' ')} - batch {result.batchSize}
              </p>
            </div>
            {invalidRows.length > 0 ? (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-100 bg-amber-50 px-3 py-1 text-xs font-semibold text-amber-700">
                <AlertTriangle className="w-3.5 h-3.5" />
                {invalidRows.length} rows need review
              </span>
            ) : (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-100 bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700">
                <CheckCircle2 className="w-3.5 h-3.5" />
                All rows clear
              </span>
            )}
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 divide-x divide-y md:divide-y-0 divide-slate-100">
            <Metric label="Rows" value={result.totalRows} />
            <Metric label="Valid" value={result.validRows} />
            <Metric label="Submitted" value={result.submittedRows} />
            <Metric label="Failed" value={result.failedRows} />
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50">
                <tr>
                  {['Row', 'Status', 'Investor', 'Scheme', 'Type', 'Amount', 'Units', 'Order', 'Errors'].map(header => (
                    <th key={header} className="text-left text-xs font-semibold text-slate-400 uppercase tracking-wider px-4 py-3 whitespace-nowrap">
                      {header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {(result.rows ?? []).map(row => (
                  <tr key={row.rowNumber} className="hover:bg-slate-50/80">
                    <td className="px-4 py-3 text-slate-500">{row.rowNumber}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold ${STATUS_STYLE[row.status]}`}>
                        {row.status === 'VALIDATION_FAILED' || row.status === 'SUBMISSION_FAILED'
                          ? <XCircle className="w-3 h-3" />
                          : <CheckCircle2 className="w-3 h-3" />}
                        {STATUS_LABEL[row.status]}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-slate-700 font-mono text-xs whitespace-nowrap">{row.investorId || '-'}</td>
                    <td className="px-4 py-3 text-slate-700 whitespace-nowrap">{row.schemeName || row.schemeCode || row.productSchemeId || '-'}</td>
                    <td className="px-4 py-3 text-slate-700 whitespace-nowrap">{row.transactionType || '-'}</td>
                    <td className="px-4 py-3 text-slate-700 whitespace-nowrap">{formatMoney(row.amount)}</td>
                    <td className="px-4 py-3 text-slate-700 whitespace-nowrap">{row.units ?? '-'}</td>
                    <td className="px-4 py-3 text-slate-700 font-mono text-xs whitespace-nowrap">{row.externalOrderId || row.orderId || '-'}</td>
                    <td className="px-4 py-3 text-slate-500 min-w-64">
                      {row.errors?.length ? row.errors.join('; ') : '-'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="p-4">
      <p className="text-xs text-slate-400">{label}</p>
      <p className="text-xl font-bold text-slate-800">{value}</p>
    </div>
  );
}
