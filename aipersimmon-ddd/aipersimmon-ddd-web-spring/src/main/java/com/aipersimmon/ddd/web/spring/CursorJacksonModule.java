package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.web.page.Cursor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;

/**
 * Serializes a {@link Cursor} as its opaque string value (not a nested object), so a {@code
 * Slice}/{@code Page} renders as {@code {"items":[...],"nextCursor":"..."}} with the cursor a plain
 * token. Registered as a Jackson {@code Module} bean, which Boot's object-mapper builder picks up
 * automatically.
 */
public class CursorJacksonModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  public CursorJacksonModule() {
    super("aipersimmon-ddd-web-cursor");
    addSerializer(
        Cursor.class,
        new JsonSerializer<>() {
          @Override
          public void serialize(Cursor value, JsonGenerator gen, SerializerProvider serializers)
              throws IOException {
            gen.writeString(value.value());
          }
        });
    addDeserializer(
        Cursor.class,
        new JsonDeserializer<>() {
          @Override
          public Cursor deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = p.getValueAsString();
            return value == null || value.isBlank() ? null : Cursor.of(value);
          }
        });
  }
}
