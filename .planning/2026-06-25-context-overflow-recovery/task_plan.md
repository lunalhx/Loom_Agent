# Context Overflow Recovery

## Goal

Implement a bounded recovery state machine for context overflow: one reactive compact, larger-context model fallback, deep summary restart, then recoverable user input for root agents or explicit context overflow failure for child agents.

## Success Criteria

- Recovery stages persist through checkpoints and never loop backward.
- Reactive compact retains the user task plus the latest five dynamic entries and reports whether the target token budget is met.
- Provider and local preflight overflows enter the same recovery path.
- Deep summary is chunked, budget/deadline aware, and has a deterministic fallback.
- Root agents can pause in `WAITING_USER_INPUT` and resume with user text or abort.
- Child agents fail with `CONTEXT_OVERFLOW` instead of asking the user.
- Existing tests remain green and new recovery tests pass.

## Phases

### Phase 1: confirm interfaces and add recovery/domain types

**Status:** complete

### Phase 2: implement compact sizing and deep summary

**Status:** complete

### Phase 3: implement bounded model recovery state machine

**Status:** complete

### Phase 4: implement user-input pause/resume API

**Status:** complete

### Phase 5: configuration, tests, and full verification

**Status:** complete

## Errors Encountered

| Error | Attempt | Resolution |
|---|---:|---|
| Method-level Surefire selector ran no tests | 1 | Use class-level selector with compatibility flags |
| Reactor modules rejected class selector without mixed-version flags | 2 | Use both `surefire.failIfNoSpecifiedTests=false` and `failIfNoTests=false` |
| Domain compile stopped on new `AgentLoopService` method | 1 | Expected sequencing issue; implement `DefaultAgentLoopService#resumeWithUserInput` before the next compile |
| Large test patch missed a non-existent import anchor | 1 | Split the test change into exact, smaller patches |
| `check-complete.sh` reported 0/0 phases | 1 | Converted the phase list to the helper's heading-plus-status format |

## Completion

Status: complete
