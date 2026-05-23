package com.scaperplugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModelType;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxInput;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Manages authentic OSRS-style NPC dialogue boxes with animated chatheads.
 * Uses ChatboxPanelManager to render dialogue in the chatbox area.
 */
@Slf4j
public class GnomeDialogue
{
    private static final int GNOME_NPC_ID = 6094;
    private static final int CHATHEAD_ANIM_HAPPY = 591;
    private static final int CHATHEAD_ANIM_IDLE = 568;

    // Colours matching OSRS dialogue
    private static final int COLOR_NPC_NAME = 0x800000;   // dark red/brown
    private static final int COLOR_DIALOGUE = 0x000000;   // black
    private static final int COLOR_CONTINUE = 0x0000FF;   // blue

    private final ChatboxPanelManager chatboxPanelManager;
    private final Client client;
    private final ClientThread clientThread;
    private final KeyManager keyManager;
    private final MouseManager mouseManager;
    private final ScaperConfig config;

    private Runnable onRecruit;
    private WorldPoint dialogueStartPosition;
    private Timer dialogueMoveTimer;

    public GnomeDialogue(ChatboxPanelManager chatboxPanelManager,
                          Client client,
                          ClientThread clientThread,
                          KeyManager keyManager,
                          MouseManager mouseManager,
                          ScaperConfig config)
    {
        this.chatboxPanelManager = chatboxPanelManager;
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.keyManager = keyManager;
        this.mouseManager = mouseManager;
    }

    // =========================================================================
    // Public entry points
    // =========================================================================

    /**
     * Start the recruitment dialogue (when gnome is AT_HOME).
     * Flow: Player "Hello!" → Gnome responds → Options
     */
    public void startRecruitDialogue(Runnable onRecruit)
    {
        this.onRecruit = onRecruit;
        startMoveTracker();
        showPlayerDialogue("Hello!", this::showGnomeResponse);
    }

    /**
     * When gnome is already following, hint to use public chat.
     */
    public void showFollowingHint()
    {
        startMoveTracker();
        String name = config.gnomeName().trim();
        showNpcDialogue(name, "Say \"" + name.toLowerCase() + "\" in public chat to talk to me!",
            CHATHEAD_ANIM_HAPPY, () -> { stopMoveTracker(); chatboxPanelManager.close(); });
    }

    // =========================================================================
    // Class-level movement tracker (covers all dialogue steps incl. options)
    // =========================================================================

    private void startMoveTracker()
    {
        stopMoveTracker();
        if (client.getLocalPlayer() != null)
        {
            dialogueStartPosition = client.getLocalPlayer().getWorldLocation();
        }
        dialogueMoveTimer = new Timer("gnome-dialogue-move", true);
        dialogueMoveTimer.scheduleAtFixedRate(new TimerTask()
        {
            @Override
            public void run()
            {
                // Must read position on client thread for thread safety
                clientThread.invokeLater(() ->
                {
                    if (dialogueStartPosition != null && client.getLocalPlayer() != null)
                    {
                        WorldPoint current = client.getLocalPlayer().getWorldLocation();
                        if (!current.equals(dialogueStartPosition))
                        {
                            chatboxPanelManager.close();
                            stopMoveTracker();
                        }
                    }
                });
            }
        }, 100, 100);
    }

    private void stopMoveTracker()
    {
        if (dialogueMoveTimer != null)
        {
            dialogueMoveTimer.cancel();
            dialogueMoveTimer = null;
        }
        dialogueStartPosition = null;
    }

    // =========================================================================
    // Dialogue steps
    // =========================================================================

    private void showGnomeResponse()
    {
        String name = config.gnomeName().trim();
        showNpcDialogue(name, "You look like you've seen the world!",
            CHATHEAD_ANIM_HAPPY, this::showOptions);
    }

