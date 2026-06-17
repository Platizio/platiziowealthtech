type SchemeLike = {
  id?: string | number | null;
  externalSchemeCode?: string | number | null;
  externalIsin?: string | number | null;
  schemeName?: string | number | null;
};

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** True when the scheme has a PostgreSQL UUID (required for GET /products/schemes/{id}; preferred for POST /orders). */
export const isPersistedSchemeId = (value: unknown): value is string => {
  if (value == null) return false;
  const text = String(value).trim();
  return UUID_REGEX.test(text);
};

/** Stable React list key for Cybrilla/local fund rows (id may be null on live browse). */
export const productSchemeKey = (scheme: SchemeLike, index = 0): string => {
  const id = scheme?.id == null ? '' : String(scheme.id).trim();
  if (id) return id;

  const code = scheme?.externalSchemeCode == null ? '' : String(scheme.externalSchemeCode).trim();
  if (code) return `code:${code}`;

  const isin = scheme?.externalIsin == null ? '' : String(scheme.externalIsin).trim();
  if (isin) return `isin:${isin}`;

  const name = scheme?.schemeName == null ? '' : String(scheme.schemeName).trim();
  if (name) return `name:${name}:${index}`;

  return `scheme-${index}`;
};
