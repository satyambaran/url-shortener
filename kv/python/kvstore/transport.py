"""Transport layer.

Abstracts how nodes talk to each other. The KVStore facade only knows
`Transport.send(target, message) -> Optional[dict]` -- exactly the contract the
take-home prompt specifies. That keeps replication code identical whether the
backend is in-process (tests/demo), HTTP, or gRPC.

Implementations included:
  - InProcessTransport: a thread-safe registry. Lets us spin up 3 KVStore
    instances inside one Python process and simulate node down / restart /
    slow-network without sockets. This is how the demo runs end-to-end.
"""
from __future__ import annotations

import random
import threading
import time
from typing import Callable, Optional, Protocol


# Handler signature: (from_node, message) -> response
MessageHandler = Callable[[str, dict], dict]


class Transport(Protocol):
    def send(self, target_node: str, message: dict) -> Optional[dict]: ...
    def register(self, node_id: str, handler: MessageHandler) -> None: ...


class InProcessTransport:
    """Shared bus used by all nodes in the same Python process.

    Failure injection knobs (used by the demo to exercise fault tolerance):
      - mark_down(node):    send() to that node returns None
      - mark_up(node):      revert
      - set_latency_ms:     artificial sleep before delivery
      - set_drop_rate:      probability that send() returns None even if up
    """

    def __init__(self) -> None:
        self._handlers: dict[str, MessageHandler] = {}
        self._down: set[str] = set()
        self._lock = threading.RLock()
        self._latency_ms = 0
        self._drop_rate = 0.0
        self._rng = random.Random(0)

    # ----- registration / fault injection ---------------------------------- #
    def register(self, node_id: str, handler: MessageHandler) -> None:
        with self._lock:
            self._handlers[node_id] = handler

    def unregister(self, node_id: str) -> None:
        with self._lock:
            self._handlers.pop(node_id, None)

    def mark_down(self, node_id: str) -> None:
        with self._lock:
            self._down.add(node_id)

    def mark_up(self, node_id: str) -> None:
        with self._lock:
            self._down.discard(node_id)

    def set_latency_ms(self, ms: int) -> None:
        self._latency_ms = ms

    def set_drop_rate(self, rate: float) -> None:
        self._drop_rate = max(0.0, min(1.0, rate))

    # ----- send ------------------------------------------------------------ #
    def send(self, target_node: str, message: dict, from_node: str = "?") -> Optional[dict]:
        # Snapshot state under lock so we don't race with mark_down/up.
        with self._lock:
            if target_node in self._down:
                return None
            handler = self._handlers.get(target_node)
            if handler is None:
                return None  # node not registered = effectively down
            latency = self._latency_ms
            drop = self._drop_rate

        if drop > 0 and self._rng.random() < drop:
            return None
        if latency > 0:
            time.sleep(latency / 1000.0)

        try:
            return handler(from_node, message)
        except Exception:
            # A peer-side exception is indistinguishable from a network error
            # to the caller. Returning None lets the quorum logic treat it as
            # an unreachable replica.
            return None
