/**
 * Investor portal session user. Mirrors the backend InvestorAuthResponse contract
 * returned by GET /investor/me, POST /investor-auth/login/otp/verify and
 * POST /investor-auth/signup. Kept separate from the distributor AuthUser
 * (src/types/auth.ts) so the two sessions never share normalization rules.
 */
export interface InvestorUser {
  accountId: string;
  email?: string;
  fullName?: string;
  status?: string;
  emailVerified?: boolean;
  mobileVerified?: boolean;
  investorLinked?: boolean;
  [key: string]: unknown;
}

export const normalizeInvestorUser = (
  user: InvestorUser | null | undefined,
): InvestorUser | null => {
  if (!user) return null;
  const accountId = String(user.accountId ?? '');
  if (!accountId) return null;
  return { ...user, accountId };
};
