package io.github.benhendayoussef.idempotency.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.benhendayoussef.idempotency.api.Idempotent;
import io.github.benhendayoussef.idempotency.api.IdempotencyObjectMapperCustomizer;
import io.github.benhendayoussef.idempotency.api.IdempotencyStore;
import io.github.benhendayoussef.idempotency.internal.IdempotencyKeyComposer;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An application-registered custom Jackson (de)serializer, wired through the documented
 * {@link IdempotencyObjectMapperCustomizer} extension point, must actually take effect on the
 * starter's internal payload mapper (used for storage/replay reconstruction) - not just on the
 * application's own {@code ObjectMapper} (which the aspect deliberately never reuses, and which is
 * what actually writes the HTTP response body on both a first call and a replay).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {CustomJacksonModuleTest.TestApp.class, CustomJacksonModuleTest.PointController.class,
                CustomJacksonModuleTest.PointModuleConfiguration.class},
        properties = {"idempotency.store=memory", "idempotency.scope=global",
                TestAutoconfigExcludes.EXCLUDE_DATASOURCE_AND_SECURITY})
@AutoConfigureMockMvc
class CustomJacksonModuleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private IdempotencyKeyComposer composer;

    @Test
    void customSerializerRegisteredViaTheCustomizerHookRoundTripsThroughReplay() throws Exception {
        // The HTTP response shape is always whatever the *application's own* HttpMessageConverter
        // produces (the default record shape {"x":3,"y":4} here), on both the first call and a
        // replay - the customizer only affects the starter's *internal* payload mapper used for
        // storage. So the meaningful assertion is on the stored payload, not the wire response.
        String key = "point-key";
        String first = mockMvc.perform(post("/point").header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(first).isEqualTo("{\"x\":3,\"y\":4}");

        var fakeRequest = new org.springframework.mock.web.MockHttpServletRequest("POST", "/point");
        fakeRequest.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/point");
        String storageKey = composer.compose(key, "", fakeRequest);

        // Proves the custom serializer actually ran on the starter's internal mapper: the plain
        // string "3,4" it writes, not Jackson's default {"x":3,"y":4} object shape for a record.
        assertThat(store.find(storageKey)).isPresent().get()
                .extracting(io.github.benhendayoussef.idempotency.api.IdempotencyRecord::payload)
                .isEqualTo("\"3,4\"");

        // And the custom deserializer must correctly reconstruct it for the replay to work at all.
        String second = mockMvc.perform(post("/point").header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
    }

    record Point(int x, int y) {
    }

    static class PointSerializer extends JsonSerializer<Point> {
        @Override
        public void serialize(Point value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.x() + "," + value.y());
        }
    }

    static class PointDeserializer extends JsonDeserializer<Point> {
        @Override
        public Point deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String[] parts = p.getValueAsString().split(",");
            return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class PointModuleConfiguration {
        @Bean
        IdempotencyObjectMapperCustomizer pointModuleCustomizer() {
            return mapper -> {
                var module = new SimpleModule();
                module.addSerializer(Point.class, new PointSerializer());
                module.addDeserializer(Point.class, new PointDeserializer());
                mapper.registerModule(module);
            };
        }
    }

    @RestController
    static class PointController {
        @Idempotent
        @PostMapping("/point")
        ResponseEntity<Point> point() {
            return ResponseEntity.status(HttpStatus.CREATED).body(new Point(3, 4));
        }
    }
}
