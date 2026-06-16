export type ArchivedInvestorRef = {
  distributorId: string;
  pan?: string;
  cybrillaInvestorId?: string;
  fullName?: string;
  archivedAt: string;
};

const STORAGE_KEY = 'platizio.archivedInvestorsForRestore';

function readAll(): ArchivedInvestorRef[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function writeAll(entries: ArchivedInvestorRef[]) {
  if (typeof window === 'undefined') return;
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
}

export function rememberArchivedInvestor(entry: Omit<ArchivedInvestorRef, 'archivedAt'>) {
  if (!entry.distributorId || (!entry.pan && !entry.cybrillaInvestorId)) return;
  const normalizedPan = entry.pan?.trim().toUpperCase();
  const existing = readAll().filter(item => {
    if (item.distributorId !== entry.distributorId) return true;
    if (normalizedPan && item.pan?.toUpperCase() === normalizedPan) return false;
    if (entry.cybrillaInvestorId && item.cybrillaInvestorId === entry.cybrillaInvestorId) return false;
    return true;
  });
  existing.push({
    ...entry,
    pan: normalizedPan || entry.pan,
    archivedAt: new Date().toISOString(),
  });
  writeAll(existing);
}

export function listArchivedInvestorsForDistributor(distributorId?: string): ArchivedInvestorRef[] {
  if (!distributorId) return [];
  return readAll().filter(item => item.distributorId === distributorId);
}

export function forgetArchivedInvestor(distributorId: string, pan?: string, cybrillaInvestorId?: string) {
  const normalizedPan = pan?.trim().toUpperCase();
  const remaining = readAll().filter(item => {
    if (item.distributorId !== distributorId) return true;
    if (normalizedPan && item.pan?.toUpperCase() === normalizedPan) return false;
    if (cybrillaInvestorId && item.cybrillaInvestorId === cybrillaInvestorId) return false;
    return true;
  });
  writeAll(remaining);
}

export async function restoreArchivedInvestorRef(
  entry: ArchivedInvestorRef,
  apiFetchFn: typeof import('../config/api').apiFetch,
): Promise<{ ok: boolean; name: string; error?: string }> {
  const name = entry.fullName || entry.pan || entry.cybrillaInvestorId || 'investor';
  const params = new URLSearchParams();
  params.set('distributorId', entry.distributorId);
  if (entry.cybrillaInvestorId) params.set('cybrillaInvestorId', entry.cybrillaInvestorId);
  else if (entry.pan) params.set('pan', entry.pan);
  else return { ok: false, name, error: 'No Finprim identifier saved for restore' };

  const response = await apiFetchFn(`/investors/restore-from-cybrilla?${params.toString()}`, { method: 'POST' });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    return { ok: false, name, error: body?.message || `Restore failed with HTTP ${response.status}` };
  }
  forgetArchivedInvestor(entry.distributorId, entry.pan, entry.cybrillaInvestorId);
  return { ok: true, name };
}
