# Scaper — RuneLite Plugin

<p align="center">
  <img src="icon.png" alt="Scaper" width="160" />
</p>

**Scaper** is a platform for OSRS players to find a clan, grow their own, and bring their in-game world to the web. The RuneLite plugin connects your Old School RuneScape account to your Scaper profile and your clan's Discord server — tracking stats, boss kills, drops, and milestones automatically.

## Features

- **Discord Bridge** — In-game clan chat synced to your Discord server in real time
- **Member Tracking** — Skills, boss KC, loot drops, and equipment tracked per member
- **Clan Events** — Create and manage clan events with skill/boss tracking and leaderboards
- **Account Linking** — Verify your OSRS account via the dashboard at [scaper.icu](https://scaper.icu)
- **Clan Browser** — Advertise your clan and find members that are the right fit
- **Clan Dashboard** — A live web dashboard showing your clan's stats, collectibles, and activity
- **Cases & Collectibles** — Earn case keys through daily tasks and in-game play; open them for profile cosmetics
- **Vault Marketplace** — Trade and sell collectibles with other players

## How it works

1. Install the **Scaper** plugin from the RuneLite Plugin Hub
2. Log into OSRS — the Scaper panel appears in the sidebar
3. Click **Generate Code** in the panel under **Settings** to create a 6-character linking code (expires in 5 minutes)
4. Go to [scaper.icu](https://scaper.icu), log in with Discord, and open your **Dashboard**
5. Enter the code from the plugin panel to link your OSRS account
6. That&#39;s it — your stats, boss kills, and drops are now tracked automatically

## Building locally

```bash
./gradlew build
```

The compiled plugin jar will be in `build/libs/`.
