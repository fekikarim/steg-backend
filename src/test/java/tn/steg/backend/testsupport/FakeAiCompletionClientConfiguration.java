package tn.steg.backend.testsupport;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tn.steg.backend.ai.domain.client.AiCompletionClient;

/**
 * Scripted fake Gemini client shared by the T09 journal tests: no live provider
 * call ever happens (the suite is reproducible), and because the four test
 * classes import the SAME configuration they share one cached Spring context
 * instead of booting Testcontainers four times.
 */
@TestConfiguration
public class FakeAiCompletionClientConfiguration {

    @Bean
    @Primary
    public AiCompletionClient aiCompletionClient() {
        return Mockito.mock(AiCompletionClient.class);
    }
}
