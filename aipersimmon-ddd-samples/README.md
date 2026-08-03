# aipersimmon-ddd samples

One runnable sample per DDD flow scenario. Each directory demonstrates the flow its companion
document describes; the scenario catalogue is
`docs/analysis/analysis-00014-ddd-samples-scenario-catalog.md`.

These samples are independent of `aipersimmon-ddd-scaffold`. They do not reuse it, do not follow its
choices, and are not constrained by it. The only thing they track is the real behaviour of the
`aipersimmon-ddd` library.

## Building

One reactor, on purpose: a sample that builds only when someone remembers to build it rots.

```bash
mvn verify                                  # every sample
mvn -pl s01-http-command-query -am verify   # one
```

Java 21, Spring Boot 3.5.x, MyBatis-Plus. The library must be installed first
(`mvn -f ../aipersimmon-ddd/pom.xml install`). Data access is MyBatis-Plus throughout; the JDBC
variants of the framework modules are not used.

## Samples

| Scenario | Directory | Document |
| --- | --- | --- |
| S1 HTTP command and query | [s01-http-command-query](s01-http-command-query) | `analysis-00015` |
| S16 Tactical modelling | [s16-tactical-modelling](s16-tactical-modelling) | `analysis-00016` |

Ports: scenario N owns the block starting at `18000 + 10*N`, so several samples can run at once.
