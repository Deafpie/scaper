package com.scaperplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects comprehensive in-game data and sends periodic snapshots to the Scaper API.
 *
 * <p>Tracked data includes:
 * <ul>
 *   <li>All 23 skills (level, boosted, XP)</li>
 *   <li>Equipment and Inventory</li>
 *   <li>World map location</li>
 *   <li>Boss kill counts (from chat messages)</li>
 *   <li>Loot drops (valuable + untradeable)</li>
 *   <li>Collection log entries</li>
 *   <li>Clan info, rank, and clan chat</li>
 *   <li>Quest states</li>
 *   <li>Combat achievements (varbit-based)</li>
 *   <li>Current world, account type, combat level</li>
 *   <li>HP, Prayer, Run energy, Weight</li>
 *   <li>Active pet/follower</li>
 *   <li>Friends list</li>
 * </ul>
 *
 * <p>All client API calls happen on the client thread (via onGameTick).
 * HTTP sends are dispatched asynchronously via CompletableFuture.
 */
@Slf4j
public class ScaperTracker
{
	private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

	// ── Chat message patterns ────────────────────────────────────────────────
	// "Your Zulrah kill count is: 150."
	private static final Pattern BOSS_KC_PATTERN = Pattern.compile(
		"Your (.+) kill count is: ([\\d,]+)");
	// "New item added to your collection log: Tanzanite fang"
	private static final Pattern COLLECTION_LOG_PATTERN = Pattern.compile(
		"New item added to your collection log: (.+)");
	// "Valuable drop: Abyssal whip (1,234,567 coins)"
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(
		"Valuable drop: (.+?) \\(([\\d,]+) coins\\)");
	// "Untradeable drop: Tome of fire (empty)"
	private static final Pattern UNTRADEABLE_DROP_PATTERN = Pattern.compile(
		"Untradeable drop: (.+)");
	// "Your completed kill count is: 5" (raids/generic)
	private static final Pattern RAIDS_KC_PATTERN = Pattern.compile(
		"Your completed (.+) count is: ([\\d,]+)");

	// ── Dependencies ─────────────────────────────────────────────────────────
	private final Client client;
	private final ScaperConfig config;
	private final OkHttpClient httpClient;

	// ── State ────────────────────────────────────────────────────────────────
	private int tickCounter = 0;
	private volatile String cachedRsn;

	// Event queues — populated on client thread, drained on snapshot send
	private final List<JsonObject> pendingBossKills = new ArrayList<>();
	private final List<JsonObject> pendingClanChat = new ArrayList<>();
	private final List<JsonObject> pendingCollectionLog = new ArrayList<>();
	private final List<JsonObject> pendingLootDrops = new ArrayList<>();

	public ScaperTracker(Client client, ScaperConfig config, OkHttpClient httpClient)
	{
		this.client = client;
		this.config = config;
		this.httpClient = httpClient;
	}

	public void setRsn(String rsn)
	{
		this.cachedRsn = rsn;
	}

