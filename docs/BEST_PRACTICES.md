# Best Practices

> Extracted from the former `docs/README.md`. See [INDEX.md](INDEX.md) for the full documentation map.

1. **Enable detection selectively**: Start with defaults, enable specific detections as needed
2. **Use appropriate thread counts**: 10-50 for most tests, 100+ for stress testing
3. **Set realistic timeouts**: Allow time for your code + JVM overhead
4. **Run multiple invocations**: More invocations = more chances to catch bugs
5. **Test both platforms and virtual threads**: Each has different characteristics
6. **Monitor heap usage**: Virtual thread stress tests (HIGH/EXTREME) require significant heap
7. **Start with Phase 1**: Use core detectors before adding Phase 2 advanced features
8. **Use in CI**: Run comprehensive tests in continuous integration pipelines

