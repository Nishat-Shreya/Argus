package com.argus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link JdkSmtpTransport} against a real loopback fake SMTP server on an ephemeral port (the
 * {@code JdkWebhookTransport}/P1-02 precedent), using the plain (non-TLS) {@code
 * SocketFactory.getDefault()} test seam. Literal {@code "127.0.0.1"}, never {@code "localhost"}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JdkSmtpTransportTest {

    private ServerSocket serverSocket;

    @AfterEach
    void stopServer() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    private EmailEndpoint endpointFor(int port) {
        return new EmailEndpoint("127.0.0.1", port, "operator@example.com",
                "ops@example.com", "s3cret");
    }

    @Test
    void j1HappyPathSpeaksTheExpectedDialogAndSubmitsTheMessage() throws Exception {
        List<String> received = new ArrayList<>();
        AtomicReference<Exception> serverFailure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        serverSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        int port = serverSocket.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try {
                runFakeServer(serverSocket, received);
            } catch (Exception e) {
                serverFailure.set(e);
            } finally {
                done.countDown();
            }
        });
        serverThread.start();

        new JdkSmtpTransport(SocketFactory.getDefault())
                .send(endpointFor(port), "Argus: 1 new finding", "line one\nline two");

        assertTrue(done.await(10, TimeUnit.SECONDS), "fake server did not finish in time");
        assertEquals(null, serverFailure.get(),
                serverFailure.get() == null ? null : serverFailure.get().toString());

        assertTrue(received.contains("EHLO argus"));
        assertTrue(received.contains("AUTH LOGIN"));
        assertTrue(received.contains(base64("operator@example.com")));
        assertTrue(received.contains(base64("s3cret")));
        assertTrue(received.contains("MAIL FROM:<operator@example.com>"));
        assertTrue(received.contains("RCPT TO:<ops@example.com>"));
        assertTrue(received.contains("DATA"));
        assertTrue(received.contains("Subject: Argus: 1 new finding"));
        assertTrue(received.contains("line one"));
        assertTrue(received.contains("line two"));
        assertTrue(received.contains("QUIT"));
    }

    @Test
    void j2ABodyLineStartingWithADotIsDotStuffed() throws Exception {
        List<String> received = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        serverSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        int port = serverSocket.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try {
                runFakeServer(serverSocket, received);
            } catch (Exception ignored) {
                // assertions below fail instead
            } finally {
                done.countDown();
            }
        });
        serverThread.start();

        new JdkSmtpTransport(SocketFactory.getDefault())
                .send(endpointFor(port), "subject", ".line starting with a dot");

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(received.contains("..line starting with a dot"),
                "a body line starting with '.' must be escaped as '..' per RFC 5321");
    }

    @Test
    void j3AnUnexpectedReplyCodeThrowsEmailDeliveryExceptionWithThatCode() throws Exception {
        serverSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        int port = serverSocket.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try (Socket client = serverSocket.accept();
                    OutputStream out = client.getOutputStream()) {
                write(out, "554 no thanks\r\n");
            } catch (IOException ignored) {
                // client will see a closed connection; assertion below covers the failure path
            }
        });
        serverThread.start();

        EmailDeliveryException thrown = assertThrows(EmailDeliveryException.class,
                () -> new JdkSmtpTransport(SocketFactory.getDefault())
                        .send(endpointFor(port), "subject", "body"));
        assertEquals(554, thrown.replyCode());
    }

    @Test
    void j4UnboundPortThrowsWithoutHanging() throws Exception {
        int unboundPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unboundPort = probe.getLocalPort();
        }

        assertThrows(IOException.class, () -> new JdkSmtpTransport(SocketFactory.getDefault())
                .send(endpointFor(unboundPort), "subject", "body"));
    }

    /** Speaks just enough SMTP to complete one AUTH LOGIN + MAIL/RCPT/DATA dialog, recording
     *  every line it reads. */
    private static void runFakeServer(ServerSocket serverSocket, List<String> received)
            throws IOException {
        try (Socket client = serverSocket.accept();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = client.getOutputStream()) {
            write(out, "220 test ready\r\n");
            int authStep = 0;
            String line;
            while ((line = in.readLine()) != null) {
                received.add(line);
                if (line.equals("QUIT")) {
                    write(out, "221 bye\r\n");
                    return;
                } else if (line.startsWith("EHLO")) {
                    write(out, "250 ok\r\n");
                } else if (line.equals("AUTH LOGIN")) {
                    authStep = 1;
                    write(out, "334 VXNlcm5hbWU6\r\n");
                } else if (authStep == 1) {
                    authStep = 2;
                    write(out, "334 UGFzc3dvcmQ6\r\n");
                } else if (authStep == 2) {
                    authStep = 0;
                    write(out, "235 authenticated\r\n");
                } else if (line.startsWith("MAIL FROM")) {
                    write(out, "250 ok\r\n");
                } else if (line.startsWith("RCPT TO")) {
                    write(out, "250 ok\r\n");
                } else if (line.equals("DATA")) {
                    write(out, "354 go ahead\r\n");
                    String bodyLine;
                    while ((bodyLine = in.readLine()) != null) {
                        received.add(bodyLine);
                        if (bodyLine.equals(".")) {
                            break;
                        }
                    }
                    write(out, "250 message accepted\r\n");
                } else {
                    write(out, "500 unrecognized\r\n");
                }
            }
        }
    }

    private static void write(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
