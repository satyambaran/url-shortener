"""Shared value types and message constants.

VersionedValue is the unit every layer (storage, replication, conflict
resolution) speaks in. By making the version explicit we keep conflict
resolution pluggable: today it's last-write-wins on (timestamp, node_id),
tomorrow it could be vector clocks without touching storage or transport.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any, Optional


# --- Wire protocol message actions (kept as strings so they're transport-agnostic) ---
# coordinator -> replica: persist this versioned value
ACTION_PUT_REPLICA = "PUT_REPLICA"
# coordinator -> replica: return your local versioned value
ACTION_GET_REPLICA = "GET_REPLICA"
# coordinator -> replica: you're stale, take this value
ACTION_READ_REPAIR = "READ_REPAIR"
ACTION_PING = "PING"                    # health check (used by tests/demo)


@dataclass(frozen=True)
class VersionedValue:
    """A value plus the metadata needed to resolve concurrent writes.

    `timestamp` is a monotonic-ish wall clock in nanoseconds. `node_id` is the
    tiebreaker so two writes that land in the same nanosecond are still
    deterministically ordered across the cluster (last-write-wins).

    `tombstone` lets us represent deletes as a write (so deletes also replicate
    and survive a stale node coming back online with a "resurrected" value).
    """

    value: Any
    timestamp: int
    node_id: str
    tombstone: bool = False

    def to_dict(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_dict(d: Optional[dict]) -> Optional["VersionedValue"]:
        if d is None:
            return None
        return VersionedValue(
            value=d.get("value"),
            timestamp=int(d["timestamp"]),
            node_id=str(d["node_id"]),
            tombstone=bool(d.get("tombstone", False)),
        )

    def is_newer_than(self, other: Optional["VersionedValue"]) -> bool:
        """LWW comparison. Pulled into the type so storage doesn't have to know."""
        if other is None:
            return True
        if self.timestamp != other.timestamp:
            return self.timestamp > other.timestamp
        # Deterministic tiebreaker: lexicographically larger node_id wins.
        return self.node_id > other.node_id
