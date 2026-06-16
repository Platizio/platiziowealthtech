export interface AuthUser {
  id: string;
  distributorId?: string;
  role?: string;
  email?: string;
  fullName?: string;
  name?: string;
  [key: string]: unknown;
}

export const ADMIN_ROLES = new Set(['ADMIN', 'MASTER_DISTRIBUTOR']);

export const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';

export const normalizeAuthUser = (user: AuthUser | null | undefined): AuthUser | null => {
  if (!user) return null;
  return {
    ...user,
    id: String(user.id || user.distributorId || ''),
  };
};

export const canAccessAdmin = (user: AuthUser | null | undefined) =>
  ADMIN_ROLES.has(normalizeRole(user?.role));
