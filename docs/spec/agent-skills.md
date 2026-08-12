# Agent Skills capability

## Problem Statement

Loom Agent currently cannot discover or activate reusable Agent Skills. Users who already maintain a Skill package must repeat its instructions in each request, manually expose supporting references, and recreate the same working method across projects. Existing Skills stored for other mature Agents also cannot be reused without copying their content into the conversation.

Users need Loom Agent to discover standard Skill packages, select relevant instructions only for the current Run, and access their supporting resources on demand. This capability must preserve Loom Agent's existing separation between model guidance and Runtime authority: activating a Skill must not grant Tool permissions, widen the Execution Profile, bypass Plan Mode, or execute packaged scripts automatically.

## Solution

Add Run-scoped Agent Skills based on the open Agent Skills package format. At the start of each root Run, Runtime discovers and validates configured user and project Skill sources, resolves duplicate names deterministically, retains source provenance, and freezes a Skill Catalog Snapshot. Only metadata needed for discovery is initially model-visible.

Users can explicitly select Skills with `$skill-name`, while the model can select relevant Skills through a dedicated non-terminal Skill Activation protocol action. Both paths produce the same immutable Active Skill Snapshot and add the complete Skill instructions to a lower-priority system-prompt section for the current Run only. Skill Activation is not a Tool Call, has no approval of its own, and never changes Tool authorization.

Supporting files remain ordinary untrusted data. The model can read a resource belonging to an active Skill through a bounded `read_skill_resource` Tool. Any Tool Call suggested by a Skill, including a Shell call that executes a packaged script, follows the existing Runtime Gate, Permission Policy, Effect Profile, Execution Profile, and grant rules exactly as if no Skill had been involved.

## User Stories

