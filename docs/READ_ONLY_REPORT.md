# Read-only 1C configuration repository history: first transport slice

**Status: source-only; uncompiled/unexecuted**. The last successful CI build predates this module. No EDT UI action invokes the runner. Existing user infobases and storage have not been contacted.

## Platform contract

Official 1C:Enterprise 8.3.27 Administrator Guide, section 7.4.15:
https://kb.1ci.com/1C_Enterprise_Platform/Guides/Administrator_Guides/1C_Enterprise_8.3.27_Administrator_Guide/Appendix_7._Startup_command-line_options_of_1C_Enterprise/7.4._Running_Designer_in_batch_mode/7.4.15._Configuration_repository_operations/

The report command is **`/ConfigurationRepositoryReport <file> [-NBegin n] [-NEnd n] [-ReportFormat txt]`**. `-NBegin -1` is defined as the latest repository version. The new platform bundle supports two query types:

- Latest revision *report*: `RepositoryHistoryRequest.latest(executable, infobase, timeout)`
- Inclusive revision range: `RepositoryHistoryRequest.range(executable, infobase, first, last, timeout)`

Representative (NOT EXECUTED) argument sequence:
```text
1cv8.exe DESIGNER /F <existing-connected-infobase-dir>
  /DisableStartupDialogs
  /ConfigurationRepositoryReport <private-temp/history.txt>
  -NBegin -1 -ReportFormat txt /Out <private-temp/designer.txt>
```

File/server addresses map only to the documented `/F` and `/S` arguments. There is **no** command for binding, locking, receiving, dumping, committing, updating or clearing repository data in the module. 1C's own attached infobase is reused for the report. The executable must point at an installed 1C platform and be an existing regular file at invocation.

## Safety engineering

- `ProcessBuilder` with argument list, **never a shell**.
- Immutable request and **allowlisted report operation**; no untrusted extra flags.
- **Neither the infobase password nor repository password is passed** on the command line; this prototype assumes that the existing connected base has accessible credentials in the local 1C environment. Authentication may fail, and that is reported safely.
- A separate private temporary directory holds report, service messages, stdout and stderr; cleanup after process exit/failure. Directory permissions are owner-only where POSIX applies.
- Polling checks cancellation, a bounded timeout (2 seconds to 30 minutes), nonzero exit status, missing/empty report and output-size limit (16 MiB per file). Stop the process on cancellation/timeout.
- Raw reports are returned as defensive bytes. The optional `TextReportDecoder` strictly accepts UTF-8 and UTF-8/UTF-16 BOM data; legacy encodings are rejected pending verified real fixtures. No invented parsing. `RepositoryRevision` defines a future structured result but is intentionally not populated yet.
- Report errors do not include raw service messages, credentials, paths or process arguments. The caller receives a typed error reason and optionally exit code.
- No checkpoint is updated by the process returning 0. Version extraction needs real report fixtures, validation and object identity checks first.

## Test coverage added (but not run)

Pure command-builder JUnit tests check /F, /S, latest/range, forbidden write switches, invalid inputs, immutable arguments and bytes. Decoder tests check UTF-8/UTF-16 BOM and malformed input. They **never launch** any executable.

## Next work

1. On explicit user build request, compile and run tests.
2. Install plugin in EDT 2026.2; check new UI.
3. On a **disposable** connected 1C test base with explicit authorization, capture real raw TXT output for version history and encoding, and validate report-specific side effects.
4. Implement parsing from observed fixtures (versions, authors, timestamps, object changes), with malformed report rejection.
5. Connect safe background Job to UI; only after this can the remote-observed version be persisted and shown as factual.
6. Never run any operation against user's permanently connected base merely because its path has been entered into settings.
