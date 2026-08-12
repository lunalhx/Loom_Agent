package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunStartGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.domain.agent.model.entity.PlanBinding;
import cn.lunalhx.ai.domain.agent.model.entity.PlanRevision;
import cn.lunalhx.ai.domain.agent.model.entity.ResumeResult;
import cn.lunalhx.ai.domain.agent.model.entity.TaskCheckpoint;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileDurableMemoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileRunStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * CLI session facade. One {@link AgentSession} per workspace session; every
 * user turn creates a fresh root run whose context is seeded from the session
 * (history, working memory, semantic checkpoint). Runs/traces/checkpoints and
 * the session are all written from the loop's own state — never from a second
 * CLI-side state machine.
 */
public class CliSessionService implements AutoCloseable {

    private final AgentLoopService loopService;
    private final AgentRuntimeProperties agent;
    private final ModelRuntimeProperties model;
    private final String workspace;
    private String sessionId;
    private final AgentSessionRepository sessionStore;
    private final AgentRunRepository runStore;
    private final AgentCheckpointRepository checkpointStore;
    private final TraceRecorder traceRecorder;
    private final cn.lunalhx.ai.domain.memory.adapter.port.DurableMemoryRepository memoryStore;
    private final cn.lunalhx.ai.domain.memory.service.MemoryPromotionService memoryPromotion;
    private final ObjectMapper mapper;
    private final CliOptions options;
    private final SecretRedactor redactor;
    private final FilePlanSubmissionHandler planSubmissionHandler;

    private AgentSession session;

    public CliSessionService(ApplicationContext spring, CliOptions options) {
        this(options, spring.getBean(ObjectMapper.class),
                spring.getBean(AgentRuntimeProperties.class),
                spring.getBean(ModelRuntimeProperties.class),
                spring.getBean(AgentSessionRepository.class),
                spring.getBean(AgentRunRepository.class),
                spring.getBean(AgentCheckpointRepository.class),
                spring.getBean(TraceRecorder.class),
                spring.getBean(AgentLoopService.class));
    }

    /** Build a shared artifact redactor from CLI options (writer last-defense). */
    private static cn.lunalhx.ai.infrastructure.store.ArtifactRedactor artifactRedactor(CliOptions options) {
        Set<String> providerKeys = options.apiKey == null || options.apiKey.isBlank()
                ? Set.of() : Set.of(options.apiKey);
        return new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(
                SecretRedactor.of(Set.copyOf(options.secretEnvNames),
                        new java.util.LinkedHashSet<>(options.secretValues), providerKeys));
    }

    /** Test entry point: direct dependencies, no Spring context required. */
    CliSessionService(CliOptions options, ObjectMapper mapper,
                      AgentRuntimeProperties agent, ModelRuntimeProperties model,
                      AgentSessionRepository sessionStore, AgentRunRepository runStore,
                      AgentCheckpointRepository checkpointStore, TraceRecorder traceRecorder,
                      AgentLoopService loopService) {
        this.sessionId = options.resumeSessionId != null
                ? options.resumeSessionId : newSessionId();
        this.workspace = options.workspaceRoot;
        this.options = options;
        this.mapper = mapper;
        this.sessionStore = sessionStore;
        this.runStore = runStore;
        this.checkpointStore = checkpointStore;
        this.traceRecorder = traceRecorder;
        this.memoryStore = new FileDurableMemoryRepository(Path.of(options.workspaceRoot), mapper,
                artifactRedactor(options));
        this.memoryPromotion = new cn.lunalhx.ai.domain.memory.service.MemoryPromotionService(memoryStore);
        this.agent = agent;
        this.model = model;
        applyOptions(agent, model, options);
        this.redactor = SecretRedactor.of(
                Set.copyOf(options.secretEnvNames),
                new java.util.LinkedHashSet<>(options.secretValues),
                options.apiKey == null || options.apiKey.isBlank()
                        ? Set.of() : Set.of(options.apiKey));
        this.planSubmissionHandler = new FilePlanSubmissionHandler(sessionStore, runStore, mapper);
        this.session = openOrCreateSession();
        this.loopService = loopService;
    }

    private AgentSession openOrCreateSession() {
        if (options.resumeSessionId != null) {
            ResumeResult result = resume(sessionId);
            if (result.getKind() == ResumeResult.Kind.SCHEMA_INCOMPATIBLE) {
                throw new OptionsException(result.getMessage());
            }
            if (result.getKind() == ResumeResult.Kind.WORKSPACE_MISMATCH) {
                throw new OptionsException(result.getMessage());
            }
            if (result.getKind() == ResumeResult.Kind.NO_CHECKPOINT) {
                System.out.println("(resume: session found, no semantic checkpoint)");
            }
            AgentSession resumed = recoverPendingPlan(result.getSession());
            if (options.startupMode != null) {
                Instant expectedUpdatedAt = resumed.getUpdatedAt();
                resumed.setCollaborationMode(options.startupMode);
                if (!sessionStore.saveIfUnchanged(resumed, expectedUpdatedAt)) {
                    throw new OptionsException("session changed while applying startup mode");
                }
            }
            return resumed;
        }
        return recoverPendingPlan(createFreshSession(sessionId,
                options.startupMode == null ? CollaborationMode.BUILD : options.startupMode));
    }

