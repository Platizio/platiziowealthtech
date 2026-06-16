import { isPersistedSchemeId } from './productSchemeKey';

export type SchemeLike = {
  id?: string | null;
  schemeName?: string | null;
  scheme_name?: string | null;
  name?: string | null;
  amcName?: string | null;
  amc_name?: string | null;
  externalIsin?: string | null;
  external_isin?: string | null;
  externalSchemeCode?: string | null;
  external_scheme_code?: string | null;
  externalFetchRequestJson?: string | null;
  external_fetch_request_json?: string | null;
  active?: boolean | null;
  metadataJson?: string | Record<string, unknown> | null;
};

export const parseSchemeMetadata = (metadataJson?: string | Record<string, unknown> | null): Record<string, unknown> => {
  if (!metadataJson) return {};
  if (typeof metadataJson === 'object') return metadataJson;
  try {
    const parsed = JSON.parse(metadataJson);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
};

const readText = (...values: unknown[]) => {
  for (const value of values) {
    if (value === undefined || value === null) continue;
    const text = String(value).trim();
    if (text) return text;
  }
  return '';
};

const ISIN_REGEX = /^INF[A-Z0-9]{10}$/i;

/** ISIN from externalIsin or externalSchemeCode when the code is an ISIN. */
export const schemeIsin = (scheme?: SchemeLike | null): string => {
  const direct = readText(scheme?.externalIsin, scheme?.external_isin);
  if (direct) return direct.toUpperCase();
  const code = readText(scheme?.externalSchemeCode, scheme?.external_scheme_code);
  return ISIN_REGEX.test(code) ? code.toUpperCase() : '';
};

const fetchRequestText = (scheme?: SchemeLike | null) =>
  readText(scheme?.externalFetchRequestJson, scheme?.external_fetch_request_json).toLowerCase();

/** OMS fund_schemes are not POA-orderable — block only when we know the row came from that import. */
export const isOmsFundScheme = (scheme?: SchemeLike | null): boolean => {
  const fetchJson = fetchRequestText(scheme);
  return fetchJson.includes('/api/oms/fund_schemes');
};

export const formatSchemeDisplayName = (scheme?: SchemeLike | null): string => {
  if (!scheme) return 'Unknown fund';
  const meta = parseSchemeMetadata(scheme.metadataJson);
  const nestedSchemeName = readText(
    (meta.mf_scheme as Record<string, unknown> | undefined)?.name,
    (meta.sif_scheme as Record<string, unknown> | undefined)?.name,
  );
  return (
    readText(scheme.schemeName, scheme.scheme_name, scheme.name, nestedSchemeName)
    || readText(scheme.externalIsin, scheme.external_isin, scheme.externalSchemeCode, scheme.external_scheme_code)
    || 'Unknown fund'
  );
};

export const formatAmcDisplayName = (scheme?: SchemeLike | null): string => {
  if (!scheme) return 'Unknown AMC';
  const meta = parseSchemeMetadata(scheme.metadataJson);
  const nestedFundName = readText(
    (meta.mf_fund as Record<string, unknown> | undefined)?.name,
    (meta.sif_fund as Record<string, unknown> | undefined)?.name,
  );
  return readText(scheme.amcName, scheme.amc_name, nestedFundName) || 'Unknown AMC';
};

/**
 * POA-orderable when it has an ISIN and is not an OMS fund_schemes import.
 * Positive signals: metadata gateway/object, or externalFetchRequestJson from mf/sif scheme plans.
 * Default catalogue (poa-mf) rows qualify via ISIN alone — metadata often only has NAV/returns.
 */
export const isPoaOrderableScheme = (scheme?: SchemeLike | null): boolean => {
  if (!scheme || scheme.active === false) return false;
  const isin = schemeIsin(scheme);
  if (!isin) return false;
  if (isOmsFundScheme(scheme)) return false;

  const meta = parseSchemeMetadata(scheme.metadataJson);
  const gateway = readText(meta.gateway).toLowerCase();
  const objectType = readText(meta.object).toLowerCase();
  if (gateway === 'cybrillapoa' || objectType === 'mf_scheme_plan' || objectType === 'sif_scheme_plan') {
    return true;
  }

  const fetchJson = fetchRequestText(scheme);
  if (fetchJson.includes('mf_scheme_plans') || fetchJson.includes('sif_scheme_plans')) {
    return true;
  }

  return true;
};

/** Safe for POST /orders — persisted UUID + ISIN + display name, not OMS. */
export const isTransactionReadyScheme = (scheme?: SchemeLike | null): boolean => {
  if (!isPersistedSchemeId(scheme?.id)) return false;
  if (!isPoaOrderableScheme(scheme)) return false;
  return formatSchemeDisplayName(scheme) !== 'Unknown fund';
};

export const readSchemeMinSip = (scheme?: SchemeLike | null, fallback = 500): number => {
  const meta = parseSchemeMetadata(scheme.metadataJson);
  const thresholds = Array.isArray(meta.thresholds) ? meta.thresholds : [];
  const sipThresholds = thresholds.filter(
    (entry: any) => String(entry?.type || '').toLowerCase() === 'sip',
  );
  const mins = sipThresholds
    .map((entry: any) => Number(entry?.amount_min))
    .filter((value: number) => Number.isFinite(value) && value > 0);
  if (mins.length === 0) return fallback;
  return Math.min(...mins);
};
