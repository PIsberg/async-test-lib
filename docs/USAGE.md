# Using Async Test Library

How to install the library, annotate a test, choose detectors and read what they report.

## Topics

Each topic lives in its own file under [`usage/`](usage/). Read the one you need rather than the whole set.

| Document | What it covers |
|----------|----------------|
| [getting-started.md](usage/getting-started.md) | Installing with Maven or Gradle, and annotating your first test |
| [configuration-options.md](usage/configuration-options.md) | Every `@AsyncTest` parameter and the detectors each phase enables, with context accessors and examples |
| [other-ways-to-run.md](usage/other-ways-to-run.md) | The optional agent, `AsyncTestRunner` without the annotation, and manual legacy diagnostics |
| [examples.md](usage/examples.md) | Race condition, opting out of expensive detectors, deadlock, and virtual-thread stress tests |
| [results-and-practices.md](usage/results-and-practices.md) | Reading a finding, adopting into an existing suite, reproducing a failure, and configuration advice |

## Support

For issues, questions, or feature requests:
- GitHub Issues: https://github.com/PIsberg/async-test-lib/issues
- Documentation: https://github.com/PIsberg/async-test-lib/wiki

## License

[PolyForm Noncommercial License 1.0.0](../LICENSE): free for non-commercial use. Commercial use
needs an annual licence key; pricing and terms are in the README's
[License](../README.md#license) section.
