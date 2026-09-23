# ClientCraftMK4 Optimization List (P0–P4)

Deep-dive audit of the resolve hot path
(`ResolvePipeline.submit → CountEngine.compute → DpEstimator + ExactSimulator + Reachability`
on a single background worker, published to the render thread via mixins/overlay),
plus build/config. File:line references are against `src/main/java/com/clientcraftmk4`.

Legend: **[done]** implemented and in the build · **[open]** tracked, not done ·
**[skipped]** deliberately not done (outcome/behavior risk noted) — needs a maintainer call.

## Data-structure / algorithm / low-level review
Findings from the deep review (all landed items behavior-identical; ruled-out items with proof):
- [done] Slot→tag identity cache (`RecipeDisplays`): 26.3+ paid an `Optional` + HolderSet deref
  per tag slot per attempt. Memoised with identity semantics (records would hash DEEP — worse
  than the `Optional`); cleared with the slots cache on reload. All tag callers benefit.
- [done] dpExact gate-rejection telemetry (`gate:cyc/shr/crs/dir` in the debug line, zero hot-path
  cost when off): the next gate-tuning step must be data-driven — which condition rejects most.
- [killed with proof] `failedExact` memo: each entry is simulated at most once per compute (the
  count>0 / count==0 / container branches are exclusive), so a cross-entry failure memo would
  never hit. Removed from the open list.
- [ruled out] `RecipeDisplayId.index()` array-indexing: ids are already a one-int record
  (hash/equals trivial) — remaining map cost ~nil, and dense-index semantics are unverifiable
  offline. Not worth the risk.
- [ruled out] Vector API / Unsafe / fastutil: Vector is still incubator (mod distribution pain),
  Unsafe prefetch loses to the HW prefetcher on linear scans, fastutil is ~20MB off-classpath.
  Plain arrays + auto-vectorization-friendly counted loops already capture it.
- [ruled out] `computeForRecipe` no-cycle/cycle split: tree is 7% of a resolve; the branch is
  ~1% of tree. Code duplication for ~5µs/resolve — no.
- [parked] "Compiled" recipe bytecode (int[] slot programs interpreted by a switch, killing the
  6-way instanceof + Holder deref per slot): real but bounded upside (~0.2ms of 5.1ms verify by
  estimate) against a rewrite of the resolver's most subtle branches (tag/composite/remainder
  fallbacks). Needs profile proof before touching — the gate telemetry above is step one.
- [done] **Incremental resolves** (the huge algorithmic win): full O(all-recipes) recompute per
  inventory change replaced by dirty-subset recompute. Design as proposed, with two simplifications
  found during implementation: (1) DP + reachability stay FULL every resolve (cheap at ~1.2ms
  combined — incrementalizing them would explode complexity for little gain); only the verify
  loop (gates + sims) scopes to dirty recipes. (2) No fingerprints: value-diff of the two
  snapshots gives changed items directly.
  - New structures: per-entry tag lists (`RecipeIndex`, pre-expansion) → tag→recIdx index +
    int[][] item dependents (flat fields, once per model); craftable-order diff in
    `TagIndex.refreshInventory` (order-sensitive list compare, empty on no-rebuild);
    `DirtySet` pure-int fixpoint core (unit-tested headless: chains, diamonds, tag unions,
    invalid seeds); previous snapshot/counts ride the request (`ResolveResult.snapshot`,
    `ResolveRequest.previous/changedTags`).
  - Merge: pre-populate from previous, evict dirty keys (absent means zero — identical to a
    fresh map); `collAllEntries` still assembles fully; container-available items derived
    post-loop from the final container set (proven identical to per-hit accumulation).
  - Soundness: outcome = pure function of closure counts (dependents fixpoint) + tag picks
    (tag propagation + order diffs) + recomputed inputs (full DP/reachability/snapshot) +
    static per-model data. Non-graph changed items propagate tags only (in no edge — nothing
    concrete to propagate). Container diffs gated on `searchContainers`. Grid/model/first-run
    mismatches and >50% dirty fall back to the untouched full path. Log gains `incr/full`,
    `dirty:N`, `reused:N` (additive fields).
- [done] Sync-triggered warmup prefetch (from the research round): `requestWarmup` on recipe
  sync, consumed on tick when the world is ready; dedupe makes redundant warms ~free. Moves the
  cold cost into the join stall behind the placeholder.

