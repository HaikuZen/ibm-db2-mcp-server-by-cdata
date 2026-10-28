package com.cdata.mcp.tests;

import com.cdata.mcp.HttpAccessValidator;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HttpAccessValidatorTests {
  private static final List<String> LOCAL = List.of("http://localhost:*");

  private static HeaderAccessor headers(Map<String, String> values) {
    return new HeaderAccessor() {
      @Override
      public List<String> getHeader(String name) {
        for (Map.Entry<String, String> e : values.entrySet()) {
          if (e.getKey().equalsIgnoreCase(name)) {
            return List.of(e.getValue());
          }
        }
        return List.of();
      }

      @Override
      public List<String> getHeaderNames() {
        return new ArrayList<>(values.keySet());
      }
    };
  }

  private static int status(HttpAccessValidator validator, Map<String, String> values) {
    try {
      validator.validate(headers(values));
      return 200;
    } catch (ServerTransportSecurityException ex) {
      return ex.getStatusCode();
    }
  }

  @Test
  public void withoutTokenOnlyOriginIsChecked() {
    HttpAccessValidator v = new HttpAccessValidator(LOCAL, null);
    Assert.assertEquals(200, status(v, Map.of()));
    Assert.assertEquals(200, status(v, Map.of("Origin", "http://localhost:6274")));
    Assert.assertEquals(403, status(v, Map.of("Origin", "http://evil.example")));
  }

  @Test
  public void tokenIsRequiredWhenConfigured() {
    HttpAccessValidator v = new HttpAccessValidator(LOCAL, "s3cret");
    Assert.assertEquals(401, status(v, Map.of()));
    Assert.assertEquals(401, status(v, Map.of("Authorization", "Bearer wrong")));
    Assert.assertEquals(401, status(v, Map.of("Authorization", "Basic s3cret")));
    Assert.assertEquals(401, status(v, Map.of("Authorization", "Bearer s3cret2")));
    Assert.assertEquals(200, status(v, Map.of("Authorization", "Bearer s3cret")));
    Assert.assertEquals(200, status(v, Map.of("authorization", "bearer  s3cret ")));
  }

  @Test
  public void badOriginIsRejectedEvenWithToken() {
    HttpAccessValidator v = new HttpAccessValidator(LOCAL, "s3cret");
    Assert.assertEquals(403, status(v, Map.of("Authorization", "Bearer s3cret", "Origin", "http://evil.example")));
  }
}