    private void showOptions()
    {
        chatboxPanelManager.openTextMenuInput("Select an Option")
            .option("You could too, want to come along?", () -> {
                chatboxPanelManager.close();
                if (onRecruit != null)
                {
                    onRecruit.run();
                }
            })
            .option("Goodbye", () -> {
                stopMoveTracker();
                chatboxPanelManager.close();
            })
            .build();
    }

    // =========================================================================
    // NPC dialogue (chathead on left, name, text, click to continue)
    // =========================================================================

    private void showNpcDialogue(String name, String text, int animId, Runnable onContinue)
    {
        chatboxPanelManager.openInput(new DialogueInput(onContinue)
        {
            @Override
            protected void open()
            {
                super.open();

                Widget container = chatboxPanelManager.getContainerWidget();
                container.deleteAllChildren();

                // --- NPC chathead (3D model) ---
                Widget head = container.createChild(-1, WidgetType.MODEL);
                head.setModelType(WidgetModelType.NPC_CHATHEAD);
                head.setModelId(GNOME_NPC_ID);
                head.setAnimationId(animId);
                head.setRotationX(40);
                head.setRotationY(0);
                head.setRotationZ(1882);
                head.setModelZoom(796);
                head.setOriginalX(35);
                head.setOriginalY(43);
                head.setOriginalWidth(32);
                head.setOriginalHeight(32);
                head.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
                head.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                head.revalidate();

                // --- NPC name (centered, dark red) ---
                Widget nameWidget = container.createChild(-1, WidgetType.TEXT);
                nameWidget.setText(name);
                nameWidget.setTextColor(COLOR_NPC_NAME);
                nameWidget.setFontId(FontID.QUILL_8);
                nameWidget.setXTextAlignment(WidgetTextAlignment.CENTER);
                nameWidget.setYTextAlignment(WidgetTextAlignment.TOP);
                nameWidget.setOriginalX(0);
                nameWidget.setOriginalY(5);
                nameWidget.setOriginalWidth(0);
                nameWidget.setOriginalHeight(20);
                nameWidget.setWidthMode(WidgetSizeMode.MINUS);
                nameWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
                nameWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                nameWidget.setTextShadowed(false);
                nameWidget.revalidate();

                // --- Dialogue text (centered, black) ---
                Widget textWidget = container.createChild(-1, WidgetType.TEXT);
                textWidget.setText(text);
                textWidget.setTextColor(COLOR_DIALOGUE);
                textWidget.setFontId(FontID.QUILL_8);
                textWidget.setXTextAlignment(WidgetTextAlignment.CENTER);
                textWidget.setYTextAlignment(WidgetTextAlignment.CENTER);
                textWidget.setOriginalX(0);
                textWidget.setOriginalY(25);
                textWidget.setOriginalWidth(0);
                textWidget.setOriginalHeight(60);
                textWidget.setWidthMode(WidgetSizeMode.MINUS);
                textWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
                textWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                textWidget.setTextShadowed(false);
                textWidget.revalidate();

                // --- "Click here to continue" (centered, blue) ---
                Widget cw = container.createChild(-1, WidgetType.TEXT);
                cw.setText("Click here to continue");
                cw.setTextColor(COLOR_CONTINUE);
                cw.setFontId(FontID.QUILL_8);
                cw.setXTextAlignment(WidgetTextAlignment.CENTER);
                cw.setYTextAlignment(WidgetTextAlignment.BOTTOM);
                cw.setOriginalX(-10);
                cw.setOriginalY(0);
                cw.setOriginalWidth(0);
                cw.setOriginalHeight(10);
                cw.setWidthMode(WidgetSizeMode.MINUS);
                cw.setHeightMode(WidgetSizeMode.MINUS);
                cw.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
                cw.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                cw.setTextShadowed(false);
                cw.revalidate();
                this.continueWidget = cw;

                log.info("[GnomeDialogue] NPC dialogue opened: {}", text);
            }
        });
    }

    // =========================================================================
    // Player dialogue (player chathead, text, click to continue)
    // =========================================================================

