package com.meet2action.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meet2action.backend.agent.AgentClient;
import com.meet2action.backend.agent.HttpAgentClient;
import jakarta.validation.Validator;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AgentClientConfig {

    @Bean
    public AgentClient agentClient(
            @Value("${app.agent.url}") String url,
            @Value("${app.agent.connect-timeout-seconds}") long connectTimeoutSeconds,
            @Value("${app.agent.read-timeout-seconds}") long readTimeoutSeconds,
            Validator validator,
            ObjectMapper objectMapper) {
        // HttpURLConnection-based factory: the JDK HttpClient sends an h2c upgrade header that uvicorn rejects with 400.
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .withReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        RestClient restClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(new BufferingClientHttpRequestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings)))
                .build();
        return new HttpAgentClient(restClient, validator, objectMapper);
    }
}
