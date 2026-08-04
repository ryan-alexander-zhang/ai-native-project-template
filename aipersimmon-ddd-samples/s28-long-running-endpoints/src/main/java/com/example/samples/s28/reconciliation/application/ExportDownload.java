package com.example.samples.s28.reconciliation.application;

import java.io.InputStream;

/**
 * An open artifact, on its way to an HTTP response.
 *
 * <p>Carrying the stream rather than the path is what keeps the storage arrangement out of the controller: the
 * edge learns how many bytes to declare and what to call the file, and nothing about where it came from. Swap the
 * store for object storage and this record does not change.
 *
 * <p>The caller closes {@link #stream()}.
 *
 * @param filename what to offer it as
 * @param bytes how long it is, so a client gets a progress bar
 * @param stream the bytes
 */
public record ExportDownload(String filename, long bytes, InputStream stream) {}
