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
`inclusiveGateway` · `subProcess` · `callActivity`

**Unsupported BPMN elements fail loudly at deploy time.** Elements the engine does not
implement (`eventBasedGateway`, `transaction`, `intermediateCatchEvent`, …) are parsed
permissively so the file can be read at all, but the resulting node is tagged with its
original element name and the validator reports an **ERROR**, which blocks `deploy`.

This is deliberate. Silently degrading `eventBasedGateway` to a plain task is not a loss of
precision — it replaces an automatic event race with "create a task and wait for a human",
while the definition still deploys cleanly and leaves a `completed` activity record. An
author and the running process would disagree with no signal at all.

You can override the inferred type per element with `zifang:type`; an explicit override is
respected and not blocked.

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
- **JDBC** — 6 `ZWF_*` tables, 10 indexes, optimistic locking via compare-and-set

Optimistic-lock conflicts surface as HTTP `409` instead of silently overwriting.

The persistence shape is deliberately **not** the runtime object: `WfDefinitionCodec`
converts between them, so the storage layout can evolve without touching engine code.

---

## Testing

114 tests, all green. `mvn -o clean install`.

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

---

## Security

**Authentication is not handled here.** z-wf assumes requests have already passed through
a central auth layer (z-ctc in this organization). Do not expose the port publicly: anyone
who can reach it can call `force-complete` or `jump` and change the outcome of any process.
See [SECURITY.md](SECURITY.md) for the full trust-boundary notes.

---

## Known limitations

`z-wf` does **not** currently cover all of Camunda 7. The most significant gaps:

- **No multi-instance** (countersign / "3 of 3 approvers") — the default requirement for
  approval systems
- **No variable service API** — variables are persisted, but there is no
  `getVariable`/`setVariable` surface like Camunda's
- **No jobs, timers, or async execution** — timeout reminders, escalation, and async
  external calls are not possible
- **No BPMN error events** — a failing `serviceTask` fails the whole process rather than
  routing to a compensation branch
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
