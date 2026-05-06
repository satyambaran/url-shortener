"""Partitioner: decides which nodes own which keys.

We support multiple strategies behind one interface so the rest of the system
doesn't care:

  - FullReplicationPartitioner: every node holds every key. Simplest. With a
    3-node cluster and RF=3 this is what consistent hashing degenerates to.
  - ConsistentHashPartitioner:  hash ring with virtual nodes. Returns the first
    `replication_factor` distinct physical nodes walking clockwise from the
    key's hash. This is the design Dynamo / Cassandra / Riak use; the wins
    (smooth rebalancing when nodes are added/removed) only show up at >RF nodes,
    but we use it anyway so adding nodes later "just works".
"""
from __future__ import annotations

import bisect
import hashlib
from typing import List, Protocol


class Partitioner(Protocol):
    def replicas_for(self, key: str) -> List[str]:
        """Return the ordered list of node_ids that should hold `key`.
        First element is the 'preferred coordinator' (not enforced -- any
        replica can coordinate)."""
        ...

    def all_nodes(self) -> List[str]: ...


class FullReplicationPartitioner:
    def __init__(self, nodes: List[str]) -> None:
        self._nodes = list(nodes)

    def replicas_for(self, key: str) -> List[str]:
        return list(self._nodes)

    def all_nodes(self) -> List[str]:
        return list(self._nodes)


class ConsistentHashPartitioner:
    """Standard ring with virtual nodes for balance.

    Trade-offs vs. naive hash%N:
      + Adding/removing a node only moves ~1/N of keys (vs. ~all of them).
      + Virtual nodes smooth out hotspots from skewed key distributions.
      - Slightly more complex; ring rebuild is O(N * vnodes).
    """

    def __init__(
        self,
        nodes: List[str],
        replication_factor: int = 3,
        virtual_nodes: int = 128,
    ) -> None:
        if replication_factor > len(nodes):
            raise ValueError(
                "replication_factor cannot exceed number of nodes")
        self._nodes = list(nodes)
        self._rf = replication_factor
        self._vnodes = virtual_nodes
        self._ring: list[int] = []           # sorted hash positions
        self._owner: dict[int, str] = {}     # hash position -> node_id
        self._build_ring()

    def _hash(self, s: str) -> int:
        # MD5 is fine for partitioning (not security). 128 bits > plenty.
        return int(hashlib.md5(s.encode()).hexdigest(), 16)

    def _build_ring(self) -> None:
        ring: list[int] = []
        owner: dict[int, str] = {}
        for node in self._nodes:
            for v in range(self._vnodes):
                h = self._hash(f"{node}#{v}")
                ring.append(h)
                owner[h] = node
        ring.sort()
        self._ring = ring
        self._owner = owner

    def replicas_for(self, key: str) -> List[str]:
        if not self._ring:
            return []
        h = self._hash(key)
        idx = bisect.bisect(self._ring, h) % len(self._ring)
        result: list[str] = []
        seen: set[str] = set()
        # Walk the ring until we have RF *distinct physical* nodes.
        for i in range(len(self._ring)):
            pos = self._ring[(idx + i) % len(self._ring)]
            node = self._owner[pos]
            if node not in seen:
                seen.add(node)
                result.append(node)
                if len(result) == self._rf:
                    break
        return result

    def all_nodes(self) -> List[str]:
        return list(self._nodes)
