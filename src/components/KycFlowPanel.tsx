import React from 'react';
import {
  CheckCircle2, Circle, Loader2, ArrowRight, Fingerprint, FileSignature,
} from 'lucide-react';
import {
  kycFlowNextActionLabel,
  type KycFlowNextAction,
  type KycFlowStatusView,
} from '../utils/kycFlow';
import KycProviderLink from './KycProviderLink';

type Props = {
  flow: KycFlowStatusView | null;
  loading?: boolean;
  actionLoading?: string;
  aadhaarRedirectUrl?: string;
  esignRedirectUrl?: string;
  aadhaarLinkDisabled?: boolean;
  esignLinkDisabled?: boolean;
  primaryActionDisabled?: boolean;
  error?: string;
  /** When true, always render the panel (Step 3 / Compliance) even before the first status fetch. */
  alwaysVisible?: boolean;
  onRunNext: (action: KycFlowNextAction) => void;
};

const FLOW_STEPS = [
  { key: 'pre', label: 'Pre-verification', stages: ['PRE_VERIFICATION_REQUIRED', 'PRE_VERIFICATION_PENDING'] },
  { key: 'kyc', label: 'KYC request', stages: ['KYC_REQUEST_REQUIRED'] },
  { key: 'aadhaar', label: 'Aadhaar via Digilocker', stages: ['AADHAAR_FETCH_REQUIRED', 'AADHAAR_FETCH_PENDING', 'AADHAAR_PROOFS_ATTACH_PENDING'] },
  { key: 'esign', label: 'eSign application', stages: ['ESIGN_REQUIRED', 'ESIGN_PENDING'] },
  { key: 'done', label: 'Submission', stages: ['KYC_COMPLETED', 'KYC_ALREADY_VERIFIED', 'KYC_SUBMITTED_WAITING_PROVIDER', 'KYC_IN_PROGRESS'] },
] as const;

const stepIndexForStage = (stage?: string) => {
  if (!stage) return 0;
  if (stage === 'KYC_COMPLETED' || stage === 'KYC_ALREADY_VERIFIED') return 4;
  const idx = FLOW_STEPS.findIndex(step => step.stages.includes(stage as never));
  return idx >= 0 ? idx : 0;
};

const isActionable = (action?: KycFlowNextAction) =>
  action && !['WAIT', 'COMPLETE'].includes(action);

const DEFAULT_FLOW: KycFlowStatusView = {
  stage: 'PRE_VERIFICATION_REQUIRED',
  nextAction: 'RUN_PRE_VERIFICATION',
  message: 'Run pre-verification to start the Cybrilla KYC flow (PAN check → KYC request → Aadhaar → eSign).',
};

export default function KycFlowPanel({
  flow,
  loading,
  actionLoading,
  aadhaarRedirectUrl,
  esignRedirectUrl,
  aadhaarLinkDisabled = false,
  esignLinkDisabled = false,
  primaryActionDisabled = false,
  error,
  alwaysVisible = false,
  onRunNext,
}: Props) {
  if (!alwaysVisible && !flow && !loading) return null;

  const displayFlow = flow || DEFAULT_FLOW;
  const activeStep = stepIndexForStage(displayFlow.stage);
  const complete = displayFlow.nextAction === 'COMPLETE';

  return (
    <div className="mt-5 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <h4 className="text-sm font-semibold text-slate-800">Cybrilla KYC flow</h4>
          <p className="mt-1 text-xs leading-5 text-slate-500">
            {loading && !flow
              ? 'Loading KYC flow status from Platizio backend…'
              : displayFlow.message}
          </p>
        </div>
        {loading && <Loader2 className="h-4 w-4 animate-spin text-slate-400" />}
      </div>

      {error && (
        <p className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
          {error} If this persists, restart the Platizio backend so migrations V37/V38 are applied.
        </p>
      )}

      <ol className="space-y-2">
        {FLOW_STEPS.map((step, index) => {
          const done = complete || index < activeStep;
          const current = !complete && index === activeStep;
          return (
            <li key={step.key} className="flex items-center gap-3 text-xs">
              {done ? (
                <CheckCircle2 className="h-4 w-4 flex-shrink-0 text-green-600" />
              ) : current ? (
                <Loader2 className="h-4 w-4 flex-shrink-0 animate-spin text-indigo-600" />
              ) : (
                <Circle className="h-4 w-4 flex-shrink-0 text-slate-300" />
              )}
              <span className={done ? 'font-medium text-green-700' : current ? 'font-semibold text-indigo-700' : 'text-slate-400'}>
                {step.label}
              </span>
            </li>
          );
        })}
      </ol>

      {isActionable(displayFlow.nextAction) && (
        <button
          type="button"
          onClick={() => onRunNext(displayFlow.nextAction)}
          disabled={Boolean(actionLoading) || primaryActionDisabled}
          className="mt-4 inline-flex items-center justify-center gap-2 rounded-lg bg-[#0B1B3E] px-4 py-2 text-xs font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
        >
          {actionLoading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ArrowRight className="h-3.5 w-3.5" />}
          {kycFlowNextActionLabel(displayFlow.nextAction)}
        </button>
      )}

      {aadhaarRedirectUrl && (
        displayFlow.nextAction === 'START_AADHAAR'
        || displayFlow.nextAction === 'REFRESH_AADHAAR'
        || displayFlow.stage === 'AADHAAR_FETCH_PENDING'
        || displayFlow.stage === 'AADHAAR_FETCH_REQUIRED'
      ) && (
        <KycProviderLink
          href={aadhaarRedirectUrl}
          label="Open Digilocker for investor"
          icon={<Fingerprint className="h-3.5 w-3.5" />}
          disabled={aadhaarLinkDisabled || Boolean(actionLoading)}
          disabledReason="Digilocker link is disabled while Aadhaar fetch is starting or already in progress."
        />
      )}

      {esignRedirectUrl && (
        displayFlow.nextAction === 'START_ESIGN'
        || displayFlow.nextAction === 'REFRESH_ESIGN'
        || displayFlow.stage === 'ESIGN_PENDING'
        || displayFlow.stage === 'ESIGN_REQUIRED'
      ) && (
        <KycProviderLink
          href={esignRedirectUrl}
          label="Open eSign for investor"
          icon={<FileSignature className="h-3.5 w-3.5" />}
          disabled={esignLinkDisabled || Boolean(actionLoading)}
          className="mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-violet-700 hover:text-violet-800"
          disabledReason="eSign link is disabled while signing is starting or already in progress."
        />
      )}

      {complete && (
        <p className="mt-3 flex items-center gap-1.5 text-xs font-medium text-green-700">
          <CheckCircle2 className="h-3.5 w-3.5" /> Cybrilla KYC flow is complete. Continue onboarding.
        </p>
      )}
    </div>
  );
}
