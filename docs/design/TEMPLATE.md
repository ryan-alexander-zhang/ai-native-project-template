---
id: design-00001-example-slug
type: design
status: draft|active|archived
kind: domain|storage|module|contract|mapping|lifecycle|interaction|algorithm|mechanism|deployment
informs: [<spec-id | plan-id>, ...]           # may be empty while the design waits to be picked up (a design can precede any spec)
implements: [<quality-<n>-QS-<i>.<k>>, ...]   # the quality scenarios this design realises; omit when it realises none
---

Front matter above; the body comes from the template of the chosen `kind`
(`docs/design/README.md`, Kinds):

| `kind` | Body template |
| --- | --- |
| `domain` | `TEMPLATE-domain.md` |
| `storage` | `TEMPLATE-storage.md` |
| `module` | `TEMPLATE-module.md` |
| `contract` | `TEMPLATE-contract.md` |
| `mapping` | `TEMPLATE-mapping.md` |
| `lifecycle` | `TEMPLATE-lifecycle.md` |
| `interaction` | `TEMPLATE-interaction.md` |
| `algorithm` | `TEMPLATE-algorithm.md` |
| `mechanism` | `TEMPLATE-mechanism.md` |
| `deployment` | `TEMPLATE-deployment.md` |

Every numbered section of the body template stays, in order; one that does not
apply reads `n/a — <where it lives>`. Nothing is added: a section the template
lacks is another design.
