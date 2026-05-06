"""Pluggable distributed key-value store.

Public entry point is `KVStore`. Everything else (storage, transport,
replication, partitioning, backup, conflict resolution) is behind a small
interface so it can be swapped without touching the facade.
"""
from .kvstore import KVStore
from .types import VersionedValue

__all__ = ["KVStore", "VersionedValue"]
