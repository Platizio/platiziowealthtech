const normalize = (value?: string) => String(value || '').trim().toUpperCase();

export const isKycVerifiedForTransaction = (kycStatus?: string) => {
  const status = normalize(kycStatus);
  return status === 'COMPLETED' || status === 'VERIFIED';
};

export const isBankVerifiedForTransaction = (bankVerificationStatus?: string) =>
  normalize(bankVerificationStatus) === 'VERIFIED';

/**
 * Matches backend `/investors/search/transaction-eligible` gate:
 * KYC completed and bank verification verified.
 */
export const isTransactionEligible = (investor: {
  kycStatus?: string;
  bankVerificationStatus?: string;
  investorStatus?: string;
}) => {
  if (normalize(investor.investorStatus) === 'READY_FOR_TRANSACTIONS') return true;
  return isKycVerifiedForTransaction(investor.kycStatus)
    && isBankVerifiedForTransaction(investor.bankVerificationStatus);
};

export const transactionEligibilityMessage = (investor: {
  kycStatus?: string;
  bankVerificationStatus?: string;
}) => {
  if (!isKycVerifiedForTransaction(investor.kycStatus)) {
    return 'Complete KYC before placing an order.';
  }
  if (!isBankVerifiedForTransaction(investor.bankVerificationStatus)) {
    return 'Add and verify a bank account before placing an order.';
  }
  return '';
};