    private void showPlayerDialogue(String text, Runnable onContinue)
    {
        chatboxPanelManager.openInput(new DialogueInput(onContinue)
        {
            @Override
            protected void open()
            {
                super.open();

                Widget container = chatboxPanelManager.getContainerWidget();
                container.deleteAllChildren();

                // --- Player chathead (3D model of local player, RIGHT side) ---
                Widget head = container.createChild(-1, WidgetType.MODEL);
                head.setModelType(WidgetModelType.LOCAL_PLAYER_CHATHEAD);
                head.setAnimationId(CHATHEAD_ANIM_HAPPY);
                head.setRotationX(40);
                head.setRotationY(0);
                head.setRotationZ(166);
                head.setModelZoom(796);
                head.setOriginalX(35);
                head.setOriginalY(43);
                head.setOriginalWidth(32);
                head.setOriginalHeight(32);
                head.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
                head.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                head.revalidate();

                // --- Player name (centered, dark red) ---
                String playerName = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getName()
                    : "Player";
                Widget nameWidget = container.createChild(-1, WidgetType.TEXT);
                nameWidget.setText(playerName);
                nameWidget.setTextColor(COLOR_NPC_NAME);
                nameWidget.setFontId(FontID.QUILL_8);
                nameWidget.setXTextAlignment(WidgetTextAlignment.CENTER);
                nameWidget.setYTextAlignment(WidgetTextAlignment.TOP);
                nameWidget.setOriginalX(0);
                nameWidget.setOriginalY(5);
                nameWidget.setOriginalWidth(0);
                nameWidget.setOriginalHeight(20);
                nameWidget.setWidthMode(WidgetSizeMode.MINUS);
                nameWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
                nameWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                nameWidget.setTextShadowed(false);
                nameWidget.revalidate();

                // --- Dialogue text (centered, black) ---
                Widget textWidget = container.createChild(-1, WidgetType.TEXT);
                textWidget.setText(text);
                textWidget.setTextColor(COLOR_DIALOGUE);
                textWidget.setFontId(FontID.QUILL_8);
                textWidget.setXTextAlignment(WidgetTextAlignment.CENTER);
                textWidget.setYTextAlignment(WidgetTextAlignment.CENTER);
                textWidget.setOriginalX(0);
                textWidget.setOriginalY(25);
                textWidget.setOriginalWidth(0);
                textWidget.setOriginalHeight(60);
                textWidget.setWidthMode(WidgetSizeMode.MINUS);
                textWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
                textWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                textWidget.setTextShadowed(false);
                textWidget.revalidate();

                // --- "Click here to continue" (centered, blue) ---
                Widget cw = container.createChild(-1, WidgetType.TEXT);
                cw.setText("Click here to continue");
                cw.setTextColor(COLOR_CONTINUE);
                cw.setFontId(FontID.QUILL_8);
                cw.setXTextAlignment(WidgetTextAlignment.CENTER);
                cw.setYTextAlignment(WidgetTextAlignment.BOTTOM);
                cw.setOriginalX(-10);
                cw.setOriginalY(0);
                cw.setOriginalWidth(0);
                cw.setOriginalHeight(10);
                cw.setWidthMode(WidgetSizeMode.MINUS);
                cw.setHeightMode(WidgetSizeMode.MINUS);
                cw.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
                cw.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
                cw.setTextShadowed(false);
                cw.revalidate();
                this.continueWidget = cw;

                log.info("[GnomeDialogue] Player dialogue opened: {}", text);
            }
        });
    }

    // =========================================================================
    // Base dialogue input with key + mouse handling
    // =========================================================================