1. As a Loom Agent user, I want to reuse standard Agent Skills, so that I do not have to repeat established working instructions in every request.
2. As a Loom Agent user, I want Skills discovered from my user-level configuration, so that my personal workflows are available across workspaces.
3. As a repository contributor, I want project Skills discovered from the Workspace, so that a team can distribute shared working guidance with its code.
4. As a Claude Code user, I want Loom Agent to discover my existing `.claude/skills` packages, so that I can reuse their portable content without moving directories.
5. As a Loom Agent user, I want `.agents/skills` to remain the canonical Loom source, so that product-specific compatibility does not define the native package location.
6. As a Loom Agent user, I want Skill source provenance retained, so that I can tell whether an active definition came from user or project configuration and from `.agents` or `.claude`.
7. As a Loom Agent user, I want deterministic same-name precedence, so that Skill selection never depends on filesystem enumeration order.
8. As a Loom Agent user, I want my user Skill to take precedence over a project Skill with the same name, so that a repository cannot silently replace my personal workflow.
9. As a Loom Agent user, I want `.agents` to take precedence over `.claude` within the same scope, so that Loom's canonical source wins over its compatibility source.
10. As a Loom Agent user, I want `/skills` to show effective Skills and their sources, so that I can understand what the current Workspace makes available.
11. As a Loom Agent user, I want `/skills` to report shadowing, validation, and compatibility diagnostics, so that unavailable or partially compatible packages are understandable.
12. As a Loom Agent user, I want `/skills` to avoid starting a Run, so that inspecting configuration does not call the model or consume a task execution.
13. As a Loom Agent user, I want to select a Skill explicitly with `$skill-name`, so that I can require a known workflow for one request.
14. As a Loom Agent user, I want explicit Skill selection resolved before the first model call, so that the selected instructions guide the complete Run.
15. As a Loom Agent user, I want the model to discover Skills from their names and descriptions, so that relevant workflows can be activated without manual selection.
16. As a Loom Agent user, I want implicit selection to use a dedicated Skill Activation action, so that instruction activation is distinguishable from Tool execution.
17. As a Loom Agent user, I want explicit and implicit selection to produce the same Active Skill Snapshot, so that selection origin does not create different trust or permission semantics.
18. As a Loom Agent user, I want duplicate activation within one Run to avoid duplicating instructions, so that context is not wasted by repeated selection.
19. As a Loom Agent user, I want Skill Activation to require no approval, so that loading guidance is as lightweight as it is in mature Agents.
20. As a Loom Agent user, I want every Skill-guided Tool Call to follow my existing Tool policy, so that `ALLOW`, `ASK`, and `DENY` behave consistently regardless of why the model chose the Tool.
21. As a Loom Agent user, I want Tool `ALLOW` to execute an otherwise authorized Skill-guided call without another Skill-specific prompt, so that permissions are not duplicated.
22. As a Loom Agent user, I want Skill metadata and instructions unable to add Tools or grants, so that a package cannot enlarge Runtime authority.
23. As a Loom Agent user, I want Skill instructions unable to relax the Execution Profile, Plan Mode, explicit denials, or the Built-in Safety Floor, so that existing hard boundaries remain authoritative.
24. As a Loom Agent user, I want active Skill instructions limited to the current Run, so that unrelated requests in the same Session do not retain irrelevant guidance.
25. As a Loom Agent user, I want every new Run to rematch and reassemble Skills, so that context contains only instructions relevant to that request.
26. As a Loom Agent user, I want a Run to use an immutable Skill Catalog Snapshot, so that mid-Run filesystem changes cannot alter which definition an activation resolves to.
27. As a Loom Agent user, I want a successfully activated Skill body included completely, so that silent instruction truncation cannot change its meaning.
28. As a Loom Agent user, I want Skill instructions placed below Runtime rules in prompt priority, so that package text cannot replace the Agent protocol or security rules.
29. As a Loom Agent user, I want ordinary Tool output to remain untrusted even after Skills are added, so that Tool data cannot become a hidden instruction-promotion channel.
30. As a Loom Agent user, I want Skill activation state recoverable within the same Run, so that checkpoint resume preserves the exact instructions already selected.
31. As a Loom Agent user, I want to read supporting Skill references on demand, so that large packages do not occupy context before their resources are needed.
32. As a Loom Agent user, I want `read_skill_resource` confined to resources of an active Skill, so that it cannot become an arbitrary host-file reader.
33. As a Loom Agent user, I want Skill resource results treated as untrusted Tool data, so that references and assets cannot silently alter Runtime rules.
34. As a Loom Agent user, I want packaged scripts never to execute during discovery or activation, so that loading instructions has no hidden side effects.
35. As a Loom Agent user, I want execution of a packaged script to use an ordinary Shell Tool Call, so that existing command policy and sandbox behavior remain visible and enforceable.
36. As a Loom Agent user, I want project Skill scripts treated as normal Workspace Shell inputs, so that they follow the same rules as other repository scripts.
37. As a Loom Agent user, I want user-level Skill scripts outside the Workspace to require the existing Execution Request and Execution Grant flow, so that Tool `ALLOW` does not imply arbitrary host access.
38. As a Loom Agent user, I want Full Access to retain its existing meaning for Skill-guided Shell calls, so that Skills do not introduce a second host-access mode.
39. As a Loom Agent user, I want Delegate Runs to inherit the root Run's frozen Skill Catalog Snapshot, so that one task tree resolves the same package definitions.
40. As a Loom Agent user, I want a Delegate to inherit Skills active in its parent, so that an explicitly selected working method is not lost during delegation.
41. As a Loom Agent user, I want a Delegate to activate additional relevant Skills from the inherited Catalog, so that a specialized subtask can load the guidance it needs.
42. As a Loom Agent user, I want Delegate-only activation to remain local to that Delegate and its descendants, so that a child cannot modify its parent's prompt state.
43. As a Loom Agent user, I want Skill inheritance unable to widen Delegate capabilities, so that the existing parent-permission-and-child-limit intersection remains intact.
44. As a Skill author, I want valid `name` and `description` metadata required, so that discovery and implicit matching have reliable identities and intent.
45. As a Skill author, I want standard `license`, `compatibility`, and `metadata` retained, so that portable package information is not discarded.
46. As a Claude Skill author, I want `disable-model-invocation` respected, so that a manual-only workflow cannot be selected implicitly by Loom Agent.
47. As a Claude Skill author, I want `user-invocable: false` respected, so that model-only background guidance is not exposed through `$skill-name`.
48. As a Loom Agent user, I want unsupported Claude-specific fields diagnosed rather than granted partial semantics, so that compatibility limits are explicit.
49. As a Loom Agent user, I want `allowed-tools` and `disallowed-tools` unable to alter Loom Tool authorization, so that package metadata cannot preauthorize or reconfigure Tools.
50. As a Loom Agent user, I want Claude-specific execution topology, argument substitution, and dynamic command injection disabled, so that compatibility does not introduce hidden execution behavior.

