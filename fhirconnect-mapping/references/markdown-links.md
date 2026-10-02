# Referencing mappings from Markdown

Every Markdown the skills write (project READMEs, alignment tables, review and test reports, notes)
refers to mapping files and methods the same way, so the references are clickable in three places:
GitHub's file view, IntelliJ's Markdown preview, and the IntelliJ editor with the FHIRconnect plugin.

| what | write | GitHub | IDEA preview | IDEA editor (plugin) |
|---|---|---|---|---|
| a mapping file | `` [`KDS_problem_diagnose`](KDS_problem_diagnose.yml) `` | opens file | opens file | Ctrl+click opens file |
| a method | `` [`KDS_problem_diagnose#dateTime`](KDS_problem_diagnose.yml#dateTime) `` | opens file (anchor ignored) | jumps to the method | Ctrl+click jumps to the method |
| a nested method | `` [`…#eventsParent.medicationCode`](medication_statement.v0.yml#eventsParent.medicationCode) `` | opens file | jumps to the method | jumps to the nested method |
| a table cell | `` [`Condition.code`](../model/…/problem_diagnosis.v1.yml#problemDiagnose "EVALUATION.problem_diagnosis.v1#problemDiagnose") `` | opens file, title as tooltip | jumps to the method | jumps to the method |
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
- The method goes into the **URL anchor** (`file.yml#method`, dotted for nested methods `parent.child`)
  and, for table cells whose text is a path, also into the **link title** (`"name#method"`) as tooltip.
  GitHub ignores the anchor and opens the file. In IDEA the FHIRconnect plugin takes over link opening
  in the preview and lands on the method; without the plugin the Markdown preview would report
  "Cannot find header to navigate to" for such a link. `#L42` line anchors work the same way but go stale.
- When a path is not known (a hand-over written without the repository at hand), use the bare
  backticked name; the plugin resolves it, GitHub shows text.
- Report formats: `[name#method](path#method)` replaces the older `<file> · <method>` and
  `<file>:<method>` spellings.
