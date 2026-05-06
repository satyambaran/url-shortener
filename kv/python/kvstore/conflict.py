"""Conflict resolution strategies.

Today: last-write-wins on (timestamp, node_id) -- the comparison already lives
on VersionedValue. This module exists so we can add vector clocks or CRDTs
later without having to rewrite the replicator or storage layer.
"""
from __future__ import annotations

from typing import Iterable, Optional, Protocol

from .types import VersionedValue


class ConflictResolver(Protocol):
    def winner(self, candidates: Iterable[Optional[VersionedValue]]) -> Optional[VersionedValue]:
        """Return the value the cluster should converge on."""
        ...


class LastWriteWinsResolver:
    """Pick the value with the highest (timestamp, node_id).

    Pros: trivially simple, requires no per-key metadata beyond a timestamp.
    Cons: depends on roughly-synchronized clocks; concurrent writes silently
          drop one of the values. For a take-home this is the right tradeoff;
          in production you'd reach for vector clocks + sibling resolution
          (Dynamo) or a consensus log (Raft).
    """

    def winner(self, candidates: Iterable[Optional[VersionedValue]]) -> Optional[VersionedValue]:
        best: Optional[VersionedValue] = None
        for c in candidates:
            if c is None:
                continue
            if best is None or c.is_newer_than(best):
                best = c
        return best
