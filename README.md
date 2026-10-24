# Strata

A multi-layer Android video editor. Kotlin, Jetpack Compose, and Google's Media3 Transformer.

## v0.1 features

- Multi-layer timeline: main track, video/photo overlay layers, text layers, audio layers
- Import 8K clips; Strata makes 720p preview copies (proxies) of 4K/8K clips so editing stays smooth
- Trim (drag the clip edges), split, duplicate, delete, reorder, move overlay clips (long-press and drag)
- Transform any layer by dragging, pinching and twisting it on the preview
- Keyframes on position, scale, rotation and opacity, with smooth easing (Pro)
- Speed, volume, brightness, contrast, saturation
- Canvas: 16:9, 9:16, 1:1, 4:5, 21:9 at 1080p or 4K, 24–60fps
- Undo/redo, autosave
- Export to Movies/Strata: up to 4K 60fps, H.264 / HEVC / AV1, custom bitrate (Pro)
- Pro mode toggle with a gold theme

## Free vs Pro

| | Free | Pro |
|---|---|---|
| Overlay layers | 3 | Unlimited |
| Export | 1080p 30fps, H.264 | 4K 60fps, HEVC, AV1 |
| Bitrate | Auto | Custom |
| Watermark | Yes | No |
| Keyframes | — | Yes |
| Speed | 0.5x–2x | 0.1x–10x |

Pro is a free switch while testing.

## How layers work

Each visible video layer becomes one Media3 sequence; the GPU compositor stacks them. Sequence 0 is an invisible full-length "clock" so output timing always follows the project frame rate. Transforms and keyframes are read live by the compositor, so moving a layer doesn't rebuild the preview.

The real limit on layers playing *at the same moment* is the phone's hardware video decoders. Proxies keep that cost low while editing.

## Build

GitHub Actions builds `Strata-v0.1.0.apk` on every push to `main` (Actions tab → latest run → Artifacts).

Locally: `gradle :app:assembleRelease` with JDK 17 and the Android SDK.
