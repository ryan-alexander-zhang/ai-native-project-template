package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.web.error.ApiException;
import com.aipersimmon.ddd.web.error.FieldError;
import com.aipersimmon.ddd.web.error.ProblemType;
import com.aipersimmon.ddd.web.page.Cursor;
import com.aipersimmon.ddd.web.page.Slice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the web starter end to end through MockMvc: the advice renders each
 * exception family to an RFC 9457 problem body with the right status and extension
 * members, the trace-id filter echoes a generated id, and a Slice serializes with
 * its cursor as an opaque string rather than a nested object.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebLayerTest {

    private final MockMvc mvc;

    WebLayerTest(@org.springframework.beans.factory.annotation.Autowired MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void apiExceptionRendersProblemDetailWithCatalogueFields() throws Exception {
        mvc.perform(post("/test/api-exception"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/problems/credit-exceeded"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"))
                .andExpect(jsonPath("$.title").value(notNullValue()))
                .andExpect(jsonPath("$.traceId").value(notNullValue()))
                .andExpect(jsonPath("$.errors[0].field").value("/lines/0/qty"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void domainExceptionMapsToConflict() throws Exception {
        mvc.perform(post("/test/domain"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void applicationExceptionMapsToUnprocessableEntity() throws Exception {
        mvc.perform(post("/test/app"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void validationFailureMapsToBadRequestWithFieldErrors() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void notFoundMapsTo404() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sliceSerializesCursorAsOpaqueString() throws Exception {
        mvc.perform(get("/test/slice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("a"))
                .andExpect(jsonPath("$.nextCursor").value("abc"));
    }

    enum OrderProblem implements ProblemType {
        CREDIT_EXCEEDED;

        @Override public String code() { return "ordering.credit-exceeded"; }
        @Override public String typeUri() { return "/problems/credit-exceeded"; }
        @Override public int status() { return 409; }
        @Override public String titleKey() { return "ordering.credit-exceeded.title"; }
    }

    record CreateReq(@NotBlank String name) {
    }

    @RestController
    static class TestController {

        @PostMapping("/test/api-exception")
        String apiException() {
            throw new ApiException(OrderProblem.CREDIT_EXCEEDED, "over limit",
                    List.of(new FieldError("/lines/0/qty", "out-of-range", "must be positive")));
        }

        @PostMapping("/test/domain")
        String domain() {
            throw new DomainException("duplicate order");
        }

        @PostMapping("/test/app")
        String app() {
            throw new ApplicationException("order not found");
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody CreateReq req) {
            return req.name();
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/not-found")
        String notFound() {
            throw new NoSuchElementException("nope");
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/slice")
        Slice<String> slice() {
            return new Slice<>(List.of("a"), Cursor.of("abc"));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.context.annotation.Import(TestController.class)
    static class App {
    }
}
