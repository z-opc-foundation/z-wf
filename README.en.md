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
`sendTask` · `receiveTask` · `task` · `exclusiveGateway` · `parallelGateway` ·
`inclusiveGateway` · `complexGateway` · `eventBasedGateway` ·
`intermediateCatchEvent` · `intermediateThrowEvent` ·
`linkThrowEvent` · `linkCatchEvent` ·
`subProcess` · `callActivity` · `boundaryEvent`

**Unsupported BPMN elements fail loudly at deploy time.** Elements the engine does not
implement (`transaction`, `adHocSubProcess`, `dataObject`, `escalationEventDefinition`, …)
are parsed permissively so the file can be read at all, but the resulting node is tagged with
its original element name and the validator reports an **ERROR**, which blocks `deploy`.

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

188 tests, all green. `mvn -o clean install`.

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
who can reach it can call `force-complete` or `jump` and change the outcome of any process.
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
- **No jobs, timers, or async execution** — timeout reminders, escalation, and async
  external calls are not possible
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