    private AgentSession recoverPendingPlan(AgentSession current) {
        AgentSession recovered = planSubmissionHandler.recoverPending(current.getId());
        return recovered == null ? current : recovered;
    }

    private AgentSession createFreshSession(String id, CollaborationMode mode) {
        AgentSession fresh = AgentSession.builder()
                .id(id)
                .schemaVersion(AgentSession.CURRENT_SCHEMA_VERSION)
                .workspaceRoot(workspace)
                .collaborationMode(Objects.requireNonNull(mode, "collaboration mode must not be null"))
                .createdAt(Instant.now())
                .history(new ArrayList<>())
                .workingMemory(new WorkingContextMemory())
                .checkpoint(null)
                .keyFiles(new LinkedHashMap<>())
                .runtimeIdentity(redactor == null ? null : "cli")
                .build();
        return sessionStore.save(fresh);
    }

    /** Create and activate a new durable Session without starting a Run. */
    public synchronized String newSession() {
        sessionId = newSessionId();
        session = createFreshSession(sessionId, session.getCollaborationMode());
        return sessionId;
    }

    /** Resume semantics: restore history + working memory + valid semantic
     *  checkpoint. A new user turn creates a NEW root run; old runs are never
     *  rewritten. Key-file changes invalidate the checkpoint summary. */
    private ResumeResult resume(String id) {
        Optional<AgentSession> loaded;
        try {
            loaded = sessionStore.find(id);
        } catch (IllegalArgumentException e) {
            throw new OptionsException(e.getMessage());
        }
        if (loaded.isEmpty()) {
            throw new OptionsException("session not found: " + id);
        }
        AgentSession s = loaded.get();
        if (!workspace.equals(s.getWorkspaceRoot())) {
            return ResumeResult.builder()
                    .kind(ResumeResult.Kind.WORKSPACE_MISMATCH)
                    .session(s)
                    .message("session " + id + " belongs to workspace "
                            + s.getWorkspaceRoot() + ", refusing to switch to " + workspace)
                    .build();
        }
        TaskCheckpoint checkpoint = s.getCheckpoint();
        if (checkpoint == null) {
            return ResumeResult.builder()
                    .kind(ResumeResult.Kind.NO_CHECKPOINT)
                    .session(s)
                    .workingMemory(s.getWorkingMemory())
                    .message("no semantic checkpoint in session " + id)
                    .build();
        }
        List<String> invalidated = keyFilesInvalidated(checkpoint);
        if (!invalidated.isEmpty()) {
            checkpoint.setSummary(null);
            checkpoint.setKeyFiles(new LinkedHashMap<>());
            s.setCheckpoint(checkpoint);
            sessionStore.save(s);
            return ResumeResult.builder()
                    .kind(ResumeResult.Kind.PARTIAL_RESUME)
                    .session(s)
                    .checkpoint(checkpoint)
                    .workingMemory(s.getWorkingMemory())
                    .invalidatedKeyFiles(invalidated)
                    .message("key files changed: " + String.join(", ", invalidated)
                            + "; stale checkpoint summary discarded")
                    .build();
        }
        return ResumeResult.builder()
                .kind(ResumeResult.Kind.FULL_RESTORE)
                .session(s)
                .checkpoint(checkpoint)
                .workingMemory(s.getWorkingMemory())
                .message("full restore")
                .build();
    }

    private List<String> keyFilesInvalidated(TaskCheckpoint checkpoint) {
        List<String> invalidated = new ArrayList<>();
        Map<String, String> keyFiles = checkpoint.getKeyFiles();
        if (keyFiles == null || keyFiles.isEmpty()) {
            return invalidated;
        }
        for (Map.Entry<String, String> e : keyFiles.entrySet()) {
            String current = sha256(Path.of(workspace).resolve(e.getKey()));
            if (current == null || !current.equals(e.getValue())) {
                invalidated.add(e.getKey());
            }
        }
        return invalidated;
    }

