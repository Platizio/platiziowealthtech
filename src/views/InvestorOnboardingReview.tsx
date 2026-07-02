import { useCallback, useEffect, useMemo, useState } from 'react';
import { motion } from 'motion/react';
import {
  FileText, Loader2, AlertCircle, CheckCircle2, ShieldCheck, Clock,
} from 'lucide-react';
import EmptyState from '../components/EmptyState';
import ContactVerification from '../components/ContactVerification';
import InvestorNomineeManager from '../components/InvestorNomineeManager';
import { apiFetch } from '../config/api';
import { useAppSelector } from '../store/hooks';
import { selectInvestorUser } from '../store/slices/investorAuthSlice';

interface ReviewResponse {
  hasDraft: boolean;
  revisionNo?: number;
  status?: string;
  payloadJson?: unknown;
  contentSha256?: string;
  submittedAt?: string;
}

interface ContactStatus {
  emailVerified?: boolean;
  emailVerificationMethod?: string | null;
  emailBelongsTo?: string | null;
  mobileVerified?: boolean;
  mobileVerificationMethod?: string | null;
  mobileBelongsTo?: string | null;
  /** SMS OTP availability flags (informational — the mobile channel now runs as a demo). */
  otpEnabled?: boolean;
  smsEnabled?: boolean;
  email?: string;
  mobileNumber?: string;
}

const normalizeStatus = (value?: string) => String(value || '').trim().toUpperCase();

const isApproved = (status?: string) => {
  const s = normalizeStatus(status);
  return s === 'ATTESTED' || s === 'APPROVED';
};

const isAwaiting = (status?: string) => {
  const s = normalizeStatus(status);
  return s === 'SUBMITTED' || s === 'AWAITING_APPROVAL' || s === 'PENDING' || s === 'AWAITING';
};

/** Pretty-prints the frozen submission payload as a read-only key/value tree. */
function ReadOnlyPayload({ value }: { value: unknown }) {
  const text = useMemo(() => {
    try {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value;
      return JSON.stringify(parsed, null, 2);
    } catch {
      return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
    }
  }, [value]);

  return (
    <pre className="max-h-96 overflow-auto rounded-xl border border-slate-200 bg-slate-50 p-4 text-xs leading-relaxed text-slate-700 whitespace-pre-wrap break-words font-mono">
      {text}
    </pre>
  );
}

/**
 * F3: investor reviews and attests the exact onboarding submission frozen by
 * their distributor. GET /investor/onboarding/review → if `hasDraft:false`
 * show an empty state. Otherwise render the payload read-only, the investor's
 * own email + mobile contact verification (mobile OTP runs as a simulated DEMO
 * until MSG91 is integrated), and an UNTICKED attestation checkbox that POSTs
 * the revision hash to /investor/onboarding/attest. Shows awaiting → approved
 * states, then a final "Nominee details" step (shared InvestorNomineeManager)
 * where distributor-captured nominees arrive pre-filled.
 */
