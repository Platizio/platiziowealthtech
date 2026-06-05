package com.platizio.wealthtech.integration;

/**
 * Raised when Cybrilla / Fintech Primitives cannot be reached at all — DNS
 * resolution failure (UnknownHostException), connection refused, or timeout —
 * i.e. a transient connectivity problem rather than a business/validation error
 * returned by the provider.
 *
 * Distinguishing this from a generic {@link CybrillaApiException} lets callers
 * degrade gracefully (defer the work to a pending state) and lets the API return
 * 503 Service Unavailable with a clear "try again" message instead of a generic
 * 502 with a scary stack trace.
 */
public class CybrillaUnavailableException extends CybrillaApiException {
    public CybrillaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
