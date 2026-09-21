package insurance.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic JSON: sorted keys, indented, trailing newline, unknown fields rejected. */
public final class Json {
  public static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private Json() {}

  public static byte[] bytes(Object value) {
    try {
      return (MAPPER.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static <T> T read(Path path, Class<T> type) {
    try {
      return MAPPER.readValue(Files.readAllBytes(path), type);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static <T> T read(byte[] raw, Class<T> type) {
    try {
      return MAPPER.readValue(raw, type);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