## Implementation Decisions

- Agent Skills are modeled as distinct domain concepts: Skill Source, Skill Package, Effective Skill Descriptor, Skill Catalog Snapshot, Skill Invocation, Skill Activation, Active Skill Snapshot, Skill Resource Observation, Skill Script Execution, and Skill Inheritance.
- Filesystem discovery and resource access are infrastructure concerns behind domain-facing ports. Runtime orchestration, prompt composition, activation state, and authority rules remain in the domain/application layers consistent with the existing module boundaries.
- Each root Run discovers user `~/.agents/skills`, user `~/.claude/skills`, project `<workspace>/.agents/skills`, and project `<workspace>/.claude/skills` before model use.
- `.agents/skills` is the canonical Loom source. `.claude/skills` supplies directory and portable-package compatibility only; it does not imply Claude Runtime compatibility. The obsolete `~/.loom-agent/skills` path is not supported.
- Same-name precedence is `user .agents > user .claude > project .agents > project .claude`. Only the winning Effective Skill Descriptor is activatable. Catalog display, diagnostics, activation trace, and audit retain accurate winning and shadowed source information.
- Skill packages use the Agent Skills directory format with required `SKILL.md`, valid `name`, and non-empty `description`; standard `license`, `compatibility`, and `metadata` are supported. Optional `references/`, `assets/`, and `scripts/` remain supporting resources.
- Claude compatibility additionally honors `disable-model-invocation: true` and `user-invocable: false` because these fields only restrict who may select a Skill.
- Claude-specific `allowed-tools`, `disallowed-tools`, execution context/agent/model selection, argument substitution, and dynamic command injection do not take effect. Unsupported semantics produce compatibility diagnostics.
- The root Run freezes an immutable Skill Catalog Snapshot containing effective metadata, provenance, content identity, invocation restrictions, and diagnostics. A new root Run rebuilds the Catalog from current sources; active Skill state is not stored as Session state.
- `/skills` is a control-plane catalog command that does not create a Run or call the model. It reports effective Skills, source provenance, invocation direction, shadowing, validation, and compatibility diagnostics.
- `$skill-name` is the explicit invocation syntax. Runtime resolves explicit selections before the first model call, deduplicates them, and activates only definitions that permit user invocation.
- Model-implicit selection uses Skill names, descriptions, and provenance from the Skill Catalog Snapshot, then emits a dedicated non-terminal Skill Activation protocol action. Skill Activation is not registered as a normal `load_skill` Tool and does not consume a Tool step.
- Explicit and implicit Skill Invocation converge on one activation service and produce identical authority, validation, snapshot, and prompt behavior.
- Runtime accepts activation only for an Effective Skill Descriptor in the current Catalog, resolves it against its frozen content identity, and atomically creates an Active Skill Snapshot. The snapshot contains the full instruction body, source provenance, content identity, and bounded resource identity needed for deterministic within-Run use and resume.
- Active Skill instructions are rendered as a separately delimited, lower-priority system-prompt section. Base Runtime identity, protocol, collaboration mode, Tool authorization, and security rules retain priority. Ordinary ToolResult content remains untrusted and cannot trigger instruction promotion.
- Active Skill bodies are admitted whole or activation fails; they are never silently clipped. Active state lasts through the current root Run task tree and is discarded when that Run completes.
- Skill Activation has no Permission Decision and no Tool Approval. It cannot add to the Effective Tool Catalog, create Permission Grants or Execution Grants, alter Permission Policy, widen the Execution Profile, enable Full Access, relax Plan Mode, or bypass the Built-in Safety Floor.
- A Tool Call proposed while following a Skill is indistinguishable for authorization purposes from any other model-originated Tool Call. The existing Runtime Gate alone produces `ALLOW`, `ASK`, or `DENY`, and existing hard capability boundaries remain in force.
- `read_skill_resource` is an ordinary bounded Tool. It reads only a normalized relative resource belonging to an Active Skill Snapshot and returns untrusted Tool data under the normal Effect, Permission Policy, Execution Profile, trace, and output-size rules.
- Skill discovery and activation never execute package content. `scripts/` is an organizational convention, not an execution grant. Any execution uses an independent ordinary Shell Tool Call.
- Project Skill scripts are ordinary Workspace inputs. Direct Shell access to a user Skill script outside the Workspace requires a matching existing Execution Grant, an approved minimal Execution Request, or Full Access. Activation does not mount, copy, or stage user Skill code into the sandbox.
- Delegate Runs inherit the root Run's Skill Catalog Snapshot and the Active Skill Snapshots present in their parent when delegated. A Delegate can activate additional Skills from the inherited Catalog for itself and descendants; child activation never propagates to its parent.
- Skill inheritance and activation never widen a Delegate's stricter Effective Tool Catalog, Permission Policy, or Execution Profile.

