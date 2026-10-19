# Terra performance assessment and regression plan

**Scope:** Terra's Android-first and iOS outdoor-map workload, not general-purpose MapLibre tuning. This is analysis and planning, not an implementation specification or a claim of measured improvements.

**Source baseline:** `6e22120883` (reviewed 2026-10-19). Record the exact commit of the measured application binary before comparing source findings with those measurements. No phone benchmarks were run for this assessment.

## 1. Workload, evidence and priorities

Terra is a mountaineering, ski-touring and avalanche-safety map platform. The map must remain responsive during hours outdoors, on battery, in cold conditions, with weak data or offline. Memory, heat, energy and process survival matter alongside frame rate. Native performance is a product advantage over a competing GL JS WebView application.

The target is the terrain-3d fork, versioned `13.6.1-terra.N`, with the specified upstream terrain PRs, mapriot's terrain changes and Terra fixes. Android uses the multiBackend AAR, choosing OpenGL ES or Vulkan at process start, with one imperatively driven full-screen MapView and a shared OkHttp client. Compose does not rebuild the view during camera movement. iOS uses SwiftUI and Metal and must remain at parity. Navigation recording lives in a separate Android foreground-service process; map following remains in scope.

The characteristic view is steep high-mountain terrain at z11–16, pitch 45–60 and up to the engine limit, with turning, pinching and panning. 2D planning remains important. Datasets are immutable, versioned PMTiles archives served as CDN z/x/y endpoints with one TileJSON per dataset. Future offline data uses local `pmtiles://file://` archives, one per dataset and region.

Layers include 512 px Terrarium raster DEMs (lossless WebP or PNG, LiDAR-derived), terrain exaggeration 1.0–1.5, almost-always-on hillshade, categorical slope/aspect color-relief, dense alternative vector slope fills, contours and contour labels, an OSM mountain basemap, avalanche-zone fills and routes, and terrain-aware peak/hut symbols with collision. Summit variants add icon-text-fit boxes or generated images. Presets use visibility without reloading; derived benchmark styles and genuine reloads still happen. One reload regenerated 220 summit images.

Reference devices: Pixel 8a/Mali-G715, Android 17, 60 Hz with 120 Hz planned; a mid-range Samsung and low-end PowerVR handset are planned. Android minimum API is 26. iOS minimum is 15.0, with iPhone 7/A10 as the test phone.

### Supplied measurements

- A 20 s z13/pitch60 orbit: GLES 55–57 mean fps, slowest 5% around 16 ms on screen; Vulkan similar mean, slowest 5% around 33 ms. At pitch45 both hold the 60 Hz display. The cap hides rendering headroom.
- Slope polygons cost 11–25 fps, reach about 30 fps mean in the worst view, and produce 50–70 ms slow frames. They trigger thermal throttling within roughly 40 s and delay map-at-rest by 1.1–1.9 s. Raster slope/aspect cost 0.5–3 fps; hillshade about 1 fps.
- Slope z13 tiles are 170–194 KB gzipped, up to 715 KB decoded, with 100–120k vertices. Pitched views contain dozens of z12–13 tiles.
- Process-start to first complete idle view is 1.5–1.9 s cold/empty-cache; activity first frame is about 0.6 s. A cleared application pays network, style parsing and compilation work.
- Thermal state splits identical runs around 50 versus 30 fps.
- GL JS evidence, **not native measurements**: 2× drapes dominate zooming; integer zoom crossings can create roughly 90 ms GPU bursts. Moving at 1× and restoring 2× after 500 ms, one central tile per frame, improved a pitch45 zoom from 33 to 85 fps. Moving pixel ratio 1 gave +21–38%. Desktop drapes retained 0.5–0.7 GB. Coarser DEMs increased drape size and cost. Initial compilation cost roughly 1 s. A metadata/DEM/base/glyph request chain delayed completeness. Label zoom changes in the drape layer key did not matter.

### Highest priorities

Terra's largest opportunities are avoiding repeated terrain work, bounding bursts of new work, and replacing dense slope geometry. Backend-specific tuning follows those shared opportunities.

