package com.argus.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal SMTP client over implicit TLS (port 465 -- SMTPS), speaking just enough of RFC 5321
 * to authenticate (AUTH LOGIN) and submit one plain-text message. No STARTTLS, no connection
 * pooling, no retry (the {@code WebhookSender}/{@code KevCatalogLoader} contract: a failure
 * surfaces honestly, once). {@code SSLSocket} is a JDK built-in -- zero new dependencies.
 *
 * BLOCKING: every method call here performs network I/O. {@code core.EmailSender} callers run
 * it off the FX thread (invariant 3).
 */
final class JdkSmtpTransport implements EmailTransport {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final SocketFactory socketFactory;

    JdkSmtpTransport() {
        this(SSLSocketFactory.getDefault());
    }

    /** Package-private test seam: inject a plain {@code SocketFactory} pointed at a local
     *  loopback server that speaks the SMTP text protocol without TLS (the {@code
     *  SocketConnector}/{@code HttpFetcher} shape). */
    JdkSmtpTransport(SocketFactory socketFactory) {
        this.socketFactory = socketFactory;
    }

    @Override
    public void send(EmailEndpoint endpoint, String subject, String body) throws IOException {
        try (Socket socket = socketFactory.createSocket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                    CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();

            expect(readResponse(in), 220, "greeting");
            command(out, in, "EHLO argus", 250, "EHLO");
            command(out, in, "AUTH LOGIN", 334, "AUTH LOGIN");
            command(out, in, base64(endpoint.username()), 334, "username");
            command(out, in, base64(endpoint.password()), 235, "authentication");
            command(out, in, "MAIL FROM:<" + endpoint.username() + ">", 250, "MAIL FROM");
            command(out, in, "RCPT TO:<" + endpoint.recipient() + ">", 250, "RCPT TO");
            command(out, in, "DATA", 354, "DATA");

            String message = "From: " + endpoint.username() + "\r\n"
                    + "To: " + endpoint.recipient() + "\r\n"
                    + "Subject: " + subject + "\r\n"
                    + "\r\n"
                    + dotStuff(body) + "\r\n.";
            writeLine(out, message);
            expect(readResponse(in), 250, "message submission");

            writeLine(out, "QUIT");
        } catch (SocketTimeoutException e) {
            throw new IOException("SMTP connection timed out", e);
        }
    }

    /** Sends one command line, reads its response, and validates the code. */
    private static void command(OutputStream out, BufferedReader in, String line,
            int expectedCode, String step) throws IOException {
        writeLine(out, line);
        expect(readResponse(in), expectedCode, step);
    }

    private static void expect(int actualCode, int expectedCode, String step)
            throws EmailDeliveryException {
        if (actualCode != expectedCode) {
            throw new EmailDeliveryException(
                    "SMTP " + step + " failed", actualCode);
        }
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads one SMTP response, following RFC 5321's multi-line continuation rule ({@code
     *  "250-"} continues, {@code "250 "} ends), and returns the numeric reply code. */
    private static int readResponse(BufferedReader in) throws IOException {
        String line;
        String lastLine = null;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("SMTP connection closed unexpectedly");
            }
            lastLine = line;
        } while (line.length() >= 4 && line.charAt(3) == '-');
        try {
            return Integer.parseInt(lastLine.substring(0, 3));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new IOException("malformed SMTP response");
        }
    }

    /** RFC 5321 §4.5.2: a line beginning with {@code .} must be escaped as {@code ..}, since a
     *  lone {@code .} line ends the {@code DATA} command. */
    private static String dotStuff(String body) {
        StringBuilder result = new StringBuilder();
        String[] lines = body.split("\r\n|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append("\r\n");
            }
            String line = lines[i];
            if (line.startsWith(".")) {
                result.append('.');
            }
            result.append(line);
        }
        return result.toString();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
