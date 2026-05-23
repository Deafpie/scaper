package com.scaperplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import okhttp3.*;

import net.runelite.client.util.Text;
import net.runelite.client.callback.ClientThread;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * AI-powered gnome companion that follows the player and responds to "gnome"
 * keyword in public chat. Uses ClientTick interpolation for smooth walking.
 *
 * Has a home location in the Tree Gnome Stronghold (2463, 3431). The gnome can
 * be recruited via Talk-to dialogue and dismissed back to home with a smoke puff.
 */
@Slf4j
public class GnomeCompanion
{
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int GNOME_NPC_ID = 6094;

    private static final int TICKS_PER_CHUNK = 5;
    private static final int COOLDOWN_TICKS = 10;
    private static final int MAX_FOLLOW_DISTANCE = 10;

    private static final int ANIM_IDLE = 2331;
    private static final int ANIM_WALK = 12042;

    // Walking speed: 1 tile = 128 local units over 30 client ticks (= 1 game tick = 0.6s)
    private static final int WALK_DURATION = 30;

    // Home location in Tree Gnome Stronghold
    private static final WorldPoint HOME_LOCATION = new WorldPoint(2463, 3431, 0);
    private static final int HOME_ORIENTATION = 512; // Facing west
    private static final int HOME_DETECT_RANGE = 20; // tiles away from home to spawn the "at home" NPC

    // Smoke puff graphic (random event disappear effect)
    private static final int SMOKE_GRAPHIC_ID = 86;
    private static final int SMOKE_DESPAWN_TICKS = 3; // game ticks before removing smoke object

    // -- Gnome state --
    public enum GnomeState { AT_HOME, FOLLOWING }
    private GnomeState gnomeState = GnomeState.AT_HOME;

    // -- Dependencies --
    private final Client client;
    private final ScaperConfig config;
    private final OkHttpClient httpClient;
    private ClientThread clientThread; // set via setter

    // -- Core state --
    private RuneLiteObject gnomeObject;
    private boolean spawned = false;
    private WorldPoint gnomeWorldPos;
    private int cooldownRemaining = 0;
    private boolean waitingForResponse = false;

    // -- Smoke puff effect --
    private RuneLiteObject smokePuffObject;
    private int smokePuffTicksRemaining = 0;

    // -- Dialogue (delegated to GnomeDialogue) --
    private GnomeDialogue gnomeDialogue; // set via setter

    // -- Smooth movement interpolation --
    private LocalPoint stepFrom;
    private LocalPoint stepTo;
    private int stepTicks;
    private boolean stepping = false;
    private boolean walkAnimActive = false;

    // -- Reaction delay (one-time pause when player starts moving) --
    private boolean gnomeIsIdle = true;
    private int reactionDelayTicks = 0;
    private static final int REACTION_DELAY = 1;
    private int settleDelayTicks = 0;

    // -- Smooth rotation --
    private int currentOrientation = 0;
    private int targetOrientation = 0;
    private static final int TURN_SPEED = 32;

    // -- Response display --
    private final Queue<String> responseQueue = new LinkedList<>();
    private int chunkDisplayTicks = 0;
    private boolean modelLoaded = false;

    // -- Overhead text (drawn by GnomeOverlay) --
    private String overheadText = null;
    private int overheadTicksRemaining = 0;
    private static final int OVERHEAD_DISPLAY_TICKS = 5;

    public GnomeCompanion(Client client, ScaperConfig config, OkHttpClient httpClient)
    {
        this.client = client;
        this.config = config;
        this.httpClient = httpClient;
    }

    public void setClientThread(ClientThread clientThread)
    {
        this.clientThread = clientThread;
    }

