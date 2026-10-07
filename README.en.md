# z-wf — A Self-Developed Workflow Engine

> A BPMN workflow engine with **no third-party engine underneath** — no Camunda, no Flowable,
> no Activiti. The definition layer shares its protocol with `z-util-wf-kernel`; the runtime
> layer (token execution tree, gateway evaluation, persistence, REST) is written from scratch.

> **[中文文档](README.md) · [能力盘点 / Capability Gap Analysis](docs/capability-gap.md)**

`z-camuda` wraps Camunda 7 in our REST shape. `z-wf` replaces the engine itself.
The module layout is identical (`core` / `web` / `starter` / `admin`), so the integration
surface carries over — but the semantics underneath are different.

---

## Coordinates

```xml
<dependency>
  <groupId>io.github.yuku123</groupId>
  <artifactId>z-wf-starter</artifactId>
  <version>2.0.0</version>
</dependency>
```

### Why this artifact is at 2.0.0

Versions `1.0.4` / `1.0.5` / `1.0.6` of `io.github.yuku123:z-wf` were already published by
the **Camunda-based** predecessor (it was originally named `z-wf` and was renamed to
`z-camuda` later). Renaming a project does not release its published coordinates.

This repository keeps the coordinate and starts at `2.0.0`, so the boundary is explicit:
**1.x = the Camunda line, 2.x = the from-scratch line.** They are not API-compatible.

---

## Modules

| Module | What it is |
|---|---|
| `z-wf-core` | Engine, definition layer, persistence SPI. No Spring dependency in the engine itself. |
| `z-wf-web` | 34 REST endpoints plus a VO boundary that keeps persistence entities off the wire. |
| `z-wf-starter` | Spring Boot auto-configuration. Optional `z-config` / `z-rpc` integration. |
| `z-wf-admin` | Standalone runnable app with a management UI. **Never published to Maven Central.** |

---

## Definition Layer

BPMN 2.0 XML and a JSON dialect, both parsed by hand-written parsers. The `zifang:*`
extension namespace carries approval semantics that standard BPMN has no slot for:

```xml
<userTask id="approve" name="Manager approval"
          zifang:assignee="${leaderId}"
          zifang:category="Approval"
          zifang:formKey="leaveForm"
          zifang:dueDate="PT24H"/>
```

### Node types

`startEvent` · `endEvent` · `userTask` · `serviceTask` · `scriptTask` · `manualTask` ·
`sendTask` · `receiveTask` · `intermediateThrowEvent` · `task` ·
`exclusiveGateway` · `parallelGateway` · `inclusiveGateway` · `complexGateway` ·
`eventBasedGateway` · `intermediateCatchEvent` ·
`linkThrowEvent` · `linkCatchEvent` · `businessRuleTask` ·
`subProcess` · `callActivity` · `boundaryEvent`

**Unsupported BPMN elements fail loudly at deploy time.** Elements the engine does not
implement (`transaction`, `adHocSubProcess`, `dataObject`, `compensationEventDefinition`, …)
are parsed permissively so the file can be read at all, but the resulting node is tagged with
its original element name and the validator reports an **ERROR**, which blocks `deploy`.
Escalation is the interesting case: `escalationEventDefinition` **is** implemented for throw
and boundary events, and the *catch* form is rejected by its own dedicated ERROR (see
"Escalation events" below) — it is not lumped into this generic list, because lumping it
would tell an author their escalation is unknown when in fact the throw and boundary halves
work today.

This is deliberate. Silently degrading an event-based gateway to a plain task is not a loss of
precision — it replaces an automatic event race with "create a task and wait for a human",
while the definition still deploys cleanly and leaves a `completed` activity record. An
author and the running process would disagree with no signal at all.

You can override the inferred type per element with `zifang:type`; an explicit override is
respected and not blocked.

### Link events (skip a whole section of the diagram)

`linkThrowEvent` redirects the token to the `linkCatchEvent` with the same `@name`; from there
it continues along **the catch's own** outgoing flow. The throw's outgoing flows are never
followed — note this is the opposite of escalation, whose outgoing flows *are* taken, so the two
must not be implemented by copying each other.

```xml
<linkThrowEvent id="jump" name="toArchive"/>
<linkCatchEvent id="landing" name="toArchive"/>
```

A link catch has **no incoming flow** by design — that is what makes it a jump target. It is
also not treated as a process entry point, otherwise every definition that uses links without
writing a `startEvent` would suddenly report "multiple unconditional start nodes". Pairing is
scoped to **one process definition** rather than matched engine-wide, so deleting another
deployed process can never silently break this one.

### Business rule task (evaluate a DMN decision table)

`businessRuleTask` evaluates an already deployed decision table. Unlike `serviceTask` it needs
**no code from the caller**, and unlike `scriptTask` the rules live in their **own deployment** —
changing a rule does not mean redeploying the process.

