# Two-node Call-ID LB assertion (T6.3)

Companion to [multi-node.md](multi-node.md).

**Production requirement:** SIP LB must hash (or stick) on Call-ID so a given provisional ringing event is delivered to a single gateway node.

**App behavior (verified in CI):** Two independent node instances both process the same Call-ID if both receive it — there is no shared dedupe bus.

Pass criteria for staging smoke:

- [ ] Call-hash enabled on SIP LB
- [ ] One Call-ID → one node’s `sip_ringing_intercepts_total` (+1)
- [ ] Node drain does not drop in-flight SIP dialogs mid-transaction
