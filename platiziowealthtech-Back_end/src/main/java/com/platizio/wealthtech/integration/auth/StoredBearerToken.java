package com.platizio.wealthtech.integration.auth;

import java.time.Instant;

record StoredBearerToken(String value, Instant refreshAt, Instant expiresAt) {
}