```xml
<businessRuleTask id="brt" name="decide level"
    zifang:decisionRef="approvalLevel"
    zifang:resultVariable="level"
    zifang:mapDecisionResult="singleEntry"/>
```

`camunda:decisionRef` / `camunda:resultVariable` are read as well, so a model exported from
Camunda deploys unchanged.

- **`resultVariable` is mandatory** (deployment-time ERROR). This implementation has **no other
  outlet** for the decision result (Camunda additionally offers a `decisionResult` local variable
  plus output mapping). Without it the node does nothing at all: the flow still runs through it
  and nothing is ever reported.
- **Four `mapDecisionResult` mappers**, named as in Camunda because they describe the **shape** of
  the result: `singleEntry` (the single value) / `singleResult` (the one row as a map) /
  `collectEntries` (the single output of each row) / `resultList` (default, every row).
  A mapper that does not fit the result **raises an error instead of taking the first row** —
  taking the first would let the flow continue on a value that looks fine but depends on match
  order, so the same case could evaluate differently twice.
- **`decisionRef` may be an expression** (`${...}`), evaluated when the node executes.
  A bare string is always a literal key — evaluating it would read it as a variable name, and
  undefined variables are fail-closed here, so every decision would turn into "decision null
  does not exist".
- **`decisionRefBinding` supports `latest` (default) and `version`** (with `decisionRefVersion`).
  Camunda's `deployment` and `versionTag` are **rejected explicitly**: processes and decisions are
  deployed separately here (two services, two REST endpoints), so there is no shared deployment
  unit, and `ZWF_DECISION` carries version numbers only, no tags.
- **Deployment does not check whether the decision is deployed** — the two are independent
  deployment paths and either order is fine.

Deploy and evaluate decisions through `POST /api/wf/decisions/deploy` and
`POST /api/wf/decisions/{key}/evaluate`.

### Multi-instance (countersign / any-one / 2-of-3)

The default requirement for approval systems. Task-like nodes accept
`multiInstanceLoopCharacteristics`:

```xml
<userTask id="counterSign" zifang:assignee="${loopAssignee}"
          zifang:loopAssignees="${approvers}">
  <multiInstanceLoopCharacteristics>
    <loopCardinality>3</loopCardinality>
    <completionCondition>${nrOfCompletedInstances >= 2}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

| Configuration | Meaning |
|---|---|
| no `completionCondition` | **countersign** — all three must approve |
| `${nrOfCompletedInstances >= 1}` | **any-one** — the first approval releases |
| `${nrOfCompletedInstances >= 2}` | **2-of-3** |

Three things worth knowing:

- **Instance counts are derived from the tasks, never stored.** A task *is* an
  instance, so the count cannot drift against `terminate`, `force-complete`, or
  concurrent edits
- Per-instance assignees come from `zifang:loopAssignees` + `${loopAssignee}`,
  **not** `${approvers[loopCounter]}` — the EL does not support variable
  subscripts (`${approvers[1]}` works, `${approvers[loopCounter]}` throws)
- A typo'd variable in `completionCondition` evaluates fail-closed, which in
  countersign means "keep waiting" — the process would hang forever with no
  error. The validator therefore requires the condition to reference a standard
  loop variable

Not supported, and rejected at deploy time rather than half-implemented:
`collection` iteration and `isSequential`.

### Error boundary events

```java
runtimeService.handleBpmnError(taskId, "APPROVAL_FAILED", "no record found", vars);
// or throw new BpmnError("APPROVAL_FAILED", "...") from a delegate
```

A matching boundary event moves the token to its outgoing flow (the compensation
branch). No match terminates the process with the error code in `deleteReason` —
never silently continuing.

BPMN's "empty errorRef catches everything" is deliberately **rejected**: a broad
catch swallows unrelated exceptions and lets a process that should have died keep
running. You must name the error you intend to catch.

### Escalation events

```java
runtimeService.escalate("overdue", "system", "no decision in time, escalating");
```

An escalation looks almost exactly like a signal — both broadcast, both match by name —
but the consequence is the opposite. A signal wakes one branch; an escalation
**interrupts the host** (its open task is cancelled and the token moves onto the boundary).
They are therefore **two separate job types behind two separate entry points**, not one
entry point with a boolean switch, so a caller reading its own code can tell whether it
is notifying or interrupting.

**Who picks it up next is decided by the boundary's outgoing node**, not by the engine —
Camunda's `escalationConfig` layer does not exist here, and claiming otherwise would be false.
`cancelActivity="false"` gives the non-interrupting form: the host task stays open and a
parallel escalation branch starts. **Zero subscribers is not an error** (same convention as
throwing a signal), but it is recorded in the process comments.

Rejected at deploy time:

- Alongside `messageEventDefinition` / `signalEventDefinition` /
  `timerEventDefinition` ⇒ ERROR. **The timer case matters most**: the timer check only
  reads the `timerType` field while an escalation runs down the subscription branch, so the
  same node gets two different verdicts and mixing them yields a timer that neither fires
  nor reports anything.
- As an intermediate catch event ⇒ ERROR. What is missing there is the ability to grow a
  **second** token out of one already parked on the node; all three existing catch kinds
  *move* the parked token instead, so bolting one on yields "the parked token was moved and
  no second one appeared" — wrong semantics that still runs. **Escalation throw and boundary
  events are supported**; only the catch half is missing.
- Camunda's `escalationTimer` (escalate automatically when due) is not supported — that is
  the "escalate after N days" spelling at the top of this section.

---

## Runtime

Tokens move through a hand-written execution tree. `enter` and `leave` are strictly
separated; parallel gateways fork independent tokens; joins skip the moving token when
testing arrival; any token mutated during a step is re-persisted.

### Condition evaluation is fail-closed

An expression referencing an **undefined variable evaluates to false**, and the default
flow takes over. It does not degrade to `0` or `null` in the comparison.

This is an approval-safety red line. Treating "amount unknown" as "amount 0" sends a
high-value request down the low-value branch silently, with nothing logged and nothing to
replay. The escape hatch `z.wf.fail-open` defaults to `false`; opening it removes this
protection on purpose.

### Message and signal wake-up

`receiveTask` parks its token until a message arrives:

```java
// point-to-point — fails loudly if the message name matches more than one task
runtimeService.triggerMessage("orderPaid", processInstanceId, "system", vars, comment);

