import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { motion } from 'motion/react';
import {
  ArrowLeft, RefreshCw, ShieldCheck, ExternalLink, Upload, CheckCircle2,
  AlertCircle, Clock, FileSignature, Fingerprint, Loader2,
} from 'lucide-react';
import { apiClient } from '../config/api';

// kyc_form states that are still "in flight" — we poll while in these.
const ACTIVE_STATES = ['under_review', 'created', 'awaiting_esign', 'awaiting_submission'];

type KycForm = {
  id?: string;
  externalKycFormId?: string;
  status?: string;
  reason?: string | null;
  proofFetchUrl?: string | null;
  proofStatus?: string | null;
  esignUrl?: string | null;
  esignStatus?: string | null;
  signatureProvided?: boolean;
  fieldsNeededJson?: string | null;
  expiresAt?: string | null;
};

type KycFormResponse = { form: KycForm | null; external: any };

const extractError = (err: any, fallback: string) =>
  err?.response?.data?.message || err?.response?.data?.error || err?.message || fallback;

const prettyStatus = (status?: string) =>
  (status || 'not_started').replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

const STATUS_STYLES: Record<string, string> = {
  under_review: 'bg-amber-50 text-amber-700 border-amber-200',
  created: 'bg-blue-50 text-blue-700 border-blue-200',
  awaiting_esign: 'bg-indigo-50 text-indigo-700 border-indigo-200',
  awaiting_submission: 'bg-violet-50 text-violet-700 border-violet-200',
  submitted: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  failed: 'bg-red-50 text-red-700 border-red-200',
  expired: 'bg-slate-100 text-slate-600 border-slate-200',
};

