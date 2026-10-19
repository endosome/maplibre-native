# Terra performance assessment and regression plan

**Scope:** Terra's Android-first and iOS outdoor-map workload, not general-purpose MapLibre tuning. This is analysis and planning, not an implementation specification or a claim of measured improvements.

**Source baseline:** `6e22120883` (reviewed 2026-10-19). Record the exact commit of the measured application binary before comparing source findings with those measurements. No phone benchmarks were run for this assessment. The first assessment was committed as `897ee57363`; the second-pass review below revises the execution order and records additional source evidence against the same engine baseline.

## 1. Workload, evidence and priorities

Terra is a mountaineering, ski-touring and avalanche-safety map platform. The map must remain responsive during hours outdoors, on battery, in cold conditions, with weak data or offline. Memory, heat, energy and process survival matter alongside frame rate. Native performance is a product advantage over a competing GL JS WebView application.

The target is the terrain-3d fork, versioned `13.6.1-terra.N`, with the specified upstream terrain PRs, mapriot's terrain changes and Terra fixes. Android uses the multiBackend AAR, choosing OpenGL ES or Vulkan at process start, with one imperatively driven full-screen MapView and a shared OkHttp client. Compose does not rebuild the view during camera movement. iOS uses SwiftUI and Metal and must remain at parity. Navigation recording lives in a separate Android foreground-service process; map following remains in scope.

The characteristic view is steep high-mountain terrain at z11–16, pitch 45–60 and up to the engine limit, with turning, pinching and panning. 2D planning remains important. Datasets are immutable, versioned PMTiles archives served as CDN z/x/y endpoints with one TileJSON per dataset. Future offline data uses local `pmtiles://file://` archives, one per dataset and region.

Layers include 512 px Terrarium raster DEMs (lossless WebP or PNG, LiDAR-derived), terrain exaggeration 1.0–1.5, almost-always-on hillshade, categorical slope/aspect color-relief, dense alternative vector slope fills, contours and contour labels, an OSM mountain basemap, avalanche-zone fills and routes, and terrain-aware peak/hut symbols with collision. Summit variants add icon-text-fit boxes or generated images. Presets use visibility without reloading; derived benchmark styles and genuine reloads still happen. One reload regenerated 220 summit images.

Reference devices: Pixel 8a/Mali-G715, Android 17, 60 Hz with 120 Hz planned; a mid-range Samsung and low-end PowerVR handset are planned. Android minimum API is 26. iOS minimum is 15.0, with iPhone 7/A10 as the test phone.

### Supplied measurements

- A 20 s z13/pitch 60 orbit: GLES 55–57 mean fps, slowest 5% around 16 ms on screen; Vulkan similar mean, slowest 5% around 33 ms. At pitch 45 both hold the 60 Hz display. The cap hides rendering headroom.
- Slope polygons cost 11–25 fps, reach about 30 fps mean in the worst view, and produce 50–70 ms slow frames. They trigger thermal throttling within roughly 40 s and delay map-at-rest by 1.1–1.9 s. Raster slope/aspect cost 0.5–3 fps; hillshade about 1 fps.
- Slope z13 tiles are 170–194 KB gzipped, up to 715 KB decoded, with 100–120k vertices. Pitched views contain dozens of z12–13 tiles.
- Process-start to first complete idle view is 1.5–1.9 s cold/empty-cache; activity first frame is about 0.6 s. A cleared application pays network, style parsing and compilation work.
- Thermal state splits identical runs around 50 versus 30 fps.
- GL JS evidence, **not native measurements**: 2× drapes dominate zooming; integer zoom crossings can create roughly 90 ms GPU bursts. Moving at 1× and restoring 2× after 500 ms, one central tile per frame, improved a pitch 45 zoom from 33 to 85 fps. Moving pixel ratio 1 gave +21–38%. Desktop drapes retained 0.5–0.7 GB. Coarser DEMs increased drape size and cost. Initial compilation cost roughly 1 s. A metadata/DEM/base/glyph request chain delayed completeness. Label zoom changes in the drape layer key did not matter.

### Highest priorities (revised after the second pass)

Terra's largest opportunities are avoiding repeated terrain work, bounding bursts of new work, and replacing dense slope geometry. Backend-specific tuning follows those shared opportunities.

| Priority | Finding | Evidence / prerequisite |
| --- | --- | --- |
| 0 | Establish correctness-qualified performance references | R1: cache invalidation/recovery; R5: equal GL depth coverage; R6: exact categorical semantics |
| 1 | Prefer raster slope classes after class-edge validation | Supplied polygon/raster measurements; Q1, M12, R6 |
| 2 | Enable hillshade prepare-result reuse | No enabling caller found; Q2, with R1 producer revisions |
| 3 | Repair terrain-depth invalidation | Preparation marks depth dirty every call; Q3 |
| 4 | Remove Metal offscreen CPU waits | Each offscreen swap commits and waits; R2; can dominate iOS arrival/zoom tails |
| 5 | Select mesh coverage before drapes and GPU DEM residency | Q4 and R3; avoid unused targets and ancestor uploads |
| 6 | Budget actual work, including new drapes and every relevant producer | M1 and R4; current mode is narrower than its name |
| 7 | Add motion quality with guaranteed full-quality convergence | M2/M3; use R8 deadline/quality tests |
| 8 | Resolve GL/Vulkan depth-path asymmetry with equal output | M6 and R5; faster missing occlusion is not a valid win |
| 9 | Remove startup dependency latency and persist compilation caches | Q6/M9, request DAG and controlled cache states |

Already implemented and **not new proposals**: the user's cover/idle/centre-elevation fixes, static-render partial-frame cap, Vulkan missing-image crash avoidance, click timing fix, public elevation queries, mapriot's overzoom/drape/distance/completion changes; plus per-target drape signatures, skipped unchanged draped-layer tweakers, hillshade active-target cleanup, a DEM retention policy, a shared CPU terrain grid, GL depth instancing and progressive loading modes. Second-pass review found that the DEM limit is not a hard bound and the build budget covers only fill/line construction; see R3 and R4. Existing mechanisms still require effectiveness and correctness tests.

Out of scope unless they affect Terra: globe, custom/native layers, legacy annotation API, shipped location component, tile-by-tile offline packs, Mapbox paths, desktop/Qt/Node-specific tuning except Node render tests, and unused style features. Fog/sky is not assumed available as a way to hide missing far terrain.

## 2. Engine path

Android's UI thread owns MapView, gestures and imperative controller calls. Camera/style changes reach the native map and renderer via update parameters. MapRenderer encapsulates a render thread; GL/Vulkan surface implementations own scheduling and lifecycle details. Compose/activity frames are distinct from actual map-surface presentation.

File-source work handles cache lookup and HTTP/local reads. Worker actors decode images/vector tiles, generate geometry buckets and shape/layout symbols. Results return asynchronously. Camera-dependent symbol placement/collision/projection remains renderer-side work; terrain adds elevation sampling and projection.

```mermaid
flowchart LR
    A[Camera, style or tile event] --> B[Map update and scheduling]
    B --> C[Style evaluation and tile cover]
    C --> D[Requests and cache lookup]
    D --> E[Workers: decode, geometry, symbol layout]
    E --> F[Render-thread bucket and drawable updates]
    C --> F
    F --> G[Uploads and tweakers]
    G --> H[Hillshade preparation]
    H --> I[Dirty terrain drapes]
    I --> J[Terrain depth and surface]
    J --> K[Terrain-aware symbols and remaining layers]
    K --> L[Submit and present]
```

Terrain adds elevation-aware mesh coverage, DEM ancestor resolution, mesh/drape association, offscreen drapes, symbol-occlusion depth and terrain-aware collision/picking. The current mesh is a regular 128×128 grid displaced by DEM sampling in shaders: it is **not** a unique worker-tessellated elevation surface per tile. Drawable creation nevertheless copies cached grid vertices/indices into each terrain drawable.

### Source anchors

- [Frame orchestration](src/mln/renderer/renderer_impl.cpp): drape coverage, signatures, uploads and scheduling.
- [Terrain](src/mln/renderer/render_terrain.cpp): computeMeshCover, update, createDrawableForTile, renderDepth, prepareDepthTarget, DEM binding.
- [Terrain declarations](src/mln/renderer/render_terrain.hpp): grid, DEM cache and GL array/instance limits.
- [Texture pool](src/mln/renderer/texture_pool.cpp): target lifetime and sizing.
- [Render target](src/mln/renderer/render_target.cpp): render-once and per-target drape caching.
- [Hillshade](src/mln/renderer/layers/render_hillshade_layer.cpp): target construction and active-set cleanup.
- [Load modes](include/mln/map/mode.hpp): scheduling budgets and mesh caps.
- [Android renderer](platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/maps/renderer/MapRenderer.java): scheduling, cap and FPS callbacks.
- [Symbol placement](src/mln/text/placement.cpp) and [collision](src/mln/text/collision_index.cpp).
- [Tile pyramid](src/mln/renderer/tile_pyramid.cpp), [DEM data](src/mln/geometry/dem_data.cpp), [DEM worker](src/mln/tile/raster_dem_tile_worker.cpp).
- [PMTiles](platform/default/src/mln/storage/pmtiles_file_source.cpp).

## 3. Ranked optimization inventory

Within each effort group, order is approximately expected user-visible gain per effort.

**Notation:** A = GLES, Vulkan and Metal (iOS included); G/V/M = backend-specific. Measured means supplied Terra observations. Strong means source evidence or direct resource accounting. Hypothesis means phone measurement decides. Small/medium/deep effort means approximately days/one-to-several weeks/substantial architectural work including validation, not a commitment.

Unless explicitly identified as an existing mechanism or an open upstream issue, each item is a **new proposal and upstream candidate**, not accepted upstream work. Terra device defaults belong in the application/fork. Benchmark IDs refer to section 6. Each proposal requires both a performance check and a correctness guard. Percentages are not invented where only eliminated work can be predicted.

### 3.1 Quick wins

