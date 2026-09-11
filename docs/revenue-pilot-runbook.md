# Governed revenue pilot runbook

This pilot measures paid-net-revenue uplift from an already approved, human-operated change. The evaluator is advisory and non-actuating: it cannot make payments, buy ads, publish content, or write to external systems. An empty `:result/actions` is part of every result.

## Before the window

1. Select one aggregate cohort and record its exact dimension. Do not include direct identifiers.
2. Freeze adjacent baseline and measurement UTC windows, baseline paid net revenue, target uplift in basis points, minimum cohort size, minimum acceptable paid net revenue, and maximum refund rate.
3. Obtain tenant-owner approval through the MarketingGovernor workflow and record the opaque actor and durable evidence reference. Approval of this plan does not authorize execution.
4. Confirm `:pilot/prohibited-actions` is exactly `#{:payments :ad-spend :publishing :external-writes}`. Any mismatch causes a hold.
5. Have a human operate any approved change in a separate system under separate authority. Do not connect the evaluator to that system.

## Evaluate

Load `test/fixtures/revenue_pilot.edn` as the format example and call `(growth.revenue-pilot/evaluate plan observation)`. The observation must be a read-only aggregate for the exact approved cohort and measurement window. Archive the complete returned map with the source snapshot reference in the existing audit system; this library performs no archival write.

Interpret `:result/status` as follows:

- `:hold`: schema, approval, cohort, window, metric, or non-actuation boundary failed. Do not interpret uplift.
- `:stop`: at least one declared safety threshold was crossed. A human operator must stop the separately operated pilot and record evidence.
- `:target-met`: measured uplift meets the declared basis-point target and no stop condition fired. This is evidence for human review, not permission to roll out.
- `:target-not-met`: the target was missed without crossing a stop threshold. End or redesign only after human review.

Refund rate and uplift use integer basis points with deterministic half-up rounding. A zero baseline or zero settled gross produces an unmeasurable rate; refund-rate unobservability triggers stop, while a zero baseline cannot meet a positive target. Preserve the approval record, cohort, window, baseline, measured value, uplift, refund rate, cohort size, reasons, and evaluator version returned in the audit/result schema.

## Verification

Run `kbb -M:dev:test`, `kbb -M:lint`, and `git diff --check`. Changes to metric semantics, rounding, schema, or stop precedence require a new evaluator version and updated golden fixture.
