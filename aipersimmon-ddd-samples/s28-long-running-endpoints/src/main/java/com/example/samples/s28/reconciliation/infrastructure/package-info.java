/**
 * Persistence and files.
 *
 * <p>Three things in here are not the ordinary mapper-and-row arrangement, and each is deliberate:
 *
 * <ul>
 *   <li>{@code ExportJobMapper} carries hand-written claim SQL over the same table an aggregate repository writes
 *       — the only place in the samples where those two coexist, and the {@code version = version + 1} in the
 *       claim is what makes it safe;
 *   <li>{@code ProgressMapper} has no row class and no {@code BaseMapper}, because progress is not an entity;
 *   <li>{@code FileArtifactStore} writes to the local filesystem, which is honest for one instance and stated to
 *       be wrong for two.
 * </ul>
 */
package com.example.samples.s28.reconciliation.infrastructure;
