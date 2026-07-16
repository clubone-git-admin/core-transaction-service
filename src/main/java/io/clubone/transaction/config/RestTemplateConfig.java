package io.clubone.transaction.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import io.clubone.transaction.security.TenantContext;
import io.clubone.transaction.security.TenantHttpHeaders;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class RestTemplateConfig {

  @Bean
  @Primary
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return buildWithTenantHeaders(builder, Duration.ofSeconds(3), Duration.ofSeconds(8));
  }

  @Bean("userAccessRestTemplate")
  public RestTemplate userAccessRestTemplate(RestTemplateBuilder builder) {
    return buildWithTenantHeaders(builder, Duration.ofSeconds(3), Duration.ofSeconds(8));
  }

  /**
   * Client-agreement create under load can exceed the default 8s before the API gateway
   * times out; keep this slightly under typical ALB/gateway idle so we fail in-app first.
   */
  @Bean("clientAgreementRestTemplate")
  public RestTemplate clientAgreementRestTemplate(
      RestTemplateBuilder builder,
      @org.springframework.beans.factory.annotation.Value("${clubone.load.client-agreement-http.read-timeout-ms:15000}")
      long readTimeoutMs) {
    long readMs = Math.max(5_000L, readTimeoutMs);
    return buildWithTenantHeaders(builder, Duration.ofSeconds(3), Duration.ofMillis(readMs));
  }

  private static RestTemplate buildWithTenantHeaders(
      RestTemplateBuilder builder, Duration connectTimeout, Duration readTimeout) {
    List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
    interceptors.add((request, body, execution) -> {
      TenantContext ctx = TenantContext.get();
      if (ctx != null) {
        var headers = TenantHttpHeaders.fromContext();
        headers.forEach((name, values) -> {
          if (!request.getHeaders().containsKey(name)) {
            request.getHeaders().addAll(name, values);
          }
        });
      }
      return execution.execute(request, body);
    });

    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .version(HttpClient.Version.HTTP_1_1)
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);

    return builder
        .requestFactory(() -> requestFactory)
        .additionalInterceptors(interceptors)
        .build();
  }
}
