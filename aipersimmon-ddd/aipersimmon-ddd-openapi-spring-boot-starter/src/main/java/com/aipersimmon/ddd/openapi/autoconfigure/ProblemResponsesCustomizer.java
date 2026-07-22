package com.aipersimmon.ddd.openapi.autoconfigure;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.web.error.DefaultProblemFamilies;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

/**
 * Documents the framework's cross-cutting error contract in the generated OpenAPI spec. springdoc
 * documents each {@code @RestController} by reflection, but the {@code application/problem+json}
 * (RFC 9457) responses the shared {@code @RestControllerAdvice} produces are invisible to it — no
 * controller method mentions them. This customizer adds them.
 *
 * <p>It registers a reusable {@code ProblemDetail} schema (shaped after the {@code -web} tier's
 * {@code ApiError}: the five RFC 9457 members plus this library's {@code code}/{@code
 * requestId}/{@code traceId}/{@code errors} extensions) and attaches a <strong>default
 * family</strong> of problem responses — 400/404/429/500 — to every operation. The type URIs and
 * statuses for the family come from {@link DefaultProblemFamilies}, the same source the runtime
 * advice resolves against, so the documented types match what is actually emitted.
 *
 * <p><strong>Per-operation override wins:</strong> a default is added only for a status code the
 * operation does not already declare. A controller that annotates a method with its own
 * {@code @ApiResponse} for, say, 404 keeps that response untouched; the other family members are
 * still filled in.
 */
public class ProblemResponsesCustomizer implements GlobalOpenApiCustomizer {

  static final String PROBLEM_MEDIA_TYPE = "application/problem+json";
  static final String PROBLEM_SCHEMA_NAME = "ProblemDetail";
  static final String FIELD_ERROR_SCHEMA_NAME = "FieldError";

  private static final String PROBLEM_SCHEMA_REF = "#/components/schemas/" + PROBLEM_SCHEMA_NAME;
  private static final String FIELD_ERROR_SCHEMA_REF =
      "#/components/schemas/" + FIELD_ERROR_SCHEMA_NAME;

  /** The default family attached to every operation, in the order shown in the spec. */
  private final List<ProblemResponse> defaults = defaultFamily();

  @Override
  public void customise(OpenAPI openApi) {
    registerSchemas(openApi);
    if (openApi.getPaths() == null) {
      return;
    }
    openApi.getPaths().values().stream()
        .map(PathItem::readOperations)
        .flatMap(List::stream)
        .forEach(this::attachDefaults);
  }

  /** Adds each default response the operation has not declared for itself (override wins). */
  private void attachDefaults(Operation operation) {
    ApiResponses responses = operation.getResponses();
    if (responses == null) {
      responses = new ApiResponses();
      operation.setResponses(responses);
    }
    for (ProblemResponse problem : defaults) {
      if (!responses.containsKey(problem.statusCode())) {
        responses.addApiResponse(problem.statusCode(), problem.toApiResponse());
      }
    }
  }

  /** Registers the reusable {@code ProblemDetail} and {@code FieldError} component schemas. */
  private void registerSchemas(OpenAPI openApi) {
    Components components = openApi.getComponents();
    if (components == null) {
      components = new Components();
      openApi.setComponents(components);
    }
    components.addSchemas(FIELD_ERROR_SCHEMA_NAME, fieldErrorSchema());
    components.addSchemas(PROBLEM_SCHEMA_NAME, problemDetailSchema());
  }

  private static Schema<?> problemDetailSchema() {
    return new ObjectSchema()
        .description(
            "RFC 9457 problem detail (media type application/problem+json). The five standard "
                + "members plus this library's extensions: a machine-readable code, the per-request "
                + "requestId, the distributed traceId (present only when tracing is wired), and "
                + "field-level errors.")
        .addProperty(
            "type",
            new StringSchema()
                .description(
                    "URI reference identifying the problem type (an identifier, not a link)")
                .example("/problems/domain-rule-violation"))
        .addProperty("title", new StringSchema().description("short, stable summary of the type"))
        .addProperty("status", new IntegerSchema().description("HTTP status code").example(422))
        .addProperty(
            "detail", new StringSchema().description("occurrence-specific explanation (nullable)"))
        .addProperty(
            "instance", new StringSchema().description("URI of this occurrence (nullable)"))
        .addProperty(
            "code",
            new StringSchema()
                .description("machine-readable domain error code")
                .example("ordering.credit-exceeded"))
        .addProperty(
            "requestId",
            new StringSchema().description("per-request edge correlation id (nullable)"))
        .addProperty(
            "traceId",
            new StringSchema()
                .description("distributed-trace id; present only when tracing is wired"))
        .addProperty(
            "errors",
            new ArraySchema()
                .description("field-level validation problems; empty when none")
                .items(new Schema<>().$ref(FIELD_ERROR_SCHEMA_REF)));
  }

  private static Schema<?> fieldErrorSchema() {
    return new ObjectSchema()
        .description("One field-level validation problem carried in ProblemDetail.errors")
        .addProperty(
            "field",
            new StringSchema()
                .description("the offending field (a name or JSON pointer)")
                .example("/lines/0/quantity"))
        .addProperty(
            "code",
            new StringSchema().description("machine-readable reason").example("out-of-range"))
        .addProperty("message", new StringSchema().description("human-readable explanation"));
  }

  /**
   * The default problem family. The family types and statuses come from {@link
   * DefaultProblemFamilies} (the same catalogue the runtime advice resolves against); 429 is a
   * web-tier concern (rate limiting) rather than a domain category, so it is stated here directly.
   */
  private static List<ProblemResponse> defaultFamily() {
    var families = DefaultProblemFamilies.DEFAULTS;
    return List.of(
        ProblemResponse.of(
            families.get(ErrorCategory.VALIDATION),
            "Request validation failed; the body lists field-level errors."),
        ProblemResponse.of(
            families.get(ErrorCategory.NOT_FOUND), "The referenced resource was not found."),
        new ProblemResponse(
            "429",
            "/problems/rate-limited",
            "Rate limit exceeded; retry after the interval in the Retry-After header."),
        ProblemResponse.of(families.get(ErrorCategory.UNEXPECTED), "Unexpected server error."));
  }

  /** One default problem response: the HTTP status, its problem-type URI, and a description. */
  private record ProblemResponse(String statusCode, String typeUri, String description) {

    static ProblemResponse of(ProblemDescriptor descriptor, String description) {
      return new ProblemResponse(
          String.valueOf(descriptor.status()), descriptor.typeUri(), description);
    }

    ApiResponse toApiResponse() {
      return new ApiResponse()
          .description(description + " Problem type: " + typeUri + ".")
          .content(
              new Content()
                  .addMediaType(
                      PROBLEM_MEDIA_TYPE,
                      new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA_REF))));
    }
  }
}