    private static String sha256(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return org.apache.commons.codec.digest.DigestUtils.sha256Hex(Files.readAllBytes(file));
        } catch (Exception e) {
            return null;
        }
    }

    /** Run one turn: new root run seeded from session, persist real state. */
    public String runTurn(String prompt) {
        return runTurn(prompt, null, null, null);
    }

    private String runTurn(String prompt, PlanBinding planBinding,
                           CollaborationMode modeOverride,
                           AgentRunStartGuard runStartGuard) {
        // Redact the user request at the single choke point so no secret
        // configured via --secret-env-name ever reaches the ledger, checkpoint,
        // run.json, trace or report on disk.
        String safePrompt = redactor.redact(prompt);
        AgentContextSnapshot seed;
        CollaborationMode runMode;
        String planTarget;
        Integer planRevision;
        long planStateVersion;
        synchronized (this) {
            seed = seedSnapshot();
            runMode = modeOverride == null ? session.getCollaborationMode() : modeOverride;
            planTarget = planBinding != null ? planBinding.getPlanId()
                    : runMode == CollaborationMode.PLAN ? planTargetForRun() : null;
            planRevision = planBinding != null ? planBinding.getRevision()
                    : runMode == CollaborationMode.PLAN ? planRevisionForRun() : null;
            planStateVersion = planBinding != null ? session.getPlanStateVersion()
                    : runMode == CollaborationMode.PLAN ? session.getPlanStateVersion() : 0L;
        }
        String runId = "run_" + currentStamp() + "-" + UUID.randomUUID().toString().substring(0, 6);

        AgentQuestion question = AgentQuestion.builder()
                .question(safePrompt)
                .runId(runId)
                .sessionId(sessionId)
                .conversationId(sessionId)
                .workspace(workspace)
                .maxSteps(options.maxSteps)
                .approvalPolicy(options.approvalPolicy)
                .collaborationMode(runMode)
                .planTarget(planTarget)
                .planRevision(planRevision)
                .planStateVersion(planStateVersion)
                .planBinding(planBinding)
                .runStartGuard(runStartGuard)
                .seedSnapshot(seed)
                .inheritedSessionExecutionGrants(session.getExecutionGrants())
                .build();

        Map<String, Object> taskState = newTaskState(runId, safePrompt);
        Map<String, Object> report = new LinkedHashMap<>();

        StringBuilder answer = new StringBuilder();
        StringBuilder error = new StringBuilder();
        AgentEvent terminal = null;
        List<AgentEvent> events = loopService.ask(question)
                .collectList()
                .block(Duration.ofMinutes(30));
        if (events != null) {
            for (AgentEvent event : events) {
                if (event.getAnswer() != null && !event.getAnswer().isBlank()) {
                    answer.setLength(0);
                    answer.append(event.getAnswer());
                }
                if (event.getType() == AgentEventType.ERROR && event.getMessage() != null) {
                    error.append(event.getMessage()).append('\n');
                }
                if (event.getType() == AgentEventType.DONE) {
                    terminal = event;
                }
            }
        }

        Optional<AgentRun> finalRun = runStore.find(runId);
        AgentRun run = finalRun.orElse(null);
        String finalAnswer = answer.length() == 0 && error.length() > 0
                ? "error: " + error.toString().strip()
                : answer.length() == 0 ? "(empty answer)" : answer.toString();

        AgentStopReason stopReason = terminal != null && terminal.getStopReason() != null
                ? terminal.getStopReason()
                : (run != null && run.getStopReason() != null
                        ? AgentStopReason.valueOf(run.getStopReason()) : AgentStopReason.MODEL_ERROR);
        String status = run != null ? run.getStatus().name().toLowerCase() : "failed";

        taskState.put("run_id", runId);
        taskState.put("session_id", sessionId);
        taskState.put("status", status);
        taskState.put("stop_reason", stopReason.name());
        taskState.put("final_answer", redactor.redact(finalAnswer));
        taskState.put("tool_steps", run != null ? run.getToolSteps() : 0);
        taskState.put("attempts", run != null ? run.getModelAttempts() : 0);
        taskState.put("parent_run_id", run != null ? run.getParentRunId() : null);
        taskState.put("root_run_id", run != null ? run.getRootRunId() : runId);

        FileRunStore fileRunStore = new FileRunStore(Path.of(workspace), mapper);
        fileRunStore.writeTaskState(runId, taskState);
        fileRunStore.appendTrace(runId, trace("run_finished", Map.of(
                "status", status, "stop_reason", stopReason.name(),
                "final_answer", redactor.redact(finalAnswer))));
        report.put("run_id", runId);
        report.put("session_id", sessionId);
        report.put("status", status);
        report.put("stop_reason", stopReason.name());
        report.put("final_answer", redactor.redact(finalAnswer));
        report.put("tool_steps", run != null ? run.getToolSteps() : 0);
        report.put("model_attempts", run != null ? run.getModelAttempts() : 0);
        report.put("parent_run_id", run != null ? run.getParentRunId() : null);
        report.put("root_run_id", run != null ? run.getRootRunId() : runId);
        report.put("task_state", redactor.redactMap(taskState));
        fileRunStore.writeReport(runId, report);

        // Workspace durable memory: promote only when the user explicitly
        // asked to remember and the final answer is a structured conclusion.
        try {
            memoryPromotion.promote(safePrompt, finalAnswer, runId);
        } catch (Exception ignored) {
            // memory promotion must never break the turn
        }

        persistSession();
        return finalAnswer;
    }

    private AgentContextSnapshot seedSnapshot() {
        if (session.getHistory() == null || session.getHistory().isEmpty()) {
            return null;
        }
        return AgentContextSnapshot.builder()
                .schemaVersion(AgentContextSnapshot.CURRENT_SCHEMA_VERSION)
                .runModeSnapshot(session.getCollaborationMode())
                .ledgerEntries(session.getHistory())
                .ledgerNextSequence(session.getLedgerNextSequence())
                .workingMemory(session.getWorkingMemory())
                .stablePrefix(null)
                .generation(0)
                .build();
    }

    private Map<String, Object> newTaskState(String runId, String userRequest) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("run_id", runId);
        state.put("session_id", sessionId);
        state.put("task_id", "task_" + currentStamp());
        state.put("user_request", redactor.redact(userRequest));
        state.put("status", "running");
        state.put("stop_reason", null);
        state.put("final_answer", null);
        state.put("tool_steps", 0);
        state.put("attempts", 0);
        state.put("resume_status", options.resumeSessionId != null ? "resumed" : "none");
        state.put("tool_calls", new ArrayList<>());
        return state;
    }

    private Map<String, Object> trace(String event, Map<String, Object> payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", event);
        entry.put("created_at", Instant.now().toString());
        entry.putAll(payload);
        return entry;
    }

    /** Persist loop-derived state back into the session. The latest semantic
     *  checkpoint of the newest root run is the single source of truth for
     *  history, working memory and the task anchor. */
    public synchronized void persistSession() {
        recoverPendingIfPresent();
        // Re-read the durable Session before applying loop-derived state. A
        // service instance may have been resumed before another process or
        // CLI instance persisted a newer Session; its stale object must not
        // replace that newer state on close.
        Instant knownUpdatedAt = session.getUpdatedAt();
        List<cn.lunalhx.ai.domain.tool.model.ExecutionGrant> pendingSessionExecutionGrants =
                session.getExecutionGrants() == null ? List.of() : List.copyOf(session.getExecutionGrants());
        Optional<AgentSession> durable = sessionStore.find(sessionId);
        Instant durableUpdatedAt = durable.map(AgentSession::getUpdatedAt).orElse(null);
        boolean durableStateAdvanced = durable
                .map(current -> current.getUpdatedAt() != null
                        && (knownUpdatedAt == null
                        || current.getUpdatedAt().isAfter(knownUpdatedAt)))
                .orElse(false);
        durable.ifPresent(current -> session = current);
        if (!pendingSessionExecutionGrants.isEmpty()) {
            List<cn.lunalhx.ai.domain.tool.model.ExecutionGrant> merged = new ArrayList<>(
                    session.getExecutionGrants() == null ? List.of() : session.getExecutionGrants());
            pendingSessionExecutionGrants.forEach(grant -> { if (!merged.contains(grant)) merged.add(grant); });
            session.setExecutionGrants(merged);
        }
        Optional<AgentRun> latestRoot = runStore.findLatestRootByConversationId(sessionId);
        if (latestRoot.isPresent() && (!durableStateAdvanced
                || committedPlanSubmission(latestRoot.get(), session))) {
            AgentRun run = latestRoot.get();
            session.setId(run.getSessionId() == null ? sessionId : run.getSessionId());
            session.setUpdatedAt(Instant.now());
            syncFromCheckpoint(run);
            session.setCheckpoint(taskCheckpoint(run));
        }
        redactSessionInPlace();
        if (!sessionStore.saveIfUnchanged(session, durableUpdatedAt)) {
            sessionStore.find(sessionId).ifPresent(current -> session = current);
        }
    }

    private boolean committedPlanSubmission(AgentRun run, AgentSession durableSession) {
        return run != null
                && durableSession != null
                && AgentStopReason.PLAN_SUBMITTED.name().equals(run.getStopReason())
                && durableSession.getPlanStateVersion() == run.getPlanStateVersion() + 1;
    }

    /** Redact every persisted text field in the session before it hits disk. */
    private void redactSessionInPlace() {
        if (session.getHistory() != null) {
            List<cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry> redacted = new ArrayList<>();
            for (cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry e : session.getHistory()) {
                redacted.add(cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry.builder()
                        .entryId(e.entryId())
                        .sequence(e.sequence())
                        .role(e.role())
                        .content(redactor.redact(e.content()))
                        .stableType(e.stableType())
                        .eventKey(e.eventKey())
                        .toolName(e.toolName())
                        .toolInputJson(e.toolInputJson() == null ? null : redactor.redact(e.toolInputJson()))
                        .artifactId(e.artifactId())
                        .originalChars(e.originalChars())
                        .renderChars(e.renderChars())
                        .build());
            }
            session.setHistory(redacted);
        }
        WorkingContextMemory wm = session.getWorkingMemory();
        if (wm != null) {
            WorkingContextMemory redacted = new WorkingContextMemory();
            redacted.setTaskSummary(redactor.redact(wm.taskSummary()));
            for (String file : wm.recentFiles()) {
                redacted.recordRecentFile(redactor.redact(file));
            }
            for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
                redacted.putFileSummary(new WorkingContextMemory.FileSummary(
                        redactor.redact(fs.path()), redactor.redact(fs.summary()),
                        fs.createdAt(), fs.sha256()));
            }
            for (WorkingContextMemory.MemoryNote n : wm.notes()) {
                redacted.addNote(new WorkingContextMemory.MemoryNote(
                        redactor.redact(n.text()), n.tags(), redactor.redact(n.source()),
                        n.createdAt(), n.sequence(), n.kind()));
            }
            session.setWorkingMemory(redacted);
        }
        TaskCheckpoint cp = session.getCheckpoint();
        if (cp != null) {
            cp.setGoal(redactor.redact(cp.getGoal()));
            cp.setCompleted(redactor.redact(cp.getCompleted()));
            cp.setExcluded(redactor.redact(cp.getExcluded()));
            cp.setBlocker(redactor.redact(cp.getBlocker()));
            cp.setNextStep(redactor.redact(cp.getNextStep()));
            cp.setSummary(redactor.redact(cp.getSummary()));
        }
    }

    private void syncFromCheckpoint(AgentRun run) {
        Optional<cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint> latest =
                checkpointStore.latest(run.getRunId());
        if (latest.isEmpty()) {
            return;
        }
        AgentContextSnapshot snapshot = latest.get().getContextSnapshot();
        if (snapshot == null) {
            return;
        }
        if (snapshot.getLedgerEntries() != null && !snapshot.getLedgerEntries().isEmpty()) {
            session.setHistory(snapshot.getLedgerEntries());
            session.setLedgerNextSequence(snapshot.getLedgerNextSequence());
        }
        if (snapshot.getWorkingMemory() != null) {
            session.setWorkingMemory(snapshot.getWorkingMemory());
        }
        syncKeyFiles(snapshot);
    }

    private void syncKeyFiles(AgentContextSnapshot snapshot) {
        WorkingContextMemory wm = snapshot.getWorkingMemory();
        Map<String, String> keyFiles = new LinkedHashMap<>();
        if (wm != null && wm.fileSummaries() != null) {
            for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
                if (fs.path() != null && fs.sha256() != null) {
                    keyFiles.put(fs.path(), fs.sha256());
                }
            }
        }
        session.setKeyFiles(keyFiles);
    }

    private TaskCheckpoint taskCheckpoint(AgentRun run) {
        TaskCheckpoint checkpoint = session.getCheckpoint();
        if (checkpoint == null) {
            checkpoint = new TaskCheckpoint();
            checkpoint.setSchemaVersion(TaskCheckpoint.CURRENT_SCHEMA_VERSION);
            checkpoint.setCreatedAt(Instant.now());
        }
        checkpoint.setSessionId(sessionId);
        checkpoint.setRunId(run.getRunId());
        checkpoint.setGoal(run.getQuestion());
        checkpoint.setSummary(StringUtils.abbreviate(
                org.apache.commons.lang3.StringUtils.defaultString(run.getFinalAnswer(),
                        org.apache.commons.lang3.StringUtils.defaultString(run.getStopReason())), 500));
        checkpoint.setNextStep(org.apache.commons.lang3.StringUtils.defaultIfBlank(run.getStopReason(),
                AgentStopReason.FINAL_ANSWER_RETURNED.name()));
        checkpoint.setRuntimeIdentity(run.getRootRunId());
        checkpoint.setRunModeSnapshot(run.getRunModeSnapshot());
        checkpoint.setKeyFiles(session.getKeyFiles());
        checkpoint.setWorkingMemory(session.getWorkingMemory());
        checkpoint.setHistory(session.getHistory());
        checkpoint.setLedgerNextSequence(session.getLedgerNextSequence());
        checkpoint.setUpdatedAt(Instant.now());
        return checkpoint;
    }

    @Override
    public void close() {
        persistSession();
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized CollaborationMode collaborationMode() {
        return session.getCollaborationMode();
    }

    /** Explicit user mode transition; it only changes the durable Session. */
    public synchronized CollaborationMode setCollaborationMode(CollaborationMode mode) {
        if (mode == null) {
            throw new OptionsException("mode must be build or plan");
        }
        AgentSession current = sessionStore.find(sessionId).orElse(session);
        Instant expectedUpdatedAt = current.getUpdatedAt();
        current.setCollaborationMode(mode);
        if (!sessionStore.saveIfUnchanged(current, expectedUpdatedAt)) {
            throw new OptionsException("session changed while updating collaboration mode");
        }
        session = current;
        return mode;
    }

    /** Explicitly start an independent Plan without invoking the model. */
    public synchronized void newPlan() {
        AgentSession current = currentDurableSession();
        Instant expectedUpdatedAt = current.getUpdatedAt();
        current.setCurrentPlanId(null);
        current.setPlanStateVersion(current.getPlanStateVersion() + 1);
        if (!sessionStore.saveIfUnchanged(current, expectedUpdatedAt)) {
            throw new OptionsException("session changed while starting a new Plan");
        }
        session = current;
    }

    /** Select the latest revision of one existing Plan without invoking the model. */
    public synchronized void selectPlan(String planId) {
        if (StringUtils.isBlank(planId)) {
            throw new OptionsException("Plan id must not be blank");
        }
        AgentSession current = currentDurableSession();
        Plan selected = null;
        if (current.getPlans() != null) {
            for (Plan plan : current.getPlans()) {
                if (plan != null && Objects.equals(planId, plan.getPlanId())
                        && plan.currentRevision() != null) {
                    selected = plan;
                    break;
                }
            }
        }
        if (selected == null) {
            throw new OptionsException("unknown Plan: " + planId);
        }
        Instant expectedUpdatedAt = current.getUpdatedAt();
        current.setCurrentPlanId(selected.getPlanId());
        current.setPlanStateVersion(current.getPlanStateVersion() + 1);
        if (!sessionStore.saveIfUnchanged(current, expectedUpdatedAt)) {
            throw new OptionsException("session changed while selecting Plan");
        }
        session = current;
    }

    /**
     * Resolve and validate one exact fresh Plan head, then start a new Build
     * Run with an immutable snapshot of that revision. The validation is
     * repeated against durable state immediately before the Run is created so
     * a concurrent selection or head advance cannot be silently adopted.
     */
    public String handoffPlan(String planId) {
        PlanBinding binding;
        long expectedPlanStateVersion;
        String expectedCurrentPlanId;
        synchronized (this) {
            if (session.getCollaborationMode() != CollaborationMode.BUILD) {
                throw new OptionsException("Plan handoff is available only in Build Mode");
            }
            AgentSession before = currentDurableSession();
            if (before.getCollaborationMode() != CollaborationMode.BUILD) {
                throw new OptionsException("Plan handoff is available only in Build Mode");
            }
            String targetId = StringUtils.defaultIfBlank(planId, before.getCurrentPlanId());
            if (StringUtils.isBlank(targetId)) {
                throw new OptionsException("no Current Plan selected");
            }
            Plan target = findPlan(before, targetId);
            PlanRevision revision = target == null ? null : target.currentRevision();
            if (revision == null) {
                throw new OptionsException("unknown Plan or Plan has no latest revision: " + targetId);
            }
            if (!PlanFreshness.isFresh(Path.of(workspace), revision)) {
                throw new OptionsException("Plan is stale; refresh its Evidence before handoff: " + targetId);
            }
            String basisIdentity = planBasisIdentity(revision);

            AgentSession after = currentDurableSession();
            Plan latestPlan = findPlan(after, targetId);
            PlanRevision latest = latestPlan == null ? null : latestPlan.currentRevision();
            if (after.getCollaborationMode() != CollaborationMode.BUILD
                    || before.getPlanStateVersion() != after.getPlanStateVersion()
                    || latest == null
                    || !sameRevision(revision, latest)
                    || !Objects.equals(after.getCurrentPlanId(), before.getCurrentPlanId())) {
                throw new OptionsException("Plan handoff rejected: Plan head changed concurrently");
            }
            if (!PlanFreshness.isFresh(Path.of(workspace), latest)) {
                throw new OptionsException("Plan is stale; refresh its Evidence before handoff: " + targetId);
            }
            binding = PlanBinding.fromHandoff(targetId, revision.getRevision(),
                    revision.getContentDigest(), basisIdentity, revision.getTitle(),
                    revision.getBody(), revision.getDependencies());
            expectedPlanStateVersion = before.getPlanStateVersion();
            expectedCurrentPlanId = before.getCurrentPlanId();
            session = after;
        }
        return runTurn(binding.authoritativePrompt(), binding, CollaborationMode.BUILD,
                handoffStartGuard(binding, expectedPlanStateVersion, expectedCurrentPlanId));
    }

    /** Read-only Plan index; it never starts a model Run. */
    public synchronized String planListView() {
        List<Plan> plans = session.getPlans();
        if (plans == null || plans.isEmpty()) {
            return "plans:\n  (none)";
        }
        StringBuilder view = new StringBuilder("plans:\n");
        for (Plan plan : plans) {
            PlanRevision revision = plan == null ? null : plan.currentRevision();
            boolean current = plan != null && Objects.equals(plan.getPlanId(), session.getCurrentPlanId());
            boolean fresh = revision != null && PlanFreshness.isFresh(Path.of(workspace), revision);
            view.append("  - ")
                    .append(plan == null ? "(invalid)" : plan.getPlanId())
                    .append(" revision ")
                    .append(revision == null ? "(none)" : revision.getRevision())
                    .append(current ? " [current]" : "")
                    .append(" freshness: ")
                    .append(fresh ? "fresh" : "stale")
                    .append(" title: ")
                    .append(revision == null ? "(none)" : revision.getTitle())
                    .append('\n');
        }
        return view.toString().stripTrailing();
    }

    /** Read-only current Plan detail; it never starts a model Run. */
    public synchronized String planShowView() {
        Plan plan = currentPlan();
        if (plan == null) {
            return "plan: (none)";
        }
        PlanRevision revision = plan.currentRevision();
        if (revision == null) {
            return "plan_id: " + plan.getPlanId() + "\nrevision: (none)";
        }
        boolean fresh = PlanFreshness.isFresh(Path.of(workspace), revision);
        String dependencies = revision.getDependencies() == null
                ? "[]" : revision.getDependencies().toString();
        int basisSize = revision.getPlanBasis() == null ? 0 : revision.getPlanBasis().size();
        StringBuilder view = new StringBuilder("plan_id: " + plan.getPlanId()
                + "\nrevision: " + revision.getRevision()
                + "\ncurrent: " + Objects.equals(plan.getPlanId(), session.getCurrentPlanId())
                + "\ntitle: " + revision.getTitle()
                + "\nbody:\n" + revision.getBody()
                + "\ndependencies: " + dependencies
                + "\ncontent_digest: " + revision.getContentDigest()
                + "\ncreated_at: " + revision.getCreatedAt()
                + "\nupdated_at: " + revision.getUpdatedAt()
                + "\nfreshness: " + (fresh ? "fresh" : "stale")
                + "\nplan_basis_receipts: " + basisSize);
        view.append("\nrevision_history:");
        if (plan.getRevisions() != null) {
            for (PlanRevision historical : plan.getRevisions()) {
                view.append("\n  - revision: ").append(historical.getRevision())
                        .append(" title: ").append(historical.getTitle())
                        .append("\n    body:\n    ")
                        .append(StringUtils.defaultString(historical.getBody()).replace("\n", "\n    "));
            }
        }
        return view.toString();
    }

    private Plan currentPlan() {
        if (session.getPlans() == null || StringUtils.isBlank(session.getCurrentPlanId())) {
            return null;
        }
        for (Plan plan : session.getPlans()) {
            if (plan != null && Objects.equals(plan.getPlanId(), session.getCurrentPlanId())) {
                return plan;
            }
        }
        return null;
    }

    private Plan findPlan(AgentSession source, String planId) {
        if (source == null || source.getPlans() == null || StringUtils.isBlank(planId)) {
            return null;
        }
        for (Plan plan : source.getPlans()) {
            if (plan != null && Objects.equals(planId, plan.getPlanId())) {
                return plan;
            }
        }
        return null;
    }

    private boolean sameRevision(PlanRevision expected, PlanRevision actual) {
        return expected != null && actual != null
                && Objects.equals(expected.getRevision(), actual.getRevision())
                && Objects.equals(expected.getContentDigest(), actual.getContentDigest())
                && Objects.equals(planBasisIdentity(expected), planBasisIdentity(actual));
    }

    private AgentRunStartGuard handoffStartGuard(PlanBinding expectedBinding,
                                                 long expectedPlanStateVersion,
                                                 String expectedCurrentPlanId) {
        return () -> {
            AutoCloseable lease = sessionStore.acquireExclusive(sessionId);
            try {
                AgentSession current = currentDurableSession();
                Plan plan = findPlan(current, expectedBinding.getPlanId());
                PlanRevision latest = plan == null ? null : plan.currentRevision();
                if (current.getCollaborationMode() != CollaborationMode.BUILD
                        || current.getPlanStateVersion() != expectedPlanStateVersion
                        || !Objects.equals(current.getCurrentPlanId(), expectedCurrentPlanId)
                        || !sameBinding(expectedBinding, latest)
                        || !PlanFreshness.isFresh(Path.of(workspace), latest)) {
                    closeQuietly(lease);
                    throw new OptionsException(
                            "Plan handoff rejected: Plan head changed concurrently");
                }
                return lease;
            } catch (RuntimeException e) {
                closeQuietly(lease);
                throw e;
            }
        };
    }

    private boolean sameBinding(PlanBinding expected, PlanRevision actual) {
        return expected != null && actual != null
                && Objects.equals(expected.getRevision(), actual.getRevision())
                && Objects.equals(expected.getPlanDocumentDigest(), actual.getContentDigest())
                && Objects.equals(expected.getTitle(), actual.getTitle())
                && Objects.equals(expected.getBody(), actual.getBody())
                && Objects.equals(expected.getDependencies(), actual.getDependencies())
                && Objects.equals(expected.getPlanBasisIdentity(), planBasisIdentity(actual));
    }

    private String planBasisIdentity(PlanRevision revision) {
        try {
            List<EvidenceReceipt> basis = revision == null || revision.getPlanBasis() == null
                    ? List.of() : revision.getPlanBasis();
            return DigestUtils.sha256Hex(mapper.writeValueAsBytes(basis));
        } catch (Exception e) {
            throw new OptionsException("cannot identify Plan Basis: " + e.getMessage());
        }
    }

    private void closeQuietly(AutoCloseable lease) {
        if (lease == null) {
            return;
        }
        try {
            lease.close();
        } catch (Exception ignored) {
        }
    }

    private void recoverPendingIfPresent() {
        Optional<AgentSession> durable = sessionStore.find(sessionId);
        if (durable.isPresent() && durable.get().getPendingPlanSubmission() != null) {
            session = recoverPendingPlan(durable.get());
        }
    }

    private AgentSession currentDurableSession() {
        return sessionStore.find(sessionId).orElse(session);
    }

    private String planTargetForRun() {
        return StringUtils.defaultIfBlank(session.getCurrentPlanId(), "NEW");
    }

    private Integer planRevisionForRun() {
        Plan plan = currentPlan();
        PlanRevision revision = plan == null ? null : plan.currentRevision();
        return revision == null ? null : revision.getRevision();
    }

    /** Current in-memory session state (tests). */
    AgentSession sessionState() {
        return session;
    }

    AgentRunRepository runRepository() {
        return runStore;
    }

    AgentSessionRepository sessionRepository() {
        return sessionStore;
    }

    /** Working memory + workspace durable memory view for {@code /memory}. */
    public String memoryView() {
        StringBuilder sb = new StringBuilder();
        WorkingContextMemory wm = session.getWorkingMemory();
        sb.append("working memory:\n");
        if (wm == null || wm.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            if (wm.taskSummary() != null) {
                sb.append("  - task: ").append(wm.taskSummary()).append('\n');
            }
            if (!wm.recentFiles().isEmpty()) {
                sb.append("  - recent files: ").append(String.join(", ", wm.recentFiles())).append('\n');
            }
            for (WorkingContextMemory.FileSummary fs : wm.fileSummaries().values()) {
                sb.append("  - file: ").append(fs.path()).append('\n')
                        .append("    summary: ").append(fs.summary()).append('\n');
            }
        }
        sb.append("durable memory (workspace):\n");
        List<cn.lunalhx.ai.domain.memory.model.MemoryEntry> entries = memoryStore.findAllNewestFirst();
        if (entries.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (cn.lunalhx.ai.domain.memory.model.MemoryEntry e : entries) {
                sb.append("  - [").append(e.getTopic()).append("] ")
                        .append(e.getSubject()).append(": ").append(e.getContent()).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    public Path sessionPath() {
        return ((FileAgentSessionRepository) sessionStore).path(sessionId);
    }

    /** Interactive approval prompt; non-interactive environments reject by default. */
    public static final class InteractiveApprovalPrompt implements cn.lunalhx.ai.domain.tool.service.PermissionPrompt {
        private final BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        private final boolean interactive;

        public InteractiveApprovalPrompt(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public cn.lunalhx.ai.domain.tool.model.GrantLifetime ask(
                cn.lunalhx.ai.domain.tool.service.AuthorizationDisplay display,
                cn.lunalhx.ai.domain.tool.model.PermissionDecision decision) {
            if (!interactive) {
                return null;
            }
            System.out.println();
            System.out.println("permission required: " + display.toolName() + " " + display.normalizedSummary());
            System.out.print("allow once/session/workspace? [o/s/w/N] ");
            System.out.flush();
            try {
                String line = reader.readLine();
                if (line == null) return null;
                return switch (line.strip().toLowerCase()) {
                    case "o", "once" -> cn.lunalhx.ai.domain.tool.model.GrantLifetime.ONCE;
                    case "s", "session" -> cn.lunalhx.ai.domain.tool.model.GrantLifetime.SESSION;
                    case "w", "workspace" -> cn.lunalhx.ai.domain.tool.model.GrantLifetime.WORKSPACE;
                    default -> null;
                };
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public cn.lunalhx.ai.domain.tool.model.GrantLifetime askExecutionGrant(
                cn.lunalhx.ai.domain.tool.model.ExecutionGrantRequest request) {
            if (!interactive) return null;
            System.out.println();
            System.out.println("external filesystem access required: " + request.access().name().toLowerCase()
                    + " " + request.canonicalPath());
            System.out.print("allow once/session/workspace? [o/s/w/N] ");
            System.out.flush();
            try {
                String line = reader.readLine();
                if (line == null) return null;
                return switch (line.strip().toLowerCase()) {
                    case "o", "once" -> cn.lunalhx.ai.domain.tool.model.GrantLifetime.ONCE;
                    case "s", "session" -> cn.lunalhx.ai.domain.tool.model.GrantLifetime.SESSION;
                    case "w", "workspace" -> cn.lunalhx.ai.domain.tool.model.GrantLifetime.WORKSPACE;
                    default -> null;
                };
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static String currentStamp() {
        return Instant.now().toString()
                .replace(":", "").replace("-", "").replace(".", "").substring(0, 15);
    }

    private static String newSessionId() {
        return "session-" + currentStamp() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void applyOptions(AgentRuntimeProperties agent,
                                     ModelRuntimeProperties model,
                                     CliOptions options) {
        agent.setWorkspaceRoot(options.workspaceRoot);
        agent.setAllowedWorkspaceRoots(List.of(options.workspaceRoot));
        agent.setMaxSteps(options.maxSteps);
        agent.setApprovalPolicy(options.approvalPolicy);
        agent.getCore().setSecretEnvNames(new ArrayList<>(options.secretEnvNames));

        model.setProvider(options.provider);
        model.setDefaultModel(options.model);
        model.setAllowedModels(List.of(options.model));
        ModelRuntimeProperties.ProviderConfig cfg = new ModelRuntimeProperties.ProviderConfig();
        cfg.setBaseUrl(options.baseUrl);
        cfg.setApiKey(options.apiKey);
        cfg.setDefaultModel(options.model);
        cfg.setTemperature(options.temperature);
        cfg.setMaxTokens(options.maxNewTokens);
        cfg.setTopP(options.topP);
        cfg.setTimeoutSeconds(options.timeoutSeconds);
        model.getProviders().put(options.provider, cfg);
    }

    public static class OptionsException extends RuntimeException {
        public OptionsException(String message) {
            super(message);
        }
    }

    /** Resolved, validated runtime options derived from {@link CliArguments}. */
    public static final class CliOptions {
        public String provider;
        public String model;
        public String baseUrl;
        public String apiKey;
        public String workspaceRoot;
        public String approvalPolicy = "ask";
        /** Explicit startup selection; null preserves a resumed Session mode. */
        public CollaborationMode startupMode;
        public int maxSteps = 6;
        public int maxNewTokens = 512;
        public double temperature = 0.2;
        public double topP = 0.9;
        public long timeoutSeconds = 300;
        public String resumeSessionId;
        public final List<String> secretEnvNames = new ArrayList<>();
        public final List<String> secretValues = new ArrayList<>();
        public cn.lunalhx.ai.domain.tool.service.PermissionPrompt approvalPrompt;
        public ModelGateway modelGateway;
    }
}
