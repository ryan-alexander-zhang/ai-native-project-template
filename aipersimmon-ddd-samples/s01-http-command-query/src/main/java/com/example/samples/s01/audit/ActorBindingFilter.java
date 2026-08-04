package com.example.samples.s01.audit;

import com.aipersimmon.ddd.operationlog.model.Actor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the actor for the duration of one HTTP request, and unbinds it whatever happens.
 *
 * <p><strong>Where the identity comes from is a stand-in, and the sample says so rather than pretending
 * otherwise.</strong> This filter reads a header. A header is client-supplied, so on its own it is the
 * opposite of a trusted boundary — anyone could claim to be anyone. In a real service this filter is
 * Spring Security's context: the identity has been authenticated before anything reads it, and the
 * filter's job is only to copy it from the security context into a place the resolver can reach.
 *
 * <p>What the stand-in does <em>not</em> compromise is the property that matters here: the actor is
 * established at the boundary and read from a scope, never taken from the command. Swap the header for
 * an authenticated principal and nothing else in this sample changes — which is the point of putting
 * the seam here.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} + 20, i.e. early: anything that records an operation
 * must run inside the binding, and a filter that authenticated the caller would come before this one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ActorBindingFilter extends OncePerRequestFilter {

  /** Stands in for an authenticated principal. See the class javadoc. */
  public static final String ACTOR_HEADER = "X-Actor";

  public static final String ACTOR_NAME_HEADER = "X-Actor-Name";

  @Override
  public void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String actorId = request.getHeader(ACTOR_HEADER);
    if (actorId != null && !actorId.isBlank()) {
      CurrentActor.bind(Actor.user(actorId, request.getHeader(ACTOR_NAME_HEADER)));
    }
    try {
      chain.doFilter(request, response);
    } finally {
      // The load-bearing line of this class. Without it the binding outlives the request on a pooled
      // thread, and the next background task on that thread files its operations under a user who has
      // gone home. See CurrentActor's javadoc and ActorResolutionTest.
      CurrentActor.clear();
    }
  }
}
