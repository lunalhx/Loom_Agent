package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.undo.UndoSessionCoordinator;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class UndoSessionCoordinatorTest {

    @Test
    public void doesNotFinalizeSuspendedSnapshotBeforeApprovalExecutes() {
        WorkspaceSnapshotPort snapshotPort = mock(WorkspaceSnapshotPort.class);
        UndoSnapshotRepository snapshotRepository = mock(UndoSnapshotRepository.class);
        WorkspaceUndoLockRepository lockRepository = mock(WorkspaceUndoLockRepository.class);
        AgentWorkspaceResolver workspaceResolver = mock(AgentWorkspaceResolver.class);
        AgentRuntimeProperties.UndoProperties config =
                new AgentRuntimeProperties.UndoProperties();
        UndoSessionCoordinator coordinator = new UndoSessionCoordinator(
                snapshotPort, snapshotRepository, lockRepository, workspaceResolver, config);

        AgentContext context = new AgentContext();
        context.setRunId("run-suspended");
        context.setResolvedWorkspace(Path.of("/workspace"));
        AgentUndoSnapshot snapshot = AgentUndoSnapshot.builder()
                .runId("run-suspended")
                .status(UndoSnapshotStatus.SUSPENDED)
                .build();
        when(snapshotRepository.findByRunId("run-suspended"))
                .thenReturn(Optional.of(snapshot));

        coordinator.finalizeSnapshot(context);

        verify(snapshotPort, never()).createAfterSnapshot(any(), anyString());
        verify(snapshotRepository, never()).save(any());
        verifyNoInteractions(lockRepository);
    }
}
