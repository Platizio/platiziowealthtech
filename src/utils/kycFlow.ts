import type { AadhaarVerificationView } from './kycPreVerification';
import { normalizeAadhaarVerificationResponse } from './kycPreVerification';

export type KycFlowNextAction =
  | 'RUN_PRE_VERIFICATION'
  | 'CREATE_KYC_REQUEST'
  | 'START_AADHAAR'
  | 'REFRESH_AADHAAR'
  | 'START_ESIGN'
  | 'REFRESH_ESIGN'
  | 'WAIT'
  | 'COMPLETE';

export type KycFlowStatusView = {
  stage: string;
  nextAction: KycFlowNextAction;
  message: string;
  investor?: Record<string, unknown> | null;
  aadhaarRedirectUrl?: string;
  esignRedirectUrl?: string;
  fieldsNeeded?: string[];
};

export type EsignVerificationView = {
  esignId?: string;
  status?: string;
  redirectUrl?: string;
  completed: boolean;
  investor?: Record<string, unknown> | null;
};

const readText = (value: unknown) => String(value ?? '').trim();

export const buildKycPostbackUrl = (investorId: string, kycReturn: 'aadhaar' | 'esign') => {
  const url = new URL(`${window.location.origin}/distributor/investor-onboarding`);
  url.searchParams.set('investorId', investorId);
  url.searchParams.set('kycReturn', kycReturn);
  return url.toString();
};

export const normalizeKycFlowStatus = (payload: unknown): KycFlowStatusView | null => {
  if (!payload || typeof payload !== 'object') return null;
  const data = payload as Record<string, unknown>;
  const nextAction = readText(data.nextAction) as KycFlowNextAction;
  if (!nextAction) return null;
  const fieldsNeeded = Array.isArray(data.fieldsNeeded)
    ? data.fieldsNeeded.map((field) => readText(field)).filter(Boolean)
    : [];
  const investor = (data.investor as Record<string, unknown> | undefined) || null;
  const readinessReason = readText(investor?.kycReadinessReason);
  const backendMessage = readText(data.message);
  const readinessFailed = readText(investor?.kycReadinessStatus).toLowerCase() === 'failed';
  return {
    stage: readText(data.stage) || 'UNKNOWN',
    nextAction,
    message: readinessFailed && readinessReason ? readinessReason : backendMessage,
    investor,
    aadhaarRedirectUrl: readText(data.aadhaarRedirectUrl) || undefined,
    esignRedirectUrl: readText(data.esignRedirectUrl) || undefined,
    fieldsNeeded,
  };
};

export const normalizeEsignVerificationResponse = (payload: unknown): EsignVerificationView | null => {
  if (!payload || typeof payload !== 'object') return null;
  const data = payload as Record<string, unknown>;
  return {
    esignId: readText(data.esignId) || undefined,
    status: readText(data.status) || undefined,
    redirectUrl: readText(data.redirectUrl) || undefined,
    completed: Boolean(data.completed),
    investor: (data.investor as Record<string, unknown> | undefined) || null,
  };
};

export const kycFlowStageLabel = (stage: string) => {
  switch (stage) {
    case 'PRE_VERIFICATION_REQUIRED': return 'Pre-verification';
    case 'KYC_REQUEST_REQUIRED': return 'KYC request';
    case 'AADHAAR_FETCH_REQUIRED':
    case 'AADHAAR_FETCH_PENDING':
    case 'AADHAAR_PROOFS_ATTACH_PENDING': return 'Aadhaar verification';
    case 'ESIGN_REQUIRED':
    case 'ESIGN_PENDING': return 'eSign application';
    case 'KYC_FIELDS_REQUIRED': return 'KYC fields required';
    case 'KYC_COMPLETED':
    case 'KYC_ALREADY_VERIFIED': return 'KYC complete';
    default: return stage.replace(/_/g, ' ').toLowerCase();
  }
};

export const kycFlowNextActionLabel = (action: KycFlowNextAction) => {
  switch (action) {
    case 'RUN_PRE_VERIFICATION': return 'Run pre-verification';
    case 'CREATE_KYC_REQUEST': return 'Create KYC request';
    case 'START_AADHAAR': return 'Start Aadhaar fetch';
    case 'REFRESH_AADHAAR': return 'Refresh Aadhaar status';
    case 'START_ESIGN': return 'Start eSign';
    case 'REFRESH_ESIGN': return 'Refresh eSign status';
    case 'WAIT': return 'Waiting on Platizio';
    case 'COMPLETE': return 'Flow complete';
    default: return action;
  }
};

export const mergeAadhaarIntoFlow = (
  flow: KycFlowStatusView | null,
  aadhaar: AadhaarVerificationView | null,
): KycFlowStatusView | null => {
  if (!flow && !aadhaar?.investor) return flow;
  const investor = aadhaar?.investor || flow?.investor || null;
  return flow ? { ...flow, investor: investor || flow.investor } : null;
};

export const parseAadhaarFromFlowActions = (payload: unknown) =>
  normalizeAadhaarVerificationResponse(payload);