## Testing Decisions

- Use the existing application-level `CliSessionService` E2E seam as the single primary behavioral seam. Tests drive a complete Run in a temporary Workspace with deterministic model responses and assert user-visible CLI output, prompts received by the model, durable Session/Run state, Tool behavior, Delegate behavior, and workspace effects rather than private implementation methods.
- Through that seam, verify discovery across the four accepted sources, deterministic precedence, retained provenance, `/skills` no-Run behavior, compatibility diagnostics, explicit `$skill-name` activation, implicit activation, Run-scoped lifetime, complete prompt composition, activation without approval, unchanged Tool authorization, resource reads, checkpoint resume, and Delegate inheritance/local activation.
- Through the same seam, verify that a Skill-guided Tool Call follows existing `ALLOW`, `ASK`, and `DENY` behavior and that Tool `ALLOW` does not itself grant out-of-Workspace Shell access.
- Add focused native contract tests only where a high-level CLI assertion cannot reliably prove the security invariant: YAML/frontmatter validation, canonical relative-path and symlink containment, resource content-identity drift, exact Skill Activation protocol parsing and precedence, whole-body context-budget admission, and immutable snapshot serialization.
- Extend the existing prompt-prefix tests only for deterministic catalog/active-instruction rendering, priority delimiters, complete-body admission, and signature changes; do not duplicate full Run behavior at that lower seam.
- Extend the existing decision-protocol tests only for Skill Activation parsing, malformed/fabricated actions, and coexistence rules with Tool, Final Answer, Plan Submission, and Plan Deviation actions.
- Extend the existing Shell permission and Execution Grant tests only to prove that user-level Skill script paths remain outside the ordinary Workspace profile and require the already defined grant flow.
- Tests must continue asserting the invariant that Tool output is untrusted and cannot activate a Skill or change Runtime rules.

## Out of Scope

- Skill creation, installation, update, removal, copying into a project, marketplace distribution, or package management.
- A Skill-specific script runner or automatic execution of any command during discovery or activation.
- Session-sticky Skill activation, manual deactivation state, or carrying active Skill instructions into unrelated root Runs.
- Live or hot reloading of a frozen Skill Catalog Snapshot or Active Skill Snapshot during a Run.
- Backward compatibility with the removed `~/.loom-agent/skills` source or the obsolete historical Skill implementation.
- Registering `load_skill` as an ordinary Tool or treating Tool output as trusted instructions.
- Dynamic `/skill-name` control commands; explicit invocation uses `$skill-name`, and `/skills` is reserved for catalog inspection.
- Authority-bearing `allowed-tools` or `disallowed-tools` semantics.
- Claude-specific execution topology, model selection, argument rendering/substitution, environment-variable substitution, or dynamic command injection.
- Automatic Workspace trust prompts or Skill-specific approval policy. Skill Activation itself remains approval-free, while ordinary Tool and Execution Grant decisions remain authoritative.

## Further Notes

- Exact numeric limits for catalog metadata, active instruction bodies, resource counts, and resource sizes remain implementation-planning details. The fixed behavioral requirement is deterministic bounded operation, diagnostics on omitted catalog entries, and whole-body admission or rejection for active instructions.
- The exact XML field encoding of the dedicated Skill Activation action remains an implementation-planning detail; the fixed architecture decision is that it is a dedicated non-terminal Runtime control action rather than a Tool Call.
- Binary asset presentation, diagnostic CLI formatting, and internal class/package decomposition remain implementation-planning details.
- The architecture decision to preserve untrusted ToolResult semantics and introduce a dedicated Skill Activation control action should be recorded in an ADR during implementation planning.
- The domain vocabulary and accepted invariants are recorded in the project glossary.
