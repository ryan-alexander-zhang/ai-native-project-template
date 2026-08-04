package com.example.samples.s10.points.domain;

import java.util.Optional;

/**
 * The points aggregate's port.
 *
 * <p>{@code find} takes the reference as well as the identity, because every operation here is about one
 * reference and the aggregate is loaded to decide about that one. See {@link PointsAccount#entry()}.
 */
public interface PointsAccounts {

  Optional<PointsAccount> find(PointsAccountId id, String reference);

  void save(PointsAccount account);
}
