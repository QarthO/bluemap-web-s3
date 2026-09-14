package gg.qdev.bluemap.s3;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/** The small subset of S3 needed by BlueMap. No SDK, NIO emulation, or native code. */
final class S3Client {
  static final int MAX_OBJECT_BYTES = 64 * 1024 * 1024;
  private final URI base;
  private final String region, accessKey, secretKey, sessionToken;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NEVER)
          .version(HttpClient.Version.HTTP_1_1)
          .build();

  S3Client(
      String endpoint,
      String bucket,
      String region,
      String accessKey,
      String secretKey,
      String sessionToken,
      boolean pathStyle) {
    URI uri = URI.create(endpoint);
    if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
      throw new IllegalArgumentException(
          "endpoint-url must be an HTTP(S) origin without credentials, path, query or fragment");
    if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))
      throw new IllegalArgumentException("Invalid bucket-name");
    String authority = uri.getRawAuthority();
    base =
        URI.create(
            uri.getScheme()
                + "://"
                + (pathStyle ? authority + "/" + bucket : bucket + "." + authority)
                + "/");
    this.region = region;
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.sessionToken = sessionToken;
  }

  record Reply(int status, byte[] body) {}

  Reply request(
      String method,
      String key,
      Map<String, String> query,
      byte[] body,
      Map<String, String> headers)
      throws IOException {
    String qs = canonicalQuery(query);
    URI uri = URI.create(base + encode(key, true) + (qs.isEmpty() ? "" : "?" + qs));
    IOException failure = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      HttpResponse<byte[]> response = null;
      try {
        var signed =
            sign(
                method,
                uri,
                body,
                headers,
                region,
                accessKey,
                secretKey,
                sessionToken,
                Instant.now());
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60));
        signed.forEach(
            (k, v) -> {
              if (!k.equals("host")) builder.header(k, v);
            });
        response =
            http.send(
                builder
                    .method(
                        method,
                        body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                info -> new LimitedBody());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("S3 request interrupted", e);
      } catch (IOException e) {
        failure = e;
      }
      if (response != null) {
        int status = response.statusCode();
        if (status >= 200 && status < 300 || status == 404)
          return new Reply(status, response.body());
        // Do not log signed headers, credentials, or provider response bodies.
        failure = new IOException("S3 " + method + " failed: HTTP " + status);
        if (status != 429 && status != 500 && status != 502 && status != 503 && status != 504)
          throw failure;
      }
      if (attempt < 2)
        try {
          Thread.sleep(250L << attempt);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("S3 retry interrupted", e);
        }
    }
    throw failure;
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long size;

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      delegate.onSubscribe(s);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) size += buffer.remaining();
      if (size > MAX_OBJECT_BYTES) {
        subscription.cancel();
        delegate.onError(new IOException("S3 response exceeds 64 MiB"));
      } else delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable t) {
      delegate.onError(t);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }
  }

  byte[] get(String key) throws IOException {
    var r = request("GET", key, Map.of(), new byte[0], Map.of());
    return r.status == 404 ? null : r.body;
  }

  boolean exists(String key) throws IOException {
    return request("HEAD", key, Map.of(), new byte[0], Map.of()).status != 404;
  }

  void put(String key, byte[] data, String type, String cache) throws IOException {
    if (data.length > MAX_OBJECT_BYTES) throw new IOException("S3 object exceeds 64 MiB");
    var r =
        request("PUT", key, Map.of(), data, Map.of("content-type", type, "cache-control", cache));
    if (r.status == 404) throw new IOException("S3 bucket does not exist");
  }

  void delete(String key) throws IOException {
    request("DELETE", key, Map.of(), new byte[0], Map.of());
  }

  record Page(List<String> keys, String next) {}

  Page page(String prefix, String token) throws IOException {
    var query = new HashMap<String, String>();
    query.put("list-type", "2");
    query.put("prefix", prefix);
    query.put("encoding-type", "url");
    if (token != null) query.put("continuation-token", token);
    var reply = request("GET", "", query, new byte[0], Map.of());
    if (reply.status == 404) throw new IOException("S3 bucket does not exist");
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(reply.body));
      var nodes = doc.getElementsByTagName("Key");
      List<String> keys = new ArrayList<>();
      for (int i = 0; i < nodes.getLength(); i++)
        keys.add(
            URLDecoder.decode(
                nodes.item(i).getTextContent().replace("+", "%2B"), StandardCharsets.UTF_8));
      boolean more = "true".equals(value(doc, "IsTruncated"));
      String next = more ? value(doc, "NextContinuationToken") : null;
      if (more && (next == null || next.equals(token)))
        throw new IOException("Invalid S3 continuation token");
      return new Page(keys, next);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Invalid S3 listing XML", e);
    }
  }

  private static String value(Document doc, String name) {
    var nodes = doc.getElementsByTagName(name);
    return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
  }

  Stream<String> list(String prefix) throws IOException {
    Page first = page(prefix, null);
    Iterator<String> iterator =
        new Iterator<>() {
          Page current = first;
          Iterator<String> keys = current.keys.iterator();

          public boolean hasNext() {
            while (!keys.hasNext() && current.next != null) {
              try {
                current = page(prefix, current.next);
                keys = current.keys.iterator();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }
            return keys.hasNext();
          }

          public String next() {
            if (!hasNext()) throw new NoSuchElementException();
            return keys.next();
          }
        };
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
        false);
  }

  static Map<String, String> sign(
      String method,
      URI uri,
      byte[] body,
      Map<String, String> extra,
      String region,
      String access,
      String secret,
      String token,
      Instant now) {
    String date =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
    String day = date.substring(0, 8), hash = hash(body);
    var headers = new TreeMap<String, String>();
    extra.forEach(
        (k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.trim().replaceAll("\\s+", " ")));
    headers.put("host", uri.getRawAuthority());
    headers.put("x-amz-date", date);
    headers.put("x-amz-content-sha256", hash);
    if (!token.isEmpty()) headers.put("x-amz-security-token", token);
    String names = String.join(";", headers.keySet());
    StringBuilder canonicalHeaders = new StringBuilder();
    headers.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
    String canonical =
        method
            + "\n"
            + uri.getRawPath()
            + "\n"
            + Objects.toString(uri.getRawQuery(), "")
            + "\n"
            + canonicalHeaders
            + "\n"
            + names
            + "\n"
            + hash;
    String scope = day + "/" + region + "/s3/aws4_request";
    String toSign =
        "AWS4-HMAC-SHA256\n"
            + date
            + "\n"
            + scope
            + "\n"
            + hash(canonical.getBytes(StandardCharsets.UTF_8));
    byte[] key = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), day);
    key = hmac(hmac(hmac(key, region), "s3"), "aws4_request");
    headers.put(
        "authorization",
        "AWS4-HMAC-SHA256 Credential="
            + access
            + "/"
            + scope
            + ", SignedHeaders="
            + names
            + ", Signature="
            + HexFormat.of().formatHex(hmac(key, toSign)));
    return headers;
  }

  static String canonicalQuery(Map<String, String> query) {
    return query.entrySet().stream()
        .map(e -> encode(e.getKey(), false) + "=" + encode(e.getValue(), false))
        .sorted()
        .collect(Collectors.joining("&"));
  }

  static String encode(String value, boolean slash) {
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 255;
      if (c >= 'a' && c <= 'z'
          || c >= 'A' && c <= 'Z'
          || c >= '0' && c <= '9'
          || "-_.~".indexOf(c) >= 0
          || slash && c == '/') out.append((char) c);
      else
        out.append('%')
            .append("0123456789ABCDEF".charAt(c >> 4))
            .append("0123456789ABCDEF".charAt(c & 15));
    }
    return out.toString();
  }

  static String hash(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