## Data-type round (large-modpack scaling)
All JDK-only (no fastutil: ~20MB dep, unsafe runtime classpath, and plain arrays beat hash
maps of any library for dense ids). Bit-identical outcomes via overflow fallbacks everywhere
a non-graph item could theoretically appear:
- Flat tag data, once per model (`TagIndex` + `GraphBuilder.installFlatTagData`): per-tag
  `BitSet` over flat ids (memory scales in BITS — hundreds of tags stay cheap) + id-indexed
  tag sets. Kills the hot `holder.is(tag)` registry walks in fallback scans and `tagsOf`
  (registry path kept as pre-graph fallback). Volatile-published for render+worker readers.
- `PlanKey` record → class with precomputed hash (was rehashing 5 fields per craft click).

---

## P0 — Solver hot loop [done]

### 1. Gate all `System.nanoTime()` behind `debugLogging` (`CountEngine.java`) [done]
- Single `boolean debug` local; all intermediate timestamps guarded; `Stats.totalNs` is `0`
  when debug is off (`Stats` is write-only — `CollectionAssembler` never reads it).
- One concise `[CC] Resolve` summary line only when debug is on.

### 2. `WorkMap.resetTo` per-recipe sort (`WorkMap.java`, `CountEngine.java`, `ExactSimulator.java`) [done]
- `fillFrom` collects ids with a single `idMap.get` per entry, writes counts directly, then one
  `Arrays.sort(int[])` for the deterministic present order. No `ArrayList`/`Comparator`/boxing.

### 3. `WorkMap.resetTo` silent no-op on growth (`WorkMap.java:60`) [done]
- `ensureCapacity(n)` grows `count/present/inPresent/log` instead of returning. Constructor sizes
  `log` to `max(cap*2, 4096)` for deep `qty=999` sims. Covered by `WorkMapTest` growth tests.

### 4. `IdentityHashMap.get` per inventory entry (`WorkMap.java`, `DpEstimator.java`) [done, partial]
- Single `get` per entry; `DpEstimator.Ctx` fills `comb[]/inv[]` in one pass over inventory +
  container with no intermediate `combinedInventory` map copy. Overflow (`oid<0`) kept in tiny
  fallback maps.
- [done] flat `int[] invCounts` per compute (one conversion) + `boolean[] reachableFlat`;
  `directCountFlat`/`allEdgesReachableFlat` are array reads with overflow fallbacks, so
  bit-identical (see CountEngine). Registry-int indexing unnecessary — flat ids already are.

### 5. Double `RecipeDisplayId` hashing per entry (`CountEngine.java`) [done]
- `treeCounts/treeCombinedCounts` converted once per compute to `int[] countByRecIdx`; per-entry
  work is a single array read. `results` sized from `flat.totalRecipes()`.

### 6. Triple edge scan per DP-positive recipe (`CountEngine.java`) [done]
- Dropped the redundant `allDirectlyAvailableFlat` pass (`directCountFlat > 0` implies it;
  `== 0` fails the `>= count` gate anyway). Single fused scan with early `maxOps==0` break.
- [done] `reachable:Set<Item>` → `boolean[]` over flat ids (+ `Set` overflow for non-graph
  options); `directCountFlat` reads `optItemId[]` + `invCounts[]` — zero hashing.

### 7. Unbounded exact-simulation fan-out (`CountEngine.java`, `ExactSimulator.java`) [done, partial]
- Container `tryResolveOnce(combined)` gated on `treeCombinedCount > 0`.
- [done] Probe-first binary search: `k=1` probe decides every failing sim in one tiny attempt
  (the naive search spent ~10 top-down attempts, the first at a huge k being the priciest).
  Success paths seed `lo` from the already-computed direct count (verified by an attempt, never
  trusted blindly) and skip the search when edges are unreachable (reachability is complete, so
  unreachable proves exact == 0). All monotonicity-based — results bit-identical.
- [open] `failedExact:Set<recIdx:gen>` memo across entries in one compute (last verify item).

### 8. `DpEstimator` per-compute garbage (`DpEstimator.java`) [done, partial]
- No combined-map copy; `results` sized; `maxOps==0` early-break; `cycleVersion` overflow guard;
  `setCycleFlags` skipped when `recReverseTargets[ri]` is empty.
- [done] Data-type round: `comb/inv/memoCount/dOpsCount long[] → int[]` (halved widths,
  saturate-on-store — exact below 2^31, downstream caps at 999 regardless);
  `calculatePerRecipeCounts` returns `int[]` by `recIdx` (no boxed map, no conversion pass).
- [open] split no-cycle/cycle variants; fuse `isRecipeContainerOnly`; iterative topo order
  instead of `computeMemo` recursion.
