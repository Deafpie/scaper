package com.scaperplugin;

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

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URLEncoder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ScaperPanel extends PluginPanel
{
	private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

	private static final String DESCRIPTION_NOT_LINKED =
		"Thank you for using Scaper!\n\n" +
		"If Scaper is in your clan's Discord already, you can use /link within " +
		"the Discord using Scaper, followed by your unique code.\n\n" +
		"Please press the button below to generate your code. Your code will " +
		"expire after 5 minutes. If the code doesn't work, click the generate " +
		"button again to get a new code.";

	private static final String DESCRIPTION_LINKED =
		"Your OSRS account has been successfully linked to Discord via Scaper.";

	private static final String DESCRIPTION_LOGGED_OUT =
		"Please log in to your OSRS account to use Scaper.";

	private final Client client;
	private final ScaperConfig config;
	private final OkHttpClient httpClient;

	// UI components
	private final JTextArea descriptionArea;
	private final JLabel statusLabel;
	private final JPanel codePanel;
	private final JLabel codeLabel;
	private final JLabel timerLabel;
	private final JButton generateButton;
	private final JButton unlinkButton;

	// State
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> countdownFuture;
	private ScheduledFuture<?> pollFuture;
	private volatile String cachedRsn;
	private volatile boolean linked;

	public ScaperPanel(Client client, ScaperConfig config, OkHttpClient httpClient)
	{
		super(false);
		this.client = client;
		this.config = config;
		this.httpClient = httpClient;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(10, 10, 10, 10));

		// ── Header ──
		JLabel title = new JLabel("Scaper");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(24f));
		title.setForeground(Color.WHITE);
		title.setHorizontalAlignment(SwingConstants.CENTER);
		title.setBorder(new EmptyBorder(0, 0, 10, 0));
		add(title, BorderLayout.NORTH);

		// ── Content ──
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Description text
		descriptionArea = new JTextArea();
		descriptionArea.setWrapStyleWord(true);
		descriptionArea.setLineWrap(true);
		descriptionArea.setEditable(false);
		descriptionArea.setFocusable(false);
		descriptionArea.setOpaque(false);
		descriptionArea.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		descriptionArea.setFont(FontManager.getRunescapeSmallFont().deriveFont(14f));
		descriptionArea.setAlignmentX(Component.LEFT_ALIGNMENT);
		descriptionArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		content.add(descriptionArea);
		content.add(Box.createVerticalStrut(10));

		// Status label (for success / error messages)
		statusLabel = new JLabel();
		statusLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		statusLabel.setBorder(new EmptyBorder(5, 0, 5, 0));
		statusLabel.setVisible(false);
		content.add(statusLabel);

		// Code display panel
		codePanel = new JPanel();
		codePanel.setLayout(new BoxLayout(codePanel, BoxLayout.Y_AXIS));
		codePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		codePanel.setBorder(new EmptyBorder(15, 10, 15, 10));
		codePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		codePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));

		codeLabel = new JLabel();
		codeLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(30f));
		codeLabel.setForeground(new Color(0, 200, 83));
		codeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		codePanel.add(codeLabel);

		codePanel.add(Box.createVerticalStrut(4));

		timerLabel = new JLabel();
		timerLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(12f));
		timerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		codePanel.add(timerLabel);

		codePanel.setVisible(false);
		content.add(codePanel);
		content.add(Box.createVerticalStrut(10));

		// Generate Code button
		generateButton = new JButton("Generate Code");
		generateButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		generateButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		generateButton.setFocusPainted(false);
		generateButton.addActionListener(e -> onGenerateCode());
		generateButton.setVisible(false);
		content.add(generateButton);

		content.add(Box.createVerticalStrut(6));

		// Unlink Account button
		unlinkButton = new JButton("Unlink Account");
		unlinkButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		unlinkButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		unlinkButton.setFocusPainted(false);
		unlinkButton.setBackground(new Color(180, 60, 60));
		unlinkButton.setForeground(Color.WHITE);
		unlinkButton.addActionListener(e -> onUnlink());
		unlinkButton.setVisible(false);
		content.add(unlinkButton);

		add(content, BorderLayout.CENTER);

		// Start in logged-out state
		showLoggedOut();
	}

	// ── State transitions ──────────────────────────────────────────────────────

	private void showLoggedOut()
	{
		SwingUtilities.invokeLater(() ->
		{
			descriptionArea.setText(DESCRIPTION_LOGGED_OUT);
			statusLabel.setVisible(false);
			codePanel.setVisible(false);
			generateButton.setVisible(false);
			unlinkButton.setVisible(false);
		});
	}

	private void showNotLinked()
	{
		SwingUtilities.invokeLater(() ->
		{
			descriptionArea.setText(DESCRIPTION_NOT_LINKED);
			statusLabel.setVisible(false);
			codePanel.setVisible(false);
			generateButton.setText("Generate Code");
			generateButton.setEnabled(true);
			generateButton.setVisible(true);
			unlinkButton.setVisible(false);
		});
	}

	private void showCodeGenerated(String code, long expiresAtMs)
	{
		SwingUtilities.invokeLater(() ->
		{
			descriptionArea.setText(DESCRIPTION_NOT_LINKED);
			statusLabel.setVisible(false);
			codeLabel.setText(code);
			codePanel.setVisible(true);
			generateButton.setText("Generate New Code");
			generateButton.setEnabled(true);
			generateButton.setVisible(true);
			unlinkButton.setVisible(false);

			startCountdown(expiresAtMs);
			startPolling();
		});
	}

	private void showLinked()
	{
		SwingUtilities.invokeLater(() ->
		{
			descriptionArea.setText(DESCRIPTION_LINKED);
			statusLabel.setText("<html><font color='#00c853'>\u2713 Successfully Linked</font></html>");
			statusLabel.setVisible(true);
			codePanel.setVisible(false);
			generateButton.setVisible(false);
			unlinkButton.setText("Unlink Account");
			unlinkButton.setEnabled(true);
			unlinkButton.setVisible(true);

			stopCountdown();
			stopPolling();
		});
	}

	private void showError(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			statusLabel.setText("<html><font color='#ff5252'>" + message + "</font></html>");
			statusLabel.setVisible(true);
			generateButton.setEnabled(true);
		});
	}

	// ── Lifecycle events ───────────────────────────────────────────────────────

	public void onLogin()
	{
		// Cache the RSN from the client thread
		if (client.getLocalPlayer() != null)
		{
			cachedRsn = client.getLocalPlayer().getName();
		}

		// The name might not be available immediately; retry once after a short delay
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

	// ── Button actions ─────────────────────────────────────────────────────────

	private void onGenerateCode()
	{
		String rsn = cachedRsn;
		if (rsn == null)
		{
			showError("Could not detect your player name. Please try again.");
			return;
		}

		generateButton.setEnabled(false);
		generateButton.setText("Generating...");
		statusLabel.setVisible(false);

		CompletableFuture.supplyAsync(() ->
		{
			try
			{
				String url = buildUrl("/api/generate-code");
				JsonObject body = new JsonObject();
				body.addProperty("rsn", rsn);

				Request request = new Request.Builder()
					.url(url)
					.post(RequestBody.create(JSON_TYPE, body.toString()))
					.build();

				try (Response response = httpClient.newCall(request).execute())
				{
					if (!response.isSuccessful())
					{
						throw new RuntimeException("Server returned " + response.code());
					}
					String respBody = response.body().string();
					JsonElement parsed = new JsonParser().parse(respBody);
					return parsed.getAsJsonObject();
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
				showError("Could not connect to Scaper server. Check API URL in plugin settings.");
				return;
			}

			String code = result.get("code").getAsString();
			long expiresAt = result.get("expiresAt").getAsLong();
			showCodeGenerated(code, expiresAt);
		});
	}

	private void onUnlink()
	{
		String rsn = cachedRsn;
		if (rsn == null)
		{
			return;
		}

		unlinkButton.setEnabled(false);
		unlinkButton.setText("Unlinking...");

		CompletableFuture.supplyAsync(() ->
		{
			try
			{
				String url = buildUrl("/api/unlink");
				JsonObject body = new JsonObject();
				body.addProperty("rsn", rsn);

				Request request = new Request.Builder()
					.url(url)
					.post(RequestBody.create(JSON_TYPE, body.toString()))
					.build();

				try (Response response = httpClient.newCall(request).execute())
				{
					return response.isSuccessful();
				}
			}
			catch (Exception e)
			{
				log.error("Failed to unlink account", e);
				return false;
			}
		}).thenAccept(success ->
		{
			if (success)
			{
				linked = false;
				showNotLinked();
			}
			else
			{
				SwingUtilities.invokeLater(() ->
				{
					unlinkButton.setEnabled(true);
					unlinkButton.setText("Unlink Account");
				});
				showError("Failed to unlink. Please try again.");
			}
		});
	}

	// ── Link status check ──────────────────────────────────────────────────────

	private void checkLinkStatus()
	{
		String rsn = cachedRsn;
		if (rsn == null)
		{
			showNotLinked();
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
					if (!response.isSuccessful())
					{
						return (JsonObject) null;
					}
					JsonElement parsed = new JsonParser().parse(response.body().string());
					return parsed.getAsJsonObject();
				}
			}
			catch (Exception e)
			{
				log.warn("Could not check link status", e);
				return (JsonObject) null;
			}
		}).thenAccept(result ->
		{
			if (result != null && result.has("linked") && result.get("linked").getAsBoolean())
			{
				linked = true;
				showLinked();
			}
			else
			{
				linked = false;
				showNotLinked();
			}
		});
	}

	/**
	 * Silent polling check — used after code generation to detect when the user runs /link.
	 */
	private void pollLinkStatus()
	{
		String rsn = cachedRsn;
		if (rsn == null || linked)
		{
			return;
		}

		try
		{
			String url = buildUrl("/api/link-status?rsn=" + URLEncoder.encode(rsn, "UTF-8"));
			Request request = new Request.Builder().url(url).get().build();

			try (Response response = httpClient.newCall(request).execute())
			{
				if (!response.isSuccessful())
				{
					return;
				}

				JsonElement parsed = new JsonParser().parse(response.body().string());
				JsonObject result = parsed.getAsJsonObject();
				if (result.has("linked") && result.get("linked").getAsBoolean())
				{
					linked = true;
					showLinked();
				}
			}
		}
		catch (Exception e)
		{
			// Silently ignore polling errors
		}
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
				timerLabel.setForeground(remaining < 60000
					? new Color(255, 167, 38)   // orange under 1 min
					: ColorScheme.LIGHT_GRAY_COLOR);
			});
		}, 0, 1, TimeUnit.SECONDS);
	}

	private void stopCountdown()
	{
		if (countdownFuture != null)
		{
			countdownFuture.cancel(false);
			countdownFuture = null;
		}
	}

	private void startPolling()
	{
		stopPolling();
		pollFuture = scheduler.scheduleAtFixedRate(this::pollLinkStatus, 5, 5, TimeUnit.SECONDS);
	}

	private void stopPolling()
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(false);
			pollFuture = null;
		}
	}

	// ── Utility ────────────────────────────────────────────────────────────────

	private String buildUrl(String path)
	{
		String base = config.apiUrl().replaceAll("/+$", "");
		return base + path;
	}
}
