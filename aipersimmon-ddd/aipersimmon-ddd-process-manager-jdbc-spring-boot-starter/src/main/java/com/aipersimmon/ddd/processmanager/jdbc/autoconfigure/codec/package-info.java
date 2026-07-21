/**
 * Optional Jackson codec convenience layer. When an {@code ObjectMapper} and a consumer-declared
 * {@link com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec.ProcessSerializationCatalog}
 * are both present, {@link
 * com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec.JacksonProcessCodecConfiguration}
 * generates JSON payload and state codecs from the catalog's explicit registrations and merges them
 * with any explicitly-declared codec beans. No classpath scan, no class-name fallback.
 */
package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure.codec;
