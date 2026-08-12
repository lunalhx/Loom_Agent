# Supervise shell process lifetime without heavy resource isolation

Status: Accepted.

Every Shell Tool Call runs under a Shell Process Supervisor that owns the complete child process tree, propagates cancellation, terminates remaining descendants when the call completes or times out, drains stdout and stderr into bounded buffers, and limits concurrent Shell calls within a Run. Background processes may not outlive `run_shell`; a future explicit Job capability is required for long-lived services. The initial lightweight sandbox does not claim strict CPU, memory, or PID isolation, because providing a hostile-code denial-of-service boundary consistently across macOS and Linux would require heavier platform facilities such as cgroups, containers, or virtual machines. This process supervision applies in Full Access as a lifecycle invariant, not as a restriction on its filesystem or network authority.
