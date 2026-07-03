# Third-Party References

External code and specs used **for reference only** — not vendored into this
repo, not a build dependency. Keep clones **outside** the repo so they do not
pollute version control. Distill the parts you actually rely on into
`docs/reference/`.

Add one section per third-party reference. Record only what a contributor needs
to find and pin the source; the working notes live under `docs/reference/`.

## <Provider / Library name>

| | |
| --- | --- |
| Upstream | <repository or spec URL> |
| Pinned commit | `<sha>` (`<date>`) |
| Local clone | `<path outside this repo>` |
| Official docs | <docs URL> |
| Distilled excerpts | [`docs/reference/<slug>/`](docs/reference/) |

Clone (if the local copy is missing):

```bash
git clone <url> <local-clone-path>
git -C <local-clone-path> checkout <sha>
```
