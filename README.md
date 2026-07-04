# Scaper — RuneLite Plugin

**Scaper** is a clan management plugin that bridges your in-game OSRS clan with your Discord server, filling the gaps to give your clan a richer, more connected experience.

Track your clanmates' progress, coordinate events, sync in-game clan chat to Discord, and give members a live dashboard of their stats — all without leaving the game.

## Features

- **Discord Bridge** — In-game clan chat synced to your Discord server in real time
- **Member Tracking** — Skills, boss KC, loot drops, and equipment tracked per member
- **Clan Events** — Create and manage clan events with skill/boss tracking and leaderboards
- **Account Linking** — Members verify their OSRS account via the dashboard at [scaper.icu](https://scaper.icu)
- **Clan Browser** — Advertise your clan and find members that are the right fit
- **Clan Dashboard** — A live web dashboard showing your clan's stats and activity

## Getting Started

1. Install **Scaper** from the RuneLite Plugin Hub
2. Log into OSRS — the Scaper panel appears in the sidebar
3. Click **Generate Code** to get a 6-character linking code
4. Head to [scaper.icu](https://scaper.icu) and enter the code in your dashboard to link your account
5. Your account is verified — your clan admin can connect your Discord server under **Clan Setup**

## Building locally

```bash
./gradlew build
```

The compiled plugin jar will be in `build/libs/`.
