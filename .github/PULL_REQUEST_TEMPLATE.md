<!-- What failure does this change prevent? The diff already shows what changed. -->

## Why

## How it was verified

<!-- Which tests, run how, and what they would have to see to fail. If you broke the fix
     deliberately to confirm the test goes red, say so — that is worth more than a green run. -->

## Checklist

- [ ] A behaviour change ships with a test that fails without the fix
- [ ] A new or changed detector is tested against the buggy code **and** its correctly
      synchronized twin, and its reported severity matches what it can actually prove
- [ ] Docs the change makes wrong are updated in this same change
- [ ] Nothing was hand-edited between `VIBETAGS-START` / `VIBETAGS-END` markers
- [ ] Public API changes are intentional and carry the right version bump per
      [docs/SUPPORT_POLICY.md](../blob/main/docs/SUPPORT_POLICY.md) — japicmp will fail the
      build otherwise
- [ ] Commits an agent authored carry the `Co-Authored-By` trailer, so provenance is
      readable from `git log`
- [ ] A new dependency was proposed with a reason and a `docs/DEPENDENCIES.md` row, not
      just added
- [ ] CI is green, or the failure is explained below

## Anything reviewers should push back on