// broadcast — wakes every subscriber
runtimeService.broadcastSignal("orderPaid", "system", vars, comment);
```

Two methods rather than a boolean flag, because "I thought I woke one" should fail to
compile, not fail silently in production.

### Gateway join semantics: the three kinds differ

| Gateway | What happens to arriving tokens | Basis |
|---|---|---|
| **Exclusive** `exclusiveGateway` | **Each passes through on its own** — no merge | Camunda: "a joining gateway has a pass-through semantic" |
| **Parallel** `parallelGateway` | Wait for all, then merge into one | BPMN 2.0 |
| **Inclusive** `inclusiveGateway` | Wait for the incoming flows that actually activated, then merge | BPMN 2.0 |
| **Complex** `complexGateway` | Configurable: `joining` (default) / `competing` (pass-through) | Camunda leaves this to the implementation |

```xml
<complexGateway id="g" zifang:complexJoin="competing"/>
```

> ⚠️ **Behaviour change (round 27)**: `isJoin` previously looked only at
> "more than one incoming flow from more than one source" and **never at the node
> type**, so an *exclusive* gateway merged parallel tokens too. The symptom was a
> model imported from Camunda silently losing one parallel branch — with "legal
> review" and "finance review" finishing in parallel and passing through an exclusive
> gateway, this engine created **one** task where Camunda creates two. It now matches
> Camunda: **models that relied on an exclusive gateway to merge two parallel branches
> will see an extra downstream step after upgrading**. To express a merge in Camunda,
> use a parallel or inclusive gateway — that is what they are for.

`joining` is the default for the complex gateway on purpose: changing a default would
silently change the behaviour of already-deployed models, with the same file producing a
different diagram before and after an upgrade and no warning at all. An invalid value —
and writing this attribute on an exclusive/parallel gateway — is an ERROR at deploy time.

---

### Job priority

`zifang:priority` has **two outlets** and they read the same number:

```xml
<userTask id="approve" zifang:assignee="boss"
          zifang:asyncBefore="true" zifang:priority="90"/>
```

- **Task priority** — who sorts first in the to-do list. For humans.
- **Job priority** — who is picked off the queue first. For the executor. When
  async jobs back up, "urgent first" is a hard requirement, so
  `WfJobQuery#setOrderByPriority(true)` switches the order to
  **priority desc → due asc → job id asc**.

They are not configured separately: doing so produces "top of the to-do list but
last in the queue".

**Ordering is opt-in, not the default** — timers want "oldest due first", and
sorting them by priority starves the ones further back. Equal priorities still
fall back to due date; equal *everything* never becomes random, because a random
order cannot be reproduced.

In-memory and JDBC **must produce the same order**; when they disagree the symptom
is "green on memory in development, occasionally scrambled on JDBC" with nothing
in the log.

Upgrading an existing database automatically adds `ZWF_JOB.PRIORITY`
(`CREATE TABLE IF NOT EXISTS` does not add columns to a table that already
exists). The added column is NULL on existing rows while the default priority is
50 — without it, every job queued before the upgrade reads back as lowest priority.

### Triggering a job early

