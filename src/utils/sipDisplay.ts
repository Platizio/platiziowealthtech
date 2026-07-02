/** Prettifies a backend SIP frequency (DAILY/WEEKLY/MONTHLY/QUARTERLY → Daily/…),
 *  falling back to the raw value for anything unrecognised. */
export const prettySipFrequency = (value?: string | null): string | null => {
  const raw = String(value || '').trim();
  if (!raw) return null;
  const known: Record<string, string> = {
    DAILY: 'Daily',
    WEEKLY: 'Weekly',
    MONTHLY: 'Monthly',
    QUARTERLY: 'Quarterly',
  };
  return known[raw.toUpperCase()] || raw;
};
