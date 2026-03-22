## Android Command Summary Whitelist

Android timeline summary policy for shell-driven activity.

| Category | Safe patterns | Timeline summary |
| --- | --- | --- |
| `Read` | `cat`, `sed -n`, `head`, `tail`, `nl -ba` | `Read <file>` |
| `Searched` | `rg`, `grep` | `Searched for <pattern> in <target>` |
| `Listed` | `ls`, `tree`, `rg --files` | `Listed <path>` |
| `Created` | `touch`, `mkdir` | `Created <path>` |
| `Edited` | `apply_patch Update File` | `Edited <path>` |
| `Deleted` | `rm` (single path), `rmdir`, `apply_patch Delete File` | `Deleted <path>` |
| `Renamed` | `mv <old> <new>` (single move) | `Renamed <old> -> <new>` |

### Rules

- Only summarize commands when intent is singular and target extraction is reliable.
- Safe pipelines are allowed when they preserve one intent:
  - `nl -ba foo.kt | sed -n '1,80p'` -> `Read foo.kt`
  - `rg -n "Running" Turn | head` -> `Searched for Running in Turn`
  - `rg --files AndroidClient | head` -> `Listed AndroidClient`
- Any mixed-intent or ambiguous shell remains `Running <command preview>`.
- `CommandExecution` rows should render only once in the timeline. Do not also append a duplicate `Running ...` thinking line for the same command.