| Priority | Finding | Evidence |
|---|---|---|
| 1 | Prefer raster slope classes, subject to exact class-edge rendering | Supplied polygon/raster measurements |
| 2 | Enable hillshade prepare-result reuse | Reuse flag exists, but no enabling caller was found |
| 3 | Repair terrain-depth invalidation | Preparation sets depthDirty on every call, apparently defeating reuse |
| 4 | Allocate/bake drapes for selected mesh coverage | Allocation precedes mesh trimming |
| 5 | Budget new drapes, uploads and drawable creation together | Never-rendered drapes bypass the drape budget |
| 6 | Add movement resolution with guaranteed sharp settled output | Strong resource rationale; web gains need native validation |
| 7 | Investigate instanced GL depth versus per-tile Vulkan/Metal depth | Confirmed backend asymmetry |
| 8 | Remove startup request dependencies and persist compilation caches | Needs native dependency/cache-state measurements |

Already implemented and **not new proposals**: the user's cover/idle/centre-elevation fixes, static-render partial-frame cap, Vulkan missing-image crash avoidance, click timing fix, public elevation queries, mapriot's overzoom/drape/distance/completion changes; plus per-target drape signatures, skipped unchanged draped-layer tweakers, hillshade active-target cleanup, bounded DEM retention, a shared CPU terrain grid, GL depth instancing and progressive loading modes. Existing mechanisms still require effectiveness and correctness tests.

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

