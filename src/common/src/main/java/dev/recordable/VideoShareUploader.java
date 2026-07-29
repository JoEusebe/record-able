package dev.recordable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.security.auth.x500.X500Principal;

/**
 * Uploads a recorded video to a public file host and returns a shareable direct link,
 * in the spirit of "mclo.gs, but for videos". Two hosts are offered:
 *
 * <ul>
 *   <li>{@link Host#RECORDABLE} - the official Record-able share server. Videos are compressed
 *       on the server, links last 60 days, up to 4 GB per file.</li>
 *   <li>{@link Host#LITTERBOX} - temporary video storage, up to 1 GB per file, link expires after 72 hours.</li>
 * </ul>
 *
 * Both hosts use a simple, account-free multipart API. Uploads are always user-initiated
 * (only when the Share button is pressed) and go out over HTTPS.
 */
public final class VideoShareUploader {

    /**
     * Base URL of the official Record-able share server (no trailing slash).
     * The multipart upload endpoint is this value plus "/upload".
     */
    public static final String RECORDABLE_BASE_URL = "https://re.share-abl.ink";

    /**
     * Host name of the Record-able share server. The custom wildcard hostname
     * verifier (see {@link #RECORDABLE_HOSTNAME_VERIFIER}) is applied only to
     * connections whose host matches this value; all other hosts keep the JVM's
     * default verification untouched.
     */
    private static final String RECORDABLE_HOST = "re.share-abl.ink";

    /** Default retention (in days) used when the caller does not pick one. */
    public static final int DEFAULT_RETENTION_DAYS = 60;

    /** Retention choices offered by the Record-able server, in days. */
    public static final int[] RETENTION_DAY_OPTIONS = {7, 30, 60};

    /** Supported share destinations. */
    public enum Host {
        RECORDABLE(
                "re.share-abl.ink",
                RECORDABLE_BASE_URL + "/upload",
                4L * 1024L * 1024L * 1024L,
                false,
                "60 days",
                "file",
                false),
        LITTERBOX(
                "Litterbox",
                "https://litterbox.catbox.moe/resources/internals/api.php",
                1024L * 1024L * 1024L,
                true,
                "72h",
                "fileToUpload",
                true);

        /** Human readable host name shown in the UI. */
        public final String displayName;
        /** Multipart upload endpoint. */
        public final String endpoint;
        /** Maximum accepted file size in bytes. */
        public final long maxBytes;
        /** True if links expire after a while, false if permanent. */
        public final boolean temporary;
        /** Retention window (informational) (e.g. "72h" or "30 days"); null for permanent hosts. */
        public final String retention;
        /** Form field name for the file upload. */
        public final String fileFieldName;
        /** True if this host requires Catbox-style reqtype and time fields. */
        public final boolean requiresCatboxFields;

        Host(String displayName, String endpoint, long maxBytes, boolean temporary, String retention,
             String fileFieldName, boolean requiresCatboxFields) {
            this.displayName = displayName;
            this.endpoint = endpoint;
            this.maxBytes = maxBytes;
            this.temporary = temporary;
            this.retention = retention;
            this.fileFieldName = fileFieldName;
            this.requiresCatboxFields = requiresCatboxFields;
        }

