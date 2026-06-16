import { apiFetch } from '../config/api';

export type PincodeLookupResult = {
  code: string;
  city: string;
  district: string;
  stateName: string;
  countryAnsiCode?: string;
  cities: string[];
};

export type IfscLookupResult = {
  ifscCode: string;
  bankName: string;
  branchName: string;
  branchAddress: string;
  city: string;
  district: string;
  state: string;
  micrCode: string;
};

/** Strip sandbox junk suffixes (e.g. Mumbai%00$##$$) from Cybrilla pincode city labels. */
export const sanitizeLocationLabel = (raw: unknown): string => {
  const trimmed = String(raw ?? '').trim();
  if (!trimmed) return '';
  const marker = trimmed.search(/%00|%|\0/);
  const base = marker > 0 ? trimmed.slice(0, marker).trim() : trimmed;
  const match = base.match(/^[\p{L}][\p{L}\p{M}\s.'-]*/u);
  return match ? match[0].trim().replace(/\s+/g, ' ') : '';
};

export const normalizeStateLabel = (raw: unknown): string => {
  const cleaned = sanitizeLocationLabel(raw);
  if (!cleaned) return '';
  return cleaned.charAt(0).toUpperCase() + cleaned.slice(1).toLowerCase();
};

const readJson = async (response: Response) => {
  try {
    return await response.json();
  } catch {
    return null;
  }
};

export const fetchPincodeDetails = async (pincode: string): Promise<PincodeLookupResult> => {
  const normalized = pincode.trim().replace(/\D/g, '');
  if (!/^\d{6}$/.test(normalized)) {
    throw new Error('Enter a valid 6-digit PIN code.');
  }

  const response = await apiFetch(`/banks/pincodes/${normalized}`);
  const body = await readJson(response);
  if (!response.ok) {
    throw new Error(typeof body === 'string' ? body : body?.message || `PIN lookup failed with HTTP ${response.status}`);
  }

  const rawDistrict = sanitizeLocationLabel(body?.district);
  const rawCity = sanitizeLocationLabel(body?.city) || rawDistrict;
  const cityOptions = new Set<string>();
  if (Array.isArray(body?.cities)) {
    body.cities.forEach((value: unknown) => {
      const cleaned = sanitizeLocationLabel(value);
      if (cleaned) cityOptions.add(cleaned);
    });
  }
  if (rawCity) cityOptions.add(rawCity);
  const cities = Array.from(cityOptions);

  return {
    code: String(body?.code || normalized),
    city: rawCity,
    district: rawDistrict,
    stateName: normalizeStateLabel(body?.stateName || body?.state_name || body?.state),
    countryAnsiCode: String(body?.countryAnsiCode || body?.country_ansi_code || '').trim() || undefined,
    cities,
  };
};

export const fetchIfscDetails = async (ifscCode: string): Promise<IfscLookupResult> => {
  const normalized = ifscCode.trim().toUpperCase();
  if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(normalized)) {
    throw new Error('Enter a valid 11-character IFSC code.');
  }

  const response = await apiFetch(`/banks/ifsc/${normalized}`);
  const body = await readJson(response);
  if (!response.ok) {
    throw new Error(typeof body === 'string' ? body : body?.message || `IFSC lookup failed with HTTP ${response.status}`);
  }

  return {
    ifscCode: String(body?.ifscCode || body?.ifsc_code || normalized),
    bankName: String(body?.bankName || body?.bank_name || '').trim(),
    branchName: String(body?.branchName || body?.branch_name || '').trim(),
    branchAddress: String(body?.branchAddress || body?.branch_address || '').trim(),
    city: String(body?.city || '').trim(),
    district: String(body?.district || '').trim(),
    state: String(body?.state || '').trim(),
    micrCode: String(body?.micrCode || body?.micr_code || '').trim(),
  };
};