- [skipped] `ThreadLocal<Ctx>` buffer pooling: saves GC pressure only (short-lived arrays are
  scavenged cheaply), not CPU — and stale memo-flag bugs would corrupt counts. Revisit only
  if modpack profiles show GC stalls.

### 9. `Reachability` O(n²) fixed point (`Reachability.java`) [done]
- Worklist queue over `dependents` (`ArrayDeque`, only parents of newly-reachable rechecked) +
  zero-ingredient seed pass preserving old fixpoint semantics.
- [done] hot per-option checks read the flat `boolean[]` (see #4); the object-graph walk
  remains only for the cold slot path.

### 10. `ExactSimulator` repeated full sorts [done via #2]
- Scratch `WorkMap`/contexts already cached per thread; each step is now hash-free.
- [open] cache `sortedEntries` per inventory generation.

### 11. `RecipeDisplays` per-call tree allocs (`RecipeDisplays.java`) [done, partial]
- `ConcurrentHashMap<RecipeDisplayId, List<SlotDisplay>>` normalized-slots cache; cleared on
  `CraftModel.markDirty/reset`. All entry-holding hot callers use the cached overload.
- [open] cache `TagKey` per `TagSlotDisplay` (`unwrapKey()` Optional alloc); return `List.of()`
  instead of `null` from `TagIndex.members`.

### 12. `resolveSlot → new ItemStack` per slot (`ResolveContext.java`, `QtyResolveContext.java`) [done, partial]
- `RecipeDisplays.resolveSlotItem` (map- and `WorkMap`-backed) returning `Item`/null; both
  resolvers use the fast path; single `graph.id` per slot. `TagIndex.tagFallbackItem(Item)`
  backs the no-alloc path; `hasDirectInputs` converted too.
- [done] Attempt-cost round (precomputation instead of nested loops): `RecipeIndex.requiredItems`
  (set-form of the consumes-walk, shared per model — sub-candidate loop does one `contains`);
  per-context output memo keyed by live-map identity (immutable published maps make identity an
  exact change detector); per-tag flat member-id arrays in `TagIndex` (version-checked, same
  order as the member sets) consumed by the `WorkMap` slot path — tag branches are array reads.
- [open] `failed:Set` prune.
- [skipped] Ascent-with-carryover search (verify k=1,2,4… reusing work state instead of binary
  search): unsound — a failed delta from carried-over state does not bound scratch feasibility
  when sub-crafts share pools (carried state is poorer in base but richer in intermediates), so
  the bracket would need a verifying probe that erases the savings. Attempt *count* is already
  optimal (probe + seeded binary + prescreen); remaining cost is per-attempt work, addressed above.

### 13. `CraftPlanner` per-iteration garbage (`CraftPlanner.java`) [done, partial]
- One shared `ResolveContext` per `buildPlan`; `cycles` pre-sized; single-loop totals;
  `computeDirectCrafts` uses cached slots + `resolveSlotItem`.
- [skipped] `computeDirectCrafts` deducts `maxCrafts*value` from `simMap` but `deductDirect`
  deducts only `value` once (under-deduct) + `HashSet` iteration nondeterminism (§7.3). Behavior
  decision + test required before touching.

---

## P1 — Render thread / per-frame

14. `CraftModel.current()` lock hold [done] — graph builds under a dedicated `GRAPH_LOCK` with
    double-checked `volatile`; index readers never block on the ~30ms build. The `recipeCount`
    walk stays (it is the change detector — removing it changes outcomes).
15. `InventoryProvider.current()` per-frame copies [done] — compare-before-snapshot (no `Map.copyOf`
    ×2 on the unchanged path; visible generations still bump only on change) + sized maps.
    `combined()` left as-is (once per worker compute, not per frame).
16. Solver on the render thread (`OverlayBuilder`) [skipped async redesign — outcome risk];
    narrow wins [done]: cached `getSlots(entry)`; dropped the double `resolveSlot` on the empty
    fallback path (pure function — recompute returned empty again); sized inventory copies;
    hoisted `SubCraftCtx` (graph/flat/context/in-progress) per build; writeback iterates the
    present list instead of `0..n` (proven equivalent: untouched ids read 0 == map default).
17. `refreshActiveGridCraftability` volatiles [done — already single reads into locals; no change].
18. `RecipeButtonMixin` per-button reads [done] — `ResultButtonRenderer` snapshot overloads; one
    volatile read + one `getCurrentRecipe()` per button per frame (was three of each).
19. Tooltip rebuild per frame [done] — identity-keyed single-entry cache in
    `OverlayRecipeComponentMixin` (grid/result stacks are stable refs between rebuilds; content is
    a pure function of the stack).
20. `applyFilteredResults` filter+sort per publish [done] — memoized on
    `(ResolveResult identity, query, filteringCraftable)`; defensive copy on the way out so
    vanilla can never mutate the cache; ranks/container checks use the snapshot.
21. Scroll burst [partial] — `WeakReference` churn skipped when referents unchanged [done];
    [skipped] debounce (drops intermediate variant steps — outcome risk) and `ContextMap` caching
    (`fromLevel` purity unverified).

## P2 — Pipeline / caching / threads

22. `computing` flag race (`ResolvePipeline.java`) [done] — flag now clears in render-thread
    `drainPending` (all `submit()` callers are render-thread, so no interleave); worker `finally`
    only clears as a fallback when the render post itself threw; drain routes through `submit()`.
23. `RenderSync.run` lambda allocs [skipped] — one small lambda per ~8ms compute; negligible.
24. Coarse `reset()/resetLatest()` [skipped] — per-collection diffs risk stale/mixed tab states.
25. `CraftPlanner.planCache` races + thrash [done] — synchronized access, cap 8 → 32.
26. Linear 999-deep planner loop [skipped] — binary search changes plan structure, not just speed.
27. `WORKER` never shut down [done] — `CLIENT_STOPPING` → `ResolvePipeline.shutdown()`.
28. Stability poll without timeout [done] — 100-tick (~5s) cap, then forced refresh; normal path identical.

## P3 — Allocations / maps

29. `TagIndex.members()` [done — sized `ArrayList`s]; kept `ConcurrentHashMap` (render + worker
    reads make the concurrency load-bearing).
30. `anyTagMember` per-call `ItemStack` [skipped] — callers may mutate the returned stack; sharing
    one instance risks cross-talk. (Hot paths already use the `Item` overload from P0 #12.)
31. `GraphBuilder` per-recipe allocs [done] — dropped the whole-index copy (reads index directly);
    sized maps/sets; single-item slot fast path (no set/sort); hoisted `BY_REGISTRY_ID`
    (`comparingInt` allocated per sort); cached-slot overloads in `buildCraftedItem`/
    `getConsolidatedEdges` (slots were parsed ~4× per recipe — now map hits).
32. Eager `new BaseResource` in `getOrDefault` [done] — `nodeFor` get + null-check (3 sites).
33. Suspect-edge detectors [done] — per-edge `HashSet` (was O(opts) inner scan); seen-set instead
    of per-recipe `HashMap`; the two flag scans fused; `recData` int[3]-per-recipe list removed
    (reads straight off `CraftedItem`); `IdentityHashMap`/`dispIdToRecIdx` sized.
34. `RecipeIndex` [done — `LinkedHashMap(1024)`, per-output `ArrayList(2)` (avg 1.17 recipes/output),
    cached `totalCount` with identical semantics]; kept lazy `tagsOf` (inversion only moves work).
35. `CollectionAssembler` [done sizing + fused displayable/craftable loop]; [skipped] the
    rank-2 `autoCraft` marking change (alters vanilla `selectRecipes` skipping = visible behavior).
36. `InventorySnapshot` copies [done] — `unmodifiableMap` views (sole producer passes fresh maps;
    same failure mode as `copyOf`, which was also immutable).

## P4 — Build / config / logging

37. Gradle flags [partial] — `org.gradle.caching=true` [done]; [skipped] configuration-cache
    (Stonecutter+Loom risk) and `jvmargs` retune (10G is harmless).
38. Full `fabric-api` dep [skipped] — subset modules are NOT published under the aggregate
    `fabric_api_version` (build-verified: `fabric-networking-api-v1:0.154.2+26.1.2` 404s);
    per-module pins would add version fragility on every MC bump for compile-only savings.
39. Opaque ModMenu coords [skipped] — zero runtime benefit, wrong-version risk; maintainer call.
40. `depends:fabric-api:*` + strict mixins [skipped] — tightening changes load behavior on odd setups.
41. Config fragility [done] — `JsonSyntaxException` → defaults (was init-killing crash); atomic
    `.tmp + move` save (was corruptible); same file content on success.
42. Duplicated logger literals [done] — `Constants.MOD_ID`.
43. Build hygiene [partial] — dead `unobfuscated=true` removed + `duplicatesStrategy = EXCLUDE`
    [done]; [skipped] Java 25 → 21 downgrade (toolchain compat risk).
44. Tests [partial] — `WorkMapTest` growth/rollback regression tests for the P0 `ensureCapacity`
    fix [done]; [open] JaCoCo thresholds + coverage for DP/simulator/planner/config/AutoCrafter.
