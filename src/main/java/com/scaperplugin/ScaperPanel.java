package com.scaperplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ScaperPanel extends PluginPanel
{
	private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
	private static final String API_URL = "https://api.scaper.icu";
	private static final String SITE_URL = "https://scaper.icu";
	private static final Color GOLD = new Color(212, 160, 23);
	private static final Color DARK_BG = new Color(30, 30, 30);
	private static final Color DARKER_BG = new Color(20, 20, 20);

	private final Client client;
	private final OkHttpClient httpClient;
	private final ScaperPlugin plugin;

	// Tabs
	private final JButton tabDashboard;
	private final JButton tabMarket;
	private final JButton tabSettings;
	private final JPanel cardPanel;
	private final CardLayout cardLayout;
	private String activeTab = "dashboard";

	// Dashboard
	private final JLabel tokenValueLabel;
	private final JPanel tasksPanel;

	// Market
	private final JPanel marketGrid;
	private final JLabel marketTokenValueLabel;

	// Settings
	private final JTextArea settingsDesc;
	private final JLabel settingsStatus;
	private final JPanel codePanel;
	private final JLabel codeLabel;
	private final JLabel timerLabel;
	private final JButton generateButton;
	private final JButton unlinkButton;

	// Inventory button
	private final JButton inventoryButton;

	// Logged-out overlay
	private final JPanel loggedOutPanel;

	// State
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private final ConcurrentHashMap<String, ImageIcon> iconCache = new ConcurrentHashMap<>();
	private ScheduledFuture<?> countdownFuture;
	private ScheduledFuture<?> pollFuture;
	private volatile String cachedRsn;
	private volatile boolean linked;
	private volatile boolean loggedIn;

	public ScaperPanel(Client client, OkHttpClient httpClient, ScaperPlugin plugin)
	{
		super(false);
		this.client = client;
		this.httpClient = httpClient;
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(DARK_BG);

		// ── Title ──
		JLabel title = new JLabel("Scaper");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
		title.setForeground(Color.WHITE);
		title.setHorizontalAlignment(SwingConstants.CENTER);
		title.setBorder(new EmptyBorder(8, 0, 6, 0));
		add(title, BorderLayout.NORTH);

		// ── Center wrapper (tabs + content + inventory btn) ──
		JPanel center = new JPanel(new BorderLayout());
		center.setBackground(DARK_BG);

		// Tab bar
		JPanel tabBar = new JPanel(new GridLayout(1, 3, 0, 0));
		tabBar.setBackground(DARKER_BG);
		tabBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		tabDashboard = makeTabButton("Dashboard");
		tabMarket = makeTabButton("Market");
		tabSettings = makeTabButton("Clan");
		tabBar.add(tabDashboard);
		tabBar.add(tabMarket);
		tabBar.add(tabSettings);
		center.add(tabBar, BorderLayout.NORTH);

		// Card panel
		cardLayout = new CardLayout();
		cardPanel = new JPanel(cardLayout);
		cardPanel.setBackground(DARK_BG);

		// ── Dashboard card ──
		JPanel dashCard = new JPanel();
		dashCard.setLayout(new BoxLayout(dashCard, BoxLayout.Y_AXIS));
		dashCard.setBackground(DARK_BG);
		dashCard.setBorder(new EmptyBorder(10, 8, 8, 8));

		// Token row
		JPanel tokenRow = new JPanel(new BorderLayout());
		tokenRow.setBackground(DARKER_BG);
		tokenRow.setBorder(new EmptyBorder(8, 10, 8, 10));
		tokenRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		tokenRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel tokenLabel = new JLabel("Tokens");
		tokenLabel.setForeground(GOLD);
		tokenLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		tokenRow.add(tokenLabel, BorderLayout.WEST);
		tokenValueLabel = new JLabel("0");
		tokenValueLabel.setForeground(Color.WHITE);
		tokenValueLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		tokenValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		tokenRow.add(tokenValueLabel, BorderLayout.EAST);
		dashCard.add(tokenRow);
		dashCard.add(Box.createVerticalStrut(12));

		JLabel tasksTitle = new JLabel("Daily Tasks");
		tasksTitle.setForeground(GOLD);
		tasksTitle.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		tasksTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		dashCard.add(tasksTitle);
		dashCard.add(Box.createVerticalStrut(6));

		tasksPanel = new JPanel();
		tasksPanel.setLayout(new BoxLayout(tasksPanel, BoxLayout.Y_AXIS));
		tasksPanel.setBackground(DARK_BG);
		tasksPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel tasksLoading = new JLabel("Loading...");
		tasksLoading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tasksLoading.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
		tasksPanel.add(tasksLoading);
		dashCard.add(tasksPanel);

		cardPanel.add(dashCard, "dashboard");

		// ── Market card ──
		JPanel marketCard = new JPanel();
		marketCard.setLayout(new BoxLayout(marketCard, BoxLayout.Y_AXIS));
		marketCard.setBackground(DARK_BG);
		marketCard.setBorder(new EmptyBorder(10, 8, 8, 8));

		// Market token row
		JPanel mktTokenRow = new JPanel(new BorderLayout());
		mktTokenRow.setBackground(DARKER_BG);
		mktTokenRow.setBorder(new EmptyBorder(8, 10, 8, 10));
		mktTokenRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		mktTokenRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel mktTokenLabel = new JLabel("Tokens");
		mktTokenLabel.setForeground(GOLD);
		mktTokenLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		mktTokenRow.add(mktTokenLabel, BorderLayout.WEST);
		marketTokenValueLabel = new JLabel("0");
		marketTokenValueLabel.setForeground(Color.WHITE);
		marketTokenValueLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(15f));
		marketTokenValueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		mktTokenRow.add(marketTokenValueLabel, BorderLayout.EAST);
		marketCard.add(mktTokenRow);
		marketCard.add(Box.createVerticalStrut(10));

		marketGrid = new JPanel(new GridLayout(0, 2, 6, 6));
		marketGrid.setBackground(DARK_BG);
		marketGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		JLabel marketLoading = new JLabel("Loading cases...");
		marketLoading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		marketLoading.setFont(FontManager.getRunescapeSmallFont().deriveFont(14f));
		marketGrid.add(marketLoading);

		JPanel marketGridWrap = new JPanel(new BorderLayout());
		marketGridWrap.setBackground(DARK_BG);
		marketGridWrap.add(marketGrid, BorderLayout.NORTH);
		JScrollPane marketScroll = new JScrollPane(marketGridWrap);
		marketScroll.setBackground(DARK_BG);
		marketScroll.setBorder(null);
		marketScroll.getViewport().setBackground(DARK_BG);
		marketScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		marketCard.add(marketScroll);

		cardPanel.add(marketCard, "market");

		// ── Clan card (formerly Settings — settings moved to gear icon) ──
		JPanel settingsCard = new JPanel();
		settingsCard.setLayout(new BoxLayout(settingsCard, BoxLayout.Y_AXIS));
		settingsCard.setBackground(DARK_BG);
		settingsCard.setBorder(new EmptyBorder(10, 8, 8, 8));

		settingsDesc = new JTextArea();
		settingsDesc.setWrapStyleWord(true);
		settingsDesc.setLineWrap(true);
		settingsDesc.setEditable(false);
		settingsDesc.setFocusable(false);
		settingsDesc.setOpaque(false);
		settingsDesc.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		settingsDesc.setFont(FontManager.getRunescapeSmallFont().deriveFont(14f));
		settingsDesc.setAlignmentX(Component.LEFT_ALIGNMENT);
		settingsDesc.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		settingsCard.add(settingsDesc);
		settingsCard.add(Box.createVerticalStrut(8));

		settingsStatus = new JLabel();
		settingsStatus.setFont(FontManager.getRunescapeBoldFont().deriveFont(14f));
		settingsStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		settingsStatus.setVisible(false);
		settingsCard.add(settingsStatus);
		settingsCard.add(Box.createVerticalStrut(6));

		codePanel = new JPanel();
		codePanel.setLayout(new BoxLayout(codePanel, BoxLayout.Y_AXIS));
		codePanel.setBackground(DARKER_BG);
		codePanel.setBorder(new EmptyBorder(12, 10, 12, 10));
		codePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		codePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
		codeLabel = new JLabel();
		codeLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(28f));
		codeLabel.setForeground(new Color(0, 200, 83));
		codeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		codePanel.add(codeLabel);
		codePanel.add(Box.createVerticalStrut(4));
		timerLabel = new JLabel();
		timerLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(13f));
		timerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		codePanel.add(timerLabel);
		codePanel.setVisible(false);
		settingsCard.add(codePanel);
		settingsCard.add(Box.createVerticalStrut(8));

		generateButton = new JButton("Generate Code");
		generateButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		generateButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		generateButton.setFocusPainted(false);
		generateButton.addActionListener(e -> onGenerateCode());
		generateButton.setVisible(false);
		settingsCard.add(generateButton);
		settingsCard.add(Box.createVerticalStrut(6));

		unlinkButton = new JButton("Unlink Account");
		unlinkButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		unlinkButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		unlinkButton.setFocusPainted(false);
		unlinkButton.setBackground(new Color(180, 60, 60));
		unlinkButton.setForeground(Color.WHITE);
		unlinkButton.addActionListener(e -> onUnlink());
		unlinkButton.setVisible(false);
		settingsCard.add(unlinkButton);

		cardPanel.add(settingsCard, "settings");

		// ── Clan card ──
		JPanel clanCard = new JPanel();
		clanCard.setLayout(new BoxLayout(clanCard, BoxLayout.Y_AXIS));
		clanCard.setBackground(DARK_BG);
		clanCard.setBorder(new EmptyBorder(20, 12, 8, 12));
		JLabel clanTitle = new JLabel("Clan");
		clanTitle.setForeground(GOLD);
		clanTitle.setFont(FontManager.getRunescapeBoldFont().deriveFont(18f));
		clanTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		clanCard.add(clanTitle);
		clanCard.add(Box.createVerticalStrut(10));
		JLabel clanHint = new JLabel("<html><font color='#aaa'>Clan features coming soon.<br><br>Manage your clan, view members,<br>and track events — all from<br>within the plugin.</font></html>");
		clanHint.setFont(FontManager.getRunescapeSmallFont().deriveFont(16f));
		clanHint.setAlignmentX(Component.LEFT_ALIGNMENT);
		clanCard.add(clanHint);
		cardPanel.add(clanCard, "clan");

		// Logged-out overlay
		loggedOutPanel = new JPanel(new BorderLayout());
		loggedOutPanel.setBackground(DARK_BG);
		loggedOutPanel.setBorder(new EmptyBorder(40, 20, 40, 20));
		JLabel loggedOutLabel = new JLabel("<html><center>Log in to your OSRS<br>account to get started.</center></html>");
		loggedOutLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		loggedOutLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(16f));
		loggedOutLabel.setHorizontalAlignment(SwingConstants.CENTER);
		loggedOutPanel.add(loggedOutLabel, BorderLayout.CENTER);
		cardPanel.add(loggedOutPanel, "loggedout");

		center.add(cardPanel, BorderLayout.CENTER);

		// ── Bottom bar: Inventory button + Settings gear ──
		JPanel bottomPanel = new JPanel(new BorderLayout(6, 0));
		bottomPanel.setBackground(DARK_BG);
		bottomPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
		inventoryButton = new JButton("Inventory");
		inventoryButton.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		inventoryButton.setFocusPainted(false);
		inventoryButton.setBackground(DARKER_BG);
		inventoryButton.setForeground(Color.WHITE);
		inventoryButton.setBorder(BorderFactory.createCompoundBorder(
			new LineBorder(new Color(60, 60, 60), 1),
			new EmptyBorder(10, 0, 10, 0)
		));
		inventoryButton.addActionListener(e -> openInventoryDialog());
		bottomPanel.add(inventoryButton, BorderLayout.CENTER);

		JButton gearBtn = new JButton("⚙");
		gearBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
		gearBtn.setForeground(new Color(150, 150, 150));
		gearBtn.setBackground(DARKER_BG);
		gearBtn.setFocusPainted(false);
		gearBtn.setBorderPainted(false);
		gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		gearBtn.setPreferredSize(new Dimension(34, 34));
		gearBtn.setToolTipText("Settings");
		gearBtn.addActionListener(e -> switchTab("settings"));
		bottomPanel.add(gearBtn, BorderLayout.EAST);
		center.add(bottomPanel, BorderLayout.SOUTH);

		add(center, BorderLayout.CENTER);

		// Tab click handlers
		tabDashboard.addActionListener(e -> switchTab("dashboard"));
		tabMarket.addActionListener(e -> switchTab("market"));
		tabSettings.addActionListener(e -> switchTab("clan"));

		// Start in logged-out state
		showLoggedOut();
	}

	// ── Tab helpers ────────────────────────────────────────────────────────────

	private JButton makeTabButton(String text)
	{
		JButton btn = new JButton(text);
		btn.setFont(FontManager.getRunescapeSmallFont().deriveFont(14f));
		btn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		btn.setBackground(DARKER_BG);
		btn.setFocusPainted(false);
		btn.setBorder(new EmptyBorder(6, 2, 6, 2));
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return btn;
	}

	private void switchTab(String tab)
	{
		if (!loggedIn)
		{
			return;
		}
		activeTab = tab;
		cardLayout.show(cardPanel, tab);
		updateTabStyles();

		if ("dashboard".equals(tab)) loadDashboard();
		if ("market".equals(tab)) loadMarket();
	}

	private void updateTabStyles()
	{
		for (JButton btn : new JButton[]{tabDashboard, tabMarket, tabSettings})
		{
			btn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			btn.setBorder(new EmptyBorder(6, 2, 6, 2));
		}
		JButton active = "dashboard".equals(activeTab) ? tabDashboard
			: "market".equals(activeTab) ? tabMarket : tabSettings;
		active.setForeground(GOLD);
		active.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 2, 0, GOLD),
			new EmptyBorder(6, 2, 4, 2)
		));
	}

	// ── State transitions ──────────────────────────────────────────────────────

	private void showLoggedOut()
	{
		loggedIn = false;
		SwingUtilities.invokeLater(() ->
		{
			cardLayout.show(cardPanel, "loggedout");
			inventoryButton.setEnabled(false);
			updateTabStyles();
		});
	}

	private void showLoggedIn()
	{
		loggedIn = true;
		SwingUtilities.invokeLater(() ->
		{
			inventoryButton.setEnabled(true);
			switchTab("dashboard");
			updateSettingsTab();
		});
	}

	private void updateSettingsTab()
	{
		SwingUtilities.invokeLater(() ->
		{
			if (linked)
			{
				settingsDesc.setText("Your OSRS account has been successfully linked to Discord via Scaper.");
				settingsStatus.setText("<html><font color='#00c853'>\u2713 Successfully Linked</font></html>");
				settingsStatus.setVisible(true);
				codePanel.setVisible(false);
				generateButton.setVisible(false);
				unlinkButton.setVisible(true);
				unlinkButton.setEnabled(true);
				unlinkButton.setText("Unlink Account");
			}
			else
			{
				settingsDesc.setText(
					"Link your Discord account to enable trading with other players.\n\n" +
					"Press the button below to generate a code, then enter it on Scaper.icu or use the /link command in Discord.\n\n" +
					"Your code will expire after 5 minutes."
				);
				settingsStatus.setVisible(false);
				generateButton.setText("Generate Code");
				generateButton.setEnabled(true);
				generateButton.setVisible(true);
				unlinkButton.setVisible(false);
			}
		});
	}

	// ── Lifecycle events ───────────────────────────────────────────────────────

	public void onLogin()
	{
		if (client.getLocalPlayer() != null)
		{
			cachedRsn = client.getLocalPlayer().getName();
		}
		if (cachedRsn == null)
		{
			scheduler.schedule(() ->
			{
				if (client.getLocalPlayer() != null)
				{
					cachedRsn = client.getLocalPlayer().getName();
				}
				checkLinkStatus();
			}, 2, TimeUnit.SECONDS);
		}
		else
		{
			checkLinkStatus();
		}
	}

	public void onLogout()
	{
		cachedRsn = null;
		linked = false;
		stopCountdown();
		stopPolling();
		showLoggedOut();
	}

	public void shutdown()
	{
		stopCountdown();
		stopPolling();
		scheduler.shutdownNow();
	}

	// ── Dashboard loading ──────────────────────────────────────────────────────

	private void loadDashboard()
	{
		String rsn = cachedRsn;
		if (rsn == null) return;

		CompletableFuture.runAsync(() ->
		{
			try
			{
				String url = buildUrl("/api/plugin/dashboard?rsn=" + URLEncoder.encode(rsn, "UTF-8"));
				Request request = new Request.Builder().url(url).get().build();
				try (Response response = httpClient.newCall(request).execute())
				{
					if (!response.isSuccessful()) return;
					String body = response.body() != null ? response.body().string() : "";
					JsonObject data = new JsonParser().parse(body).getAsJsonObject();
					int tokens = data.has("tokens") ? data.get("tokens").getAsInt() : 0;
					JsonArray tasks = data.has("tasks") ? data.getAsJsonArray("tasks") : new JsonArray();
					boolean isLinked = data.has("linked") && data.get("linked").getAsBoolean();

					// Auto-enroll if any tasks are not enrolled
					boolean needsEnroll = false;
					for (JsonElement el : tasks) {
						JsonObject t = el.getAsJsonObject();
						if (!t.has("enrolled") || !t.get("enrolled").getAsBoolean()) {
							needsEnroll = true;
							break;
						}
					}
					if (isLinked && needsEnroll) {
						try {
							String enrollUrl = buildUrl("/api/plugin/tasks/enroll");
							String enrollJson = "{\"rsn\":\"" + rsn + "\"}";
							RequestBody enrollBody = RequestBody.create(JSON_TYPE, enrollJson);
							Request enrollReq = new Request.Builder().url(enrollUrl).post(enrollBody).build();
							httpClient.newCall(enrollReq).execute().close();
							// Reload dashboard after enrollment
							loadDashboard();
							return;
						} catch (Exception ignored) {}
					}

					SwingUtilities.invokeLater(() ->
					{
						tokenValueLabel.setText(String.format("%,d", tokens));
						marketTokenValueLabel.setText(String.format("%,d", tokens));
						tasksPanel.removeAll();
						if (!isLinked)
						{
							JLabel hint = new JLabel("<html><font color='#888'>Link your Discord account<br>in Settings to earn tokens.</font></html>");
							hint.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
							tasksPanel.add(hint);
						}
						else if (tasks.size() == 0)
						{
							JLabel noTasks = new JLabel("No daily tasks available.");
							noTasks.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
							noTasks.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
							tasksPanel.add(noTasks);
						}
						else
						{
							for (JsonElement el : tasks)
							{
								JsonObject t = el.getAsJsonObject();
								String taskId = t.has("id") ? t.get("id").getAsString() : "";
								String label = t.has("label") ? t.get("label").getAsString() : "Task";
								String difficulty = t.has("difficulty") ? t.get("difficulty").getAsString() : "easy";
								String type = t.has("type") ? t.get("type").getAsString() : "";
								int amount = t.has("amount") ? t.get("amount").getAsInt() : 1;
								int taskTokens = t.has("tokens") ? t.get("tokens").getAsInt() : 0;
								boolean enrolled = t.has("enrolled") && t.get("enrolled").getAsBoolean();
								boolean claimed = t.has("claimed") && t.get("claimed").getAsBoolean();
								int progress = (t.has("progress") && !t.get("progress").isJsonNull()) ? t.get("progress").getAsInt() : 0;
								boolean complete = enrolled && progress >= amount;

								// Difficulty styling
								Color diffColor = difficulty.equals("hard") ? new Color(244, 67, 54) :
								                  difficulty.equals("medium") ? new Color(255, 152, 0) :
								                  new Color(76, 175, 80);
								Color diffBg = difficulty.equals("hard") ? new Color(244, 67, 54, 25) :
								               difficulty.equals("medium") ? new Color(255, 152, 0, 25) :
								               new Color(76, 175, 80, 25);
								String diffLabel = difficulty.toUpperCase();

								// Bar colors — gradient feel via two-tone
								Color barFg = complete ? new Color(76, 175, 80) :
								             difficulty.equals("hard") ? new Color(244, 67, 54) :
								             difficulty.equals("medium") ? new Color(255, 152, 0) :
								             new Color(212, 160, 23);
								Color barBg = new Color(20, 20, 20);

								// Format progress text
								String progressText;
								if (!enrolled) {
									progressText = "Waiting for snapshot...";
								} else if (claimed) {
									progressText = "\u2713 Claimed!";
								} else if (type.equals("xp_gain")) {
									progressText = formatXp(progress) + " / " + formatXp(amount) + " XP";
								} else {
									progressText = progress + " / " + amount + " kills";
								}

								// Percentage
								int pct = amount > 0 ? Math.min(100, (int)((long)progress * 100 / amount)) : 0;

								// === Card panel ===
								JPanel card = new JPanel();
								card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
								card.setBackground(claimed ? new Color(20, 28, 20) : new Color(24, 24, 24));
								card.setBorder(BorderFactory.createCompoundBorder(
									new LineBorder(claimed ? new Color(76, 175, 80, 60) : new Color(50, 50, 50), 1, true),
									new EmptyBorder(8, 10, 8, 10)
								));
								card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
								card.setAlignmentX(Component.LEFT_ALIGNMENT);

								// --- Top row: difficulty badge + task name ---
								JPanel topRow = new JPanel(new BorderLayout(6, 0));
								topRow.setBackground(card.getBackground());
								topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

								JLabel badge = new JLabel(" " + diffLabel + " ");
								badge.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 10f));
								badge.setForeground(diffColor);
								badge.setBackground(diffBg);
								badge.setOpaque(true);
								badge.setBorder(BorderFactory.createCompoundBorder(
									new LineBorder(new Color(diffColor.getRed(), diffColor.getGreen(), diffColor.getBlue(), 80), 1, true),
									new EmptyBorder(1, 4, 1, 4)
								));

								JLabel nameLabel = new JLabel(label);
								nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 14f));
								nameLabel.setForeground(claimed ? new Color(76, 175, 80) : new Color(230, 230, 230));

								JPanel badgeWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
								badgeWrap.setBackground(card.getBackground());
								badgeWrap.add(badge);
								badgeWrap.add(nameLabel);
								topRow.add(badgeWrap, BorderLayout.CENTER);

								// Token reward label on the right
								JLabel tokenLabel = new JLabel("+" + taskTokens);
								tokenLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 12f));
								tokenLabel.setForeground(new Color(212, 160, 23));
								topRow.add(tokenLabel, BorderLayout.EAST);

								card.add(topRow);
								card.add(Box.createVerticalStrut(4));

								// --- Progress bar ---
								if (enrolled && !claimed) {
									JProgressBar bar = new JProgressBar(0, Math.max(amount, 1));
									bar.setValue(Math.min(progress, amount));
									bar.setPreferredSize(new Dimension(0, 10));
									bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
									bar.setForeground(barFg);
									bar.setBackground(barBg);
									bar.setBorderPainted(false);
									bar.setStringPainted(false);
									bar.setAlignmentX(Component.LEFT_ALIGNMENT);
									card.add(bar);
									card.add(Box.createVerticalStrut(3));
								}

								// --- Bottom row: progress text + claim button ---
								JPanel bottomRow = new JPanel(new BorderLayout(4, 0));
								bottomRow.setBackground(card.getBackground());
								bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);

								String statusHtml;
								if (claimed) {
									statusHtml = "<html><font color='#4caf50'><b>\u2713 Claimed!</b></font></html>";
								} else if (!enrolled) {
									statusHtml = "<html><font color='#888'>Waiting for snapshot...</font></html>";
								} else {
									String pctColor = complete ? "#4caf50" : "#aaa";
									statusHtml = "<html><font color='#ccc'>" + progressText + "</font> <font color='" + pctColor + "'>(" + pct + "%)</font></html>";
								}
								JLabel statusLabel = new JLabel(statusHtml);
								statusLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(13f));
								bottomRow.add(statusLabel, BorderLayout.CENTER);

								// Claim button
								if (enrolled && complete && !claimed) {
									JButton claimBtn = new JButton("Claim");
									claimBtn.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 11f));
									claimBtn.setForeground(new Color(20, 20, 20));
									claimBtn.setBackground(new Color(212, 160, 23));
									claimBtn.setFocusPainted(false);
									claimBtn.setBorderPainted(false);
									claimBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
									claimBtn.setPreferredSize(new Dimension(60, 22));
									claimBtn.addActionListener(e -> claimTask(taskId));
									bottomRow.add(claimBtn, BorderLayout.EAST);
								}

								card.add(bottomRow);

								tasksPanel.add(card);
								tasksPanel.add(Box.createVerticalStrut(6));
							}
						}
						tasksPanel.revalidate();
						tasksPanel.repaint();
					});
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to load dashboard", e);
			}
		});
	}

	// ── Market loading ─────────────────────────────────────────────────────────

	private void loadMarket()
	{
		CompletableFuture.runAsync(() ->
		{
			try
			{
				String url = buildUrl("/api/market/cases");
				Request request = new Request.Builder().url(url).get().build();
				try (Response response = httpClient.newCall(request).execute())
				{
					if (!response.isSuccessful()) return;
					String body = response.body() != null ? response.body().string() : "";
					JsonObject data = new JsonParser().parse(body).getAsJsonObject();
					JsonArray cases = data.has("cases") ? data.getAsJsonArray("cases") : new JsonArray();

					SwingUtilities.invokeLater(() ->
					{
						marketGrid.removeAll();
						if (cases.size() == 0)
						{
							JLabel empty = new JLabel("No cases available.");
							empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
							empty.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
							marketGrid.add(empty);
						}
						else
						{
							for (JsonElement el : cases)
							{
								JsonObject c = el.getAsJsonObject();
								String id = c.get("id").getAsString();
								String name = c.has("name") ? c.get("name").getAsString() : id;
								int cost = c.has("cost") ? c.get("cost").getAsInt() : 0;
								String thumb = c.has("thumbnail") && !c.get("thumbnail").isJsonNull() ? c.get("thumbnail").getAsString() : "";
								String closedImg = c.has("image") && !c.get("image").isJsonNull() ? c.get("image").getAsString() : thumb;
								String openImg = c.has("imageOpen") && !c.get("imageOpen").isJsonNull() ? c.get("imageOpen").getAsString() : closedImg;

								JPanel card = new JPanel();
								card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
								card.setBackground(DARKER_BG);
								card.setBorder(BorderFactory.createCompoundBorder(
									new LineBorder(new Color(50, 50, 50), 1),
									new EmptyBorder(8, 8, 8, 8)
								));
								card.setPreferredSize(new Dimension(100, 130));
								card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
								card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

								// Thumbnail image
								JLabel imgLabel = new JLabel();
								imgLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
								imgLabel.setPreferredSize(new Dimension(64, 64));
								imgLabel.setHorizontalAlignment(SwingConstants.CENTER);
								card.add(imgLabel);
								loadImageIcon(thumb, 64, icon -> imgLabel.setIcon(icon));

								card.add(Box.createVerticalStrut(4));

								JLabel nameLabel = new JLabel(name);
								nameLabel.setForeground(Color.WHITE);
								nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(13f));
								nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
								nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
								card.add(nameLabel);

								JLabel priceLabel = new JLabel(cost == 0 ? "Free" : String.format("%,d", cost));
								priceLabel.setForeground(GOLD);
								priceLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
								priceLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
								card.add(priceLabel);

								final String caseId = id;
								final String caseName = name;
								final String ci = closedImg;
								final String oi = openImg;
								card.addMouseListener(new java.awt.event.MouseAdapter()
								{
									@Override
									public void mouseClicked(java.awt.event.MouseEvent e)
									{
										buyAndOpenCase(caseId, caseName, ci, oi);
									}
								});

								marketGrid.add(card);
							}
						}
						marketGrid.revalidate();
						marketGrid.repaint();
					});
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to load market", e);
			}
		});
	}

	private void buyAndOpenCase(String caseId, String caseName, String closedImg, String openImg)
	{
		String rsn = cachedRsn;
		if (rsn == null) return;

		CompletableFuture.runAsync(() ->
		{
			try
			{
				JsonObject body = new JsonObject();
				body.addProperty("rsn", rsn);
				body.addProperty("caseId", caseId);

				Request request = new Request.Builder()
					.url(buildUrl("/api/cases/buy-and-open"))
					.post(RequestBody.create(JSON_TYPE, body.toString()))
					.build();

				try (Response response = httpClient.newCall(request).execute())
				{
					String respBody = response.body() != null ? response.body().string() : "";
					if (response.code() == 402)
					{
						SwingUtilities.invokeLater(() ->
							JOptionPane.showMessageDialog(ScaperPanel.this, "Not enough tokens!", "Scaper", JOptionPane.WARNING_MESSAGE)
						);
						return;
					}
					if (response.code() == 403)
					{
						SwingUtilities.invokeLater(() ->
							JOptionPane.showMessageDialog(ScaperPanel.this, "Link your account in Settings first.", "Scaper", JOptionPane.WARNING_MESSAGE)
						);
						return;
					}
					if (!response.isSuccessful())
					{
						log.warn("Buy-and-open failed: {}", respBody);
						return;
					}

					// Update token display
					JsonObject data = new JsonParser().parse(respBody).getAsJsonObject();
					if (data.has("balance"))
					{
						int balance = data.get("balance").getAsInt();
						SwingUtilities.invokeLater(() -> tokenValueLabel.setText(String.format("%,d", balance)));
					}

					// Trigger overlay
					SwingUtilities.invokeLater(() -> plugin.openCase(caseId, caseName, closedImg, openImg));
				}
			}
			catch (Exception e)
			{
				log.error("Buy-and-open error", e);
			}
		});
	}

	// ── Inventory dialog ───────────────────────────────────────────────────────

	private void openInventoryDialog()
	{
		String rsn = cachedRsn;
		if (rsn == null) return;

		CompletableFuture.runAsync(() ->
		{
			try
			{
				String url = buildUrl("/api/plugin/inventory?rsn=" + URLEncoder.encode(rsn, "UTF-8"));
				Request request = new Request.Builder().url(url).get().build();
				try (Response response = httpClient.newCall(request).execute())
				{
					if (!response.isSuccessful())
					{
						SwingUtilities.invokeLater(() ->
							JOptionPane.showMessageDialog(ScaperPanel.this, "Could not load inventory. Make sure your account is linked.", "Scaper", JOptionPane.ERROR_MESSAGE)
						);
						return;
					}
					String body = response.body() != null ? response.body().string() : "";
					JsonObject data = new JsonParser().parse(body).getAsJsonObject();
					JsonArray cases = data.has("cases") ? data.getAsJsonArray("cases") : new JsonArray();
					JsonArray stickers = data.has("stickers") ? data.getAsJsonArray("stickers") : new JsonArray();

					// Build combined item list for filtering/sorting
					java.util.List<JsonObject> allItems = new java.util.ArrayList<>();
					for (JsonElement el : cases) allItems.add(el.getAsJsonObject());
					for (JsonElement el : stickers) allItems.add(el.getAsJsonObject());

					SwingUtilities.invokeLater(() -> showInventoryFrame(allItems));
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to load inventory", e);
			}
		});
	}

	private void showInventoryFrame(java.util.List<JsonObject> allItems)
	{
		JFrame frame = new JFrame("Inventory");
		frame.setSize(420, 560);
		frame.setLocationRelativeTo(null);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.getContentPane().setBackground(DARK_BG);

		JPanel root = new JPanel(new BorderLayout(0, 6));
		root.setBackground(DARK_BG);
		root.setBorder(new EmptyBorder(8, 8, 8, 8));

		// ── Controls panel ──
		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setBackground(DARK_BG);

		// Row 1: Search + Sort + Rarity
		JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		row1.setBackground(DARK_BG);
		JLabel searchLbl = new JLabel("Search:");
		searchLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		searchLbl.setFont(FontManager.getRunescapeSmallFont().deriveFont(13f));
		row1.add(searchLbl);
		JTextField searchField = new JTextField(8);
		searchField.setBackground(DARKER_BG);
		searchField.setForeground(Color.WHITE);
		searchField.setCaretColor(Color.WHITE);
		searchField.setBorder(new LineBorder(new Color(60, 60, 60), 1));
		row1.add(searchField);

		JLabel sortLbl = new JLabel("Sort:");
		sortLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sortLbl.setFont(FontManager.getRunescapeSmallFont().deriveFont(13f));
		row1.add(sortLbl);
		JComboBox<String> sortBox = new JComboBox<>(new String[]{"Default", "A-Z", "Z-A", "Wear (low)", "Wear (high)", "Rarity"});
		sortBox.setBackground(DARKER_BG);
		sortBox.setForeground(Color.WHITE);
		row1.add(sortBox);

		JLabel rarLbl = new JLabel("Rarity:");
		rarLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rarLbl.setFont(FontManager.getRunescapeSmallFont().deriveFont(13f));
		row1.add(rarLbl);
		JComboBox<String> rarBox = new JComboBox<>(new String[]{"All", "Common", "Uncommon", "Rare", "Epic", "Legendary"});
		rarBox.setBackground(DARKER_BG);
		rarBox.setForeground(Color.WHITE);
		row1.add(rarBox);
		controls.add(row1);

		// Row 2: Filter toggles
		JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
		row2.setBackground(DARK_BG);
		JRadioButton allBtn = new JRadioButton("All", true);
		JRadioButton dupsBtn = new JRadioButton("Duplicates");
		JRadioButton holoBtn = new JRadioButton("Holo only");
		ButtonGroup filterGroup = new ButtonGroup();
		filterGroup.add(allBtn); filterGroup.add(dupsBtn); filterGroup.add(holoBtn);
		for (JRadioButton rb : new JRadioButton[]{allBtn, dupsBtn, holoBtn})
		{
			rb.setBackground(DARK_BG);
			rb.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			rb.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
			row2.add(rb);
		}
		controls.add(row2);

		// Row 3: Page info
		JLabel pageLabel = new JLabel();
		pageLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pageLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
		pageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		controls.add(pageLabel);

		root.add(controls, BorderLayout.NORTH);

		// ── Grid panel ──
		JPanel gridPanel = new JPanel(new GridLayout(0, 3, 4, 4));
		gridPanel.setBackground(DARK_BG);
		JPanel gridWrap = new JPanel(new BorderLayout());
		gridWrap.setBackground(DARK_BG);
		gridWrap.add(gridPanel, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(gridWrap);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(DARK_BG);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		root.add(scroll, BorderLayout.CENTER);

		frame.setContentPane(root);

		java.util.Map<String, Color> rarityColors = new java.util.LinkedHashMap<>();
		rarityColors.put("common", new Color(158, 158, 158));
		rarityColors.put("uncommon", new Color(76, 175, 80));
		rarityColors.put("rare", new Color(90, 173, 255));
		rarityColors.put("epic", new Color(206, 147, 216));
		rarityColors.put("legendary", new Color(255, 215, 0));

		// Rebuild grid based on filters
		Runnable rebuild = () ->
		{
			String query = searchField.getText().trim().toLowerCase();
			String sort = (String) sortBox.getSelectedItem();
			String rarFilter = ((String) rarBox.getSelectedItem()).toLowerCase();
			boolean dupsOnly = dupsBtn.isSelected();
			boolean holoOnly = holoBtn.isSelected();

			java.util.List<JsonObject> filtered = new java.util.ArrayList<>();
			java.util.Map<String, Integer> nameCounts = new java.util.LinkedHashMap<>();
			// Group cases by item_id, keep stickers individual
			java.util.Map<String, JsonObject> caseGrouped = new java.util.LinkedHashMap<>();
			java.util.Map<String, Integer> caseCounts = new java.util.LinkedHashMap<>();
			java.util.List<JsonObject> stickerItems = new java.util.ArrayList<>();
			for (JsonObject it : allItems)
			{
				String type = getStr(it, "item_type");
				String id = getStr(it, "item_id");
				String n = getStr(it, "item_name");
				nameCounts.merge(n, 1, Integer::sum);
				if ("case".equals(type))
				{
					caseCounts.merge(id, 1, Integer::sum);
					caseGrouped.putIfAbsent(id, it);
				}
				else
				{
					stickerItems.add(it);
				}
			}
			// Build display list: grouped cases first, then individual stickers
			java.util.List<JsonObject> displayItems = new java.util.ArrayList<>();
			for (java.util.Map.Entry<String, JsonObject> entry : caseGrouped.entrySet())
			{
				JsonObject copy = entry.getValue().deepCopy();
				copy.addProperty("_qty", caseCounts.get(entry.getKey()));
				displayItems.add(copy);
			}
			displayItems.addAll(stickerItems);

			for (JsonObject it : displayItems)
			{
				String name = it.has("item_name") && !it.get("item_name").isJsonNull() ? it.get("item_name").getAsString() : "";
				String rarity = it.has("item_rarity") && !it.get("item_rarity").isJsonNull() ? it.get("item_rarity").getAsString() : "common";
				int isHolo = it.has("is_holo") && !it.get("is_holo").isJsonNull() ? it.get("is_holo").getAsInt() : 0;

				if (!query.isEmpty() && !name.toLowerCase().contains(query)) continue;
				if (!"all".equals(rarFilter) && !rarity.equals(rarFilter)) continue;
				if (dupsOnly && nameCounts.getOrDefault(name, 0) < 2) continue;
				if (holoOnly && isHolo != 1) continue;
				filtered.add(it);
			}

			// Sort
			if ("A-Z".equals(sort))
				filtered.sort((a, b) -> getStr(a, "item_name").compareToIgnoreCase(getStr(b, "item_name")));
			else if ("Z-A".equals(sort))
				filtered.sort((a, b) -> getStr(b, "item_name").compareToIgnoreCase(getStr(a, "item_name")));
			else if ("Wear (low)".equals(sort))
				filtered.sort((a, b) -> getInt(a, "wear_hundredths", 9999) - getInt(b, "wear_hundredths", 9999));
			else if ("Wear (high)".equals(sort))
				filtered.sort((a, b) -> getInt(b, "wear_hundredths", -1) - getInt(a, "wear_hundredths", -1));
			else if ("Rarity".equals(sort))
			{
				java.util.Map<String, Integer> ro = java.util.Map.of("legendary", 0, "epic", 1, "rare", 2, "uncommon", 3, "common", 4);
				filtered.sort((a, b) -> ro.getOrDefault(getStr(a, "item_rarity"), 9) - ro.getOrDefault(getStr(b, "item_rarity"), 9));
			}

			pageLabel.setText(filtered.size() + " items");

			gridPanel.removeAll();
			for (JsonObject it : filtered)
			{
				String name = getStr(it, "item_name");
				String rarity = getStr(it, "item_rarity");
				if (rarity.isEmpty()) rarity = "common";
				String img = getStr(it, "item_image");
				String type = getStr(it, "item_type");
				int wearH = getInt(it, "wear_hundredths", -1);
				int isHolo = it.has("is_holo") && !it.get("is_holo").isJsonNull() ? it.get("is_holo").getAsInt() : 0;
				int qty = it.has("_qty") ? it.get("_qty").getAsInt() : 0;
				Color rc = rarityColors.getOrDefault(rarity, rarityColors.get("common"));

				JPanel tile = new JPanel();
				tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
				tile.setBackground(DARKER_BG);
				tile.setBorder(new LineBorder(rc, 1));
				tile.setPreferredSize(new Dimension(100, 110));

				JLabel imgLabel = new JLabel();
				imgLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
				imgLabel.setPreferredSize(new Dimension(52, 52));
				imgLabel.setHorizontalAlignment(SwingConstants.CENTER);
				tile.add(imgLabel);
				loadImageIcon(img, 52, icon -> imgLabel.setIcon(icon));

				JLabel nameLabel = new JLabel("<html><center>" + name + (qty > 1 ? " x" + qty : "") + "</center></html>");
				nameLabel.setForeground(Color.WHITE);
				nameLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
				nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
				nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
				tile.add(nameLabel);

				if (wearH >= 0)
				{
					JLabel wearLabel = new JLabel(String.format("%.2f", wearH / 100.0));
					wearLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					wearLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
					wearLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
					tile.add(wearLabel);
				}

				if (isHolo == 1)
				{
					JLabel holoLabel = new JLabel("Holo");
					holoLabel.setForeground(new Color(255, 215, 0));
					holoLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
					holoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
					tile.add(holoLabel);
				}

				gridPanel.add(tile);
			}
			gridPanel.revalidate();
			gridPanel.repaint();
		};

		// Wire up filter actions
		searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
		});
		sortBox.addActionListener(e -> rebuild.run());
		rarBox.addActionListener(e -> rebuild.run());
		allBtn.addActionListener(e -> rebuild.run());
		dupsBtn.addActionListener(e -> rebuild.run());
		holoBtn.addActionListener(e -> rebuild.run());

		rebuild.run();
		frame.setVisible(true);
	}

	private static String getStr(JsonObject obj, String key)
	{
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
	}

	private static int getInt(JsonObject obj, String key, int def)
	{
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : def;
	}

	// ── Code generation ────────────────────────────────────────────────────────

	private void onGenerateCode()
	{
		String rsn = cachedRsn;
		if (rsn == null)
		{
			settingsStatus.setText("<html><font color='#ff5252'>Could not detect your player name.</font></html>");
			settingsStatus.setVisible(true);
			return;
		}

		generateButton.setEnabled(false);
		generateButton.setText("Generating...");
		settingsStatus.setVisible(false);

		CompletableFuture.supplyAsync(() ->
		{
			try
			{
				JsonObject body = new JsonObject();
				body.addProperty("rsn", rsn);
				Request request = new Request.Builder()
					.url(buildUrl("/api/generate-code"))
					.post(RequestBody.create(JSON_TYPE, body.toString()))
					.build();
				try (Response response = httpClient.newCall(request).execute())
				{
					if (!response.isSuccessful()) throw new RuntimeException("Server returned " + response.code());
					return new JsonParser().parse(response.body().string()).getAsJsonObject();
				}
			}
			catch (Exception e)
			{
				log.error("Failed to generate code", e);
				return (JsonObject) null;
			}
		}).thenAccept(result ->
		{
			if (result == null)
			{
				SwingUtilities.invokeLater(() ->
				{
					settingsStatus.setText("<html><font color='#ff5252'>Could not connect to server.</font></html>");
					settingsStatus.setVisible(true);
					generateButton.setEnabled(true);
					generateButton.setText("Generate Code");
				});
				return;
			}
			String code = result.get("code").getAsString();
			long expiresAt = result.get("expiresAt").getAsLong();
			SwingUtilities.invokeLater(() ->
			{
				codeLabel.setText(code);
				codePanel.setVisible(true);
				generateButton.setText("Generate New Code");
				generateButton.setEnabled(true);
				startCountdown(expiresAt);
				startPolling();
			});
		});
	}

	private void onUnlink()
	{
		String rsn = cachedRsn;
		if (rsn == null) return;
		unlinkButton.setEnabled(false);
		unlinkButton.setText("Unlinking...");

		CompletableFuture.supplyAsync(() ->
		{
			try
			{
				JsonObject body = new JsonObject();
				body.addProperty("rsn", rsn);
				Request request = new Request.Builder()
					.url(buildUrl("/api/unlink"))
					.post(RequestBody.create(JSON_TYPE, body.toString()))
					.build();
				try (Response response = httpClient.newCall(request).execute())
				{
					return response.isSuccessful();
				}
			}
			catch (Exception e)
			{
				log.error("Failed to unlink", e);
				return false;
			}
		}).thenAccept(success ->
		{
			if (success)
			{
				linked = false;
				updateSettingsTab();
			}
			else
			{
				SwingUtilities.invokeLater(() ->
				{
					unlinkButton.setEnabled(true);
					unlinkButton.setText("Unlink Account");
					settingsStatus.setText("<html><font color='#ff5252'>Failed to unlink.</font></html>");
					settingsStatus.setVisible(true);
				});
			}
		});
	}

	// ── Link status ────────────────────────────────────────────────────────────

	private void checkLinkStatus()
	{
		String rsn = cachedRsn;
		if (rsn == null)
		{
			showLoggedOut();
			return;
		}

		CompletableFuture.supplyAsync(() ->
		{
			try
			{
				String url = buildUrl("/api/link-status?rsn=" + URLEncoder.encode(rsn, "UTF-8"));
				Request request = new Request.Builder().url(url).get().build();
				try (Response response = httpClient.newCall(request).execute())
				{
					if (!response.isSuccessful()) return (JsonObject) null;
					return new JsonParser().parse(response.body().string()).getAsJsonObject();
				}
			}
			catch (Exception e)
			{
				log.warn("Could not check link status", e);
				return (JsonObject) null;
			}
		}).thenAccept(result ->
		{
			linked = result != null && result.has("linked") && result.get("linked").getAsBoolean();
			showLoggedIn();
		});
	}

	private void pollLinkStatus()
	{
		String rsn = cachedRsn;
		if (rsn == null || linked) return;
		try
		{
			String url = buildUrl("/api/link-status?rsn=" + URLEncoder.encode(rsn, "UTF-8"));
			Request request = new Request.Builder().url(url).get().build();
			try (Response response = httpClient.newCall(request).execute())
			{
				if (!response.isSuccessful()) return;
				JsonObject result = new JsonParser().parse(response.body().string()).getAsJsonObject();
				if (result.has("linked") && result.get("linked").getAsBoolean())
				{
					linked = true;
					updateSettingsTab();
				}
			}
		}
		catch (Exception e) { /* silent */ }
	}

	// ── Timer helpers ──────────────────────────────────────────────────────────

	private void startCountdown(long expiresAtMs)
	{
		stopCountdown();
		countdownFuture = scheduler.scheduleAtFixedRate(() ->
		{
			long remaining = expiresAtMs - System.currentTimeMillis();
			if (remaining <= 0)
			{
				SwingUtilities.invokeLater(() ->
				{
					timerLabel.setText("Code expired");
					timerLabel.setForeground(new Color(255, 82, 82));
				});
				stopCountdown();
				return;
			}
			long minutes = remaining / 60000;
			long seconds = (remaining % 60000) / 1000;
			SwingUtilities.invokeLater(() ->
			{
				timerLabel.setText(String.format("Expires in %d:%02d", minutes, seconds));
				timerLabel.setForeground(remaining < 60000 ? new Color(255, 167, 38) : ColorScheme.LIGHT_GRAY_COLOR);
			});
		}, 0, 1, TimeUnit.SECONDS);
	}

	private void stopCountdown()
	{
		if (countdownFuture != null) { countdownFuture.cancel(false); countdownFuture = null; }
	}

	private void startPolling()
	{
		stopPolling();
		pollFuture = scheduler.scheduleAtFixedRate(this::pollLinkStatus, 5, 5, TimeUnit.SECONDS);
	}

	private void stopPolling()
	{
		if (pollFuture != null) { pollFuture.cancel(false); pollFuture = null; }
	}

	// ── Utility ────────────────────────────────────────────────────────────────

	private String buildUrl(String path)
	{
		return API_URL + path;
	}

	private String resolveImageUrl(String path)
	{
		if (path == null || path.isEmpty()) return "";
		if (path.startsWith("http")) return path;
		if (path.startsWith("/uploads/")) return API_URL + path;
		return SITE_URL + path;
	}

	private void loadImageIcon(String rawUrl, int size, java.util.function.Consumer<ImageIcon> callback)
	{
		String fullUrl = resolveImageUrl(rawUrl);
		if (fullUrl.isEmpty()) return;
		if (iconCache.containsKey(fullUrl + "@" + size))
		{
			callback.accept(iconCache.get(fullUrl + "@" + size));
			return;
		}
		CompletableFuture.runAsync(() ->
		{
			try
			{
				BufferedImage img = ImageIO.read(new URL(fullUrl));
				if (img != null)
				{
					Image scaled = img.getScaledInstance(size, size, Image.SCALE_SMOOTH);
					ImageIcon icon = new ImageIcon(scaled);
					iconCache.put(fullUrl + "@" + size, icon);
					SwingUtilities.invokeLater(() -> callback.accept(icon));
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to load image: {}", fullUrl);
			}
		});
	}

	private String formatXp(int xp) {
		if (xp >= 1000000) return String.format("%.1fM", xp / 1000000.0);
		if (xp >= 1000) return String.format("%.0fk", xp / 1000.0);
		return String.valueOf(xp);
	}

	private String toHex(Color c) {
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	private void claimTask(String taskId) {
		String rsn = cachedRsn;
		if (rsn == null || taskId == null || taskId.isEmpty()) return;
		CompletableFuture.runAsync(() -> {
			try {
				String url = buildUrl("/api/plugin/tasks/claim");
				String json = "{\"rsn\":\"" + rsn + "\",\"taskId\":\"" + taskId + "\"}";
				RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
				Request request = new Request.Builder().url(url).post(body).build();
				try (Response response = httpClient.newCall(request).execute()) {
					if (response.isSuccessful()) {
						SwingUtilities.invokeLater(() -> loadDashboard());
					}
				}
			} catch (Exception e) {
				log.warn("Failed to claim task {}", taskId, e);
			}
		});
	}
}
