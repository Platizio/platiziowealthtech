import React, { useCallback, useRef, useState } from 'react';
import { Loader2, Search } from 'lucide-react';
import { fetchPincodeDetails } from '../utils/referenceLookup';

type ResolvedLocation = {
  city: string;
  state: string;
  district?: string;
};

type Props = {
  postalCode: string;
  city: string;
  state: string;
  onPostalCodeChange: (value: string) => void;
  onCityChange: (value: string) => void;
  onStateChange: (value: string) => void;
  /** Preferred: apply city + state together so React state updates do not overwrite each other. */
  onLocationResolved?: (location: ResolvedLocation) => void;
  inputClassName: string;
  selectClassName: string;
  postalCodeError?: string;
  cityError?: string;
  stateError?: string;
  disabled?: boolean;
};

export default function PincodeCityFields({
  postalCode,
  city,
  state,
  onPostalCodeChange,
  onCityChange,
  onStateChange,
  onLocationResolved,
  inputClassName,
  selectClassName,
  postalCodeError,
  cityError,
  stateError,
  disabled = false,
}: Props) {
  const [cities, setCities] = useState<string[]>([]);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [lookupError, setLookupError] = useState('');
  const [stateFromPin, setStateFromPin] = useState(false);
  const lastFetchedPin = useRef('');

  const normalizedPin = postalCode.trim().replace(/\D/g, '');
  const pinIsValid = /^\d{6}$/.test(normalizedPin);
  const pinAlreadyLoaded = pinIsValid && normalizedPin === lastFetchedPin.current;

  const resetLookupResults = useCallback(() => {
    setCities([]);
    setLookupError('');
    setStateFromPin(false);
    lastFetchedPin.current = '';
  }, []);

  const handlePostalCodeChange = (value: string) => {
    const nextNormalized = value.trim().replace(/\D/g, '');
    const hadLoadedPin = Boolean(lastFetchedPin.current);
    if (hadLoadedPin && nextNormalized !== lastFetchedPin.current) {
      resetLookupResults();
      if (onLocationResolved) {
        onLocationResolved({ city: '', state: '' });
      } else {
        onCityChange('');
        onStateChange('');
      }
    }
    onPostalCodeChange(value);
  };

  const searchPin = async () => {
    if (!pinIsValid) {
      setLookupError('Enter a valid 6-digit PIN code before searching.');
      return;
    }
    if (pinAlreadyLoaded) {
      return;
    }

    setLookupLoading(true);
    setLookupError('');
    setStateFromPin(false);

    try {
      const result = await fetchPincodeDetails(normalizedPin);
      lastFetchedPin.current = normalizedPin;
      const options = result.cities.length ? result.cities : (result.city ? [result.city] : []);
      setCities(options);

      const resolvedCity = options.length === 1 ? options[0] : '';
      const resolvedState = result.stateName || '';

      if (onLocationResolved) {
        onLocationResolved({
          city: resolvedCity,
          state: resolvedState,
          district: result.district || undefined,
        });
      } else {
        if (resolvedState) {
          onStateChange(resolvedState);
        } else {
          onStateChange('');
        }
        onCityChange(resolvedCity);
      }

      setStateFromPin(Boolean(resolvedState));
    } catch (err) {
      lastFetchedPin.current = '';
      setCities([]);
      setStateFromPin(false);
      setLookupError(err instanceof Error ? err.message : 'Unable to fetch location for this PIN code.');
    } finally {
      setLookupLoading(false);
    }
  };

  const labelClass = 'block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5';
  const errorClass = 'mt-1.5 text-xs font-medium text-red-600';
  const lockedFieldClass = ' bg-slate-100 cursor-not-allowed';
  const buttonClass =
    'inline-flex flex-shrink-0 items-center justify-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50';

  return (
    <>
      <div>
        <label className={labelClass}>
          PIN Code<span className="text-red-400 ml-0.5">*</span>
        </label>
        <div className="flex gap-2">
          <input
            value={postalCode}
            onChange={e => handlePostalCodeChange(e.target.value.replace(/\D/g, '').slice(0, 6))}
            onKeyDown={e => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void searchPin();
              }
            }}
            placeholder="400001"
            maxLength={6}
            disabled={disabled || lookupLoading}
            className={`${inputClassName} flex-1`}
          />
          <button
            type="button"
            onClick={() => void searchPin()}
            disabled={disabled || lookupLoading || !pinIsValid || pinAlreadyLoaded}
            title={pinAlreadyLoaded ? 'Location already loaded for this PIN' : 'Look up city and state from PIN'}
            className={buttonClass}
          >
            {lookupLoading
              ? <Loader2 className="h-3.5 w-3.5 animate-spin" />
              : <Search className="h-3.5 w-3.5" />}
            {lookupLoading ? 'Searching…' : pinAlreadyLoaded ? 'Loaded' : 'Search PIN'}
          </button>
        </div>
        {postalCodeError && <p className={errorClass}>{postalCodeError}</p>}
        {lookupError && !postalCodeError && <p className={errorClass}>{lookupError}</p>}
        {pinIsValid && !pinAlreadyLoaded && !lookupLoading && !lookupError && (
          <p className="mt-1 text-[11px] text-slate-400">Click Search PIN to load city and state</p>
        )}
      </div>

      <div>
        <label className={labelClass}>
          State<span className="text-red-400 ml-0.5">*</span>
        </label>
        <input
          value={state}
          onChange={e => onStateChange(e.target.value)}
          placeholder={lookupLoading ? 'Fetching from PIN…' : 'Search PIN to auto-fill'}
          readOnly={stateFromPin && Boolean(state)}
          disabled={disabled || lookupLoading}
          className={`${inputClassName}${stateFromPin && state ? lockedFieldClass : ''}`}
        />
        {stateFromPin && state && !stateError && (
          <p className="mt-1 text-[11px] text-slate-400">Filled automatically from PIN code</p>
        )}
        {stateError && <p className={errorClass}>{stateError}</p>}
      </div>

      <div>
        <label className={labelClass}>
          City<span className="text-red-400 ml-0.5">*</span>
        </label>
        {cities.length > 0 ? (
          <select
            value={city}
            onChange={e => onCityChange(e.target.value)}
            disabled={disabled || lookupLoading}
            className={selectClassName}
          >
            <option value="">Select city</option>
            {cities.map(option => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        ) : (
          <select
            value={city}
            onChange={e => onCityChange(e.target.value)}
            disabled={disabled || lookupLoading || !pinAlreadyLoaded}
            className={selectClassName}
          >
            <option value="">
              {lookupLoading
                ? 'Loading cities…'
                : pinAlreadyLoaded
                  ? 'No cities returned for this PIN'
                  : 'Search PIN to load cities'}
            </option>
            {city ? <option value={city}>{city}</option> : null}
          </select>
        )}
        {cityError && <p className={errorClass}>{cityError}</p>}
      </div>
    </>
  );
}
