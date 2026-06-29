import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  CheckCircle2, AlertCircle, Loader2, ShieldCheck, XCircle, ArrowRight,
} from 'lucide-react';
import { apiFetch } from '../config/api';

/**
 * Public investor onboarding-approval page (investor.md M2 · R7a/R7b). Reached from the
 * email link `/investor/link-approve?token=…` with NO session — the opaque token is the
 * possession factor. Loads the distributor-entered Step-1 details (read-only), then the
 * investor accepts consent and Approves (links the distributor by PAN) or Declines.
 */

interface ReviewPayload {
  fullName?: string;
  pan?: string;
  email?: string;
  mobileNumber?: string;
  dateOfBirth?: string | null;
  relationshipType?: string;
}
interface ReviewResponse {
  review?: ReviewPayload;
  maskedEmail?: string;
  expiresAt?: string;
}

const fmtDate = (v?: string | null) => {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
};

export default function InvestorLinkApprove() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') || '';

  const [data, setData] = useState<ReviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [consent, setConsent] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState<'approved' | 'rejected' | null>(null);
  const [approved, setApproved] = useState<{ email?: string; pan?: string } | null>(null);

  const load = useCallback(async () => {
    if (!token) { setError('This approval link is missing its token.'); setLoading(false); return; }
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch(`/investor/link/review?token=${encodeURIComponent(token)}`, { skipAuthRedirect: true });
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || 'This link is invalid or has expired.');
      }
      setData(body as ReviewResponse);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'This link is invalid or has expired.');
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { void load(); }, [load]);

  const post = async (path: string, payload: object) => {
    const res = await apiFetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      skipAuthRedirect: true,
    });
    const body = await res.json().catch(() => null);
    if (!res.ok) {
      throw new Error((body as { message?: string } | null)?.message || 'Something went wrong. Please try again.');
    }
    return body;
  };

  const approve = async () => {
    setSubmitting(true);
    setError('');
    try {
      const body = await post('/investor/link/approve', { token, consentAccepted: consent });
      setApproved(body as { email?: string; pan?: string });
      setDone('approved');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not approve this link.');
    } finally {
      setSubmitting(false);
    }
  };

  const reject = async () => {
    setSubmitting(true);
    setError('');
    try {
      await post('/investor/link/reject', { token });
      setDone('rejected');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not decline this link.');
    } finally {
      setSubmitting(false);
    }
  };

  const Shell = ({ children }: { children: ReactNode }) => (
    <div className="min-h-screen bg-[#0B1B3E] flex items-center justify-center p-6">
      <div className="w-full max-w-md rounded-2xl bg-white p-8 shadow-xl">
        <div className="mb-6 flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-[#0B1B3E] text-base font-bold text-white">P</div>
          <span className="text-lg font-bold tracking-tight text-slate-800">Platizio</span>
        </div>
        {children}
      </div>
    </div>
  );

  if (loading) {
    return <Shell><div className="flex flex-col items-center gap-3 py-8"><Loader2 className="h-7 w-7 animate-spin text-blue-600" /><p className="text-sm text-slate-500">Loading your approval request…</p></div></Shell>;
  }

  if (done === 'approved') {
    return (
      <Shell>
        <div className="text-center">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100"><CheckCircle2 className="h-8 w-8 text-emerald-600" /></div>
          <h1 className="text-xl font-semibold text-slate-800">Approved</h1>
          <p className="mt-2 text-sm text-slate-500">You've approved the link. Next, create your investor account to complete KYC and your profile.</p>
          <button onClick={() => navigate(`/investor/signup?invited=1${approved?.email ? `&email=${encodeURIComponent(approved.email)}` : ''}${approved?.pan ? `&pan=${encodeURIComponent(approved.pan)}` : ''}`)} className="mt-6 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white hover:bg-[#1A3066]">
            Continue to sign up <ArrowRight className="h-4 w-4" />
          </button>
        </div>
      </Shell>
    );
  }

  if (done === 'rejected') {
    return (
      <Shell>
        <div className="text-center">
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-slate-100"><XCircle className="h-8 w-8 text-slate-500" /></div>
          <h1 className="text-xl font-semibold text-slate-800">Declined</h1>
          <p className="mt-2 text-sm text-slate-500">You've declined this onboarding link. Your distributor has been notified.</p>
        </div>
      </Shell>
    );
  }

  if (error && !data) {
    return (
      <Shell>
        <div className="flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
          <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
          <div><p className="font-semibold text-red-700">Link unavailable</p><p className="mt-1 text-sm text-red-600">{error}</p></div>
        </div>
      </Shell>
    );
  }

  const r = data?.review ?? {};
  const rows: Array<{ label: string; value: string }> = [
    { label: 'Full name', value: r.fullName || '—' },
    { label: 'PAN', value: r.pan || '—' },
    { label: 'Email', value: r.email || data?.maskedEmail || '—' },
    { label: 'Mobile', value: r.mobileNumber || '—' },
    { label: 'Date of birth', value: fmtDate(r.dateOfBirth) },
    { label: 'Relationship', value: r.relationshipType || '—' },
  ];

  return (
    <Shell>
      <h1 className="text-xl font-semibold text-slate-800">Approve your onboarding</h1>
      <p className="mt-1 text-sm text-slate-500">Your distributor entered these details. Review them and approve to continue.</p>

      <div className="mt-5 overflow-hidden rounded-xl border border-slate-200">
        {rows.map((row, i) => (
          <div key={row.label} className={`flex items-center justify-between px-4 py-3 ${i > 0 ? 'border-t border-slate-100' : ''}`}>
            <span className="text-xs font-bold uppercase tracking-wider text-slate-400">{row.label}</span>
            <span className="text-sm font-medium text-slate-800">{row.value}</span>
          </div>
        ))}
      </div>

      {error && (
        <div className="mt-4 flex items-start gap-2 rounded-xl border border-red-100 bg-red-50 px-3 py-2.5">
          <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
          <p className="text-sm text-red-700">{error}</p>
        </div>
      )}

      <label className="mt-5 flex cursor-pointer items-start gap-2.5">
        <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} className="mt-0.5 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-200" />
        <span className="text-xs leading-relaxed text-slate-600">I confirm these details are mine and I authorise this distributor to onboard me on Platizio.</span>
      </label>

      <div className="mt-6 flex gap-3">
        <button onClick={() => void reject()} disabled={submitting} className="flex-1 rounded-xl border border-slate-200 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-60">
          Decline
        </button>
        <button onClick={() => void approve()} disabled={submitting || !consent} className="flex flex-[2] items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60">
          {submitting ? (<><Loader2 className="h-4 w-4 animate-spin" /> Submitting…</>) : (<><ShieldCheck className="h-4 w-4" /> Approve</>)}
        </button>
      </div>
    </Shell>
  );
}
