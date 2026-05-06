"""KVStore facade.

This is the only class users (and the take-home prompt's `if __name__`) touch.
It wires together the pluggable layers:

    storage       -- where data lives on this node (default: WAL+snapshot)
    partitioner   -- which nodes own which keys
    transport     -- how nodes talk to each other
    replicator    -- how a write/read fans out to replicas (default: quorum)
    backup        -- how to take a live snapshot (default: WAL checkpoint)

The contract from the take-home (`send_to_node`, `on_message`, `get`, `put`,
`__init__(node_id, peer_nodes)`) is preserved. `send_to_node` delegates to the
injected Transport; `on_message` is the inbound dispatcher.

Defaults are chosen so a one-liner `KVStore("node1", ["node2", "node3"], transport=t)`
gives you a sensible production-shaped node.
"""
from __future__ import annotations

import os
import threading
import time
from typing import Any, List, Optional

from .backup import BackupStrategy, WalCheckpointBackup
from .conflict import ConflictResolver, LastWriteWinsResolver
from .partitioner import ConsistentHashPartitioner, Partitioner
from .replication import QuorumReplicator, Replicator
from .storage import Storage, WalSnapshotStorage
from .transport import InProcessTransport, Transport
from .types import (
    ACTION_GET_REPLICA,
    ACTION_PING,
    ACTION_PUT_REPLICA,
    ACTION_READ_REPAIR,
    VersionedValue,
)


