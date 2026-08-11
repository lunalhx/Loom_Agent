package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmission;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlanSubmissionTransactionTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void pendingPlanIsInvisibleUntilTerminalRunThenRecoveryCommitsIt() throws Exception {
        Path workspace = Files.createTempDirectory("plan-transaction");
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        String sessionId = "session-transaction";
        String runId = "run-transaction";
        sessions.save(AgentSession.builder()
                .id(sessionId)
                .schemaVersion(AgentSession.CURRENT_SCHEMA_VERSION)
                .workspaceRoot(workspace.toString())
                .collaborationMode(CollaborationMode.PLAN)
                .createdAt(Instant.now())
                .history(new ArrayList<>())
                .keyFiles(new LinkedHashMap<>())
                .build());

        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setSessionId(sessionId);
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setPlanTarget("NEW");
        context.setPlanStateVersion(0L);
        context.setResolvedWorkspace(workspace);
        context.setDecision(AgentDecision.builder()
                .type("plan_submission")
                .planSubmission(PlanSubmission.builder()
                        .title("First plan")
                        .body("Research before implementation.")
                        .dependencies(java.util.List.of())
                        .build())
                .build());

        FilePlanSubmissionHandler handler = new FilePlanSubmissionHandler(sessions, runs, mapper);
        assertEquals("PREPARED", handler.prepare(context).outcome().name());
        AgentSession pending = sessions.find(sessionId).orElseThrow();
        assertTrue(pending.getPlans().isEmpty());
        assertNotNull(pending.getPendingPlanSubmission());
        assertEquals("CONFLICT", handler.commit(context).outcome().name());

        runs.save(AgentRun.builder()
                .runId(runId)
                .sessionId(sessionId)
                .rootRunId(runId)
                .runKind(AgentRunKind.ROOT)
                .runModeSnapshot(CollaborationMode.PLAN)
                .status(AgentRunStatus.COMPLETED)
                .stopReason("PLAN_SUBMITTED")
                .build());

        AgentSession recovered = handler.recoverPending(sessionId);
        assertEquals(1, recovered.getPlans().size());
        assertEquals(recovered.getCurrentPlanId(), recovered.getPlans().get(0).getPlanId());
        assertEquals(1L, recovered.getPlanStateVersion());
        assertNull(recovered.getPendingPlanSubmission());
        assertFalse(recovered.getPlans().get(0).currentRevision().getBody().isBlank());
    }

    @Test
    public void abandonedRunningSubmissionRollsBackDuringRecovery() throws Exception {
        Path workspace = Files.createTempDirectory("plan-abandoned");
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        String sessionId = "session-abandoned";
        String runId = "run-abandoned";
        savePlanSession(sessions, workspace, sessionId);
        AgentContext context = planContext(workspace, sessionId, runId);

        FilePlanSubmissionHandler handler = new FilePlanSubmissionHandler(sessions, runs, mapper);
        assertEquals("PREPARED", handler.prepare(context).outcome().name());
        // This releases the live submission lease without making the Run
        // terminal, simulating a process that died before lifecycle.complete.
        assertEquals("CONFLICT", handler.commit(context).outcome().name());
        runs.save(AgentRun.builder()
                .runId(runId)
                .sessionId(sessionId)
                .rootRunId(runId)
                .runKind(AgentRunKind.ROOT)
                .runModeSnapshot(CollaborationMode.PLAN)
                .status(AgentRunStatus.RUNNING)
                .stopReason(null)
                .build());

        AgentSession recovered = new FilePlanSubmissionHandler(sessions, runs, mapper)
                .recoverPending(sessionId);
        assertTrue(recovered.getPlans().isEmpty());
        assertNull(recovered.getPendingPlanSubmission());
        AgentRun abandoned = runs.find(runId).orElseThrow();
        assertEquals(AgentRunStatus.FAILED, abandoned.getStatus());
        assertEquals("PLAN_CONFLICT", abandoned.getStopReason());
    }

    @Test
    public void commitRevalidatesEvidenceAfterTerminalRunPersistence() throws Exception {
        Path workspace = Files.createTempDirectory("plan-commit-evidence");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        String sessionId = "session-commit-evidence";
        String runId = "run-commit-evidence";
        savePlanSession(sessions, workspace, sessionId);
        AgentContext context = planContext(workspace, sessionId, runId);
        context.setEvidenceReceipts(List.of(readReceipt(runId)));

        FilePlanSubmissionHandler handler = new FilePlanSubmissionHandler(sessions, runs, mapper);
        assertEquals("PREPARED", handler.prepare(context).outcome().name());
        Files.writeString(observed, "after\n");
        runs.save(AgentRun.builder()
                .runId(runId)
                .sessionId(sessionId)
                .rootRunId(runId)
                .runKind(AgentRunKind.ROOT)
                .runModeSnapshot(CollaborationMode.PLAN)
                .status(AgentRunStatus.COMPLETED)
                .stopReason("PLAN_SUBMITTED")
                .evidenceReceipts(context.getEvidenceReceipts())
                .evidenceDrift(false)
                .build());

        assertEquals("CONFLICT", handler.commit(context).outcome().name());
        AgentSession persisted = sessions.find(sessionId).orElseThrow();
        assertTrue(persisted.getPlans().isEmpty());
        assertNull(persisted.getCurrentPlanId());
        assertNull(persisted.getPendingPlanSubmission());
        assertEquals(0L, persisted.getPlanStateVersion());
    }

    @Test
    public void revisionBasisRefreshesSameKeyAppendsNewKeyAndRetainsHistory() throws Exception {
        Path workspace = Files.createTempDirectory("plan-revision-basis");
        Files.writeString(workspace.resolve("observed.txt"), "before\n");
        Files.writeString(workspace.resolve("new.txt"), "new evidence\n");
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        String sessionId = "session-revision-basis";
        savePlanSession(sessions, workspace, sessionId);

        FilePlanSubmissionHandler handler = new FilePlanSubmissionHandler(sessions, runs, mapper);
        AgentContext first = planContext(workspace, sessionId, "run-revision-first");
        first.setEvidenceReceipts(List.of(readReceipt("run-revision-first")));
        assertEquals("PREPARED", handler.prepare(first).outcome().name());
        saveCompletedPlanRun(runs, first);
        assertEquals("SUBMITTED", handler.commit(first).outcome().name());

        AgentSession afterFirst = sessions.find(sessionId).orElseThrow();
        Plan firstPlan = afterFirst.getPlans().get(0);
        String planId = firstPlan.getPlanId();
        Files.writeString(workspace.resolve("observed.txt"), "after\n");

        AgentContext revision = planContext(workspace, sessionId, "run-revision-second");
        revision.setPlanTarget(planId);
        revision.setPlanRevision(1);
        revision.setPlanStateVersion(1L);
        revision.setDecision(AgentDecision.builder()
                .type("plan_submission")
                .planSubmission(PlanSubmission.builder()
                        .title("Revision two")
                        .body("Refresh the observed state and add another dependency.")
                        .dependencies(List.of("new.txt"))
                        .build())
                .build());
        revision.setEvidenceReceipts(List.of(
                readReceipt("run-revision-second", "read-observed", "observed.txt", "after"),
                readReceipt("run-revision-second", "read-new", "new.txt", "new evidence")));

        assertEquals("PREPARED", handler.prepare(revision).outcome().name());
        saveCompletedPlanRun(runs, revision);
        assertEquals("SUBMITTED", handler.commit(revision).outcome().name());

        AgentSession persisted = sessions.find(sessionId).orElseThrow();
        Plan persistedPlan = persisted.getPlans().get(0);
        assertEquals(2, persistedPlan.getRevisions().size());
        assertEquals(2, persistedPlan.currentRevision().getPlanBasis().size());
        assertEquals("run-revision-second", persistedPlan.currentRevision().getPlanBasis().stream()
                .filter(receipt -> "observed.txt".equals(receipt.getRepositoryRelativePath()))
                .findFirst().orElseThrow().getSourceRunId());
        assertEquals("run-revision-first", persistedPlan.getRevisions().get(0)
                .getPlanBasis().get(0).getSourceRunId());
        assertTrue(PlanFreshness.isFresh(workspace, persistedPlan.currentRevision()));
    }

    @Test
    public void staleInheritedBasisBlocksRevisionUntilItIsRefreshed() throws Exception {
        Path workspace = Files.createTempDirectory("plan-stale-revision");
        Files.writeString(workspace.resolve("observed.txt"), "before\n");
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        String sessionId = "session-stale-revision";
        savePlanSession(sessions, workspace, sessionId);

        FilePlanSubmissionHandler handler = new FilePlanSubmissionHandler(sessions, runs, mapper);
        AgentContext first = planContext(workspace, sessionId, "run-stale-first");
        first.setEvidenceReceipts(List.of(readReceipt("run-stale-first")));
        assertEquals("PREPARED", handler.prepare(first).outcome().name());
        saveCompletedPlanRun(runs, first);
        assertEquals("SUBMITTED", handler.commit(first).outcome().name());
        Plan plan = sessions.find(sessionId).orElseThrow().getPlans().get(0);

        Files.writeString(workspace.resolve("observed.txt"), "changed\n");
        AgentContext revision = planContext(workspace, sessionId, "run-stale-second");
        revision.setPlanTarget(plan.getPlanId());
        revision.setPlanRevision(1);
        revision.setPlanStateVersion(1L);

        assertEquals("CONFLICT", handler.prepare(revision).outcome().name());
        AgentSession persisted = sessions.find(sessionId).orElseThrow();
        assertEquals(1, persisted.getPlans().get(0).getRevisions().size());
        assertEquals(1L, persisted.getPlanStateVersion());
        assertNull(persisted.getPendingPlanSubmission());
    }

    private void savePlanSession(AgentSessionRepository sessions, Path workspace,
                                 String sessionId) {
        sessions.save(AgentSession.builder()
                .id(sessionId)
                .schemaVersion(AgentSession.CURRENT_SCHEMA_VERSION)
                .workspaceRoot(workspace.toString())
                .collaborationMode(CollaborationMode.PLAN)
                .createdAt(Instant.now())
                .history(new ArrayList<>())
                .keyFiles(new LinkedHashMap<>())
                .build());
    }

    private AgentContext planContext(Path workspace, String sessionId, String runId) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setSessionId(sessionId);
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setPlanTarget("NEW");
        context.setPlanStateVersion(0L);
        context.setResolvedWorkspace(workspace);
        context.setDecision(AgentDecision.builder()
                .type("plan_submission")
                .planSubmission(PlanSubmission.builder()
                        .title("First plan")
                        .body("Research before implementation.")
                        .dependencies(List.of())
                        .build())
                .build());
        return context;
    }

    private EvidenceReceipt readReceipt(String runId) {
        return readReceipt(runId, "read-observed", "observed.txt", "before");
    }

    private EvidenceReceipt readReceipt(String runId, String evidenceKey,
                                        String path, String content) {
        String semantics = "read_file:utf8-lines:v1";
        return EvidenceReceipt.builder()
                .evidenceKey(evidenceKey)
                .observationType("read_file")
                .toolSemantics(semantics)
                .normalizedScope(path + ":1-1")
                .repositoryRelativePath(path)
                .observedStartLine(1)
                .observedEndLine(1)
                .digestAlgorithm("SHA-256")
                .stateDigest(DigestUtils.sha256Hex(content))
                .complete(true)
                .sourceRunId(runId)
                .rootRunId(runId)
                .revalidation(EvidenceRevalidation.builder()
                        .digestAlgorithm("SHA-256")
                        .observationType("read_file")
                        .toolSemantics(semantics)
                        .repositoryRelativePath(path)
                        .startLine(1)
                        .endLine(1)
                        .build())
                .build();
    }

    private void saveCompletedPlanRun(FileAgentRunRepository runs, AgentContext context) {
        runs.save(AgentRun.builder()
                .runId(context.getRunId())
                .sessionId(context.getSessionId())
                .rootRunId(context.getRootRunId())
                .runKind(AgentRunKind.ROOT)
                .runModeSnapshot(CollaborationMode.PLAN)
                .planTarget(context.getPlanTarget())
                .planRevision(context.getPlanRevision())
                .planStateVersion(context.getPlanStateVersion())
                .status(AgentRunStatus.COMPLETED)
                .stopReason("PLAN_SUBMITTED")
                .evidenceReceipts(context.getEvidenceReceipts())
                .evidenceDrift(context.isEvidenceDrift())
                .build());
    }
}