export default function InvestorKycModify() {
  const navigate = useNavigate();
  const { investorId, action } = useParams<{ investorId: string; action?: string }>();

  const [data, setData] = useState<KycFormResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  // PATCH "additional details" form
  const [details, setDetails] = useState<Record<string, string>>({});
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const callbackHandled = useRef(false);

  const form = data?.form || null;
  const status = form?.status;
  const isActive = !!status && ACTIVE_STATES.includes(status);

  const fieldsNeeded: string[] = useMemo(() => {
    if (!form?.fieldsNeededJson) return [];
    try {
      const parsed = JSON.parse(form.fieldsNeededJson);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [form?.fieldsNeededJson]);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await apiClient.get<KycFormResponse>(`/investors/${investorId}/kyc-form`);
      setData(res.data);
    } catch (err) {
      setError(extractError(err, 'Unable to load the KYC form.'));
    } finally {
      setLoading(false);
    }
  }, [investorId]);

  const refresh = useCallback(async (formId?: string) => {
    const id = formId || form?.externalKycFormId;
    if (!id) return;
    setBusy('refresh');
    setError(null);
    try {
      const res = await apiClient.post<KycFormResponse>(`/investors/${investorId}/kyc-form/${id}/refresh`);
      setData(res.data);
    } catch (err) {
      setError(extractError(err, 'Unable to refresh the KYC form status.'));
    } finally {
      setBusy(null);
    }
  }, [form?.externalKycFormId, investorId]);

  // Initial load.
  useEffect(() => { load(); }, [load]);

  // Handle Digilocker / eSign redirect-back: refresh, then clean the URL.
  useEffect(() => {
    if (!action || callbackHandled.current || loading) return;
    callbackHandled.current = true;
    const label = action === 'esign-callback' ? 'eSign' : 'Digilocker';
    setInfo(`Returned from ${label}. Refreshing status…`);
    (async () => {
      await refresh();
      navigate(`/distributor/investors/${investorId}/kyc-modify`, { replace: true });
    })();
  }, [action, loading, refresh, navigate, investorId]);

  // Poll while the form is in an active state.
  useEffect(() => {
    if (!isActive || !form?.externalKycFormId) return;
    const interval = setInterval(() => { refresh(); }, 8000);
    return () => clearInterval(interval);
  }, [isActive, form?.externalKycFormId, refresh]);

  const startForm = async () => {
    setBusy('create');
    setError(null);
    setInfo(null);
    try {
      const res = await apiClient.post<KycFormResponse>(`/investors/${investorId}/kyc-form`);
      setData(res.data);
    } catch (err) {
      setError(extractError(err, 'Unable to start the KYC modify form.'));
    } finally {
      setBusy(null);
    }
  };

  const submitDetails = async () => {
    if (!form?.externalKycFormId) return;
    const payload: Record<string, unknown> = {};
    Object.entries(details).forEach(([k, v]) => {
      if (v != null && String(v).trim() !== '') payload[k] = v;
    });
    if (Object.keys(payload).length === 0) {
      setError('Enter at least one field to update.');
      return;
    }
    setBusy('details');
    setError(null);
    try {
      const res = await apiClient.patch<KycFormResponse>(
        `/investors/${investorId}/kyc-form/${form.externalKycFormId}`, payload);
      setData(res.data);
      setInfo('Details submitted.');
    } catch (err) {
      setError(extractError(err, 'Unable to submit the details.'));
    } finally {
      setBusy(null);
    }
  };

  const uploadSignature = async (file: File) => {
    if (!form?.externalKycFormId) return;
    setBusy('signature');
    setError(null);
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await apiClient.post<KycFormResponse>(
        `/investors/${investorId}/kyc-form/${form.externalKycFormId}/signature`, fd);
      setData(res.data);
      setInfo('Signature uploaded.');
    } catch (err) {
      setError(extractError(err, 'Unable to upload the signature.'));
    } finally {
      setBusy(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const retryProof = async () => {
    if (!form?.externalKycFormId) return;
    setBusy('retry-proof');
    setError(null);
    try {
      const res = await apiClient.post<KycFormResponse>(
        `/investors/${investorId}/kyc-form/${form.externalKycFormId}/retry-proof-fetch`);
      setData(res.data);
      setInfo('A fresh Digilocker link has been generated.');
    } catch (err) {
      setError(extractError(err, 'Unable to retry the proof fetch.'));
    } finally {
      setBusy(null);
    }
  };

  const setField = (key: string, value: string) =>
    setDetails((prev) => ({ ...prev, [key]: value }));

  const proofFetched = form?.proofStatus === 'fetched';
  const proofFailed = form?.proofStatus === 'failed';
  const signatureProvided = !!form?.signatureProvided;
  const needsSignature = fieldsNeeded.includes('signature');
  const needsProof = fieldsNeeded.includes('identity_proof') || fieldsNeeded.includes('address');

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-4 md:p-8 max-w-3xl mx-auto"
    >
      <button
        onClick={() => navigate('/distributor/investors')}
        className="inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-700 mb-4"
      >
        <ArrowLeft className="w-4 h-4" /> Back to investors
      </button>

      <div className="flex items-center gap-3 mb-6">
        <div className="w-10 h-10 rounded-xl bg-indigo-50 flex items-center justify-center">
          <ShieldCheck className="w-5 h-5 text-indigo-600" />
        </div>
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Modify KYC</h1>
          <p className="text-slate-500 text-sm">Cybrilla POA KYC Forms — update an already-verified KYC record.</p>
        </div>
      </div>

      {error && (
        <div className="mb-4 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" /> <span>{error}</span>
        </div>
      )}
      {info && !error && (
        <div className="mb-4 flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-700">
          <Clock className="w-4 h-4 mt-0.5 shrink-0" /> <span>{info}</span>
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-16 text-slate-400">
          <Loader2 className="w-6 h-6 animate-spin" />
        </div>
      ) : !form ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-8 text-center">
          <Fingerprint className="w-10 h-10 text-indigo-500 mx-auto mb-3" />
          <h2 className="text-lg font-semibold text-slate-800">Start a KYC modification</h2>
          <p className="text-slate-500 text-sm mt-2 max-w-md mx-auto">
            This creates a Cybrilla <code className="text-xs">kyc_form</code> with
            <span className="font-medium"> type = modify</span>. Cybrilla checks eligibility
            (KYC must be validated / verified / registered / onhold). The investor then completes
            an Aadhaar (Digilocker) fetch, signature upload and eSign.
          </p>
          <button
            onClick={startForm}
            disabled={busy === 'create'}
            className="mt-5 inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            {busy === 'create' ? <Loader2 className="w-4 h-4 animate-spin" /> : <ShieldCheck className="w-4 h-4" />}
            Start KYC modify
          </button>
        </div>
      ) : (
        <div className="space-y-4">
          {/* Status header */}
          <div className="rounded-2xl border border-slate-200 bg-white p-5">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-xs uppercase tracking-wide text-slate-400">Form status</p>
                <span className={`mt-1 inline-flex items-center rounded-full border px-3 py-1 text-sm font-semibold ${STATUS_STYLES[status || ''] || 'bg-slate-100 text-slate-600 border-slate-200'}`}>
                  {prettyStatus(status)}
                </span>
                {form.reason && <p className="text-sm text-red-600 mt-2">Reason: {form.reason}</p>}
                {form.externalKycFormId && (
                  <p className="text-xs text-slate-400 mt-2 font-mono">{form.externalKycFormId}</p>
                )}
              </div>
              <button
                onClick={() => refresh()}
                disabled={!!busy}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-50"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${busy === 'refresh' ? 'animate-spin' : ''}`} /> Refresh
              </button>
            </div>
          </div>

          {status === 'under_review' && (
            <div className="rounded-2xl border border-amber-200 bg-amber-50 p-5 text-sm text-amber-800 flex items-center gap-2">
              <Loader2 className="w-4 h-4 animate-spin" />
              Cybrilla is checking the investor's eligibility for KYC modification…
            </div>
          )}

          {status === 'failed' && (
            <div className="rounded-2xl border border-slate-200 bg-white p-5">
              <p className="text-sm text-slate-600 mb-3">This form has failed{form.reason ? `: ${form.reason}` : '.'} You can start a new one.</p>
              <button onClick={startForm} disabled={busy === 'create'}
                className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50">
                {busy === 'create' ? <Loader2 className="w-4 h-4 animate-spin" /> : <ShieldCheck className="w-4 h-4" />} Start new form
              </button>
            </div>
          )}

          {status === 'expired' && (
            <div className="rounded-2xl border border-slate-200 bg-white p-5">
              <p className="text-sm text-slate-600 mb-3">This form expired (no action within 7 days). Start a new one to try again.</p>
              <button onClick={startForm} disabled={busy === 'create'}
                className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50">
                {busy === 'create' ? <Loader2 className="w-4 h-4 animate-spin" /> : <ShieldCheck className="w-4 h-4" />} Start new form
              </button>
            </div>
          )}

          {status === 'awaiting_submission' && (
            <div className="rounded-2xl border border-violet-200 bg-violet-50 p-5 text-sm text-violet-800 flex items-center gap-2">
              <Loader2 className="w-4 h-4 animate-spin" /> eSign complete. Cybrilla is submitting the form to the KRA…
            </div>
          )}

          {status === 'submitted' && (
            <div className="rounded-2xl border border-emerald-200 bg-emerald-50 p-6 text-center">
              <CheckCircle2 className="w-10 h-10 text-emerald-600 mx-auto mb-2" />
              <h2 className="text-lg font-semibold text-emerald-800">KYC modification submitted</h2>
              <p className="text-sm text-emerald-700 mt-1">The modified KYC form has been submitted to the KRA.</p>
            </div>
          )}

          {/* Step-by-step actions (only while the form can still be completed) */}
          {status === 'created' && (
            <>
              {/* 1. Digilocker / Aadhaar proof */}
              <div className="rounded-2xl border border-slate-200 bg-white p-5">
                <div className="flex items-center gap-2 mb-2">
                  <Fingerprint className="w-4 h-4 text-indigo-600" />
                  <h3 className="font-semibold text-slate-800">1. Aadhaar via Digilocker</h3>
                  {proofFetched && <CheckCircle2 className="w-4 h-4 text-emerald-600" />}
                </div>
                {proofFetched ? (
                  <p className="text-sm text-emerald-700">Aadhaar proof fetched — used as identity & address proof.</p>
                ) : (
                  <>
                    <p className="text-sm text-slate-500 mb-3">
                      The investor completes the Digilocker journey to fetch Aadhaar. They are redirected
                      back here automatically afterwards.
                    </p>
                    <div className="flex flex-wrap gap-2">
                      {form.proofFetchUrl && !proofFailed && (
                        <a
                          href={form.proofFetchUrl}
                          className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700"
                        >
                          <ExternalLink className="w-4 h-4" /> Open Digilocker
                        </a>
                      )}
                      {proofFailed && (
                        <button onClick={retryProof} disabled={busy === 'retry-proof'}
                          className="inline-flex items-center gap-2 rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm font-semibold text-amber-700 hover:bg-amber-100 disabled:opacity-50">
                          <RefreshCw className={`w-4 h-4 ${busy === 'retry-proof' ? 'animate-spin' : ''}`} /> Retry Digilocker
                        </button>
                      )}
                    </div>
                    {form.proofStatus && (
                      <p className="text-xs text-slate-400 mt-2">Proof status: {form.proofStatus}</p>
                    )}
                  </>
                )}
              </div>

              {/* 2. Signature */}
              <div className="rounded-2xl border border-slate-200 bg-white p-5">
                <div className="flex items-center gap-2 mb-2">
                  <FileSignature className="w-4 h-4 text-indigo-600" />
                  <h3 className="font-semibold text-slate-800">2. Wet-signature photocopy</h3>
                  {signatureProvided && <CheckCircle2 className="w-4 h-4 text-emerald-600" />}
                </div>
                {signatureProvided ? (
                  <p className="text-sm text-emerald-700">Signature uploaded.</p>
                ) : (
                  <>
                    <p className="text-sm text-slate-500 mb-3">Upload a scan/photo of the investor's signature (png, jpg, jpeg or pdf, up to 5 MB).</p>
                    <input
                      ref={fileInputRef}
                      type="file"
                      accept="image/png,image/jpeg,image/jpg,application/pdf"
                      onChange={(e) => { const f = e.target.files?.[0]; if (f) uploadSignature(f); }}
                      disabled={busy === 'signature'}
                      className="block text-sm text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-indigo-600 file:px-4 file:py-2 file:text-sm file:font-semibold file:text-white hover:file:bg-indigo-700"
                    />
                    {busy === 'signature' && <p className="text-xs text-slate-400 mt-2 flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" /> Uploading…</p>}
                  </>
                )}
              </div>

              {/* 3. Additional details (PATCH) */}
              <div className="rounded-2xl border border-slate-200 bg-white p-5">
                <div className="flex items-center gap-2 mb-2">
                  <h3 className="font-semibold text-slate-800">3. Additional details</h3>
                </div>
                {fieldsNeeded.length > 0 && (
                  <p className="text-xs text-slate-500 mb-3">
                    Still required by Cybrilla: <span className="font-medium">{fieldsNeeded.join(', ')}</span>
                  </p>
                )}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <TextField label="Email address" value={details.emailAddress} onChange={(v) => setField('emailAddress', v)} />
                  <TextField label="Phone number" value={details.phoneNumber} onChange={(v) => setField('phoneNumber', v)} />
                  <SelectField label="Gender" value={details.gender} onChange={(v) => setField('gender', v)} options={['male', 'female', 'transgender']} />
                  <SelectField label="Marital status" value={details.maritalStatus} onChange={(v) => setField('maritalStatus', v)} options={['married', 'unmarried', 'others']} />
                  <TextField label="Father's name" value={details.fatherName} onChange={(v) => setField('fatherName', v)} />
                  <TextField label="Spouse's name" value={details.spouseName} onChange={(v) => setField('spouseName', v)} />
                  <SelectField label="Occupation" value={details.occupationType} onChange={(v) => setField('occupationType', v)}
                    options={['business', 'professional', 'retired', 'housewife', 'student', 'public_sector_service', 'private_sector_service', 'government_service', 'agriculture', 'doctor', 'forex_dealer', 'service', 'others']} />
                  <SelectField label="Income slab" value={details.incomeSlab} onChange={(v) => setField('incomeSlab', v)}
                    options={['upto_1lakh', 'above_1lakh_upto_5lakh', 'above_5lakh_upto_10lakh', 'above_10lakh_upto_25lakh', 'above_25lakh_upto_1cr', 'above_1cr']} />
                  <SelectField label="PEP details" value={details.pepDetails} onChange={(v) => setField('pepDetails', v)} options={['no_exposure', 'pep', 'related_pep']} />
                  <TextField label="Aadhaar (last 4)" value={details.aadhaarNumber} onChange={(v) => setField('aadhaarNumber', v)} />
                  <TextField label="Place of birth" value={details.placeOfBirth} onChange={(v) => setField('placeOfBirth', v)} />
                  <TextField label="Country of birth (ISO)" value={details.countryOfBirth} onChange={(v) => setField('countryOfBirth', v)} placeholder="in" />
                </div>
                <button onClick={submitDetails} disabled={busy === 'details'}
                  className="mt-4 inline-flex items-center gap-2 rounded-lg bg-slate-800 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-900 disabled:opacity-50">
                  {busy === 'details' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />} Submit details
                </button>
              </div>

              {(needsProof || needsSignature || fieldsNeeded.length > 0) && (
                <p className="text-xs text-slate-400 text-center">
                  Once every required item is provided, the form moves to <span className="font-medium">awaiting eSign</span>.
                </p>
              )}
            </>
          )}

          {/* eSign step */}
          {status === 'awaiting_esign' && (
            <div className="rounded-2xl border border-indigo-200 bg-white p-5">
              <div className="flex items-center gap-2 mb-2">
                <FileSignature className="w-4 h-4 text-indigo-600" />
                <h3 className="font-semibold text-slate-800">Complete eSign</h3>
              </div>
              <p className="text-sm text-slate-500 mb-3">
                All details are in. The investor must eSign to submit the form to the KRA. They are
                redirected back here automatically afterwards.
              </p>
              {form.esignUrl ? (
                <a href={form.esignUrl}
                  className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700">
                  <ExternalLink className="w-4 h-4" /> Open eSign
                </a>
              ) : (
                <button onClick={() => refresh()} disabled={!!busy}
                  className="inline-flex items-center gap-2 rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50">
                  <RefreshCw className={`w-4 h-4 ${busy === 'refresh' ? 'animate-spin' : ''}`} /> Get eSign link
                </button>
              )}
            </div>
          )}
        </div>
      )}
    </motion.div>
  );
}

function TextField({ label, value, onChange, placeholder }: {
  label: string; value?: string; onChange: (v: string) => void; placeholder?: string;
}) {
  return (
    <label className="block">
      <span className="text-xs font-medium text-slate-500">{label}</span>
      <input
        type="text"
        value={value || ''}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:border-indigo-400 focus:outline-none focus:ring-1 focus:ring-indigo-400"
      />
    </label>
  );
}

function SelectField({ label, value, onChange, options }: {
  label: string; value?: string; onChange: (v: string) => void; options: string[];
}) {
  return (
    <label className="block">
      <span className="text-xs font-medium text-slate-500">{label}</span>
      <select
        value={value || ''}
        onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:border-indigo-400 focus:outline-none focus:ring-1 focus:ring-indigo-400"
      >
        <option value="">—</option>
        {options.map((o) => (
          <option key={o} value={o}>{o.replace(/_/g, ' ')}</option>
        ))}
      </select>
    </label>
  );
}