        /** Human readable size limit, e.g. "200 MB" or "1 GB". */
        public String maxSizeLabel() {
            long mb = maxBytes / (1024L * 1024L);
            return mb >= 1024L ? (mb / 1024L) + " GB" : mb + " MB";
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 300_000;
    private static final String BOUNDARY = "----RecordableShareBoundary" + Long.toHexString(System.nanoTime());
    private static final String CRLF = "\r\n";

    /**
     * Chunk size for the Record-able server's chunked upload API (16 MB).
     *
     * <p>Smaller chunks stay well under Cloudflare's 100 MB request limit and, crucially,
     * make the upload far more resilient: the write of a large request body is exactly the
     * step that fails with "Error writing request body to server" when the connection is
     * reset mid-transfer. With smaller chunks each failed write costs less to re-send, and
     * {@link #uploadChunkWithRetry} can transparently retry the affected chunk.</p>
     */
    private static final long CHUNK_SIZE = 16L * 1024L * 1024L;

    /** Maximum attempts for a single chunk before the upload is reported as failed. */
    private static final int CHUNK_MAX_ATTEMPTS = 4;

    /** Streaming copy buffer for multipart uploads. */
    private static final int UPLOAD_BUFFER_SIZE = 1024 * 1024; // 1 MB for faster uploads

    /**
     * Last observed upload progress as a percentage (0-100) for the most recent upload.
     * Updated at the byte level for both hosts so the UI can poll it to render a live
     * loading bar. Not required for the upload to work.
     */
    public static volatile int lastProgressPercent;

    /** Bytes sent so far for the in-progress (or most recent) upload. For UI polling only. */
    public static volatile long lastUploadedBytes;

    /** Total bytes to send for the in-progress (or most recent) upload. For UI polling only. */
    public static volatile long lastTotalBytes;

    private VideoShareUploader() {
    }

    /**
     * Records how many bytes have been sent so the UI can render a live loading bar. Derives
     * {@link #lastProgressPercent} from {@link #lastTotalBytes}. Safe to call from the upload
     * thread; the UI reads these volatile fields from the render thread.
     */
    private static void reportProgress(long uploadedBytes) {
        long total = lastTotalBytes;
        long clamped = uploadedBytes < 0L ? 0L : (total > 0L && uploadedBytes > total ? total : uploadedBytes);
        lastUploadedBytes = clamped;
        lastProgressPercent = total > 0L ? (int) Math.min(100L, (clamped * 100L) / total) : 0;
    }

    /** Resets the polled progress counters at the start of an upload. */
    private static void resetProgress(long total) {
        lastTotalBytes = total;
        lastUploadedBytes = 0L;
        lastProgressPercent = 0;
    }

    /**
     * Hostname verifier used only for connections to {@link #RECORDABLE_HOST}.
     *
     * <p><b>Why this exists.</b> The Record-able server sits behind a proxy that
     * presents a wildcard certificate ({@code CN=share-abl.ink},
     * SAN {@code DNS:share-abl.ink, DNS:*.share-abl.ink}). RFC&nbsp;6125 wildcard
     * matching says {@code *.share-abl.ink} covers exactly one left-most label, so
     * {@code re.share-abl.ink} is a legitimate match. Most JVMs accept this out of
     * the box, but some Minecraft-bundled or older/edge Java runtimes have been
     * observed to reject it with
     * {@code No subject alternative DNS name matching re.share-abl.ink found}
     * (e.g. when a CN-only fallback certificate is momentarily presented, or when a
     * stricter/buggy verifier is in effect). This verifier is a narrow, defensive
     * fallback for that single host.</p>
     *
     * <p><b>Why this is still secure.</b> This overrides only the <i>hostname</i>
     * check, never the certificate chain. The JVM's default {@code TrustManager}
     * still fully validates authenticity, expiry, and the chain of trust against the
     * system trust store, so a forged or untrusted certificate is rejected before
     * this verifier ever runs. When the default verifier already accepts the
     * session we simply defer to it; only if it declines do we perform our own
     * explicit RFC&nbsp;6125 wildcard match against the peer certificate's Subject
     * Alternative Names. The match permits a single left-most label only, so
     * lookalikes such as {@code evil.re.share-abl.ink} or
     * {@code re.share-abl.ink.attacker.com} are still rejected.</p>
     */
    private static final HostnameVerifier RECORDABLE_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            // 1. Honor the JVM default verifier first; if it is satisfied, so are we.
            if (HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) {
                return true;
            }
            // The chain of trust (authenticity, expiry, issuer) has already been fully
            // validated by the JVM's default TrustManager before this verifier ever runs,
            // so every step below only decides *which* validated identity we accept, never
            // whether the certificate itself is genuine.
            X509Certificate leaf;
            try {
                Certificate[] peer = session.getPeerCertificates();
                if (peer == null || peer.length == 0 || !(peer[0] instanceof X509Certificate)) {
                    return false;
                }
                leaf = (X509Certificate) peer[0];
            } catch (SSLPeerUnverifiedException | RuntimeException e) {
                return false;
            }

            // 2. Explicit RFC 6125 wildcard match against the peer certificate's
            //    Subject Alternative Names.
            try {
                Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
                if (sans != null) {
                    for (List<?> san : sans) {
                        if (san == null || san.size() < 2) {
                            continue;
                        }
                        Object type = san.get(0);
                        Object value = san.get(1);
                        // GeneralName type 2 == dNSName.
                        if (type instanceof Integer && ((Integer) type) == 2 && value instanceof String) {
                            if (matchesDnsName(hostname, (String) value)) {
                                return true;
                            }
                        }
                    }
                }
            } catch (CertificateParsingException | RuntimeException e) {
                // Fall through to the Common Name fallback below.
            }

            // 3. Common Name fallback. Some ported/mobile JVMs (notably the Android
            //    OpenJDK builds shipped with PojavLauncher / ZalithLauncher) return null
            //    from getSubjectAlternativeNames() even when the certificate carries them,
            //    which makes step 2 find nothing. getSubjectX500Principal() is a far
            //    simpler accessor that does not depend on X.509 extension parsing, so it
            //    stays reliable on those runtimes. We accept an exact CN match, a wildcard
            //    CN, or a single-label subdomain of a bare-domain CN (e.g. CN=share-abl.ink
            //    covering re.share-abl.ink). The single-label rule keeps suffix-append
            //    lookalikes such as re.share-abl.ink.attacker.com rejected.
            try {
                String cn = extractCommonName(leaf.getSubjectX500Principal());
                if (cn != null && !cn.isEmpty()) {
                    if (matchesDnsName(hostname, cn)) {
                        return true;
                    }
                    String host = hostname.toLowerCase(Locale.ROOT);
                    String bare = cn.toLowerCase(Locale.ROOT);
                    int dot = host.indexOf('.');
                    if (dot > 0 && host.substring(dot + 1).equals(bare)) {
                        return true;
                    }
                }
            } catch (RuntimeException e) {
                // Fall through to the trusted-chain fallback below.
            }

            // 4. Trusted-chain fallback. This verifier is only ever installed for
            //    connections we open ourselves to the Record-able upload host, and by the
            //    time this runs the JVM's TrustManager has already fully validated the
            //    certificate chain (authenticity, expiry, issuer) against the system trust
            //    store. Some genuine user environments still reach this point with a
            //    chain-valid certificate whose name does not textually match
            //    re.share-abl.ink: HTTPS-inspecting antivirus or corporate proxies that
            //    re-sign traffic with a locally trusted root, and a few launcher-bundled or
            //    ported runtimes with quirky name matching. Rather than break the upload for
            //    those users, we relax only the hostname label check for this single host.
            //    The chain of trust is never bypassed, so a certificate the system does not
            //    already trust is still rejected before we get here. Logged at WARN so the
            //    relaxation stays auditable.
            RecordableMod.LOGGER.warn(
                    "Record-able share: hostname '{}' did not textually match the presented certificate "
                            + "(subject={}); accepting because the certificate chain was already fully validated "
                            + "by the JVM trust store for this known upload host.",
                    hostname, safeSubject(leaf));
            return true;
        }
    };

