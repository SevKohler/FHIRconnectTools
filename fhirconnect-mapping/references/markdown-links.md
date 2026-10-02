# Referencing mappings from Markdown

Every Markdown the skills write (project READMEs, alignment tables, review and test reports, notes)
refers to mapping files and methods the same way, so the references are clickable in three places:
GitHub's file view, IntelliJ's Markdown preview, and the IntelliJ editor with the FHIRconnect plugin.

| what | write | GitHub | IDEA preview | IDEA editor (plugin) |
|---|---|---|---|---|
| a mapping file | `` [`KDS_problem_diagnose`](KDS_problem_diagnose.yml) `` | opens file | opens file | Ctrl+click opens file |
| a method | `` [`KDS_problem_diagnose#dateTime`](KDS_problem_diagnose.yml) `` | opens file | opens file | Ctrl+click jumps to the method |
| a nested method | `` [`…#eventsParent.medicationCode`](medication_statement.v0.yml) `` | opens file | opens file | jumps to the nested method |
| a table cell | `` [`Condition.code`](../model/…/problem_diagnosis.v1.yml "EVALUATION.problem_diagnosis.v1#problemDiagnose") `` | opens file, title as tooltip | same | jumps to the method |
| a template / profile | `` [`KDS_Diagnose.opt`](../resources/KDS_Diagnose.opt) `` | opens / downloads | opens | opens |
| a name in prose | `` `EVALUATION.problem_diagnosis.v1` `` (metadata.name) or the full archetype id | plain text | plain text | Ctrl+click jumps to the mapping |
| a method in prose | `` `KDS_problem_diagnose#dateTime` `` | plain text | plain text | Ctrl+click jumps to the method |

In generated mapping READMEs both path cells of a row (`FHIR` and `openEHR`) are links to the
method that produces the row, so a reader can click either side and land on the YAML.

Rules:

- Link **targets are relative paths** from the Markdown file, forward slashes, exactly as the file is
  named in the repository. Never absolute paths, never `file:` URLs.
- Link **text is the `metadata.name`** (plus `#method` for methods), in backticks, so the name is
  readable and greppable even where the link is not rendered.
- The method is named **in the link text** (`` `name#method` ``) or, when the text must stay a path
  as in table cells, **in the link title** (`"name#method"`). Never in the URL: `file.yml#method` makes
  the IDEA Markdown plugin look for a header called `method` and fail with "Cannot find header".
  Dotted for nested methods (`parent.child`). `#L42` in the URL is tolerated by the plugin but has the
  same header problem in the preview, so avoid it too.
- When a path is not known (a hand-over written without the repository at hand), use the bare
  backticked name; the plugin resolves it, GitHub shows text.
- Report formats: `[name#method](path)` replaces the older `<file> · <method>` and `<file>:<method>`
  spellings.
