import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, RefreshCw, BadgeCheck, ShieldCheck, FileCheck2, CheckCircle2, ArrowRight } from 'lucide-react';
import { apiFetch } from '../config/api';
import type { InvestorUser } from '../types/investorAuth';

/**
 * Investor account profile. Renders live data from GET /investor/me (account) and,
 * when the account is linked to an investor record, the KYC status from
 * GET /investor/kyc/status. The session user passed in is used only as an instant
 * fallback while the live fetch is in flight, so the page never flashes empty.
 */

interface MePayload {
  email?: string;
  fullName?: string;
  status?: string;
  emailVerified?: boolean;
  mobileVerified?: boolean;
  investorLinked?: boolean;
  kycStatus?: string | null;
  distributorName?: string | null;
}

const prettyStatus = (s?: string | null) =>
  s ? String(s).replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase()) : '—';

export default function InvestorProfile({ investor }: { investor: InvestorUser }) {
  const navigate = useNavigate();
  const [account, setAccount] = useState<MePayload>(investor);
  const [kycStatus, setKycStatus] = useState<string | null>(null);
  const [approval, setApproval] = useState<{ hasDraft?: boolean; status?: string; revisionNo?: number } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/me');
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || `Unable to load your profile (${res.status}).`);
      }
      const me = (body as MePayload) || {};
      setAccount(me);
      // KYC status comes straight from /me (local DB) — resilient to the live Cybrilla
      // KYC endpoint being unavailable.
      setKycStatus(me.kycStatus ?? null);

      // Onboarding-approval is only meaningful once the account is linked.
      if (me.investorLinked) {
        try {
          const revRes = await apiFetch('/investor/onboarding/review');
          const revBody = await revRes.json().catch(() => null);
          if (revRes.ok) {
            setApproval(revBody as { hasDraft?: boolean; status?: string; revisionNo?: number });
          }
        } catch {
          /* onboarding review is best-effort; ignore failures here */
        }
      } else {
        setKycStatus(null);
        setApproval(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load your profile.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const rows: Array<{ label: string; value: string }> = [
    { label: 'Full name', value: account.fullName || '—' },
    { label: 'Email', value: account.email || '—' },
    { label: 'Account status', value: prettyStatus(account.status) },
    { label: 'Distributor', value: account.distributorName || (account.investorLinked ? '—' : 'Not allotted yet') },
    { label: 'Email verified', value: account.emailVerified ? 'Yes' : 'No' },
    { label: 'Mobile verified', value: account.mobileVerified ? 'Yes' : 'No' },
    { label: 'Linked to investor record', value: account.investorLinked ? 'Yes' : 'Not yet' },
  ];

  return (
    <div className="mx-auto max-w-2xl p-8">
      <div className="mb-6 flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Your profile</h1>
          <p className="mt-1 text-sm text-slate-500">Your investor account details.</p>
        </div>
        <button
          onClick={() => void load()}
          className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
        >
          {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />} Refresh
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded-xl border border-red-100 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
        {rows.map((row, i) => (
          <div
            key={row.label}
            className={`flex items-center justify-between px-6 py-4 ${i > 0 ? 'border-t border-slate-100' : ''}`}
          >
            <span className="text-xs font-bold uppercase tracking-wider text-slate-500">{row.label}</span>
            <span className="text-sm font-medium text-slate-800">{row.value}</span>
          </div>
        ))}
      </div>

      {/* KYC summary — only when linked to an investor record */}
      {account.investorLinked && (
        <div className="mt-6 flex items-center justify-between rounded-2xl border border-slate-200 bg-white px-6 py-5">
          <div className="flex items-center gap-3">
            <div className={`flex h-10 w-10 items-center justify-center rounded-xl ${String(kycStatus).toUpperCase() === 'COMPLETED' ? 'bg-emerald-50 text-emerald-600' : 'bg-amber-50 text-amber-600'}`}>
              {String(kycStatus).toUpperCase() === 'COMPLETED' ? <BadgeCheck className="h-5 w-5" /> : <ShieldCheck className="h-5 w-5" />}
            </div>
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">KYC status</p>
              <p className="text-sm font-semibold text-slate-800">{prettyStatus(kycStatus)}</p>
            </div>
          </div>
          <button
            onClick={() => navigate('/investor/kyc')}
            className="rounded-lg bg-[#0B1B3E] px-4 py-2 text-sm font-semibold text-white hover:bg-[#1A3066]"
          >
            {String(kycStatus).toUpperCase() === 'COMPLETED' ? 'View KYC' : 'Complete KYC'}
          </button>
        </div>
      )}

      {/* Onboarding approval — give the investor the option to review & approve their submission */}
      {account.investorLinked && approval?.hasDraft && (
        String(approval.status).toUpperCase() === 'ATTESTED' ? (
          <div className="mt-4 flex items-center gap-3 rounded-2xl border border-emerald-200 bg-emerald-50 px-6 py-5">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-100 text-emerald-600">
              <CheckCircle2 className="h-5 w-5" />
            </div>
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wider text-emerald-700">Onboarding approval</p>
              <p className="text-sm font-semibold text-slate-800">You've approved your onboarding submission{approval.revisionNo ? ` (rev ${approval.revisionNo})` : ''}.</p>
            </div>
          </div>
        ) : (
          <div className="mt-4 flex items-center justify-between rounded-2xl border border-amber-200 bg-amber-50 px-6 py-5">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-100 text-amber-700">
                <FileCheck2 className="h-5 w-5" />
              </div>
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-amber-700">Awaiting your approval</p>
                <p className="text-sm font-semibold text-slate-800">Your onboarding submission needs your approval before it can be finalized.</p>
              </div>
            </div>
            <button
              onClick={() => navigate('/investor/onboarding')}
              className="flex flex-shrink-0 items-center gap-1.5 rounded-lg bg-[#0B1B3E] px-4 py-2 text-sm font-semibold text-white hover:bg-[#1A3066]"
            >
              Review &amp; approve <ArrowRight className="h-4 w-4" />
            </button>
          </div>
        )
      )}
    </div>
  );
}
