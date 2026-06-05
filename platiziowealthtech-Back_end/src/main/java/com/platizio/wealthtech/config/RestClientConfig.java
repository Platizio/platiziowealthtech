package com.platizio.wealthtech.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Use the JDK {@link HttpClient} request factory so PATCH is supported.
 * The default {@code SimpleClientHttpRequestFactory} (HttpURLConnection) throws
 * {@code Invalid HTTP method: PATCH} on several JDK/runtime combinations.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(90));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
