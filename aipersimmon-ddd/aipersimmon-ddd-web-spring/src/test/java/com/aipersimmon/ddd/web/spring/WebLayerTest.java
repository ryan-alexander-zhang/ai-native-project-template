package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import com.aipersimmon.ddd.web.error.ApiException;
import com.aipersimmon.ddd.web.error.FieldError;
import com.aipersimmon.ddd.web.error.ProblemType;
import com.aipersimmon.ddd.web.error.ProblemTypeCatalog;
import com.aipersimmon.ddd.web.page.Cursor;
import com.aipersimmon.ddd.web.page.Slice;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the web starter end to end through MockMvc: the advice renders each
 * exception family to an RFC 9457 problem body with the corrected status semantics
 * (business rule → 422, state conflict → 409, not-found → 404, bean validation → 400),
 * a domain error code resolves through the registry into the full wire form, and a
 * Slice serialises with its cursor as an opaque string.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebLayerTest {

    private final MockMvc mvc;

    WebLayerTest(@org.springframework.beans.factory.annotation.Autowired MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void apiExceptionRendersCatalogueFields() throws Exception {
        mvc.perform(post("/test/api-exception"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("/problems/credit-exceeded"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"))
                .andExpect(jsonPath("$.title").value(notNullValue()))
                .andExpect(jsonPath("$.traceId").value(notNullValue()))
                .andExpect(jsonPath("$.errors[0].field").value("/lines/0/qty"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void codedDomainExceptionResolvesThroughRegistry() throws Exception {
        mvc.perform(post("/test/domain-coded"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("/problems/credit-exceeded"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"));
    }

    @Test
    void uncodedDomainExceptionDefaultsToUnprocessable() throws Exception {
        mvc.perform(post("/test/domain"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void illegalStateTransitionMapsToConflict() throws Exception {
        mvc.perform(post("/test/illegal-transition"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void applicationExceptionMapsToUnprocessable() throws Exception {
        mvc.perform(post("/test/app"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void entityNotFoundMapsTo404() throws Exception {
        mvc.perform(post("/test/entity-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void concurrencyConflictMapsTo409() throws Exception {
        mvc.perform(post("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void beanValidationBodyMapsToBadRequestWithFieldErrors() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void constraintViolationMapsToBadRequest() throws Exception {
        mvc.perform(post("/test/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void noSuchElementMapsTo404() throws Exception {
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
        @Override public int status() { return 422; }
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

        @PostMapping("/test/domain-coded")
        String domainCoded() {
            throw new DomainException(OrderProblem.CREDIT_EXCEEDED, "over limit");
        }

        @PostMapping("/test/domain")
        String domain() {
            throw new DomainException("business rule violated");
        }

        @PostMapping("/test/illegal-transition")
        String illegalTransition() {
            throw new IllegalStateTransitionException("PENDING", "SHIPPED");
        }

        @PostMapping("/test/app")
        String app() {
            throw new ApplicationException("use-case failed");
        }

        @PostMapping("/test/entity-not-found")
        String entityNotFound() {
            throw new EntityNotFoundException("unknown order");
        }

        @PostMapping("/test/conflict")
        String conflict() {
            throw new ConcurrencyConflictException("stale write");
        }

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody CreateReq req) {
            return req.name();
        }

        @PostMapping("/test/constraint")
        String constraint() {
            throw new ConstraintViolationException("invalid", Set.of());
        }

        @GetMapping("/test/not-found")
        String notFound() {
            throw new NoSuchElementException("nope");
        }

        @GetMapping("/test/slice")
        Slice<String> slice() {
            return new Slice<>(List.of("a"), Cursor.of("abc"));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.context.annotation.Import(TestController.class)
    static class App {

        @Bean
        ProblemTypeCatalog orderingCatalog() {
            return () -> List.of(OrderProblem.values());
        }
    }
}
