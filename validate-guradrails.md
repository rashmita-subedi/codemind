# Guardrails (Safety + Control Layer)

1. Input Guardrails (Before LLM)
Validate user/system input:

Prompt injection detection
Malicious instruction filtering
Sensitive data detection (PII, secrets) 

Examples:

"Ignore previous instructions..." → block or sanitize
"Give me DB password" → reject

---

2. Reasoning Guardrails (During Planning)
      Control how the agent thinks:

Max iteration limits
Task scope restriction
Allowed domains of operation 

Prevents:
Infinite loops
Hallucinated plans
Out-of-scope execution

--- 

3. Tool Guardrails (Most Critical)
   Control what the agent can execute:

Whitelisted tools only
Argument validation
Rate limiting
Sandboxed execution 

Example:

{
"tool": "delete_file",
"allowed_paths": ["/tmp/safe/", "/workspace/"],
"requires_approval": true
}

--- 

4. Output Guardrails (After LLM)
Validate responses before returning:

Toxicity filtering
Data leakage prevention
Format validation (JSON schema)

--- 

5. Environment Guardrails
   Filesystem isolation
   Network restrictions
   No unrestricted shell access

---


Guardrail Techniques
Rule-based filters (fast, deterministic)
LLM-as-a-judge (self-critique)
Policy engine (RBAC / ABAC)
Static + dynamic validation