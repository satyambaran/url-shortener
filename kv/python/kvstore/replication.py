"""Replication strategies.

Plug a different class in here to change the consistency model. The KVStore
facade only depends on the `Replicator` Protocol.

Included:
  - QuorumReplicator (Dynamo-style, N/W/R configurable, default 3/2/2)

Why quorum:
  - Tunable: W+R > N gives strong-ish consistency for single-key reads;
    W+R <= N gives higher availability.
  - No leader election complexity. Any node can coordinate.
  - Survives one node down (with N=3, W=2, R=2 -> still serves reads + writes).
  - Honest about its limits: concurrent writes to the same key resolve via LWW
    so the "loser" is silently dropped. Documented and intentional.
"""
from __future__ import annotations

import threading
import time
from concurrent.futures import ThreadPoolExecutor, Future
from typing import Callable, Optional, Protocol

from .conflict import ConflictResolver, LastWriteWinsResolver
from .partitioner import Partitioner
from .types import (
    ACTION_GET_REPLICA,
    ACTION_PUT_REPLICA,
    ACTION_READ_REPAIR,
    VersionedValue,
)


# Send function injected by the KVStore facade. Signature mirrors the
# take-home's `send_to_node`: returns None on failure.
SendFn = Callable[[str, dict], Optional[dict]]
# Local apply function: write to *this node's* storage, bypassing the network.
LocalApplyFn = Callable[[str, VersionedValue], None]
LocalReadFn = Callable[[str], Optional[VersionedValue]]


class Replicator(Protocol):
    def write(self, key: str, vv: VersionedValue) -> bool: ...
    def read(self, key: str) -> Optional[VersionedValue]: ...


class QuorumReplicator:
    """N/W/R quorum coordinator.

    Behavior:
      WRITE: stamp value -> fan out PUT_REPLICA to all replicas in parallel
             (local replica is applied directly, no network) -> wait for W
             total acks -> return success. Stragglers keep going in the
             background; if they fail forever, eventual consistency is
             restored on the next read via read-repair.

      READ:  fan out GET_REPLICA to all replicas in parallel -> wait for R
             responses -> resolve conflict via LWW -> if any responder was
             stale, fire-and-forget READ_REPAIR to fix it.
    """

    def __init__(
        self,
        node_id: str,
        partitioner: Partitioner,
        send: SendFn,
        local_apply: LocalApplyFn,
        local_read: LocalReadFn,
        write_quorum: int = 2,
        read_quorum: int = 2,
        request_timeout_s: float = 2.0,
        resolver: Optional[ConflictResolver] = None,
    ) -> None:
        self.node_id = node_id
        self.partitioner = partitioner
        self.send = send
        self.local_apply = local_apply
        self.local_read = local_read
        self.W = write_quorum
        self.R = read_quorum
        self.timeout = request_timeout_s
        self.resolver = resolver or LastWriteWinsResolver()
        # One pool per replicator instance. Bounded so a flood of clients
        # can't exhaust threads. Daemon=True so it doesn't block process exit.
        self._pool = ThreadPoolExecutor(
            max_workers=16, thread_name_prefix=f"repl-{node_id}")
        self._lock = threading.Lock()

    # ---------------------------------------------------------------- write #
    def write(self, key: str, vv: VersionedValue) -> bool:
        replicas = self.partitioner.replicas_for(key)
        if len(replicas) < self.W:
            # Cluster is too small to ever satisfy the quorum -- fail fast.
            return False

        acks = 0
        futures: list[Future] = []
        for r in replicas:
            if r == self.node_id:
                # Local apply is synchronous and can't fail (modulo disk full,
                # which would raise -- caller sees False).
                try:
                    self.local_apply(key, vv)
                    acks += 1
                except Exception:
                    pass
            else:
                futures.append(self._pool.submit(self._send_put, r, key, vv))

        # Collect remote acks until we either hit W total or run out.
        deadline = time.time() + self.timeout
        for fut in futures:
            remaining = max(0.0, deadline - time.time())
            try:
                ok = fut.result(timeout=remaining)
            except Exception:
                ok = False
            if ok:
                acks += 1
                if acks >= self.W:
                    # Don't cancel outstanding futures -- let them complete in
                    # the background so replicas converge. This is "sloppy
                    # quorum" minus hinted handoff; good enough for take-home.
                    return True
        return acks >= self.W

    def _send_put(self, target: str, key: str, vv: VersionedValue) -> bool:
        resp = self.send(
            target, {"action": ACTION_PUT_REPLICA, "key": key, "vv": vv.to_dict()})
        return bool(resp and resp.get("ok"))

    # ----------------------------------------------------------------- read #
    def read(self, key: str) -> Optional[VersionedValue]:
        """Return the freshest VersionedValue across replicas, or None if not
        found / read quorum unreachable.

        We track (reachable, value) so we can distinguish three cases:
          - replica unreachable    -> doesn't count toward R
          - replica reachable, no value -> counts toward R, value is None
          - replica reachable, has value -> counts toward R
        """
        replicas = self.partitioner.replicas_for(key)
        if not replicas:
            return None

        # (node_id, reachable, value)
        responses: list[tuple[str, bool, Optional[VersionedValue]]] = []
        futures: list[tuple[str, Future]] = []

        for r in replicas:
            if r == self.node_id:
                responses.append((r, True, self.local_read(key)))
            else:
                futures.append((r, self._pool.submit(self._send_get, r, key)))

        deadline = time.time() + self.timeout
        for r, fut in futures:
            remaining = max(0.0, deadline - time.time())
            try:
                reachable, vv = fut.result(timeout=remaining)
            except Exception:
                reachable, vv = False, None
            responses.append((r, reachable, vv))

        reachable_count = sum(1 for _, ok, _ in responses if ok)
        if reachable_count < self.R:
            # Couldn't reach enough replicas to satisfy the read quorum.
            return None

        winner = self.resolver.winner(vv for _, ok, vv in responses if ok)

        # Read repair: anyone reachable whose value is stale gets the winner.
        if winner is not None:
            for r, ok, vv in responses:
                if not ok:
                    continue
                if vv is None or winner.is_newer_than(vv):
                    if r == self.node_id:
                        try:
                            self.local_apply(key, winner)
                        except Exception:
                            pass
                    else:
                        # Fire and forget. Failure is fine -- next read retries.
                        self._pool.submit(self._send_repair, r, key, winner)
        return winner

    def _send_get(self, target: str, key: str) -> tuple[bool, Optional[VersionedValue]]:
        resp = self.send(target, {"action": ACTION_GET_REPLICA, "key": key})
        if resp is None:
            return False, None
        return True, VersionedValue.from_dict(resp.get("vv"))

    def _send_repair(self, target: str, key: str, vv: VersionedValue) -> None:
        self.send(target, {"action": ACTION_READ_REPAIR,
                  "key": key, "vv": vv.to_dict()})

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)
