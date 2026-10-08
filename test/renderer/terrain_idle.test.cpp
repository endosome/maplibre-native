#include <mln/test/util.hpp>
#include <mln/test/stub_file_source.hpp>
#include <mln/test/stub_map_observer.hpp>
#include <mln/test/map_adapter.hpp>

#include <mln/gfx/headless_frontend.hpp>
#include <mln/map/camera.hpp>
#include <mln/map/map_options.hpp>
#include <mln/map/mode.hpp>
#include <mln/style/style.hpp>
#include <mln/util/image.hpp>
#include <mln/util/run_loop.hpp>

#include <cmath>

using namespace mln;

namespace {

// A DEM with relief (a slope east-west), so hillshade and the drape have work to do.
std::string makeSlopedDEMTile(uint32_t size) {
    PremultipliedImage image({size, size});
    for (uint32_t y = 0; y < size; ++y) {
        for (uint32_t x = 0; x < size; ++x) {
            // Terrarium: v = R*256 + G + B/256 - 32768; heights 1000..2000 m across the tile.
            const double v = 1000.0 + 1000.0 * x / size + 32768.0;
            uint8_t* px = image.data.get() + (y * size + x) * 4;
            px[0] = static_cast<uint8_t>(static_cast<int>(v) / 256);
            px[1] = static_cast<uint8_t>(static_cast<int>(v) % 256);
            px[2] = static_cast<uint8_t>((v - std::floor(v)) * 256);
            px[3] = 255;
        }
    }
    return encodePNG(image);
}

const char* terrainIdleStyle = R"STYLE({
  "version": 8,
  "sources": {
    "dem": {"type": "raster-dem", "tiles": ["http://example.com/dem/{z}/{x}/{y}.png"], "encoding": "terrarium",
            "minzoom": 6, "maxzoom": 12, "tileSize": 512}
  },
  "terrain": {"source": "dem", "exaggeration": 1.0},
  "layers": [
    {"id": "background", "type": "background", "paint": {"background-color": "#e8e4d8"}},
    {"id": "hillshade", "type": "hillshade", "source": "dem"}
  ]
})STYLE";

} // namespace

// At a zoom well past the DEM's maxzoom, in 3D, with the camera still, the map must come to rest: render a few frames,
// stop asking for more and report idle. It didn't (the iOS and Android apps at zoom 15.5 over a maxzoom-12 DEM).
TEST(TerrainIdle, ComesToRestPastTheDEMMaxzoom) {
    util::RunLoop loop;
    auto fileSource = std::make_shared<StubFileSource>(ResourceOptions::Default(), ClientOptions());
    const std::string tile = makeSlopedDEMTile(512);
    fileSource->tileResponse = [&](const Resource&) {
        Response res;
        res.data = std::make_shared<std::string>(tile);
        return res;
    };
    StubMapObserver observer;
    bool idle = false;
    int frames = 0;
    int partial = 0;
    int repaints = 0;
    observer.didFinishRenderingFrameCallback = [&](MapObserver::RenderFrameStatus status) {
        ++frames;
        if (status.mode != MapObserver::RenderMode::Full) ++partial;
        if (status.needsRepaint) ++repaints;
    };
    observer.didBecomeIdleCallback = [&] { idle = true; };
    HeadlessFrontend frontend{{402, 874}, 1};
    MapAdapter map(frontend, observer, fileSource,
                   MapOptions().withMapMode(MapMode::Continuous).withSize(frontend.getSize()));
    map.getStyle().loadJSON(terrainIdleStyle);
    map.jumpTo(CameraOptions().withCenter(LatLng{49.1795, 20.0881}).withZoom(15.5).withPitch(53.0).withBearing(284.0));
    for (int i = 0; i < 400 && !idle; ++i) {
        loop.runOnce();
        frontend.renderFrame();
    }
    EXPECT_TRUE(idle) << frames << " frames, " << partial << " partial, " << repaints << " asked for another";
}
