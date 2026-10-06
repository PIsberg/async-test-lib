<!-- VIBETAGS-START -->
# Rules for RunOutcomes

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: Counts are LongAdders and values sit in a ConcurrentHashMap merge, so concurrent recorders never lose an update; the assertions read totals after the run, when no worker is still recording.
<!-- VIBETAGS-END -->
