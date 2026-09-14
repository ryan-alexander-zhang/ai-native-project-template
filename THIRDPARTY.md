# Third-Party References

External code and specs used **for reference only**: not vendored, not a build
dependency. Clones stay **outside** the repo. Copy only the files relied on
into `docs/reference/<slug>/source/` (raw material, excluded from board and
drift checks); distill them into `docs/reference/reference-<nnnnn>-<slug>.md`.

One section per reference, holding only what a contributor needs to find and
pin the source; working notes live under `docs/reference/`.

## <Provider / Library name>

| | |
| --- | --- |
| Upstream | <repository or spec URL> |
| Pinned commit | `<sha>` (`<date>`) |
| Local clone | `<path outside this repo>` |
| Official docs | <docs URL> |
| Raw material | `docs/reference/<slug>/source/` |
| Distilled excerpts | `docs/reference/reference-<nnnnn>-<slug>.md` |

Clone (if the local copy is missing):

```bash
git clone <url> <local-clone-path>
git -C <local-clone-path> checkout <sha>
```
