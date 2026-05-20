import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';

type SpringFieldError = {
  field?: string;
  fieldName?: string;
  objectName?: string;
  defaultMessage?: string;
  message?: string;
};

export type ServerValidation = {
  fieldErrors: Record<string, string>;
  globalErrors: string[];
};

export const parseServerValidation = (payload: any): ServerValidation => {
  const rawErrors = Array.isArray(payload?.errors)
    ? payload.errors
    : Array.isArray(payload?.data?.errors)
      ? payload.data.errors
      : [];

  const fieldErrors: Record<string, string> = {};
  const globalErrors: string[] = [];

  rawErrors.forEach((error: SpringFieldError | string) => {
    if (typeof error === 'string') {
      globalErrors.push(error);
      return;
    }

    const message = error.defaultMessage || error.message || 'Invalid value';
    const field = error.field || error.fieldName;
    if (field) fieldErrors[field] = message;
    else globalErrors.push(message);
  });

  if (payload?.message && rawErrors.length === 0) {
    globalErrors.push(payload.message);
  }

  if (payload?.error && rawErrors.length === 0 && payload.error !== payload?.message) {
    globalErrors.push(payload.error);
  }

  return { fieldErrors, globalErrors };
};

export const readServerValidation = async (response: Response): Promise<ServerValidation & { payload: any }> => {
  const payload = await response.json().catch(() => null);
  return {
    ...parseServerValidation(payload),
    payload,
  };
};

export const setHookFormServerErrors = <T extends FieldValues>(
  setError: UseFormSetError<T>,
  fieldErrors: Record<string, string>,
  fieldMap: Record<string, Path<T>> = {},
) => {
  Object.entries(fieldErrors).forEach(([field, message]) => {
    setError((fieldMap[field] || field) as Path<T>, { type: 'server', message });
  });
};

export const mapServerErrorsToState = (
  fieldErrors: Record<string, string>,
  fieldMap: Record<string, string> = {},
) =>
  Object.entries(fieldErrors).reduce<Record<string, string>>((next, [field, message]) => {
    next[fieldMap[field] || field] = message;
    return next;
  }, {});

export const buildValidationSummary = (validation: ServerValidation, fallback = 'Please fix the highlighted fields.') => {
  if (validation.globalErrors.length > 0) return validation.globalErrors.join(' ');
  if (Object.keys(validation.fieldErrors).length > 0) return fallback;
  return '';
};
