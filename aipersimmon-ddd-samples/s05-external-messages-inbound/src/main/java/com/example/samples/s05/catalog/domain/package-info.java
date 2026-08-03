/**
 * The catalog context's write model — this context's language, with nothing of the ERP's in it.
 *
 * <p>The one concession to being a mirror is {@code upstreamRevision}, and it is a genuine domain fact
 * rather than a leak: "which version of the upstream truth do I hold" is what makes late and duplicate
 * messages answerable by the aggregate that owns the data.
 */
package com.example.samples.s05.catalog.domain;
