# Security

- Never commit `local.properties`, API keys, tokens, credentials or service-account files.
- Use a short-lived, restricted development key for local testing.
- Revoke a key immediately if it has ever appeared in source code, logs, screenshots or Git history.
- A production mobile client must not hold a privileged LLM key. Route requests through an authenticated backend with rate limits and monitoring.

To report a security issue, contact the repository owner privately rather than opening a public issue containing secrets.
