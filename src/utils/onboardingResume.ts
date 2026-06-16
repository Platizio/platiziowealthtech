import type { KycFlowStatusView } from './kycFlow';

const normalizeWorkflowStatus = (value?: string | null) =>
  String(value || '').trim().toUpperCase().replace(/-/g, '_');

export const isKycVerifiedStatus = (status?: string | null) => {
  const normalized = normalizeWorkflowStatus(status);
  return normalized === 'COMPLETED' || normalized === 'VERIFIED';
};

export const isEsignStatusComplete = (status?: string | null) => {
  const normalized = normalizeWorkflowStatus(status);
  return ['COMPLETED', 'COMPLETE', 'SUCCESSFUL', 'VERIFIED', 'SUCCESS'].includes(normalized);
};

/** Maps backend onboarding resume `nextStep` to InvestorOnboarding wizard step (1–7). */
export const mapBackendNextStepToWizardStep = (
  nextStep?: string | null,
  documentsComplete?: boolean,
): number | null => {
  switch (normalizeWorkflowStatus(nextStep)) {
    case 'SYNC_INVESTOR_PROFILE':
      return 3;
    case 'APPLY_KYC':
    case 'REFRESH_KYC':
      return 4;
    case 'ADD_BANK_ACCOUNT':
    case 'REFRESH_BANK_VERIFICATION':
      return 5;
    case 'UPLOAD_DOCUMENTS':
      return 7;
    case 'READY_FOR_TRANSACTIONS':
      return documentsComplete ? 7 : 6;
    default:
      return null;
  }
};

export const inferWizardStepFromInvestor = (investor: Record<string, unknown> | null | undefined) => {
  if (!investor) return 1;
  const kycStatus = normalizeWorkflowStatus(String(investor.kycStatus || ''));
  const bankStatus = normalizeWorkflowStatus(String(investor.bankVerificationStatus || ''));
  if (!isKycVerifiedStatus(kycStatus)) return 4;
  if (!bankStatus || ['NOT_CAPTURED', 'VERIFICATION_PENDING', 'PENDING', 'FAILED'].includes(bankStatus)) {
    return 5;
  }
  return 6;
};

export const canProceedFromKycStep = (input: {
  isExistingKycVerified: boolean;
  kycPhase: string;
  kycFlowStatus: KycFlowStatusView | null;
  kycDecisionCanProceed: boolean;
  kycDecisionRequiresFreshKyc: boolean;
  aadhaarFetchComplete: boolean;
  kycRequestId: string;
  esignStatus?: string | null;
}) => {
  if (input.isExistingKycVerified) return true;
  if (input.kycPhase === 'verified') return true;
  if (input.kycFlowStatus?.nextAction === 'COMPLETE') return true;
  if (
    input.kycFlowStatus?.stage === 'KYC_COMPLETED'
    || input.kycFlowStatus?.stage === 'KYC_ALREADY_VERIFIED'
  ) {
    return true;
  }
  if (input.kycDecisionCanProceed && !input.kycDecisionRequiresFreshKyc) return true;
  if (
    input.kycDecisionRequiresFreshKyc
    && input.kycRequestId
    && input.aadhaarFetchComplete
    && isEsignStatusComplete(input.esignStatus)
  ) {
    return true;
  }
  if (
    input.kycRequestId
    && input.aadhaarFetchComplete
    && isEsignStatusComplete(input.esignStatus)
    && input.kycFlowStatus?.stage === 'KYC_SUBMITTED_WAITING_PROVIDER'
  ) {
    return true;
  }
  return false;
};
