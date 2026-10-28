package com.cdata.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;

/**
 * Checks every HTTP request before the MCP transport handles it:
 * <ul>
 * <li>the Origin header, which browsers send: a web page from elsewhere can't use the browser
 * to reach a server listening on this machine (DNS rebinding);</li>
 * <li>when a token is configured, the "Authorization: Bearer &lt;token&gt;" header.</li>
 * </ul>
 */
public class HttpAccessValidator implements ServerHttpHeaderValidator {
  private static final String BEARER = "Bearer ";

  private final ServerHttpHeaderValidator origins;
  private final byte[] token;

  public HttpAccessValidator(List<String> allowedOrigins, String token) {
    this.origins = DefaultServerTransportSecurityValidator.builder().allowedOrigins(allowedOrigins).build();
    this.token = token == null ? null : token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void validate(HeaderAccessor headers) throws ServerTransportSecurityException {
    this.origins.validate(headers);
    if (this.token != null && !MessageDigest.isEqual(bearerToken(headers), this.token)) {
      // MessageDigest.isEqual takes the same time wherever the values differ, so it doesn't leak the token
      throw new ServerTransportSecurityException(401, "Missing or invalid bearer token");
    }
  }

  private static byte[] bearerToken(HeaderAccessor headers) {
    List<String> values = headers.getHeader("Authorization");
    if (values == null || values.isEmpty()) {
      return new byte[0];
    }
    String value = values.get(0).trim();
    // the scheme name is case-insensitive
    if (!value.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return new byte[0];
    }
    return value.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
  }
}