    /**
     * Abstract base for dialogue steps. Handles Space/Enter/click to advance.
     */
    private abstract class DialogueInput extends ChatboxInput
        implements KeyListener, MouseListener
    {
        private final Runnable onContinue;
        private boolean consumed = false;
        protected Widget continueWidget;
        private WorldPoint startPosition;
        private Timer moveCheckTimer;

        DialogueInput(Runnable onContinue)
        {
            this.onContinue = onContinue;
        }

        @Override
        protected void open()
        {
            keyManager.registerKeyListener(this);
            mouseManager.registerMouseListener(this);

            // Record player position when dialogue opens
            if (client.getLocalPlayer() != null)
            {
                startPosition = client.getLocalPlayer().getWorldLocation();
            }

            // Poll every 100ms to check if player moved
            moveCheckTimer = new Timer("gnome-move-check", true);
            moveCheckTimer.scheduleAtFixedRate(new TimerTask()
            {
                @Override
                public void run()
                {
                    if (startPosition != null && client.getLocalPlayer() != null)
                    {
                        WorldPoint current = client.getLocalPlayer().getWorldLocation();
                        if (!current.equals(startPosition))
                        {
                            clientThread.invokeLater(() ->
                            {
                                if (!consumed)
                                {
                                    consumed = true;
                                    chatboxPanelManager.close();
                                }
                            });
                            moveCheckTimer.cancel();
                        }
                    }
                }
            }, 100, 100);
        }

        @Override
        protected void close()
        {
            keyManager.unregisterKeyListener(this);
            mouseManager.unregisterMouseListener(this);
            if (moveCheckTimer != null)
            {
                moveCheckTimer.cancel();
                moveCheckTimer = null;
            }
        }

        private void advance()
        {
            if (consumed) return;
            consumed = true;

            // Show "Please wait..." text
            if (continueWidget != null)
            {
                continueWidget.setText("Please wait...");
                continueWidget.setTextColor(COLOR_DIALOGUE);
            }

            // Delay ~1 game tick (600ms) before advancing
            new Timer("gnome-dialogue", true).schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    clientThread.invokeLater(() ->
                    {
                        chatboxPanelManager.close();
                        if (onContinue != null)
                        {
                            onContinue.run();
                        }
                    });
                }
            }, 600);
        }

        // --- KeyListener ---

        @Override
        public void keyPressed(KeyEvent e)
        {
            if (e.getKeyCode() == KeyEvent.VK_SPACE
                || e.getKeyCode() == KeyEvent.VK_ENTER
                || e.getKeyCode() == KeyEvent.VK_1)
            {
                e.consume();
                advance();
            }
            else if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
            {
                e.consume();
                if (!consumed)
                {
                    consumed = true;
                    clientThread.invokeLater(() -> chatboxPanelManager.close());
                }
            }
        }

        @Override
        public void keyTyped(KeyEvent e) {}

        @Override
        public void keyReleased(KeyEvent e) {}

        // --- MouseListener ---

        @Override
        public MouseEvent mouseClicked(MouseEvent e)
        {
            // Check if click is in the chatbox area
            Widget container = chatboxPanelManager.getContainerWidget();
            if (container != null)
            {
                java.awt.Rectangle bounds = container.getBounds();
                if (bounds != null && bounds.contains(e.getPoint()))
                {
                    e.consume();
                    advance();
                }
            }
            return e;
        }

        @Override
        public MouseEvent mousePressed(MouseEvent e) { return e; }

        @Override
        public MouseEvent mouseReleased(MouseEvent e) { return e; }

        @Override
        public MouseEvent mouseEntered(MouseEvent e) { return e; }

        @Override
        public MouseEvent mouseExited(MouseEvent e) { return e; }

        @Override
        public MouseEvent mouseDragged(MouseEvent e) { return e; }

        @Override
        public MouseEvent mouseMoved(MouseEvent e)
        {
            if (continueWidget != null)
            {
                java.awt.Rectangle bounds = continueWidget.getBounds();
                if (bounds != null && bounds.contains(e.getPoint()))
                {
                    continueWidget.setTextColor(0xFFFFFF); // white on hover
                }
                else
                {
                    continueWidget.setTextColor(COLOR_CONTINUE); // blue default
                }
            }
            return e;
        }
    }
}
