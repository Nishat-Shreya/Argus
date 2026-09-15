package com.argus.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Section 6.3: {@code DesktopNotifiers} — the factory that guarantees a disabled build never
 * touches AWT (F1). Uses a fake probe + a fake real supplier only; loads no {@code java.awt}
 * class.
 */
class DesktopNotifiersTest {

    @Test
    void f1DisabledNeverProbesOrConstructsReal() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger realCalls = new AtomicInteger();
        BooleanSupplier probe = () -> {
            probeCalls.incrementAndGet();
            return true;
        };
        Supplier<DesktopNotifier> real = () -> {
            realCalls.incrementAndGet();
            return new FakeNotifier();
        };

        DesktopNotifier notifier = DesktopNotifiers.create(false, probe, real);

        assertNotNull(notifier);
        assertEquals(0, probeCalls.get());
        assertEquals(0, realCalls.get());
        assertDoesNotThrow(() -> notifier.show(new DesktopNotification("c", "t")));
        assertDoesNotThrow(notifier::close);
    }

    @Test
    void f2EnabledButNoTrayFallsBackToDisabledWithoutConstructingReal() {
        AtomicInteger realCalls = new AtomicInteger();
        Supplier<DesktopNotifier> real = () -> {
            realCalls.incrementAndGet();
            return new FakeNotifier();
        };

        DesktopNotifier notifier = DesktopNotifiers.create(true, () -> false, real);

        assertNotNull(notifier);
        assertEquals(0, realCalls.get());
    }

    @Test
    void f3EnabledAndTrayAvailableReturnsTheSuppliedInstance() {
        FakeNotifier fake = new FakeNotifier();
        DesktopNotifier notifier = DesktopNotifiers.create(true, () -> true, () -> fake);

        assertSame(fake, notifier);
    }

    @Test
    void f4ProbeThrowingYieldsDisabled() {
        BooleanSupplier throwingProbe = () -> {
            throw new IllegalStateException("boom");
        };
        AtomicInteger realCalls = new AtomicInteger();
        Supplier<DesktopNotifier> real = () -> {
            realCalls.incrementAndGet();
            return new FakeNotifier();
        };

        DesktopNotifier notifier =
                assertDoesNotThrow(() -> DesktopNotifiers.create(true, throwingProbe, real));

        assertNotNull(notifier);
        assertEquals(0, realCalls.get());
    }

    @Test
    void f5RealSupplierThrowingYieldsDisabled() {
        Supplier<DesktopNotifier> throwingReal = () -> {
            throw new IllegalStateException("boom");
        };

        DesktopNotifier notifier =
                assertDoesNotThrow(() -> DesktopNotifiers.create(true, () -> true, throwingReal));

        assertNotNull(notifier);
        assertDoesNotThrow(() -> notifier.show(new DesktopNotification("c", "t")));
    }

    @Test
    void f6DisabledNotifierIsANeverThrowingNoOp() {
        DesktopNotifier notifier = DesktopNotifier.disabled();
        assertDoesNotThrow(() -> notifier.show(new DesktopNotification("c", "t")));
        assertDoesNotThrow(() -> notifier.show(null));
        assertDoesNotThrow(notifier::close);
        assertDoesNotThrow(notifier::close);
    }

    private static final class FakeNotifier implements DesktopNotifier {
        @Override
        public void show(DesktopNotification notification) {
            // recording fake; nothing to do for these tests
        }

        @Override
        public void close() {
            // recording fake; nothing to do for these tests
        }
    }
}
