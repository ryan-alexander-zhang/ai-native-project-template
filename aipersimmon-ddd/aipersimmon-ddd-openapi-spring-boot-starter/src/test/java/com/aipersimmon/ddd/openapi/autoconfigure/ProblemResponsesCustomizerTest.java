package com.aipersimmon.ddd.openapi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class ProblemResponsesCustomizerTest {

  private final ProblemResponsesCustomizer customizer = new ProblemResponsesCustomizer();

  @Test
  void attachesTheDefaultProblemFamilyToEveryOperation() {
    Operation get = new Operation();
    OpenAPI api = new OpenAPI().paths(new Paths().addPathItem("/orders", new PathItem().get(get)));

    customizer.customise(api);

    assertThat(get.getResponses()).containsKeys("400", "404", "429", "500");
    ApiResponse notFound = get.getResponses().get("404");
    assertThat(notFound.getContent()).containsKey("application/problem+json");
    assertThat(notFound.getContent().get("application/problem+json").getSchema().get$ref())
        .isEqualTo("#/components/schemas/ProblemDetail");
  }

  @Test
  void registersTheReusableProblemAndFieldErrorSchemas() {
    OpenAPI api =
        new OpenAPI()
            .paths(new Paths().addPathItem("/orders", new PathItem().get(new Operation())));

    customizer.customise(api);

    assertThat(api.getComponents().getSchemas()).containsKeys("ProblemDetail", "FieldError");
    assertThat(api.getComponents().getSchemas().get("ProblemDetail").getProperties())
        .containsKeys("type", "title", "status", "code", "requestId", "traceId", "errors");
  }

  @Test
  void doesNotOverrideAnOperationsOwnResponseForTheSameStatus() {
    Operation get = new Operation();
    ApiResponse own404 = new ApiResponse().description("Order not found");
    get.setResponses(new ApiResponses().addApiResponse("404", own404));
    OpenAPI api =
        new OpenAPI().paths(new Paths().addPathItem("/orders/{id}", new PathItem().get(get)));

    customizer.customise(api);

    // The declared 404 is untouched; the rest of the family is still filled in.
    assertThat(get.getResponses().get("404")).isSameAs(own404);
    assertThat(get.getResponses()).containsKeys("400", "429", "500");
  }

  @Test
  void toleratesAnApiWithNoPaths() {
    OpenAPI api = new OpenAPI();

    customizer.customise(api);

    // Schemas are still registered so a hand-authored spec can reference them.
    assertThat(api.getComponents().getSchemas()).containsKeys("ProblemDetail", "FieldError");
  }
}
