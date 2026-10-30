# RichTap offline SDK

`richtap-2.0.4-lite.aar` is the unmodified Lite AAR published in the vendor's MIT-licensed [RichTapDynamics sample](https://github.com/richtap-haptics/RichTapDynamics/tree/75dad7cfad71382437219f0aff7f198c40e377ea).

- Original path: `app/libs/richtap_sdk_lite.aar`
- Commit: `75dad7cfad71382437219f0aff7f198c40e377ea`
- Git blob: `753313c9fbaf68d08349c80aa255b2d067266daa`
- SHA-256: `9a942e397e8236fb67e57e4379ed3f3ac19dacc2c53a1ae957a81691be08501f`
- License notice is included in the APK at `assets/licenses/richtap.txt`.

The later 2.2.0 NETWORK sample initializes a network reporting service. This project deliberately uses the official offline Lite artifact; it does not modify or disable code inside an SDK. Hardware compatibility with current ROMs must be established on the user's phone. `isSupportedRichTap()` and a submitted playback request are not delivery acknowledgements.

The adapter only uses the SDK when its RichTap hardware path is reported supported. It does not use the SDK's generic emulation on other phones. Initialization failures and missing optional system libraries fall back to Android primitives, amplitude control or platform feedback. There is no user-facing engine selector.