export default function InvestorOnboardingReview() {
  const investor = useAppSelector(selectInvestorUser);

  const [review, setReview] = useState<ReviewResponse | null>(null);
  const [contact, setContact] = useState<ContactStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [attested, setAttested] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [attestError, setAttestError] = useState('');

  const loadReview = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [reviewRes, contactRes] = await Promise.all([
        apiFetch('/investor/onboarding/review'),
        apiFetch('/investor/contact/status'),
      ]);
      const reviewData = (await reviewRes.json().catch(() => null)) as ReviewResponse | null;
      if (!reviewRes.ok) {
        throw new Error((reviewData as { message?: string } | null)?.message || 'Unable to load your submission.');
      }
      setReview(reviewData);
      if (contactRes.ok) {
        setContact((await contactRes.json().catch(() => null)) as ContactStatus | null);
      }
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Unable to load your submission.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadReview();
  }, [loadReview]);

  const handleAttest = async () => {
    if (!attested || !review?.contentSha256) return;
    setSubmitting(true);
    setAttestError('');
    try {
      const res = await apiFetch('/investor/onboarding/attest', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ revisionHash: review.contentSha256 }),
      });
      const data = (await res.json().catch(() => null)) as ReviewResponse | null;
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Could not record your approval. Please try again.');
      }
      // Backend returns the updated review; fall back to a local approved state.
      setReview(data && data.hasDraft !== undefined ? data : { ...(review as ReviewResponse), status: 'ATTESTED' });
    } catch (e) {
      setAttestError(e instanceof Error ? e.message : 'Could not record your approval. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  const emailValue = contact?.email || investor?.email || '';
  const mobileValue = contact?.mobileNumber || '';

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading your onboarding submission…</p>
        </div>
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
          <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
          <div>
            <p className="text-sm text-red-700">{loadError}</p>
            <button onClick={() => void loadReview()} className="mt-2 text-xs font-semibold text-red-600 hover:underline">
              Try again
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!review?.hasDraft) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <EmptyState
          icon={FileText}
          title="No submission to review yet"
          subtitle="Your distributor has not submitted an onboarding application for your approval. You'll be able to review and approve it here once they do."
        />
      </div>
    );
  }

  const approved = isApproved(review.status);
  const awaiting = isAwaiting(review.status);

  return (
    <div className="mx-auto max-w-2xl p-8">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-slate-800">Review your onboarding submission</h1>
        <p className="mt-1 text-sm text-slate-500">
          Confirm the exact details your distributor submitted. Approving freezes this submission for finalization.
        </p>
      </div>

      {/* Status pill */}
      <div className="mb-6">
        {approved ? (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 border border-emerald-200 px-3 py-1 text-xs font-semibold text-emerald-700">
            <CheckCircle2 className="h-3.5 w-3.5" /> Approved
          </span>
        ) : awaiting ? (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-amber-50 border border-amber-200 px-3 py-1 text-xs font-semibold text-amber-700">
            <Clock className="h-3.5 w-3.5" /> Awaiting your approval
          </span>
        ) : (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 border border-slate-200 px-3 py-1 text-xs font-semibold text-slate-600">
            <Clock className="h-3.5 w-3.5" /> {normalizeStatus(review.status) || 'Pending'}
          </span>
        )}
        {typeof review.revisionNo === 'number' && (
          <span className="ml-2 text-xs text-slate-400">Revision #{review.revisionNo}</span>
        )}
      </div>

      {/* Read-only payload */}
      <div className="mb-6 rounded-2xl border border-slate-200 bg-white p-6">
        <h2 className="mb-3 text-sm font-semibold text-slate-800">Submitted details</h2>
        <ReadOnlyPayload value={review.payloadJson} />
        {review.contentSha256 && (
          <p className="mt-3 break-all font-mono text-[11px] text-slate-400">
            Revision hash: {review.contentSha256}
          </p>
        )}
      </div>

      {/* Contact verification (investor-self) */}
      <div className="mb-6 rounded-2xl border border-slate-200 bg-white p-6">
        <h2 className="mb-1 text-sm font-semibold text-slate-800">Verify your contact details</h2>
        <p className="mb-4 text-xs text-slate-500">
          Confirm the email and mobile on this submission belong to you.
        </p>

        <div className="mb-4">
          <label className="block text-xs font-bold uppercase tracking-wider text-slate-500">Email</label>
          <p className="mt-1 text-sm text-slate-700">{emailValue || '—'}</p>
          <ContactVerification
            mode="investor-self"
            investorId={null}
            channel="email"
            value={emailValue}
            verified={contact?.emailVerified}
            method={(contact?.emailVerificationMethod as 'OTP' | 'SELF_DECLARED' | null) ?? null}
            belongsTo={contact?.emailBelongsTo}
            onVerified={(s) => setContact((prev) => ({ ...prev, ...s }))}
          />
        </div>

        <div>
          <label className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-slate-500">
            Mobile
            <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-700">Demo</span>
          </label>
          <p className="mt-1 text-sm text-slate-700">{mobileValue || '—'}</p>
          <p className="mt-1 text-[11px] text-slate-400">
            SMS OTP is simulated for now — real delivery arrives with MSG91.
          </p>
          <ContactVerification
            mode="investor-self"
            investorId={null}
            channel="mobile"
            value={mobileValue}
            verified={contact?.mobileVerified}
            method={(contact?.mobileVerificationMethod as 'OTP' | 'SELF_DECLARED' | null) ?? null}
            belongsTo={contact?.mobileBelongsTo}
            onVerified={(s) => setContact((prev) => ({ ...prev, ...s }))}
          />
        </div>
      </div>

      {/* Attestation / approved state */}
      {approved ? (
        <motion.div initial={{ opacity: 0, scale: 0.98 }} animate={{ opacity: 1, scale: 1 }}
          className="rounded-2xl border border-emerald-200 bg-emerald-50 p-6 text-center">
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-emerald-100">
            <ShieldCheck className="h-6 w-6 text-emerald-600" />
          </div>
          <p className="text-sm font-semibold text-slate-700">Submission approved</p>
          <p className="mt-1 text-xs text-slate-500">
            You've approved this exact submission. Your distributor can now finalize it. Any change will require a fresh approval.
          </p>
        </motion.div>
      ) : (
        <div className="rounded-2xl border border-slate-200 bg-white p-6">
          {attestError && (
            <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
              <p className="text-sm text-red-700">{attestError}</p>
            </div>
          )}
          <label className="flex items-start gap-3 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={attested}
              onChange={(e) => { setAttested(e.target.checked); setAttestError(''); }}
              className="mt-0.5 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
            />
            <span className="text-sm text-slate-700 leading-relaxed">
              I approve this exact submission.
            </span>
          </label>
          <button
            type="button"
            onClick={() => void handleAttest()}
            disabled={!attested || submitting || !review.contentSha256}
            className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {submitting ? (<><Loader2 className="h-4 w-4 animate-spin" /> Submitting approval…</>) : (<><ShieldCheck className="h-4 w-4" /> Approve submission</>)}
          </button>
          <p className="mt-3 text-center text-xs text-slate-400">
            Approval applies only to this exact submission. If your distributor edits it afterwards, you'll be asked to approve again.
          </p>
        </div>
      )}

      {/* Final step — nominee details (shared with /investor/nominations). Nominees
          the distributor captured during onboarding arrive pre-filled from the same GET. */}
      <div className="mt-6 rounded-2xl border border-slate-200 bg-white p-6">
        <h2 className="mb-1 text-sm font-semibold text-slate-800">Nominee details</h2>
        <p className="mb-4 text-xs text-slate-500">
          Review the nominees on your account, complete anything that's missing, upload their
          ID documents — or record that you'd rather not nominate.
        </p>
        <InvestorNomineeManager />
      </div>
    </div>
  );
}
