package cn.lunalhx.ai.domain.agent.model.entity;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RootRunSecurityScopeTest {
    @Test
    public void closesDisposableRootsAndSignalsRegisteredShells() {
        RootRunSecurityScope scope = RootRunSecurityScope.create();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        scope.registerShellCanceller(() -> cancelled.set(true));
        assertTrue(java.nio.file.Files.exists(scope.homeRoot()));
        scope.close();
        assertTrue(cancelled.get());
        assertFalse(java.nio.file.Files.exists(scope.homeRoot()));
        assertFalse(java.nio.file.Files.exists(scope.temporaryRoot()));
    }
}
