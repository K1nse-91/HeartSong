# HeartSong

## Platform
Minecraft Java Edition 26.2, Fabric client mod, Java 25.

## Confirmed task
Players select a single local MP3 through a hotkey-opened in-game screen. Player health below 20% starts music; health above 40% stops it; both endpoints and the range between preserve the current state. Package the mod JAR on the Windows desktop.

## Implementation decisions
Use the surrounding Minecraft settings-screen language, game font, standard navigable buttons and text input. H is the initial key and is rebindable. Store one imported track locally, persist settings, loop the track while the health state is active. Reset on death or leaving the world. These are implementation defaults, not extra user requirements.