**Q1. Prefer raster slope classes.** Scenario/stage/backend: pitched overlays, zoom and thermal endurance; network, workers, uploads, vertex/fragment work; A. Gain: measured representation advantage, approximately 8–24.5 fps across the supplied cost ranges, but obtain the actual delta from paired same-camera runs. The polygon completion penalty is another large opportunity. Small application/data change if raster semantics match; class accuracy is the risk. Complete nearest/class-preserving rendering before adoption. Retain vectors where geometry is required without drawing the entire dense dataset. Verify DEVICE-OVERLAYS, START and soak, with threshold/tile-seam/zoom class tests. Status: Terra choice; nearest support relates to [#4117](https://github.com/maplibre/maplibre-native/issues/4117).

**Q2. Enable hillshade prepare reuse.** Scenario/stage/backend: nearly every view; GPU fragment/pass/bandwidth; A. RenderTarget::render supports setRenderOnce, but RenderHillshadeLayer::update does not enable it when creating targets; repository search found no caller. Strong elimination of repeated preparation for unchanged DEMs; milliseconds unmeasured. The source comment's historical ~50 ms is not Terra evidence. Small change; invalidate for replacement/border backfill, which already creates fresh targets. Verify HEADLESS-PASSES and hillshade orbit: one prepare per DEM/border revision, including seams and late neighbors.

**Q3. Repair terrain-depth cache invalidation.** Scenario/stage/backend: fixed camera with puck/route/fade updates; GPU geometry/fragment/bandwidth; A. prepareDepthTarget reattaches the group and marks depthDirty even for an existing target; renderDepth calls it before testing the flag. Strong opportunity to remove the depth pass on these redraws, not during orbit. Small change; dirty on creation, resize, terrain/DEM/exaggeration or projection changes rather than unchanged attachment. Verify IDLE-DYNAMIC: zero depth passes for puck-only changes, with camera/DEM/resize occlusion tests.

**Q4. Select the mesh cover before drape allocation.** Scenario/stage/backend: steep pitch and overlays; CPU allocation/GPU memory/drape work; A. Renderer::Impl allocates from computeMeshCover before RenderTerrain::update trims to maxMeshTiles. Strong savings proportional to targets never sampled by selected meshes; instrument the set difference. Small-to-medium refactor; preserve intentional ancestor fallbacks, never shrink visible coverage further. Verify HEADLESS-COUNTS and DEVICE-PITCH: selected coverage plus explicit fallbacks equals the pool, with horizon/bottom-corner tests.

**Q5. Cache summit image generation across reloads.** Scenario/stage/backend: reload, locale, startup; app CPU/JNI/atlas upload; A. Strong elimination of regeneration of the reported 220 images; registration may remain necessary. Bounded keys include text, locale, appearance, size and data version. Small change; stale locale/theme is the risk. Prefer one stretchable box plus text where equivalent to per-summit bitmaps. Verify STYLE-RELOAD generation/atlas counts and locale/icon-text-fit goldens. Mainly app-side.

**Q6. Bundle/embed versioned metadata.** Scenario/stage/backend: empty-cache start/weak data; network dependency latency/JSON parse; A. Strong elimination of actually observed style/TileJSON discovery RTTs. Embed tiles/bounds/zooms/encoding/attribution where permitted or warm metadata together. Small change; metadata/data-release consistency is essential. Verify START-NETWORK DAG and equivalent-source/stale-release tests. Do not assume GL JS's DEM→base request dependency applies identically: native TilePyramid already has initial coverage/prefetch.

**Q7. Use incremental mutations for benchmark layer insertion when equivalent.** Scenario/stage/backend: presets/test setup; parse/layout/atlases/requests; A. Strong avoidance of whole-style work. Keep product visibility switches; batch incremental changes where possible, separately benchmark genuine reloads. Small change; ordering/transitions may differ. Verify STYLE-SWITCH images and request/layout counts. Existing application APIs.

**Q8. Prefer GLES on the measured G715 configuration for now.** Scenario/stage/backend: pitch60 tails; presentation/GPU-driver; G/V. Measured slow-end improvement 33→about16.7 ms, not double mean fps. Small device/OS/driver policy, with override and retest each bump. Provisional GL on affected PowerVR is correctness-driven, not an assumed speed win. Verify backend A/B and physical panel: [#4506](https://github.com/maplibre/maplibre-native/issues/4506) reports corruption absent from screenshots.

**Q9. Remove nonessential terrain-overlay fades.** Scenario/stage/backend: arrival/switch/idle; scheduling and repeated drapes; A. Strong if transitions cause rebakes, small otherwise. Start with zero raster-overlay fade and zero/short slope/aspect paint transitions; retain label fades if useful. Small change; loading pops are the risk. Verify ZOOM bake/revision counts and arrival-order videos. Existing properties where supported; check Terra's exact style.

**Q10. Enforce loaded-static zero work.** Scenario/stage/backend: battery/heat; frames/timers/wakeups; A. Android already exposes WHEN_DIRTY. Audit deferred loops, fades, completion state, location animation, timers and app polling. Strong if unnecessary wakeups exist; zero gain if already idle. Small-to-medium; missed redraw risk. Verify IDLE after completion/toggle/restore, then event tests that wake exactly as necessary.

**Q11. Upload terrain placeholders before consumption.** Scenario/stage/backend: first terrain frame/toggle/partial data; upload/descriptor ordering; V, parity A. The skip-on-missing-image fix prevents crashes but may omit content. Prepare depth/elevation placeholders before upload/descriptor resolution. Strong correctness, possibly fewer recovery frames; no FPS promise. Small-to-medium lifetime/layout risk. Verify TERRAIN-TOGGLE, missing DEM, first-frame tests and Vulkan validation. Known remaining fork fix.

**Q12. Audit the shared OkHttp client's actual behavior.** Scenario/stage/backend: initial view/fast movement; network queueing; Android G/V. SDK default client configures 20 per-host requests, but the supplied Call.Factory may not inherit that. Record effective limits, queues, reuse, cancellation and interceptors. Hypothesis of RTT-scale savings if map requests queue behind app traffic. Small; overconcurrency hurts mobile links. Verify NETWORK with 4/8/12/20 concurrency, fixed RTT/loss and app traffic. Do not maximize concurrency blindly.

### 3.2 Medium changes

**M1. Budget all progressive work, including new drapes.** Scenario/stage/backend: zoom/start bursts; render CPU/upload/GPU; A. TerrainLoadMode limits builds and rebakes, but new drapes always render. Budget them using ancestor fallback or a valid lower-quality initial bake. Prioritize projected importance/foreground/central coverage, prevent starvation. Strong reduction of burst size, unknown completion tradeoff. Medium; holes/stale content/false completion risk. Verify ZOOM-BURST with synchronized arrivals, per-stage deadlines and final-quality convergence. Extends existing modes.

**M2. Movement-sensitive drape resolution.** Scenario/stage/backend: pitched zoom; fragments/bandwidth/memory; A. Use 512² moving near targets, restore current 1024² quality after 300–500 ms stillness, initially one refinement/frame under a time budget. Existing pool ignores changed requested size for a retained tile: resizing/rebinding is prerequisite. Strong 75% reduction in target pixel count, end-to-end gain hypothetical; web 33→85 fps is not a native forecast. Medium; double-allocation/seam risk, atomic replacement required. Verify ZOOM, size transitions and settled reference equality.

**M3. Runtime render scale independent of logical density.** Scenario/stage/backend: 2D/3D motion; fragments/bandwidth; A. Preserve viewport, symbols, picking and cover while reducing physical render dimensions. Do not animate construction-time pixelRatio as a framebuffer-only control. Web +21–38% motivates testing; native gain hypothetical. Medium coordinate/surface/text risk; quantized reusable sizes and hysteresis. Verify RESOLUTION, pick invariance, sharp final images and resize peaks.

**M4. Byte-budgeted drape pool and size-class reuse.** Scenario/stage/backend: memory/zoom reversals; allocation/driver/GPU memory; A. Current tile-keyed map removes departed targets immediately. Add bounded allocation reuse and only a measured-small recently-used content cache; account for in-flight resources. Strong bounded-memory/allocation benefit; CPU gain unknown. Medium stale-content/fence risk. Verify MEMORY-SOAK, reversal/allocation counters and poisoned reuse tests. Avoid large speculative caches.

**M5. Share terrain GPU grid buffers.** Scenario/stage/backend: arrivals/DEM upgrades; CPU copies/allocations/upload; A. createDrawableForTile copies cached grid vertices/indices; non-GL repeats for depth. Use immutable shared buffers per variant and per-tile bindings/uniforms. Trace backend dedup first. Strong CPU-copy removal; GPU gain conditional. Medium ownership/context risk. Verify MICRO-MESH, ZOOM, buffer identities and restored-context images.

**M6. Port depth instancing to Vulkan/Metal.** Scenario/stage/backend: steep orbit; submission/state/descriptors/upload; V/M. GL is already instanced; non-GL depth is per tile. Strong reduction from O(tiles) draw calls toward a small number; whole-frame gain unknown. Medium array-capacity/mapping/device-limit risk. Do not reproduce GL's fixed 64-instance limit without handling larger covers. Verify BACKEND-DEPTH, >64 coverage, ridges and ancestor fallback. Port of existing fork work.

**M7. Share DEM GPU storage across consumers.** Scenario/stage/backend: memory/arrivals; upload/GPU memory; A, especially G. Terrain, hillshade preparation and color-relief can create separate textures; GL also has an array. Share by content/revision with separate samplers where possible. Distinct elevation/slope/aspect datasets remain distinct. Strong ~1.02 MiB per duplicated 516² RGBA8 image, before driver overhead. Medium border/sampler/lifetime risk. Verify DEM-UPLOAD unique allocation counts, late-neighbor and source-toggle images.

**M8. Partial DEM-border and atlas updates.** Scenario/stage/backend: neighbors/icons/glyphs arriving; upload/allocation; A. Backfill invalidates prepared resources. Subupload changed strips, then regenerate affected output/edges where worthwhile; subupload atlas regions where the current backend replaces the atlas. Strong byte reduction, timing depends on batching. Medium; many tiny transfers may lose. Verify DEM-UPLOAD bytes/submissions and corner/atlas-bleeding images.

**M9. Persistent compilation caches and selective prewarm.** Scenario/stage/backend: start/first overlay; driver/render CPU; A. Vulkan has an in-memory VkPipelineCache created empty. Add validated disk persistence; investigate GLES program binaries and supported Metal binary archives. Searches found no persistent equivalents. Key by build/shader schema/device-driver/backend identifiers; invalidate safely. Strong when compilation is measured critical, no assumed native 1 s. Medium corruption/stale-cache/contention risk. Verify START-CACHE fresh/warm/invalid/driver-mismatch states and output. Serialize off the interaction path.

**M10. Reduce synchronous Vulkan initialization and staging churn.** Scenario/stage/backend: start/zoom arrivals; CPU/transfer/synchronization; V. submitOneTimeCommand submits then waits on a fence; dummy texture uses it. Subuploads allocate staging storage. Trace actual call sites rather than asserting all uploads wait. Batch initialization and use bounded staging rings/arenas retired by fences. Strong for measured waits/bursts, magnitude unknown. Medium synchronization risk. Verify BACKEND-UPLOAD wait/submission traces, validation and repeated upload stress.

**M11. Audit mobile attachment load/store and transitions.** Scenario/stage/backend: drapes/hillshade/depth; bandwidth/pass cost; A. Store sampled drape color; avoid storing dead transient depth/stencil. Inspect GLES invalidation, Vulkan ops/barriers and Metal memoryless attachments where valid. Hypothesis, potentially important on Mali/PowerVR/A10. Medium undefined-read risk. Verify BACKEND-PASSES traffic/counters, validation and overlap/stencil images.

**M12. Portable exact categorical color-relief.** Scenario/stage/backend: slope/aspect; fragment sampling/compatibility; A, especially V. Current stops use RGBA32F; DEM sampler is linear. For few classes compare uniforms or exact portable lookup storage plus class-preserving sampling. Keep continuous elevation/hillshade distinct. Strong portability, hypothetical speed benefit. Medium threshold/precision risk. Verify thresholds ±epsilon, nodata, aspect 0/360 and all devices. Addresses [#4150](https://github.com/maplibre/maplibre-native/issues/4150) and [#4117](https://github.com/maplibre/maplibre-native/issues/4117), not proof of a speedup.

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
|---|---|---|---|
| S1 | Avoid copying cached PMTiles directory vector before find_tile; offline lookup CPU/allocation; A | Strong one-copy-per-lookup removal; small lifetime review | MICRO-PMTILES lookup/run-length/missing results and allocations |
| S2 | Replace string directory keys and linear LRU maintenance if hot; A | Hypothesis; small–medium eviction risk | Large-directory benchmark and bounded-cache tests |
| S3 | Reuse drapedTiles, hillshade sets, terrain sets, GL depth-UBO scratch; render CPU; A/G | Strong allocation removal, time unknown; small clear/reentrancy risk | Allocations/frame and mutation renders |
| S4 | Replace TexturePool contains+at with one lookup; A | Strong tiny win; small/low risk | Lookup benchmark/pool tests |
| S5 | Walk DEM parents instead of scanning cached DEMs; placement/binding CPU; A | Strong algorithmic opportunity; small–medium wrap/ancestor risk | Sparse DEM/late-upgrade lookup tests |
| S6 | Fuse DEM copy/range scans or vectorize where worthwhile; worker CPU; A | Hypothesis; small–medium nodata/integer-semantics risk | MICRO-DEM exact ranges/negative values |
| S7 | Skip premultiplication for verified opaque DEMs if utility does not already do so; decode; A where decoder used | Hypothesis; small alpha/nodata risk | MICRO-IMAGE byte-exact opaque/alpha fixtures |
| S8 | Rebind improved DEM without rebuilding unchanged grid drawable; CPU/upload; A | Strong avoided copies; medium descriptor/revision risk | Ancestor-upgrade tests and drawable/upload counts |
| S9 | Skip unchanged UBO updates/descriptor dirties beyond current guards; A, especially V | Hypothesis; small–medium; inspect setTexture identity checks | Descriptor/UBO counters and replacement renders |
| S10 | Skip consecutive redundant pipeline/texture binds; G/V/M | Hypothesis; small–medium state-cache risk; Vulkan binds per segment | State counters and alternating-pipeline images |
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
|---|---|---|---|---|
| Moving render scale | 0.75× native linear; test logical DPR1 separately | 0.67–0.75× | 0.5–0.67× | Construction-time Android pixelRatio exists; independent runtime scale needs adding |
| Rest resolution | Native | Native | Native | Restore after 300–500 ms, explicit quality-complete event |
| Moving drapes | 512² near | 512² | 512²; test 256² only far | Internal sizing exists; motion policy/resize needed |
| Rest drapes | Existing 1024² near/far policy | Same | Same | Progressive refinement must finish |
| Refinement | Initially 1 target/frame then cost-based | 1/frame | 1/frame with headroom | Full cost budgeting needs adding |
| Drape soft budget | Trial 128 MiB | 96 MiB | 64 MiB | Includes attachments; may be insufficient, preserve coverage |
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
| Disk network cache | Trial 256 MiB | 256 MiB | 128 MiB | Verify platform cache API; archives separate |

1024² RGBA8 color is 4 MiB; depth/stencil can bring the target to about8–9 MiB before alignment/buffering/driver overhead. A 64-layer 516² RGBA8 DEM array is about65 MiB, additional to separately retained DEM textures. Count caps alone are insufficient.

Current fork public modes on Android/iOS:

| Mode | New builds/frame | Drape rebakes/frame | Base mesh cap |
|---|---:|---:|---:|
| Quality | Unlimited | Unlimited | 64 |
| Balanced | 32 | 16 | 48 |
| Performance | 8 | 4 | 24 |

Mesh cap scales with viewport area. Balanced/Performance cannot be permanent low-device defaults without settled visual validation: they trade terrain distance as well as scheduling. Split those concerns before using them to satisfy same quality at rest.

## 6. Performance test plan

Fix data release, camera, styles, locale, viewport and final images. Stable test IDs and raw traces/per-frame data accompany summaries. Build on Google Benchmark, benchmark/api/render.benchmark.cpp, tile-cover benchmarks, render tests and existing terrain/PMTiles fixtures.

### 6.1 Tier 1: microbenchmarks, relevant PRs on Linux CI

| Family | Fixtures/measurement | Correctness |
|---|---|---|
| MICRO-DEM | Terrarium construction/range/borders/sampling;256/512,negative,nodata,cliffs | Exact channels/ranges/corners/encoding |
| MICRO-IMAGE | Pixel-identical PNG/WebP: snow,relief,categories,opaque/alpha | Pixel equality and malformed/truncated |
| MICRO-MESH | Grid generation separately from N drawable constructions/rebinds;32/64/128 grids | Bounds,skirts,shared lifetime |
| MICRO-MVT | Sparse basemap,contours,real dense slopes; decompress separately | Geometry/properties and malformed |
| MICRO-FILL | 10k/50k/120k vertices,holes,slivers,many polygons | Topology,segments,masks |
| MICRO-LINE | Dense contours,long routes,joins,caps,dashes | Joins/caps/tile edges |
| MICRO-SYMBOL | Peaks/huts/contours/icon-text-fit;flat/terrain | Collision,elevation,occlusion,cross-tile |
| MICRO-STYLE | Locale parse/apply/visibility/paint/layout separately | Equivalent output/relayout counts |
| MICRO-COVER | Pitch0/45/60/85 where supported,negative/positive relief,sparse DEM,overzoom,wrap | Oracle,no holes,conservative bounds |
| MICRO-PMTILES | Header/root/leaf parse,lookup,hit/miss,concurrent | Gzip/corrupt/run-length/cancellation |

For pitch85 exercise core coverage even if public camera clamps lower; never call a clamped run an85-degree result. Measure ns/op, throughput, allocations/op, allocated bytes and output size. Separate decompression/decode/layout/upload preparation, plus end-to-end tile processing to detect shifted cost.

Use dedicated pinned optimized CI where possible, fixed toolchain, warmup/repeated batches/randomized baseline-candidate order. Initial reproducible timing gate >5%, tightening stable cases toward2–3%. Shared CI may have5–15% noise or worse; trigger dedicated reruns while exact allocation/output regressions fail directly. No promised1% detection on noisy CI.

### 6.2 Tier 2: headless rendering/resources, PRs and nightly

Fixed 2D/3D mountain scenes with all overlay combinations and summit variants. Test fully settled forced redraws and scripted camera/tile/style transitions.

Record draws,triangles,passes,pipeline/texture/descriptor changes; full/selected cover; drape allocations,bakes,skips,deferrals; hillshade/depth passes; logical texture/buffer bytes,allocation/upload/in-flight bytes; placement work and revisions; eventual quality completion.

Exact or explicitly budgeted count changes fail. Unchanged forced redraw must not prepare hillshade; puck-only must not redraw depth after Q3; unused drapes must not grow with precap cover. Use images and semantic assertions: exact slope-class interiors, controlled text tolerances. Compare progressive settled output with unlimited full-quality reference.

Software renderer establishes pass/draw/resource/logical-byte regressions, **not mobile FPS, physical residency or energy**. Treat software wall time separately, initially5–10% sensitivity; count gates are deterministic. Linux covers available GL/Vulkan paths; Metal requires macOS correctness infrastructure and physical iOS performance. Emulators give no phone frame-rate evidence.

### 6.3 Tier 3: devices, nightly and every engine bump

Pixel GL/Vulkan and iPhone7 Metal first; add actual Samsung SoC/GPU and PowerVR. Matrix: no overlays, raster slope,vector slope,aspect,all; explicit hillshade off/on; text-only/shared-box/generated summit variants. 60Hz first, separate120Hz baseline.

| ID | Procedure |
|---|---|
| DEVICE-ORBIT |20s z13/pitch60 plus45 control;streaming cold and fully resident warm |
| DEVICE-OVERLAYS |Paired identical paths/configurations for representation and layer deltas |
| DEVICE-PITCH |Tilt to maximum;relief,horizon,bottom corners;precap/selected counts |
| ZOOM/ZOOM-BURST |Cross integer levels/DEM maxzoom,reverse before completion,synchronized arrivals |
| DEVICE-COMBINED |Fixed zoom-turn-pan and fling;weak links |
| DEVICE-2D |Same overlays in flat pan/zoom |
| START |Process→activity frame→map content→data complete→full-quality present→idle |
| TERRAIN-TOGGLE |Repeated2D↔3D during pending requests;orphan/resource/partial-frame checks |
| STYLE-SWITCH |Visibility,incremental insertion,genuine reload separately |
| STYLE-RELOAD |Locale/generated images;generation/registration/atlas/request counts |
| RESOLUTION |Move→still,brief repeat gestures,resize mid-refinement;quality deadline/peak |
| PACING |30/60/120,display-rate changes,overload;presents/input latency |
| IDLE |Loaded static5–10min,no location;zero map frames/periodic wakeups |
| IDLE-DYNAMIC |Fixed camera,puck1/5/10Hz,route append;attribute each redraw |
| NAVIGATION |20–30min screen-on with actual separate service,realistic GPS/route growth |
| MEMORY-SOAK |20–30min regions/zooms/reversals;memory slope/cache turnover |
| RESTORE |Background,lock,rotation,surface recreation,pressure,process death/camera restore |
| NETWORK/FLING-NETWORK |Controlled RTT/bandwidth/loss,offline,warm cache,errors,missing DEM,cancellation |
| PMTILES |Local cold page cache where controllable/warm,multiple regions/datasets |
| BINARY |Delivered/installed size,mapped pages,load/link time,fresh start |
| DEVICE-LABELS |Density/box variants;layout/placement latency and settled stability |
| LOD/DEM-FORMATS |Error-bounded quality and format variants with identical reference terrain |
| BACKEND-* / DEM-UPLOAD |Instrumented depth/draw/pass/upload attribution experiments |

Startup states must separate fresh app-data, warm tiles/cold app-owned pipelines, warm tiles/pipelines/new process, reused activity/map, and offline warm data. pm clear does not prove OS/driver caches cold; record controlled caches.

Complete means current camera/style revision, visible data,valid terrain bindings,finished drapes,required symbols,then actual presentation. Record full-quality completion separately from moving-resolution coverage. Preserve existing corrected terrain-completion tests; first idle alone is insufficient.

### 6.4 Presentation metrics

Record map-surface actual intervals;p50/p95/p99;mean duration of worst1%/5% with explicit naming;missed-refresh count/fraction;intervals>33/>50ms plus refresh-normalized thresholds;longest stall/consecutive miss streak;callback CPU separately. Mean fps is presented count/elapsed, not mean reciprocal interval. At60Hz16ms is normally rounded16.67ms.

Use Perfetto/SurfaceFlinger attribution and verify map SurfaceView coverage. Activity/Compose FrameTimingMetric alone does not measure the separate map surface. See [FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) and [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics). Older/API26 devices use available surface/frame timestamps, validating with high-speed video if needed. iOS uses Instruments/Core Animation/Metal presentation evidence; display-link callbacks do not prove fresh map images.

### 6.5 Energy and thermal

| Workload | Measure | Gate |
|---|---|---|
| Static loaded |Incremental power over matched screen-on baseline,frames,CPU,wakeups |Zero map rendering;reproducible excess power/wakeups fail |
| Fixed pan/orbit |Joules/minute,work,temperature,frequencies |No significant regression at equal output/cadence |
|20–30min navigation/soak |Energy,tails over time,memory,throttle onset |No growth/earlier reproducible throttling/worse sustained pacing |

Android: battery/thermal state,dumpsys meminfo,Perfetto scheduling/frequency and rails where available. Pixel power metrics are system-wide; isolate other work. Battery percentage over20s is unusable for small energy differences. iOS: Instruments Energy/Metal/system traces; [MetricKit](https://developer.apple.com/documentation/metrickit) for production trends, not deterministic per-run watts.

Fixed brightness,radios,state of charge and charging state; energy tests unplugged or externally measured without changing charging behavior. Include separate outdoor bright-screen profile. Controlled cold-weather testing is separate: room-temperature thermal0 does not model a cold battery.

### 6.6 Metadata for every run

- Device,SoC/GPU,RAM,OS/build,driver where available.
- Renderer,engine commit/version,app/toolchain/build flags,data/style/font/image release.
- Physical/logical resolution,density,render scale,refresh,cap,quality knobs.
- FPS,presentation distribution,render/worker/main CPU,GPU time if reliable.
- Decode/layout/upload/drape/placement stage times and queue delays.
- Startup milestones and full-quality deadline.
- Requests,bytes,hits,cancellations,downloaded/decoded/uploaded-never-used tiles.
- RSS/PSS,native/Java heap,logical GPU bytes,physical residency where available,peak/steady values.
- Thermal status/temperatures throughout,frequencies,battery,charging,brightness.
- Trace overhead configuration; unavailable metrics marked unavailable.

Separate map and navigation process cost where possible, retaining whole-product totals.

### 6.7 Noise and release rules

Start cool: Android thermal0 plus stable temperature band;iOS nominal thermal state/environment controls. Status alone is coarse. Randomized round-robin baseline/candidate/config order, cooling between short tests. At least3 runs;5 is a starting minimum for small effects, not proof of1–3% sensitivity.

Establish10–20 A/A paired runs across sessions. Estimate paired differences using robust central measures and bootstrap confidence intervals over **runs**, not correlated frames. Gate when adverse change exceeds A/A noise and predeclared meaningful effect and confirms on paired rerun. Potential material regression stays blocked while unresolved; underpowered nonsignificance is not a pass.

| Tier | Initial detectable effect |
|---|---|
| Exact counts/allocations |One deterministic operation/resource |
| Dedicated microbench |2–3%;1% with enough stability/repeats |
| Shared CI timing |Often5–15%;dedicated rerun |
| Controlled headless timing |About5%;counts separate |
| Cool device stage CPU/GPU |About3–5%;more pairs for smaller |
| Presented tails |Often5–10% or detectable missed-vsync rate |
| Controlled startup |About3–5% or tens of ms |
| Controlled power |Often5–10%,instrument/device dependent |
| Battery percentage |Too coarse for small-change gates without long runs |

At60Hz stable16.67ms cannot reveal2% headroom: use stage execution and120Hz, while keeping60Hz user tests. Initial objectives: no50+ms stalls in warm resident representative orbit;p95 one interval where accepted device/config sustains it;bounded refinement;zero idle frames;no repeated-cycle memory growth. Final absolute thresholds need distributions, not only1.5–1.9s observed startup range.

PRs: micro/count/render correctness. Renderer changes: device smoke. Nightly: rotating device matrix. Weekly and engine bumps: energy/long soak. Before each13.6.1-terra.N promotion: required-device release gate. Pixel first does not establish iOS parity; run A10.

## 7. Open measurements

| Question | Experiment |
|---|---|
| Is measured binary missing hillshade reuse/depth guard effectiveness? |Pin commit;count passes in fixed redraw/warm orbit |
| Is Vulkan CPU/GPU/present bound? |Correlated stage/wait/present traces and depth A/B |
| Does polygon cost persist with settled drapes? |No arrivals vs forced invalidation vs zoom |
| Does native dynamic draping match web gains? |512/1024 movement,identical mesh/data,settled equality |
| Does framebuffer scaling still help after drape changes? |Factorial drape×framebuffer experiment;do not add percentages |
|256 or512 DEM? |Matched resolution/independent policies,requests/decode/memory/GPU |
|PNG or WebP per device/link? |Pixel-identical offline/shaped-network fixtures |
|How much DEM duplication is resident? |Identity accounting/GPU capture/shared-storage prototype |
|Why repeated-zoom hillshade slowdown after cleanup? |Long loop,active/retained targets,passes,references |
|Worker count? |1/2/4 with dense tiles and render contention/energy |
|Prefetch? |0/1/default with fixed link,useful bytes/completion |
|Summit box cost? |Shared box+text vs bitmaps;separately warm generation/atlas |
|Cache limits? |Budget sweep with navigation/restore/revisit |
|120Hz value? |Matched path/quality,input latency/misses/joules |
|Local archive strategy? |Open/read vs cached pread vs bounded mapping,cold/warm/concurrent |
|PowerVR render or presentation corruption? |Physical video plus screenshot/GPU capture,surface/backend variants |

## 8. Upstream context and first tranche

Preserve correctness from [#4701](https://github.com/maplibre/maplibre-native/pull/4701) (ancestor upgrades/drape comparisons), [#4558](https://github.com/maplibre/maplibre-native/pull/4558) (terrain labels/collision/picking), and [#4711](https://github.com/maplibre/maplibre-native/pull/4711)/[#4712](https://github.com/maplibre/maplibre-native/pull/4712) (gesture anchoring). These were checked as upstream context during the initial review; the checkout is the source authority for this report. [#4684](https://github.com/maplibre/maplibre-native/issues/4684) warrants repeated-zoom soak despite the existing cleanup. [#4414](https://github.com/maplibre/maplibre-native/issues/4414) means fog is not a presumed solution to distant coverage cost.

First implementation tranche: Q2–Q4,Q11,deterministic pass/resource tests and source-matched GLES/Vulkan traces. In parallel at the product/data level, adopt raster slope once class-edge correctness is established. These have the strongest evidence-to-effort ratio before deep renderer changes.
