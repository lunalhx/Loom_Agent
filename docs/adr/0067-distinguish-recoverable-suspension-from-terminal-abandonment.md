# Distinguish recoverable suspension from terminal abandonment

Status: Accepted.

Unexpected process/host loss and explicit Run Suspension leave the Run non-terminal and later require explicit recovery, while Run Abandonment is a user-selected terminal result that can never be recovered. Exiting with an active recoverable Run, including the first `Ctrl-C`, must let the user choose suspension or abandonment; suspension stops the Attempt/process tree and records any unresolved in-flight call as Interrupted Tool Call, whereas successful, failed, conflict, deviation, and other established terminal outcomes never enter recovery. Full Access Runs cannot survive process exit by design, so they do not offer suspension: the user must either return to the active Run or explicitly abandon it.