**Q1. Prefer raster slope classes.** Scenario/stage/backend: pitched overlays, zoom and thermal endurance; network, workers, uploads, vertex/fragment work; A. Gain: the supplied measurements strongly favor raster, but subtracting the endpoints of independently reported FPS-cost ranges is not a valid paired effect estimate. Obtain the actual delta from same-camera paired runs, and report frame-time and energy differences as well as FPS. The polygon completion penalty is another large opportunity. Small application/data change if raster semantics match; class accuracy is the risk. Complete nearest/class-preserving rendering before adoption. Retain vectors where geometry is required without drawing the entire dense dataset. Verify DEVICE-OVERLAYS, START and soak, with threshold/tile-seam/zoom class tests. Status: Terra choice; nearest support relates to [#4117](https://github.com/maplibre/maplibre-native/issues/4117).

**Q2. Enable hillshade prepare reuse.** Scenario/stage/backend: nearly every view; GPU fragment/pass/bandwidth; A. RenderTarget::render supports setRenderOnce, but RenderHillshadeLayer::update does not enable it when creating targets; repository search found no caller. Strong elimination of repeated preparation for unchanged DEMs; milliseconds unmeasured. Pair this change with R1: the prepared-image revision must invalidate dependent drapes, including late border updates. The source comment's historical ~50 ms is not Terra evidence. Small change; invalidate for replacement/border backfill, which already creates fresh targets. Verify HEADLESS-PASSES and hillshade orbit: one prepare per DEM/border revision, including seams and late neighbors.

**Q3. Repair terrain-depth cache invalidation.** Scenario/stage/backend: fixed camera with puck/route/fade updates; GPU geometry/fragment/bandwidth; A. prepareDepthTarget reattaches the group and marks depthDirty even for an existing target; renderDepth calls it before testing the flag. Strong opportunity to remove the depth pass on these redraws, not during orbit. Small change; dirty on creation, resize, terrain/DEM/exaggeration or projection changes rather than unchanged attachment. Verify IDLE-DYNAMIC: zero depth passes for puck-only changes, with camera/DEM/resize occlusion tests.

**Q4. Select the mesh cover before drape allocation.** Scenario/stage/backend: steep pitch and overlays; CPU allocation/GPU memory/drape work; A. Renderer::Impl allocates from computeMeshCover before RenderTerrain::update trims to maxMeshTiles. Strong savings proportional to targets never sampled by selected meshes; instrument the set difference. Small-to-medium refactor; preserve intentional ancestor fallbacks, never shrink visible coverage further. Verify HEADLESS-COUNTS and DEVICE-PITCH: selected coverage plus explicit fallbacks equals the pool, with horizon/bottom-corner tests.

**Q5. Cache summit image generation across reloads.** Scenario/stage/backend: reload, locale, startup; app CPU/JNI/atlas upload; A. Strong elimination of regeneration of the reported 220 images; registration may remain necessary. Bounded keys include text, locale, appearance, size and data version. Small change; stale locale/theme is the risk. Prefer one stretchable box plus text where equivalent to per-summit bitmaps. Verify STYLE-RELOAD generation/atlas counts and locale/icon-text-fit goldens. Mainly app-side.

**Q6. Bundle/embed versioned metadata.** Scenario/stage/backend: empty-cache start/weak data; network dependency latency/JSON parse; A. Strong elimination of actually observed style/TileJSON discovery RTTs. Embed tiles/bounds/zooms/encoding/attribution where permitted or warm metadata together. Small change; metadata/data-release consistency is essential. Verify START-NETWORK DAG and equivalent-source/stale-release tests. Do not assume GL JS's DEM→base request dependency applies identically: native TilePyramid already has initial coverage/prefetch.

**Q7. Use incremental mutations for benchmark layer insertion when equivalent.** Scenario/stage/backend: presets/test setup; parse/layout/atlases/requests; A. Strong avoidance of whole-style work. Keep product visibility switches; batch incremental changes where possible, separately benchmark genuine reloads. Small change; ordering/transitions may differ. Verify STYLE-SWITCH images and request/layout counts. Existing application APIs.

**Q8. Prefer GLES on the measured G715 configuration for now.** Scenario/stage/backend: pitch 60 tails; presentation/GPU-driver; G/V. Measured slow-end improvement 33→about 16.7 ms, not double mean fps. Small device/OS/driver policy, with override and retest each bump. Provisional GL on affected PowerVR is correctness-driven, not an assumed speed win. Verify backend A/B and physical panel: [#4506](https://github.com/maplibre/maplibre-native/issues/4506) reports corruption absent from screenshots.

**Q9. Remove nonessential terrain-overlay fades.** Scenario/stage/backend: arrival/switch/idle; scheduling and repeated drapes; A. Strong if transitions cause rebakes, small otherwise. Start with zero raster-overlay fade and zero/short slope/aspect paint transitions; retain label fades if useful. Small change; loading pops are the risk. Verify ZOOM bake/revision counts and arrival-order videos. Existing properties where supported; check Terra's exact style.

**Q10. Enforce loaded-static zero work.** Scenario/stage/backend: battery/heat; frames/timers/wakeups; A. Android already exposes WHEN_DIRTY. Audit deferred loops, fades, completion state, location animation, timers and app polling. Strong if unnecessary wakeups exist; zero gain if already idle. Small-to-medium; missed redraw risk. Verify IDLE after completion/toggle/restore, then event tests that wake exactly as necessary.

**Q11. Upload terrain placeholders before consumption.** Scenario/stage/backend: first terrain frame/toggle/partial data; upload/descriptor ordering; V, parity A. The skip-on-missing-image fix prevents crashes but may omit content. Prepare depth/elevation placeholders before upload/descriptor resolution. Strong correctness, possibly fewer recovery frames; no FPS promise. Small-to-medium lifetime/layout risk. Verify TERRAIN-TOGGLE, missing DEM, first-frame tests and Vulkan validation. Known remaining fork fix.

**Q12. Audit the shared OkHttp client's actual behavior.** Scenario/stage/backend: initial view/fast movement; network queueing; Android G/V. SDK default client configures 20 per-host requests, but the supplied Call.Factory may not inherit that. Record effective limits, queues, reuse, cancellation and interceptors. Hypothesis of RTT-scale savings if map requests queue behind app traffic. Small; overconcurrency hurts mobile links. Verify NETWORK with 4/8/12/20 concurrency, fixed RTT/loss and app traffic. Do not maximize concurrency blindly.

### 3.2 Medium changes

**M1. Budget all progressive work, including new drapes.** Scenario/stage/backend: zoom/start bursts; render CPU/upload/GPU; A. TerrainLoadMode gates new fill/line tile construction and drape rebakes, but not all producer/build paths; new drapes always render. R4 defines the corrected scope and multi-source cost accounting. Budget them using ancestor fallback or a valid lower-quality initial bake. Prioritize projected importance/foreground/central coverage, prevent starvation. Strong reduction of burst size, unknown completion tradeoff. Medium; holes/stale content/false completion risk. Verify ZOOM-BURST with synchronized arrivals, per-stage deadlines and final-quality convergence. Extends existing modes.

**M2. Movement-sensitive drape resolution.** Scenario/stage/backend: pitched zoom; fragments/bandwidth/memory; A. Use 512² moving near targets, restore current 1024² quality after 300–500 ms stillness, initially one refinement/frame under a time budget. Existing pool ignores changed requested size for a retained tile: resizing/rebinding is prerequisite. Strong 75% reduction in target pixel count, end-to-end gain hypothetical; web 33→85 fps is not a native forecast. Medium; double-allocation/seam risk, atomic replacement required. Verify ZOOM, size transitions and settled reference equality.

**M3. Runtime render scale independent of logical density.** Scenario/stage/backend: 2D/3D motion; fragments/bandwidth; A. Preserve viewport, symbols, picking and cover while reducing physical render dimensions. Do not animate construction-time pixelRatio as a framebuffer-only control. Web +21–38% motivates testing; native gain hypothetical. Medium coordinate/surface/text risk; quantized reusable sizes and hysteresis. Verify RESOLUTION, pick invariance, sharp final images and resize peaks.

**M4. Byte-budgeted drape pool and size-class reuse.** Scenario/stage/backend: memory/zoom reversals; allocation/driver/GPU memory; A. Current tile-keyed map removes departed targets immediately. Add bounded allocation reuse and only a measured-small recently-used content cache; account for in-flight resources. Strong bounded-memory/allocation benefit; CPU gain unknown. Medium stale-content/fence risk. Verify MEMORY-SOAK, reversal/allocation counters and poisoned reuse tests. Avoid large speculative caches.

**M5. Share terrain GPU grid buffers.** Scenario/stage/backend: arrivals/DEM upgrades; CPU copies/allocations/upload; A. createDrawableForTile copies cached grid vertices/indices; non-GL repeats for depth. Use immutable shared buffers per variant and per-tile bindings/uniforms. Trace backend dedup first. Strong CPU-copy removal; GPU gain conditional. Medium ownership/context risk. Verify MICRO-MESH, ZOOM, buffer identities and restored-context images.

**M6. Port depth instancing to Vulkan/Metal.** Scenario/stage/backend: steep orbit; submission/state/descriptors/upload; V/M. GL is already instanced; non-GL depth is per tile. Strong reduction from O(tiles) draw calls toward a small number; whole-frame gain unknown. Medium array-capacity/mapping/device-limit risk. Do not reproduce GL's fixed 64-instance limit without handling larger covers. Verify BACKEND-DEPTH, >64 coverage, ridges and ancestor fallback. Port of existing fork work.

**M7. Share DEM GPU storage across consumers.** Scenario/stage/backend: memory/arrivals; upload/GPU memory; A, especially G. Terrain, hillshade preparation and color-relief can create separate textures; GL also has an array. Share by content/revision with separate samplers where possible. Distinct elevation/slope/aspect datasets remain distinct. Strong ~1.02 MiB per duplicated 516² RGBA8 image, before driver overhead. Medium border/sampler/lifetime risk. Verify DEM-UPLOAD unique allocation counts, late-neighbor and source-toggle images.

**M8. Partial DEM-border and atlas updates.** Scenario/stage/backend: neighbors/icons/glyphs arriving; upload/allocation; A. Backfill invalidates prepared resources. Subupload changed strips, then regenerate affected output/edges where worthwhile; subupload atlas regions where the current backend replaces the atlas. Strong byte reduction, timing depends on batching. Medium; many tiny transfers may lose. Verify DEM-UPLOAD bytes/submissions and corner/atlas-bleeding images.

**M9. Persistent compilation caches and selective prewarm.** Scenario/stage/backend: start/first overlay; driver/render CPU; A. Vulkan has an in-memory VkPipelineCache created empty. Add validated disk persistence; investigate GLES program binaries and supported Metal binary archives. Searches found no persistent equivalents. Key by build/shader schema/device-driver/backend identifiers; invalidate safely. Strong when compilation is measured critical, no assumed native 1 s. Medium corruption/stale-cache/contention risk. Verify START-CACHE fresh/warm/invalid/driver-mismatch states and output. Serialize off the interaction path.

**M10. Reduce synchronous Vulkan initialization and staging churn.** Scenario/stage/backend: start/zoom arrivals; CPU/transfer/synchronization; V. submitOneTimeCommand submits then waits on a fence; dummy texture uses it. Subuploads allocate staging storage. Trace actual call sites rather than asserting all uploads wait. Batch initialization and use bounded staging rings/arenas retired by fences. Strong for measured waits/bursts, magnitude unknown. Medium synchronization risk. Verify BACKEND-UPLOAD wait/submission traces, validation and repeated upload stress.

**M11. Audit mobile attachment load/store and transitions.** Scenario/stage/backend: drapes/hillshade/depth; bandwidth/pass cost; A. Store sampled drape color; avoid storing dead transient depth/stencil. Vulkan offscreen depth/stencil already uses DontCare stores, and Metal offscreen depth already uses StoreActionDontCare: these are baseline behavior, not new savings. Inspect remaining GLES invalidation, attachment allocation/usage and Metal transient or shared scratch attachments where valid; see R10. Hypothesis, potentially important on Mali/PowerVR/A10. Medium undefined-read risk. Verify BACKEND-PASSES traffic/counters, validation and overlap/stencil images.

**M12. Portable exact categorical color-relief.** Scenario/stage/backend: slope/aspect; fragment sampling/compatibility; A, especially V. Current stops use RGBA32F; DEM sampler is linear. More importantly, non-Interpolate ramps are sampled at 256 positions over -500..9000 and the fragment shader interpolates colors; nearest spatial sampling alone cannot make this an exact slope/aspect classifier (R6). For few classes compare uniforms or exact portable lookup storage plus class-preserving sampling. Keep continuous elevation/hillshade distinct. Strong portability, hypothetical speed benefit. Medium threshold/precision risk. Verify thresholds ±epsilon, nodata, aspect 0/360 and all devices. Addresses [#4150](https://github.com/maplibre/maplibre-native/issues/4150) and [#4117](https://github.com/maplibre/maplibre-native/issues/4117), not proof of a speedup.

**M13. Cache symbol elevation inputs.** Scenario/stage/backend: contours/peaks/huts during turns; render CPU/symbol upload; A. Per-tile sampler already exists. Cache repeated anchor/line-vertex elevations by DEM/border revision and exaggeration; avoid repeated ancestor searches and projection-independent work. Camera projection/collision still update. Hypothesis, especially contour-heavy. Medium stale-height/cross-tile risk. Verify MICRO-SYMBOL sample counts and ridge/tile-edge/new-DEM placement.

**M14. Incremental placement with stable existing labels.** Scenario/stage/backend: dense motion labels; layout/placement CPU; A. Profile existing throttling first; bound placement slices or reduce admission of new low-priority labels while moving. Continue projection/occlusion updates for existing labels, finish placement after settling under deadline. Hypothesis of smaller spikes. Medium collision/lag/starvation risk. Verify DEVICE-LABELS latency/stability and settled-label equality. Never freeze collision positions through large turns.

**M15. Useful-work priority and cancellation throughout tile processing.** Scenario/stage/backend: fling/zoom/weak network; network/decode/workers/upload; A. Carry importance/cancellation through all queues. Preserve useful ancestors. Inspected thread pool visits tagged queues, not an established screen-importance scheduler. Strong when obsolete work occurs. Medium starvation/restart-churn risk. Verify FLING-NETWORK downloaded/decoded/uploaded-but-never-used counters and eventual completeness.

**M16. Share compatible source/layout work and narrow invalidation.** Scenario/stage/backend: presets/locales/stacked datasets; worker/render/memory; A. One elevation source for terrain/hillshade; share compatible layout buckets; distinguish paint from layout/filter/visibility changes. Audit TilePyramid relayout cache clearing. Strong if duplication/broad invalidation exists. Medium source identity/ordering/paint risk. Verify STYLE-SWITCH bucket/layout counts and equivalent images. Do not merge independently updated datasets just to reduce source count.

**M17. Process-wide decoded/GPU byte budgets.** Scenario/stage/backend: stacked sources/navigation/pressure; heap/GPU memory; A. TilePyramid capacity is viewport/zoom-derived tile count. Account for decoded bytes, bucket expansion, textures and in-flight allocations rather than treating dense and empty tiles equally. Coordinate all caches. Strong memory/process-survival benefit. Medium eviction-churn risk. Verify MEMORY-SOAK, pressure/revisit tests and latency-versus-budget curves.

**M18. PMTiles request sharing and efficient local I/O.** Scenario/stage/backend: offline/direct-online archives; I/O/queue/decode; A where used. Current path reads 127-byte header then metadata/directories, with 100 directory entries cached per archive. Coalesce in-flight header/directory reads, use byte budgets, cache open files, compare bounded pread/mapping. Local requests perform file status/read work each time. Strong removal of duplicates; latency unknown. Medium cancellation/replacement/range risk. Verify PMTILES malformed/gzip fixtures and concurrent/canceled reads. Current CDN z/x/y clients do not pay client-side PMTiles lookup; that is server work.

**M19. Presentation-deadline pacing.** Scenario/stage/backend: 60/120 Hz and energy; scheduling/present queue; Android G/V, corresponding M audit. MapRenderer caps by native render elapsed time then Thread.sleep; FPS is callback intervals. Add deadline-aware pacing/coalescing while retaining WHEN_DIRTY. Hypothetical tail gain; strong reduced excess work if overproducing. Medium latency/wakeup risk. Verify PACING actual presents, input latency, 30/60/120 and pause/resume.

### 3.3 Deep changes

**D1. Separate DEM resolution, mesh LOD and drape resolution.** Scenario/stage/backend: steep z11–16; cover/vertices/drapes/memory; A. Preserve current overzoom, choose mesh by projected geometric error and texture by projected demand. Replace far detail with valid coarser coverage rather than dropping it. Strong opportunity, unmeasured implementation gain. Deep crack/silhouette/elevation/camera-clearance risk. Verify LOD screen-space error, silhouettes and seams. Settled quality cannot be silently lowered.

**D2. Cross-stage work scheduler with cost estimates and deadlines.** Scenario/stage/backend: simultaneous decode/upload/drape/placement; CPU/GPU; A. Extend M1 with separate CPU/upload/GPU estimates, recent-frame feedback and reserves for camera/symbol work. Foreground coverage outranks invisible refinement. Strong rationale, hypothetical percentage. Deep oscillation/starvation risk. Verify deterministic arrival bursts, stage caps, refinement age and completion deadlines.

**D3. Separate frequently changing draped content.** Scenario/stage/backend: route recording/selection/animation; CPU/RTT GPU; A. Puck changes must not rebake the map. Start with only intersecting route drapes dirty; if still expensive add dedicated internal terrain-conforming dynamic pass or overlay drape. Strong when GPS updates invalidate expensive base targets. Deep order/blending/occlusion risk. Verify NAVIGATION rebakes per update and route/hillshade/label order. Not a recommendation for custom layers.

**D4. Explicit drape dependency revisions.** Scenario/stage/backend: moving layer-heavy scenes; render CPU; A. Per-target caching exists, but global drawable walks/signature intermediates remain. Track dirty source tiles/layers and affected targets with conservative full invalidation fallback. Strong reduction of sparse-change O(drawables) work; time unknown. Deep stale style/paint/zoom risk. Verify HEADLESS-COUNTS and randomized mutations versus forced-rebake reference.

**D5. Dense geometry optimization only if vector option B remains required.** Scenario/stage/backend: slope polygons; parse/rings/triangulation/upload/vertices; A. Reduce data first; profile fill generator allocation, classification, triangulation and segmentation. Consider reusable triangulations/preprocessed Terra format only after remaining cost is established. Hypothesis; raster likely has better cost/benefit. Deep topology/query/format risk. Verify MICRO-FILL pathological rings/holes/seams and equivalent masks.

**D6. Shared CPU/GPU DEM representation.** Scenario/stage/backend: DEM-heavy scenes; decode/upload/sampling/memory; A. Compare packed RGB with supported scalar or tile-relative quantized storage; cache gradients only if total storage/preparation wins. Hypothesis; conversion can outweigh bandwidth gains. Deep precision/capability risk. Absolute half-float height is not a safe mountain default; class thresholds must stay exact. Verify DEM-FORMATS error, A10/PowerVR capabilities and energy.

**D7. Broader static geometry/command reuse.** Scenario/stage/backend: warm orbit; submission/driver CPU; A, mainly V/M implementation. After redundant passes are removed, investigate geometry arenas, descriptor reduction, batching and reusable command fragments. Preserve transparent ordering/stencil dependencies. Hypothesis; CPU savings may not help fragment-bound scenes. Deep lifetime/order/transient-memory risk. Verify BACKEND-DRAWS CPU/state counts and order-sensitive suite.

**D8. Predictive low-waste requests.** Scenario/stage/backend: weak links/following/first view; network critical path; A. Conservative initial cover, cached DEM bounds and motion can initiate useful work earlier. Prioritize likely glyph ranges rather than all locales. Hypothetical RTT-scale gains; waste can erase them. Deep prediction/bandwidth/battery risk. Verify NETWORK dependency graph, useful bytes and completion across RTT/loss.

### 3.4 Small gains worth retaining

These often affect whole-frame time by less than 1–3% individually. Small effects require tight measurement, not omission.

| ID | Candidate, stage and backend | Evidence, effort/risk | Verification |
| --- | --- | --- | --- |
| S1 | Avoid copying cached PMTiles directory vector before find_tile; offline lookup CPU/allocation; A | Strong one-copy-per-lookup removal; small lifetime review | MICRO-PMTILES lookup/run-length/missing results and allocations |
| S2 | Replace string directory keys and linear LRU maintenance if hot; A | Hypothesis; small–medium eviction risk | Large-directory benchmark and bounded-cache tests |
| S3 | Reuse drapedTiles, hillshade sets, terrain sets, GL depth-UBO scratch; render CPU; A/G | Strong allocation removal, time unknown; small clear/reentrancy risk | Allocations/frame and mutation renders |
| S4 | Replace TexturePool contains+at with one lookup; A | Strong tiny win; small/low risk | Lookup benchmark/pool tests |
| S5 | Walk DEM parents instead of scanning cached DEMs; placement/binding CPU; A | Strong algorithmic opportunity; small–medium wrap/ancestor risk | Sparse DEM/late-upgrade lookup tests |
| S6 | Fuse DEM copy/range scans or vectorize where worthwhile; worker CPU; A | Hypothesis; small–medium nodata/integer-semantics risk | MICRO-DEM exact ranges/negative values |
| S7 | Add a verified opaque DEM decode path that skips the existing premultiplication loop; decode; A where decoder used | The inspected premultiply utility always loops over RGBA pixels; opportunity confirmed, gain unmeasured. Small alpha/nodata risk; use a validated opaque data path | MICRO-IMAGE byte-exact opaque/alpha fixtures |
| S8 | Rebind improved DEM without rebuilding unchanged grid drawable; CPU/upload; A | Strong avoided copies; medium descriptor/revision risk | Ancestor-upgrade tests and drawable/upload counts |
| S9 | Skip unchanged UBO updates/descriptor dirties beyond current guards; A, especially V | Hypothesis; small–medium; inspect setTexture identity checks | Descriptor/UBO counters and replacement renders |
| S10 | Skip consecutive redundant pipeline/texture binds; G/V/M | Vulkan binds per segment; Metal already caches several encoder bindings/states. Hypothesis only for remaining redundancy; small–medium state-cache risk | State counters and alternating-pipeline images |
| S11 | Cache constant hillshade preparation uniforms/matrices; A | Strong invariant work; small; Q2 removes most repeats first | Preparation benchmark with zoom/maxzoom fixtures |
| S12 | Omit unnecessary slope fill outlines/AA geometry; worker/upload/fragment; A | Hypothesis; medium if bucket specialization needed, edge risk; style toggle may not stop generation | Seam/mask goldens and vertex/pass counts |
| S13 | Tune worker count/priority for modest phones; CPU/heat/contention; A | Hypothesis; small experiments, possible medium API work | 1/2/4 worker sweeps, starvation/cancellation |
| S14 | Reduce shared_ptr/temporary closure churn only in measured hot loops; A | Hypothesis; small lifetime risk | Focused benchmark and thread stress |
| S15 | Batch database writes/avoid redundant cache layers; I/O/wakeups; A | Hypothesis, source paths to verify; medium durability risk | Cache/restart/crash/immutable-data tests |
| S16 | Reduce JNI payload copies/event objects; Android G/V | Hypothesis, ResponseBody.bytes→JNI→native to verify; medium ownership risk | Heap profile and cancellation/error-body tests |
| S17 | Trim shipped/loaded code; size/link/page faults/startup; A | Hypothesis pending linker map; medium build-matrix risk; keep both Android backends | BINARY symbol/API checks and all-backend startup smoke |
| S18 | Remove production diagnostics/hot-frame callback traffic; A | Strong where enabled; small; above-ground diagnostics already gated | Trace-off/on cost and functional error reporting |

S17 includes required-ABI-only delivery, separate release debug symbols, LTO/PGO experiments and Java startup profiles. Measure application-delivered bytes and mapped resident code, not just AAR/xcframework archive size. MultiBackend packaging does not prove both backends are initialized: inspect actual loading. All S items are upstream candidates unless app-only or already guarded, not claimed accepted work.

## 4. Explaining the Mali GLES/Vulkan tail gap

A missed deadline is established, not a cause: 15→18 ms rendering can turn a 16.7 ms present interval into 33.3 ms.

1. Warm fully loaded fixed-cover orbit, no arrivals/compilation, to isolate steady state.
2. Diagnostic depth-off and GL-instancing-off variants to isolate the source-backed depth asymmetry.
3. Remove overlays/symbols one at a time to separate drape/depth/symbol/pass dependencies.
4. Restore streaming zoom with upload/compile/fence markers.

Collect recording CPU, GPU execution if available, acquire/present/frame-fence waits, draws/passes, descriptor writes, pipeline misses and staging allocations, aligned with map-surface presents. Investigate non-instanced submission, pass external-memory traffic, warm compilation, one-time submissions, staging churn, swapchain pacing/buffering and thermal clocks. Do not mistake teardown device->waitIdle for per-frame blocking: the inspected Context waitIdle is in resource destruction; attribute every other wait to its actual call path.

## 5. Style, data and runtime policy

### 5.1 Data/style recommendations

**Slope/aspect:** raster default requires class identity through source sampling, overzoom, drape baking and final compositing. Source-nearest alone may not preserve edges if later drape sampling interpolates. Test the entire chain.

For vector option B, start an **experimental** build budget of 10–20k generated/rendered vertices per near-field z12–13 tile, versus current 100–120k source vertices. Track both counts and scene-wide totals; allow higher zoom budgets only for projected detail. Jointly simplify shared boundaries, remove redundant points, dissolve adjacent same-class areas, preserve topology/holes and measure projected error and class-area differences. Do not delete safety-relevant slivers silently. Compressed bytes are not a substitute for expanded bucket/upload budgets.

**DEM size/encoding:** keep 512 as control. Compare 256/512 at matched geographic sampling resolution and independent mesh/drape policies. Smaller tiles can multiply requests/borders/targets/draws. Coarser DEM must not force larger drapes. Compare pixel-identical lossless PNG/WebP on every CPU class, measuring bytes, CPU, peak decode memory, completion and energy, offline and high RTT. No universal file-size-based winner. Current DEM retains RGBA bytes rather than eagerly expanding all samples to float. A 512 tile with the inspected two-pixel border is **516×516**, not 514×514.

**Zooms/bounds:** publish truthful bounds and data maxzoom. Let valid ancestor DEMs feed existing mesh overzoom; do not invent source maxzoom to request finer meshes. Keep the existing three-level overzoom behavior and test its limit. Use source/layer minzoom to avoid useless decode/layout; verify hidden-source suppression instead of assuming it.

**Contours/labels:** index contours at low zoom; intermediate contours when useful. Limit line-label repetition/font stacks/optional variants; share summit box assets. Reduce low-priority candidates before shaping rather than relying on collision to remove them. Keep peak/hut collision enabled. Blind symbol-layer merging changes ordering/priority.

**Sources/layers:** one elevation source for terrain/hillshade. Merge only when compatible layout sharing/draw reduction actually occurs. Preserve independent dataset releases and attribution. One huge source can increase bytes/parse cost when only one dataset is visible. Judge by useful bytes/buckets/draws, not source count.

**Network/cache:** warm fresh immutable z/x/y revisits should produce zero revalidation requests. Verify Cache-Control/expiry through engine database and shared OkHttp. Include data version/request variation in keys; keep style/TileJSON consistent. Future local archives need overlap precedence, replacement and multi-region tests. [PMTiles concepts](https://docs.protomaps.com/pmtiles/) separate directory and content access; local reads should not repeat unnecessary opens as if each were an independent HTTP request.

### 5.2 Starting runtime defaults

These are experiments, **not validated shipping budgets**. Low includes PowerVR and A10 until measured otherwise. Resting output must equal current full-quality reference.

| Knob | Pixel/capable | Mid-range | Low/A10 | Availability/qualification |
| --- | --- | --- | --- | --- |
| Moving render scale | 0.75× native linear; test logical DPR1 separately | 0.67–0.75× | 0.5–0.67× | Construction-time Android pixelRatio exists; independent runtime scale needs adding |
| Rest resolution | Native | Native | Native | Restore after 300–500 ms, explicit quality-complete event |
| Moving drapes | 512² near | 512² | 512²; test 256² only far | Internal sizing exists; motion policy/resize needed |
| Rest drapes | Existing 1024² near/far policy | Same | Same | Progressive refinement must finish |
| Refinement | Initially 1 target/frame then cost-based | 1/frame | 1/frame with headroom | Full cost budgeting needs adding |
| Drape soft budget | Trial 128 MiB | 96 MiB | 64 MiB | Soft experimental limits only; active full-quality output may exceed them. Use R10 attachment sharing before sacrificing detail |
| Inactive decoded tile cache | Trial 64 MiB | 32 MiB | 16 MiB | Byte API needed; active set separate |
| Total GPU envelope | Measure OS-pressure-safe limit | Same | Strictest measured A10 limit | Not equal to drape budget; no universal invented cap |
| Rest mesh LOD | Existing quality | Existing quality | Existing quality | Current modes couple scheduling and mesh caps |
| Moving mesh LOD | Unchanged initially | Same | Same | Screen-error policy is future work |
| Interaction FPS | 60 default; 120 after energy/headroom tests | 60 if sustained | 30 if 60 cannot be smooth | Existing FPS APIs; pacing validation needed |
| Still map | 0 frames | 0 | 0 | Existing event-driven scheduling |
| Following FPS | 30 initially; raise for gesture | 30 | 30 | Independent from GPS sampling |
| Prefetch zoom delta | 0 versus1 on weak network | 0 versus1 | 0 versus1 | Public; do not remove necessary ancestors |
| Overlay fade | 0 initially | 0 | 0 | Existing style controls |
| Labels | 100–150 ms fade, full placement initially | Same | Test reduced new admission in motion | Fade exists; admission policy new |
| Renderer | GLES for measured G715 | Device/driver A/B | GL affected PowerVR; Metal iOS | Process-start decision |
| Disk network cache | Trial 256 MiB | 256 MiB | 128 MiB | Android OfflineManager and Darwin MLNOfflineStorage expose setMaximumAmbientCacheSize; archives separate |

1024² RGBA8 color is 4 MiB; depth/stencil can bring the target to about 8–9 MiB before alignment/buffering/driver overhead. A 64-layer 516² RGBA8 DEM array is about 65 MiB, additional to separately retained DEM textures. Count caps alone are insufficient.

Current fork public modes on Android/iOS (allowances are keyed by tile ID, shared across sources; they are not a total tile-work budget—see R4):

| Mode | New fill/line tile allowances/frame | Drape rebakes/frame | Base mesh cap |
| --- | ---: | ---: | ---: |
| Quality | Unlimited | Unlimited | 64 |
| Balanced | 32 | 16 | 48 |
| Performance | 8 | 4 | 24 |

Mesh cap scales with viewport area. Balanced/Performance cannot be permanent low-device defaults without settled visual validation: they trade terrain distance as well as scheduling. Split those concerns before using them to satisfy same quality at rest.

## 6. Performance test plan

Fix data release, camera, styles, locale, viewport and final images. Stable test IDs and raw traces/per-frame data accompany summaries. Build on Google Benchmark, benchmark/api/render.benchmark.cpp, tile-cover benchmarks, render tests and existing terrain/PMTiles fixtures.

### 6.1 Tier 1: microbenchmarks, relevant PRs on Linux CI

| Family | Fixtures/measurement | Correctness |
| --- | --- | --- |
| MICRO-DEM | Terrarium construction/range/borders/sampling; 256/512, negative, nodata, cliffs | Exact channels/ranges/corners/encoding |
| MICRO-IMAGE | Pixel-identical PNG/WebP: snow, relief, categories, opaque/alpha | Pixel equality and malformed/truncated |
| MICRO-MESH | Grid generation separately from N drawable constructions/rebinds; 32/64/128 grids | Bounds, skirts, shared lifetime |
| MICRO-MVT | Sparse basemap, contours, real dense slopes; decompress separately | Geometry/properties and malformed |
| MICRO-FILL | 10k/50k/120k vertices, holes, slivers, many polygons | Topology, segments, masks |
| MICRO-LINE | Dense contours, long routes, joins, caps, dashes | Joins/caps/tile edges |
| MICRO-SYMBOL | Peaks/huts/contours/icon-text-fit; flat/terrain | Collision, elevation, occlusion, cross-tile |
| MICRO-STYLE | Locale parse/apply/visibility/paint/layout separately | Equivalent output/relayout counts |
| MICRO-COVER | Pitch0/45/60/85 where supported, negative/positive relief, sparse DEM, overzoom, wrap | Oracle, no holes, conservative bounds |
| MICRO-PMTILES | Header/root/leaf parse, lookup, hit/miss, concurrent | Gzip/corrupt/run-length/cancellation |

For pitch 85 exercise core coverage even if public camera clamps lower; never call a clamped run an 85-degree result. Measure ns/op, throughput, allocations/op, allocated bytes and output size. Separate decompression/decode/layout/upload preparation, plus end-to-end tile processing to detect shifted cost.

Use dedicated pinned optimized CI where possible, fixed toolchain, warmup/repeated batches/randomized baseline-candidate order. Initial reproducible timing gate >5%, tightening stable cases toward 2–3%. Shared CI may have5–15% noise or worse; trigger dedicated reruns while exact allocation/output regressions fail directly. No promised1% detection on noisy CI.

### 6.2 Tier 2: headless rendering/resources, PRs and nightly

Fixed 2D/3D mountain scenes with all overlay combinations and summit variants. Test fully settled forced redraws and scripted camera/tile/style transitions.

Record draws, triangles, passes, pipeline/texture/descriptor changes; full/selected cover; drape allocations, bakes, skips, deferrals; hillshade/depth passes; logical texture/buffer bytes, allocation/upload/in-flight bytes; placement work and revisions; eventual quality completion.

Exact or explicitly budgeted count changes fail. Unchanged forced redraw must not prepare hillshade; puck-only must not redraw depth after Q3; unused drapes must not grow with precap cover. Use images and semantic assertions: exact slope-class interiors, controlled text tolerances. Compare progressive settled output with unlimited full-quality reference.

Software renderer establishes pass/draw/resource/logical-byte regressions, **not mobile FPS, physical residency or energy**. Treat software wall time separately, initially 5–10% sensitivity; count gates are deterministic. Linux covers available GL/Vulkan paths; Metal requires macOS correctness infrastructure and physical iOS performance. Emulators give no phone frame-rate evidence.

### 6.3 Tier 3: devices, nightly and every engine bump

Pixel GL/Vulkan and iPhone 7 Metal first; add actual Samsung SoC/GPU and PowerVR. Matrix: no overlays, raster slope, vector slope, aspect, all; explicit hillshade off/on; text-only/shared-box/generated summit variants. 60 Hz first, separate120 Hz baseline.

| ID | Procedure |
| --- | --- |
| DEVICE-ORBIT | 20 s z13/pitch 60 plus45 control; streaming cold and fully resident warm |
| DEVICE-OVERLAYS | Paired identical paths/configurations for representation and layer deltas |
| DEVICE-PITCH | Tilt to maximum; relief, horizon, bottom corners; precap/selected counts |
| ZOOM/ZOOM-BURST | Cross integer levels/DEM maxzoom, reverse before completion, synchronized arrivals |
| DEVICE-COMBINED | Fixed zoom-turn-pan and fling; weak links |
| DEVICE-2D | Same overlays in flat pan/zoom |
| START | Process→activity frame→map content→data complete→full-quality present→idle |
| TERRAIN-TOGGLE | Repeated2D↔3D during pending requests; orphan/resource/partial-frame checks |
| STYLE-SWITCH | Visibility, incremental insertion, genuine reload separately |
| STYLE-RELOAD | Locale/generated images; generation/registration/atlas/request counts |
| RESOLUTION | Move→still, brief repeat gestures, resize mid-refinement; quality deadline/peak |
| PACING | 30/60/120, display-rate changes, overload; presents/input latency |
| IDLE | Loaded static5–10 min, no location; zero map frames/periodic wakeups |
| IDLE-DYNAMIC | Fixed camera, puck1/5/10 Hz, route append; attribute each redraw |
| NAVIGATION | 20–30 min screen-on with actual separate service, realistic GPS/route growth |
| MEMORY-SOAK | 20–30 min regions/zooms/reversals; memory slope/cache turnover |
| RESTORE | Background, lock, rotation, surface recreation, pressure, process death/camera restore |
| NETWORK/FLING-NETWORK | Controlled RTT/bandwidth/loss, offline, warm cache, errors, missing DEM, cancellation |
| PMTILES | Local cold page cache where controllable/warm, multiple regions/datasets |
| BINARY | Delivered/installed size, mapped pages, load/link time, fresh start |
| DEVICE-LABELS | Density/box variants; layout/placement latency and settled stability |
| LOD/DEM-FORMATS | Error-bounded quality and format variants with identical reference terrain |
| BACKEND-* / DEM-UPLOAD | Instrumented depth/draw/pass/upload attribution experiments |

Startup states must separate fresh app-data, warm tiles/cold app-owned pipelines, warm tiles/pipelines/new process, reused activity/map, and offline warm data. pm clear does not prove OS/driver caches cold; record controlled caches.

Complete means current camera/style revision, visible data, valid terrain bindings, finished drapes, required symbols, then actual presentation. Record full-quality completion separately from moving-resolution coverage. Preserve existing corrected terrain-completion tests; first idle alone is insufficient.

### 6.4 Presentation metrics

Record map-surface actual intervals; p50/p95/p99; mean duration of worst1%/5% with explicit naming; missed-refresh count/fraction; intervals>33/>50 ms plus refresh-normalized thresholds; longest stall/consecutive miss streak; callback CPU separately. Mean fps is presented count/elapsed, not mean reciprocal interval. At 60Hz16 ms is normally rounded16.67 ms.

Use Perfetto/SurfaceFlinger attribution and verify map SurfaceView coverage. Activity/Compose FrameTimingMetric alone does not measure the separate map surface. See [FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) and [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics). Older/API 26 devices use available surface/frame timestamps, validating with high-speed video if needed. iOS uses Instruments/Core Animation/Metal presentation evidence; display-link callbacks do not prove fresh map images.

### 6.5 Energy and thermal

| Workload | Measure | Gate |
| --- | --- | --- |
| Static loaded | Incremental power over matched screen-on baseline, frames, CPU, wakeups | Zero map rendering; reproducible excess power/wakeups fail |
| Fixed pan/orbit | Joules/minute, work, temperature, frequencies | No significant regression at equal output/cadence |
| 20–30 min navigation/soak | Energy, tails over time, memory, throttle onset | No growth/earlier reproducible throttling/worse sustained pacing |

Android: battery/thermal state, dumpsys meminfo, Perfetto scheduling/frequency and rails where available. Pixel power metrics are system-wide; isolate other work. Battery percentage over 20 s is unusable for small energy differences. iOS: Instruments Energy/Metal/system traces; [MetricKit](https://developer.apple.com/documentation/metrickit) for production trends, not deterministic per-run watts.

Fixed brightness, radios, state of charge and charging state; energy tests unplugged or externally measured without changing charging behavior. Include separate outdoor bright-screen profile. Controlled cold-weather testing is separate: room-temperature thermal status 0 does not model a cold battery.

### 6.6 Metadata for every run

- Device, SoC/GPU, RAM, OS/build, driver where available.
- Renderer, engine commit/version, app/toolchain/build flags, data/style/font/image release.
- Physical/logical resolution, density, render scale, refresh, cap, quality knobs.
- FPS, presentation distribution, render/worker/main CPU, GPU time if reliable.
- Decode/layout/upload/drape/placement stage times and queue delays.
- Startup milestones and full-quality deadline.
- Requests, bytes, hits, cancellations, downloaded/decoded/uploaded-never-used tiles.
- RSS/PSS, native/Java heap, logical GPU bytes, physical residency where available, peak/steady values.
- Thermal status/temperatures throughout, frequencies, battery, charging, brightness.
- Trace overhead configuration; unavailable metrics marked unavailable.

Separate map and navigation process cost where possible, retaining whole-product totals.

### 6.7 Noise and release rules

Start cool: Android thermal status 0 plus stable temperature band; iOS nominal thermal state/environment controls. Status alone is coarse. Randomized round-robin baseline/candidate/config order, cooling between short tests. At least3 runs; 5 is a starting minimum for small effects, not proof of1–3% sensitivity.

Establish10–20 A/A paired runs across sessions. Estimate paired differences using robust central measures and bootstrap confidence intervals over **runs**, not correlated frames. Gate when adverse change exceeds A/A noise and predeclared meaningful effect and confirms on paired rerun. Potential material regression stays blocked while unresolved; underpowered nonsignificance is not a pass.

| Tier | Initial detectable effect |
| --- | --- |
| Exact counts/allocations | One deterministic operation/resource |
| Dedicated microbench | 2–3%; 1% with enough stability/repeats |
| Shared CI timing | Often 5–15%; dedicated rerun |
| Controlled headless timing | About5%; counts separate |
| Cool device stage CPU/GPU | About3–5%; more pairs for smaller |
| Presented tails | Often 5–10% or detectable missed-vsync rate |
| Controlled startup | About3–5% or tens of ms |
| Controlled power | Often 5–10%, instrument/device dependent |
| Battery percentage | Too coarse for small-change gates without long runs |

At 60 Hz stable16.67 ms cannot reveal2% headroom: use stage execution and120 Hz, while keeping60 Hz user tests. Initial objectives: no 50+ms stalls in warm resident representative orbit; p95 one interval where accepted device/config sustains it; bounded refinement; zero idle frames; no repeated-cycle memory growth. Final absolute thresholds need distributions, not only 1.5–1.9 s observed startup range.

PRs: micro/count/render correctness. Renderer changes: device smoke. Nightly: rotating device matrix. Weekly and engine bumps: energy/long soak. Before each13.6.1-terra.N promotion: required-device release gate. Pixel first does not establish iOS parity; run A10.

## 7. Open measurements

| Question | Experiment |
| --- | --- |
| Is measured binary missing hillshade reuse/depth guard effectiveness? | Pin commit; count passes in fixed redraw/warm orbit |
| Is Vulkan CPU/GPU/present bound? | Correlated stage/wait/present traces and depth A/B |
| Does polygon cost persist with settled drapes? | No arrivals vs forced invalidation vs zoom |
| Does native dynamic draping match web gains? | 512/1024 movement, identical mesh/data, settled equality |
| Does framebuffer scaling still help after drape changes? | Factorial drape×framebuffer experiment; do not add percentages |
| 256 or512 DEM? | Matched resolution/independent policies, requests/decode/memory/GPU |
| PNG or WebP per device/link? | Pixel-identical offline/shaped-network fixtures |
| How much DEM duplication is resident? | Identity accounting/GPU capture/shared-storage prototype |
| Why repeated-zoom hillshade slowdown after cleanup? | Long loop, active/retained targets, passes, references |
| Worker count? | 1/2/4 with dense tiles and render contention/energy |
| Prefetch? | 0/1/default with fixed link, useful bytes/completion |
| Summit box cost? | Shared box+text vs bitmaps; separately warm generation/atlas |
| Cache limits? | Budget sweep with navigation/restore/revisit |
| 120 Hz value? | Matched path/quality, input latency/misses/joules |
| Local archive strategy? | Open/read vs cached pread vs bounded mapping, cold/warm/concurrent |
| PowerVR render or presentation corruption? | Physical video plus screenshot/GPU capture, surface/backend variants |

## 8. Upstream context and first tranche

Preserve correctness from [#4701](https://github.com/maplibre/maplibre-native/pull/4701) (ancestor upgrades/drape comparisons), [#4558](https://github.com/maplibre/maplibre-native/pull/4558) (terrain labels/collision/picking), and [#4711](https://github.com/maplibre/maplibre-native/pull/4711)/[#4712](https://github.com/maplibre/maplibre-native/pull/4712) (gesture anchoring). These were checked as upstream context during the initial review; the checkout is the source authority for this report. [#4684](https://github.com/maplibre/maplibre-native/issues/4684) warrants repeated-zoom soak despite the existing cleanup. [#4414](https://github.com/maplibre/maplibre-native/issues/4414) means fog is not a presumed solution to distant coverage cost.

Revised first tranche: build R1/R5/R6 correctness-qualified references and pass/resource counters; then Q2/Q3/Q4/Q11 and R2 on iOS. Trace R3/R4 during the first streaming zoom before choosing mode defaults. Adopt raster slope after exact categorical output is demonstrated. Next address M1/M2, followed by M3 and persistent compilation caching if traces justify it. Keep GL as the provisional G715 shipping choice, but only compare performance at equal depth coverage and visual output.

## 9. Second-pass source review: additions, corrections and revised changes

This review deliberately distinguishes source-backed mechanisms from hypotheses and from existing optimizations. It does not establish phone speedups. All R proposals below are fork work/upstream candidates unless described as existing behavior; no upstream acceptance is implied. They supplement the stable Q/M/D/S IDs rather than silently replacing the initial record in commit `897ee57363`.

### 9.1 Changes required before trusting a performance win

**R1. Make cache validity reflect rendered content, and commit validity only after successful production.**

- **Where/evidence:** [renderer_impl.cpp](src/mln/renderer/renderer_impl.cpp) adds bucket identity to the fast drape signature except for hillshade. [render_target.cpp](src/mln/renderer/render_target.cpp) then has a second `coverage.sameContentAs(bakedCoverage)` fast exit whose coverage calculation includes tile IDs and layer style revisions, not the same bucket/producer revisions. Thus a changed fast signature does not necessarily imply a rebake. Both paths use integer zoom; continuous evaluated paint and in-place feature-state changes need explicit coverage. Hillshade producer identity is deliberately omitted by a current workaround. Background clear-color changes and same-ID/order style mutations also require tests.
- **Failure/recovery evidence:** bakedSignature/bakedCoverage are assigned before createRenderPass and drawing. A later allocation/encoding failure can leave metadata claiming content was baked. The renderer catches bad_alloc and asks for another frame, but that is not proof the next frame will retry rather than take a cache fast exit. Exceptions can also occur after temporary enabled flags, current-drape state or global UBOs are changed. This is a source-backed failure-path concern; use fault injection to establish each observable failure.
- **Change:** define explicit producer/content generations shared by both cache levels, including prepare-output revisions and relevant evaluated paint. Distinguish intentional tile-local zoom evaluation from accidentally freezing fractional-zoom styling. Commit successful bake metadata only when production succeeds; use scoped restoration for temporary rendering state. Retry failed production without infinite wakeups, and keep valid previous output only when semantically allowed.
- **Scenario/stage/backend:** all draped scenes, style/route/DEM updates, low-memory recovery; render CPU/GPU and scheduling; A.
- **Expected gain/confidence:** strong correctness prerequisite and elimination of redundant retries; may initially cost more because previously stale content gets rendered. That is a corrected baseline, not a performance regression to hide. Small-to-medium effort, high stale-output risk if incomplete.
- **Verification:** new `CACHE-VALIDITY` suite: same tile/new bucket, same DEM ID/new border, producer target replacement, same IDs/different source data, background change, reordered layers, feature-state, paint transitions, fractional-zoom line widths/dashes and final transition values. Compare cached rendering against a forced-rebake reference with the same documented zoom semantics. Inject failures before/after pass creation and midway through rendering; require restored state, successful retry and truthful quality completion. Track invalidation reason, producer generation and retry count.

**R5. Require equal GL depth coverage before interpreting its speed advantage.**

- **Where/evidence:** [render_terrain.hpp](src/mln/renderer/render_terrain.hpp) caps GL DEM-array layers and depth instances at 64. `packDEMArrayLayer` returns when full. The instanced depth list skips tiles with no packed layer and stops at maxDepthInstances. Mesh caps can scale above 64 with viewport area. Packing is called when a new per-tile DEM texture is created; retaining an existing texture does not itself retry a previously failed array allocation. Retained ancestors compete for slots with actually drawn DEMs.
- **Change:** instrument selected terrain tiles, depth-covered tiles and unique bound DEMs separately. Allocate array slots to current consumers first; retry missing residency; use multiple instanced batches and/or a correct per-tile fallback. Make capacity/resource failure visible in quality status. Keep this distinct from the existing missing-image crash fix.
- **Scenario/stage/backend:** high pitch, many ancestors, large viewport and long pan; GPU memory, upload, depth submission; G, with V/M comparison.
- **Expected gain/confidence:** strong correctness and better use of bounded slots; restored depth can increase GL time. Do not count omitted occlusion as an optimization. Medium effort, ridge-label visibility risk.
- **Verification:** `DEPTH-COVERAGE`: 63/64/65 unique DEMs, >64 mesh tiles, slot exhaustion then release, shared ancestors, repeat pan and viewport rotation. Require every selected surface region with an occlusion consumer to be represented correctly; compare per-tile and instanced depth results and visible labels. `getDepthMesh` currently returns the full shared mesh, so a coarser GL depth mesh is **not** an explanation for the baseline gap.

**R6. Treat exact categorical color-relief as a prerequisite, not merely a sampler switch.**

- **Where/evidence:** [render_color_relief_layer.cpp](src/mln/renderer/layers/render_color_relief_layer.cpp), `updateColorRamp`, extracts explicit Interpolate stops, but other expressions fall back to 256 samples spanning -500 to 9000. That is about 37.25 input units per sample. [color_relief.fragment.glsl](shaders/color_relief.fragment.glsl) binary-searches stops then mixes colors. For step-style slopes in degrees or aspect classes, this can be coarse and semantically wrong independently of the spatial filter. Terra's exact expressions/units are not in the reviewed checkout; reproduce with those before claiming a product defect. Check generated GL/Vulkan/Metal shader counterparts too.
- **Change:** support exact step thresholds/class lookup and nearest spatial category sampling through the complete rendering chain. Compare (a) exact scalar-plus-class shader, (b) class-ID raster plus palette, and (c) precolored lossless raster if fixed palettes are acceptable. Precolored raster can avoid the color-relief search/format path but needs style-switch and drape-filter validation; class-ID values must never be linearly interpolated as numeric measurements.
- **Scenario/stage/backend:** slope/aspect on every view; worker/ramp generation and GPU fragment/sampling; A. Strong semantic evidence, unmeasured timing; small-to-medium for a fixed Terra classifier, medium for general style support. Boundary, alpha/nodata, wrap and palette risks.
- **Verification:** `CLASS-EXACT`: every actual class threshold on both sides and exactly at equality; steep/narrow class islands; nodata/transparent pixels; aspect wrap; z11–16 and subpixel boundaries. Compare source data classification, direct 2D output and 3D draped output. Include asynchronous palette changes and reuse after settling.
- **Precision clarification:** do not justify nearest sampling by saying linear interpolation of packed Terrarium RGB can never represent linear elevation. The decode is affine, so ideal linear channel interpolation commutes with decoding in exact arithmetic. Real filtering precision, nodata, premultiplication and class semantics are separate reasons to control sampling. Continuous elevation and categorical data require different tests.

### 9.2 Newly prioritized performance changes

**R2. Remove CPU waits between Metal offscreen producers and consumers.**

- **Where/evidence:** [mtl/offscreen_texture.cpp](src/mln/mtl/offscreen_texture.cpp), `OffscreenTextureResource::bind`, creates a command buffer; `swap` commits and calls `waitUntilCompleted`. [mtl/command_encoder.cpp](src/mln/mtl/command_encoder.cpp), `present`, invokes that swap, and `RenderTarget::render` presents each offscreen target. This is a concrete path from every rendered prepare/drape/depth target to a synchronous CPU wait, not merely a still-image readback concern.
- **Change:** encode offscreen producers and dependent draws in one frame command buffer where possible, or correctly ordered submissions with retained resources and asynchronous completion. Preserve texture visibility, render-pass ordering, resource lifetimes and frame-in-flight limits. Keep explicit completion waits for CPU readback, teardown and tests that actually require them; simply deleting a wait is not a safe implementation.
- **Scenario/stage/backend:** iOS cold start, drape bursts, hillshade, zoom and pitch; CPU/GPU overlap and submission; M.
- **Expected gain/confidence:** strong elimination of per-target CPU serialization and many submission costs; no defensible percentage without A10 traces. Medium-to-deep backend effort, high synchronization/lifetime risk. Q2 reduces the number of waits immediately; R2 addresses those that must remain as GPU work.
- **Verification:** `METAL-SUBMISSION` records command-buffer commits, CPU wait time and GPU producer-consumer order in Instruments. Count submissions versus rendered targets before/after; exercise first frame, full drape rebuild, background/surface restore, low memory and screenshot readback. Require identical images and zero validation hazards. Add this experiment before broader Metal instancing work if waits dominate.

**R3. Request and upload DEM ancestors according to actual consumers.**

- **Where/evidence:** `demAncestorLevels` is 8. [tile_pyramid.cpp](src/mln/renderer/tile_pyramid.cpp) explicitly creates those ancestors and marks them Required. `TilePyramid::isLoaded` checks active tiles for completeness, so speculative retained ancestors can contribute to the loaded critical path; trace actual blocking IDs. `RenderTerrain::update` enumerates all loaded DEM tiles, creates their GPU textures and, on GL, packs their arrays before mesh selection. Every enumerated existing texture receives this frame's lastUsed value, while eviction excludes those marked used this frame. The 96-texture setting therefore is not a hard bound on this active set.
- **Change:** distinguish (1) required visible DEM consumers, (2) CPU-only cover/query metadata, (3) valid fallback ancestors and (4) speculative reuse. Build/retain the minimum union needed by terrain, symbols and low-maxzoom layers; preserve all necessary ancestor behavior from #4701. Upload only GPU-consumed DEMs and justified near-future fallbacks. Mark actual use rather than mere enumeration for eviction; explicitly report active-set excess instead of hiding it behind a nominal cap. Do not simply set ancestor retention to zero or declare incomplete essential terrain loaded.
- **Scenario/stage/backend:** cold complete view, wide tilt, stacked datasets and memory; network/decode/upload/GPU storage; A. Strong reduction opportunity in unnecessary work, actual count unknown. Medium effort with fallback/coverage/idle risks.
- **Verification:** `DEM-RESIDENCY`: request/decode/upload/live-texture counts by reason and zoom; which ancestors block complete; actual bound texture IDs. Sweep 0/2/4/8 only diagnostically, then compare consumer-driven policy. Sparse/maxzoom DEM, shallow vector layers and late upgrades must remain visually identical. Force >96 loaded related tiles to test whether budgets really hold, with an explicit soft-limit exception for indispensable active content.

**R4. Correct the progressive-load budget model and its 2D scope.**

- **Where/evidence:** the only found `allowNewTileBuild` callers are render_fill_layer.cpp and render_line_layer.cpp. The context key is hash(tile ID) without source identity, so multiple datasets at identical z/x/y share one allowance. Reset in RenderOrchestrator is not guarded by terrainEnabled, and these callers are not terrain-gated; a non-Quality mode can therefore influence 2D fill/line loading too. These are source findings, not claims that the behavior is inherently wrong.
- **Change:** describe and test the current API honestly. Choose whether the revised mode is terrain-only or a general progressive policy, then expose that contract consistently. Charge measured work by source/bucket/bytes rather than assuming a tile ID has constant cost. Coordinate fills, lines, symbols, raster/DEM upload, hillshade preparation and drapes, with coverage-aware fallback. Partial budgeted layers must not cause a final drape to be marked complete prematurely.
- **Scenario/stage/backend:** multi-source z13 slopes, zoom bursts, terrain toggle to 2D; render CPU/upload/GPU; A. Strong evidence of coverage gaps in current accounting; gain needs burst traces. Medium effort, starvation/partial-composition risk.
- **Verification:** `BUDGET-SCOPE`: same z/x/y across several sources, mixed heavy/light tiles, 2D with each mode, toggle during deferral, symbol-only/raster-only arrivals. Report source/bucket work consumed, queue age and visible/full-quality deadlines, not just admitted tile count.

**R7. Share a DEM lookup snapshot/index across cover, queries and placement.**

- **Where/evidence:** `loadedDEMTiles` allocates a vector and deduplicates by linear scan; repeated callers rebuild it. `DEMElevationProvider` scans loaded render tiles for each requested elevation range. `getElevationAtLatLng` finds a tile, then delegates to a path that resolves it again. `pickLatLng` performs up to 64 ray samples plus eight bisections, each requesting elevations. These amplify both allocations and searches on gesture/query paths, beyond S5's single ancestor lookup.
- **Change:** build a revision-scoped DEM snapshot and tile index once per update, reuse aggregate ranges and per-ID ancestor results, and pass the resolved sampler through the entire ray query. Cache projection-independent data while preserving current camera transforms; consider a min/max hierarchy or tile traversal for picking only after the simpler reuse is measured. Do not reuse a hit across a changed camera/DEM merely to lower cost.
- **Scenario/stage/backend:** turn/tilt anchored on ground, taps, cover at steep pitch, line labels; render/UI-facing query CPU and allocation; A. Strong repeated-work opportunity; actual gesture impact depends on call frequency. Small-to-medium for snapshot reuse, deep for a new ray accelerator; lifetime and nearest-hit risks.
- **Verification:** `MICRO-TERRAIN-QUERY` and `GESTURE-LATENCY`: elevation samples/allocations/resolutions per query, time on the thread blocking input, and event-to-present delay. Ridge in front of valley, tile boundaries, poles/wrap, missing DEM, negative elevation and camera-inside-terrain fixtures must preserve nearest-surface semantics. This optimizes existing public queries/gesture fixes rather than proposing them again.

**R8. Define motion quality as a state machine with deadlines and hysteresis.**

- **Change:** separate gesture-active, camera-animation/following, settling, refinement and idle. A continuous following camera may never be still for 500 ms: it must not remain indefinitely coarse. Refine important retained tiles opportunistically under a maximum age/deadline during navigation; prioritize new coverage while still improving legibility. After explicit gesture stop, require current full-quality output by a measured deadline. A new gesture may pause refinement without thrashing target sizes.
- **Scenario/stage/backend:** repeated short gestures, route following, pixel/drape scaling; scheduling/allocation/GPU; A. Strong avoidance of pathological resize/refinement loops; FPS/energy gain hypothetical. Medium policy/API effort, quality starvation and sharpness-pop risk. App-facing policy belongs in Terra with reusable engine primitives.
- **Verification:** `QUALITY-CONVERGENCE`: 300 ms gestures separated by 200 ms pauses, endless slow follow, toggles during refinement, background/foreground, and thermal transitions. Assert maximum low-quality age, useful refinement count, no unbounded simultaneous old/new textures, and no false idle while quality work remains. Render scale, drape scale and mesh detail are independent knobs; test interactions factorially.

**R9. Make terrain depth consumer-driven and separately costed.**

- **Change:** when no visible layer consumes terrain occlusion, skip generating its depth texture while retaining surface depth testing. Count consumers rather than disabling depth just because symbols are absent: inspect all non-draped consumers. After Q3/R2/M6, experiment with lower-resolution packed depth or a cheaper depth mesh only if conservative occlusion can preserve label visibility/terrain silhouettes. `getDepthMesh` currently reuses the full 128-grid mesh; there is no separately optimized baseline depth geometry.
- **Scenario/stage/backend:** overlay-only/no-label views and high-pitch symbol scenes; GPU vertices/fragments/bandwidth; A. Strong whole-pass removal when unused; reduced-resolution gain hypothetical. Small-to-medium for consumer gating, deep/high visual risk for geometric simplification. 128² cells are 32,768 surface triangles per tile, roughly 2.1 million for 64 tiles per pass, excluding skirts; instancing alone does not reduce that vertex work.
- **Verification:** `DEPTH-CONSUMERS` zero pass with no consumers, enable/disable one consumer, first frame after toggle, front/back ridge labels, subpixel peaks and thin terrain. Any optional coarse-depth mode must restore reference at rest and must not systematically reveal hidden labels during motion.

**R10. Pool transient attachments separately from persistent drape color.**

- **Where/evidence:** drapes retain color plus depth/stencil even when the baked color is reused. Vulkan already discards offscreen depth/stencil stores, and Metal already discards depth stores; discarding contents does not remove attachment allocation. Offscreen textures on Metal advertise ShaderRead/ShaderWrite/RenderTarget usage even for resources whose actual usage may be narrower.
- **Change:** retain color per target, but investigate per-size transient depth/stencil scratch storage for sequential bakes, with frame-in-flight-safe ownership. On Metal, test appropriate memoryless storage/usage flags; on Vulkan, transient allocation where supported; on GL, reuse attachment storage without invalidating sampled color. Examine whether hillshade preparation needs any depth allocation at all (its flat prepare draw should be checked against actual depth state before removal). Keep required drape stencil clipping.
- **Scenario/stage/backend:** resting memory and zoom allocation peaks; GPU storage/bandwidth/driver allocation; A. Strong potential to remove per-retained-target transient storage, approximately one attachment's allocation times the cached target count; actual backend format/residency must be measured. Medium effort, concurrency/aliasing/stencil risks. Upstream candidate.
- **Verification:** `ATTACHMENT-LIFETIME`: color contents survive scratch reuse, multiple sizes/in-flight frames, overlap clipping, restore, and byte accounting. Compare resting physical/logical memory and peak during atomic resolution replacement. This is a more promising response to the trial drape budgets than dropping far terrain.

**R11. Index drape draw candidates instead of scanning/toggling all drawables for each target.**

- **Where/evidence:** `renderDrapedLayerGroups` allocates savedEnabled, walks all draped drawables to change enable flags for one target, draws opaque/translucent groups, then restores flags. Per-target invalidation caching avoids many bakes, but each remaining bake can still cost O(all draped drawables).
- **Change:** maintain tile/source overlap lists or pass a target filter into group traversal without mutating global drawable state. Preserve layer ordering, ancestor/child masks and other enable conditions. As a small first step reserve/reuse scratch and use scoped restoration, coordinated with R1.
- **Scenario/stage/backend:** integer-zoom rebuilds and dense multi-layer fills; render CPU/allocation; A. Strong reduction in unnecessary visits; end-to-end gain unmeasured. Medium indexing effort, order/stencil/lifetime risks. Upstream candidate.
- **Verification:** `DRAPE-TRAVERSAL` count visited versus drawn drawables, dense scene with few overlaps, mixed ancestor/child tiles, exceptions and successive targets. Images must equal the old correct ordering; do not turn this into a transparent-layer reordering optimization.

### 9.3 Additional smaller and workload-specific candidates

| ID | Change and where to inspect | Scenario/stage/backend; gain/confidence | Effort/risk/status and verification |
|---|---|---|---|
| R12 | Preserve MapView/surface identity through Compose/SwiftUI updates; compare opaque SurfaceView and required TextureView paths, avoid needless translucency or surface resizing | Start/rotation/restore and composition; platform CPU/GPU; Android primarily, iOS equivalent lifecycle audit. Hypothesis: fewer recreations/compositor copies | Small app-side; UI clipping/z-order risk. SURFACE-LIFETIME traces native context/surface counts and first-map-present latency with UI overlays. Existing imperative usage is already correct; optimize only observed remaining lifecycle churn |
| R13 | Keep growing route history immutable in chunks, update only the live tail; inspect GeoJSON source reparse, line bucket rebuilding and incremental APIs actually supported | Navigation; serialization/worker/tessellation/upload; A. Strong if every GPS update reparses full history; app implementation to verify | Medium app/engine work; chunk seams, dash/gradient continuity and query identity risks. NAVIGATION route-length sweep (minutes to hours), update bytes and per-tick CPU; final route must be identical |
| R14 | Test raw/class-ID raster and existing MLT capability as data alternatives without assuming they beat current formats | Dense slope I/O/parse/class lookup; A where supported. Hypothesis; MLT cannot remove polygon tessellation or vertex cost by itself | Medium experimental data pipeline; decoder parity/query/tooling risks. FORMAT-AB with identical content and both backends; retain current format unless measured full-path win. PMTiles code recognizes MLT, but complete platform/style support remains to verify |
| R15 | Avoid repeated gzip/compress/decompress across CDN, HTTP, database and PMTiles layers; audit the actual bytes at each boundary | Cold/offline arrival; CPU/I/O; A. Hypothesis; PMTiles gzip decode is visible in its callback path and may serialize unrelated lookups | Small instrumentation, medium bounded worker offload/cache change; content-encoding/cancel/durability risks. PIPELINE-BYTES traces byte counts and decode jobs; compressed/uncompressed/corrupt/304 fixtures and no double-decompression |
| R16 | Replace per-tile neighbor `std::map` construction and powers of two with a fixed table/bit operations after correctness checks; batch border updates before creating replacement prepare targets | DEM arrivals; render/source CPU/allocation/upload; A. Source-backed small repeated work in RenderRasterDEMSource::onTileChanged | Small upstream candidate; pole/wrap/corner risks. MICRO-DEM-NEIGHBORS and adversarial eight-neighbor order, allocations and target replacements |
| R17 | On new targets, refresh per-target signature entries even when global draped content is unchanged; use cover revision separately from content revision | Slow pan/LOD with same source tiles; render CPU/invalidation; A. Strong missing-entry fallback path; performance size unknown | Small-to-medium; stale signature risks. COVER-CONTENT-ORTHOGONAL: change mesh cover without changing drawable set; assert fresh entries and no unnecessary global rescans. Do not assume global content identity implies identical target IDs |
| R18 | Prewarm glyphs/fonts and static sprites for the actual locale and initial area, with bounded app lifetime caches where supported | First complete labeled view/reload; network/shaping/atlas; A. Hypothesis of eliminated critical RTTs; do not fetch whole fonts | Small-to-medium app/data work; locale/fallback/memory risk. START-GLYPHS separate bytes, ranges, shaping and first required label; missing glyph and mixed-script fixtures |
| R19 | Cache cover results only against all relevant inputs and share a DEM-range index across source covers; exploit camera-unchanged dynamic redraws | Puck/route and multi-source pitched frames; cover CPU; A. Hypothesis beyond R7; existing mesh cover already reused within a frame | Medium upstream candidate; source tileSize/zoom/bounds/LOD/DEM revision differences prohibit blind sharing. MICRO-COVER and IDLE-DYNAMIC with one input changed at a time |
| R20 | Bound tile-cache destruction and GPU resource retirement per frame; distinguish logical eviction from actual release after fences | Zoom/style switch/pressure; render CPU/allocator and memory peaks; A. Hypothesis beyond existing deferred tile release | Medium upstream candidate; delayed release can exceed memory budget. EVICTION-BURST traces destructors/deletion queues and retired bytes, OOM pressure and restore; never report freed bytes before resources retire |
| R21 | Benchmark stable summit/contour IDs and reduced cross-tile index churn; inspect existing placement throttle before adding another | Pitched labels; placement/index CPU; A. Existing code already coalesces placement and uses a 30 ms override for new symbols in non-tilted views; gain hypothetical | Small data tuning/medium engine changes; identity/locale/collision risk. DEVICE-LABELS tracks new IDs, index additions and placement age; preserve important near-camera labels rather than assuming new tilted-view labels are all distant |

For every hypothetical row, keep a rejection criterion: if the targeted stage accounts for less than the detectable total gain and the change adds complexity, retain the benchmark and defer the implementation. Do not remove small demonstrated wins simply because they do not change capped FPS.

### 9.4 Revised test rigor and missing adversarial cases

Add the named R suites to the appropriate tier: deterministic correctness/count tests on PRs, microbench timings on controlled Linux, actual Metal submission/depth/surface tests on phones, and memory/navigation tests in the soak matrix. Extra microbenchmarks: DEM neighbor handling, terrain pick/elevation query, drape overlap traversal, cache-hit signature evaluation, and cache eviction/retirement. Benchmark shared-grid creation separately from per-tile copies so M5 savings are not misattributed to meshing arithmetic.

Add fixtures for a high peak outside currently loaded DEM bounds, delayed neighbors and deeper ancestors, a valid empty tile, a permanent 404, transient failures, out-of-order/canceled completions, atlas resizing, same-size image replacement, and malformed local archive ranges. Distinguish a legitimate empty source contribution from a failed missing layer. Strict completeness must not hang on optional speculative resources, and must not ignore required missing content. Test the existing static-render partial-frame cap unchanged: a bounded partial image is not automatically a fully complete image.

Use two correctness references:

1. An accepted visual/semantic reference for Terra, including current terrain alignment and exact slope classes.
2. A diagnostic forced-recompute renderer using equivalent quality semantics, to detect cache/invalidation differences.

A faster candidate with missing depth, stale drapes, suppressed labels, extra culling, smaller full-quality buffers or a shorter camera path is a correctness failure, not a valid benchmark result. Also compare camera ground tracks and screen coverage: changed terrain-anchored gesture behavior can otherwise make two ostensibly identical animations draw different workloads.

#### Statistical and measurement corrections

- Present metrics should say whether they measure unique newly presented map buffers, display refreshes reusing old buffers, or map callback completions. For active animation count missed presentation opportunities; for static maps report zero new frames rather than calling compositor refreshes map FPS. Tag surface replacement and discard/label incomplete trace segments, not arbitrary slow frames.
- FrameTimeline and available SurfaceView instrumentation vary by platform/tool version. Validate observability on each device against surface identifiers and sampled video. A map layer absent from a trace is **missing data**, not zero jank. Do not use `dumpsys gfxinfo` or a Compose frame report as the sole map measure.
- Define worst-1%/5% mean durations separately from p99/p95. Compare quantiles in milliseconds; report FPS equivalents only as an aid. Record 33.33/50.00 ms bins with a small timestamp tolerance as well as the user's literal >33/>50 ms counts, so rounding does not create false changes.
- Pooling 20 s orbit frames gives many correlated observations, not thousands of independent experiments. One run at 60 Hz has only about 12 observations in its worst 1% even before dependence. Use multiple independent runs and sufficient duration to stabilize tails; report per-run distributions and confidence intervals.
- Choose one-sided regression tests, a small set of primary gates, and an explicit multiple-comparison policy across the large matrix. Exact correctness/resource invariants can all gate; exploratory timing metrics trigger confirmation, not hundreds of uncorrected statistical release failures. Use paired-bootstrap/blocked analysis and a predeclared confirmatory protocol.
- To plan sample count, estimate the standard deviation `s_d` of paired log-time differences from A/A runs. A rough normal-approximation starting point for 80% power at one-sided 5% significance is `n ≈ ((1.645 + 0.842) * s_d / log(1 + effect))²` paired runs. This is not a reliable tail-quantile sample-size formula; pilot/bootstrap tail gates separately, and account for multiplicity. Five pairs alone cannot guarantee 1% detection.
- Never add individual optimization percentages. Use factorial tests for drape scale × framebuffer scale × labels and cumulative-build tests for the final combination. Gains can overlap or change the bottleneck.
- Thermal status 0 is necessary for the requested cold-run protocol but not sufficient for equal clocks, skin temperature or battery conditions. Keep the entire thermal history, not just start/end. Android thermal APIs are not uniformly available at API 26; record unavailable fields and use supported device diagnostics/environment controls rather than fabricating zero. Long soaks deliberately leave the cool state; compare time-to-throttle and integrated performance instead of discarding hot samples.
- Keep compilation mode, release optimization, validation layers, logging and profiler overhead fixed. Use low-overhead timing runs and separate high-detail diagnostic captures, then quantify instrumentation overhead with A/A controls. Disable benchmark HUD/polling in energy runs.
- Add task-completion energy, not just joules/minute: energy to a complete view and energy for an identical camera path at equal quality. A cap that reduces frames by leaving the task unfinished is not an energy improvement. Display/radio/GPS dominate some whole-product totals, so preserve both map-specific attribution and total product energy.
- Use fixed-trace/replayed local HTTP fixtures for blocking network regression gates, a controlled shaped-link test for latency behavior, and a live-CDN monitoring suite for operational conditions. Live internet variation should not be mistaken for an engine change. Report blocked/rerun results when external variance prevents attribution.
- Maintain both immediate-parent and fixed-release baselines, cumulative resource budgets and trend charts. A sequence of individually subthreshold 1% regressions must not escape indefinitely by resetting the baseline each commit.
- Binary-size checks should report compressed delivery, installed per-ABI size and loaded/resident pages; debug symbol archives are separate. Build minimum-platform smoke checks (API 26 and iOS 15) alongside reference-phone tests so a performance feature does not silently require a newer API/GPU feature.

### 9.5 Rejected assumptions and decision log

- **Not new work:** Vulkan offscreen depth/stencil DontCare stores, Metal offscreen depth DontCare stores, Metal encoder state-binding caches, deferred tile release, shared within-frame mesh cover and current placement throttling already exist. Measure remaining gaps, not generic textbook savings.
- **Not a guaranteed bound:** 96 DEM textures and mode tile counts do not currently mean all active GPU work/memory is capped. R3/R4 must establish actual behavior.
- **Not an independent measured effect:** subtracting FPS-range endpoints or porting web percentages to native. Use paired native frame-time/energy experiments.
- **Not a safe quality trade:** silently omitting far mesh tiles, depth instances or final labels; permanently coarse following maps; globally nearest-filtering the entire basemap drape solely to fix slope edges.
- **Not mandatory new API:** ambient network cache size already has Android `OfflineManager.setMaximumAmbientCacheSize` and Darwin `MLNOfflineStorage` APIs despite their offline naming. Using that ambient-cache control does not require tile-by-tile offline packs.
- **Not a trivial asynchronous conversion:** Metal offscreen waits protect resource/command completion today. R2 requires correct ownership and producer-consumer ordering, while CPU readbacks still wait.
- **Not all CPU-side elevation is full Terrarium precision:** `DEMData::get` returns int32 and truncates decoded fractional height. Preserve or deliberately improve defined CPU/GPU/query consistency with tests; do not attribute precision-changing speedups to an equivalent decode path.

The revised implementation sequence is intentionally evidence-driven: first ensure the measured images are complete and equivalent; eliminate repeated prepare/depth work and Metal waits; align selected coverage, DEM residency and actual work budgets; then tune motion resolution, compilation and other measured stages. All engine modifications remain future work—the two commits associated with this document only preserve and refine the analysis.
