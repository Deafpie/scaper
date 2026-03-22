# Scaper — RuneLite Plugin

Link your Old School RuneScape account to your clan's Discord server via the **Scaper** bot.

## How it works

1. Install the **Scaper** plugin from the RuneLite Plugin Hub
2. Log into OSRS — the Scaper panel appears in the sidebar
3. Click **Generate Code** to get a 6-character linking code (expires in 5 minutes)
4. In your clan's Discord, run `/link <code>` with the Scaper bot
5. Your OSRS account is now linked and you'll receive the **Verified** role

## Configuration

In RuneLite settings for Scaper, set the **API URL** to the URL provided by your clan admin.

## Building locally

```bash
./gradlew build
```

The compiled plugin jar will be in `build/libs/`.
