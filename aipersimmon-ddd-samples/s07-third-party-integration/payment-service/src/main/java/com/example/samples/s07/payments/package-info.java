/**
 * The payments context: it owns the record of what we asked a provider to do and what came back.
 *
 * <p>It does not own the money, which is the whole difficulty. Every state in here is a belief about
 * something that happened somewhere else, and the sample is about keeping that belief honest — including
 * the state that says we do not know.
 */
package com.example.samples.s07.payments;
