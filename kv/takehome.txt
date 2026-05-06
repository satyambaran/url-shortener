"""
DISTRIBUTED KEY-VALUE STORE - TAKE HOME ASSIGNMENT
===================================================

Time: 2-3 hours
You may use any resources (Google, docs, AI tools, etc.)


BACKGROUND
----------
You're building a key-value store (like Redis) that runs on multiple servers.
When a user stores data, it should be safe even if one server crashes.
The system also needs periodic backups for disaster recovery.


TASK
----
Implement a KVStore class that:

1. BASIC OPERATIONS
   - get(key) returns the value for a key
   - put(key, value) stores a key-value pair

2. MULTI-NODE
   - Assume 3 nodes (servers), each running an instance of your class
   - Use send_to_node() to communicate between nodes
   - Design how data is distributed/replicated across nodes

3. FAULT TOLERANCE  
   - If one node crashes, data should not be lost
   - If one node is temporarily unreachable, the system should still work
   - Data should survive node restarts (persistence)

4. BACKUP
   - The system needs to support periodic backups
   - Backups happen while the system is live (writes keep coming)
   - No writes should be lost during backup
   - Think about: how do you get a consistent snapshot while writes continue?


WHAT TO SUBMIT
--------------
- Your implementation of the KVStore class
- Comments explaining your key design decisions
- Pseudocode is acceptable for complex parts - we care about the design


EVALUATION
----------
We're NOT looking for production-ready code. We're looking for:
- Understanding of distributed systems tradeoffs
- Thoughtful design decisions with clear reasoning
- Awareness of what can go wrong


HINTS
-----
- There's no perfect solution - every choice has tradeoffs
- Think about: What happens if a node is slow? Down? Comes back up?
- Think about: What if two users write the same key at the same time?
- Think about: How does backup interact with ongoing writes?


INTERVIEW
---------
In the follow-up interview, we'll:
- Walk through your code together
- Discuss why you made certain choices
- Explore failure scenarios
- Potentially ask you to modify the design for different requirements

===================================================
"""

import threading
from typing import Any, Optional, List, Dict


class KVStore:
    """
    Distributed key-value store.
    
    Assumptions:
    - 3 nodes in the cluster, each with its own instance of this class
    - Nodes communicate via send_to_node() 
    - Nodes can fail or become unreachable at any time
    - Data must survive node restarts
    """
    
    def __init__(self, node_id: str, peer_nodes: List[str]):
        """
        Initialize the KV store.
        
        Args:
            node_id: This node's identifier (e.g., "node1")
            peer_nodes: List of other node IDs (e.g., ["node2", "node3"])
        """
        self.node_id = node_id
        self.peer_nodes = peer_nodes
        
        # TODO: Your implementation
    
    def send_to_node(self, target_node: str, message: dict) -> Optional[dict]:
        """
        Send a message to another node and wait for response.
        
        This simulates network communication between nodes.
        In reality this would be HTTP/RPC - just use it as a black box.
        
        Args:
            target_node: ID of the node to send to (e.g., "node2")
            message: Any dictionary you want to send
            
        Returns:
            Response dictionary from the other node, OR
            None if the node is down/unreachable
            
        Example:
            response = self.send_to_node("node2", {"action": "replicate", "key": "foo", "value": "bar"})
            if response is None:
                # node2 is down, handle it
        """
        pass  # Provided - don't implement
    
    def on_message(self, from_node: str, message: dict) -> dict:
        """
        Handle incoming message from another node.
        
        When another node calls send_to_node() targeting this node,
        this method receives that message.
        
        Args:
            from_node: ID of the node that sent the message
            message: The message dictionary
            
        Returns:
            Response dictionary to send back
        """
        # TODO: Your implementation (if needed)
        pass
    
    def get(self, key: str) -> Optional[Any]:
        """
        Retrieve a value by key.
        
        Args:
            key: The key to look up
            
        Returns:
            The value if found, None if not found
        """
        # TODO: Your implementation
        pass
    
    def put(self, key: str, value: Any) -> bool:
        """
        Store a key-value pair.
        
        Args:
            key: The key to store
            value: The value to store (assume it's JSON-serializable)
            
        Returns:
            True if successful, False otherwise
        """
        # TODO: Your implementation
        pass


# Quick test
if __name__ == "__main__":
    store = KVStore("node1", ["node2", "node3"])
    store.put("user:1", {"name": "Alice", "email": "alice@example.com"})
    print(store.get("user:1"))