# User Stories

This directory stores user story documents.

## Include

- user stories attached to a PRD
- acceptance criteria for one functional requirement
- definition of done for that story

## Exclude

- standalone product requirements
- implementation design
- stories without a parent PRD

## Note

Each user story file uses a PRD id as `parent` and a `function_requirement_id` that matches a
unique `FR-xx` item in that PRD. User story front matter uses `type: us`, and ids and filenames use
the `us-00001-...` prefix.
