package com.platizio.wealthtech.integration.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExternalAuthTokenCacheStore {

    private static final Logger logger = LoggerFactory.getLogger(ExternalAuthTokenCacheStore.class);
    private static final TypeReference<Map<String, TokenCacheFileEntry>> CACHE_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Path cacheFile;
    private final boolean useLegacyFallback;
    private final Path legacyCacheFile = Path.of(".external-auth-token-cache.json").toAbsolutePath().normalize();

    @Autowired
    public ExternalAuthTokenCacheStore(
            ObjectMapper objectMapper,
            @Value("${external-auth.token-cache.enabled:true}") boolean enabled,
            @Value("${external-auth.token-cache.file:.external-auth-token-cache.json}") String cacheFile
    ) {
        this(objectMapper, enabled, cacheFile, true);
    }

    ExternalAuthTokenCacheStore(ObjectMapper objectMapper, boolean enabled, String cacheFile, boolean useLegacyFallback) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.cacheFile = Path.of(cacheFile).toAbsolutePath().normalize();
        this.useLegacyFallback = useLegacyFallback;
    }

    public Optional<StoredBearerToken> load(String audienceKey) {
        if (!enabled) {
            logger.info("external_auth_cache status='disabled'");
            return Optional.empty();
        }

        Path readableCacheFile = readableCacheFile();
        if (readableCacheFile == null) {
            logger.info("external_auth_cache status='missing' file='{}'", cacheFile);
            return Optional.empty();
        }

        TokenCacheFileEntry entry = readAll(readableCacheFile).get(audienceKey);
        if (entry == null) {
            logger.info("external_auth_cache status='entry_missing' key='{}' file='{}'", audienceKey, readableCacheFile);
            return Optional.empty();
        }

        try {
            StoredBearerToken token = new StoredBearerToken(
                    entry.value(),
                    Instant.parse(entry.refreshAt()),
                    Instant.parse(entry.expiresAt())
            );
            logger.info(
                    "external_auth_cache status='loaded' key='{}' file='{}' refresh_at='{}' expires_at='{}'",
                    audienceKey,
                    readableCacheFile,
                    token.refreshAt(),
                    token.expiresAt()
            );
            return Optional.of(token);
        } catch (RuntimeException ex) {
            logger.warn("external_auth token cache entry could not be read for '{}'", audienceKey);
            return Optional.empty();
        }
    }

    public void save(String audienceKey, StoredBearerToken token) {
        if (!enabled) {
            return;
        }

        Map<String, TokenCacheFileEntry> cache = readAll(readableCacheFileOrPrimary());
        cache.put(audienceKey, new TokenCacheFileEntry(
                token.value(),
                token.refreshAt().toString(),
                token.expiresAt().toString()
        ));
        writeAll(cache);
        logger.info("external_auth_cache status='saved' key='{}' file='{}'", audienceKey, cacheFile);
    }

    public void remove(String audienceKey) {
        if (!enabled) {
            return;
        }

        Path readableCacheFile = readableCacheFile();
        if (readableCacheFile == null) {
            return;
        }

        Map<String, TokenCacheFileEntry> cache = readAll(readableCacheFile);
        if (cache.remove(audienceKey) != null) {
            writeAll(cache);
        }
    }

    private Path readableCacheFile() {
        if (Files.exists(cacheFile)) {
            return cacheFile;
        }
        if (useLegacyFallback && !legacyCacheFile.equals(cacheFile) && Files.exists(legacyCacheFile)) {
            logger.info("external_auth_cache status='using_legacy_file' file='{}'", legacyCacheFile);
            return legacyCacheFile;
        }
        return null;
    }

    private Path readableCacheFileOrPrimary() {
        Path readableCacheFile = readableCacheFile();
        return readableCacheFile != null ? readableCacheFile : cacheFile;
    }

    private Map<String, TokenCacheFileEntry> readAll(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }

        try {
            return objectMapper.readValue(file.toFile(), CACHE_TYPE);
        } catch (IOException ex) {
            logger.warn("external_auth token cache file could not be read, starting with empty cache: {}", file);
            return new LinkedHashMap<>();
        }
    }

    private void writeAll(Map<String, TokenCacheFileEntry> cache) {
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), cache);
        } catch (IOException ex) {
            logger.warn("external_auth token cache file could not be written: {}", ex.getMessage());
        }
    }

    private record TokenCacheFileEntry(String value, String refreshAt, String expiresAt) {
    }
}