class KVStore:
    """Distributed key-value store node.

    Args follow the take-home skeleton, plus optional injection points for
    every pluggable layer. None of the optionals are required for the basic
    case.
    """

    def __init__(
        self,
        node_id: str,
        peer_nodes: List[str],
        *,
        transport: Optional[Transport] = None,
        storage: Optional[Storage] = None,
        partitioner: Optional[Partitioner] = None,
        replicator_factory: Optional[callable] = None,
        backup_strategy: Optional[BackupStrategy] = None,
        resolver: Optional[ConflictResolver] = None,
        data_dir: Optional[str] = None,
        write_quorum: int = 2,
        read_quorum: int = 2,
        replication_factor: int = 3,
    ) -> None:
        self.node_id = node_id
        self.peer_nodes = list(peer_nodes)
        self._all_nodes = [node_id] + list(peer_nodes)

        # ---- transport: required for multi-node, optional for single-node ----
        # If the caller didn't pass one, create a private InProcessTransport
        # and register ourselves. This makes a single-node KVStore work out of
        # the box (useful for tests that don't care about the cluster).
        self._transport = transport if transport is not None else InProcessTransport()
        self._transport.register(node_id, self.on_message)

        # ---- storage ----
        if storage is None:
            data_dir = data_dir or os.path.join(".kv_data", node_id)
            storage = WalSnapshotStorage(data_dir)
        self._storage = storage

        # ---- partitioner ----
        if partitioner is None:
            partitioner = ConsistentHashPartitioner(
                self._all_nodes,
                replication_factor=min(
                    replication_factor, len(self._all_nodes)),
            )
        self._partitioner = partitioner

        # ---- conflict resolver ----
        self._resolver = resolver or LastWriteWinsResolver()

        # ---- replicator ----
        if replicator_factory is None:
            self._replicator: Replicator = QuorumReplicator(
                node_id=node_id,
                partitioner=self._partitioner,
                send=self._send_via_transport,
                local_apply=self._local_apply,
                local_read=self._storage.get,
                write_quorum=write_quorum,
                read_quorum=read_quorum,
                resolver=self._resolver,
            )
        else:
            self._replicator = replicator_factory(self)

        # ---- backup ----
        if backup_strategy is None and isinstance(storage, WalSnapshotStorage):
            backup_strategy = WalCheckpointBackup(storage)
        self._backup = backup_strategy

        # Lock around timestamp generation so two concurrent puts on the SAME
        # node always produce strictly increasing timestamps. Cross-node
        # tiebreaking is handled by node_id in VersionedValue.
        self._ts_lock = threading.Lock()
        self._last_ts = 0

    # ====================================================================== #
    # Public API (matches the take-home skeleton)
    # ====================================================================== #
    def get(self, key: str) -> Optional[Any]:
        vv = self._replicator.read(key)
        if vv is None or vv.tombstone:
            return None
        return vv.value

    def put(self, key: str, value: Any) -> bool:
        vv = VersionedValue(
            value=value, timestamp=self._next_ts(), node_id=self.node_id)
        return self._replicator.write(key, vv)

    def delete(self, key: str) -> bool:
        """Bonus: tombstone-based delete. Replicates exactly like a put so
        deletes can't be 'undone' by a stale replica coming back online."""
        vv = VersionedValue(
            value=None, timestamp=self._next_ts(), node_id=self.node_id, tombstone=True
        )
        return self._replicator.write(key, vv)

    def send_to_node(self, target_node: str, message: dict) -> Optional[dict]:
        """Take-home contract method. Delegates to the pluggable transport."""
        return self._send_via_transport(target_node, message)

    def on_message(self, from_node: str, message: dict) -> dict:
        """Inbound dispatcher. The transport calls this when another node
        sends us a message. Keep this small + side-effect-only-via-storage so
        it stays correct under concurrent requests."""
        action = message.get("action")

        if action == ACTION_PUT_REPLICA:
            vv = VersionedValue.from_dict(message["vv"])
            if vv is None:
                return {"ok": False, "error": "bad payload"}
            self._local_apply(message["key"], vv)
            return {"ok": True}

        if action == ACTION_GET_REPLICA:
            vv = self._storage.get(message["key"])
            return {"ok": True, "vv": vv.to_dict() if vv else None}

        if action == ACTION_READ_REPAIR:
            vv = VersionedValue.from_dict(message["vv"])
            if vv is not None:
                self._local_apply(message["key"], vv)
            return {"ok": True}

        if action == ACTION_PING:
            return {"ok": True, "node_id": self.node_id}

        return {"ok": False, "error": f"unknown action {action!r}"}

    # ====================================================================== #
    # Operational
    # ====================================================================== #
    def backup(self, dest_dir: str) -> dict:
        """Take a live, consistent backup. Returns a manifest dict."""
        if self._backup is None:
            raise RuntimeError(
                "No backup strategy configured for this storage")
        return self._backup.backup(dest_dir)

    def close(self) -> None:
        try:
            if hasattr(self._replicator, "shutdown"):
                self._replicator.shutdown()
        finally:
            self._storage.close()

    # ====================================================================== #
    # Internals
    # ====================================================================== #
    def _send_via_transport(self, target_node: str, message: dict) -> Optional[dict]:
        # InProcessTransport accepts an optional from_node so the receiver can
        # tell who's calling. For real network transports the source would come
        # from auth headers / mTLS instead.
        if hasattr(self._transport, "send"):
            try:
                # type: ignore[call-arg]
                return self._transport.send(target_node, message, from_node=self.node_id)
            except TypeError:
                # Transport without the optional from_node kwarg.
                return self._transport.send(target_node, message)
        return None

    def _local_apply(self, key: str, vv: VersionedValue) -> None:
        # LWW guard happens inside InMemoryStorage.put; the WAL still records
        # the attempt so replays remain deterministic.
        self._storage.put(key, vv)

    def _next_ts(self) -> int:
        """Monotonic per-node timestamp in nanoseconds.

        time.time_ns() can stall (or even go backwards across NTP corrections),
        so we clamp it to be strictly > the previous value emitted by this
        node. Cross-node ties are broken by node_id in VersionedValue.
        """
        with self._ts_lock:
            now = time.time_ns()
            if now <= self._last_ts:
                now = self._last_ts + 1
            self._last_ts = now
            return now