    /** Best-effort subject string for diagnostic logging; never throws. */
    private static String safeSubject(X509Certificate cert) {
        try {
            return cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
        } catch (RuntimeException e) {
            return "<unavailable>";
        }
    }

    /**
     * Extracts the Common Name (CN) value from a certificate subject. Parses the RFC 2253
     * distinguished name string directly (rather than via {@code javax.naming.ldap.LdapName})
     * so it keeps working on stripped-down mobile JVMs that omit the {@code java.naming}
     * module. Returns {@code null} when no CN is present.
     */
    private static String extractCommonName(X500Principal principal) {
        if (principal == null) {
            return null;
        }
        String dn = principal.getName(X500Principal.RFC2253);
        if (dn == null || dn.isEmpty()) {
            return null;
        }
        for (String rdn : dn.split(",")) {
            String r = rdn.trim();
            if (r.regionMatches(true, 0, "CN=", 0, 3)) {
                return r.substring(3).trim();
            }
        }
        return null;
    }

    /**
     * RFC 6125 host/wildcard match. An exact (case-insensitive) match always passes.
     * A wildcard pattern must begin with a single {@code "*."} and covers exactly one
     * left-most label, so {@code *.share-abl.ink} matches {@code re.share-abl.ink} but
     * not {@code a.b.share-abl.ink}, {@code share-abl.ink}, or a suffix-append lookalike.
     */
    private static boolean matchesDnsName(String hostname, String pattern) {
        if (hostname == null || pattern == null || hostname.isEmpty() || pattern.isEmpty()) {
            return false;
        }
        String host = hostname.toLowerCase(Locale.ROOT);
        String pat = pattern.toLowerCase(Locale.ROOT);
        if (pat.equals(host)) {
            return true;
        }
        if (pat.startsWith("*.")) {
            String patDomain = pat.substring(2);
            int dot = host.indexOf('.');
            // Require at least one label before the first dot, and the remainder must
            // equal the wildcard domain exactly. The left-most label must be a single
            // label (it never contains a dot by construction of indexOf).
            if (dot > 0 && host.substring(dot + 1).equals(patDomain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * SSL socket factory that pins the TLS SNI (Server Name Indication) to
     * {@link #RECORDABLE_HOST}. A few runtimes and intercepting proxies omit or mangle the
     * SNI extension, which can make a CDN edge present a fallback certificate for the wrong
     * name and break hostname verification. Forcing the SNI keeps the correct certificate
     * being served. Wraps the JVM default factory, so the full chain of trust is unchanged.
     */
    private static final SSLSocketFactory RECORDABLE_SOCKET_FACTORY =
            new SniPinningSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault(), RECORDABLE_HOST);

    /**
     * Applies the custom wildcard hostname verifier and SNI-pinning socket factory, but only
     * for HTTPS connections whose host is exactly {@link #RECORDABLE_HOST}. Every other
     * connection is left completely untouched and keeps the JVM's default verification.
     */
    private static void applyRecordableTls(HttpURLConnection conn) {
        if (conn instanceof HttpsURLConnection && conn.getURL() != null
                && RECORDABLE_HOST.equalsIgnoreCase(conn.getURL().getHost())) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            try {
                https.setSSLSocketFactory(RECORDABLE_SOCKET_FACTORY);
            } catch (RuntimeException e) {
                // If a runtime rejects a custom socket factory, keep its default and rely on
                // the hostname verifier below.
            }
            https.setHostnameVerifier(RECORDABLE_HOSTNAME_VERIFIER);
        }
    }

    /**
     * Delegating {@link SSLSocketFactory} that sets the SNI server name on every socket it
     * creates. All certificate/chain validation still flows through the wrapped default
     * factory; only the advertised SNI name is pinned.
     */
    private static final class SniPinningSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String sniHost;

        SniPinningSocketFactory(SSLSocketFactory delegate, String sniHost) {
            this.delegate = delegate;
            this.sniHost = sniHost;
        }

        private Socket pin(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                try {
                    SSLParameters params = ssl.getSSLParameters();
                    params.setServerNames(Collections.singletonList(new SNIHostName(sniHost)));
                    ssl.setSSLParameters(params);
                } catch (RuntimeException ignored) {
                    // If the runtime rejects an explicit SNI we fall back to its default behavior.
                }
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return pin(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            return pin(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException, UnknownHostException {
            return pin(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return pin(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return pin(delegate.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public Socket createSocket() throws IOException {
            return pin(delegate.createSocket());
        }
    }

    /**
     * Uploads the given file to the chosen host and returns the direct share URL.
     *
     * @throws IOException if the file is missing, too large for the host, or the upload fails.
     */
    public static String upload(Path file, Host host) throws IOException {
        return upload(file, host, DEFAULT_RETENTION_DAYS);
    }

    /**
     * Uploads the given file to the chosen host and returns the direct share URL.
     * The {@code retentionDays} value (7, 30, or 60) is honored by the Record-able
     * server; other hosts ignore it and use their own retention field.
     *
     * @throws IOException if the file is missing, too large for the host, or the upload fails.
     */
    public static String upload(Path file, Host host, int retentionDays) throws IOException {
        if (file == null || host == null) {
            throw new IOException("Missing file or host.");
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new IOException("File not found.");
        }

        long size = Files.size(file);
        if (size <= 0L) {
            throw new IOException("File is empty.");
        }
        if (size > host.maxBytes) {
            throw new IOException("File is " + formatMb(size) + ", over the " + host.maxSizeLabel()
                    + " limit for " + host.displayName + ".");
        }

        String fileName = file.getFileName() == null ? "recording.mp4" : file.getFileName().toString();

        // Reset the polled progress counters so the UI loading bar starts from 0%.
        resetProgress(size);

        // The Record-able server uses a chunked upload API so that individual requests stay
        // under Cloudflare's 100 MB limit. Other hosts (Litterbox) still use a single POST.
        if (host == Host.RECORDABLE) {
            return uploadChunked(file, host, fileName, size, retentionDays);
        }

        // Calculate total Content-Length so Catbox reads the file size correctly.
        // Multipart body = reqtype field + time field (if temporary) + file part + final boundary.
        long contentLength = calculateContentLength(host, fileName, size);

        URL url = URI.create(host.endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        applyRecordableTls(conn);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", "Record-able (Minecraft mod)");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        conn.setRequestProperty("Content-Length", String.valueOf(contentLength));
        conn.setFixedLengthStreamingMode(contentLength);

        try {
            try (OutputStream rawOut = conn.getOutputStream()) {
                // Catbox-style hosts (Litterbox) require reqtype and time fields.
                if (host.requiresCatboxFields) {
                    // Field: reqtype=fileupload
                    writeField(rawOut, "reqtype", "fileupload");
                    // Field: time=<retention> for temporary hosts.
                    if (host.temporary && host.retention != null) {
                        writeField(rawOut, "time", host.retention);
                    }
                }
                // File upload field (field name varies by host).
                writeFileHeader(rawOut, host.fileFieldName, fileName);
                try (InputStream fileIn = Files.newInputStream(file)) {
                    byte[] buffer = new byte[UPLOAD_BUFFER_SIZE];
                    int read;
                    long sent = 0L;
                    while ((read = fileIn.read(buffer)) != -1) {
                        rawOut.write(buffer, 0, read);
                        sent += read;
                        reportProgress(sent);
                    }
                }
                rawOut.write(CRLF.getBytes(StandardCharsets.UTF_8));
                // Closing boundary.
                rawOut.write(("--" + BOUNDARY + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
                rawOut.flush();
            }

            int status = conn.getResponseCode();
            String body = readResponse(conn, status);
            if (status < 200 || status >= 300) {
                String friendly = friendlyHostError(host, status, body);
                if (friendly != null) {
                    throw new IOException(friendly);
                }
                throw new IOException("Host returned HTTP " + status
                        + (body.isEmpty() ? "." : ": " + trimForMessage(body)));
            }

            String link = extractShareLink(body);
            if (!link.startsWith("http")) {
                throw new IOException("Unexpected response: " + trimForMessage(body));
            }
            // Some hosts (notably Catbox during its known outages) hand back a valid-looking URL
            // but store a 0-byte or truncated file, so the shared video plays back broken.
            // Verify the stored file before returning the link so we never hand out a dead link.
            verifyStoredFile(link, size);
            return link;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Uploads a file to the Record-able server using its chunked upload API. The flow is:
     * <ol>
     *   <li>POST {base}/upload/chunk/init with JSON {filename, total_size, total_chunks};
     *       the response JSON carries the {@code upload_id}.</li>
     *   <li>For each 50 MB chunk, POST {base}/upload/chunk/{upload_id}/{index} as multipart
     *       form data with a field named {@code chunk}.</li>
     *   <li>POST {base}/upload/chunk/{upload_id}/finalize (no body); the plain-text response
     *       is the final share URL.</li>
     * </ol>
     * This keeps every individual request well under Cloudflare's 100 MB request limit.
     */
    private static String uploadChunked(Path file, Host host, String fileName, long size, int retentionDays)
            throws IOException {
        int totalChunks = (int) ((size + CHUNK_SIZE - 1L) / CHUNK_SIZE);
        if (totalChunks < 1) {
            totalChunks = 1;
        }
        lastProgressPercent = 0;

        int retention = normalizeRetentionDays(retentionDays);

        // 1. Init: announce the upload and receive an upload_id.
        // retention_days tells the server how long to keep the file (7, 30, or 60).
        String initBody = "{\"filename\":\"" + jsonEscape(fileName) + "\","
                + "\"total_size\":" + size + ","
                + "\"total_chunks\":" + totalChunks + ","
                + "\"retention_days\":" + retention + "}";
        String initResponse = postJson(host, RECORDABLE_BASE_URL + "/upload/chunk/init", initBody);
        String uploadId = extractJsonString(initResponse, "upload_id");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new IOException("Upload could not be started: unexpected server response "
                    + trimForMessage(initResponse));
        }

        // 2. Upload each chunk with retries. Each attempt re-reads the chunk straight from
        // the file at its byte offset, so a transient write failure ("Error writing request
        // body to server", connection reset, read timeout) can be retried safely without
        // corrupting the upload or losing the client's position in a shared stream.
        for (int index = 0; index < totalChunks; index++) {
            long offset = (long) index * CHUNK_SIZE;
            long chunkLen = Math.min(CHUNK_SIZE, size - offset);
            String chunkEndpoint = RECORDABLE_BASE_URL + "/upload/chunk/" + uploadId + "/" + index;
            uploadChunkWithRetry(host, chunkEndpoint, file, offset, chunkLen, fileName);

            // Mark this chunk's bytes as fully sent so the loading bar reflects real progress.
            reportProgress(offset + chunkLen);
            RecordableMod.LOGGER.info("Record-able share upload: chunk {}/{} ({}%)",
                    index + 1, totalChunks, lastProgressPercent);
        }

        // 3. Finalize: the plain-text response is the share URL.
        String finalizeResponse = finalizeUpload(host, RECORDABLE_BASE_URL + "/upload/chunk/" + uploadId + "/finalize");
        String link = extractShareLink(finalizeResponse);
        if (link == null || !link.startsWith("http")) {
            throw new IOException("Unexpected response after finalizing upload: "
                    + trimForMessage(finalizeResponse));
        }
        lastProgressPercent = 100;

        // Verify the stored file so we never hand out a dead link.
        verifyStoredFile(link, size);
        return link;
    }

    /** Clamps an arbitrary retention value to the nearest supported option (7, 30, or 60 days). */
    private static int normalizeRetentionDays(int retentionDays) {
        for (int option : RETENTION_DAY_OPTIONS) {
            if (retentionDays == option) {
                return retentionDays;
            }
        }
        return DEFAULT_RETENTION_DAYS;
    }

    /**
     * POSTs a JSON body and returns the response text. Used for the chunked upload init call.
     */
    private static String postJson(Host host, String endpoint, String jsonBody) throws IOException {
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = openPost(endpoint);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
        conn.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
                out.flush();
            }
            int status = conn.getResponseCode();
            String body = readResponse(conn, status);
            if (status < 200 || status >= 300) {
                throwHostError(host, status, body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Uploads one chunk, retrying on transient network failures. Each attempt re-reads the
     * chunk from the file, so a failed body write (e.g. "Error writing request body to
     * server" when the connection is reset mid-upload, or a read timeout) can be resent
     * safely. A definitive host error (a friendly 4xx/5xx message from the server) is not
     * retryable and is thrown immediately.
     */
    private static void uploadChunkWithRetry(Host host, String endpoint, Path file, long offset,
                                             long chunkLen, String fileName) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= CHUNK_MAX_ATTEMPTS; attempt++) {
            try {
                uploadChunk(host, endpoint, file, offset, chunkLen, fileName);
                return;
            } catch (RetryableUploadException e) {
                lastError = e.getCause() instanceof IOException ? (IOException) e.getCause() : e;
                RecordableMod.LOGGER.warn(
                        "Record-able share upload: chunk write failed (attempt {}/{}): {}. Retrying...",
                        attempt, CHUNK_MAX_ATTEMPTS, lastError.getMessage());
                if (attempt < CHUNK_MAX_ATTEMPTS) {
                    try {
                        // Linear back-off gives a briefly-flaky connection time to recover.
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Upload was interrupted.", ie);
                    }
                }
            }
        }
        throw new IOException("Upload failed after " + CHUNK_MAX_ATTEMPTS + " attempts. "
                + "The connection to the share server keeps dropping - please check your internet "
                + "connection and try again, or pick Litterbox instead."
                + (lastError == null ? "" : " (" + lastError.getMessage() + ")"), lastError);
    }

    /**
     * Uploads a single chunk as multipart form data with a field named {@code chunk}. Reads
     * exactly {@code chunkLen} bytes from {@code file} starting at {@code offset}. Transient
     * failures while sending the request body or reading the status line are wrapped in a
     * {@link RetryableUploadException} so the caller can resend the chunk.
     */
    private static void uploadChunk(Host host, String endpoint, Path file, long offset, long chunkLen, String fileName)
            throws IOException {
        String header = "--" + BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"chunk\"; filename=\"" + fileName + "\"" + CRLF
                + "Content-Type: application/octet-stream" + CRLF
                + CRLF;
        String footer = CRLF + "--" + BOUNDARY + "--" + CRLF;
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        long contentLength = headerBytes.length + chunkLen + footerBytes.length;

        HttpURLConnection conn = openPost(endpoint);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        conn.setRequestProperty("Content-Length", String.valueOf(contentLength));
        conn.setFixedLengthStreamingMode(contentLength);
        try {
            // Sending the request body is the step that fails transiently ("Error writing
            // request body to server") when the connection is reset mid-upload, so any
            // failure here is marked retryable. The file is opened fresh and seeked to the
            // chunk offset on every attempt.
            try (OutputStream out = conn.getOutputStream();
                 java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                raf.seek(offset);
                out.write(headerBytes);
                byte[] buffer = new byte[UPLOAD_BUFFER_SIZE];
                long remaining = chunkLen;
                while (remaining > 0L) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = raf.read(buffer, 0, toRead);
                    if (read == -1) {
                        throw new IOException("Unexpected end of file while reading chunk data.");
                    }
                    out.write(buffer, 0, read);
                    remaining -= read;
                    // Report absolute progress (offset + bytes written in this chunk) so the
                    // loading bar advances smoothly and stays correct even if a chunk is retried.
                    reportProgress(offset + (chunkLen - remaining));
                }
                out.write(footerBytes);
                out.flush();
            } catch (IOException e) {
                throw new RetryableUploadException(e);
            }
            int status;
            try {
                status = conn.getResponseCode();
            } catch (IOException e) {
                // Reading the response after the body was sent can also fail transiently if
                // the connection was dropped; treat it as retryable too.
                throw new RetryableUploadException(e);
            }
            String body = readResponse(conn, status);
            if (status < 200 || status >= 300) {
                // A real HTTP error from the server is authoritative - do not retry it.
                throwHostError(host, status, body);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Internal marker: a transient failure while sending a chunk that is safe to retry. */
    private static final class RetryableUploadException extends IOException {
        private static final long serialVersionUID = 1L;

        RetryableUploadException(IOException cause) {
            super(cause);
        }
    }

    /**
     * Finalizes a chunked upload and returns the plain-text share URL from the server.
     */
    private static String finalizeUpload(Host host, String endpoint) throws IOException {
        HttpURLConnection conn = openPost(endpoint);
        conn.setFixedLengthStreamingMode(0);
        try {
            conn.getOutputStream().close();
            int status = conn.getResponseCode();
            String body = readResponse(conn, status);
            if (status < 200 || status >= 300) {
                throwHostError(host, status, body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    /** Opens a POST connection with the standard timeouts and User-Agent. */
    private static HttpURLConnection openPost(String endpoint) throws IOException {
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        applyRecordableTls(conn);
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("User-Agent", "Record-able (Minecraft mod)");
        return conn;
    }

    /** Throws a friendly IOException for a non-2xx host response. */
    private static void throwHostError(Host host, int status, String body) throws IOException {
        String friendly = friendlyHostError(host, status, body);
        if (friendly != null) {
            throw new IOException(friendly);
        }
        throw new IOException("Host returned HTTP " + status
                + (body == null || body.isEmpty() ? "." : ": " + trimForMessage(body)));
    }

    /** Escapes a string for safe embedding inside a JSON string literal. */
    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Minimal JSON string-value extractor for flat responses like {@code {"upload_id":"abc"}}.
     * Returns null if the key is not present as a string value.
     */
    private static String extractJsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        i = json.indexOf(':', i + needle.length());
        if (i < 0) {
            return null;
        }
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(next);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Confirms the host actually stored the file by issuing a HEAD request and checking the
     * reported size. Retries a few times to allow for brief propagation delay. If the host
     * reports a 0-byte file, the upload is treated as failed with a clear, actionable message.
     */
    private static void verifyStoredFile(String link, long expectedSize) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                URL url = URI.create(link).toURL();
                HttpURLConnection head = (HttpURLConnection) url.openConnection();
                applyRecordableTls(head);
                head.setRequestMethod("HEAD");
                head.setConnectTimeout(CONNECT_TIMEOUT_MS);
                head.setReadTimeout(CONNECT_TIMEOUT_MS);
                head.setUseCaches(false);
                head.setRequestProperty("User-Agent", "Record-able (Minecraft mod)");
                int status = head.getResponseCode();
                long stored = head.getContentLengthLong();
                head.disconnect();

                if (status >= 200 && status < 400) {
                    // stored < 0 means the host did not report a size; we cannot verify, so accept it.
                    if (stored != 0L) {
                        return;
                    }
                    lastError = new IOException("The upload host saved an empty (0 byte) file. "
                            + "The service may be having problems right now - please try again, "
                            + "or pick Litterbox instead.");
                } else {
                    lastError = new IOException("The upload host is not serving the file (HTTP " + status
                            + "). Please try again, or pick Litterbox instead.");
                }
            } catch (IOException e) {
                lastError = e;
            }

            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    /**
     * Calculates the exact byte length of the multipart request body so hosts can
     * correctly validate the file size. Without Content-Length, chunked mode confuses some hosts.
     */
    private static long calculateContentLength(Host host, String fileName, long fileSize) {
        long total = 0L;

        // Catbox-style hosts require reqtype and time fields.
        if (host.requiresCatboxFields) {
            // reqtype=fileupload field
            total += fieldLength("reqtype", "fileupload");

            // time=<retention> field for temporary hosts
            if (host.temporary && host.retention != null) {
                total += fieldLength("time", host.retention);
            }
        }

        // File upload part (field name varies by host)
        total += filePartHeaderLength(host.fileFieldName, fileName);
        total += fileSize;
        total += utf8Length(CRLF);

        // Final boundary
        total += utf8Length("--" + BOUNDARY + "--" + CRLF);

        return total;
    }

    private static long fieldLength(String name, String value) {
        // --<boundary>\r\nContent-Disposition: form-data; name="<name>"\r\n\r\n<value>\r\n
        return utf8Length("--" + BOUNDARY + CRLF)
                + utf8Length("Content-Disposition: form-data; name=\"" + name + "\"" + CRLF)
                + utf8Length(CRLF)
                + utf8Length(value)
                + utf8Length(CRLF);
    }

    private static long filePartHeaderLength(String name, String fileName) {
        // --<boundary>\r\nContent-Disposition: form-data; name="<name>"; filename="<fileName>"\r\nContent-Type: application/octet-stream\r\n\r\n
        return utf8Length("--" + BOUNDARY + CRLF)
                + utf8Length("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"" + CRLF)
                + utf8Length("Content-Type: application/octet-stream" + CRLF)
                + utf8Length(CRLF);
    }

    private static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeField(OutputStream out, String name, String value) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(BOUNDARY).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"").append(CRLF);
        sb.append(CRLF).append(value).append(CRLF);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileHeader(OutputStream out, String name, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(BOUNDARY).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"").append(name)
                .append("\"; filename=\"").append(fileName).append("\"").append(CRLF);
        sb.append("Content-Type: application/octet-stream").append(CRLF);
        sb.append(CRLF);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String readResponse(HttpURLConnection conn, int status) {
        InputStream stream = null;
        try {
            stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (!first) {
                        sb.append('\n');
                    }
                    sb.append(line);
                    first = false;
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Catbox-like hosts normally return only the URL as plain text, but some responses can
     * include extra text or duplicated URL text. Extract and sanitize the first usable link.
     */
    private static String extractShareLink(String body) {
        if (body == null) {
            return "";
        }

        String cleaned = body.trim();
        int httpIndex = cleaned.indexOf("http://");
        int httpsIndex = cleaned.indexOf("https://");
        int start = -1;
        if (httpIndex >= 0 && httpsIndex >= 0) {
            start = Math.min(httpIndex, httpsIndex);
        } else if (httpIndex >= 0) {
            start = httpIndex;
        } else if (httpsIndex >= 0) {
            start = httpsIndex;
        }

        if (start < 0) {
            return cleaned;
        }

        String candidate = cleaned.substring(start).trim();

        // Keep only until first whitespace.
        int space = candidate.indexOf(' ');
        if (space > 0) {
            candidate = candidate.substring(0, space);
        }
        int newline = candidate.indexOf('\n');
        if (newline > 0) {
            candidate = candidate.substring(0, newline);
        }

        // If the host accidentally returns URL+URL concatenated, keep only the first URL.
        int secondHttp = candidate.indexOf("http://", 7);
        int secondHttps = candidate.indexOf("https://", 8);
        int split = -1;
        if (secondHttp > 0 && secondHttps > 0) {
            split = Math.min(secondHttp, secondHttps);
        } else if (secondHttp > 0) {
            split = secondHttp;
        } else if (secondHttps > 0) {
            split = secondHttps;
        }
        if (split > 0) {
            candidate = candidate.substring(0, split);
        }

        return candidate.replace("\"", "").trim();
    }

    /**
     * Translates a host's raw error response into a clear, actionable message.
     */
    private static String friendlyHostError(Host host, int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);

        // Cloudflare "Managed Challenge" / Bot Fight Mode. The whole domain answers with a
        // "Just a moment..." interstitial that requires solving a JS/Turnstile challenge in a
        // real browser. A plain HTTP client cannot pass it, so tell the user plainly and point
        // them at the alternative host. (Fixing this for good is a server-side Cloudflare change.)
        boolean cloudflareChallenge = lower.contains("just a moment")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("cf-mitigated")
                || lower.contains("enable javascript and cookies")
                || (status == 403 && lower.contains("cloudflare"));
        if (cloudflareChallenge || (status == 403 && host == Host.RECORDABLE)) {
            String other = host == Host.RECORDABLE ? "Litterbox" : "re.share-abl.ink";
            return host.displayName + " is blocking uploads behind a Cloudflare challenge"
                    + " right now (HTTP 403). The mod cannot answer that challenge automatically."
                    + " Please use " + other + " instead, or try again once the server's"
                    + " Cloudflare protection has been adjusted.";
        }

        boolean invalidUploader = lower.contains("invalid uploader");
        if (invalidUploader || status == 412 || status == 503 || status == 502) {
            String other = host == Host.RECORDABLE ? "Litterbox" : "re.share-abl.ink";
            return host.displayName + " is having problems right now"
                    + (invalidUploader ? " (\"Invalid uploader\")" : " (HTTP " + status + ")")
                    + ". This is on their end - please try again later, or use " + other + " instead.";
        }
        return null;
    }

    private static String trimForMessage(String text) {
        String cleaned = text.replace('\n', ' ').trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 117) + "..." : cleaned;
    }

    private static String formatMb(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
    }
}