	public String getRsn()
	{
		return cachedRsn;
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// Event handlers — called from ScaperPlugin @Subscribe methods
	// ═══════════════════════════════════════════════════════════════════════════

	/**
	 * Called every game tick (~0.6 seconds) on the client thread.
	 * Auto-detects RSN if not yet set, then periodically collects + sends data.
	 */
	public void onGameTick()
	{
		if (!config.trackingEnabled()) return;

		// Auto-detect RSN on first available tick
		if (cachedRsn == null)
		{
			Player local = client.getLocalPlayer();
			if (local != null && local.getName() != null && !local.getName().isEmpty())
			{
				cachedRsn = local.getName();
				log.info("Tracker auto-detected RSN: {}", cachedRsn);
			}
			else
			{
				return; // can't track without a name
			}
		}

		tickCounter++;

		// Convert config interval (seconds) to ticks (1 tick ≈ 0.6s)
		int intervalTicks = Math.max(17, (int) Math.ceil(config.trackingIntervalSeconds() / 0.6));

		if (tickCounter >= intervalTicks)
		{
			tickCounter = 0;
			collectAndSend();
		}
	}

	/**
	 * Called on every chat message. Captures:
	 * - Clan chat messages (type CLAN_CHAT)
	 * - Boss kill counts (from GAMEMESSAGE/SPAM)
	 * - Collection log entries (from GAMEMESSAGE)
	 * - Valuable/untradeable drops (from GAMEMESSAGE)
	 */
	public void onChatMessage(ChatMessage event)
	{
		if (cachedRsn == null || !config.trackingEnabled()) return;

		ChatMessageType type = event.getType();

		// ── Clan chat ─────────────────────────────────────────────────────
		if (config.trackClanChat() && type == ChatMessageType.CLAN_CHAT)
		{
			JsonObject msg = new JsonObject();
			msg.addProperty("sender", stripTags(event.getName()));
			msg.addProperty("message", stripTags(event.getMessage()));
			msg.addProperty("timestamp", System.currentTimeMillis());
			synchronized (pendingClanChat)
			{
				pendingClanChat.add(msg);
			}
		}

		// ── Game messages (boss KC, drops, collection log) ────────────────
		if (config.trackLoot() &&
			(type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM))
		{
			String text = stripTags(event.getMessage());

			// Boss KC
			Matcher kcMatcher = BOSS_KC_PATTERN.matcher(text);
			if (kcMatcher.find())
			{
				JsonObject kc = new JsonObject();
				kc.addProperty("boss", kcMatcher.group(1));
				kc.addProperty("killCount", parseFormattedNumber(kcMatcher.group(2)));
				kc.addProperty("timestamp", System.currentTimeMillis());
				synchronized (pendingBossKills)
				{
					pendingBossKills.add(kc);
				}
			}

			// Raids KC
			Matcher raidsMatcher = RAIDS_KC_PATTERN.matcher(text);
			if (raidsMatcher.find())
			{
				JsonObject kc = new JsonObject();
				kc.addProperty("boss", raidsMatcher.group(1));
				kc.addProperty("killCount", parseFormattedNumber(raidsMatcher.group(2)));
				kc.addProperty("timestamp", System.currentTimeMillis());
				synchronized (pendingBossKills)
				{
					pendingBossKills.add(kc);
				}
			}

			// Collection log
			Matcher clMatcher = COLLECTION_LOG_PATTERN.matcher(text);
			if (clMatcher.find())
			{
				JsonObject entry = new JsonObject();
				entry.addProperty("itemName", clMatcher.group(1));
				entry.addProperty("timestamp", System.currentTimeMillis());
				synchronized (pendingCollectionLog)
				{
					pendingCollectionLog.add(entry);
				}
			}

			// Valuable drop
			Matcher dropMatcher = VALUABLE_DROP_PATTERN.matcher(text);
			if (dropMatcher.find())
			{
				JsonObject drop = new JsonObject();
				drop.addProperty("itemName", dropMatcher.group(1));
				drop.addProperty("value", parseFormattedNumber(dropMatcher.group(2)));
				drop.addProperty("source", "valuable");
				drop.addProperty("timestamp", System.currentTimeMillis());
				synchronized (pendingLootDrops)
				{
					pendingLootDrops.add(drop);
				}
			}

			// Untradeable drop
			Matcher untradeMatcher = UNTRADEABLE_DROP_PATTERN.matcher(text);
			if (untradeMatcher.find())
			{
				JsonObject drop = new JsonObject();
				drop.addProperty("itemName", untradeMatcher.group(1));
				drop.addProperty("value", 0);
				drop.addProperty("source", "untradeable");
				drop.addProperty("timestamp", System.currentTimeMillis());
				synchronized (pendingLootDrops)
				{
					pendingLootDrops.add(drop);
				}
			}
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// Data collection — MUST run on client thread
	// ═══════════════════════════════════════════════════════════════════════════

	private void collectAndSend()
	{
		try
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("rsn", cachedRsn);
			payload.addProperty("timestamp", System.currentTimeMillis());

			// ── Basic info ────────────────────────────────────────────────
			payload.addProperty("world", client.getWorld());
			payload.addProperty("accountType", safeAccountType());

			Player localPlayer = client.getLocalPlayer();
			if (localPlayer != null)
			{
				payload.addProperty("combatLevel", localPlayer.getCombatLevel());

				// Location
				if (config.trackLocation())
				{
					WorldPoint loc = localPlayer.getWorldLocation();
					if (loc != null)
					{
						JsonObject location = new JsonObject();
						location.addProperty("x", loc.getX());
						location.addProperty("y", loc.getY());
						location.addProperty("plane", loc.getPlane());
						payload.add("location", location);
					}
				}
			}

			// ── HP & Prayer ───────────────────────────────────────────────
			payload.addProperty("hpCurrent", client.getBoostedSkillLevel(Skill.HITPOINTS));
			payload.addProperty("hpMax", client.getRealSkillLevel(Skill.HITPOINTS));
			payload.addProperty("prayerCurrent", client.getBoostedSkillLevel(Skill.PRAYER));
			payload.addProperty("prayerMax", client.getRealSkillLevel(Skill.PRAYER));

			// ── Run energy & weight ───────────────────────────────────────
			payload.addProperty("runEnergy", client.getEnergy());
			payload.addProperty("weight", client.getWeight());

			// ── All skills ────────────────────────────────────────────────
			collectSkills(payload);

			// ── Equipment ─────────────────────────────────────────────────
			if (config.trackEquipment())
			{
				collectItemContainer(payload, "equipment", InventoryID.EQUIPMENT);
			}

			// ── Inventory ─────────────────────────────────────────────────
			if (config.trackInventory())
			{
				collectItemContainer(payload, "inventory", InventoryID.INVENTORY);
			}

			// ── Clan info ─────────────────────────────────────────────────
			collectClanInfo(payload);

			// ── Pet / follower ────────────────────────────────────────────
			collectPet(payload);

			// ── Quest states ──────────────────────────────────────────────
			collectQuests(payload);

			// ── Combat achievements ───────────────────────────────────────
			collectCombatAchievements(payload);

			// ── Friends list ──────────────────────────────────────────────
			collectFriends(payload);

			// ── Drain event queues ────────────────────────────────────────
			drainEvents(payload);

			// ── Send asynchronously ───────────────────────────────────────
			sendPayload(payload);
		}
		catch (Exception e)
		{
			log.warn("Error collecting tracking snapshot", e);
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// Individual collectors
	// ═══════════════════════════════════════════════════════════════════════════

	private void collectSkills(JsonObject payload)
	{
		try
		{
			JsonArray skills = new JsonArray();
			for (Skill skill : Skill.values())
			{
				JsonObject s = new JsonObject();
				s.addProperty("name", skill.getName());
				s.addProperty("level", client.getRealSkillLevel(skill));
				s.addProperty("boosted", client.getBoostedSkillLevel(skill));
				s.addProperty("xp", client.getSkillExperience(skill));
				skills.add(s);
			}
			payload.add("skills", skills);
		}
		catch (Exception e)
		{
			log.debug("Error collecting skills", e);
		}
	}

	private void collectItemContainer(JsonObject payload, String key, InventoryID inventoryId)
	{
		try
		{
			ItemContainer container = client.getItemContainer(inventoryId);
			if (container == null) return;

			JsonArray items = new JsonArray();
			Item[] containerItems = container.getItems();
			for (int i = 0; i < containerItems.length; i++)
			{
				Item item = containerItems[i];
				if (item.getId() <= 0 || item.getId() == -1) continue;

				JsonObject itemObj = new JsonObject();
				itemObj.addProperty("slot", i);
				itemObj.addProperty("itemId", item.getId());
				itemObj.addProperty("quantity", item.getQuantity());
				try
				{
					ItemComposition def = client.getItemDefinition(item.getId());
					itemObj.addProperty("name", def != null ? def.getName() : "Unknown");
				}
				catch (Exception e)
				{
					itemObj.addProperty("name", "Unknown");
				}
				items.add(itemObj);
			}
			payload.add(key, items);
		}
		catch (Exception e)
		{
			log.debug("Error collecting {}", key, e);
		}
	}

	private void collectClanInfo(JsonObject payload)
	{
		try
		{
			// Use ClanSettings for persistent info (name, your rank)
			ClanSettings clanSettings = client.getClanSettings();
			if (clanSettings == null) return;

			JsonObject clan = new JsonObject();
			clan.addProperty("name", clanSettings.getName());

			// Find our rank in the member list
			List<ClanMember> members = clanSettings.getMembers();
			if (members != null)
			{
				for (ClanMember member : members)
				{
					if (member.getName() != null
						&& member.getName().equalsIgnoreCase(cachedRsn))
					{
						clan.addProperty("myRank", String.valueOf(member.getRank()));
						break;
					}
				}
				clan.addProperty("totalMembers", members.size());
			}

			// Use ClanChannel for online member count
			ClanChannel channel = client.getClanChannel();
			if (channel != null)
			{
				List<ClanChannelMember> online = channel.getMembers();
				clan.addProperty("onlineMembers", online != null ? online.size() : 0);
			}

			payload.add("clan", clan);
		}
		catch (Exception e)
		{
			log.debug("Error collecting clan info", e);
		}
	}

	private void collectPet(JsonObject payload)
	{
		try
		{
			NPC follower = client.getFollower();
			if (follower != null)
			{
				JsonObject pet = new JsonObject();
				pet.addProperty("name", follower.getName());
				pet.addProperty("npcId", follower.getId());
				payload.add("pet", pet);
			}
		}
		catch (Exception e)
		{
			log.debug("Error collecting pet info", e);
		}
	}

	private void collectQuests(JsonObject payload)
	{
		try
		{
			JsonArray quests = new JsonArray();
			for (Quest quest : Quest.values())
			{
				try
				{
					QuestState state = quest.getState(client);
					JsonObject q = new JsonObject();
					q.addProperty("name", quest.getName());
					q.addProperty("state", state != null ? state.name() : "UNKNOWN");
					quests.add(q);
				}
				catch (Exception ignored)
				{
					// Some quests may not be queryable on certain account types
				}
			}
			payload.add("quests", quests);
		}
		catch (Exception e)
		{
			log.debug("Error collecting quest data", e);
		}
	}

	private void collectCombatAchievements(JsonObject payload)
	{
		try
		{
			JsonObject ca = new JsonObject();

			// Combat achievement tier completion varbits
			// These IDs are approximate and may need updating after game updates
			ca.addProperty("easyTasks", safeVarbit(12855));
			ca.addProperty("mediumTasks", safeVarbit(12856));
			ca.addProperty("hardTasks", safeVarbit(12857));
			ca.addProperty("eliteTasks", safeVarbit(12858));
			ca.addProperty("masterTasks", safeVarbit(12859));
			ca.addProperty("grandmasterTasks", safeVarbit(12860));
			ca.addProperty("totalPoints", safeVarbit(12063));

			payload.add("combatAchievements", ca);
		}
		catch (Exception e)
		{
			log.debug("Error collecting combat achievements", e);
		}
	}

	private void collectFriends(JsonObject payload)
	{
		try
		{
			NameableContainer<Friend> fc = client.getFriendContainer();
			if (fc == null) return;

			Friend[] members = fc.getMembers();
			if (members == null) return;

			JsonArray friends = new JsonArray();
			for (Friend friend : members)
			{
				if (friend == null) continue;
				JsonObject f = new JsonObject();
				f.addProperty("name", friend.getName());
				f.addProperty("world", friend.getWorld());
				friends.add(f);
			}
			payload.add("friends", friends);
		}
		catch (Exception e)
		{
			log.debug("Error collecting friends list", e);
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// Event queue draining
	// ═══════════════════════════════════════════════════════════════════════════

	private void drainEvents(JsonObject payload)
	{
		// Boss kills
		synchronized (pendingBossKills)
		{
			if (!pendingBossKills.isEmpty())
			{
				JsonArray arr = new JsonArray();
				pendingBossKills.forEach(arr::add);
				payload.add("bossKills", arr);
				pendingBossKills.clear();
			}
		}

		// Clan chat
		synchronized (pendingClanChat)
		{
			if (!pendingClanChat.isEmpty())
			{
				JsonArray arr = new JsonArray();
				pendingClanChat.forEach(arr::add);
				payload.add("clanChat", arr);
				pendingClanChat.clear();
			}
		}

		// Collection log
		synchronized (pendingCollectionLog)
		{
			if (!pendingCollectionLog.isEmpty())
			{
				JsonArray arr = new JsonArray();
				pendingCollectionLog.forEach(arr::add);
				payload.add("collectionLog", arr);
				pendingCollectionLog.clear();
			}
		}

		// Loot drops
		synchronized (pendingLootDrops)
		{
			if (!pendingLootDrops.isEmpty())
			{
				JsonArray arr = new JsonArray();
				pendingLootDrops.forEach(arr::add);
				payload.add("lootDrops", arr);
				pendingLootDrops.clear();
			}
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// HTTP sender
	// ═══════════════════════════════════════════════════════════════════════════

	private void sendPayload(JsonObject payload)
	{
		final String json = payload.toString();

		CompletableFuture.runAsync(() ->
		{
			try
			{
				String url = config.apiUrl().replaceAll("/+$", "") + "/api/tracking/snapshot";
				Request request = new Request.Builder()
					.url(url)
					.post(RequestBody.create(JSON_TYPE, json))
					.build();

				try (Response response = httpClient.newCall(request).execute())
				{
					if (!response.isSuccessful())
					{
						log.debug("Tracking API returned {}", response.code());
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to send tracking data: {}", e.getMessage());
			}
		});
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// Util
	// ═══════════════════════════════════════════════════════════════════════════

	/**
	 * Reset all tracker state (on logout or shutdown).
	 */
	public void reset()
	{
		tickCounter = 0;
		cachedRsn = null;
		synchronized (pendingBossKills) { pendingBossKills.clear(); }
		synchronized (pendingClanChat) { pendingClanChat.clear(); }
		synchronized (pendingCollectionLog) { pendingCollectionLog.clear(); }
		synchronized (pendingLootDrops) { pendingLootDrops.clear(); }
	}

	private String safeAccountType()
	{
		try
		{
			// Use reflection to avoid compile-time dependency on AccountType enum
			java.lang.reflect.Method m = client.getClass().getMethod("getAccountType");
			Object result = m.invoke(client);
			return result != null ? result.toString() : "NORMAL";
		}
		catch (Throwable e)
		{
			return "NORMAL";
		}
	}

	private int safeVarbit(int varbitId)
	{
		try
		{
			return client.getVarbitValue(varbitId);
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	/** Strip HTML/color tags from RuneScape chat messages. */
	private static String stripTags(String input)
	{
		if (input == null) return "";
		return input.replaceAll("<[^>]+>", "");
	}

	/** Parse number strings like "1,234,567" → 1234567 */
	private static long parseFormattedNumber(String text)
	{
		try
		{
			return Long.parseLong(text.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
