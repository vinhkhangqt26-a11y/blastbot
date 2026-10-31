# BlastBot

Native Android project for a Block Blast assistant.

## Build
Push to `main` or run the **Build BlastBot APK** workflow manually. The debug APK is uploaded as the `BlastBot-apk` artifact.

## Current status
- Native Android project scaffold
- Accessibility-service setup screen
- Accessibility service can observe screen-change events
- Automatic gameplay is not enabled yet

Next: implement board recognition, move planning, and verified one-move-at-a-time execution. A completed Android drag gesture must not be treated as proof that the game accepted the placement.