    public void setGnomeDialogue(GnomeDialogue gnomeDialogue)
    {
        this.gnomeDialogue = gnomeDialogue;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void onGameTick(String rsn)
    {
        boolean shouldBeActive = config.gnomeCompanion() && rsn != null
            && client.getGameState() == GameState.LOGGED_IN;

        // Handle smoke puff countdown
        if (smokePuffTicksRemaining > 0)
        {
            smokePuffTicksRemaining--;
            if (smokePuffTicksRemaining <= 0)
            {
                removeSmokePuff();
            }
        }

        if (!shouldBeActive)
        {
            if (spawned) despawnGnome();
            return;
        }

        if (gnomeState == GnomeState.AT_HOME)
        {
            // Spawn at home location if player is near the stronghold
            Player local = client.getLocalPlayer();
            if (local != null)
            {
                int distToHome = local.getWorldLocation().distanceTo(HOME_LOCATION);
                if (distToHome <= HOME_DETECT_RANGE && !spawned)
                {
                    spawnGnomeAtHome();
                }
                else if (distToHome > HOME_DETECT_RANGE && spawned)
                {
                    despawnGnome();
                }
            }
            return; // Don't follow when at home
        }

        // FOLLOWING state
        if (!spawned)
            spawnGnome();

        if (!spawned) return;

        decideMovement();

        if (cooldownRemaining > 0) cooldownRemaining--;

        // Tick down overhead text timer
        if (overheadTicksRemaining > 0)
        {
            overheadTicksRemaining--;
            if (overheadTicksRemaining <= 0)
            {
                overheadText = null;
            }
        }

        processResponseDisplay();
    }

    public void onClientTick()
    {
        if (!spawned || gnomeObject == null) return;

        // Don't interpolate movement when at home (standing still)
        if (gnomeState == GnomeState.AT_HOME) return;

        // Interpolate position if a step is in progress
        if (stepping && stepFrom != null && stepTo != null)
        {
            stepTicks++;

            if (stepTicks >= WALK_DURATION)
            {
                gnomeObject.setLocation(stepTo, gnomeWorldPos.getPlane());
                stepping = false;

                Player local = client.getLocalPlayer();
                if (local != null)
                {
                    int dist = gnomeWorldPos.distanceTo(local.getWorldLocation());
                    if (dist <= 1 && walkAnimActive)
                    {
                        walkAnimActive = false;
                        setIdleAnim();
                    }
                }
            }
            else
            {
                double t = (double) stepTicks / WALK_DURATION;
                int x = stepFrom.getX() + (int) ((stepTo.getX() - stepFrom.getX()) * t);
                int y = stepFrom.getY() + (int) ((stepTo.getY() - stepFrom.getY()) * t);
                LocalPoint interp = new LocalPoint(x, y, client.getTopLevelWorldView());
                gnomeObject.setLocation(interp, gnomeWorldPos.getPlane());
            }
        }

        updateFacingTarget();
        interpolateOrientation();
    }

    // =========================================================================
    // Chat detection
    // =========================================================================

    public void onChatMessage(ChatMessage event, String rsn)
    {
        if (!spawned || rsn == null) return;
        if (gnomeState != GnomeState.FOLLOWING) return;

        log.info("[Gnome] Chat event: type={} name='{}' msg='{}' rsn='{}'",
            event.getType(), event.getName(), event.getMessage(), rsn);

        if (event.getType() != ChatMessageType.PUBLICCHAT) return;

        String sender = Text.sanitize(event.getName());
        String cleanRsn = Text.sanitize(rsn);

        log.info("[Gnome] After sanitize: sender='{}' rsn='{}'", sender, cleanRsn);

        if (!sender.equalsIgnoreCase(cleanRsn)) return;

        String message = Text.sanitize(event.getMessage());
        String triggerName = config.gnomeName().trim().toLowerCase();
        if (!message.toLowerCase().matches(".*\\b" + java.util.regex.Pattern.quote(triggerName) + "\\b.*"))
        {
            log.info("[Gnome] Message didn't contain '{}': '{}'", triggerName, message);
            return;
        }

        if (cooldownRemaining > 0 || waitingForResponse)
        {
            log.info("[Gnome] On cooldown ({}) or waiting ({})", cooldownRemaining, waitingForResponse);
            return;
        }

        log.info("[Gnome] Trigger from {}: {}", cleanRsn, message);
        cooldownRemaining = COOLDOWN_TICKS;
        waitingForResponse = true;

        sendToApi(cleanRsn, message);
    }

    // =========================================================================
    // State management
    // =========================================================================

    /** Get the current gnome state. */
    public GnomeState getGnomeState()
    {
        return gnomeState;
    }

    /** Recruit the gnome to follow the player. */
    public void recruit()
    {
        gnomeState = GnomeState.FOLLOWING;
        despawnGnome(); // despawn from home
        // Will respawn near player on next game tick
        addGameMessage("The gnome happily agrees to join you on your adventure!");
        log.info("[Gnome] Recruited! State -> FOLLOWING");
    }

    /** Dismiss the gnome back to the Tree Gnome Stronghold with a smoke puff. */
    public void dismiss()
    {
        if (spawned && gnomeObject != null)
        {
            // Spawn smoke puff at gnome's current location
            spawnSmokePuff();
        }
        despawnGnome();
        gnomeState = GnomeState.AT_HOME;
        addGameMessage("Your gnome companion vanishes in a puff of smoke and returns to the Tree Gnome Stronghold.");
        log.info("[Gnome] Dismissed! State -> AT_HOME");
    }

    public void reset()
    {
        despawnGnome();
        removeSmokePuff();
        responseQueue.clear();
        waitingForResponse = false;
        cooldownRemaining = 0;
        modelLoaded = false;
        stepping = false;
        stepFrom = null;
        stepTo = null;
        gnomeIsIdle = true;
        reactionDelayTicks = 0;
        settleDelayTicks = 0;
        // Note: gnomeState is NOT reset here — it persists across scene loads
    }

    // =========================================================================
    // Dialogue system
    // =========================================================================

    /** Start the talk-to dialogue. Called when player clicks Talk-to. */
    public void startDialogue()
    {
        if (gnomeDialogue == null)
        {
            log.warn("[Gnome] No GnomeDialogue set — cannot open dialogue");
            return;
        }

        // Distance check — must be within 1 tile
        if (gnomeWorldPos != null && client.getLocalPlayer() != null)
        {
            WorldPoint pw = client.getLocalPlayer().getWorldLocation();
            int dist = Math.max(Math.abs(pw.getX() - gnomeWorldPos.getX()),
                                Math.abs(pw.getY() - gnomeWorldPos.getY()));
            if (dist > 1)
            {
                client.addChatMessage(
                    net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
                    "They're too far away to hear me.", "");
                return;
            }
        }

        if (gnomeState == GnomeState.AT_HOME)
        {
            // Recruitment dialogue with chathead
            gnomeDialogue.startRecruitDialogue(this::recruit);
        }
        else
        {
            // Already following — hint dialogue
            gnomeDialogue.showFollowingHint();
        }
    }

    // =========================================================================
    // Smoke puff effect
    // =========================================================================

    private void spawnSmokePuff()
    {
        try
        {
            if (gnomeWorldPos == null) return;

            WorldView wv = client.getTopLevelWorldView();
            LocalPoint lp = LocalPoint.fromWorld(wv, gnomeWorldPos);
            if (lp == null) return;

            smokePuffObject = client.createRuneLiteObject();

            // Load the spot anim's model data — graphic 86 uses model 325
            // and animation 65 for the smoke puff effect
            ModelData smokeModel = client.loadModelData(325);
            if (smokeModel != null)
            {
                smokePuffObject.setModel(smokeModel.light());
                Animation smokeAnim = client.loadAnimation(65);
                if (smokeAnim != null)
                {
                    smokePuffObject.setAnimation(smokeAnim);
                    smokePuffObject.setShouldLoop(false);
                }
                smokePuffObject.setLocation(lp, gnomeWorldPos.getPlane());
                smokePuffObject.setActive(true);
                smokePuffTicksRemaining = SMOKE_DESPAWN_TICKS;
                log.info("[Gnome] Smoke puff spawned at {}", gnomeWorldPos);
            }
            else
            {
                log.warn("[Gnome] Could not load smoke model");
            }
        }
        catch (Exception e)
        {
            log.warn("[Gnome] Smoke puff failed: {}", e.getMessage());
        }
    }

    private void removeSmokePuff()
    {
        if (smokePuffObject != null)
        {
            try { smokePuffObject.setActive(false); }
            catch (Exception ignored) {}
            smokePuffObject = null;
        }
        smokePuffTicksRemaining = 0;
    }

    // =========================================================================
    // Spawn / despawn
    // =========================================================================

    /** Spawn the gnome at its home location in the Tree Gnome Stronghold. */
    private void spawnGnomeAtHome()
    {
        try
        {
            WorldView wv = client.getTopLevelWorldView();
            LocalPoint lp = LocalPoint.fromWorld(wv, HOME_LOCATION);
            if (lp == null) return;

            if (!buildGnomeModel()) return;

            gnomeWorldPos = HOME_LOCATION;
            gnomeObject.setLocation(lp, HOME_LOCATION.getPlane());
            gnomeObject.setOrientation(HOME_ORIENTATION);
            currentOrientation = HOME_ORIENTATION;
            targetOrientation = HOME_ORIENTATION;
            gnomeObject.setActive(true);
            spawned = true;
            modelLoaded = true;
            log.info("[Gnome] Spawned at home: {}", HOME_LOCATION);
        }
        catch (Exception e)
        {
            log.error("[Gnome] Home spawn failed", e);
        }
    }

    /** Spawn the gnome near the player (following mode). */
    private void spawnGnome()
    {
        try
        {
            Player local = client.getLocalPlayer();
            if (local == null) return;

            if (!buildGnomeModel()) return;

            // Place gnome 1 tile behind the player (opposite of where they're facing)
            WorldPoint pw = local.getWorldLocation();
            int playerOri = local.getCurrentOrientation();
            int dx = 0, dy = 0;
            if (playerOri >= 768 && playerOri < 1280)
            {
                dy = -1;
            }
            else if (playerOri >= 1280 && playerOri < 1792)
            {
                dx = -1;
            }
            else if (playerOri >= 256 && playerOri < 768)
            {
                dx = 1;
            }
            else
            {
                dy = 1;
            }
            gnomeWorldPos = new WorldPoint(pw.getX() + dx, pw.getY() + dy, pw.getPlane());

            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), gnomeWorldPos);
            if (lp != null)
            {
                gnomeObject.setLocation(lp, gnomeWorldPos.getPlane());
                int facing = calculateOrientation(gnomeWorldPos, pw);
                gnomeObject.setOrientation(facing);
                currentOrientation = facing;
                targetOrientation = facing;
                gnomeObject.setActive(true);
                spawned = true;
                modelLoaded = true;
                log.info("[Gnome] Spawned at {}", gnomeWorldPos);
            }
        }
        catch (Exception e)
        {
            log.error("[Gnome] Spawn failed", e);
        }
    }

    /** Build the gnome NPC model and assign to gnomeObject. Returns true on success. */
    private boolean buildGnomeModel()
    {
        NPCComposition comp = client.getNpcDefinition(GNOME_NPC_ID);
        if (comp == null)
        {
            log.warn("[Gnome] No NPC def for {}", GNOME_NPC_ID);
            return false;
        }

        int[] modelIds = comp.getModels();
        if (modelIds == null || modelIds.length == 0)
        {
            log.warn("[Gnome] No models for NPC {}", GNOME_NPC_ID);
            return false;
        }

        ModelData[] parts = new ModelData[modelIds.length];
        for (int i = 0; i < modelIds.length; i++)
        {
            parts[i] = client.loadModelData(modelIds[i]);
            if (parts[i] == null)
            {
                log.warn("[Gnome] Model {} failed to load", modelIds[i]);
                return false;
            }
        }

        ModelData merged = client.mergeModels(parts, parts.length);

        short[] colorFind = comp.getColorToReplace();
        short[] colorReplace = comp.getColorToReplaceWith();
        if (colorFind != null && colorReplace != null)
        {
            for (int i = 0; i < Math.min(colorFind.length, colorReplace.length); i++)
            {
                merged.recolor(colorFind[i], colorReplace[i]);
            }
        }

        gnomeObject = client.createRuneLiteObject();
        gnomeObject.setModel(merged.light());
        setIdleAnim();
        return true;
    }

    private void despawnGnome()
    {
        if (gnomeObject != null)
        {
            try { gnomeObject.setActive(false); }
            catch (Exception e) { log.debug("[Gnome] Despawn err: {}", e.getMessage()); }
            gnomeObject = null;
        }
        spawned = false;
        stepping = false;
        walkAnimActive = false;
    }

    // =========================================================================
    // Movement - decide on game tick, interpolate on client tick
    // =========================================================================

    private void decideMovement()
    {
        if (gnomeObject == null || gnomeWorldPos == null) return;

        Player local = client.getLocalPlayer();
        if (local == null) return;

        WorldPoint playerWp = local.getWorldLocation();
        WorldPoint behindPlayer = getBehindPlayer(local, playerWp);
        int distance = gnomeWorldPos.distanceTo(playerWp);

        // Teleport if too far away (loading zone, etc.)
        if (distance > MAX_FOLLOW_DISTANCE)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), behindPlayer);
            if (lp != null)
            {
                gnomeObject.setLocation(lp, behindPlayer.getPlane());
                gnomeWorldPos = behindPlayer;
                stepping = false;
                stepFrom = null;
                stepTo = null;
                int facing = calculateOrientation(behindPlayer, playerWp);
                gnomeObject.setOrientation(facing);
                currentOrientation = facing;
                targetOrientation = facing;
            }
            if (walkAnimActive) { walkAnimActive = false; setIdleAnim(); }
            log.debug("[Gnome] Teleported to catch up, dist was {}", distance);
            return;
        }

        // If a step is still interpolating, force-complete it before deciding
        if (stepping && stepTo != null)
        {
            gnomeObject.setLocation(stepTo, gnomeWorldPos.getPlane());
            stepping = false;
        }

        // If the player stepped onto the gnome's tile, move behind the player
        if (distance == 0)
        {
            int plane = gnomeWorldPos.getPlane();
            WorldPoint nextTile = null;

            int bdx = behindPlayer.getX() - gnomeWorldPos.getX();
            int bdy = behindPlayer.getY() - gnomeWorldPos.getY();
            if (bdx != 0 || bdy != 0)
            {
                int sdx = Integer.compare(bdx, 0);
                int sdy = Integer.compare(bdy, 0);
                if (canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), sdx, sdy, plane))
                {
                    nextTile = new WorldPoint(gnomeWorldPos.getX() + sdx, gnomeWorldPos.getY() + sdy, plane);
                }
            }

            if (nextTile == null)
            {
                int[][] cardinals = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
                for (int[] dir : cardinals)
                {
                    if (canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), dir[0], dir[1], plane))
                    {
                        nextTile = new WorldPoint(gnomeWorldPos.getX() + dir[0], gnomeWorldPos.getY() + dir[1], plane);
                        break;
                    }
                }
            }

            if (nextTile != null)
            {
                WorldView wv = client.getTopLevelWorldView();
                LocalPoint from = LocalPoint.fromWorld(wv, gnomeWorldPos);
                LocalPoint to = LocalPoint.fromWorld(wv, nextTile);
                if (from != null && to != null)
                {
                    stepFrom = from;
                    stepTo = to;
                    stepTicks = 0;
                    stepping = true;
                    gnomeWorldPos = nextTile;
                    if (!walkAnimActive) { walkAnimActive = true; setWalkAnim(); }
                }
            }
            return;
        }

        // Target the tile behind the player instead of the player's tile
        int targetDist = gnomeWorldPos.distanceTo(behindPlayer);

        if (targetDist >= 1 && distance > 1)
        {
            settleDelayTicks = 1;
            if (gnomeIsIdle)
            {
                gnomeIsIdle = false;
                reactionDelayTicks = REACTION_DELAY;
                log.debug("[Gnome] Player started moving, waiting {} ticks before following", REACTION_DELAY);
            }

            if (reactionDelayTicks > 0)
            {
                reactionDelayTicks--;
                return;
            }

            int dx = Integer.compare(behindPlayer.getX() - gnomeWorldPos.getX(), 0);
            int dy = Integer.compare(behindPlayer.getY() - gnomeWorldPos.getY(), 0);

            WorldPoint nextTile = null;
            int plane = gnomeWorldPos.getPlane();

            if (canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), dx, dy, plane))
            {
                nextTile = new WorldPoint(gnomeWorldPos.getX() + dx, gnomeWorldPos.getY() + dy, plane);
            }
            else if (dx != 0 && dy != 0)
            {
                if (canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), dx, 0, plane))
                {
                    nextTile = new WorldPoint(gnomeWorldPos.getX() + dx, gnomeWorldPos.getY(), plane);
                }
                else if (canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), 0, dy, plane))
                {
                    nextTile = new WorldPoint(gnomeWorldPos.getX(), gnomeWorldPos.getY() + dy, plane);
                }
            }

            if (nextTile == null)
            {
                // Stuck — can't path, so stop walking animation
                if (walkAnimActive)
                {
                    walkAnimActive = false;
                    setIdleAnim();
                }
                return;
            }

            WorldView wv = client.getTopLevelWorldView();
            LocalPoint from = LocalPoint.fromWorld(wv, gnomeWorldPos);
            LocalPoint to = LocalPoint.fromWorld(wv, nextTile);

            if (from != null && to != null)
            {
                stepFrom = from;
                stepTo = to;
                stepTicks = 0;
                stepping = true;
                gnomeWorldPos = nextTile;

                if (!walkAnimActive)
                {
                    walkAnimActive = true;
                    setWalkAnim();
                }
            }
        }
        else
        {
            // Check if gnome is diagonally adjacent
            int diffX = playerWp.getX() - gnomeWorldPos.getX();
            int diffY = playerWp.getY() - gnomeWorldPos.getY();
            boolean isDiagonal = (diffX != 0 && diffY != 0 && Math.abs(diffX) <= 1 && Math.abs(diffY) <= 1);

            if (isDiagonal)
            {
                if (settleDelayTicks > 0)
                {
                    settleDelayTicks--;
                    return;
                }

                int plane = gnomeWorldPos.getPlane();
                WorldPoint nextTile = null;

                int bdx = Integer.compare(behindPlayer.getX() - gnomeWorldPos.getX(), 0);
                int bdy = Integer.compare(behindPlayer.getY() - gnomeWorldPos.getY(), 0);
                if (canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), bdx, bdy, plane))
                {
                    nextTile = new WorldPoint(gnomeWorldPos.getX() + bdx, gnomeWorldPos.getY() + bdy, plane);
                }
                else
                {
                    if (diffX == 0 || canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), diffX, 0, plane))
                    {
                        if (diffX != 0)
                            nextTile = new WorldPoint(gnomeWorldPos.getX() + diffX, gnomeWorldPos.getY(), plane);
                    }
                    if (nextTile == null && (diffY == 0 || canMoveTo(gnomeWorldPos.getX(), gnomeWorldPos.getY(), 0, diffY, plane)))
                    {
                        if (diffY != 0)
                            nextTile = new WorldPoint(gnomeWorldPos.getX(), gnomeWorldPos.getY() + diffY, plane);
                    }
                }

                if (nextTile != null)
                {
                    WorldView wv = client.getTopLevelWorldView();
                    LocalPoint from = LocalPoint.fromWorld(wv, gnomeWorldPos);
                    LocalPoint to = LocalPoint.fromWorld(wv, nextTile);
                    if (from != null && to != null)
                    {
                        stepFrom = from;
                        stepTo = to;
                        stepTicks = 0;
                        stepping = true;
                        gnomeWorldPos = nextTile;
                        if (!walkAnimActive) { walkAnimActive = true; setWalkAnim(); }
                        return;
                    }
                }
            }

            gnomeIsIdle = true;
            if (walkAnimActive)
            {
                walkAnimActive = false;
                setIdleAnim();
            }
        }
    }

    // =========================================================================
    // Facing / rotation
    // =========================================================================

    private void updateFacingTarget()
    {
        if (gnomeObject == null) return;

        Player local = client.getLocalPlayer();
        if (local == null) return;

        LocalPoint playerLp = local.getLocalLocation();
        if (playerLp == null) return;

        LocalPoint gnomeLp;
        if (stepping && stepFrom != null && stepTo != null && stepTicks <= WALK_DURATION)
        {
            double t = (double) stepTicks / WALK_DURATION;
            int x = stepFrom.getX() + (int) ((stepTo.getX() - stepFrom.getX()) * t);
            int y = stepFrom.getY() + (int) ((stepTo.getY() - stepFrom.getY()) * t);
            gnomeLp = new LocalPoint(x, y, client.getTopLevelWorldView());
        }
        else if (gnomeWorldPos != null)
        {
            gnomeLp = LocalPoint.fromWorld(client.getTopLevelWorldView(), gnomeWorldPos);
        }
        else
        {
            return;
        }

        if (gnomeLp == null) return;

        int dx = playerLp.getX() - gnomeLp.getX();
        int dy = playerLp.getY() - gnomeLp.getY();
        if (dx == 0 && dy == 0) return;

        double radians = Math.atan2(dx, dy);
        int angle = (int) Math.round(radians * 1024.0 / Math.PI) + 1024;
        targetOrientation = angle & 2047;
    }

    private void interpolateOrientation()
    {
        if (gnomeObject == null) return;
        if (currentOrientation == targetOrientation) return;

        int delta = targetOrientation - currentOrientation;

        if (delta > 1024) delta -= 2048;
        if (delta < -1024) delta += 2048;

        if (delta > TURN_SPEED) delta = TURN_SPEED;
        else if (delta < -TURN_SPEED) delta = -TURN_SPEED;

        currentOrientation = (currentOrientation + delta) & 2047;
        gnomeObject.setOrientation(currentOrientation);
    }

    // =========================================================================
    // Orientation / pathfinding helpers
    // =========================================================================

    private WorldPoint getBehindPlayer(Player player, WorldPoint playerWp)
    {
        int ori = player.getCurrentOrientation();
        int dx = 0, dy = 0;

        if (ori >= 768 && ori < 1280)      { dy = -1; }
        else if (ori >= 1280 && ori < 1792) { dx = -1; }
        else if (ori >= 256 && ori < 768)   { dx = 1; }
        else                                { dy = 1; }

        return new WorldPoint(playerWp.getX() + dx, playerWp.getY() + dy, playerWp.getPlane());
    }

    private boolean canMoveTo(int worldX, int worldY, int dx, int dy, int plane)
    {
        if (dx == 0 && dy == 0) return false;

        WorldView wv = client.getTopLevelWorldView();
        CollisionData[] collisionMaps = wv.getCollisionMaps();
        if (collisionMaps == null) return true;

        CollisionData cd = collisionMaps[plane];
        if (cd == null) return true;

        int[][] flags = cd.getFlags();

        int srcSceneX = worldX - wv.getBaseX();
        int srcSceneY = worldY - wv.getBaseY();
        int dstSceneX = srcSceneX + dx;
        int dstSceneY = srcSceneY + dy;

        if (srcSceneX < 0 || srcSceneY < 0 || srcSceneX >= flags.length || srcSceneY >= flags[0].length)
            return false;
        if (dstSceneX < 0 || dstSceneY < 0 || dstSceneX >= flags.length || dstSceneY >= flags[0].length)
            return false;

        int srcFlag = flags[srcSceneX][srcSceneY];
        int dstFlag = flags[dstSceneX][dstSceneY];

        if ((dstFlag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return false;
        if ((dstFlag & CollisionDataFlag.BLOCK_MOVEMENT_OBJECT) != 0) return false;

        if (dy == 1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0) return false;
        if (dy == -1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0) return false;
        if (dx == 1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0) return false;
        if (dx == -1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0) return false;

        if (dx != 0 && dy != 0)
        {
            int midXFlag = flags[srcSceneX + dx][srcSceneY];
            int midYFlag = flags[srcSceneX][srcSceneY + dy];

            if ((midXFlag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return false;
            if ((midXFlag & CollisionDataFlag.BLOCK_MOVEMENT_OBJECT) != 0) return false;
            if ((midYFlag & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return false;
            if ((midYFlag & CollisionDataFlag.BLOCK_MOVEMENT_OBJECT) != 0) return false;

            if (dx == 1 && dy == 1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST) != 0) return false;
            if (dx == -1 && dy == 1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST) != 0) return false;
            if (dx == 1 && dy == -1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST) != 0) return false;
            if (dx == -1 && dy == -1 && (srcFlag & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST) != 0) return false;
        }

        return true;
    }

    private int calculateOrientation(WorldPoint from, WorldPoint to)
    {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        if (dx == 0 && dy == 0) return 0;

        double radians = Math.atan2(dx, dy);
        int angle = (int) Math.round(radians * 1024.0 / Math.PI) + 1024;
        return angle & 2047;
    }

    // =========================================================================
    // Animation helpers
    // =========================================================================

    private void setIdleAnim()
    {
        if (gnomeObject == null) return;
        Animation anim = client.loadAnimation(ANIM_IDLE);
        if (anim != null)
        {
            gnomeObject.setAnimation(anim);
            gnomeObject.setShouldLoop(true);
        }
    }

    private void setWalkAnim()
    {
        if (gnomeObject == null) return;
        Animation anim = client.loadAnimation(ANIM_WALK);
        if (anim != null)
        {
            gnomeObject.setAnimation(anim);
            gnomeObject.setShouldLoop(true);
        }
    }

    // =========================================================================
    // AI chat -- send to API and display responses
    // =========================================================================

    private void sendToApi(String rsn, String message)
    {
        CompletableFuture.runAsync(() ->
        {
            try
            {
                String url = config.apiUrl().replaceAll("/+$", "") + "/api/gnome-chat";

                JsonObject body = new JsonObject();
                body.addProperty("rsn", rsn);
                body.addProperty("message", message);

                Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON_TYPE, body.toString()))
                    .build();

                try (Response response = httpClient.newCall(request).execute())
                {
                    if (response.isSuccessful() && response.body() != null)
                    {
                        String responseBody = response.body().string();
                        log.info("[Gnome] API response body: {}", responseBody);
                        JsonElement parsed = new JsonParser().parse(responseBody);
                        JsonObject obj = parsed.getAsJsonObject();
                        JsonArray chunks = obj.getAsJsonArray("chunks");

                        if (chunks != null && chunks.size() > 0)
                        {
                            synchronized (responseQueue)
                            {
                                for (int i = 0; i < chunks.size(); i++)
                                {
                                    responseQueue.add(chunks.get(i).getAsString());
                                }
                            }
                            log.info("[Gnome] Queued {} chunk(s) for display", chunks.size());
                        }
                        else
                        {
                            log.warn("[Gnome] API returned empty chunks");
                            addGameMessage("Gnome seems confused... (empty response)");
                        }
                    }
                    else
                    {
                        String errBody = response.body() != null ? response.body().string() : "no body";
                        log.warn("[Gnome] API returned status {} - {}", response.code(), errBody);
                        addGameMessage("Gnome couldn't think right now (API " + response.code() + ")");
                    }
                }
            }
            catch (Exception e)
            {
                log.warn("[Gnome] Failed to get AI response: {}", e.getMessage());
                addGameMessage("Gnome lost in thought... (" + e.getMessage() + ")");
            }
            finally
            {
                waitingForResponse = false;
            }
        });
    }

    private void processResponseDisplay()
    {
        if (responseQueue.isEmpty()) return;

        chunkDisplayTicks++;

        if (chunkDisplayTicks >= TICKS_PER_CHUNK)
        {
            chunkDisplayTicks = 0;
            String chunk;
            synchronized (responseQueue)
            {
                chunk = responseQueue.poll();
            }
            if (chunk != null)
            {
                displayGnomeMessage(chunk);
            }
        }
    }

    private void displayGnomeMessage(String message)
    {
        try
        {
            String name = config.gnomeName().trim();
            client.addChatMessage(
                ChatMessageType.PUBLICCHAT,
                name,
                message,
                name
            );

            overheadText = message;
            overheadTicksRemaining = OVERHEAD_DISPLAY_TICKS;

            log.info("[Gnome] Displayed: {}", message);
        }
        catch (Exception e)
        {
            log.warn("[Gnome] Failed to display message: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Public accessors for GnomeOverlay and ScaperPlugin
    // =========================================================================

    public String getOverheadText()
    {
        return overheadText;
    }

    public boolean isSpawned()
    {
        return spawned;
    }

    public WorldPoint getGnomeWorldPos()
    {
        return gnomeWorldPos;
    }

    public LocalPoint getGnomeLocalPoint()
    {
        if (gnomeObject == null || gnomeWorldPos == null) return null;

        if (stepping && stepFrom != null && stepTo != null && stepTicks <= WALK_DURATION)
        {
            double t = (double) stepTicks / WALK_DURATION;
            int x = (int) (stepFrom.getX() + (stepTo.getX() - stepFrom.getX()) * t);
            int y = (int) (stepFrom.getY() + (stepTo.getY() - stepFrom.getY()) * t);
            return new LocalPoint(x, y, client.getTopLevelWorldView());
        }

        return LocalPoint.fromWorld(client.getTopLevelWorldView(), gnomeWorldPos);
    }

    public int getGnomePlane()
    {
        return gnomeWorldPos != null ? gnomeWorldPos.getPlane() : 0;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private void addGameMessage(String msg)
    {
        try
        {
            client.addChatMessage(
                ChatMessageType.GAMEMESSAGE,
                "",
                "[Gnome] " + msg,
                ""
            );
        }
        catch (Exception e)
        {
            log.warn("[Gnome] Could not post game message: {}", e.getMessage());
        }
    }
}
