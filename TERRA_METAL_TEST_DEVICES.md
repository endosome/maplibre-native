# Terra Apple Metal test devices

This document defines the recommended physical-device coverage for the Metal investigations and regression tests in [TERRA_PERFORMANCE.md](TERRA_PERFORMANCE.md). It is a test-lab recommendation, not a claim of measured performance on the additional devices.

## Recommended device matrix

The existing iPhone 7 can exercise most of the proposed Metal changes. For ongoing regression coverage, use three representative iPhones, plus an iPad if Terra supports tablets. There is no need to purchase every Apple GPU generation.

| Priority | Device | GPU family | Coverage and reason |
|---|---|---|---|
| Keep | iPhone 7, A10 Fusion | Apple3 | Existing constrained-device reference: memory pressure, expensive offscreen passes, tile/upload bursts, sustained performance and iOS 15 compatibility. |
| Buy first | iPhone 15 Pro, A17 Pro | Apple9 | A newer GPU architecture and ProMotion up to 120 Hz. Tests rendering headroom, frame pacing, newer driver behavior and energy at 60 versus 120 Hz. |
| Add next | iPhone 12, A14 Bionic | Apple7 | Intermediate GPU generation and a mainstream phone workload. Detects changes that help the oldest or newer device but regress the intermediate generation. |
| If supporting iPad | iPad Pro 11-inch, third generation, M1, 2021 | Apple7 | Large viewport and different memory/thermal workload. Exercises larger terrain covers, drape counts, viewport-scaled mesh caps and instance/resource-capacity failures that phone views may not reveal. |

The M1 iPad and A14 iPhone share a feature-family classification, but they are not interchangeable performance references. Viewport, memory, GPU configuration and thermal behavior differ. Likewise, GPU-family membership does not imply identical speed across devices.

Sources:

- [Apple Metal feature-set and GPU-family tables](https://developer.apple.com/metal/Metal-Feature-Set-Tables.pdf)
- [iPhone 7 technical specifications](https://support.apple.com/en-us/111943)
- [iPhone 12 technical specifications](https://support.apple.com/en-za/111876)
- [iPhone 15 Pro technical specifications](https://support.apple.com/en-us/111829)
- [M1 iPad Pro 11-inch technical specifications](https://support.apple.com/en-us/111897)

## Coverage for the proposed engine changes

The identifiers below refer to the detailed proposals and correctness requirements in [the performance assessment](TERRA_PERFORMANCE.md).

| Change | Device coverage | What to establish |
|---|---|---|
| Remove CPU waits after each Metal offscreen target (R2) | Begin on iPhone 7; validate on the newer phone too | Command-buffer submission count, CPU wait duration, producer-consumer ordering and resource lifetimes. Preserve correct still-image readback and teardown completion. |
| Hillshade prepare-result reuse and depth-cache invalidation (Q2, Q3, R1) | All three iPhones | These are shared correctness/performance changes, not newer-GPU features. Require correct invalidation, unchanged images and fewer redundant passes. |
| Terrain-depth instancing and shared mesh buffers (M5, M6) | iPhone 7 and newer phone; add iPad for large covers | Reduced draw/copy/upload work with identical occlusion. Exercise more than 64 terrain tiles and capacity limits; do not accept missing depth as a speedup. |
| Memoryless or shared transient attachments (M11, R10) | iPhone 7 and newer phone | Actual format/storage support, allocation behavior, attachment lifetime and unchanged drape clipping. Do not assume discarding attachment contents frees their allocations. |
| Pipeline and binary-archive caching (M9) | Oldest supported OS and a current OS | API availability and runtime support, cold/warm launch behavior, safe invalidation and recovery. GPU generation alone does not establish API availability. |
| Dynamic render resolution and drape refinement (M2, M3, R8) | iPhone 7, iPhone 12 and iPhone 15 Pro | Constrained performance, intermediate behavior and 120 Hz pacing respectively. Require full-quality convergence, bounded memory peaks and stable picking/symbol sizes. |
| Battery, thermal and memory soaks | Every shipping reference device | Sustained performance, energy, throttling and process survival. A newer phone cannot establish the older phone's endurance. |
| Large viewport and resource-capacity tests | Physical iPad if supported | Selected mesh versus drape coverage, depth-instance capacity, memory peaks, horizon correctness and rotation/resize behavior. |

The full test procedures, metrics, noise rules and release gates remain in the performance assessment. This matrix specifies where to run them rather than duplicating their definitions.

## Purchase and rollout order

1. Keep the existing iPhone 7 as the constrained-device reference.
2. Add an iPhone 15 Pro first. This provides the largest missing test dimension, 120 Hz, while also checking the newer GPU path.
3. Add an iPhone 12 when establishing nightly release gates across an intermediate GPU generation.
4. Add the M1 iPad Pro if the product supports iPad layouts and large map viewports.

With a tight budget, the iPhone 7 plus iPhone 15 Pro is the initial two-phone matrix. It can investigate the important Metal findings, but does not provide the intermediate-generation coverage of the three-phone recommendation.

The iPhone 15 Pro is a representative newer test device, not a claim that it is the newest available model or that buying the newest phone is necessary.

## OS and hardware support boundaries

Retain a device on the oldest supported OS and test a newer device on a current supported OS. Record the exact OS build, app/engine build, GPU family, display settings and relevant runtime capabilities for every run. Preserve a compatible build/deploy/profiling toolchain for the older OS.

An iOS 15 deployment target alone does **not** make iPhone 7 the oldest supported hardware. Apple made iOS 15 available for iPhone 6s and later, including the first-generation iPhone SE. If Terra permits those devices, add an iPhone 6s or first-generation SE to minimum-device testing, or explicitly narrow the supported hardware policy. Sharing a GPU-family classification does not make their performance equivalent to the A10 reference.

Source: [Apple's iOS 15 compatibility announcement](https://www.apple.com/newsroom/2021/09/ios-15-is-available-today/).

Treat tablet hardware support separately too: if Terra supports iPad, the M1 tablet is a large-viewport reference, not proof that all older supported iPads perform acceptably. Determine the actual oldest supported tablet before declaring minimum-hardware coverage complete.

## Lab setup and interpretation

- Use physical devices for GPU timing, presentation, memory pressure, energy and thermal conclusions. Simulator runs are useful for functional tests but do not establish phone GPU performance.
- Record battery health and performance-management state on used phones, especially the iPhone 7. Battery aging must not be mistaken for an engine regression.
- Compare a change on the same device, OS, viewport, data, quality and scenario. Cross-device FPS differences do not measure the benefit of a change.
- Run the ProMotion device at controlled 60 Hz and 120 Hz configurations, recording actual display/presentation behavior rather than assuming an app request guarantees the requested cadence.
- Use Instruments and Metal captures to establish CPU/GPU ordering and resource behavior. Query supported capabilities and account for differences in available profiling metrics on the oldest device.
- Gate correctness at equal output: complete drapes, terrain depth coverage, slope classes and settled labels. Missing work is not a valid optimization.
- Use the main assessment's cool-start, round-robin, repeated-run and soak protocols. A successful short benchmark on the newer phone does not replace A10 memory and thermal tests.
