package com.meet2action.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meet2action.backend.trello.HttpTrelloClient;
import com.meet2action.backend.trello.TrelloClient;
import com.meet2action.backend.trello.TrelloProperties;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the Trello client. Missing credentials never prevent startup; they are checked per sync request. */
@Configuration
@EnableConfigurationProperties(TrelloProperties.class)
public class TrelloConfig {

    @Bean
    public TrelloClient trelloClient(TrelloProperties props, ObjectMapper mapper) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                .withReadTimeout(Duration.ofSeconds(props.getReadTimeoutSeconds()));
        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                // Buffering gives every request a Content-Length instead of chunked encoding, which some servers refuse.
                .requestFactory(new BufferingClientHttpRequestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings)))
                .build();
        return new HttpTrelloClient(restClient, props, mapper);
    }
}
