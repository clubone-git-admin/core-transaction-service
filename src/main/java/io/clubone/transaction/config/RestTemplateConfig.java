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
    return buildWithTenantHeaders(builder);
  }

  @Bean("userAccessRestTemplate")
  public RestTemplate userAccessRestTemplate(RestTemplateBuilder builder) {
    return buildWithTenantHeaders(builder);
  }

  private static RestTemplate buildWithTenantHeaders(RestTemplateBuilder builder) {
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

    // JDK HttpClient + virtual-thread executor: connection reuse without Apache pool dep.
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .version(HttpClient.Version.HTTP_1_1)
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(8));

    return builder
        .requestFactory(() -> requestFactory)
        .additionalInterceptors(interceptors)
        .build();
  }
}
