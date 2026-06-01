### Agent Memory Integration (MCP Tool)

The `AgentMemory` acts as a specialized MCP tool providing long-term semantic memory for the agents orchestrated by JavaMCPHub.

**Tool Name**: `agent_memory_store`

**Capabilities**:
- **Store Observation**: Persist episodic facts, findings, and observations encountered by the agent.
- **Semantic Retrieval**: Retrieve relevant historical context based on natural language queries before executing complex tasks.

**Configuration Example**:
To register this tool in the JavaMCPHub environment, add the following configuration:

```yaml
mcp:
  tools:
    agent-memory:
      enabled: true
      base-url: "https://your-railway-app-url.up.railway.app"
      tenant-id: "shared-agent-tenant-id"
```

**Workflow**:
1. When the agent receives a complex prompt, it first calls the `agent_memory_store` search tool to gather context.
2. The agent executes its core tasks.
3. Important findings are stored back into the `agent_memory_store` via the store tool.
4. The background Spring Batch job automatically consolidates these episodic memories into dense semantic profiles, reducing token overhead for future retrievals.
