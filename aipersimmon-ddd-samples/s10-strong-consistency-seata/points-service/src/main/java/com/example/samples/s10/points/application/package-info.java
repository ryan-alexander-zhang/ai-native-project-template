/**
 * The participant's four operations: one for AT, three for TCC.
 *
 * <p>The count is the comparison. AT needs one handler because the framework supplies the undo; TCC needs
 * three because the model supplies it. Neither set mentions Seata — the protocol is chosen at the edge
 * (which endpoint the caller calls), not in here.
 */
package com.example.samples.s10.points.application;
