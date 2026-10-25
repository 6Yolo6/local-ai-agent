package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MovieSearchToolsWiringTest {
    @Test void springCreatesSearchComponentWithTheProductionConstructor() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(MovieSearchTools.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MovieSearchTools.class);
                    assertThat(context.getBean(MovieSearchTools.class).search("").path("error").path("code").asText())
                            .isEqualTo("INVALID_ARGUMENTS");
                });
    }
}
