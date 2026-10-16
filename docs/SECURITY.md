# Security principles

Never store a 1C repository password, ITS credential or session token in project preferences or plaintext source files. Use Eclipse secure storage when implementing secret handling. Redact command arguments, environment secrets, and diagnostic URLs before logging.

Never access a user's working 1C infobase or repository unless explicitly instructed and verified as the intended target. Integration tests must use temporary disposable data. The prototype must not spawn the 1C platform, modify files, or access remote services.
