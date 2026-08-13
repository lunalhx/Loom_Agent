# Make Plan Submission a terminal protocol action

Status: Accepted.

Plan Submission is a distinct model-output action: Tool Calls continue exploration, Final Answers complete a Run without changing Plans, and Plan Submission atomically persists a Plan revision and completes the Run. Runtime assigns Plan identity, revision, timestamps, and captured Plan Basis while the Agent supplies only content and additional declared dependencies. It is not modeled as an ordinary tool or inferred from Markdown, preventing persistence from becoming an ambiguous or non-terminal side effect of the tool loop.