`POST /api/wf/jobs/{jobId}/trigger` fires a job before it is due. The use case is
ordinary: the overdue reminder is two hours away and the customer is out of
patience.

**Only time-triggered jobs (`TIMER`, `EVENT_TIMER`) and async jobs are allowed.**
Everything else is rejected, and the three rejection reasons are deliberately
worded separately because their consequences are not the same:

| Rejected | Consequence | Why one message would not do |
|---|---|---|
| message / signal / escalation subscription | **the same step runs twice** | Triggering it forges an event that never happened; the real one still arrives |
| event-gateway branch | **siblings are cancelled, irreversibly** | It is a *race*, and there is no "undo a race" |
| external task | a worker's in-flight work is **advanced at the same time** | Leases exist so exactly one side writes the result |

A single shared message can only state the mildest of the three, which is how the
dangerous one ends up described as "about the same as a normal subscription".

Two more things the endpoint is explicit about: a **missing job is an error, not
a success** (the usual cause is the scanner having just consumed it, and
reporting success stops the caller from retrying), and the response carries a
**`triggered` boolean** so "fired" is distinguishable from "**it should have
ranged and did not**" — the latter is neither a failure nor a success.

---

## Persistence

Abstracted behind a 25-method SPI. Two implementations ship:

- **In-memory** — Java native serialization for deep copies
- **JDBC** — 9 `ZWF_*` tables, 15 indexes, optimistic locking via compare-and-set

Optimistic-lock conflicts surface as HTTP `409` instead of silently overwriting.

The persistence shape is deliberately **not** the runtime object: `WfDefinitionCodec`
converts between them, so the storage layout can evolve without touching engine code.

---

## Testing

989 tests, all green (core 901 / web 16 / admin 72). `mvn -o clean install`.

Six of the test classes are **behaviour audits** rather than feature tests —
one per node type and one per extension-point callback. This project shipped
three "implemented, registered, never actually invoked" defects, and none of
them is visible to a static check.

Every bug fix in this repository shipped with a **reverse verification**: temporarily
revert the fix, confirm the test turns red, restore it. A test that stays green after you
remove the fix is not testing the fix.

Four defects found this way were of the same family — *the capability looks present but
does nothing*:

| Defect | What actually happened |
|---|---|
| Unsupported BPMN elements | Degraded to a plain task; validator reported nothing; deploy succeeded |
| `receiveTask` never waited | The task object was built and then **discarded**; the token walked to the end event |
| `onBeforeCreate` hook | Implemented, registered, never invoked |
| `delegateChain` | No column in the DDL, no write in the UPDATE, no read in the query |
| Unresolvable `delegateClass` | `log.error` then pass through — the service task silently did nothing while the process reported success |
| Embedded `subProcess` | Inline children were parsed but the engine never entered them |
| `callActivity` `resultExpression` | Parsed and stored, never evaluated — always null |
| `notifyOverdue` | A dead hook: zero call sites anywhere in the repo |
| `notifyTaskAssigned` | Fired only at task creation, never on claim/transfer/delegate |
| `terminate()` | Fired no hooks at all — nobody was told the request was cancelled |

---

## Security

**Authentication is not handled here.** z-wf assumes requests have already passed through
a central auth layer (z-ctc in this organization). Do not expose the port publicly: anyone
who can reach it can call `force-complete`, `jump`, or `POST /api/wf/jobs/{jobId}/trigger`
and change the outcome of any process. `trigger` has an internal type whitelist — it refuses
the job kinds that must not be fired by hand — but *who* is allowed to push a process along is
a business decision, and the engine does not make it for you.
See [SECURITY.md](SECURITY.md) for the full trust-boundary notes.

---

## Known limitations

`z-wf` does **not** currently cover all of Camunda 7. The most significant gaps:

- **No collection-based multi-instance** and no sequential (`isSequential`) countersign —
  both are rejected at deploy time rather than half-implemented
- **No timer / escalation / compensation boundary events** — only error boundaries
- **No BPMN escalation or compensation**
- **No variable service API** — variables are persisted, but there is no
  `getVariable`/`setVariable` surface like Camunda's
- **Narrower extension surface** — 3 hook interfaces against Camunda's dozens of listeners

Identity, forms, authorization, and CMMN are **deliberately out of scope**; the reasoning
is in [docs/capability-gap.md §5](docs/capability-gap.md).

The full item-by-item comparison against Camunda 7 is in
**[docs/capability-gap.md](docs/capability-gap.md)**.

---

## Building

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
mvn -o clean install

# single test class — note -am, or you will test a stale jar from ~/.m2
mvn -o -pl z-wf-admin -am test -Dtest=WfWebApiTest
```

Requires JDK 8. Builds offline once dependencies are resolved.

---

## License

MIT — see [LICENSE](LICENSE).
